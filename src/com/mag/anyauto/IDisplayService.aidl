// DisplayService runs in a Shizuku-spawned process with the shell user's rights (uid 2000):
// it owns the virtual display the car screen shows, starts apps on it and injects input.
package com.mag.anyauto;

import android.content.ComponentName;
import android.view.MotionEvent;
import android.view.Surface;

interface IDisplayService {
    // Shizuku convention: destroy() must use this transaction id so unbind can stop it.
    void destroy() = 16777114;

    // Creates the virtual display, or keeps the existing one and gives it this surface and size.
    // surface may be null: the apps keep running, nothing is drawn. Returns the display id, or -1
    // (then lastError() says why).
    int show(in Surface surface, int width, int height, int dpi) = 1;

    // Starts the app's launcher activity on the display, or brings its task there.
    // Returns "ok" or the reason it failed.
    String launch(in ComponentName component) = 2;

    // A touch event in display pixels; the service sets the display id and injects it.
    oneway void touch(in MotionEvent event) = 3;

    // Key press (down + up) on the display, e.g. KEYCODE_BACK.
    oneway void key(int keyCode) = 4;

    // Releases the display. Its tasks move back to the phone screen.
    void close() = 5;

    // Display id, flags used and the last error, for the phone screen and logs.
    String lastError() = 6;
    String info() = 7;

    // Tests without a car ("headless car", Headless.java): the display draws into an ImageReader
    // of this process instead of a surface, and the newest frame is written about once a second to
    // /data/local/tmp/anyauto-headless.png. Returns the display id, or -1.
    int showHeadless(int width, int height, int dpi) = 8;

    // The car shows AnyAuto (true) or not (false): keep the phone awake with its own screen dark
    // while it does, because the display sleeps with the phone (PhonePower.java).
    oneway void keepRunning(boolean on) = 9;
}
