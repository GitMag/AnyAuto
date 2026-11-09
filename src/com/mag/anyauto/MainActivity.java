package com.mag.anyauto;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Home: status cards only when something needs doing (the car
 * has the display, Shizuku isn't ready, not installed for the car), then the apps in the car with
 * Try, and one pinned button at the bottom, Choose apps. Help and the install guidance are in
 * the overflow menu.
 */
public final class MainActivity extends Activity implements Shell.Listener {
    static final String UNKNOWNAUTO = "com.mag.unknownauto";
    static final String UNKNOWNAUTO_RELEASES = "https://github.com/GitMag/UnknownAuto/releases/latest";
    private static final int MENU_HELP = 1, MENU_INSTALL = 2, MENU_KEYBOARD = 3, MENU_FULL = 4;

    private LinearLayout cards;
    private TextView section, sectionCount;
    private View empty;
    private final AppAdapter adapter = new AppAdapter();
    private List<Apps.App> chosen = new ArrayList<>();
    private final Runnable carChanged = this::refresh;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Shell.init(this);
        setTitle(R.string.app_name);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        header.addView(cards);

        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.HORIZONTAL);
        sec.setPadding(dp(16), dp(16), dp(16), dp(4));
        section = Ui.text(this, "In the car", 14, true, Ui.accent(this));
        sec.addView(section, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        sectionCount = Ui.text(this, "", 14, false, Ui.text2(this));
        sec.addView(sectionCount);
        header.addView(sec);

        empty = emptyState();
        header.addView(empty);

        ListView list = new ListView(this);
        list.addHeaderView(header, null, false);
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setClipToPadding(false);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            Apps.App a = (Apps.App) parent.getItemAtPosition(pos);
            if (a != null) tryApp(a);
        });

        Button choose = Ui.filled(this, "Choose apps");
        choose.setOnClickListener(v -> startActivity(new Intent(this, PickerActivity.class)));

        FrameLayout root = new FrameLayout(this);
        root.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48), Gravity.BOTTOM);
        root.addView(choose, bp);
        // the button sits 12 dp above the navigation bar; the list can scroll its last row clear of it
        // edge-to-edge (Android 15+): the top inset covers the status bar and the action bar
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars());
                top = i.top;
                bottom = i.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            bp.setMargins(dp(16), 0, dp(16), bottom + dp(12));
            choose.setLayoutParams(bp);
            list.setPadding(0, top, 0, bottom + dp(12 + 48 + 12));
            return insets;
        });
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Shell.addListener(this);
        Mirror.addCarListener(carChanged);
        Shell.connect();
        new Thread(() -> {
            List<Apps.App> apps = Apps.chosen(this);
            runOnUiThread(() -> {
                chosen = apps;
                refresh();
            });
        }, "anyauto-apps").start();
        refresh();
    }

    @Override
    protected void onPause() {
        Shell.removeListener(this);
        Mirror.removeCarListener(carChanged);
        super.onPause();
    }

    @Override
    public void onShellChanged() {
        refresh();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_FULL, 0, "Full screen in the car").setCheckable(true)
                .setChecked(Prefs.fullScreen(this));
        menu.add(0, MENU_KEYBOARD, 1, "Keyboard");
        menu.add(0, MENU_HELP, 2, "Help");
        menu.add(0, MENU_INSTALL, 3, "Install for the car");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_FULL) {
            boolean on = !item.isChecked();
            item.setChecked(on);
            Prefs.setFullScreen(this, on);
            Mirror.settingsChanged();
            return true;
        }
        if (item.getItemId() == MENU_KEYBOARD) {
            String[] choices = {"On the car screen", "On the phone (its screen then stays on while AnyAuto is on the car screen)"};
            new AlertDialog.Builder(this)
                    .setTitle("Where the keyboard opens")
                    .setSingleChoiceItems(choices, Prefs.keyboardOnCar(this) ? 0 : 1, (d, which) -> {
                        Prefs.setKeyboardOnCar(this, which == 0);
                        Mirror.settingsChanged();
                        d.dismiss();
                    })
                    .setNegativeButton("Close", null)
                    .show();
            return true;
        }
        if (item.getItemId() == MENU_HELP) {
            startActivity(new Intent(this, HelpActivity.class));
            return true;
        }
        if (item.getItemId() == MENU_INSTALL) {
            new AlertDialog.Builder(this)
                    .setTitle(installedForCar(this) ? "Installed for the car" : "Not installed for the car")
                    .setMessage(installedForCar(this)
                            ? "AnyAuto was installed in a way Android Auto accepts. After an update, install it with UnknownAuto again."
                            : INSTALL_BODY)
                    .setPositiveButton(unknownAutoLabel(this), (d, w) -> openUnknownAuto(this))
                    .setNegativeButton("Close", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private static final String INSTALL_BODY = "Android Auto only runs apps installed from Google Play. "
            + "Reinstall AnyAuto with UnknownAuto before using it in a car. The desktop head unit works either way.";

    // ------------------------------------------------------------------ status cards

    private void refresh() {
        cards.removeAllViews();
        boolean onCar = Mirror.carShowing();
        if (onCar) {
            cards.addView(card(R.drawable.ic_car, "On the car screen",
                    "The car is using the display. The phone stays awake with its screen off. Try is unavailable meanwhile."));
        }
        Shell.State st = Shell.state();
        switch (st) {
            case READY:
                cards.addView(slim(Ui.icon(this, R.drawable.ic_check, Ui.accent(this)), null, "Shizuku is running"));
                break;
            case CONNECTING:
                ProgressBar p = new ProgressBar(this);
                p.setIndeterminate(true);
                cards.addView(slim(null, p, "Connecting to Shizuku…"));
                break;
            case NO_SHIZUKU:
                cards.addView(card(R.drawable.ic_shell, "Shizuku isn't installed",
                        "AnyAuto needs Shizuku. It gives the app the rights adb has, without root.",
                        Ui.filled(this, "Get Shizuku"), v -> openStore()));
                break;
            case NOT_RUNNING:
                cards.addView(card(R.drawable.ic_shell, "Shizuku isn't running",
                        "Start it in the Shizuku app: Wireless debugging, then Start. It stops at every reboot.",
                        Ui.filled(this, "Open Shizuku"), v -> {
                            Intent i = getPackageManager().getLaunchIntentForPackage(Shell.SHIZUKU_PACKAGE);
                            if (i != null) startActivity(i);
                        }));
                break;
            case OLD_SHIZUKU:
                cards.addView(card(R.drawable.ic_shell, "Shizuku is too old",
                        "Update Shizuku to version 11 or newer.",
                        Ui.filled(this, "Update Shizuku"), v -> openStore()));
                break;
            case NO_PERMISSION:
                cards.addView(card(R.drawable.ic_shell, "Allow AnyAuto in Shizuku",
                        "Shizuku asks once. Choose Allow all the time.",
                        Ui.filled(this, "Allow"), v -> Shell.requestPermission()));
                break;
        }
        long version = versionCode();
        if (!installedForCar(this) && !Prefs.installCardHidden(this, version)) {
            View c = card(R.drawable.ic_car, "Not installed for the car", INSTALL_BODY,
                    Ui.filled(this, unknownAutoLabel(this)), v -> openUnknownAuto(this));
            Button hide = Ui.textButton(this, "Hide");
            hide.setOnClickListener(v -> {
                Prefs.hideInstallCard(this, version);
                refresh();
            });
            LinearLayout buttons = c.findViewWithTag("buttons");
            buttons.addView(hide, 0);
            cards.addView(c);
        }

        boolean none = chosen.isEmpty();
        section.setVisibility(none ? View.GONE : View.VISIBLE);
        sectionCount.setVisibility(none ? View.GONE : View.VISIBLE);
        sectionCount.setText(chosen.size() == 1 ? "1 app" : chosen.size() + " apps");
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private View card(int icon, String title, String body) {
        return card(icon, title, body, null, null);
    }

    /** A card: icon + title, body, buttons right-aligned (the fixing one filled). */
    private View card(int icon, String title, String body, Button button, View.OnClickListener action) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(button != null ? 8 : 16));
        c.setBackground(Ui.round(Ui.card(this), dp(16)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(12), dp(16), 0);
        c.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(Ui.icon(this, icon, Ui.text(this)));
        top.addView(iv, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView t = Ui.text(this, title, 16, true, Ui.text(this));
        t.setPadding(dp(12), 0, 0, 0);
        top.addView(t);
        c.addView(top);
        TextView bt = Ui.text(this, body, 14, false, Ui.text2(this));
        bt.setPadding(0, dp(8), 0, 0);
        c.addView(bt);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setTag("buttons");
        buttons.setGravity(Gravity.END);
        if (button != null) {
            button.setOnClickListener(action);
            buttons.addView(button);
            buttons.setPadding(0, dp(8), 0, 0);
            c.addView(buttons);
        }
        return c;
    }

    /** A slim status row without a card: ready, connecting. */
    private View slim(android.graphics.drawable.Drawable icon, View leading, String text) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(8), dp(16), 0);
        r.setMinimumHeight(dp(40));
        if (leading != null) {
            r.addView(leading, new LinearLayout.LayoutParams(dp(20), dp(20)));
        } else {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            r.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));
        }
        TextView t = Ui.text(this, text, 14, false, Ui.text2(this));
        t.setPadding(dp(12), 0, 0, 0);
        r.addView(t);
        return r;
    }

    private View emptyState() {
        LinearLayout e = new LinearLayout(this);
        e.setOrientation(LinearLayout.VERTICAL);
        e.setGravity(Gravity.CENTER_HORIZONTAL);
        e.setPadding(dp(32), dp(72), dp(32), dp(24));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(Ui.icon(this, R.drawable.ic_car, Ui.text2(this)));
        e.addView(iv, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView t = Ui.text(this, "No apps in the car yet", 18, true, Ui.text(this));
        t.setPadding(0, dp(16), 0, dp(8));
        t.setGravity(Gravity.CENTER);
        e.addView(t);
        TextView b = Ui.text(this, "Choose the phone apps to show on the car screen.", 14, false, Ui.text2(this));
        b.setGravity(Gravity.CENTER);
        e.addView(b);
        return e;
    }

    // ------------------------------------------------------------------ actions

    private void tryApp(Apps.App a) {
        if (Mirror.carShowing()) return;
        startActivity(new Intent(this, PreviewActivity.class).putExtra(PreviewActivity.EXTRA_APP, a.component));
    }

    private void openStore() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + Shell.SHIZUKU_PACKAGE)));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")));
        }
    }

    static String unknownAutoLabel(Context c) {
        return c.getPackageManager().getLaunchIntentForPackage(UNKNOWNAUTO) != null ? "Open UnknownAuto" : "Get UnknownAuto";
    }

    static void openUnknownAuto(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(UNKNOWNAUTO);
        if (i == null) i = new Intent(Intent.ACTION_VIEW, Uri.parse(UNKNOWNAUTO_RELEASES));
        c.startActivity(i);
    }

    /** Installed as if from Google Play (UnknownAuto): the only install Android Auto runs in a car. */
    static boolean installedForCar(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            String installer = Build.VERSION.SDK_INT >= 30
                    ? pm.getInstallSourceInfo(c.getPackageName()).getInstallingPackageName()
                    : pm.getInstallerPackageName(c.getPackageName());
            return "com.android.vending".equals(installer);
        } catch (Exception e) {
            return false;
        }
    }

    private long versionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ chosen apps

    private final class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return chosen.size();
        }

        @Override
        public Apps.App getItem(int i) {
            return chosen.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public boolean isEnabled(int i) {
            return !Mirror.carShowing();
        }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            Row r;
            if (v == null) {
                r = new Row();
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(8), dp(8), dp(8));
                r.icon = new ImageView(MainActivity.this);
                row.addView(r.icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
                r.label = Ui.text(MainActivity.this, "", 16, false, Ui.text(MainActivity.this));
                r.label.setPadding(dp(16), 0, dp(8), 0);
                row.addView(r.label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                // the whole row is the target; the button only names it
                r.tryIt = Ui.textButton(MainActivity.this, "Try");
                r.tryIt.setClickable(false);
                r.tryIt.setFocusable(false);
                row.addView(r.tryIt);
                row.setTag(r);
                v = row;
            } else {
                r = (Row) v.getTag();
            }
            Apps.App a = chosen.get(i);
            r.icon.setImageDrawable(a.icon(getPackageManager()));
            r.label.setText(a.label);
            r.tryIt.setAlpha(Mirror.carShowing() ? 0.38f : 1f);
            return v;
        }
    }

    private static final class Row {
        ImageView icon;
        TextView label;
        Button tryIt;
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
