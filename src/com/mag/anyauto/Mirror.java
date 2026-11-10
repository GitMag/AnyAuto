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
 * <p>The display outlives the screen surface: without one (another app in front, the apps grid)
 * it is paused, and the app is still there when the surface comes back. {@link #release()}
 * closes the display; its tasks move back to the phone screen.
 */
public final class Mirror implements Touch.Sink, Shell.Listener {
    private static final String TAG = "AnyAuto";
    // the car sends the visible and the stable area one after the other: resize once
    private static final long CONTENT_SETTLE_MS = 150;

    public interface Listener {
        /** Main thread: {@link #problem()} may have changed. */
        void onMirrorChanged();
    }

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
    private boolean hold;             // the car shows AnyAuto's apps grid
    private ComponentName app;        // what should be on the display
    private IDisplayService pushedTo; // the service the state below belongs to
    private boolean pushedOn;
    private int pushedW, pushedH, pushedDpi;
    private boolean pushedKeep;
    private Boolean pushedKeyboard;   // keyboard on the display (true) or on the phone (false)
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
            sync();
        });
    }

    // ---- input in screen-surface coordinates (the car's or the preview's)

    public void click(float x, float y) {
        io.post(() -> {
            if (content.contains((int) x, (int) y)) touch.click(x - content.left, y - content.top);
        });
    }

    public void scroll(float dx, float dy) {
        touch.scroll(dx, dy);
    }

    public void fling(float vx, float vy) {
        touch.fling(vx, vy);
    }

    public void scale(float fx, float fy, float factor) {
        io.post(() -> touch.scale(fx - content.left, fy - content.top, factor));
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

    /** A setting changed (keyboard, full screen): the car's Mirror applies it now. */
    public static void settingsChanged() {
        Mirror m = carShowing;
        if (m != null) m.io.post(m::applyContent);
    }

    public void back() {
        io.post(() -> call(s -> s.key(KeyEvent.KEYCODE_BACK)));
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
        if (!r.equals(content)) {
            content.set(r);
            Log.i(TAG, "content " + content.toShortString() + " of " + screenW + "x" + screenH);
            compositor.content(content, dpi);
            touch.area(content.width(), content.height(), dpi, null);
        }
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
                    setProblem(new Problem("Can't make the car display", s.lastError()));
                    return;
                }
                pushedKeyboard = null; // a new display, or a new size: say it again
                Log.i(TAG, s.info());
            }
            // the car: where the setting says; the preview: on the phone, since a finger on the
            // phone takes the focus and would close a keyboard on the display (DisplayService)
            Boolean keyboard = car && Prefs.keyboardOnCar(context);
            if (!keyboard.equals(pushedKeyboard)) {
                s.keyboardHere(keyboard);
                pushedKeyboard = keyboard;
            }
            boolean keep = screen != null || hold;
            if (car && pushedKeep != keep) {
                pushedKeep = keep;
                s.keepRunning(keep);
                if (keep) setCarShowing(this);
                else if (carShowing == this) setCarShowing(null);
            }
            if (app != null && !app.equals(launched)) {
                String r = s.launch(app);
                if (!"ok".equals(r)) {
                    setProblem(new Problem("Can't open " + label(app), r + ". Try again from Apps."));
                    return;
                }
                launched = app;
                Log.i(TAG, "opened " + app.flattenToShortString() + " on display " + displayId);
            }
            setProblem(null);
        } catch (DeadObjectException e) {
            lost(s);
        } catch (RemoteException e) {
            setProblem(new Problem("Can't reach the display service", String.valueOf(e)));
        }
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
            compositor.overlay(Compositor.PROBLEM, p.title, null);
        } else if (pushedTo == null && Shell.state() == Shell.State.CONNECTING) {
            compositor.overlay(Compositor.CONNECTING, "Connecting to Shizuku…", null);
        } else if (app == null) {
            compositor.overlay(Compositor.IDLE, "No app open", car ? "Tap Apps to open one" : null);
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
