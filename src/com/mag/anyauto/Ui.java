package com.mag.anyauto;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;

/**
 * The phone UI's colour and type tokens and the few building
 * blocks every screen uses. Colours are Material You system colours on Android 12+, with
 * fixed fallbacks for Android 10 and 11; text colours come from the DayNight theme.
 */
final class Ui {
    private Ui() {
    }

    static boolean night(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int sys(Context c, int id, int fallback) {
        return Build.VERSION.SDK_INT >= 31 ? c.getColor(id) : fallback;
    }

    static int accent(Context c) {
        return night(c) ? sys(c, android.R.color.system_accent1_200, 0xFFADC6FF)
                : sys(c, android.R.color.system_accent1_600, 0xFF1F5FBF);
    }

    static int onAccent(Context c) {
        return night(c) ? sys(c, android.R.color.system_accent1_800, 0xFF002F67)
                : sys(c, android.R.color.system_accent1_0, 0xFFFFFFFF);
    }

    static int accentContainer(Context c) {
        return night(c) ? sys(c, android.R.color.system_accent1_700, 0xFF274A80)
                : sys(c, android.R.color.system_accent1_100, 0xFFD8E3FF);
    }

    static int onAccentContainer(Context c) {
        return night(c) ? sys(c, android.R.color.system_accent1_100, 0xFFD8E3FF)
                : sys(c, android.R.color.system_accent1_900, 0xFF0B2D6B);
    }

    static int card(Context c) {
        return night(c) ? sys(c, android.R.color.system_neutral1_800, 0xFF1E2024)
                : sys(c, android.R.color.system_neutral1_50, 0xFFEEF0F6);
    }

    static int outline(Context c) {
        return night(c) ? sys(c, android.R.color.system_neutral2_700, 0xFF44474F)
                : sys(c, android.R.color.system_neutral2_200, 0xFFC5C6CC);
    }

    static int text(Context c) {
        return attrColor(c, android.R.attr.textColorPrimary);
    }

    static int text2(Context c) {
        return attrColor(c, android.R.attr.textColorSecondary);
    }

    private static int attrColor(Context c, int attr) {
        TypedArray a = c.obtainStyledAttributes(new int[]{attr});
        try {
            ColorStateList l = a.getColorStateList(0);
            return l != null ? l.getDefaultColor() : 0xFF000000;
        } finally {
            a.recycle();
        }
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, CharSequence s, int sp, boolean medium, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (medium) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** The one filled (accent) button of a screen or card: a 48 dp pill. */
    static Button filled(Context c, String label) {
        Button b = baseButton(c, label, onAccent(c));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                round(accent(c), dp(c, 24)), null));
        return b;
    }

    /** A secondary action: accent text, no fill. */
    static Button textButton(Context c, String label) {
        Button b = baseButton(c, label, accent(c));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(accent(c) & 0x33FFFFFF),
                null, round(0xFFFFFFFF, dp(c, 24))));
        return b;
    }

    private static Button baseButton(Context c, String label, int textColor) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(textColor);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(c, 48));
        b.setMinimumHeight(dp(c, 48));
        b.setPadding(dp(c, 20), 0, dp(c, 20), 0);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    static Drawable icon(Context c, int res, int tint) {
        Drawable d = c.getDrawable(res).mutate();
        d.setTint(tint);
        return d;
    }
}
