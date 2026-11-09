package com.mag.anyauto;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

import rikka.shizuku.Shizuku;

/**
 * The app's one connection to {@link DisplayService} through Shizuku, shared by the car session
 * and the phone screens. Keeps the user service bound while the app process lives; Shizuku stops
 * the service when the process dies (daemon off), which releases the virtual display with it.
 * Listeners are called on the main thread whenever {@link #state()} may have changed.
 */
public final class Shell {
    private static final String TAG = "AnyAuto";
    static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final int REQUEST = 4100;
    // bump whenever DisplayService changes, so Shizuku starts the new code instead of a running copy
    private static final int SERVICE_VERSION = 10;

    enum State { NO_SHIZUKU, NOT_RUNNING, OLD_SHIZUKU, NO_PERMISSION, CONNECTING, READY }

    interface Listener {
        void onShellChanged();
    }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static Context app;
    private static Shizuku.UserServiceArgs args;
    private static volatile IDisplayService service;
    private static volatile boolean binding;

    private static final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (binder == null || !binder.pingBinder()) {
                Log.w(TAG, "Shizuku user service came without a live binder");
                binding = false;
                changed();
                return;
            }
            service = IDisplayService.Stub.asInterface(binder);
            binding = false;
            Log.i(TAG, "display service connected");
            changed();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "display service disconnected");
            service = null;
            binding = false;
            changed();
        }
    };

    private Shell() {
    }

    /** Once per process, from the main thread; safe to call again. */
    public static void init(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        args = new Shizuku.UserServiceArgs(new ComponentName(app.getPackageName(), DisplayService.class.getName()))
                .daemon(false).processNameSuffix("display").debuggable(false).version(SERVICE_VERSION);
        Shizuku.addBinderReceivedListenerSticky(() -> {
            connect();
            changed();
        }, main);
        Shizuku.addBinderDeadListener(() -> {
            service = null;
            binding = false;
            changed();
        }, main);
        Shizuku.addRequestPermissionResultListener((code, result) -> {
            if (code != REQUEST) return;
            connect();
            changed();
        }, main);
    }

    static State state() {
        if (service != null) return State.READY;
        if (!installed()) return State.NO_SHIZUKU;
        try {
            if (!Shizuku.pingBinder()) return State.NOT_RUNNING;
            if (Shizuku.isPreV11()) return State.OLD_SHIZUKU;
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return State.NO_PERMISSION;
        } catch (Throwable t) {
            return State.NOT_RUNNING;
        }
        return State.CONNECTING;
    }

    /** The bound service, or null; starts binding when Shizuku is ready for it. */
    static IDisplayService service() {
        IDisplayService s = service;
        if (s == null) connect();
        return s;
    }

    /** Binds the user service if Shizuku runs, the app is allowed and no bind is under way. */
    static void connect() {
        if (service != null || binding || app == null) return;
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()
                    || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            binding = true;
            Shizuku.bindUserService(args, connection);
        } catch (Throwable t) {
            binding = false;
            Log.w(TAG, "Shizuku bind failed", t);
        }
    }

    /** Called when a call fails with a dead binder: forget the service and bind again. */
    static void lost(IDisplayService s) {
        if (service == s) {
            service = null;
            binding = false;
            changed();
        }
    }

    static void requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST);
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku permission request failed", t);
        }
    }

    static boolean installed() {
        try {
            app.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void changed() {
        main.post(() -> {
            for (Listener l : listeners) l.onShellChanged();
        });
    }
}
