package com.mag.anyauto;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * The headless car, for tests from a computer without Android Auto: the display draws into an
 * ImageReader in the shell process (which keeps running while the phone sleeps, like Android
 * Auto's surface would), and the newest frame lands in /data/local/tmp/anyauto-headless.png.
 * Input then goes straight to the display: adb shell input -d &lt;id&gt; tap x y. Commands: README,
 * Testing. Exported only to holders of DUMP (the adb shell).
 */
public final class Headless extends BroadcastReceiver {
    private static final String TAG = "AnyAuto";

    @Override
    public void onReceive(Context c, Intent intent) {
        Shell.init(c);
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                IDisplayService s = null;
                for (int i = 0; i < 50 && (s = Shell.service()) == null; i++) Thread.sleep(100);
                if (s == null) {
                    Log.w(TAG, "headless: no display service (" + Shell.state() + ")");
                    return;
                }
                if (intent.getBooleanExtra("stop", false)) {
                    s.close();
                    Log.i(TAG, "headless: display closed");
                    return;
                }
                int id = s.showHeadless(intent.getIntExtra("w", 1280), intent.getIntExtra("h", 480),
                        intent.getIntExtra("dpi", 160));
                Log.i(TAG, "headless: " + (id < 0 ? s.lastError() : s.info()));
                String app = intent.getStringExtra("app");
                ComponentName cn = app == null ? null : ComponentName.unflattenFromString(app);
                if (id >= 0 && cn != null) Log.i(TAG, "headless: launch " + app + ": " + s.launch(cn));
                // like a car surface: keep the phone awake and dark (PhonePower)
                if (id >= 0) s.keepRunning(intent.getBooleanExtra("keep", true));
            } catch (Exception e) {
                Log.w(TAG, "headless failed", e);
            } finally {
                result.finish();
            }
        }, "anyauto-headless").start();
    }
}
