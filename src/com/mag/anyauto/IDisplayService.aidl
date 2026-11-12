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

    // The car shows AnyAuto (true) or not (false). If the display shares the phone's display group
    // it sleeps with the phone, so the phone is kept awake with its own screen dark (PhonePower);
    // if it has a group of its own, the phone is left alone (DisplayService.applyPhoneMode).
    oneway void keepRunning(boolean on) = 9;

    // Where the keyboard opens: on the display itself (true, the car: nobody touches the dark
    // phone) or on the phone's screen (false: the preview, where a finger on the phone moves the
    // focus there and would close a keyboard on the display; or the car with the setting "On the
    // phone", which also keeps the phone's screen on). Default true. Kept for new displays.
    oneway void keyboardHere(boolean here) = 10;

    // The keyboard button: hides an open keyboard (Back), or opens it for the focused text field
    // (a tap on the field). Returns "shown", "hidden", "no field", "old android" (needs
    // Android 11) or "error: ...".
    String toggleKeyboard() = 11;

    // The setting "Keep the phone's screen on" (car only): the phone stays awake with its screen
    // on while the car shows AnyAuto, instead of dark or left alone (DisplayService.applyPhoneMode).
    oneway void phoneScreenOn(boolean on) = 12;

    // The flicker guard (PhonePower): true once this phone turned its screen back on by itself, or
    // fell asleep, when AnyAuto powered the screen off; its screen then stays on instead.
    boolean phoneScreenFights() = 13;

    // Closes the app on the display, like swiping it away in Recents: every task on the display
    // is removed. Returns "ok", "none" (nothing was open there) or the reason it failed.
    String closeApp() = 14;

    // The display's display group: 1 = the phone's (the phone is kept awake while the car shows
    // AnyAuto, its screen dark unless a setting keeps it on), 0 = its own (the phone is left
    // alone), -1 = no display yet.
    int phoneGroup() = 15;
}
