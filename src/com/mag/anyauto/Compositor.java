package com.mag.anyauto;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Puts the virtual display into the part of the screen surface the user can actually see.
 *
 * <p>A virtual display always fills its surface from the top-left corner, but Android Auto shows
 * only part of the surface it gives a navigation app: the dashboard's map card is a slice on the
 * right (e.g. x 792–1178 of 1190), and in full screen the action strip covers the top. So the
 * display draws into {@link #input()} (a SurfaceTexture of the content size), and every frame is
 * drawn into the content rect of the screen surface with OpenGL ES 2; the rest stays black.
 * One copy per frame on the GPU, no CPU work.
 *
 * <p>When there is no app frame to show (idle, connecting, a problem) an <b>overlay</b> is drawn
 * instead: a glyph, a title and maybe a hint, rendered with a Canvas into a bitmap of the content
 * size and drawn as a second texture.
 *
 * <p>All GL work runs on its own thread. {@link #attach} / {@link #detach} wait for it, because
 * the host may destroy the screen surface as soon as its callback returns.
 */
final class Compositor implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "AnyAuto";
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private static final String VERTEX =
            "attribute vec4 aPos;\n"
                    + "attribute vec4 aTex;\n"
                    + "uniform mat4 uTexM;\n"
                    + "varying vec2 vTex;\n"
                    + "void main() {\n"
                    + "  gl_Position = aPos;\n"
                    + "  vTex = (uTexM * aTex).xy;\n"
                    + "}\n";
    private static final String FRAGMENT_2D =
            "precision mediump float;\n"
                    + "varying vec2 vTex;\n"
                    + "uniform sampler2D sTex;\n"
                    + "void main() {\n"
                    + "  gl_FragColor = texture2D(sTex, vTex);\n"
                    + "}\n";
    // a bitmap's first row is the top, GL's t = 0 is the bottom: t' = 1 - t
    private static final float[] FLIP_Y = {1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1};

    // overlay kinds
    static final int NONE = 0, IDLE = 1, CONNECTING = 2, PROBLEM = 3;

    private static final String FRAGMENT =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "varying vec2 vTex;\n"
                    + "uniform samplerExternalOES sTex;\n"
                    + "void main() {\n"
                    + "  gl_FragColor = texture2D(sTex, vTex);\n"
                    + "}\n";
    // full-viewport quad as a triangle strip: x, y, s, t
    private static final float[] QUAD = {
            -1, -1, 0, 0,
            1, -1, 1, 0,
            -1, 1, 0, 1,
            1, 1, 1, 1,
    };

    private final Context context;
    private final HandlerThread thread = new HandlerThread("anyauto-gl");
    private final Handler gl;
    private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
    private EGLContext ctx = EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private int surfaceW, surfaceH;
    private final Rect content = new Rect();
    private int program, aPos, aTex, uTexM, texture;
    private int program2, aPos2, aTex2, uTexM2, overlayTex;
    private int dpi = 160;
    private int overlayKind = NONE;
    private String overlayTitle, overlayHint;
    private boolean overlayReady;
    private FloatBuffer quad;
    private SurfaceTexture texIn;
    private Surface input;
    private final float[] texM = new float[16];
    private boolean haveFrame;
    private volatile boolean released;

    Compositor(Context context) {
        this.context = context;
        thread.start();
        gl = new Handler(thread.getLooper());
        runSync(this::setUp);
    }

    /** The surface the virtual display draws into; its buffers are the content size. */
    Surface input() {
        return input;
    }

    /** Draws into this screen surface from now on (waits until done). */
    void attach(Surface s, int w, int h) {
        runSync(() -> {
            dropWindow();
            try {
                window = EGL14.eglCreateWindowSurface(dpy, config, s, new int[]{EGL14.EGL_NONE}, 0);
            } catch (RuntimeException e) {
                window = EGL14.EGL_NO_SURFACE;
            }
            if (window == null || window == EGL14.EGL_NO_SURFACE) {
                Log.w(TAG, "compositor: no EGL surface for the screen (0x"
                        + Integer.toHexString(EGL14.eglGetError()) + ")");
                window = EGL14.EGL_NO_SURFACE;
                return;
            }
            surfaceW = w;
            surfaceH = h;
            renderOverlay();
            draw();
        });
    }

    /** Stops drawing into the screen surface and lets go of it (waits until done). */
    void detach() {
        runSync(this::dropWindow);
    }

    /** Where in the screen surface the display goes, and so the size of {@link #input()}'s buffers. */
    void content(Rect r, int dpi) {
        Rect copy = new Rect(r);
        gl.post(() -> {
            content.set(copy);
            this.dpi = Math.max(1, dpi);
            texIn.setDefaultBufferSize(copy.width(), copy.height());
            renderOverlay();
            draw();
        });
    }

    /** What to draw instead of the app: {@link #NONE} shows the app's frames. */
    void overlay(int kind, String title, String hint) {
        gl.post(() -> {
            if (kind == overlayKind && TextUtils.equals(title, overlayTitle) && TextUtils.equals(hint, overlayHint)) {
                return;
            }
            overlayKind = kind;
            overlayTitle = title;
            overlayHint = hint;
            renderOverlay();
            draw();
        });
    }

    void release() {
        released = true;
        gl.post(() -> {
            dropWindow();
            if (input != null) input.release();
            if (texIn != null) texIn.release();
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, pbuffer);
                EGL14.eglDestroyContext(dpy, ctx);
                EGL14.eglTerminate(dpy);
            }
            thread.quitSafely();
        });
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        // GL thread (the listener's handler)
        if (released) return;
        makeCurrent(window != EGL14.EGL_NO_SURFACE ? window : pbuffer);
        st.updateTexImage(); // always: frees the display's buffer even with no screen surface
        st.getTransformMatrix(texM);
        haveFrame = true;
        draw();
    }

    // ------------------------------------------------------------------ GL thread

    private void setUp() {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] v = new int[2];
        EGL14.eglInitialize(dpy, v, 0, v, 1);
        config = chooseConfig(true);
        if (config == null) config = chooseConfig(false);
        ctx = EGL14.eglCreateContext(dpy, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        pbuffer = EGL14.eglCreatePbufferSurface(dpy, config,
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        makeCurrent(pbuffer);

        program = link(compile(GLES20.GL_VERTEX_SHADER, VERTEX), compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT));
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aTex = GLES20.glGetAttribLocation(program, "aTex");
        uTexM = GLES20.glGetUniformLocation(program, "uTexM");
        quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(QUAD).position(0);

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        texture = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        program2 = link(compile(GLES20.GL_VERTEX_SHADER, VERTEX), compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_2D));
        aPos2 = GLES20.glGetAttribLocation(program2, "aPos");
        aTex2 = GLES20.glGetAttribLocation(program2, "aTex");
        uTexM2 = GLES20.glGetUniformLocation(program2, "uTexM");
        GLES20.glGenTextures(1, t, 0);
        overlayTex = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        texIn = new SurfaceTexture(texture);
        texIn.setDefaultBufferSize(1, 1);
        texIn.setOnFrameAvailableListener(this, gl);
        input = new Surface(texIn);
    }

    private EGLConfig chooseConfig(boolean recordable) {
        int[] attrs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                recordable ? EGL_RECORDABLE_ANDROID : EGL14.EGL_NONE, 1,
                EGL14.EGL_NONE,
        };
        EGLConfig[] out = new EGLConfig[1];
        int[] n = new int[1];
        return EGL14.eglChooseConfig(dpy, attrs, 0, out, 0, 1, n, 0) && n[0] > 0 ? out[0] : null;
    }

    private void draw() {
        if (window == EGL14.EGL_NO_SURFACE) return;
        makeCurrent(window);
        GLES20.glViewport(0, 0, surfaceW, surfaceH);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (!content.isEmpty()) {
            // GL's origin is bottom-left
            GLES20.glViewport(content.left, surfaceH - content.bottom, content.width(), content.height());
            if (overlayKind != NONE && overlayReady) {
                quadWith(program2, aPos2, aTex2, uTexM2, GLES20.GL_TEXTURE_2D, overlayTex, FLIP_Y);
            } else if (haveFrame) {
                quadWith(program, aPos, aTex, uTexM, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture, texM);
            }
        }
        EGL14.eglSwapBuffers(dpy, window);
    }

    private void quadWith(int prog, int pos, int tex, int texMatrix, int target, int textureId, float[] m) {
        GLES20.glUseProgram(prog);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(target, textureId);
        quad.position(0);
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(pos);
        quad.position(2);
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(tex);
        GLES20.glUniformMatrix4fv(texMatrix, 1, false, m, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /**
     * The overlay as a bitmap of the content size: black, and a centred column of glyph (60 %
     * white), title (white) and hint (60 % white). Sizes are px at 160 dpi, scaled by the surface
     * dpi; smaller in a dashboard slice, and the hint only when the content is at least 60 % of the
     * surface width (it names the Apps button, which a slice doesn't show).
     */
    private void renderOverlay() {
        overlayReady = false;
        if (overlayKind == NONE || content.isEmpty()) return;
        int w = content.width(), h = content.height();
        float s = dpi / 160f;
        boolean narrow = w < 500 * s;
        int glyph = Math.round((narrow ? 64 : 96) * s);
        float gap = 16 * s, side = 24 * s;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(Color.BLACK);

        TextPaint title = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        title.setColor(Color.WHITE);
        title.setTextSize((narrow ? 24 : 32) * s);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        int textW = Math.max(1, Math.round(w - 2 * side));
        StaticLayout tl = StaticLayout.Builder.obtain(overlayTitle == null ? "" : overlayTitle, 0,
                        overlayTitle == null ? 0 : overlayTitle.length(), title, textW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END).build();
        StaticLayout hl = null;
        boolean wide = surfaceW <= 0 || w >= 0.6f * surfaceW;
        if (overlayHint != null && wide) {
            TextPaint hint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            hint.setColor(0x99FFFFFF);
            hint.setTextSize(24 * s);
            hl = StaticLayout.Builder.obtain(overlayHint, 0, overlayHint.length(), hint, textW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).build();
        }
        float total = glyph + gap + tl.getHeight() + (hl != null ? gap + hl.getHeight() : 0);
        float y = (h - total) / 2f;

        int res = overlayKind == IDLE ? R.drawable.ic_apps
                : overlayKind == CONNECTING ? R.drawable.ic_shell : R.drawable.ic_phone_alert;
        Drawable d = context.getDrawable(res).mutate();
        d.setTint(0x99FFFFFF);
        int gx = (w - glyph) / 2;
        d.setBounds(gx, Math.round(y), gx + glyph, Math.round(y) + glyph);
        d.draw(cv);
        y += glyph + gap;
        cv.save();
        cv.translate(side, y);
        tl.draw(cv);
        cv.restore();
        if (hl != null) {
            y += tl.getHeight() + gap;
            cv.save();
            cv.translate(side, y);
            hl.draw(cv);
            cv.restore();
        }

        makeCurrent(window != EGL14.EGL_NO_SURFACE ? window : pbuffer);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        b.recycle();
        overlayReady = true;
    }

    private void dropWindow() {
        if (window == EGL14.EGL_NO_SURFACE) return;
        makeCurrent(pbuffer);
        EGL14.eglDestroySurface(dpy, window);
        window = EGL14.EGL_NO_SURFACE;
    }

    private void makeCurrent(EGLSurface s) {
        EGL14.eglMakeCurrent(dpy, s, s, ctx);
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static int link(int vs, int fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("program: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private void runSync(Runnable r) {
        if (Thread.currentThread() == thread) {
            r.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        gl.post(() -> {
            try {
                r.run();
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
