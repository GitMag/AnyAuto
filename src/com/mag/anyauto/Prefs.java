package com.mag.anyauto;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The apps chosen for the car and the one shown last. */
public final class Prefs {
    private static final String CAR_APPS = "car_apps";
    private static final String LAST = "last";

    private Prefs() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("anyauto", Context.MODE_PRIVATE);
    }

    static Set<String> carApps(Context c) {
        return new HashSet<>(p(c).getStringSet(CAR_APPS, new HashSet<>()));
    }

    static boolean isCarApp(Context c, ComponentName cn) {
        return carApps(c).contains(cn.flattenToString());
    }

    static void setCarApp(Context c, ComponentName cn, boolean on) {
        Set<String> s = carApps(c);
        if (on) s.add(cn.flattenToString());
        else s.remove(cn.flattenToString());
        p(c).edit().putStringSet(CAR_APPS, s).apply();
    }

    static List<ComponentName> carAppComponents(Context c) {
        List<ComponentName> out = new ArrayList<>();
        for (String f : carApps(c)) {
            ComponentName cn = ComponentName.unflattenFromString(f);
            if (cn != null) out.add(cn);
        }
        return out;
    }

    // the last car surface size: the phone preview takes the car's shape
    public static void setCarSize(Context c, int w, int h) {
        if (w > 0 && h > 0) p(c).edit().putInt("car_w", w).putInt("car_h", h).apply();
    }

    /** {width, height} of the last car surface, or null before the first car. */
    static int[] carSize(Context c) {
        int w = p(c).getInt("car_w", 0), h = p(c).getInt("car_h", 0);
        return w > 0 && h > 0 ? new int[]{w, h} : null;
    }

    // the install-for-the-car card was hidden for this version
    static boolean installCardHidden(Context c, long versionCode) {
        return p(c).getLong("install_card_hidden", -1) == versionCode;
    }

    static void hideInstallCard(Context c, long versionCode) {
        p(c).edit().putLong("install_card_hidden", versionCode).apply();
    }

    public static ComponentName last(Context c) {
        String f = p(c).getString(LAST, null);
        return f == null ? null : ComponentName.unflattenFromString(f);
    }

    public static void setLast(Context c, ComponentName cn) {
        p(c).edit().putString(LAST, cn.flattenToString()).apply();
    }
}
