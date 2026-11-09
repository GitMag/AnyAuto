package com.mag.anyauto;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** The phone's apps with a launcher icon (the manifest's queries element makes them visible). */
public final class Apps {
    public static final class App {
        public final ComponentName component;
        public final String label;
        final ResolveInfo info;

        App(ComponentName component, String label, ResolveInfo info) {
            this.component = component;
            this.label = label;
            this.info = info;
        }

        public Drawable icon(PackageManager pm) {
            return info.loadIcon(pm);
        }
    }

    private Apps() {
    }

    /** All launchable apps but AnyAuto itself, sorted by name. */
    static List<App> launchable(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<App> out = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (ri.activityInfo == null || c.getPackageName().equals(ri.activityInfo.packageName)) continue;
            ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            out.add(new App(cn, String.valueOf(ri.loadLabel(pm)), ri));
        }
        Collator col = Collator.getInstance();
        Collections.sort(out, (a, b) -> col.compare(a.label, b.label));
        return out;
    }

    /** The chosen car apps that are still installed, sorted by name. */
    public static List<App> chosen(Context c) {
        Set<String> want = Prefs.carApps(c);
        List<App> out = new ArrayList<>();
        for (App a : launchable(c)) {
            if (want.contains(a.component.flattenToString())) out.add(a);
        }
        return out;
    }

    public static App find(Context c, ComponentName cn) {
        if (cn == null) return null;
        for (App a : launchable(c)) {
            if (a.component.equals(cn)) return a;
        }
        return null;
    }

    public static Bitmap bitmap(Drawable d, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(cv);
        return b;
    }
}
