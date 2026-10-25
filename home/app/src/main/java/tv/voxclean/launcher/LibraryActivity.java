package tv.voxclean.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Every TV app, three columns of rows: OK puts an app on Home or hides it. The choice is saved at once (HiddenApps
 * in Store.LIBRARY); Home re-reads it when it comes back. Focus is Vox's own state, as on Home.
 */
public class LibraryActivity extends Activity {
    private static final String TAG = "Vox";
    private static final int COLS = 3;
    private static final float MINI_FOCUS_SCALE = 1.15f;

    private static final class App {
        final String pkg, label;
        final ResolveInfo info;

        App(String pkg, String label, ResolveInfo info) {
            this.pkg = pkg;
            this.label = label;
            this.info = info;
        }
    }

    private float scale;
    private Typeface font;
    private int ink, muted;
    private final List<App> apps = new ArrayList<>();
    private final List<View> rows = new ArrayList<>();
    private final List<ImageView> minis = new ArrayList<>();
    private final List<TextView> names = new ArrayList<>(), states = new ArrayList<>();
    private Store store;
    private HiddenApps choices;
    private Set<String> base;
    private TextView count;
    private ScrollView scroll;
    private int focus;
    private boolean okDown;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        font = getResources().getFont(R.font.plex_mono_regular);
        ink = getColor(R.color.ink);
        muted = getColor(R.color.muted);
        base = VoxConfig.load(this).hiddenApps;
        store = new Store(this, Store.LIBRARY);
        choices = HiddenApps.parse(store.read());
        loadApps();
        setContentView(buildLayout());
        paint(false);
    }

    private int px(float designPx) {
        return Math.round(designPx * scale);
    }

    private TextView text(float sizePx, float spacingEm, int color) {
        TextView t = new TextView(this);
        t.setTypeface(font);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(sizePx));
        t.setLetterSpacing(spacingEm);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** One row per package with an exported leanback activity, labelled by its first one, sorted by label. */
    private void loadApps() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.packageName.equals(getPackageName()) || !ai.exported || !seen.add(ai.packageName)) continue;
            apps.add(new App(ai.packageName, String.valueOf(ri.loadLabel(pm)).toUpperCase(Locale.ROOT), ri));
        }
        apps.sort((a, b) -> a.label.compareTo(b.label));
    }

    private View buildLayout() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(getColor(R.color.page));
        page.setPadding(px(185), px(64), px(185), 0);

        TextView title = text(22, 0.08f, ink);
        title.setText(R.string.library_title);
        page.addView(title);
        count = text(16, 0.1f, muted);
        LinearLayout.LayoutParams c = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        c.topMargin = px(12);
        c.bottomMargin = px(40);
        page.addView(count, c);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % COLS == 0) {
                line = new LinearLayout(this);
                grid.addView(line);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i % COLS < COLS - 1) lp.setMarginEnd(px(48));
            line.addView(row(apps.get(i)), lp);
        }
        // A short last line keeps its rows column-wide.
        int spare = (COLS - apps.size() % COLS) % COLS;
        for (int i = 0; i < spare && line != null; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 0, 1f);
            if (i < spare - 1) lp.setMarginEnd(px(48));
            line.addView(new View(this), lp);
        }
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(grid);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private View row(App app) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(8), px(20), px(8), px(20));
        row.setClipChildren(false);
        row.setClipToPadding(false);
        // The 1 px bottom rule: a page-coloured layer inset over a rule-coloured one.
        LayerDrawable bg = new LayerDrawable(new Drawable[]{
                new ColorDrawable(getColor(R.color.rule)), new ColorDrawable(getColor(R.color.page))});
        bg.setLayerInset(1, 0, 0, 0, Math.max(1, px(1)));
        row.setBackground(bg);

        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(getColor(R.color.tile));
        ImageView mini = new ImageView(this);
        int voxel = Voxel.forPackage(app.pkg);
        if (voxel != 0) {
            mini.setScaleType(ImageView.ScaleType.FIT_XY);
            mini.setImageResource(voxel);
        } else {
            int inset = px(6);
            mini.setPadding(inset, inset, inset, inset);
            mini.setScaleType(ImageView.ScaleType.FIT_CENTER);
            mini.setImageDrawable(appIcon(app));
        }
        box.addView(mini, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(box, new LinearLayout.LayoutParams(px(44), px(44)));

        TextView name = text(20, 0, muted);
        name.setText(app.label);
        LinearLayout.LayoutParams n = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        n.setMarginStart(px(22));
        n.setMarginEnd(px(16));
        row.addView(name, n);

        TextView state = text(15, 0.12f, muted);
        row.addView(state);

        rows.add(row);
        minis.add(mini);
        names.add(name);
        states.add(state);
        return row;
    }

    private Drawable appIcon(App app) {
        PackageManager pm = getPackageManager();
        try {
            return app.info.loadIcon(pm);
        } catch (RuntimeException e) {
            Log.w(TAG, "no icon for " + app.pkg + " in the Library; trying its banner", e);
            Drawable banner = app.info.activityInfo.loadBanner(pm);
            return banner != null ? banner : pm.getDefaultActivityIcon();
        }
    }

    private Set<String> hidden() {
        return choices.apply(base);
    }

    /** States, the count line, and the focus rule: the focused row's text turns ink and its icon grows. */
    private void paint(boolean animate) {
        Set<String> hidden = hidden();
        int onHome = 0;
        for (int i = 0; i < apps.size(); i++) {
            boolean shown = !hidden.contains(apps.get(i).pkg);
            if (shown) onHome++;
            states.get(i).setText(shown ? R.string.on_home : R.string.hidden);
            boolean on = i == focus;
            Motion.textColor(states.get(i), shown || on ? ink : muted, animate);
            Motion.textColor(names.get(i), on ? ink : muted, animate);
            Motion.scale((View) minis.get(i).getParent(), on ? MINI_FOCUS_SCALE : 1f, animate);
        }
        count.setText(getString(R.string.library_count, apps.size(), onHome));
    }

    /** Keeps the focused row in view with a row's margin above and below. */
    private void reveal() {
        View row = rows.get(focus);
        View line = (View) row.getParent();
        int margin = row.getHeight();
        int top = line.getTop() - margin, bottom = line.getBottom() + margin;
        int y = scroll.getScrollY(), h = scroll.getHeight();
        if (top < y) scroll.smoothScrollTo(0, Math.max(0, top));
        else if (bottom > y + h) scroll.smoothScrollTo(0, bottom - h);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int next = focus;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (focus % COLS > 0) next = focus - 1;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (focus % COLS < COLS - 1 && focus + 1 < apps.size()) next = focus + 1;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (focus >= COLS) next = focus - COLS;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (focus + COLS < apps.size()) next = focus + COLS;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (event.getRepeatCount() == 0) okDown = true;
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
        if (next != focus) {
            focus = next;
            paint(true);
            reveal();
        }
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                // Only a press that began here: the OK that opened the Library must not toggle a row.
                if (okDown && !apps.isEmpty()) toggle();
                okDown = false;
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    private void toggle() {
        String pkg = apps.get(focus).pkg;
        boolean hide = !hidden().contains(pkg);
        choices.set(pkg, hide);
        if (!store.write(choices.lines())) {
            Log.e(TAG, "Library: " + pkg + (hide ? " hidden" : " shown") + " in memory only; the save was refused");
            Toast.makeText(this, R.string.library_cant_save, Toast.LENGTH_LONG).show();
        }
        paint(true);
    }
}
