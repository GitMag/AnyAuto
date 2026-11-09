package com.mag.anyauto;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.AttributionSource;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Runs in the Shizuku-spawned process with the <b>shell</b> user's rights (uid 2000), the same
 * ones {@code adb shell} has and the technique scrcpy's {@code --new-display} uses:
 * <ul>
 * <li>a <b>trusted public</b> virtual display (ADD_TRUSTED_DISPLAY), so any app's activities may
 *     start on it, drawing straight into the car's surface (no copy, no encoder);</li>
 * <li>{@code ALWAYS_UNLOCKED} and its own focus (Android 13/14+), so it keeps showing while the
 *     phone is locked and does not steal the phone screen's focus. It still sleeps with the
 *     phone, so {@link PhonePower} keeps the phone awake with its screen dark;</li>
 * <li>activity starts with a launch display (an existing task moves there) and input injection
 *     (INJECT_EVENTS) with the event's display id set.</li>
 * </ul>
 * Hidden APIs are fine here: this process is not an app, so no hidden-API restrictions apply.
 * Bound by {@link Shell} with {@code Shizuku.bindUserService}; it dies with the app's process.
 */
public final class DisplayService extends IDisplayService.Stub {
    private static final String TAG = "AnyAuto.shell";
    private static final String SHELL = "com.android.shell";

    // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (all but the first three are hidden)
    private static final int PUBLIC = 1;
    private static final int PRESENTATION = 1 << 1;
    private static final int OWN_CONTENT_ONLY = 1 << 3;
    private static final int SUPPORTS_TOUCH = 1 << 6;
    private static final int TRUSTED = 1 << 10;
    private static final int OWN_DISPLAY_GROUP = 1 << 11;
    private static final int ALWAYS_UNLOCKED = 1 << 12;
    private static final int TOUCH_FEEDBACK_DISABLED = 1 << 13;
    private static final int OWN_FOCUS = 1 << 14;
    private static final int BASE = PUBLIC | PRESENTATION | OWN_CONTENT_ONLY | SUPPORTS_TOUCH;

    private final Context context;
    private DisplayManager dm;
    private VirtualDisplay display;
    private volatile int displayId = -1;
    private Surface surface;
    private int width, height, dpi, flags;
    private volatile String lastError = "";
    private PhonePower phone;

    // the headless car for tests: an ImageReader instead of the car's surface
    private static final File HEADLESS_PNG = new File("/data/local/tmp/anyauto-headless.png");
    private HandlerThread readerThread;
    private Handler readerHandler;
    private ImageReader reader;
    private Bitmap frame;
    private long frameSaved;
    private final Runnable saveFrame = this::saveFrame;

    // input injection, looked up once
    private Object inputManager;
    private Method inject, setDisplayId;

    // Shizuku tries (Context) first, then the no-arg constructor.
    public DisplayService() {
        this(null);
    }

    public DisplayService(Context base) {
        context = new ShellContext(base != null ? base : systemContext());
    }

    @Override
    public void destroy() {
        close();
        System.exit(0);
    }

    @Override
    public synchronized int show(Surface s, int w, int h, int d) {
        try {
            Surface old = surface;
            if (display == null) {
                display = create(w, h, d, s);
                displayId = display.getDisplay().getDisplayId();
                Log.i(TAG, "display " + displayId + " " + w + "x" + h + "@" + d + " flags 0x" + Integer.toHexString(flags));
            } else {
                display.setSurface(s);
                if (w > 0 && h > 0 && d > 0 && (w != width || h != height || d != dpi)) {
                    display.resize(w, h, d);
                    Log.i(TAG, "display " + displayId + " resized to " + w + "x" + h + "@" + d);
                }
            }
            surface = s;
            if (w > 0 && h > 0 && d > 0) {
                width = w;
                height = h;
                dpi = d;
            }
            // this process's copy of the previous surface (the app releases its own); the
            // headless reader's surface belongs to the reader
            if (old != null && old != s && (reader == null || old != reader.getSurface())) old.release();
            return displayId;
        } catch (Throwable t) {
            lastError = "display: " + describe(t);
            Log.w(TAG, lastError, t);
            return -1;
        }
    }

    /** Tries the full flag set first and drops the newer, permission-gated flags one step at a time. */
    private VirtualDisplay create(int w, int h, int d, Surface s) throws Exception {
        DisplayManager dm = dm();
        int sdk = Build.VERSION.SDK_INT;
        // no DEVICE_DISPLAY_GROUP (it belongs to virtual devices; we have none)
        int[] tries = {
                sdk >= 34 ? BASE | TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED | TOUCH_FEEDBACK_DISABLED
                        | OWN_FOCUS : -1,
                sdk >= 33 ? BASE | TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED | TOUCH_FEEDBACK_DISABLED : -1,
                BASE | TRUSTED,
                BASE,
        };
        Exception last = null;
        for (int f : tries) {
            if (f < 0) continue;
            try {
                VirtualDisplay vd = dm.createVirtualDisplay("AnyAuto", w, h, d, s, f);
                if (vd != null) {
                    flags = f;
                    return vd;
                }
            } catch (SecurityException | IllegalArgumentException e) {
                Log.i(TAG, "flags 0x" + Integer.toHexString(f) + " refused: " + e);
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("createVirtualDisplay returned null");
    }

    /** DisplayManager(Context) is hidden; the shell context makes its calls come from com.android.shell. */
    private synchronized DisplayManager dm() throws Exception {
        if (dm == null) {
            Constructor<DisplayManager> c = DisplayManager.class.getDeclaredConstructor(Context.class);
            c.setAccessible(true);
            dm = c.newInstance(context);
        }
        return dm;
    }

    @Override
    public void keepRunning(boolean on) {
        try {
            synchronized (this) {
                if (phone == null) {
                    if (!on) return;
                    phone = new PhonePower(context, dm());
                }
            }
            phone.keep(on);
        } catch (Throwable t) {
            lastError = "phone power: " + describe(t);
            Log.w(TAG, lastError, t);
        }
    }

    @Override
    public synchronized String launch(ComponentName component) {
        if (display == null) return "no display";
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // an existing task of the app moves to the launch display (checked on Android 17)
        Bundle options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle();
        try {
            Object atm = Build.VERSION.SDK_INT >= 29
                    ? Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
                    : ActivityManager.class.getMethod("getService").invoke(null);
            for (Method m : atm.getClass().getMethods()) {
                if (!m.getName().equals("startActivity")) continue;
                Class<?>[] p = m.getParameterTypes();
                Object[] args;
                if (p.length == 11 && p[3] == Intent.class) {
                    // API 30+: (caller, callingPackage, callingFeatureId, intent, resolvedType,
                    //           resultTo, resultWho, requestCode, flags, profilerInfo, options)
                    args = new Object[]{null, SHELL, null, intent, null, null, null, 0, 0, null, options};
                } else if (p.length == 10 && p[2] == Intent.class) {
                    // API 29: the same without callingFeatureId
                    args = new Object[]{null, SHELL, intent, null, null, null, 0, 0, null, options};
                } else {
                    continue;
                }
                int res = (Integer) m.invoke(atm, args);
                // 0 started, 1/2/3 brought to front / delivered to the top instance
                if (res >= 0) return "ok";
                lastError = "start " + component.flattenToShortString() + ": " + res;
                return lastError;
            }
            lastError = "no usable startActivity on " + atm.getClass().getName();
        } catch (Throwable t) {
            lastError = "start " + component.flattenToShortString() + ": " + describe(t);
            Log.w(TAG, lastError, t);
        }
        return lastError;
    }

    @Override
    public void touch(MotionEvent event) {
        int id = displayId;
        if (id >= 0) inject(event, id);
        event.recycle();
    }

    @Override
    public void key(int keyCode) {
        int id = displayId;
        if (id < 0) return;
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            KeyEvent e = new KeyEvent(now, now, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD,
                    0, 0, InputDevice.SOURCE_KEYBOARD);
            inject(e, id);
        }
    }

    private void inject(InputEvent e, int id) {
        try {
            if (inject == null) {
                // InputManagerGlobal holds injectInputEvent from Android 14 on, InputManager before
                Class<?> c;
                try {
                    c = Class.forName("android.hardware.input.InputManagerGlobal");
                } catch (ClassNotFoundException old) {
                    c = Class.forName("android.hardware.input.InputManager");
                }
                inputManager = c.getMethod("getInstance").invoke(null);
                inject = c.getMethod("injectInputEvent", InputEvent.class, int.class);
                setDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
            }
            setDisplayId.invoke(e, id);
            inject.invoke(inputManager, e, 0); // INJECT_INPUT_EVENT_MODE_ASYNC
        } catch (Throwable t) {
            lastError = "input: " + describe(t);
            Log.w(TAG, lastError, t);
        }
    }

    @Override
    public synchronized void close() {
        if (phone != null) phone.releaseNow();
        if (display != null) {
            Log.i(TAG, "display " + displayId + " released");
            display.release();
            display = null;
        }
        displayId = -1;
        if (surface != null && (reader == null || surface != reader.getSurface())) surface.release();
        surface = null;
        if (reader != null) {
            reader.close();
            reader = null;
        }
    }

    @Override
    public synchronized int showHeadless(int w, int h, int d) {
        if (reader == null || reader.getWidth() != w || reader.getHeight() != h) {
            if (readerThread == null) {
                readerThread = new HandlerThread("anyauto-headless");
                readerThread.start();
                readerHandler = new Handler(readerThread.getLooper());
            }
            ImageReader old = reader;
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(this::onFrame, readerHandler);
            int id = show(reader.getSurface(), w, h, d);
            if (old != null) old.close();
            return id;
        }
        return show(reader.getSurface(), w, h, d);
    }

    /** Reader thread: keeps the newest frame; saves it when the screen settles, or each second. */
    private void onFrame(ImageReader r) {
        Image img;
        try {
            img = r.acquireLatestImage();
        } catch (IllegalStateException closed) {
            return;
        }
        if (img == null) return;
        try {
            Image.Plane p = img.getPlanes()[0];
            int w = img.getWidth(), h = img.getHeight();
            int stride = p.getRowStride() / p.getPixelStride();
            if (frame == null || frame.getWidth() != stride || frame.getHeight() != h) {
                frame = Bitmap.createBitmap(stride, h, Bitmap.Config.ARGB_8888);
            }
            frame.copyPixelsFromBuffer(p.getBuffer());
            frame.setHasAlpha(false);
            if (stride != w) frame = Bitmap.createBitmap(frame, 0, 0, w, h);
        } finally {
            img.close();
        }
        readerHandler.removeCallbacks(saveFrame);
        if (SystemClock.uptimeMillis() - frameSaved > 1000) saveFrame();
        else readerHandler.postDelayed(saveFrame, 250);
    }

    private void saveFrame() {
        frameSaved = SystemClock.uptimeMillis();
        File tmp = new File(HEADLESS_PNG.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            frame.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) {
            Log.w(TAG, "headless frame not saved", e);
            return;
        }
        tmp.renameTo(HEADLESS_PNG);
    }

    @Override
    public String lastError() {
        return lastError;
    }

    @Override
    public synchronized String info() {
        return "display " + displayId + (display == null ? "" : " " + width + "x" + height + "@" + dpi
                + " flags 0x" + Integer.toHexString(flags)) + ", Android " + Build.VERSION.SDK_INT
                + ", uid " + Process.myUid() + (phone == null ? "" : ", " + phone.describe());
    }

    private static String describe(Throwable t) {
        if (t instanceof InvocationTargetException && t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static Context systemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            if (thread == null) thread = at.getMethod("systemMain").invoke(null);
            return (Context) at.getMethod("getSystemContext").invoke(thread);
        } catch (Throwable t) {
            throw new IllegalStateException("no context for the shell service", t);
        }
    }

    /** Makes system-service calls name com.android.shell, the package of uid 2000. */
    private static final class ShellContext extends ContextWrapper {
        ShellContext(Context base) {
            super(base);
        }

        @Override
        public String getPackageName() {
            return SHELL;
        }

        @Override
        public String getOpPackageName() {
            return SHELL;
        }

        @Override
        public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(Process.myUid()).setPackageName(SHELL).build();
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }
    }
}
