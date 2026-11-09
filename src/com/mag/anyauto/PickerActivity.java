package com.mag.anyauto;

import android.app.ActionBar;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Choose apps (search at the top): every app with a
 * launcher icon, ticks save at once. "In the car" holds the apps that were chosen when the screen
 * opened, so rows don't jump while you tick; "Other apps" the rest. Done (bottom) just closes.
 */
public final class PickerActivity extends Activity {
    private static final int HEADER = 0, APP = 1;

    private final Adapter adapter = new Adapter();
    private List<Apps.App> all = new ArrayList<>();
    private Set<String> chosenAtOpen = new HashSet<>();
    private String filter = "";
    private TextView noMatch;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Choose apps");
        ActionBar ab = getActionBar();
        if (ab != null) ab.setDisplayHomeAsUpEnabled(true);
        chosenAtOpen = Prefs.carApps(this);
        updateSubtitle();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(searchField());

        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            Object o = adapter.getItem(pos);
            if (!(o instanceof Apps.App)) return;
            Apps.App a = (Apps.App) o;
            Prefs.setCarApp(this, a.component, !Prefs.isCarApp(this, a.component));
            updateSubtitle();
            adapter.notifyDataSetChanged();
        });
        noMatch = Ui.text(this, "", 14, false, Ui.text2(this));
        noMatch.setGravity(Gravity.CENTER);
        noMatch.setPadding(dp(32), dp(48), dp(32), 0);
        noMatch.setVisibility(View.GONE);
        root.addView(noMatch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        Button done = Ui.filled(this, "Done");
        done.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams donep = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        donep.setMargins(dp(16), dp(8), dp(16), dp(12));
        root.addView(done, donep);
        // Done rides above the navigation bar, and above the keyboard while it is open
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(WindowInsets.Type.systemBars()).top;
                bottom = Math.max(insets.getInsets(WindowInsets.Type.systemBars()).bottom,
                        insets.getInsets(WindowInsets.Type.ime()).bottom);
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
        setContentView(root);

        new Thread(() -> {
            List<Apps.App> apps = Apps.launchable(this);
            runOnUiThread(() -> {
                all = apps;
                adapter.rebuild();
            });
        }, "anyauto-apps").start();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateSubtitle() {
        int n = Prefs.carApps(this).size();
        ActionBar ab = getActionBar();
        if (ab != null) ab.setSubtitle(n == 0 ? "Nothing chosen yet" : n + " chosen");
    }

    /** A pill: search icon, the field, × while there is text. */
    private View searchField() {
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(Ui.round(Ui.card(this), dp(24)));
        pill.setPadding(dp(16), 0, dp(4), 0);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(Ui.icon(this, R.drawable.ic_search, Ui.text2(this)));
        pill.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        EditText field = new EditText(this);
        field.setHint("Search apps");
        field.setSingleLine(true);
        field.setBackground(null);
        field.setTextSize(16);
        field.setPadding(dp(12), 0, dp(4), 0);
        pill.addView(field, new LinearLayout.LayoutParams(0, dp(48), 1));
        ImageButton clear = new ImageButton(this);
        clear.setImageDrawable(Ui.icon(this, R.drawable.ic_close, Ui.text2(this)));
        clear.setBackground(null);
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(v -> field.setText(""));
        pill.addView(clear, new LinearLayout.LayoutParams(dp(40), dp(40)));
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int c, int d) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filter = s.toString().trim().toLowerCase(Locale.ROOT);
                clear.setVisibility(filter.isEmpty() ? View.GONE : View.VISIBLE);
                adapter.rebuild();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.setMargins(dp(16), dp(12), dp(16), dp(4));
        pill.setLayoutParams(lp);
        return pill;
    }

    private final class Adapter extends BaseAdapter {
        // String = section header or hint, Apps.App = row
        private final List<Object> items = new ArrayList<>();

        void rebuild() {
            items.clear();
            List<Apps.App> inCar = new ArrayList<>(), other = new ArrayList<>();
            for (Apps.App a : all) {
                if (!filter.isEmpty() && !a.label.toLowerCase(Locale.ROOT).contains(filter)
                        && !a.component.getPackageName().contains(filter)) {
                    continue;
                }
                (chosenAtOpen.contains(a.component.flattenToString()) ? inCar : other).add(a);
            }
            if (chosenAtOpen.isEmpty() && filter.isEmpty()) items.add("hint:Tick the apps to show in the car.");
            if (!inCar.isEmpty()) {
                items.add("In the car");
                items.addAll(inCar);
            }
            if (!other.isEmpty()) {
                items.add("Other apps");
                items.addAll(other);
            }
            boolean none = inCar.isEmpty() && other.isEmpty() && !all.isEmpty();
            noMatch.setText("No apps match “" + filter + "”");
            noMatch.setVisibility(none ? View.VISIBLE : View.GONE);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int i) {
            return items.get(i) instanceof Apps.App ? APP : HEADER;
        }

        @Override
        public boolean isEnabled(int i) {
            return items.get(i) instanceof Apps.App;
        }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            Object o = items.get(i);
            if (o instanceof String) {
                String s = (String) o;
                boolean hint = s.startsWith("hint:");
                TextView t = v instanceof TextView ? (TextView) v
                        : Ui.text(PickerActivity.this, "", 14, !hint, Ui.accent(PickerActivity.this));
                t.setText(hint ? s.substring(5) : s);
                t.setTextColor(hint ? Ui.text2(PickerActivity.this) : Ui.accent(PickerActivity.this));
                t.setPadding(dp(16), dp(hint ? 12 : 16), dp(16), dp(4));
                return t;
            }
            Row r;
            if (v == null) {
                r = new Row();
                LinearLayout row = new LinearLayout(PickerActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(8), dp(12), dp(8));
                r.icon = new ImageView(PickerActivity.this);
                row.addView(r.icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
                r.label = Ui.text(PickerActivity.this, "", 16, false, Ui.text(PickerActivity.this));
                r.label.setPadding(dp(16), 0, dp(8), 0);
                row.addView(r.label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                r.check = new CheckBox(PickerActivity.this);
                r.check.setClickable(false); // the row toggles it
                r.check.setFocusable(false);
                r.check.setButtonTintList(ColorStateList.valueOf(Ui.accent(PickerActivity.this)));
                row.addView(r.check);
                row.setTag(r);
                v = row;
            } else {
                r = (Row) v.getTag();
            }
            Apps.App a = (Apps.App) o;
            r.icon.setImageDrawable(a.icon(getPackageManager()));
            r.label.setText(a.label);
            r.check.setChecked(Prefs.isCarApp(PickerActivity.this, a.component));
            return v;
        }
    }

    private static final class Row {
        ImageView icon;
        TextView label;
        CheckBox check;
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
