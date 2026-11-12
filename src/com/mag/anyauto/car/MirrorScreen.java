package com.mag.anyauto.car;

import android.graphics.Rect;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.MessageInfo;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.mag.anyauto.Mirror;
import com.mag.anyauto.Prefs;
import com.mag.anyauto.R;

/**
 * The car screen: a NavigationTemplate whose surface the virtual display draws into. The controls
 * are AnyAuto's own navigation bar on the surface (Back, Apps, Keyboard, Close, Hide; drawn by
 * the compositor, tapped through {@link Mirror}). Android Auto's action strip, which floats over
 * the app and must hold at least one button, has only the one that shows or hides that bar; the
 * map action strip has the pan button, which enables drags. When the app cannot be shown, the
 * reason goes in the template's message card.
 *
 * <p>The surface callbacks arrive on the main thread. They are forwarded to {@link Mirror}; the
 * gestures are logged ({@code adb logcat -s AnyAuto.car}) to check what the host sends.
 */
final class MirrorScreen extends Screen implements SurfaceCallback, Mirror.Nav {
    private static final String TAG = "AnyAuto.car";
    private final Mirror mirror;

    MirrorScreen(CarContext c, Mirror mirror) {
        super(c);
        this.mirror = mirror;
        c.getCarService(AppManager.class).setSurfaceCallback(this);
        mirror.setNav(this);
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onDestroy(@NonNull LifecycleOwner owner) {
                c.getCarService(AppManager.class).setSurfaceCallback(null);
                mirror.setNav(null);
            }
        });
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        ActionStrip strip = new ActionStrip.Builder()
                .addAction(new Action.Builder().setIcon(icon(R.drawable.ic_nav_bar))
                        .setOnClickListener(mirror::toggleNav)
                        .build())
                .build();
        NavigationTemplate.Builder b = new NavigationTemplate.Builder()
                .setActionStrip(strip)
                .setMapActionStrip(new ActionStrip.Builder().addAction(Action.PAN).build())
                .setPanModeListener(on -> Log.d(TAG, "pan mode " + on));
        // problems only: a title and one sentence with the fix
        Mirror.Problem p = mirror.problem();
        if (p != null) {
            b.setNavigationInfo(new MessageInfo.Builder(p.title).setText(p.text)
                    .setImage(icon(R.drawable.ic_phone_alert)).build());
        }
        return b.build();
    }

    // ------------------------------------------------------------------ Mirror.Nav (main thread)

    @Override
    public void apps() {
        getScreenManager().push(new AppsScreen(getCarContext(), mirror));
    }

    @Override
    public void message(String text, boolean longer) {
        CarToast.makeText(getCarContext(), text, longer ? CarToast.LENGTH_LONG : CarToast.LENGTH_SHORT).show();
    }

    private CarIcon icon(int res) {
        return new CarIcon.Builder(IconCompat.createWithResource(getCarContext(), res)).build();
    }

    // ------------------------------------------------------------------ SurfaceCallback (main thread)

    @Override
    public void onSurfaceAvailable(@NonNull SurfaceContainer s) {
        Log.i(TAG, "surface " + s.getWidth() + "x" + s.getHeight() + "@" + s.getDpi());
        if (s.getSurface() == null) return;
        Prefs.setCarSize(getCarContext(), s.getWidth(), s.getHeight());
        mirror.surface(s.getSurface(), s.getWidth(), s.getHeight(), s.getDpi());
    }

    @Override
    public void onVisibleAreaChanged(@NonNull Rect r) {
        Log.d(TAG, "visible area " + r.toShortString());
        mirror.visibleArea(r);
    }

    @Override
    public void onStableAreaChanged(@NonNull Rect r) {
        Log.d(TAG, "stable area " + r.toShortString());
        mirror.stableArea(r);
    }

    @Override
    public void onSurfaceDestroyed(@NonNull SurfaceContainer s) {
        Log.i(TAG, "surface destroyed");
        mirror.surfaceGone();
    }

    @Override
    public void onClick(float x, float y) {
        Log.d(TAG, "click " + x + "," + y);
        mirror.click(x, y);
    }

    @Override
    public void onScroll(float dx, float dy) {
        Log.d(TAG, "scroll " + dx + "," + dy);
        mirror.scroll(dx, dy);
    }

    @Override
    public void onFling(float vx, float vy) {
        Log.d(TAG, "fling " + vx + "," + vy);
        mirror.fling(vx, vy);
    }

    @Override
    public void onScale(float fx, float fy, float factor) {
        Log.d(TAG, "scale " + fx + "," + fy + " x" + factor);
        mirror.scale(fx, fy, factor);
    }
}
