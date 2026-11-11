package com.mag.anyauto;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.UiAutomation;
import android.content.AttributionSource;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

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
    private volatile PhonePower phone;
    // the car's (and the headless car's) default; the preview asks for the phone's screen
    private boolean keyboardHere = true;
    // the setting "Keep the phone's screen on"
    private boolean keepPhoneOn;
    // the car shows AnyAuto (keepRunning)
    private boolean carOn;
    // the display has a display group of its own, so it keeps running while the phone sleeps
    // (Android 14 emulator: group 1); on Android 17 builds it shares the phone's group 0
    private boolean ownGroup;
    // keeps only the car display's group awake (PowerManager.newWakeLock(..., displayId))
    private PowerManager.WakeLock carLock;

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
                keepLandscape();
                applyKeyboard();
                ownGroup = ownGroup(displayId);
                Log.i(TAG, "display " + displayId + (ownGroup
                        ? " has its own display group: the phone is left alone"
                        : " is in the phone's display group: the phone is kept awake while the car shows it"));
                applyPhoneMode();
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

    @Override
    public synchronized void keyboardHere(boolean here) {
        if (keyboardHere == here) return;
        keyboardHere = here;
        if (display != null) applyKeyboard();
        // a keyboard on the phone needs the phone's screen on
        applyPhoneMode();
    }

    @Override
    public boolean phoneScreenFights() {
        PhonePower p = phone;
        return p != null && p.fights();
    }

    @Override
    public synchronized void phoneScreenOn(boolean on) {
        if (keepPhoneOn == on) return;
        keepPhoneOn = on;
        applyPhoneMode();
    }

    /**
     * Where the display's keyboard opens (IME policy, like scrcpy's {@code --display-ime-policy}):
     * LOCAL puts it on the display itself; FALLBACK_DISPLAY (Android's default for a virtual
     * display) on the phone's screen, which is dark while the car uses the display. A keyboard on
     * the display only stays open while that display has the focus, and a finger on the phone's
     * screen takes the focus, so the preview keeps the keyboard on the phone. Needs a trusted
     * display and INTERNAL_SYSTEM_WINDOW (the shell user has it). Best effort: if it is refused,
     * the display still works and the keyboard stays on the phone.
     */
    /** IWindowManager, straight from ServiceManager: WindowManagerGlobal.getWindowManagerService()
     *  needs ApplicationSharedMemory on Android 17, which this process never sets up. */
    private static Object windowManager() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, Context.WINDOW_SERVICE);
        return Class.forName("android.view.IWindowManager$Stub")
                .getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
    }

    /**
     * The display never turns for an app. A virtual display follows an app's requested
     * orientation like a phone: on Android 14 a portrait-only app turned it to portrait (rotation
     * 270, 392x696), and the car's landscape surface then showed the whole display shrunk into a
     * narrow column, with the car's taps landing in the wrong places. Android 17 letterboxes such
     * apps by itself. Here every Android gets that: the app stays upright at full height in a
     * column in the middle. Rotation 0 is the display's own shape, so a portrait-shaped dashboard
     * card stays portrait. Any one of the three settings was enough on Android 14; each is best
     * effort (all need SET_ORIENTATION, which the shell user has).
     */
    private void keepLandscape() {
        int id = displayId;
        Object wm;
        try {
            wm = windowManager();
        } catch (Throwable t) {
            Log.w(TAG, "orientation: no window manager: " + describe(t));
            return;
        }
        StringBuilder done = new StringBuilder();
        for (Method m : wm.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            try {
                switch (m.getName()) {
                    case "setIgnoreOrientationRequest": // Android 12+: (displayId, ignore)
                        if (p.length == 2 && p[0] == int.class && p[1] == boolean.class) {
                            m.invoke(wm, id, true);
                            done.append(" ignore-requests");
                        }
                        break;
                    case "setFixedToUserRotation": // Android 10+: (displayId, FIXED_TO_USER_ROTATION_ENABLED = 2)
                        if (p.length == 2 && p[0] == int.class && p[1] == int.class) {
                            m.invoke(wm, id, 2);
                            done.append(" fixed-to-user");
                        }
                        break;
                    case "freezeDisplayRotation": // (displayId, rotation), (displayId, rotation, caller) on 14+
                        if (p.length == 2 && p[0] == int.class && p[1] == int.class) {
                            m.invoke(wm, id, 0);
                            done.append(" rotation-0");
                        } else if (p.length == 3 && p[0] == int.class && p[1] == int.class && p[2] == String.class) {
                            m.invoke(wm, id, 0, "AnyAuto");
                            done.append(" rotation-0");
                        }
                        break;
                }
            } catch (Throwable t) {
                Log.w(TAG, "orientation: " + m.getName() + ": " + describe(t));
            }
        }
        Log.i(TAG, "display " + id + " stays as it is:" + (done.length() == 0 ? " nothing applied" : done));
    }

    private void applyKeyboard() {
        int id = displayId;
        boolean here = keyboardHere;
        if ((flags & TRUSTED) == 0) {
            if (here) Log.i(TAG, "display not trusted: the keyboard stays on the phone");
            return;
        }
        try {
            Object wm = windowManager();
            if (Build.VERSION.SDK_INT >= 31) {
                // WindowManager.DISPLAY_IME_POLICY_LOCAL = 0, DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1
                wm.getClass().getMethod("setDisplayImePolicy", int.class, int.class)
                        .invoke(wm, id, here ? 0 : 1);
            } else {
                wm.getClass().getMethod("setShouldShowIme", int.class, boolean.class).invoke(wm, id, here);
            }
            Log.i(TAG, "keyboard for display " + id + " opens " + (here ? "on it" : "on the phone"));
        } catch (Throwable t) {
            // not lastError: that one explains failures, and the display itself is fine
            Log.w(TAG, "keyboard stays on the phone: " + describe(t), t);
        }
    }

    // ------------------------------------------------------------------ the keyboard button

    private final Object a11yLock = new Object();
    private HandlerThread a11yThread;

    /**
     * Reads the keyboard state through accessibility (a UiAutomation, like {@code uiautomator}
     * uses; the shell user may connect one), connected only for this press so accessibility is not
     * left on while driving. Then: keyboard open (on the display, or on the phone with the setting
     * "On the phone") → Back closes it; a focused text field → a tap on it opens the keyboard, the
     * same as a finger would.
     */
    @Override
    public String toggleKeyboard() {
        int id;
        boolean here;
        synchronized (this) {
            id = displayId;
            here = keyboardHere;
        }
        if (id < 0) return "error: no display";
        if (Build.VERSION.SDK_INT < 30) return "old android"; // getWindowsOnAllDisplays
        synchronized (a11yLock) {
            UiAutomation ua = null;
            try {
                ua = connectA11y();
                Object[] state = keyboardState(ua, id, here);
                AccessibilityNodeInfo field = (AccessibilityNodeInfo) state[1];
                if ((Boolean) state[0]) {
                    key(KeyEvent.KEYCODE_BACK);
                    return "hidden";
                }
                if (field == null) return "no field";
                Rect r = new Rect();
                field.getBoundsInScreen(r);
                tap(id, r.centerX(), r.centerY());
                return "shown";
            } catch (Throwable t) {
                Log.w(TAG, "keyboard button: " + describe(t), t);
                return "error: " + describe(t);
            } finally {
                disconnectA11y(ua);
            }
        }
    }

    /** {keyboard open (Boolean), focused text field or null}. */
    private static Object[] keyboardState(UiAutomation ua, int id, boolean here) {
        boolean shown = false;
        AccessibilityNodeInfo field = null;
        // web pages build their accessibility tree on demand: look again briefly
        for (int i = 0; i < 4 && !shown && field == null; i++) {
            if (i > 0) SystemClock.sleep(200);
            SparseArray<List<AccessibilityWindowInfo>> all = ua.getWindowsOnAllDisplays();
            shown = hasKeyboard(all.get(id)) || (!here && hasKeyboard(all.get(Display.DEFAULT_DISPLAY)));
            if (!shown) field = focusedField(all.get(id));
        }
        return new Object[]{shown, field};
    }

    private static boolean hasKeyboard(List<AccessibilityWindowInfo> windows) {
        if (windows == null) return false;
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
        }
        return false;
    }

    /** The text field with the input focus in the display's windows, top window first. */
    private static AccessibilityNodeInfo focusedField(List<AccessibilityWindowInfo> windows) {
        if (windows == null) return null;
        for (AccessibilityWindowInfo w : windows) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null && f.isEditable()) return f;
        }
        return null;
    }

    private UiAutomation connectA11y() throws Exception {
        if (a11yThread == null) {
            a11yThread = new HandlerThread("anyauto-a11y");
            a11yThread.start();
        }
        Class<?> iconn = Class.forName("android.app.IUiAutomationConnection");
        Object conn = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance();
        UiAutomation ua;
        try {
            // (Looper, connection): Android 7 to 17, on our own thread
            Constructor<UiAutomation> k = UiAutomation.class.getDeclaredConstructor(Looper.class, iconn);
            k.setAccessible(true);
            ua = k.newInstance(a11yThread.getLooper(), conn);
        } catch (NoSuchMethodException e) {
            Constructor<UiAutomation> k = UiAutomation.class.getDeclaredConstructor(Context.class, iconn);
            k.setAccessible(true);
            ua = k.newInstance(context, conn);
        }
        Method connect = UiAutomation.class.getDeclaredMethod("connect", int.class);
        connect.setAccessible(true);
        connect.invoke(ua, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo info = ua.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        ua.setServiceInfo(info);
        return ua;
    }

    private static void disconnectA11y(UiAutomation ua) {
        if (ua == null) return;
        try {
            Method m = UiAutomation.class.getDeclaredMethod("disconnect");
            m.setAccessible(true);
            m.invoke(ua);
        } catch (Throwable t) {
            Log.w(TAG, "accessibility disconnect: " + describe(t));
        }
    }

    /** A finger tap at display pixels (x, y). */
    private void tap(int id, int x, int y) {
        MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties()};
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        coords[0].x = Math.max(0, Math.min(width - 1, x));
        coords[0].y = Math.max(0, Math.min(height - 1, y));
        coords[0].pressure = 1f;
        coords[0].size = 1f;
        long down = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, props, coords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            inject(e, id);
            e.recycle();
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(60);
        }
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
    public synchronized void keepRunning(boolean on) {
        carOn = on;
        applyPhoneMode();
    }

    /**
     * What happens to the phone while the car shows AnyAuto:
     * <ul>
     * <li>the display has its own display group (it runs while the phone sleeps): the phone is
     *     left alone, it sleeps, locks and can be used as usual; a wake lock for the car display
     *     alone keeps that one awake;</li>
     * <li>it shares the phone's group (Android 17 builds with desktop mode): the phone must stay
     *     awake, so {@link PhonePower} keeps it awake with its screen dark;</li>
     * <li>"Keep the phone's screen on", or the keyboard on the phone: the phone stays awake with
     *     its screen on, in both cases.</li>
     * </ul>
     */
    private synchronized void applyPhoneMode() {
        try {
            boolean lit = keepPhoneOn || !keyboardHere;
            if (ownGroup && !lit) {
                if (phone != null) phone.releaseNow();
            } else {
                if (phone == null) {
                    if (!carOn) return;
                    phone = new PhonePower(context, dm(), !lit);
                }
                phone.setDark(!lit);
                phone.keep(carOn);
            }
            carLock(carOn && ownGroup);
        } catch (Throwable t) {
            lastError = "phone power: " + describe(t);
            Log.w(TAG, lastError, t);
        }
    }

    /** A screen wake lock for the car display's group only (hidden API, Android 13+); best effort. */
    private void carLock(boolean on) {
        try {
            if (on && carLock == null) {
                PowerManager pm = context.createPackageContext(SHELL, 0).getSystemService(PowerManager.class);
                Method m = PowerManager.class.getMethod("newWakeLock", int.class, String.class, int.class);
                carLock = (PowerManager.WakeLock) m.invoke(pm,
                        PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "AnyAuto:car-display", displayId);
                carLock.setReferenceCounted(false);
            }
            if (carLock == null) return;
            if (on && !carLock.isHeld()) {
                carLock.acquire();
                Log.i(TAG, "car display kept awake on its own; the phone is untouched");
            } else if (!on && carLock.isHeld()) {
                carLock.release();
            }
        } catch (Throwable t) {
            Log.i(TAG, "no wake lock for the car display alone: " + describe(t));
        }
    }

    /** True when the display's group is not the phone's (DisplayInfo.displayGroupId, Android 12+). */
    private static boolean ownGroup(int id) {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            Object g = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
            Method info = g.getClass().getMethod("getDisplayInfo", int.class);
            Object car = info.invoke(g, id), phone = info.invoke(g, Display.DEFAULT_DISPLAY);
            if (car == null || phone == null) return false;
            java.lang.reflect.Field f = car.getClass().getField("displayGroupId");
            return f.getInt(car) != f.getInt(phone);
        } catch (Throwable t) {
            Log.i(TAG, "display group unknown: " + describe(t));
            return false;
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
        if (carLock != null && carLock.isHeld()) carLock.release();
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
