package com.mag.anyauto;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Shell side ({@link DisplayService}'s process): keeps the phone awake with its own screen dark
 * while the car shows AnyAuto.
 *
 * <p>Why: a virtual display belongs to a display group, and the group sleeps with its power
 * group: its apps pause and nothing reaches the car. {@code FLAG_OWN_DISPLAY_GROUP} would give it
 * its own group, but Android 17 builds that support desktop mode put every virtual display in the
 * phone's group anyway (DisplayGroupAllocator; checked on the April 2026 emulator build). So the
 * phone must stay awake. Instead of keeping its screen lit, only the panel is powered off
 * ({@code IDisplayManager.requestDisplayPower}, MANAGE_DISPLAYS; the same idea as scrcpy's
 * "turn screen off"), and powered off again whenever the system turns it back on (dimming,
 * brightness changes).
 *
 * <p>The power button still wins: it puts the phone to sleep and the car screen pauses. The
 * next tap on the car screen wakes the phone, dark again (the car side calls {@link #keep} with
 * true on every touch-down; touches of the phone preview must not). When the car no longer shows
 * AnyAuto the phone goes back to how it was: back to sleep if we woke it, otherwise its screen
 * comes back on. On the Pixel 9 (Android 17, October build) restoring the panel state was not
 * always enough, so a panel that is still off half a second later gets a sleep-and-wake (the
 * phone then shows its lock screen).
 */
final class PhonePower {
    private static final String TAG = "AnyAuto.shell";
    private static final long WATCH_MS = 1000;
    // the car switches between AnyAuto's grid, the app and other screens: release only when it
    // has stayed away this long, so the phone does not light up and sleep on every switch
    static final long GRACE_MS = 8000;
    // the flicker guard: some phones turn their screen straight back on when it is powered off
    // (reported on a Samsung with One UI 9); fighting that makes the screen flicker without end
    // and the phone unusable. Three darkenings within this window (the screen came back on by
    // itself twice) mean the phone fights, and it then stays on instead.
    private static final int FIGHT_BLANKS = 3;
    private static final long FIGHT_WINDOW_MS = 10_000;
    // the phone fell asleep this soon after we darkened it: that was us, not the power button
    private static final long SLEPT_AFTER_BLANK_MS = 3000;

    private final HandlerThread thread = new HandlerThread("anyauto-power");
    private final Handler h;
    private final DisplayManager dm;
    private final PowerManager pm;
    private final PowerManager.WakeLock lock;
    private boolean wanted; // the car shows AnyAuto
    private volatile boolean armed; // we hold the phone awake (and dark) right now
    private volatile boolean dark;  // false: the keyboard is on the phone, so its screen stays on
    private boolean wokeIt; // the phone was asleep when we armed
    private volatile boolean fights; // the phone would not stay dark: its screen stays on now
    private final long[] blanks = new long[FIGHT_BLANKS]; // when we last darkened it (ring)
    private int blankCount;
    private long lastBlank;
    private Object idm;
    private Method requestDisplayPower;

    PhonePower(Context shell, DisplayManager dm, boolean dark) {
        this.dark = dark;
        thread.start();
        h = new Handler(thread.getLooper());
        this.dm = dm;
        pm = powerManager(shell);
        // a screen wake lock keeps the phone's display group awake; ACQUIRE_CAUSES_WAKEUP wakes it
        lock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "AnyAuto:car");
        lock.setReferenceCounted(false);
        dm.registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int id) {
            }

            @Override
            public void onDisplayRemoved(int id) {
            }

            @Override
            public void onDisplayChanged(int id) {
                if (id == Display.DEFAULT_DISPLAY && armed) blank();
            }
        }, h);
    }

    /**
     * The car shows AnyAuto (true; also on every touch-down on the car screen, which wakes the
     * phone again if the user had put it to sleep) or not any more (false).
     */
    void keep(boolean on) {
        h.post(() -> {
            h.removeCallbacks(releaseLater);
            if (on) {
                wanted = true;
                arm();
            } else if (wanted) {
                h.postDelayed(releaseLater, GRACE_MS);
            }
        });
    }

    /** The display is closed: let the phone go now. */
    void releaseNow() {
        h.post(() -> {
            h.removeCallbacks(releaseLater);
            wanted = false;
            disarm();
        });
    }

    private final Runnable releaseLater = this::releaseLater;

    private void releaseLater() {
        wanted = false;
        disarm();
    }

    /**
     * Dark (true, default) or with the phone's screen on (false: the keyboard setting "On the
     * phone"). Once we have powered the panel off, Android does not show a keyboard on it again
     * even after it is lit (its insets control never arrives; Android 17 emulator), so in that
     * mode the panel is never powered off at all.
     */
    void setDark(boolean on) {
        h.post(() -> {
            if (dark == on) return;
            dark = on;
            if (!armed) return;
            if (on) {
                blank();
            } else {
                setPhonePower(Display.STATE_UNKNOWN);
                h.postDelayed(checkRestored, 500);
            }
        });
    }

    /** True once the flicker guard found that this phone does not stay dark. */
    boolean fights() {
        return fights;
    }

    String describe() {
        return wanted ? (armed ? (fights ? "phone kept awake, screen on (it would not stay off)"
                : dark ? "phone kept awake, screen off" : "phone kept awake, screen on")
                : "phone asleep, car screen paused") : "phone untouched";
    }

    // ------------------------------------------------------------------ power thread

    private void arm() {
        h.removeCallbacks(checkRestored);
        if (armed) {
            blank();
            return;
        }
        wokeIt = !pm.isInteractive();
        lock.acquire();
        armed = true;
        Log.i(TAG, "phone kept awake with its screen " + (dark ? "off" : "on") + (wokeIt ? " (woken for the car)" : ""));
        blank();
        h.removeCallbacks(watch);
        h.postDelayed(watch, WATCH_MS);
    }

    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            if (!armed) return;
            if (!pm.isInteractive()) {
                if (dark && !fights && SystemClock.uptimeMillis() - lastBlank < SLEPT_AFTER_BLANK_MS) {
                    // asleep right after we darkened it: the phone treats a dark screen as sleep
                    giveUpDark("it falls asleep when its screen is powered off");
                    h.postDelayed(this, WATCH_MS);
                    return;
                }
                // the power button: let it sleep; the next tap on the car screen wakes it again
                Log.i(TAG, "phone put to sleep by the user; the car screen pauses until it is tapped");
                armed = false;
                lock.release();
                return;
            }
            blank();
            h.postDelayed(this, WATCH_MS);
        }
    };

    private void disarm() {
        h.removeCallbacks(watch);
        if (!armed) return;
        armed = false;
        if (wokeIt) {
            goToSleep();
        } else {
            setPhonePower(Display.STATE_UNKNOWN); // 0: back to the state the system wants
            h.postDelayed(checkRestored, 500);
        }
        lock.release();
        Log.i(TAG, "phone released" + (wokeIt ? ", back to sleep" : ", screen back on"));
    }

    /** The panel is still off although the phone is awake: sleep and wake it (lock screen). */
    private final Runnable checkRestored = this::checkRestored;

    private void checkRestored() {
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (armed || d == null || d.getState() != Display.STATE_OFF || !pm.isInteractive()) return;
        Log.i(TAG, "phone screen still off after release: sleep and wake");
        goToSleep();
        h.postDelayed(this::wakeUp, 300);
    }

    private void wakeUp() {
        try {
            try {
                pm.getClass().getMethod("wakeUp", long.class, int.class, String.class)
                        .invoke(pm, SystemClock.uptimeMillis(), 0, "AnyAuto");
            } catch (NoSuchMethodException e) {
                pm.getClass().getMethod("wakeUp", long.class).invoke(pm, SystemClock.uptimeMillis());
            }
        } catch (Throwable t) {
            Log.w(TAG, "wakeUp failed", t);
        }
    }

    private void blank() {
        // asleep (the power button, or going to sleep right now): nothing to darken, and not a
        // darkening that the flicker guard should count
        if (!dark || fights || !pm.isInteractive()) return;
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (d != null && d.getState() == Display.STATE_OFF) return;
        long now = SystemClock.uptimeMillis();
        blanks[blankCount % FIGHT_BLANKS] = now;
        blankCount++;
        // the oldest of the last FIGHT_BLANKS darkenings sits where the next one will go
        if (blankCount >= FIGHT_BLANKS && now - blanks[blankCount % FIGHT_BLANKS] < FIGHT_WINDOW_MS) {
            giveUpDark("its screen keeps coming back on");
            return;
        }
        lastBlank = now;
        setPhonePower(Display.STATE_OFF);
    }

    /** The flicker guard: stop powering the screen off; the phone stays awake with it on. */
    private void giveUpDark(String why) {
        fights = true;
        Log.w(TAG, "phone: " + why + ". It stays on from now on, so it does not flicker");
        h.removeCallbacks(checkRestored);
        setPhonePower(Display.STATE_UNKNOWN);
        if (!pm.isInteractive()) wakeUp();
    }

    /** IDisplayManager.requestDisplayPower: (int, int state) on Android 17, (int, boolean on) before. */
    private void setPhonePower(int state) {
        try {
            if (requestDisplayPower == null) {
                Object global = Class.forName("android.hardware.display.DisplayManagerGlobal")
                        .getMethod("getInstance").invoke(null);
                Field f = global.getClass().getDeclaredField("mDm");
                f.setAccessible(true);
                idm = f.get(global);
                for (Method m : idm.getClass().getMethods()) {
                    if (m.getName().equals("requestDisplayPower") && m.getParameterTypes().length == 2) {
                        requestDisplayPower = m;
                    }
                }
                if (requestDisplayPower == null) throw new NoSuchMethodException("requestDisplayPower");
            }
            Object arg = requestDisplayPower.getParameterTypes()[1] == boolean.class
                    ? (Object) (state != Display.STATE_OFF) : (Object) state;
            requestDisplayPower.invoke(idm, Display.DEFAULT_DISPLAY, arg);
        } catch (Throwable t) {
            Log.w(TAG, "phone screen power " + state + " failed", t);
        }
    }

    private void goToSleep() {
        try {
            pm.getClass().getMethod("goToSleep", long.class).invoke(pm, SystemClock.uptimeMillis());
        } catch (Throwable t) {
            Log.w(TAG, "goToSleep failed", t);
        }
    }

    /** A PowerManager whose calls name com.android.shell, the package of uid 2000. */
    private static PowerManager powerManager(Context c) {
        try {
            return c.createPackageContext("com.android.shell", 0).getSystemService(PowerManager.class);
        } catch (Exception e) {
            return c.getSystemService(PowerManager.class);
        }
    }
}
