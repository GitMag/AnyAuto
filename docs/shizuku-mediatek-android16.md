# Shizuku UserService startup failure on MediaTek / HyperOS (Android 16)

## Confirmed reproduction (Xiaomi 13T Pro, Android 16, 2026-11-13)

The Shizuku binder is delivered and AnyAuto has permission, but `DisplayService` never connects. The Shizuku server logs:

```text
I/ShizukuServiceStarter: starting service com.mag.anyauto/com.mag.anyauto.DisplayService...
W/ShizukuServiceStarter: unable to start service com.mag.anyauto/com.mag.anyauto.DisplayService...
W/ShizukuServiceStarter: java.lang.reflect.InvocationTargetException
W/ShizukuServiceStarter: Caused by: java.lang.NullPointerException:
    Attempt to invoke virtual method 'boolean java.lang.Object.equals(java.lang.Object)' on a null object reference
W/ShizukuServiceStarter:     at android.app.LoadedApk.makeApplicationInner(LoadedApk.java:1605)
W/ShizukuServiceStarter:     at android.app.LoadedApk.makeApplication(LoadedApk.java:1495)
W/UserServiceRecord: Service record ... is not started in 30000 ms
```

The failure occurs in the **Shizuku service starter**, before `DisplayService` is constructed. Increasing AnyAuto's timeout or retry count cannot repair it. The 35-second timeout and three retries on `fix/shizuku-user-service-timeout` are only diagnostics and recovery for transient failures.

## Upstream evidence

- https://github.com/RikkaApps/Shizuku/issues/1198 — MediaTek / Xiaomi framework may call `Application.getProcessName().equals(...)` while `ActivityThread.mBoundApplication` is null in the Shizuku helper process.
- https://github.com/RikkaApps/Shizuku/issues/2561 — same `LoadedApk.makeApplicationInner` failure on Android 16, Shizuku 13.6.0.
- https://github.com/RikkaApps/Shizuku/issues/2048 — regression from 13.5.4 to 13.6.0 on some OEM devices.
- https://github.com/RikkaApps/Shizuku/issues/2633 — a different Android 16 UserService regression; Shizuku 13.5.4 worked on that reporter's device.

## Workarounds / next implementation

1. **A/B test only (not verified on this Xiaomi):** try official Shizuku 13.5.4 instead of 13.6.0, then restart it through wireless debugging and re-authorize AnyAuto. This requires uninstalling the newer Shizuku first, which can reset Shizuku permissions/configuration. Back up settings and plan to re-authorize other apps. Do not download APKs from unknown mirrors.
2. **Robust AnyAuto fix:** introduce an alternative shell process transport using the Shizuku remote-process API (if available on the installed server), bypassing `bindUserService` / `ServiceStarter`. It must preserve the existing `IDisplayService` Binder interface, surface transfer, lifetime cleanup, permissions and security model. This needs implementation and tests on Android 16; **not implemented by this documentation change**.
3. **Upstream fix:** Shizuku should initialize the app process identity before calling `LoadedApk.makeApplication()` or handle OEM-specific initialization without crashing.

## Focused diagnostic capture

```sh
logcat -d -v time -b all | grep -E 'ShizukuServiceStarter|UserServiceManager|UserServiceRecord|LoadedApk.makeApplication|AnyAuto' | tail -n 100
```

Avoid misdiagnosing unrelated `INTERACT_ACROSS_USERS`, SELinux `usap_pool_primary`, or other app's Wi-Fi warnings as the cause: the exception above is the decisive evidence.
