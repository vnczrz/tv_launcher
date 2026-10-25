package tv.voxclean.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Vox's Home: a header (greeting, Wi-Fi, gear, clock) over a paged 4x2 grid of square tiles. Laid out in
 * 1920x1080 design pixels scaled to the screen. Focus is Vox's own state (Grid), not Android's view focus:
 * the D-pad rules are the spec's, and the focused thing grows and darkens.
 */
public class MainActivity extends Activity {
    private static final String TAG = "Vox";
    private static final long PAGE_FADE_MS = 320;
    private static final int MAX_CONFIG_BYTES = 64 * 1024;
    private static final float CHIP_ICON_REST_ALPHA = 0.55f, CHIP_ICON_FOCUS_SCALE = 1.25f;
    private static final String STATE_PAGE = "page", STATE_INDEX = "index", STATE_HEADER = "header",
            STATE_CHIP = "chip", STATE_KEY = "key";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private float scale;
    private Typeface font;
    private int ink, muted, tileColor, dotColor;
    private VoxConfig config;

    private List<Tiles.Tile> tiles = Collections.emptyList();
    /** The leanback activity behind each tile key, and the first one per package (the Live tile borrows YouTube TV's). */
    private final Map<String, ResolveInfo> activities = new HashMap<>();
    private final Map<String, ResolveInfo> firstByPackage = new HashMap<>();
    /** Tile artwork, loaded when a page is first shown; dropped when memory is short and Home is hidden. */
    private final Map<String, Drawable> art = new HashMap<>();

    private Grid focus = Grid.first();
    /** The focused tile's key, so focus stays on the same app when tiles are added or removed. */
    private String focusedKey;
    /** stopped: Home left the screen since it was last resumed (an app came in front). */
    private boolean started, stopped, stale = true, turning, okDown;
    private int packageSequence;
    private String greetingSlot;

    private TextView greeting, wifiText, clock, pageLabel;
    private View wifiChip, gearChip;
    private ImageView wifiIcon, gearIcon;
    private FrameLayout gridLayer;
    private final FrameLayout[] cells = new FrameLayout[Grid.PER_PAGE];
    private final ImageView[] cellArt = new ImageView[Grid.PER_PAGE];
    private final TextView[] cellName = new TextView[Grid.PER_PAGE];
    private LinearLayout dots;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // An update sends REMOVED then ADDED: keep the tile until the new APK is in.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            // On screen: rebuild now. Hidden: mark stale, so a run of app updates costs one rebuild in onResume.
            if (started) loadTiles();
            else stale = true;
        }
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClock();
        }
    };

    /** At boot Wi-Fi often connects after Home is shown, so the chip follows the network callbacks. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            handler.post(MainActivity.this::updateNetwork);
        }

        @Override
        public void onLost(Network network) {
            handler.post(MainActivity.this::updateNetwork);
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            handler.post(MainActivity.this::updateNetwork);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        scale = dm.widthPixels / 1920f;
        font = getResources().getFont(R.font.plex_mono_regular);
        ink = getColor(R.color.ink);
        muted = getColor(R.color.muted);
        tileColor = getColor(R.color.tile);
        dotColor = getColor(R.color.dot);
        config = readConfig();
        if (state != null) {
            focus = new Grid(state.getInt(STATE_PAGE), state.getInt(STATE_INDEX), state.getBoolean(STATE_HEADER),
                    state.getInt(STATE_CHIP, Grid.CHIP_SETTINGS));
            focusedKey = state.getString(STATE_KEY);
        }
        setContentView(buildLayout());

        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        // For the activity's whole life: an install while an app is in front must still mark the tiles stale.
        register(packageReceiver, packages);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);
        loadTiles();
    }

    private void register(BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    /** config.json from the app's external files directory, where home/home-config.sh pushes it. */
    private VoxConfig readConfig() {
        File dir = getExternalFilesDir(null);
        File file = dir != null ? new File(dir, VoxConfig.FILE_NAME) : null;
        if (file == null || !file.exists()) return VoxConfig.parse(null);
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[MAX_CONFIG_BYTES + 1];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            if (n > MAX_CONFIG_BYTES) {
                Log.w(TAG, "config.json is over " + MAX_CONFIG_BYTES + " bytes; ignored");
                return VoxConfig.parse(null);
            }
            VoxConfig c = VoxConfig.parse(new String(buf, 0, n, StandardCharsets.UTF_8));
            Log.i(TAG, c.fileIgnored ? "config.json does not parse; ignored as a whole"
                    : "config.json read: focus_scale " + c.focusScale + ", live_uri " + c.liveUri);
            return c;
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "config.json at " + file + " could not be read; using defaults", e);
            return VoxConfig.parse(null);
        }
    }

    // ---- layout ---------------------------------------------------------------------------------

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

    private static FrameLayout.LayoutParams at(int w, int h, int gravity) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = gravity;
        return lp;
    }

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.page));

        // Header: top 64, height 56, between the grid's edges (185 px in from each side).
        greeting = text(22, 0.08f, ink);
        greeting.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams g = at(ViewGroup.LayoutParams.WRAP_CONTENT, px(56), Gravity.TOP | Gravity.START);
        g.leftMargin = px(185);
        g.topMargin = px(64);
        root.addView(greeting, g);

        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        wifiIcon = new ImageView(this);
        wifiText = text(22, 0, muted);
        wifiChip = chip(wifiIcon, R.drawable.ic_wifi, wifiText);
        gearIcon = new ImageView(this);
        gearChip = chip(gearIcon, R.drawable.ic_settings, null);
        clock = text(22, 0, ink);
        clock.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.setMarginEnd(px(28));
        status.addView(wifiChip, gap);
        status.addView(gearChip, new LinearLayout.LayoutParams(gap));
        status.addView(clock);
        FrameLayout.LayoutParams s = at(ViewGroup.LayoutParams.WRAP_CONTENT, px(56), Gravity.TOP | Gravity.END);
        s.rightMargin = px(185);
        s.topMargin = px(64);
        root.addView(status, s);

        // Grid: 4x2 squares of 380 px with 10 px gaps, top-left at (185, 178).
        gridLayer = new FrameLayout(this);
        for (int i = 0; i < Grid.PER_PAGE; i++) {
            FrameLayout cell = new FrameLayout(this);
            cell.setBackgroundColor(tileColor);
            ImageView image = new ImageView(this);
            cell.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            TextView name = text(16, 0.12f, muted);
            name.setMaxWidth(px(332));
            FrameLayout.LayoutParams n = at(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            n.topMargin = px(20);
            n.rightMargin = px(24);
            cell.addView(name, n);
            FrameLayout.LayoutParams c = at(px(380), px(380), Gravity.TOP | Gravity.START);
            c.leftMargin = px((i % Grid.COLS) * 390);
            c.topMargin = px((i / Grid.COLS) * 390);
            gridLayer.addView(cell, c);
            cells[i] = cell;
            cellArt[i] = image;
            cellName[i] = name;
        }
        FrameLayout.LayoutParams gl = at(px(4 * 380 + 3 * 10), px(2 * 380 + 10), Gravity.TOP | Gravity.START);
        gl.leftMargin = px(185);
        gl.topMargin = px(178);
        root.addView(gridLayer, gl);

        // Footer: page dots bottom left, PAGE n / m bottom right.
        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(dots, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pageLabel = text(16, 0.12f, muted);
        footer.addView(pageLabel);
        FrameLayout.LayoutParams f = at(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        f.leftMargin = px(185);
        f.rightMargin = px(185);
        f.bottomMargin = px(56);
        root.addView(footer, f);
        return root;
    }

    /** A header item: a 28 px Carbon icon, optionally followed by a label. */
    private View chip(ImageView icon, int drawable, TextView label) {
        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(px(16), px(10), px(16), px(10));
        // The focused icon grows to 1.25x past its own bounds; drawn into the padding, not cut at it.
        chip.setClipToPadding(false);
        chip.setClipChildren(false);
        icon.setImageResource(drawable);
        icon.setAlpha(CHIP_ICON_REST_ALPHA);
        chip.addView(icon, new LinearLayout.LayoutParams(px(28), px(28)));
        if (label != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(px(12));
            label.setMaxWidth(px(420));
            chip.addView(label, lp);
        }
        return chip;
    }

    // ---- tiles ----------------------------------------------------------------------------------

    /** Asks PackageManager for the TV apps again and redraws the page, keeping focus on the same app. */
    private void loadTiles() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        List<Tiles.Tile> rows = new ArrayList<>();
        activities.clear();
        firstByPackage.clear();
        for (ResolveInfo ri : found) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.packageName.equals(getPackageName()) || !ai.exported) continue;
            Tiles.Tile t = new Tiles.Tile(ai.packageName, ai.name, String.valueOf(ri.loadLabel(pm)));
            rows.add(t);
            activities.put(t.key, ri);
            if (!firstByPackage.containsKey(ai.packageName)) firstByPackage.put(ai.packageName, ri);
        }
        tiles = Tiles.arrange(rows, config.hiddenApps);
        art.clear();
        packageSequence = changedSince(packageSequence).sequence;
        int pos = focusedKey != null ? Tiles.indexOf(tiles, focusedKey) : -1;
        focus = (pos >= 0 ? Grid.at(pos, focus) : focus).clamp(tiles.size());
        rememberFocusedKey();
        stale = false;
        Log.i(TAG, "tiles: " + tiles.size() + " (" + found.size() + " leanback activities)");
        render();
    }

    private static final class Changes {
        final int sequence;
        final boolean any;

        Changes(int sequence, boolean any) {
            this.sequence = sequence;
            this.any = any;
        }
    }

    /** PackageManager's record of app changes since the sequence number: catches a broadcast that never arrived. */
    private Changes changedSince(int sequence) {
        ChangedPackages changed = getPackageManager().getChangedPackages(sequence);
        if (changed == null) return new Changes(sequence, false);
        return new Changes(changed.getSequenceNumber(), !changed.getPackageNames().isEmpty());
    }

    private void rememberFocusedKey() {
        int pos = focus.position();
        if (!focus.header && pos < tiles.size()) focusedKey = tiles.get(pos).key;
    }

    /** Binds the current page's tiles, the dots and the page label, with focus drawn without motion. */
    private void render() {
        for (int i = 0; i < Grid.PER_PAGE; i++) {
            int pos = focus.page * Grid.PER_PAGE + i;
            bindCell(i, pos < tiles.size() ? tiles.get(pos) : null);
        }
        int pages = Grid.pages(tiles.size());
        dots.removeAllViews();
        for (int p = 0; p < pages; p++) {
            boolean on = p == focus.page;
            View dot = new View(this);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(px(6));
            shape.setColor(on ? ink : dotColor);
            dot.setBackground(shape);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(on ? 40 : 12), px(12));
            lp.setMarginEnd(px(10));
            dots.addView(dot, lp);
        }
        pageLabel.setText(getString(R.string.page_label, focus.page + 1, pages));
        paintFocus(false);
    }

    private void bindCell(int i, Tiles.Tile tile) {
        if (tile == null) {
            cells[i].setVisibility(View.INVISIBLE);
            cellArt[i].setImageDrawable(null);
            return;
        }
        cells[i].setVisibility(View.VISIBLE);
        cellName[i].setText(tile.label);
        Drawable d = artFor(tile);
        ImageView image = cellArt[i];
        if (Voxel.forTile(tile) != 0) {
            // The voxel image is the tile: full bleed, its background is the tile's grey.
            image.setScaleType(ImageView.ScaleType.FIT_XY);
            image.setPadding(0, 0, 0, 0);
        } else {
            // The app's own icon centred on the grey tile; a 16:9 banner gets a wider box.
            boolean banner = d != null && d.getIntrinsicWidth() >= d.getIntrinsicHeight() * 1.4f;
            int inset = px(banner ? 62 : 110);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setPadding(inset, inset, inset, inset);
        }
        image.setImageDrawable(d);
    }

    private Drawable artFor(Tiles.Tile tile) {
        Drawable d = art.get(tile.key);
        if (d != null) return d;
        int voxel = Voxel.forTile(tile);
        if (voxel != 0) {
            d = getDrawable(voxel);
        } else {
            ResolveInfo ri = tile.isLive() ? firstByPackage.get(tile.pkg) : activities.get(tile.key);
            d = ri != null ? appArt(ri) : null;
        }
        if (d != null) art.put(tile.key, d);
        return d;
    }

    /** The app's icon at a high density (it is drawn larger than a launcher icon), else its banner. */
    private Drawable appArt(ResolveInfo ri) {
        PackageManager pm = getPackageManager();
        ActivityInfo ai = ri.activityInfo;
        try {
            Resources res = pm.getResourcesForApplication(ai.applicationInfo);
            int icon = ri.getIconResource();
            if (icon != 0) return res.getDrawableForDensity(icon, DisplayMetrics.DENSITY_XXXHIGH, null);
            int banner = ai.banner != 0 ? ai.banner : ai.applicationInfo.banner;
            if (banner != 0) return res.getDrawableForDensity(banner, DisplayMetrics.DENSITY_XXXHIGH, null);
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
            Log.w(TAG, "no artwork for " + ai.packageName + "/" + ai.name + "; using the default icon", e);
        }
        return ri.loadIcon(pm);
    }

    /** The focused tile or header item grows and darkens; everything else rests. */
    private void paintFocus(boolean animate) {
        for (int i = 0; i < Grid.PER_PAGE; i++) {
            boolean on = !focus.header && i == focus.index;
            Motion.scale(cellArt[i], on ? config.focusScale : VoxConfig.REST_SCALE, animate);
            Motion.textColor(cellName[i], on ? ink : muted, animate);
        }
        paintChip(wifiIcon, wifiText, focus.header && focus.chip == Grid.CHIP_WIFI, animate);
        paintChip(gearIcon, null, focus.header && focus.chip == Grid.CHIP_SETTINGS, animate);
    }

    private void paintChip(ImageView icon, TextView label, boolean on, boolean animate) {
        Motion.scale(icon, on ? CHIP_ICON_FOCUS_SCALE : 1f, animate);
        Motion.alpha(icon, on ? 1f : CHIP_ICON_REST_ALPHA, animate);
        if (label != null) Motion.textColor(label, on ? ink : muted, animate);
    }

    // ---- keys -----------------------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Grid.Key key = arrow(keyCode);
        if (key != null) {
            if (!turning) moveFocus(key);
            return true;
        }
        if (isOk(keyCode)) {
            if (event.getRepeatCount() == 0) okDown = true;
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) return true;   // Back on Home does nothing
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isOk(keyCode)) {
            // Acts on release, so a press that began in another app (or on the screen before) does nothing here.
            if (okDown && !turning) activate();
            okDown = false;
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) return true;
        return arrow(keyCode) != null || super.onKeyUp(keyCode, event);
    }

    private static Grid.Key arrow(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return Grid.Key.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return Grid.Key.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return Grid.Key.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return Grid.Key.RIGHT;
            default: return null;
        }
    }

    private static boolean isOk(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    private void moveFocus(Grid.Key key) {
        Grid next = focus.move(key, tiles.size());
        Log.d(TAG, key + ": " + focus + " -> " + next);
        if (next.equals(focus)) return;
        if (next.page != focus.page) {
            turnTo(next);
            return;
        }
        focus = next;
        rememberFocusedKey();
        paintFocus(true);
    }

    /** Page turn: the grid fades out, the tiles swap, it fades back in. */
    private void turnTo(Grid next) {
        turning = true;
        gridLayer.animate().cancel();
        gridLayer.animate().alpha(0f).setDuration(PAGE_FADE_MS).withEndAction(() -> {
            focus = next;
            rememberFocusedKey();
            render();
            gridLayer.animate().alpha(1f).setDuration(PAGE_FADE_MS).withEndAction(() -> turning = false).start();
        }).start();
    }

    private void activate() {
        if (focus.header) {
            String action = focus.chip == Grid.CHIP_WIFI ? Settings.ACTION_WIFI_SETTINGS : Settings.ACTION_SETTINGS;
            open(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "SETTINGS");
            return;
        }
        int pos = focus.position();
        if (pos >= tiles.size()) return;
        Tiles.Tile tile = tiles.get(pos);
        Intent intent;
        if (tile.isLive()) {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(config.liveUri)).setPackage(Tiles.YOUTUBE_TV)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } else {
            // The leanback category, as the stock launcher and the remote use, so a running app resumes.
            intent = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(tile.pkg, tile.activity))
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
        open(intent, tile.label);
    }

    private void open(Intent intent, String name) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "could not open " + name + " with " + intent, e);
            Toast.makeText(this, getString(R.string.cant_open, name), Toast.LENGTH_SHORT).show();
        }
    }

    /** Home pressed while Home is in front (singleTask): back to page 1 without re-creating the activity. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Log.d(TAG, "onNewIntent " + intent + " stopped=" + stopped + " focus " + focus);
        // From an app (Home was stopped) focus stays on the tile it was on; only Home on Home resets.
        if (stopped || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        Grid first = Grid.first();
        if (turning) {
            gridLayer.animate().cancel();
            gridLayer.setAlpha(1f);
            turning = false;
        }
        if (first.page != focus.page) {
            turnTo(first);
        } else {
            focus = first;
            rememberFocusedKey();
            paintFocus(true);
        }
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        IntentFilter time = new IntentFilter(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        register(timeReceiver, time);
        pickGreeting();
        updateClock();
        updateNetwork();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Cleared here, not in onStart: Home pressed in an app delivers onNewIntent after onStart (Android 14).
        stopped = false;
        // A package change whose broadcast never arrived still shows up in PackageManager's change record.
        if (stale || changedSince(packageSequence).any) loadTiles();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        stopped = true;
        okDown = false;
        unregisterReceiver(timeReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(STATE_PAGE, focus.page);
        out.putInt(STATE_INDEX, focus.index);
        out.putBoolean(STATE_HEADER, focus.header);
        out.putInt(STATE_CHIP, focus.chip);
        out.putString(STATE_KEY, focusedKey);
    }

    /**
     * Another app needs memory while Home is hidden: let go of the artwork and rebuild on the way back. Never while
     * Home is on screen (RUNNING_LOW can arrive then), and not at UI_HIDDEN, the ordinary trip to an app.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (started) return;
        if (level < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && level != ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                && level != ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) return;
        art.clear();
        for (ImageView image : cellArt) image.setImageDrawable(null);
        stale = true;
        Log.i(TAG, "memory trim " + level + ": tile artwork released until Home is back");
    }

    // ---- header ---------------------------------------------------------------------------------

    private void pickGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greetingSlot = Greeting.slot(hour);
        greeting.setText(Greeting.pick(hour, config.greetings, random));
    }

    /** Shown only once the time is real (network time arrives after boot); follows time and zone changes. */
    private void updateClock() {
        long now = System.currentTimeMillis();
        if (!ClockText.valid(now)) {
            clock.setVisibility(View.INVISIBLE);
            return;
        }
        clock.setText(ClockText.format(now, ZoneId.systemDefault()));
        clock.setVisibility(View.VISIBLE);
        // The greeting is one line per visit, but a new part of the day gets a new line.
        if (!Greeting.slot(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)).equals(greetingSlot)) pickGreeting();
    }

    @SuppressWarnings("deprecation")   // getConnectionInfo still answers for apps holding ACCESS_FINE_LOCATION
    private void updateNetwork() {
        boolean online = false, ethernet = false, wifi = false;
        String ssid = null;
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (wifi) {
                WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                ssid = info != null ? info.getSSID() : null;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "network state not readable", e);
        }
        wifiText.setText(NetworkLabel.of(online, ethernet, wifi, ssid));
    }
}
