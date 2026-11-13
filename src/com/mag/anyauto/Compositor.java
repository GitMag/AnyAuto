package com.mag.anyauto;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
 * <p>The car's <b>navigation bar</b> (Back, Apps, Keyboard, Close, Hide) is a third texture, drawn
 * into its own rect beside the content; {@link Mirror} hit-tests the taps with {@link #navButton}.
 *
 * <p>On cars with a rotary knob or a touchpad, the <b>pointer</b> (a ring around the point it
 * clicks) is drawn last, over the app and the bar, blended by its alpha.
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

    // the navigation bar's buttons, top to bottom
    static final int NAV_BACK = 0, NAV_APPS = 1, NAV_KEYBOARD = 2, NAV_CLOSE = 3, NAV_HIDE = 4;
    private static final int[] NAV_ICONS = {R.drawable.ic_back, R.drawable.ic_apps, R.drawable.ic_keyboard,
            R.drawable.ic_close, R.drawable.ic_chevron_left};
    // Hide points to the screen edge the bar goes to
    private static final int NAV_HIDE_RIGHT = R.drawable.ic_chevron_right;
    private static final String[] NAV_LABELS = {"Back", "Apps", "Keyboard", "Close", "Hide"};
    private static final int NAV_BG = 0xFF16171A;
    private static final long NAV_PRESS_MS = 180;
    // the pointer: ring radius and the bitmap's size, dp
    private static final float CURSOR_RING_DP = 14, CURSOR_BOX_DP = 40;

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
    private int program2, aPos2, aTex2, uTexM2, overlayTex, navTex;
    private final Rect nav = new Rect();
    private boolean navAppOpen, navReady, navRight;
    private int navSkip;
    private int navPressed = -1;
    private final Runnable navRelease = () -> {
        navPressed = -1;
        renderNav();
        draw();
    };
    private int dpi = 160;
    private int cursorTex, cursorDownTex, cursorSize, cursorDpi;
    private float cursorX, cursorY;
    private boolean cursorShown, cursorDown;
    private final Runnable cursorRelease = () -> {
        cursorDown = false;
        draw();
    };
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
            if (cursorDpi != this.dpi) renderCursor();
            draw();
        });
    }

    /** The pointer's point in screen-surface pixels, and whether it shows. */
    void cursor(float x, float y, boolean shown) {
        gl.post(() -> {
            if (x == cursorX && y == cursorY && shown == cursorShown) return;
            cursorX = x;
            cursorY = y;
            cursorShown = shown;
            if (shown && cursorDpi != dpi) renderCursor();
            draw();
        });
    }

    /** The pointer clicks: its ring fills for a moment. */
    void cursorPressed() {
        gl.post(() -> {
            gl.removeCallbacks(cursorRelease);
            cursorDown = true;
            draw();
            gl.postDelayed(cursorRelease, NAV_PRESS_MS);
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

    /**
     * The navigation bar goes into this rect of the screen surface (empty: no bar), at the right
     * edge or the left; its buttons start {@code skipTop} px below the rect's top (on the right,
     * below Android Auto's own button). All buttons but Apps are dimmed while no app is open.
     */
    void nav(Rect r, int skipTop, boolean right, boolean appOpen) {
        Rect copy = new Rect(r);
        gl.post(() -> {
            if (copy.equals(nav) && skipTop == navSkip && right == navRight && appOpen == navAppOpen) return;
            nav.set(copy);
            navSkip = skipTop;
            navRight = right;
            navAppOpen = appOpen;
            renderNav();
            draw();
        });
    }

    /** Lights up a navigation button for a moment, so a tap on it shows. */
    void navPressed(int button) {
        gl.post(() -> {
            gl.removeCallbacks(navRelease);
            navPressed = button;
            renderNav();
            draw();
            gl.postDelayed(navRelease, NAV_PRESS_MS);
        });
    }

    /** The button under y in a bar of this rect, top to bottom, or -1. Same layout as renderNav. */
    static int navButton(Rect bar, int skipTop, int dpi, float y) {
        float pad = navPad(dpi);
        float slot = (bar.height() - skipTop - 2 * pad) / NAV_ICONS.length;
        if (slot <= 0) return -1;
        int i = (int) Math.floor((y - bar.top - skipTop - pad) / slot);
        return i < 0 || i >= NAV_ICONS.length ? -1 : i;
    }

    /** How many buttons the bar has. */
    static int navCount() {
        return NAV_ICONS.length;
    }

    /** The middle of button {@code i} in a bar of this rect, as y in the screen surface. */
    static float navCentreY(Rect bar, int skipTop, int dpi, int i) {
        float pad = navPad(dpi);
        float slot = (bar.height() - skipTop - 2 * pad) / NAV_ICONS.length;
        return bar.top + skipTop + pad + (i + 0.5f) * slot;
    }

    /** The bar's width for a screen: 72 dp, at most an eighth of the screen's width. */
    static int navWidth(int screenW, int dpi) {
        return Math.max(40, Math.min(Math.round(72 * dpi / 160f), screenW / 8));
    }

    private static float navPad(int dpi) {
        return 8 * dpi / 160f;
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
        overlayTex = texture2D();
        navTex = texture2D();
        cursorTex = texture2D();
        cursorDownTex = texture2D();

        texIn = new SurfaceTexture(texture);
        texIn.setDefaultBufferSize(1, 1);
        texIn.setOnFrameAvailableListener(this, gl);
        input = new Surface(texIn);
    }

    private static int texture2D() {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return t[0];
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
        if (!nav.isEmpty() && navReady) {
            GLES20.glViewport(nav.left, surfaceH - nav.bottom, nav.width(), nav.height());
            quadWith(program2, aPos2, aTex2, uTexM2, GLES20.GL_TEXTURE_2D, navTex, FLIP_Y);
        }
        if (cursorShown && cursorDpi > 0) {
            // centred on the point; Android bitmaps are premultiplied
            int half = cursorSize / 2;
            GLES20.glViewport(Math.round(cursorX) - half, surfaceH - Math.round(cursorY) - half, cursorSize, cursorSize);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            quadWith(program2, aPos2, aTex2, uTexM2, GLES20.GL_TEXTURE_2D, cursorDown ? cursorDownTex : cursorTex, FLIP_Y);
            GLES20.glDisable(GLES20.GL_BLEND);
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

    /**
     * The navigation bar as a bitmap of its rect: a dark column with a hairline on the app's side,
     * and five equal slots of glyph + label below the skipped top (the label only where a slot is
     * tall enough). Sizes are px at 160 dpi, scaled by the surface dpi.
     */
    private void renderNav() {
        navReady = false;
        if (nav.isEmpty()) return;
        int w = nav.width(), h = nav.height();
        float s = dpi / 160f, pad = navPad(dpi);
        float slot = (h - navSkip - 2 * pad) / NAV_ICONS.length;
        if (slot <= 0) return;
        int glyph = Math.round(Math.min(28 * s, slot * 0.45f));
        TextPaint label = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        label.setTextSize(12 * s);
        label.setTextAlign(android.graphics.Paint.Align.CENTER);
        float labelGap = 4 * s;
        Paint.FontMetrics fm = label.getFontMetrics();
        float labelH = fm.descent - fm.ascent;
        boolean labels = slot >= glyph + labelGap + labelH + 12 * s && label.measureText("Keyboard") <= w - 4 * s;

        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(NAV_BG);
        Paint line = new Paint();
        line.setColor(0x26FFFFFF);
        int hair = Math.max(1, Math.round(s));
        if (navRight) cv.drawRect(0, 0, hair, h, line);
        else cv.drawRect(w - hair, 0, w, h, line);
        Paint press = new Paint(Paint.ANTI_ALIAS_FLAG);
        press.setColor(0x33FFFFFF);
        for (int i = 0; i < NAV_ICONS.length; i++) {
            boolean on = navAppOpen || i == NAV_APPS;
            int fg = on ? 0xFFFFFFFF : 0x55FFFFFF;
            float top = navSkip + pad + i * slot;
            if (i == navPressed && on) {
                float in = 6 * s;
                cv.drawRoundRect(in, top + in / 2, w - in, top + slot - in / 2, 12 * s, 12 * s, press);
            }
            float block = glyph + (labels ? labelGap + labelH : 0);
            float y = top + (slot - block) / 2f;
            Drawable d = context.getDrawable(i == NAV_HIDE && navRight ? NAV_HIDE_RIGHT : NAV_ICONS[i]).mutate();
            d.setTint(fg);
            int gx = (w - glyph) / 2;
            d.setBounds(gx, Math.round(y), gx + glyph, Math.round(y) + glyph);
            d.draw(cv);
            if (labels) {
                label.setColor(on ? 0xCCFFFFFF : 0x55FFFFFF);
                cv.drawText(NAV_LABELS[i], w / 2f, y + glyph + labelGap - fm.ascent, label);
            }
        }

        makeCurrent(window != EGL14.EGL_NO_SURFACE ? window : pbuffer);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, navTex);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        b.recycle();
        navReady = true;
    }

    /**
     * The pointer as two bitmaps (normal, and pressed with the ring filled): a white ring with a
     * dark edge, so it shows on light and dark apps, and a dot at the point it clicks. Sizes are
     * px at 160 dpi, scaled by the surface dpi.
     */
    private void renderCursor() {
        float s = dpi / 160f;
        cursorSize = Math.max(8, Math.round(CURSOR_BOX_DP * s));
        float c = cursorSize / 2f, r = CURSOR_RING_DP * s, w = 3 * s, edge = 1.5f * s;
        for (int pass = 0; pass < 2; pass++) {
            Bitmap b = Bitmap.createBitmap(cursorSize, cursorSize, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (pass == 1) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x66FFFFFF);
                cv.drawCircle(c, c, r, p);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(w + 2 * edge);
            p.setColor(0xB3000000);
            cv.drawCircle(c, c, r, p);
            p.setStrokeWidth(w);
            p.setColor(Color.WHITE);
            cv.drawCircle(c, c, r, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xB3000000);
            cv.drawCircle(c, c, 2.5f * s + edge, p);
            p.setColor(Color.WHITE);
            cv.drawCircle(c, c, 2.5f * s, p);
            makeCurrent(window != EGL14.EGL_NO_SURFACE ? window : pbuffer);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pass == 0 ? cursorTex : cursorDownTex);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
            b.recycle();
        }
        cursorDpi = dpi;
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
