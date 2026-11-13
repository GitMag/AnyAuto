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
    private static final String KEYBOARD_ON_CAR = "keyboard_on_car";
    private static final String FULL_SCREEN = "full_screen";
    private static final String KEEP_PHONE_ON = "keep_phone_on";
    private static final String AUTO_UPDATE = "auto_update";
    private static final String UPDATE_CHECKED = "update_checked";
    private static final String UPDATE_VERSION = "update_version";
    private static final String NAV_BAR = "nav_bar";
    private static final String NAV_RIGHT = "nav_right";
    private static final String PHONE_GROUP = "phone_group";
    private static final String DARK_NOTE_HIDDEN = "dark_note_hidden";
    private static final String DARK_TOLD = "dark_told";
    private static final String CAR_KNOB = "car_knob";
    private static final String POINTER_TOLD = "pointer_told";

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

    /** The app was closed on the car screen: the next drive starts at the apps grid. */
    public static void clearLast(Context c) {
        p(c).edit().remove(LAST).apply();
    }

    /** AnyAuto's navigation bar on the car screen (Back, Apps, Keyboard, Close, Hide); default shown. */
    public static boolean navBar(Context c) {
        return p(c).getBoolean(NAV_BAR, true);
    }

    public static void setNavBar(Context c, boolean on) {
        p(c).edit().putBoolean(NAV_BAR, on).apply();
    }

    /** The navigation bar on the car screen's right edge instead of the left (default left). */
    public static boolean navRight(Context c) {
        return p(c).getBoolean(NAV_RIGHT, false);
    }

    public static void setNavRight(Context c, boolean right) {
        p(c).edit().putBoolean(NAV_RIGHT, right).apply();
    }

    /**
     * What the last display told about this phone ({@code IDisplayService.phoneGroup}): 1 = the car
     * display shares the phone's display group, so the phone's screen goes dark while the car shows
     * AnyAuto; 0 = it has its own, the phone is left alone; -1 = not known yet.
     */
    static int phoneGroup(Context c) {
        return p(c).getInt(PHONE_GROUP, -1);
    }

    static void setPhoneGroup(Context c, int g) {
        if (g >= 0 && g != phoneGroup(c)) p(c).edit().putInt(PHONE_GROUP, g).apply();
    }

    /** The Home card "The phone's screen turns off in the car" was dismissed. */
    static boolean darkNoteHidden(Context c) {
        return p(c).getBoolean(DARK_NOTE_HIDDEN, false);
    }

    static void hideDarkNote(Context c) {
        p(c).edit().putBoolean(DARK_NOTE_HIDDEN, true).apply();
    }

    /** How many drives the car screen said that the phone's dark screen is on purpose. */
    static int darkTold(Context c) {
        return p(c).getInt(DARK_TOLD, 0);
    }

    static void addDarkTold(Context c) {
        p(c).edit().putInt(DARK_TOLD, darkTold(c) + 1).apply();
    }

    /**
     * The car has a rotary knob or a touchpad: Android Auto showed it the pan button (which touch
     * screens don't get) and the user pressed it. Its action strip then has Apps too.
     */
    public static boolean carKnob(Context c) {
        return p(c).getBoolean(CAR_KNOB, false);
    }

    static void setCarKnob(Context c) {
        if (!carKnob(c)) p(c).edit().putBoolean(CAR_KNOB, true).apply();
    }

    /** Forgets the knob car and the pointer hints (tests: the headless car's {@code --ez knob false}). */
    static void forgetCarKnob(Context c) {
        p(c).edit().remove(CAR_KNOB).remove(POINTER_TOLD).apply();
    }

    /** How many drives the car explained the pointer (knob and touchpad cars). */
    static int pointerTold(Context c) {
        return p(c).getInt(POINTER_TOLD, 0);
    }

    static void addPointerTold(Context c) {
        p(c).edit().putInt(POINTER_TOLD, pointerTold(c) + 1).apply();
    }

    /** Where the keyboard opens while the car shows an app: on the car screen (default) or the phone. */
    public static boolean keyboardOnCar(Context c) {
        return p(c).getBoolean(KEYBOARD_ON_CAR, true);
    }

    public static void setKeyboardOnCar(Context c, boolean onCar) {
        p(c).edit().putBoolean(KEYBOARD_ON_CAR, onCar).apply();
    }

    /** The app also uses the band Android Auto keeps for Apps and Back (default on). */
    public static boolean fullScreen(Context c) {
        return p(c).getBoolean(FULL_SCREEN, true);
    }

    public static void setFullScreen(Context c, boolean on) {
        p(c).edit().putBoolean(FULL_SCREEN, on).apply();
    }

    /** The phone stays awake with its screen on while the car shows AnyAuto (default off). */
    public static boolean keepPhoneOn(Context c) {
        return p(c).getBoolean(KEEP_PHONE_ON, false);
    }

    public static void setKeepPhoneOn(Context c, boolean on) {
        p(c).edit().putBoolean(KEEP_PHONE_ON, on).apply();
    }

    /** Check GitHub for a new version when AnyAuto is opened (default on). */
    public static boolean autoUpdate(Context c) {
        return p(c).getBoolean(AUTO_UPDATE, true);
    }

    public static void setAutoUpdate(Context c, boolean on) {
        p(c).edit().putBoolean(AUTO_UPDATE, on).apply();
    }

    static long lastUpdateCheck(Context c) {
        return p(c).getLong(UPDATE_CHECKED, 0);
    }

    static void setLastUpdateCheck(Context c, long when) {
        p(c).edit().putLong(UPDATE_CHECKED, when).apply();
    }

    /** The downloaded update's version name (Updater), or null. */
    static String updateVersion(Context c) {
        return p(c).getString(UPDATE_VERSION, null);
    }

    static void setUpdateVersion(Context c, String v) {
        p(c).edit().putString(UPDATE_VERSION, v).apply();
    }
}
