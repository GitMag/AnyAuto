package com.mag.anyauto;

import android.app.ActionBar;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Help: set-up, what happens while in the car, the limits. */
public final class HelpActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Help");
        ActionBar ab = getActionBar();
        if (ab != null) ab.setDisplayHomeAsUpEnabled(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(8), dp(16), dp(16));

        heading(col, "Set up");
        para(col, "1. Install Shizuku and start it (Wireless debugging, then Start). It has to be started again after every reboot.");
        para(col, "2. For a car, install AnyAuto with UnknownAuto: Android Auto only runs apps installed from Google Play. The desktop head unit doesn't check.");
        para(col, "3. Choose the apps for the car. Try shows an app at car size on this phone.");
        para(col, "4. In the car, open AnyAuto and pick an app. Apps, Keyboard and Back are at the top of the car screen; Pan turns drags on.");

        heading(col, "While an app is on the car screen");
        para(col, "The phone stays awake with its screen off. The power button still puts it to sleep; the car screen pauses until you tap it. When Android Auto stops showing AnyAuto, the phone goes back to how it was.");
        para(col, "Tap a text field and the keyboard opens on the car screen. The Keyboard button opens it for the selected text field, or closes it. To type on the phone instead, choose ⋮ → Keyboard → On the phone; the phone's screen then stays on.");
        para(col, "The app uses the whole car screen, and Apps, Keyboard and Back float over its top corner until they hide. To keep them out of the app's way, turn off ⋮ → Full screen in the car.");

        heading(col, "Limits");
        para(col, "Apps that protect their windows (banking, DRM video, incognito tabs) show black.");
        para(col, "The Keyboard button needs Android 11 or newer.");
        para(col, "Apps on the car screen skip the phone's lock.");
        para(col, "There is one car display, so Try is unavailable while AnyAuto is on the car screen.");

        Button ua = Ui.textButton(this, MainActivity.unknownAutoLabel(this));
        ua.setOnClickListener(v -> MainActivity.openUnknownAuto(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        col.addView(ua, lp);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        scroll.setClipToPadding(false);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars()).top
                    : insets.getSystemWindowInsetTop();
            int bottom = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars()).bottom
                    : insets.getSystemWindowInsetBottom();
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
        setContentView(scroll);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void heading(LinearLayout col, String s) {
        TextView t = Ui.text(this, s, 16, true, Ui.text(this));
        t.setPadding(0, dp(20), 0, dp(4));
        col.addView(t);
    }

    private void para(LinearLayout col, String s) {
        TextView t = Ui.text(this, s, 14, false, Ui.text2(this));
        t.setPadding(0, dp(4), 0, dp(4));
        t.setLineSpacing(0, 1.15f);
        col.addView(t);
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
