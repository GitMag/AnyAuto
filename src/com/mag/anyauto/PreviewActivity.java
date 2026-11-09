package com.mag.anyauto;

import android.app.Activity;
import android.content.ComponentName;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The car screen simulated on the phone, in landscape: the
 * same virtual display, launch and input path as the car ({@link Mirror}). The picture takes the
 * last car's shape (letterboxed), and the controls sit in a rail at the right edge so nothing
 * covers it: Close at the top, the Direct and Card toggles in the middle, Back at the bottom
 * right. Touches go through the car's gesture model by default (tap / scroll distances / fling /
 * pinch, synthesised back into touches by {@link Touch}); Direct passes real touches through.
 * Card simulates the dashboard's map card. Density: the picture counts as 480 dp tall.
 */
public final class PreviewActivity extends Activity implements SurfaceHolder.Callback, Mirror.Listener {
    static final String EXTRA_APP = "app";
    private static final int RAIL_BG = 0xFF1A1B1E;

    private Mirror mirror;
    private SurfaceView view;
    private TextView title, line;
    private View direct, card;
    private boolean directOn, cardOn;
    private int viewW, viewH;
    private GestureDetector gestures;
    private ScaleGestureDetector scales;
    // AnyAuto is on the car screen: the display is the car's, the preview only says so
    private boolean blocked;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Shell.init(this);
        mirror = new Mirror(this, this, false);

        // the picture: black area left of the rail, the surface centred in the car's shape
        FrameLayout area = new FrameLayout(this);
        area.setBackgroundColor(Color.BLACK);
        view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        area.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        int[] car = Prefs.carSize(this);
        if (car != null) {
            area.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
                int aw = r - l, ah = bt - t;
                if (aw <= 0 || ah <= 0) return;
                float aspect = car[0] / (float) car[1];
                int w = aw, h = Math.round(aw / aspect);
                if (h > ah) {
                    h = ah;
                    w = Math.round(ah * aspect);
                }
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
                if (lp.width != w || lp.height != h) {
                    lp.width = w;
                    lp.height = h;
                    view.post(() -> view.setLayoutParams(lp));
                }
            });
        }

        LinearLayout msg = new LinearLayout(this);
        msg.setOrientation(LinearLayout.VERTICAL);
        msg.setGravity(Gravity.CENTER);
        title = Ui.text(this, "", 20, true, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        line = Ui.text(this, "", 16, false, 0x99FFFFFF);
        line.setGravity(Gravity.CENTER);
        line.setPadding(0, dp(6), 0, 0);
        msg.addView(title);
        msg.addView(line);
        msg.setPadding(dp(24), 0, dp(24), 0);
        area.addView(msg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        showMessage(null, null);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.BLACK);
        root.addView(area, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        root.addView(rail(), new LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        hideSystemBars();

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                mirror.click(e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (!scales.isInProgress()) mirror.scroll(dx, dy);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (!scales.isInProgress()) mirror.fling(vx, vy);
                return true;
            }
        });
        scales = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                mirror.scale(d.getFocusX(), d.getFocusY(), d.getScaleFactor());
                return true;
            }
        });
        view.setOnTouchListener((v, e) -> {
            if (blocked) return true;
            if (directOn) {
                mirror.raw(e);
                return true;
            }
            scales.onTouchEvent(e);
            gestures.onTouchEvent(e);
            return true;
        });

        blocked = Mirror.carShowing();
        if (blocked) {
            showMessage("AnyAuto is on the car screen", "Try works when it isn't.");
            return;
        }
        ComponentName app = getIntent().getParcelableExtra(EXTRA_APP);
        if (app != null) mirror.open(app);
    }

    /** Close (rare) at the top, the two toggles in the middle, Back (all the time) at the bottom. */
    private View rail() {
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        // a 1 dp hairline on the left edge
        LayerDrawable bg = new LayerDrawable(new Drawable[]{new ColorDrawable(0x33FFFFFF), new ColorDrawable(RAIL_BG)});
        bg.setLayerInset(1, dp(1), 0, 0, 0);
        rail.setBackground(bg);
        rail.setPadding(0, dp(12), 0, dp(12));

        rail.addView(railButton(R.drawable.ic_close, "Close", v -> finish()));
        rail.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        direct = railButton(R.drawable.ic_touch, "Direct", v -> {
            directOn = !directOn;
            styleToggle(direct, directOn);
        });
        rail.addView(direct);
        card = railButton(R.drawable.ic_card, "Card", v -> {
            cardOn = !cardOn;
            styleToggle(card, cardOn);
            mirror.forceContent(cardOn ? cardRect(viewW, viewH) : null);
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(72), dp(64));
        cp.topMargin = dp(8);
        rail.addView(card, cp);
        rail.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));

        // Back: filled accent, under the right thumb
        LinearLayout back = (LinearLayout) railButton(R.drawable.ic_back, "Back", v -> mirror.back());
        back.setBackground(Ui.round(Ui.accent(this), dp(32)));
        ((ImageView) back.getChildAt(0)).setImageDrawable(Ui.icon(this, R.drawable.ic_back, Ui.onAccent(this)));
        ((TextView) back.getChildAt(1)).setTextColor(Ui.onAccent(this));
        rail.addView(back, new LinearLayout.LayoutParams(dp(64), dp(64)));
        return rail;
    }

    private View railButton(int icon, String label, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(Ui.icon(this, icon, Color.WHITE));
        b.addView(iv, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView t = Ui.text(this, label, 12, false, 0xCCFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(4), 0, 0);
        b.addView(t);
        b.setClickable(true);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(72), dp(64)));
        return b;
    }

    private void styleToggle(View b, boolean on) {
        b.setBackground(on ? Ui.round(Ui.accentContainer(this), dp(16)) : null);
        int fg = on ? Ui.onAccentContainer(this) : Color.WHITE;
        LinearLayout l = (LinearLayout) b;
        ((ImageView) l.getChildAt(0)).getDrawable().setTint(fg);
        ((TextView) l.getChildAt(1)).setTextColor(on ? fg : 0xCCFFFFFF);
    }

    @Override
    protected void onDestroy() {
        if (blocked) mirror.releaseQuietly();
        else mirror.release();
        super.onDestroy();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        if (blocked) return;
        viewW = w;
        viewH = h;
        mirror.surface(holder.getSurface(), w, h, Math.max(160, h * 160 / 480));
        if (cardOn) mirror.forceContent(cardRect(w, h));
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (blocked) return;
        mirror.surfaceGone();
    }

    @Override
    public void onMirrorChanged() {
        // problems are drawn on the picture by the compositor, like on the car
    }

    private void showMessage(String t, String l) {
        title.setText(t);
        title.setVisibility(t == null ? View.GONE : View.VISIBLE);
        line.setText(l);
        line.setVisibility(l == null ? View.GONE : View.VISIBLE);
    }

    /** Like the DHU's map card: x 792–1178, y 96–688 of 1190×700. */
    private static Rect cardRect(int w, int h) {
        return new Rect(w * 792 / 1190, h * 96 / 700, w * 1178 / 1190, h * 688 / 700);
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
