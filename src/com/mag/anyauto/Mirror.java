package com.mag.anyauto;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One screen that shows the virtual display: the car's surface (car/MirrorScreen) or the phone
 * preview. Keeps what should be shown (screen surface, its usable area, the app) and pushes it to
 * {@link DisplayService} whenever it changes or the service (re)connects. All service calls run on
 * one worker thread, in order; input goes through {@link Touch} on the same thread.
 *
 * <p>The display draws into a {@link Compositor}, which puts it into the <b>content rect</b>: the
 * part of the screen surface the user can see. That is the car's stable area (not covered by the
 * action strip, and the dashboard card's slice when AnyAuto is in the map card), else its visible
 * area, else the whole surface. The display is sized to the content rect, and car coordinates
 * are moved into it before they reach {@link Touch}.
 *
 * <p>On the car, AnyAuto's <b>navigation bar</b> (Back, Apps, Keyboard, Close, Hide) takes a band
 * at the left (or, by the setting, the right) of the full-width view, beside the app; taps in it
 * are AnyAuto's, not the app's.
 *
 * <p>Cars without a touch screen (a rotary knob, a touchpad) get the <b>pointer</b>. Their only way
 * into the surface is Android Auto's pan mode: the pan button (which touch screens don't show)
 * turns it on, a nudge of the knob then arrives as a scroll of a fixed distance (75 px on the
 * desktop head unit) and a touchpad as finger distances; turning the knob sends nothing, and a
 * press of the knob just ends pan mode (the car's Back does not). So in pan mode the pointer
 * moves, pushing on against an edge scrolls the app (mouse-wheel events), and the end of pan mode
 * is a tap at the pointer, on the app or on the navigation bar. On the bar the pointer goes from
 * button to button. Pressing the pan button again brings the pointer back where it was.
 *
 * <p>Android Auto doesn't end pan mode when the user goes idle (10 s): it only hides its own
 * buttons, nudges then move the knob's focus instead, and the next press of the pan button ends
 * the pan mode that is still on. So after {@value #PAN_IDLE_MS} ms without the knob the pointer
 * hides, and that press doesn't tap.
 *
 * <p>The display outlives the screen surface: without one (another app in front, the apps grid)
 * it is paused, and the app is still there when the surface comes back. {@link #release()}
 * closes the display; its tasks move back to the phone screen.
 */
public final class Mirror implements Touch.Sink, Shell.Listener {
    private static final String TAG = "AnyAuto";
    // the car sends the visible and the stable area one after the other: resize once
    private static final long CONTENT_SETTLE_MS = 150;
    // while the car shows AnyAuto: how often to ask whether the phone refused to stay dark
    private static final long FIGHT_CHECK_MS = 10_000;

    public interface Listener {
        /** Main thread: {@link #problem()} or the navigation bar setting may have changed. */
        void onMirrorChanged();
    }

    /** What the car screen does for the navigation bar (main thread). */
    public interface Nav {
        /** The Apps button: show the apps grid. */
        void apps();

        /** A short message for the driver (a toast). */
        void message(String text, boolean longer);
    }

    // drives on which the car says that the phone's dark screen is on purpose
    private static final int DARK_TELL_TIMES = 3;

    // the pointer (knob and touchpad cars), sizes in dp: a nudge moves it one step, quick nudges
    // the same way up to POINTER_MAX_SPEED steps; at most a 48 dp touch target apart, so any
    // button can be reached
    private static final float POINTER_STEP_DP = 32;
    private static final int POINTER_MAX_SPEED = 4;
    private static final long POINTER_ACCEL_MS = 600;
    // a knob's nudge: along one axis, at least this far (touchpad finger distances are not)
    private static final float NUDGE_MIN_DP = 30;
    // a touchpad finger pushing this far past an edge scrolls once
    private static final float EDGE_PUSH_DP = 48;
    // an edge push scrolls this share of the app's height (or width)
    private static final float SCROLL_SHARE = 0.45f;
    // a press ends pan mode; wait this long before the tap, so leaving the screen cancels it
    private static final long PAN_CLICK_MS = 150;
    private static final long POINTER_HIDE_MS = 2000;
    // Android Auto's own idle time: after it, the end of pan mode is not a click
    private static final long PAN_IDLE_MS = 10_000;
    // the pointer keeps this far inside the screen, so its dot stays visible
    private static final float POINTER_INSET_DP = 4;
    // drives on which the car explains the pointer
    private static final int POINTER_TELL_TIMES = 3;
    private static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3;

    /** What keeps the app off the screen: a short title and one sentence with the fix. */
    public static final class Problem {
        public final String title, text;

        Problem(String title, String text) {
            this.title = title;
            this.text = text;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Problem && ((Problem) o).title.equals(title)
                    && Objects.equals(((Problem) o).text, text);
        }

        @Override
        public int hashCode() {
            return title.hashCode();
        }
    }

    private final HandlerThread thread = new HandlerThread("anyauto-io");
    private final Handler io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    // the car: its surfaces are ours to release (a SurfaceView's are not), and the phone is kept
    // awake with its screen dark while AnyAuto is in use on the car screen (PhonePower)
    private final boolean car;
    private final Touch touch;
    private final Compositor compositor;
    private final Context context;

    // worker thread state
    private Surface screen;           // the car's (or the preview's) surface, or null
    private int screenW, screenH, dpi;
    private final Rect stable = new Rect(), visible = new Rect();
    private Rect forced;              // the preview's simulated dashboard card
    private final Rect content = new Rect();
    private final Rect navBar = new Rect(); // the car's navigation bar, or empty
    private int navSkip;                    // px at its top without buttons (under Android Auto's button)
    private boolean navRight;
    private volatile Nav nav;
    private boolean darkTold;         // this drive was told that the phone's screen is dark on purpose
    private boolean panning;          // Android Auto's pan mode: knob and touchpad move the pointer
    private boolean panTouched;       // a touch-screen tap came during this pan mode: no click at its end
    private boolean panIdle;          // no knob for PAN_IDLE_MS: Android Auto went idle, no click at the end
    private boolean knobFound;        // pan mode showed this drive that the car has a knob or touchpad
    private boolean pointerTold;      // this drive explained the pointer
    private float px = -1, py = -1;   // the pointer in screen-surface pixels (-1: not placed yet)
    private boolean pointerShown;
    private int lastDir = -1, streak;
    private long lastNudge;
    private float pushX, pushY;       // a touchpad finger's distance past the edge
    private boolean hold;             // the car shows AnyAuto's apps grid
    private ComponentName app;        // what should be on the display
    private IDisplayService pushedTo; // the service the state below belongs to
    private boolean pushedOn;
    private int pushedW, pushedH, pushedDpi;
    private boolean pushedKeep;
    private Boolean pushedKeyboard;   // keyboard on the display (true) or on the phone (false)
    private Boolean pushedPhoneOn;    // "Keep the phone's screen on"
    private int displayId = -1;
    private volatile ComponentName launched;
    private volatile Problem problem;
    private final Runnable applyContent = this::applyContent;
    // a car Mirror has the display right now; the phone preview stays out of its way
    private static volatile Mirror carShowing;
    private static final CopyOnWriteArrayList<Runnable> carListeners = new CopyOnWriteArrayList<>();

    public Mirror(Context context, Listener listener, boolean car) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.car = car;
        compositor = new Compositor(this.context);
        thread.start();
        io = new Handler(thread.getLooper());
        touch = new Touch(io, this);
        Shell.addListener(this);
    }

    /** A new or changed screen surface (size or dpi), from the main thread. */
    public void surface(Surface s, int w, int h, int d) {
        io.post(() -> {
            if (screen != s) {
                compositor.detach();
                drop(screen);
            }
            screen = s;
            screenW = w;
            screenH = h;
            dpi = d;
            compositor.attach(s, w, h);
            io.removeCallbacks(applyContent);
            applyContent();
        });
    }

    /** The car's visible area (not covered right now) changed. */
    public void visibleArea(Rect r) {
        Rect c = new Rect(r);
        io.post(() -> {
            visible.set(c);
            contentChanged();
        });
    }

    /** The car's stable area (never covered) changed. */
    public void stableArea(Rect r) {
        Rect c = new Rect(r);
        io.post(() -> {
            stable.set(c);
            contentChanged();
        });
    }

    /** The preview's simulated dashboard card (null: the whole surface). */
    void forceContent(Rect r) {
        Rect c = r == null ? null : new Rect(r);
        io.post(() -> {
            forced = c;
            contentChanged();
        });
    }

    /**
     * The surface is going away. Blocks (up to a second) until the display and the compositor
     * have let go of it, because the host may destroy it as soon as this returns.
     */
    public void surfaceGone() {
        touch.cancel();
        CountDownLatch done = new CountDownLatch(1);
        io.post(() -> {
            compositor.detach();
            drop(screen);
            screen = null;
            // the template is gone, and pan mode with it: no tap at the pointer
            panning = false;
            io.removeCallbacks(pointerClick);
            io.removeCallbacks(panIdleNow);
            showPointer(false);
            sync();
            done.countDown();
        });
        try {
            done.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The car shows AnyAuto's apps grid: AnyAuto is still in use, keep the phone dark. */
    public void hold(boolean on) {
        io.post(() -> {
            hold = on;
            sync();
        });
    }

    /** Shows this app on the display (starting it there, or moving its task there). */
    public void open(ComponentName c) {
        io.post(() -> {
            app = c;
            launched = null;
            relayout(); // a hidden navigation bar goes once an app is open
        });
    }

    /** The car screen's handler for the navigation bar's Apps button and messages. */
    public void setNav(Nav n) {
        nav = n;
    }

    /** Android Auto's own button: shows or hides the navigation bar. */
    public void toggleNav() {
        Prefs.setNavBar(context, !Prefs.navBar(context));
        io.post(this::applyContent);
        main.post(listener::onMirrorChanged);
    }

    // ---- input in screen-surface coordinates (the car's or the preview's)

    public void click(float x, float y) {
        io.post(() -> {
            // a touch screen as well (some cars have both): its taps aren't the end of pan mode
            if (panning) panTouched = true;
            tap(x, y);
        });
    }

    /** A tap at a point of the screen surface: the navigation bar's, or the app's (worker thread). */
    private void tap(float x, float y) {
        if (navBar.contains((int) x, (int) y)) navTap(Compositor.navButton(navBar, navSkip, dpi, y));
        else if (content.contains((int) x, (int) y)) touch.click(x - content.left, y - content.top);
    }

    /**
     * Android Auto's pan mode came on or went off (main thread). Only cars without a touch screen
     * show the pan button, so this is the knob's or touchpad's way in: on, the pointer shows; off,
     * which is how a press of the knob arrives, it taps where the pointer is.
     */
    public void panMode(boolean on) {
        io.post(() -> {
            if (on == panning) return;
            panning = on;
            io.removeCallbacks(hidePointer);
            io.removeCallbacks(pointerClick);
            io.removeCallbacks(panIdleNow);
            if (on) {
                panTouched = false;
                panActive();
                pushX = pushY = 0;
                lastDir = -1;
                if (!Prefs.carKnob(context)) {
                    Prefs.setCarKnob(context);
                    knobFound = true;
                }
                showPointer(true);
                tellPointer();
            } else {
                if (!panTouched && !panIdle) io.postDelayed(pointerClick, PAN_CLICK_MS);
                else Log.d(TAG, "pan mode ended without a click (" + (panIdle ? "idle" : "touch") + ")");
                io.postDelayed(hidePointer, POINTER_HIDE_MS);
                // the action strip gets Apps; refreshed only now, so pan mode isn't disturbed
                if (knobFound) {
                    knobFound = false;
                    main.post(listener::onMirrorChanged);
                }
            }
        });
    }

    private final Runnable pointerClick = this::pointerClick;
    private final Runnable hidePointer = this::hidePointer;
    private final Runnable panIdleNow = this::panIdleNow;

    /** The knob was used in pan mode: the pointer is live (again) for PAN_IDLE_MS. */
    private void panActive() {
        io.removeCallbacks(panIdleNow);
        io.postDelayed(panIdleNow, PAN_IDLE_MS);
        if (panIdle) {
            panIdle = false;
            showPointer(true);
        }
    }

    /** Android Auto went idle: its pan mode is dead until the pan button is pressed again. */
    private void panIdleNow() {
        if (!panning) return;
        panIdle = true;
        showPointer(false);
    }

    /** The end of pan mode: a tap where the pointer is, unless the screen went away meanwhile. */
    private void pointerClick() {
        if (screen == null || px < 0) return;
        Log.d(TAG, "pointer click " + Math.round(px) + "," + Math.round(py));
        compositor.cursorPressed();
        tap(px, py);
    }

    private void hidePointer() {
        if (!panning) showPointer(false);
    }

    /** Shows or hides the pointer, placing it first: in the middle the first time, else where it was. */
    private void showPointer(boolean on) {
        pointerShown = on;
        if (on) placePointer();
        if (px >= 0) compositor.cursor(px, py, on);
    }

    /** The pointer goes over the app and the navigation bar, a little inside the screen's edges. */
    private Rect pointerArea() {
        Rect r = new Rect(content);
        if (!navBar.isEmpty()) r.union(navBar);
        int in = Math.round(POINTER_INSET_DP * dpi / 160f);
        if (r.width() > 4 * in && r.height() > 4 * in) {
            r.inset(r.left <= 0 ? in : 0, r.top <= 0 ? in : 0);
            if (r.right >= screenW) r.right -= in;
            if (r.bottom >= screenH) r.bottom -= in;
        }
        return r;
    }

    private void placePointer() {
        Rect a = pointerArea();
        if (a.isEmpty()) return;
        if (px < 0) {
            px = content.exactCenterX();
            py = content.exactCenterY();
        }
        px = clamp(px, a.left, a.right - 1);
        py = clamp(py, a.top, a.bottom - 1);
    }

    /**
     * Pan mode's scroll (worker thread). The distances point the way the knob was pushed (the
     * opposite of a finger drag's). A knob's nudge moves the pointer by a step; quick nudges the
     * same way take bigger steps. A touchpad's finger distances move it as they are. Pushing on
     * against an edge scrolls the app instead.
     */
    private void movePointer(float dx, float dy) {
        Rect a = pointerArea();
        if (a.isEmpty()) return;
        panActive();
        placePointer();
        float s = dpi / 160f;
        boolean nudge = (dx == 0) != (dy == 0) && Math.max(Math.abs(dx), Math.abs(dy)) >= NUDGE_MIN_DP * s;
        if (nudge) {
            int dir = dx > 0 ? RIGHT : dx < 0 ? LEFT : dy > 0 ? DOWN : UP;
            long now = SystemClock.uptimeMillis();
            streak = dir == lastDir && now - lastNudge < POINTER_ACCEL_MS ? Math.min(streak + 1, POINTER_MAX_SPEED - 1) : 0;
            lastDir = dir;
            lastNudge = now;
            if (!navBar.isEmpty() && navNudge(dir, POINTER_STEP_DP * s * (1 + streak), a)) {
                compositor.cursor(px, py, true);
                return;
            }
            boolean atEdge = dir == RIGHT ? px >= a.right - 1 : dir == LEFT ? px <= a.left
                    : dir == DOWN ? py >= a.bottom - 1 : py <= a.top;
            if (atEdge) {
                edgeScroll(dir);
                return;
            }
            float step = POINTER_STEP_DP * s * (1 + streak);
            px = clamp(px + Math.signum(dx) * step, a.left, a.right - 1);
            py = clamp(py + Math.signum(dy) * step, a.top, a.bottom - 1);
        } else {
            float nx = px + dx, ny = py + dy;
            px = clamp(nx, a.left, a.right - 1);
            py = clamp(ny, a.top, a.bottom - 1);
            pushX = nx != px ? pushX + nx - px : 0;
            pushY = ny != py ? pushY + ny - py : 0;
            float limit = EDGE_PUSH_DP * s;
            if (Math.abs(pushY) >= limit) {
                edgeScroll(pushY > 0 ? DOWN : UP);
                pushY = 0;
            }
            if (Math.abs(pushX) >= limit) {
                edgeScroll(pushX > 0 ? RIGHT : LEFT);
                pushX = 0;
            }
        }
        compositor.cursor(px, py, true);
    }

    /**
     * A knob's nudge where the navigation bar is (worker thread): on the bar the pointer sits on a
     * button, up and down go to the next one, towards the app leaves the bar (towards the screen's
     * edge does nothing); a nudge that would end on the bar lands on the button at that height.
     * False when the bar has nothing to do with this nudge.
     */
    private boolean navNudge(int dir, float step, Rect a) {
        int n = Compositor.navCount();
        boolean onBar = navBar.contains((int) px, (int) py);
        if (onBar) {
            int i = navButtonNear(py);
            if (dir == UP || dir == DOWN) {
                i = Math.max(0, Math.min(n - 1, i + (dir == DOWN ? 1 : -1)));
                py = Compositor.navCentreY(navBar, navSkip, dpi, i);
            } else if (navRight ? dir == LEFT : dir == RIGHT) {
                px = clamp(navRight ? navBar.left - step : navBar.right + step, a.left, a.right - 1);
            }
            return true;
        }
        if (dir != (navRight ? RIGHT : LEFT)) return false;
        float nx = px + (navRight ? step : -step);
        if (navRight ? nx < navBar.left : nx >= navBar.right) return false;
        px = navBar.exactCenterX();
        py = Compositor.navCentreY(navBar, navSkip, dpi, navButtonNear(py));
        return true;
    }

    /** The bar's button at this height, or the nearest one above or below the buttons. */
    private int navButtonNear(float y) {
        int i = Compositor.navButton(navBar, navSkip, dpi, y);
        if (i >= 0) return i;
        return y < Compositor.navCentreY(navBar, navSkip, dpi, 0) ? 0 : Compositor.navCount() - 1;
    }

    /**
     * Scrolls the app by about half a screen with the mouse wheel: up or down at the pointer's x and
     * the app's middle height (so a bar at the very top or bottom doesn't catch it), left or right
     * at the pointer's y and the middle width.
     */
    private void edgeScroll(int dir) {
        if (content.isEmpty()) return;
        boolean vertical = dir == UP || dir == DOWN;
        float notch = 64f * dpi / 160f;
        int n = Math.max(1, Math.round(SCROLL_SHARE * (vertical ? content.height() : content.width()) / notch));
        float x = vertical ? clamp(px, content.left, content.right - 1) : content.exactCenterX();
        float y = vertical ? content.exactCenterY() : clamp(py, content.top, content.bottom - 1);
        float v = dir == DOWN ? -n : dir == UP ? n : 0;
        float h = dir == RIGHT ? n : dir == LEFT ? -n : 0;
        Log.d(TAG, "pointer scroll " + (vertical ? "v " + v : "h " + h));
        inject(Touch.wheel(x - content.left, y - content.top, v, h));
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** The first few drives with pan mode: how the pointer works, once per drive. */
    private void tellPointer() {
        if (pointerTold || Prefs.pointerTold(context) >= POINTER_TELL_TIMES) return;
        pointerTold = true;
        Prefs.addPointerTold(context);
        say("Move the ring, then press to click there. Push past an edge to scroll.", true);
    }

    /** A tap on the navigation bar (worker thread). All but Apps need an open app. */
    private void navTap(int button) {
        if (button < 0) return;
        boolean open = launched != null;
        if (!open && button != Compositor.NAV_APPS) return;
        compositor.navPressed(button);
        // like a finger on the app: wakes the phone again if the user had put it to sleep
        if (car && pushedKeep) call(s -> s.keepRunning(true));
        switch (button) {
            case Compositor.NAV_BACK:
                call(s -> s.key(KeyEvent.KEYCODE_BACK));
                break;
            case Compositor.NAV_APPS:
                main.post(() -> {
                    Nav n = nav;
                    if (n != null) n.apps();
                });
                break;
            case Compositor.NAV_KEYBOARD:
                toggleKeyboard(r -> say(keyboardMessage(r), false));
                break;
            case Compositor.NAV_CLOSE:
                closeApp();
                break;
            case Compositor.NAV_HIDE:
                Prefs.setNavBar(context, false);
                applyContent();
                main.post(listener::onMirrorChanged);
                break;
        }
    }

    /**
     * Closes the app on the display (its tasks are removed, as a swipe in Recents does) and
     * leaves the display empty; the next drive starts at the apps grid.
     */
    public void closeApp() {
        io.post(() -> {
            ComponentName was = launched;
            String[] r = {null};
            call(s -> r[0] = s.closeApp());
            Log.i(TAG, "close " + (was == null ? "-" : was.flattenToShortString()) + ": " + r[0]);
            if (r[0] == null) return; // no service: nothing is shown anyway
            if (!"ok".equals(r[0]) && !"none".equals(r[0])) {
                say("Can't close the app: " + (r[0].startsWith("close: ") ? r[0].substring(7) : r[0]), false);
                return;
            }
            app = null;
            launched = null;
            Prefs.clearLast(context);
            relayout();
            main.post(listener::onMirrorChanged);
        });
    }

    private void say(String text, boolean longer) {
        if (text == null) return;
        main.post(() -> {
            Nav n = nav;
            if (n != null) n.message(text, longer);
        });
    }

    /** A drag's distances; in pan mode, the knob's or touchpad's (they move the pointer). */
    public void scroll(float dx, float dy) {
        io.post(() -> {
            if (panning) movePointer(dx, dy);
            else touch.scroll(dx, dy);
        });
    }

    public void fling(float vx, float vy) {
        io.post(() -> {
            if (!panning) touch.fling(vx, vy);
        });
    }

    public void scale(float fx, float fy, float factor) {
        io.post(() -> {
            float x = fx, y = fy;
            if (x < 0 || y < 0) {
                // no focal point (the car has no touch screen): the pointer's, or the app's middle
                if (panning && px >= 0) {
                    x = px;
                    y = py;
                } else {
                    x = content.exactCenterX();
                    y = content.exactCenterY();
                }
            }
            touch.scale(x - content.left, y - content.top, factor);
        });
    }

    /** A real touch, passed through as it is (the preview's "Direct" mode). */
    void raw(MotionEvent e) {
        MotionEvent copy = MotionEvent.obtain(e);
        copy.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        io.post(() -> {
            copy.offsetLocation(-content.left, -content.top);
            inject(copy);
        });
    }

    /**
     * The keyboard button (car action strip, preview rail). {@code done} gets the service's
     * answer on the main thread; {@link #keyboardMessage} turns it into a short message.
     */
    public void toggleKeyboard(java.util.function.Consumer<String> done) {
        io.post(() -> {
            String[] r = {"error: no display"};
            call(s -> r[0] = s.toggleKeyboard());
            main.post(() -> done.accept(r[0]));
        });
    }

    /** A short message for the keyboard button's answer, or null when the screen says enough. */
    public static String keyboardMessage(String r) {
        if (r == null) return null;
        switch (r) {
            case "shown":
            case "hidden":
                return null;
            case "no field":
                return "Tap a text field first";
            case "old android":
                return "The keyboard button needs Android 11 or newer";
            default:
                return "Keyboard: " + (r.startsWith("error: ") ? r.substring(7) : r);
        }
    }

    /** A setting changed (keyboard, full screen, phone screen): the car's Mirror applies it now. */
    public static void settingsChanged() {
        Mirror m = carShowing;
        if (m != null) m.io.post(m::applyContent);
    }

    /** The app's Back (the car's Back button, the preview's rail). */
    public void back() {
        io.post(() -> {
            // like a finger on the app: wakes the phone again if the user had put it to sleep
            if (car && pushedKeep) call(s -> s.keepRunning(true));
            call(s -> s.key(KeyEvent.KEYCODE_BACK));
        });
    }

    /** Closes the display and stops the worker. */
    public void release() {
        Shell.removeListener(this);
        if (carShowing == this) setCarShowing(null);
        io.post(() -> {
            touch.cancel();
            IDisplayService s = pushedTo;
            if (s != null) {
                try {
                    s.close();
                } catch (RemoteException ignore) {
                    // the service is gone, and the display with it
                }
            }
            compositor.detach();
            compositor.release();
            drop(screen);
            screen = null;
            thread.quitSafely();
        });
    }

    /** Stops the worker without touching the display (a preview that never used it). */
    public void releaseQuietly() {
        Shell.removeListener(this);
        compositor.release();
        thread.quitSafely();
    }

    /** True while AnyAuto is on the car screen: the one display is the car's then. */
    public static boolean carShowing() {
        return carShowing != null;
    }

    /** Called on the main thread whenever {@link #carShowing()} changes. */
    public static void addCarListener(Runnable r) {
        carListeners.addIfAbsent(r);
    }

    public static void removeCarListener(Runnable r) {
        carListeners.remove(r);
    }

    private static void setCarShowing(Mirror m) {
        if (carShowing == m) return;
        carShowing = m;
        new Handler(Looper.getMainLooper()).post(() -> {
            for (Runnable r : carListeners) r.run();
        });
    }

    /** What keeps the app off the screen; null when it is shown. */
    public Problem problem() {
        return problem;
    }

    /** The app on the display now, or null. */
    public ComponentName currentApp() {
        return launched;
    }

    @Override
    public void onShellChanged() {
        io.post(this::sync);
    }

    @Override
    public void inject(MotionEvent e) {
        IDisplayService s = pushedTo;
        if (s == null || displayId < 0) {
            e.recycle();
            return;
        }
        try {
            // a finger on the car screen wakes the phone again if the user had put it to sleep
            if (car && e.getActionMasked() == MotionEvent.ACTION_DOWN) s.keepRunning(true);
            s.touch(e);
        } catch (RemoteException ex) {
            lost(s);
        } finally {
            e.recycle();
        }
    }

    // ------------------------------------------------------------------ worker thread

    /** The navigation bar may come or go: lay out again, or just sync before the first surface. */
    private void relayout() {
        if (screenW > 0 && screenH > 0) applyContent();
        else sync();
    }

    private void contentChanged() {
        io.removeCallbacks(applyContent);
        io.postDelayed(applyContent, CONTENT_SETTLE_MS);
    }

    /** Stable area, else visible area, else the whole surface (an area must fit the surface). */
    private void applyContent() {
        Rect full = new Rect(0, 0, screenW, screenH);
        Rect r;
        if (forced != null && full.contains(forced) && !forced.isEmpty()) r = forced;
        else if (!stable.isEmpty() && full.contains(stable)) r = car && Prefs.fullScreen(context) ? fullScreen(stable) : stable;
        else if (!visible.isEmpty() && full.contains(visible)) r = visible;
        else r = full;
        if (r.isEmpty()) return;
        Rect bar = new Rect();
        int skip = 0;
        boolean right = Prefs.navRight(context);
        // full-width view only; with no app open there is nothing to cover, so the bar stays
        if (car && (Prefs.navBar(context) || app == null) && r.width() >= screenW * 0.8f) {
            int bw = Compositor.navWidth(screenW, dpi);
            if (right) {
                // Android Auto's own button floats at the top right: the buttons start below it,
                // where the stable area starts (in full screen the bar itself goes to the top)
                if (stable.isEmpty()) skip = Math.round(80 * dpi / 160f);
                else if (stable.top > r.top) skip = stable.top - r.top;
                bar.set(r.right - bw, r.top, r.right, r.bottom);
                r = new Rect(r.left, r.top, r.right - bw, r.bottom);
            } else {
                bar.set(r.left, r.top, r.left + bw, r.bottom);
                r = new Rect(r.left + bw, r.top, r.right, r.bottom);
            }
        }
        if (!bar.equals(navBar) || skip != navSkip || right != navRight) {
            navBar.set(bar);
            navSkip = skip;
            navRight = right;
            Log.i(TAG, "navigation bar " + (bar.isEmpty() ? "hidden" : bar.toShortString()
                    + (right ? " (right, buttons from +" + skip + ")" : " (left)")));
        }
        compositor.nav(navBar, navSkip, navRight, launched != null);
        if (!r.equals(content)) {
            content.set(r);
            Log.i(TAG, "content " + content.toShortString() + " of " + screenW + "x" + screenH);
            compositor.content(content, dpi);
            touch.area(content.width(), content.height(), dpi, null);
        }
        if (px >= 0) showPointer(pointerShown); // still inside the app and the bar
        sync();
    }

    /**
     * The setting "Full screen in the car": the app gets the whole surface, edge to edge, the way
     * Google Maps draws its map. The stable area leaves a margin all round (12 px on the desktop
     * head unit, plus the band Android Auto keeps for Apps and Back), which showed as a black frame.
     * The buttons float over the app while shown and hide by themselves, and Android Auto rounds the
     * surface's corners. Fixed, so the app never jumps when the buttons show and hide. Only for
     * the full-width view: the dashboard and launcher cards keep their card.
     */
    private Rect fullScreen(Rect s) {
        if (s.width() < screenW * 0.8f) return s;
        return new Rect(0, 0, screenW, screenH);
    }

    /**
     * The flicker guard's memory (PhonePower): a phone that would not stay dark keeps its screen
     * on in later drives too, by turning on "Keep the phone's screen on" (the user can turn it off
     * again in the menu). Asked every FIGHT_CHECK_MS while the car shows AnyAuto.
     */
    private final Runnable checkFights = this::checkFights;

    private void checkFights() {
        IDisplayService s = pushedTo;
        if (s == null || !car || !pushedKeep) return;
        try {
            if (s.phoneScreenFights()) {
                if (!Prefs.keepPhoneOn(context)) {
                    Prefs.setKeepPhoneOn(context, true);
                    Log.i(TAG, "the phone does not stay dark: Keep the phone's screen on is now on");
                    applyContent(); // pushes the setting
                }
                return;
            }
        } catch (RemoteException e) {
            lost(s);
            return;
        }
        io.postDelayed(checkFights, FIGHT_CHECK_MS);
    }

    private void sync() {
        IDisplayService s = Shell.service();
        if (s == null) {
            pushedTo = null;
            displayId = -1;
            setProblem(shellProblem(Shell.state()));
            return;
        }
        if (s != pushedTo) {
            // a new service process: nothing of ours is on it yet
            pushedTo = s;
            pushedOn = false;
            pushedKeep = false;
            pushedKeyboard = null;
            pushedPhoneOn = null;
            displayId = -1;
            launched = null;
        }
        try {
            if (content.isEmpty()) {
                setProblem(null); // no surface yet
                return;
            }
            boolean on = screen != null;
            int w = content.width(), h = content.height();
            if (displayId < 0 || on != pushedOn || w != pushedW || h != pushedH || dpi != pushedDpi) {
                // without a screen the display gets no surface: it pauses instead of drawing for nobody
                displayId = s.show(on ? compositor.input() : null, w, h, dpi);
                pushedOn = on;
                pushedW = w;
                pushedH = h;
                pushedDpi = dpi;
                if (displayId < 0) {
                    setProblem(Shell.rootOnOldAndroid()
                            ? new Problem("Shizuku runs as root",
                                    "On Android 13 and older, start Shizuku with Wireless debugging instead.")
                            : new Problem("Can't make the car display", s.lastError()));
                    return;
                }
                pushedKeyboard = null; // a new display, or a new size: say it again
                Log.i(TAG, s.info());
                // for the phone's Home screen: does this phone's screen go dark in the car?
                Prefs.setPhoneGroup(context, s.phoneGroup());
            }
            // the car: where the setting says; the preview: on the phone, since a finger on the
            // phone takes the focus and would close a keyboard on the display (DisplayService)
            Boolean keyboard = car && Prefs.keyboardOnCar(context);
            if (!keyboard.equals(pushedKeyboard)) {
                s.keyboardHere(keyboard);
                pushedKeyboard = keyboard;
            }
            Boolean phoneOn = car && Prefs.keepPhoneOn(context);
            if (!phoneOn.equals(pushedPhoneOn)) {
                s.phoneScreenOn(phoneOn);
                pushedPhoneOn = phoneOn;
            }
            boolean keep = screen != null || hold;
            if (car && pushedKeep != keep) {
                pushedKeep = keep;
                s.keepRunning(keep);
                if (keep) setCarShowing(this);
                else if (carShowing == this) setCarShowing(null);
                io.removeCallbacks(checkFights);
                if (keep) io.postDelayed(checkFights, FIGHT_CHECK_MS);
                if (keep) tellDark(s);
            }
            if (app != null && !app.equals(launched)) {
                String r = s.launch(app);
                if (!"ok".equals(r)) {
                    setProblem(new Problem("Can't open " + label(app), r + ". Try again from Apps."));
                    return;
                }
                launched = app;
                Log.i(TAG, "opened " + app.flattenToShortString() + " on display " + displayId);
                compositor.nav(navBar, navSkip, navRight, true);
            }
            if (launched == null) compositor.nav(navBar, navSkip, navRight, false);
            setProblem(null);
        } catch (DeadObjectException e) {
            lost(s);
        } catch (RemoteException e) {
            setProblem(new Problem("Can't reach the display service", String.valueOf(e)));
        }
    }

    /**
     * The first few drives: the car says that the phone's dark screen is on purpose, once per
     * drive, when this phone's screen does go dark (it shares the car display's group, and no
     * setting keeps it on). New users otherwise take the black phone for a crash.
     */
    private void tellDark(IDisplayService s) throws RemoteException {
        if (darkTold || Prefs.darkTold(context) >= DARK_TELL_TIMES) return;
        if (s.phoneGroup() != 1 || Prefs.keepPhoneOn(context) || !Prefs.keyboardOnCar(context)) return;
        darkTold = true;
        Prefs.addDarkTold(context);
        say("Your phone's screen is off on purpose while AnyAuto runs. It comes back when you leave AnyAuto.", true);
    }

    private String label(ComponentName c) {
        try {
            PackageManager pm = context.getPackageManager();
            return String.valueOf(pm.getActivityInfo(c, 0).loadLabel(pm));
        } catch (PackageManager.NameNotFoundException e) {
            return c.getPackageName();
        }
    }

    private interface Call {
        void call(IDisplayService s) throws RemoteException;
    }

    private void call(Call c) {
        IDisplayService s = pushedTo;
        if (s == null || displayId < 0) return;
        try {
            c.call(s);
        } catch (RemoteException e) {
            lost(s);
        }
    }

    private void lost(IDisplayService s) {
        Log.w(TAG, "display service died");
        pushedTo = null;
        displayId = -1;
        Shell.lost(s);
        setProblem(shellProblem(Shell.state()));
    }

    private void drop(Surface s) {
        if (s != null && car) s.release();
    }

    private void setProblem(Problem p) {
        updateOverlay(p);
        if (Objects.equals(p, problem)) return;
        problem = p;
        if (p != null) Log.i(TAG, "car screen: " + p.title + " (" + p.text + ")");
        main.post(listener::onMirrorChanged);
    }

    /** What the surface shows when there is no app frame. */
    private void updateOverlay(Problem p) {
        if (p != null) {
            // the car shows the text in its own message box; the phone preview has only this
            compositor.overlay(Compositor.PROBLEM, p.title, car ? null : p.text);
        } else if (pushedTo == null && Shell.state() == Shell.State.CONNECTING) {
            compositor.overlay(Compositor.CONNECTING, "Connecting to Shizuku…", null);
        } else if (app == null) {
            compositor.overlay(Compositor.IDLE, "No app open", car ? "Choose Apps to open one" : null);
        } else {
            compositor.overlay(Compositor.NONE, null, null);
        }
    }

    /** The car's and the phone's wording for a Shizuku state (no problem while connecting). */
    static Problem shellProblem(Shell.State st) {
        switch (st) {
            case NO_SHIZUKU:
                return new Problem("Shizuku isn't installed", "Install Shizuku on the phone, then start it.");
            case NOT_RUNNING:
                return new Problem("Shizuku isn't running", "Start it in the Shizuku app on the phone.");
            case OLD_SHIZUKU:
                return new Problem("Shizuku is too old", "Update Shizuku on the phone.");
            case NO_PERMISSION:
                return new Problem("AnyAuto isn't allowed in Shizuku", "Open AnyAuto on the phone and tap Allow.");
            default:
                return null;
        }
    }
}
