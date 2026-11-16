package com.mag.anyauto;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;
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
    // every Shizuku build and fork keeps these two names, whatever its package is called
    private static final String MANAGER_PROVIDER = "moe.shizuku.manager.ShizukuManagerProvider";
    private static final String API_PERMISSION = "moe.shizuku.manager.permission.API_V23";
    private static final long MANAGER_CACHE_MS = 3000;
    private static final int REQUEST = 4100;
    // bump whenever DisplayService changes, so Shizuku starts the new code instead of a running copy
    private static final int SERVICE_VERSION = 14;

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
    private static volatile String managerPkg;
    private static volatile long managerAt;

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

    /**
     * A live binder counts first, whoever sent it: Shizuku, a fork under another package name
     * (Shevery) or Sui, which has no app at all. Only without one does the installed app decide
     * between "not running" and "not installed".
     */
    static State state() {
        if (service != null) return State.READY;
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.isPreV11()) return State.OLD_SHIZUKU;
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return State.NO_PERMISSION;
                return State.CONNECTING;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku state check failed", t);
        }
        return manager() != null ? State.NOT_RUNNING : State.NO_SHIZUKU;
    }

    /**
     * Shizuku was started with root, on Android 13 or older. The display service then runs as
     * root, and Android 13 and older refuse root a virtual display: it names the shell's package,
     * which isn't root's ("packageName must match the calling uid"). Android 14 lets root through.
     * The service can't turn into the shell either: binder keeps the identity the process had
     * when it opened /dev/binder. Shizuku has to be started with Wireless debugging (or adb).
     */
    static boolean rootOnOldAndroid() {
        if (Build.VERSION.SDK_INT > 33) return false;
        try {
            return Shizuku.pingBinder() && Shizuku.getUid() == 0;
        } catch (Throwable t) {
            return false;
        }
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

    /**
     * The installed Shizuku app, or null: Shizuku itself or a fork. Forks may use their own package
     * name (Shevery: com.hamondev.shevery, its stealth build a random one), but they all keep the
     * manager's binder provider class, so that is looked for first. Then the official package
     * name, then whichever app defines Shizuku's permission (Shevery's compatibility stub).
     * Apps with a launcher icon are visible to AnyAuto (manifest queries), so the provider scan
     * also works on Android 11 and newer. Cached for a few seconds: the home screen asks often.
     */
    static String manager() {
        long now = SystemClock.elapsedRealtime();
        if (managerAt == 0 || now - managerAt > MANAGER_CACHE_MS) {
            managerPkg = findManager();
            managerAt = now;
        }
        return managerPkg;
    }

    /** The Shizuku app's name for buttons and messages ("Shevery"), or "Shizuku". */
    static String managerLabel() {
        String pkg = manager();
        if (pkg != null && !SHIZUKU_PACKAGE.equals(pkg)) {
            try {
                PackageManager pm = app.getPackageManager();
                CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                if (label != null && label.length() > 0) return label.toString();
            } catch (PackageManager.NameNotFoundException ignored) {
                // removed since the last scan
            }
        }
        return "Shizuku";
    }

    private static String findManager() {
        PackageManager pm = app.getPackageManager();
        try {
            List<ProviderInfo> providers = pm.queryContentProviders(null, 0, 0);
            if (providers != null) {
                for (ProviderInfo p : providers) {
                    if (MANAGER_PROVIDER.equals(p.name)) return p.packageName;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "provider scan failed", e);
        }
        try {
            pm.getPackageInfo(SHIZUKU_PACKAGE, 0);
            return SHIZUKU_PACKAGE;
        } catch (PackageManager.NameNotFoundException ignored) {
            // no official package
        }
        try {
            return pm.getPermissionInfo(API_PERMISSION, 0).packageName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
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
