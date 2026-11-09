package com.mag.anyauto.car;

import android.content.ComponentName;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.GridItem;
import androidx.car.app.model.GridTemplate;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Template;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.mag.anyauto.Apps;
import com.mag.anyauto.Mirror;
import com.mag.anyauto.Prefs;

import java.util.List;

/**
 * The apps chosen on the phone, as a grid by name; "Open" under the one on the display now. A
 * tap shows the app on the mirror screen below. Header: Back when an app is on the display
 * (back to it), the app icon on first use.
 */
final class AppsScreen extends Screen {
    // small icons: the whole template goes over binder in one parcel
    private static final int ICON_PX = 96;
    private final Mirror mirror;

    AppsScreen(CarContext c, Mirror mirror) {
        super(c);
        this.mirror = mirror;
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStart(@NonNull LifecycleOwner owner) {
                // the grid is part of using AnyAuto: keep the phone dark while it shows
                mirror.hold(true);
                // apps ticked on the phone meanwhile
                invalidate();
            }

            @Override
            public void onStop(@NonNull LifecycleOwner owner) {
                mirror.hold(false);
            }
        });
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        CarContext c = getCarContext();
        List<Apps.App> apps = Apps.chosen(c);
        if (apps.isEmpty()) {
            return new MessageTemplate.Builder(
                    "No apps chosen yet.\nOpen AnyAuto on the phone and tick the apps for the car.")
                    .setTitle("AnyAuto")
                    .setHeaderAction(Action.APP_ICON)
                    .setIcon(CarIcon.APP_ICON)
                    .build();
        }
        ComponentName open = mirror.currentApp();
        int limit = c.getCarService(ConstraintManager.class)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID);
        PackageManager pm = c.getPackageManager();
        ItemList.Builder list = new ItemList.Builder();
        for (int i = 0; i < Math.min(limit, apps.size()); i++) {
            Apps.App a = apps.get(i);
            CarIcon icon = new CarIcon.Builder(IconCompat.createWithBitmap(Apps.bitmap(a.icon(pm), ICON_PX))).build();
            GridItem.Builder item = new GridItem.Builder()
                    .setTitle(a.label)
                    .setImage(icon, GridItem.IMAGE_TYPE_LARGE)
                    .setOnClickListener(() -> {
                        Prefs.setLast(c, a.component);
                        mirror.open(a.component);
                        getScreenManager().pop();
                    });
            if (a.component.equals(open)) item.setText("Open");
            list.addItem(item.build());
        }
        return new GridTemplate.Builder()
                .setTitle("Apps")
                .setHeaderAction(open != null ? Action.BACK : Action.APP_ICON)
                .setSingleList(list.build())
                .build();
    }
}
