package tv.voxclean.launcher;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Vox's Home: a header (greeting, Wi-Fi, gear, clock) over a paged 4x2 grid of square tiles, a settings sheet, and
 * the wake intro. Laid out in 1920x1080 design pixels scaled to the screen. Focus is Vox's own state (Grid), not
 * Android's view focus: the D-pad rules are the spec's, and the focused thing grows and darkens.
 */
public class MainActivity extends Activity {
    private static final String TAG = "Vox";
    private static final long PAGE_FADE_MS = 320, HOLD_MS = 500, RESUME_FADE_MS = 300;
    private static final long LIFT_MS = 550, SETTLE_MS = 400, GLIDE_MS = 500, SHEET_MS = 400;
    private static final long LIVE_PULSE_MS = 1200, BLINK_MS = 530, INTRO_SKIP_FADE_MS = 250;
    /** A cold start this soon after boot is the TV turning on, so it gets the wake intro too. */
    private static final long BOOT_INTRO_WINDOW_MS = 5 * 60_000;
    /** SCREEN_ON and Home's onStart arrive in either order; this close together, Home is what the screen woke into. */
    private static final long WAKE_WINDOW_MS = 1500;
    private static final float CHIP_ICON_REST_ALPHA = 0.55f, CHIP_ICON_FOCUS_SCALE = 1.25f;
    private static final float LIFT_SCALE = 1.03f, LIFT_TILT = -2f, STEP_BACK_SCALE = 0.97f, STEP_BACK_ALPHA = 0.4f;
    private static final String STATE_PAGE = "page", STATE_INDEX = "index", STATE_HEADER = "header",
            STATE_CHIP = "chip", STATE_KEY = "key";
    /** Debug only (setprop log.tag.Vox DEBUG): am start -n tv.voxclean.launcher/.MainActivity --ez vox.intro true */
    private static final String EXTRA_INTRO = "vox.intro";
    private static final int SHEET_NETWORK = 0, SHEET_LIBRARY = 1, SHEET_HOME = 2, SHEET_ANDROID = 3,
            SHEET_RESTART = 4, SHEET_ITEMS = 5;
    /** The settle curve, cubic-bezier(.32,.72,0,1): tiles making room, the page dots, the sheet. */
    private static final PathInterpolator SETTLE = new PathInterpolator(.32f, .72f, 0f, 1f);
    private final Object introToken = new Object();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private float scale;
    private Typeface font, light;
    private int ink, muted, tileColor, dotColor, liveColor;
    private VoxConfig config;
    private Store orderStore, libraryStore;
    private SharedPreferences prefs;

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

    /** The order before a move began (null when no tile is lifted); Back puts it back. */
    private List<Tiles.Tile> moveStart;
    private String movingKey;
    /** OK was pressed again after the lift, so its release drops (the lifting press's own release does not). */
    private boolean dropArmed;
    private final Runnable lift = this::startMove;

    private boolean sheetOpen;
    private int sheetFocus;

    private boolean introRunning, introBootPending, introAwaitingFocus;
    private long screenOnAt = -WAKE_WINDOW_MS * 2;
    /** A key that skipped the intro: its release is swallowed too. */
    private int swallowUp = -1;
    private boolean cursorOn;
    private final Runnable blink = new Runnable() {
        @Override
        public void run() {
            cursorOn = !cursorOn;
            introCursor.setVisibility(cursorOn ? View.VISIBLE : View.INVISIBLE);
            handler.postAtTime(this, introToken, SystemClock.uptimeMillis() + BLINK_MS);
        }
    };

    private TextView greeting, wifiText, clock, pageLabel, hint;
    private View wifiChip, gearChip, footer;
    private ImageView wifiIcon, gearIcon;
    private FrameLayout gridLayer;
    /** Each slot: the outer cell glides (FLIP); the card inside lifts, steps back and casts the shadow. */
    private final FrameLayout[] cells = new FrameLayout[Grid.PER_PAGE];
    private final FrameLayout[] cards = new FrameLayout[Grid.PER_PAGE];
    private final ImageView[] cellArt = new ImageView[Grid.PER_PAGE];
    private final TextView[] cellName = new TextView[Grid.PER_PAGE];
    private LinearLayout dots;
    private final List<View> dotViews = new ArrayList<>();
    private ObjectAnimator livePulse;

    private View scrim;
    private LinearLayout sheet;
    private final View[] sheetItems = new View[SHEET_ITEMS];
    private final ImageView[] sheetIcons = new ImageView[SHEET_ITEMS];
    private final List<TextView> sheetTexts = new ArrayList<>();
    private TextView netName, netSignal, netIp, netWifi;

    private FrameLayout intro;
    private TextView introText;
    private View introCursor;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // An update sends REMOVED then ADDED: keep the tile until the new APK is in.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            // A lifted tile is put back: the order it was moving through just changed.
            cancelMove(false);
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

    /** The wake intro: ready (white) as the screen goes off, played when the screen comes back on Home. */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                if (started) coverForIntro();
                return;
            }
            screenOnAt = SystemClock.uptimeMillis();
            if (started) playIntro();
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
        light = getResources().getFont(R.font.plex_mono_light);
        ink = getColor(R.color.ink);
        muted = getColor(R.color.muted);
        tileColor = getColor(R.color.tile);
        dotColor = getColor(R.color.dot);
        liveColor = getColor(R.color.live);
        config = VoxConfig.load(this);
        orderStore = new Store(this, Store.ORDER);
        libraryStore = new Store(this, Store.LIBRARY);
        prefs = getSharedPreferences(LastApp.PREFS, MODE_PRIVATE);
        if (state != null) {
            focus = new Grid(state.getInt(STATE_PAGE), state.getInt(STATE_INDEX), state.getBoolean(STATE_HEADER),
                    state.getInt(STATE_CHIP, Grid.CHIP_SETTINGS));
            focusedKey = state.getString(STATE_KEY);
        } else {
            introBootPending = SystemClock.elapsedRealtime() < BOOT_INTRO_WINDOW_MS;
        }
        setContentView(buildLayout());

        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        // For the activity's whole life: an install while an app is in front must still mark the tiles stale.
        register(packageReceiver, packages);
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        register(screenReceiver, screen);
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
        root.setClipChildren(false);

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

        // Grid: 4x2 squares of 380 px with 10 px gaps, top-left at (185, 178). Nothing clips, so a lifted tile's
        // shadow and a gliding tile draw past their slots.
        gridLayer = new FrameLayout(this);
        gridLayer.setClipChildren(false);
        for (int i = 0; i < Grid.PER_PAGE; i++) {
            FrameLayout cell = new FrameLayout(this);
            cell.setClipChildren(false);
            FrameLayout card = new FrameLayout(this);
            card.setBackgroundColor(tileColor);
            ImageView image = new ImageView(this);
            card.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            TextView name = text(16, 0.12f, muted);
            name.setMaxWidth(px(332));
            FrameLayout.LayoutParams n = at(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            n.topMargin = px(20);
            n.rightMargin = px(24);
            card.addView(name, n);
            cell.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams c = at(px(380), px(380), Gravity.TOP | Gravity.START);
            c.leftMargin = slotX(i);
            c.topMargin = slotY(i);
            gridLayer.addView(cell, c);
            cells[i] = cell;
            cards[i] = card;
            cellArt[i] = image;
            cellName[i] = name;
        }
        FrameLayout.LayoutParams gl = at(px(4 * 380 + 3 * 10), px(2 * 380 + 10), Gravity.TOP | Gravity.START);
        gl.leftMargin = px(185);
        gl.topMargin = px(178);
        root.addView(gridLayer, gl);

        // Footer: page dots bottom left, PAGE n / m bottom right. During a move the hint takes its place.
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(dots, new LinearLayout.LayoutParams(0, px(12), 1f));
        pageLabel = text(16, 0.12f, muted);
        bar.addView(pageLabel);
        FrameLayout.LayoutParams f = at(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        f.leftMargin = px(185);
        f.rightMargin = px(185);
        f.bottomMargin = px(56);
        root.addView(bar, f);
        footer = bar;
        hint = text(16, 0.12f, ink);
        hint.setText(R.string.moving_hint);
        hint.setAlpha(0f);
        FrameLayout.LayoutParams h = at(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        h.bottomMargin = px(56);
        root.addView(hint, h);

        buildSheet(root);
        buildIntro(root);
        return root;
    }

    private int slotX(int i) {
        return px((i % Grid.COLS) * 390);
    }

    private int slotY(int i) {
        return px((i / Grid.COLS) * 390);
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

    /** The settings sheet: 640 px from the right over Home under a white scrim, both above every tile layer. */
    private void buildSheet(FrameLayout root) {
        scrim = new View(this);
        scrim.setBackgroundColor(Color.argb(184, 255, 255, 255));
        scrim.setVisibility(View.GONE);
        scrim.setTranslationZ(px(40));
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundColor(getColor(R.color.page));
        sheet.setPadding(px(72), px(64), px(72), px(64));
        sheet.setClipChildren(false);
        sheet.setClipToPadding(false);
        sheet.setElevation(px(24));
        sheet.setTranslationZ(px(40));
        sheet.setVisibility(View.GONE);
        TextView title = text(22, 0.08f, ink);
        title.setText(R.string.settings_title);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.bottomMargin = px(48);
        sheet.addView(title, tl);

        // Network card: name, signal, IP, and the way to Android's Wi-Fi screen (apps cannot join networks).
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(tileColor);
        card.setPadding(px(32), px(28), px(32), px(28));
        netName = text(22, 0, muted);
        netSignal = text(18, 0.04f, muted);
        netIp = text(18, 0.04f, muted);
        netWifi = text(18, 0.04f, muted);
        netWifi.setText("WI-FI NETWORKS → ANDROID");
        for (TextView t : new TextView[]{netName, netSignal, netIp, netWifi}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = px(t == netName ? 12 : 8);
            card.addView(t, lp);
            sheetTexts.add(t);
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.bottomMargin = px(36);
        sheet.addView(card, cl);
        sheetItems[SHEET_NETWORK] = card;

        sheetRow(SHEET_LIBRARY, R.drawable.ic_apps, "APP LIBRARY", "OPEN");
        sheetRow(SHEET_HOME, R.drawable.ic_home, "CHOOSE DEFAULT HOME", "ANDROID");
        sheetRow(SHEET_ANDROID, R.drawable.ic_settings, "ANDROID SETTINGS", "ANDROID");
        sheetRow(SHEET_RESTART, R.drawable.ic_restart, "RESTART", "ANDROID");

        FrameLayout.LayoutParams sl = at(px(640), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        root.addView(sheet, sl);
    }

    private void sheetRow(int index, int icon, String label, String go) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(8), px(22), px(8), px(22));
        row.setClipChildren(false);
        row.setClipToPadding(false);
        ImageView i = new ImageView(this);
        i.setImageResource(icon);
        i.setAlpha(CHIP_ICON_REST_ALPHA);
        row.addView(i, new LinearLayout.LayoutParams(px(28), px(28)));
        TextView t = text(22, 0.04f, muted);
        t.setText(label);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(px(22));
        row.addView(t, tl);
        TextView g = text(16, 0.1f, muted);
        g.setText(go);
        row.addView(g);
        sheet.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        View rule = new View(this);
        rule.setBackgroundColor(getColor(R.color.rule));
        sheet.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(1))));
        sheetItems[index] = row;
        sheetIcons[index] = i;
        sheetTexts.add(t);
        sheetTexts.add(g);
    }

    /** The wake intro: a white page over everything, the greeting typed big at the header's left edge. */
    private void buildIntro(FrameLayout root) {
        intro = new FrameLayout(this);
        intro.setBackgroundColor(getColor(R.color.page));
        intro.setTranslationZ(px(80));
        intro.setVisibility(View.GONE);
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        introText = text(64, 0.04f, ink);
        introText.setTypeface(light);
        line.addView(introText);
        introCursor = new View(this);
        introCursor.setBackgroundColor(ink);
        // A full-height block: 1.2 em tall, 0.55 em wide, of the 64 px line.
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(px(64 * 0.55f), px(64 * 1.2f));
        cl.setMarginStart(px(64 * 0.06f));
        line.addView(introCursor, cl);
        FrameLayout.LayoutParams ll = at(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL | Gravity.START);
        ll.leftMargin = px(185);
        intro.addView(line, ll);
        root.addView(intro, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
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
        // The Library's choices on top of the starting hidden set, then the owner's saved order.
        List<Tiles.Tile> arranged = Tiles.arrange(rows, HiddenApps.parse(libraryStore.read()).apply(config.hiddenApps));
        tiles = Reorder.apply(arranged, orderStore.read());
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

    /** Binds the current page's tiles, the dots and the page label, with focus and move state drawn without motion. */
    private void render() {
        for (int i = 0; i < Grid.PER_PAGE; i++) {
            int pos = focus.page * Grid.PER_PAGE + i;
            bindCell(i, pos < tiles.size() ? tiles.get(pos) : null);
        }
        int pages = Grid.pages(tiles.size());
        if (dotViews.size() != pages) buildDots(pages);
        paintDots(false);
        pageLabel.setText(getString(R.string.page_label, focus.page + 1, pages));
        paintFocus(false);
        paintMove(false);
    }

    private void bindCell(int i, Tiles.Tile tile) {
        cells[i].animate().cancel();
        cells[i].setTranslationX(0);
        cells[i].setTranslationY(0);
        if (tile == null) {
            cells[i].setVisibility(View.INVISIBLE);
            cellArt[i].setImageDrawable(null);
            cellName[i].setTag(null);
            return;
        }
        cells[i].setVisibility(View.VISIBLE);
        cellName[i].setText(tile.label);
        cellName[i].setTag(tile.key);
        Drawable d = artFor(tile);
        ImageView image = cellArt[i];
        if (Voxel.forTile(tile) != 0) {
            // The voxel image is the tile: full bleed, its background is the tile's colour.
            image.setScaleType(ImageView.ScaleType.FIT_XY);
            image.setPadding(0, 0, 0, 0);
        } else {
            // The app's own icon centred on the tile; a 16:9 banner gets a wider box.
            boolean banner = d != null && d.getIntrinsicWidth() >= d.getIntrinsicHeight() * 1.4f;
            int inset = px(banner ? 62 : 110);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setPadding(inset, inset, inset, inset);
        }
        image.setImageDrawable(d);
        if (tile.isLive()) {
            pulseLive(cellName[i]);
        } else if (livePulse != null && livePulse.getTarget() == cellName[i]) {
            stopLivePulse();
        }
    }

    /** The Live label is red, focused or not, and pulses slowly like an on-air light while Home is on screen. */
    private void pulseLive(TextView name) {
        Motion.textColor(name, liveColor, false);
        if (livePulse != null && livePulse.getTarget() == name) return;
        stopLivePulse();
        livePulse = ObjectAnimator.ofFloat(name, View.ALPHA, 1f, 0.2f).setDuration(LIVE_PULSE_MS);
        livePulse.setInterpolator(new AccelerateDecelerateInterpolator());
        livePulse.setRepeatCount(ValueAnimator.INFINITE);
        livePulse.setRepeatMode(ValueAnimator.REVERSE);
        livePulse.start();
        if (!started) livePulse.pause();
    }

    private void stopLivePulse() {
        if (livePulse == null) return;
        livePulse.cancel();
        ((View) livePulse.getTarget()).setAlpha(1f);
        livePulse = null;
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

    /** The focused tile or header item grows and darkens; everything else rests. The Live label stays red. */
    private void paintFocus(boolean animate) {
        for (int i = 0; i < Grid.PER_PAGE; i++) {
            boolean on = !focus.header && i == focus.index;
            Motion.scale(cellArt[i], on ? config.focusScale : VoxConfig.REST_SCALE, animate);
            if (!Tiles.LIVE_KEY.equals(cellName[i].getTag())) Motion.textColor(cellName[i], on ? ink : muted, animate);
        }
        paintChip(wifiIcon, wifiText, focus.header && focus.chip == Grid.CHIP_WIFI, animate);
        paintChip(gearIcon, null, focus.header && focus.chip == Grid.CHIP_SETTINGS, animate);
    }

    private void paintChip(ImageView icon, TextView label, boolean on, boolean animate) {
        Motion.scale(icon, on ? CHIP_ICON_FOCUS_SCALE : 1f, animate);
        Motion.alpha(icon, on ? 1f : CHIP_ICON_REST_ALPHA, animate);
        if (label != null) Motion.textColor(label, on ? ink : muted, animate);
    }

    // ---- page dots ------------------------------------------------------------------------------

    /** Only when the number of pages changes; a page turn animates the dots that are there. */
    private void buildDots(int pages) {
        dots.removeAllViews();
        dotViews.clear();
        for (int p = 0; p < pages; p++) {
            View dot = new View(this);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(px(6));
            shape.setColor(dotColor);
            dot.setBackground(shape);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(12), px(12));
            lp.setMarginEnd(px(10));
            dots.addView(dot, lp);
            dotViews.add(dot);
        }
    }

    /** The active pill (40 px, ink) glides to the page's dot: the old one shrinks to 12 px as the new one grows. */
    private void paintDots(boolean animate) {
        for (int p = 0; p < dotViews.size(); p++) {
            View dot = dotViews.get(p);
            boolean on = p == focus.page;
            int fromW = dot.getLayoutParams().width, toW = px(on ? 40 : 12);
            int toColor = on ? ink : dotColor;
            ValueAnimator running = (ValueAnimator) dot.getTag(R.id.color_anim);
            if (running != null) running.cancel();
            if (!animate || fromW == toW) {
                setDot(dot, toW, toColor);
                continue;
            }
            int fromColor = on ? dotColor : ink;
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(PAGE_FADE_MS);
            a.setInterpolator(SETTLE);
            a.addUpdateListener(v -> {
                float t = (float) v.getAnimatedValue();
                setDot(dot, Math.round(fromW + (toW - fromW) * t), blend(fromColor, toColor, t));
            });
            dot.setTag(R.id.color_anim, a);
            a.start();
        }
    }

    private static void setDot(View dot, int width, int color) {
        ((GradientDrawable) dot.getBackground()).setColor(color);
        ViewGroup.LayoutParams lp = dot.getLayoutParams();
        if (lp.width != width) {
            lp.width = width;
            dot.setLayoutParams(lp);
        }
    }

    private static int blend(int from, int to, float t) {
        return Color.argb(
                Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t),
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t));
    }

    // ---- keys -----------------------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (introRunning) {
            // Any key skips the intro; the key itself does nothing else.
            if (event.getRepeatCount() == 0) {
                swallowUp = keyCode;
                finishIntro(INTRO_SKIP_FADE_MS);
            }
            return true;
        }
        if (sheetOpen) return sheetKeyDown(keyCode, event);
        boolean back = keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE;
        Grid.Key key = arrow(keyCode);
        if (moveStart != null) {
            if (key != null) {
                if (!turning) carry(key);
            } else if (isOk(keyCode)) {
                if (event.getRepeatCount() == 0) dropArmed = true;
            } else if (back) {
                if (event.getRepeatCount() == 0) cancelMove(true);
            } else {
                return super.onKeyDown(keyCode, event);
            }
            return true;
        }
        if (key != null) {
            if (!turning) moveFocus(key);
            return true;
        }
        if (isOk(keyCode)) {
            if (event.getRepeatCount() == 0) {
                okDown = true;
                // Hold OK on a tile to pick it up.
                if (!focus.header && !turning && focus.position() < tiles.size()) handler.postDelayed(lift, HOLD_MS);
            } else if (event.isLongPress() && okDown) {
                // The system's long-press repeat (also what `input keyevent --longpress` sends) lifts at once.
                handler.removeCallbacks(lift);
                startMove();
            }
            return true;
        }
        if (back) return true;   // Back on Home does nothing
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == swallowUp) {
            swallowUp = -1;
            return true;
        }
        if (introRunning) return true;
        if (sheetOpen) return sheetKeyUp(keyCode, event);
        if (isOk(keyCode)) {
            handler.removeCallbacks(lift);
            if (moveStart != null) {
                if (dropArmed) dropMove();
            } else if (okDown && !turning) {
                // Acts on release, so a press that began in another app (or on the screen before) does nothing here.
                activate();
            }
            okDown = false;
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) return true;
        return arrow(keyCode) != null || super.onKeyUp(keyCode, event);
    }

    /** Focus and Home-intent logging, off unless switched on over ADB: setprop log.tag.Vox DEBUG */
    private static boolean debug() {
        return Log.isLoggable(TAG, Log.DEBUG);
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
        handler.removeCallbacks(lift);
        Grid next = focus.move(key, tiles.size());
        if (debug()) Log.d(TAG, key + ": " + focus + " -> " + next);
        if (next.equals(focus)) return;
        if (next.page != focus.page) {
            turnTo(next);
            return;
        }
        focus = next;
        rememberFocusedKey();
        paintFocus(true);
    }

    /** Page turn: the grid fades out, the tiles swap, it fades back in; the page dots glide meanwhile. */
    private void turnTo(Grid next) {
        turning = true;
        Grid target = next.clamp(tiles.size());
        int from = focus.page;
        focus = new Grid(target.page, focus.index, focus.header, focus.chip);
        paintDots(true);
        focus = new Grid(from, focus.index, focus.header, focus.chip);
        gridLayer.animate().cancel();
        gridLayer.animate().alpha(0f).setDuration(PAGE_FADE_MS).withEndAction(() -> {
            // Clamped: apps removed during the fade may have shortened or dropped the target page.
            focus = next.clamp(tiles.size());
            rememberFocusedKey();
            render();
            gridLayer.animate().alpha(1f).setDuration(PAGE_FADE_MS).withEndAction(() -> turning = false).start();
        }).start();
    }

    private void activate() {
        if (focus.header) {
            if (focus.chip == Grid.CHIP_WIFI) {
                open(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "WI-FI", null);
            } else {
                openSheet();
            }
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
        open(intent, tile.label, tile.pkg);
    }

    /**
     * Opens with a cross-fade. pkg, when given, is kept as the last app Home opened: the screensaver's fallback
     * when UsageStats has nothing.
     */
    private void open(Intent intent, String name, String pkg) {
        try {
            startActivity(intent,
                    ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out).toBundle());
            // Not in onPause: the screensaver starting pauses Home too, and over Home it must read "Home in front".
            if (pkg != null) prefs.edit().putString(LastApp.KEY_APP, pkg).putBoolean(LastApp.KEY_HOME_FRONT, false).apply();
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "could not open " + name + " with " + intent, e);
            Toast.makeText(this, getString(R.string.cant_open, name), Toast.LENGTH_SHORT).show();
        }
    }

    // ---- reorder --------------------------------------------------------------------------------

    /** Hold OK: the tile lifts, the others step back, and the hint replaces the page footer. */
    private void startMove() {
        int pos = focus.position();
        if (focus.header || pos >= tiles.size() || moveStart != null || sheetOpen) return;
        okDown = false;   // the release of this press neither opens the app nor drops
        dropArmed = false;
        moveStart = new ArrayList<>(tiles);
        movingKey = tiles.get(pos).key;
        tiles = new ArrayList<>(tiles);
        paintMove(true);
        if (debug()) Log.d(TAG, "move start " + movingKey + " at " + pos);
    }

    /** Arrows carry the lifted tile by re-insertion; on the page the others glide from where they were (FLIP). */
    private void carry(Grid.Key key) {
        int from = focus.position();
        int to = Reorder.target(from, key, tiles.size());
        if (to < 0) return;
        Map<String, Integer> before = slots();
        Reorder.move(tiles, from, to);
        Grid next = Grid.at(to, focus);
        if (next.page != focus.page) {
            turnTo(next);
        } else {
            focus = next;
            rememberFocusedKey();
            render();
            glide(before);
        }
        if (debug()) Log.d(TAG, "carry " + key + ": " + from + " -> " + to);
    }

    /** Which slot each tile key sits in on this page. */
    private Map<String, Integer> slots() {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < Grid.PER_PAGE; i++) {
            Object key = cellName[i].getTag();
            if (key != null && cells[i].getVisibility() == View.VISIBLE) m.put((String) key, i);
        }
        return m;
    }

    /** FLIP: each moved tile starts at its old slot and travels home; the carried one on the carried spring. */
    private void glide(Map<String, Integer> before) {
        for (int j = 0; j < Grid.PER_PAGE; j++) {
            Object key = cellName[j].getTag();
            Integer was = key != null ? before.get(key) : null;
            if (was == null || was == j) continue;
            View cell = cells[j];
            cell.setTranslationX(slotX(was) - slotX(j));
            cell.setTranslationY(slotY(was) - slotY(j));
            boolean carried = key.equals(movingKey);
            cell.animate().translationX(0).translationY(0)
                    .setDuration(carried ? LIFT_MS : GLIDE_MS)
                    .setInterpolator(carried ? new OvershootInterpolator(1.2f) : SETTLE)
                    .start();
        }
    }

    /** OK: the lift eases off in place and the order is saved. */
    private void dropMove() {
        if (moveStart == null) return;
        boolean changed = !Reorder.keys(moveStart).equals(Reorder.keys(tiles));
        endMove();
        if (changed && !orderStore.write(Reorder.keys(tiles))) {
            Toast.makeText(this, R.string.cant_save, Toast.LENGTH_LONG).show();
        }
    }

    /** Back (glide: true) puts the tile home; an app change or leaving Home puts it back without motion. */
    private void cancelMove(boolean glide) {
        if (moveStart == null) return;
        String key = movingKey;
        Map<String, Integer> before = slots();
        tiles = moveStart;
        int pos = Tiles.indexOf(tiles, key);
        Grid home = pos >= 0 ? Grid.at(pos, focus) : focus;
        if (pos < 0 || (glide && home.page != focus.page)) {
            endMove();
            if (pos < 0) render();
            else turnTo(home);
            return;
        }
        focus = home;
        rememberFocusedKey();
        // Drawn still lifted at its home slot, glided there, then the lift eases off.
        render();
        if (glide) glide(before);
        endMove();
    }

    private void endMove() {
        moveStart = null;
        dropArmed = false;
        paintMove(true);
        movingKey = null;
    }

    /** Lifted tile, stepped-back tiles and the hint, or all of them at rest. */
    private void paintMove(boolean animate) {
        boolean moving = moveStart != null;
        for (int i = 0; i < Grid.PER_PAGE; i++) {
            boolean carried = moving && movingKey != null && movingKey.equals(cellName[i].getTag());
            FrameLayout card = cards[i];
            float s = carried ? LIFT_SCALE : moving ? STEP_BACK_SCALE : 1f;
            float a = moving && !carried ? STEP_BACK_ALPHA : 1f;
            float y = carried ? -px(12) : 0f;
            float z = carried ? px(24) : 0f;
            cells[i].setTranslationZ(carried ? px(10) : 0f);
            card.animate().cancel();
            cellArt[i].animate().cancel();
            if (animate) {
                card.animate().scaleX(s).scaleY(s).alpha(a).translationY(y).translationZ(z)
                        .setDuration(carried ? LIFT_MS : SETTLE_MS)
                        .setInterpolator(carried ? new OvershootInterpolator(1.2f) : SETTLE).start();
                cellArt[i].animate().rotation(carried ? LIFT_TILT : 0f).setDuration(Motion.FADE_MS).start();
            } else {
                card.setScaleX(s);
                card.setScaleY(s);
                card.setAlpha(a);
                card.setTranslationY(y);
                card.setTranslationZ(z);
                cellArt[i].setRotation(carried ? LIFT_TILT : 0f);
            }
        }
        float footerAlpha = moving ? 0f : 1f, hintAlpha = moving ? 1f : 0f;
        if (animate) {
            footer.animate().alpha(footerAlpha).setDuration(250).start();
            hint.animate().alpha(hintAlpha).setDuration(250).start();
        } else {
            footer.setAlpha(footerAlpha);
            hint.setAlpha(hintAlpha);
        }
    }

    // ---- settings sheet -------------------------------------------------------------------------

    private void openSheet() {
        updateNetwork();
        sheetOpen = true;
        sheetFocus = SHEET_LIBRARY;
        paintSheet(false);
        scrim.setVisibility(View.VISIBLE);
        sheet.setVisibility(View.VISIBLE);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(SHEET_MS).setInterpolator(SETTLE).withEndAction(null).start();
        sheet.setTranslationX(px(640));
        sheet.animate().translationX(0).setDuration(SHEET_MS).setInterpolator(SETTLE).withEndAction(null).start();
    }

    private void closeSheet(boolean animate) {
        if (!sheetOpen) return;
        sheetOpen = false;
        if (!animate) {
            scrim.animate().cancel();
            sheet.animate().cancel();
            scrim.setVisibility(View.GONE);
            sheet.setVisibility(View.GONE);
            return;
        }
        scrim.animate().alpha(0f).setDuration(SHEET_MS).setInterpolator(SETTLE)
                .withEndAction(() -> scrim.setVisibility(View.GONE)).start();
        sheet.animate().translationX(px(640)).setDuration(SHEET_MS).setInterpolator(SETTLE)
                .withEndAction(() -> sheet.setVisibility(View.GONE)).start();
    }

    private boolean sheetKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            int next = Math.max(0, Math.min(SHEET_ITEMS - 1,
                    sheetFocus + (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1)));
            if (next != sheetFocus) {
                sheetFocus = next;
                paintSheet(true);
            }
            return true;
        }
        if (isOk(keyCode)) {
            if (event.getRepeatCount() == 0) okDown = true;
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getRepeatCount() == 0) closeSheet(true);
            return true;
        }
        return arrow(keyCode) != null || super.onKeyDown(keyCode, event);
    }

    private boolean sheetKeyUp(int keyCode, KeyEvent event) {
        if (isOk(keyCode)) {
            if (okDown) sheetActivate();
            okDown = false;
            return true;
        }
        return keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE || arrow(keyCode) != null
                || super.onKeyUp(keyCode, event);
    }

    private void sheetActivate() {
        switch (sheetFocus) {
            case SHEET_NETWORK:
                open(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "WI-FI", null);
                break;
            case SHEET_LIBRARY:
                // Home re-reads the Library's choices when it comes back.
                stale = true;
                open(new Intent(this, LibraryActivity.class), "LIBRARY", null);
                break;
            case SHEET_HOME:
                // The couch-side way back to upstream's launcher: the permission controller's Default home app.
                open(new Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        "DEFAULT HOME", null);
                break;
            case SHEET_ANDROID:
                open(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "SETTINGS", null);
                break;
            case SHEET_RESTART:
                // An app cannot restart the TV: TV Settings' reboot broadcast needs READ_PRIVILEGED_PHONE_STATE, and
                // no public intent opens its System screen. Restart is at Android settings > System > Restart.
                open(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "SETTINGS", null);
                break;
            default:
                break;
        }
    }

    /** Same focus rule as everywhere: the focused row's text darkens and its icon grows. */
    private void paintSheet(boolean animate) {
        for (TextView t : sheetTexts) Motion.textColor(t, muted, animate);
        for (int i = 0; i < SHEET_ITEMS; i++) {
            ImageView icon = sheetIcons[i];
            if (icon == null) continue;
            boolean on = i == sheetFocus;
            Motion.scale(icon, on ? CHIP_ICON_FOCUS_SCALE : 1f, animate);
            Motion.alpha(icon, on ? 1f : CHIP_ICON_REST_ALPHA, animate);
        }
        View item = sheetItems[sheetFocus];
        if (item instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) item;
            for (int c = 0; c < group.getChildCount(); c++) {
                View child = group.getChildAt(c);
                if (child instanceof TextView) Motion.textColor((TextView) child, ink, animate);
            }
        }
    }

    // ---- wake intro -----------------------------------------------------------------------------

    /** The screen is going off on Home: go white now, so waking never flashes the grid before the intro. */
    private void coverForIntro() {
        handler.removeCallbacksAndMessages(introToken);
        intro.animate().cancel();
        intro.setAlpha(1f);
        intro.setVisibility(View.VISIBLE);
        introText.setText("");
        introCursor.setVisibility(View.VISIBLE);
    }

    /**
     * Types the greeting with beats (Typing), the cursor blinking while it waits and solid while it types; then the
     * cursor goes, the line sits alone for a beat, and Home fades in. Any key skips it.
     */
    private void playIntro() {
        if (introRunning) return;
        introRunning = true;
        closeSheet(false);
        cancelMove(false);
        handler.removeCallbacks(lift);
        coverForIntro();
        // A plain white page first: the TV plays its own wake animation over the screen, so typing waits until
        // Home has window focus and then intro_delay_ms more. Keys skip it all along.
        introCursor.setVisibility(View.INVISIBLE);
        introAwaitingFocus = !hasWindowFocus();
        if (!introAwaitingFocus) handler.postAtTime(this::typeIntro, introToken, SystemClock.uptimeMillis() + config.introDelayMs);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && introRunning && introAwaitingFocus) {
            introAwaitingFocus = false;
            handler.postAtTime(this::typeIntro, introToken, SystemClock.uptimeMillis() + config.introDelayMs);
        }
    }

    private void typeIntro() {
        String line = greeting.getText().toString();
        int[] times = Typing.schedule(line, random);
        long t0 = SystemClock.uptimeMillis();
        startBlink();
        if (times.length > 0) at(t0, times[0] - 10, this::solidCursor);
        for (int i = 0; i < times.length; i++) {
            int n = i + 1;
            at(t0, times[i], () -> introText.setText(line.substring(0, n)));
        }
        int typed = Typing.typed(times);
        at(t0, typed + 40, this::startBlink);
        at(t0, typed + Typing.BLINK_AFTER_MS, () -> {
            handler.removeCallbacks(blink, introToken);
            introCursor.setVisibility(View.INVISIBLE);
        });
        at(t0, typed + Typing.BLINK_AFTER_MS + Typing.BEAT_MS, () -> finishIntro(Typing.FADE_MS));
        if (debug()) Log.d(TAG, "intro: " + line + " typed by " + typed + " ms");
    }

    private void at(long t0, long delay, Runnable r) {
        handler.postAtTime(r, introToken, t0 + delay);
    }

    private void startBlink() {
        handler.removeCallbacks(blink, introToken);
        cursorOn = true;
        introCursor.setVisibility(View.VISIBLE);
        handler.postAtTime(blink, introToken, SystemClock.uptimeMillis() + BLINK_MS);
    }

    private void solidCursor() {
        handler.removeCallbacks(blink, introToken);
        introCursor.setVisibility(View.VISIBLE);
    }

    /** The line fades out over Home (Home is under the white page all along). */
    private void finishIntro(long fadeMs) {
        handler.removeCallbacksAndMessages(introToken);
        introRunning = false;
        intro.animate().cancel();
        intro.animate().alpha(0f).setDuration(fadeMs).setInterpolator(SETTLE)
                .withEndAction(() -> intro.setVisibility(View.GONE)).start();
    }

    /** Gone at once: an app came in front instead of Home. */
    private void dropIntro() {
        handler.removeCallbacksAndMessages(introToken);
        introRunning = false;
        intro.animate().cancel();
        intro.setVisibility(View.GONE);
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    /** Home pressed while Home is in front (singleTask): back to page 1 without re-creating the activity. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (debug()) Log.d(TAG, "onNewIntent " + intent + " stopped=" + stopped + " focus " + focus);
        if (debug() && intent.getBooleanExtra(EXTRA_INTRO, false)) {
            playIntro();
            return;
        }
        if (introRunning) finishIntro(INTRO_SKIP_FADE_MS);
        // From an app (Home was stopped) focus stays on the tile it was on; only Home on Home resets.
        if (stopped || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        closeSheet(true);
        cancelMove(false);
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
        if (livePulse != null) livePulse.resume();
        if (introBootPending || SystemClock.uptimeMillis() - screenOnAt < WAKE_WINDOW_MS) {
            introBootPending = false;
            playIntro();
        } else if (!introRunning && intro.getVisibility() == View.VISIBLE) {
            // Covered as the screen went off. SCREEN_ON may still be on its way; if it has not played the intro
            // by then, the screen woke for something else and the cover goes. playIntro clears this check.
            handler.postAtTime(() -> {
                if (!introRunning) dropIntro();
            }, introToken, SystemClock.uptimeMillis() + WAKE_WINDOW_MS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean fromApp = stopped;
        // Cleared here, not in onStart: Home pressed in an app delivers onNewIntent after onStart (Android 14).
        stopped = false;
        prefs.edit().putBoolean(LastApp.KEY_HOME_FRONT, true).apply();
        // A package change whose broadcast never arrived still shows up in PackageManager's change record.
        if (stale || changedSince(packageSequence).any) loadTiles();
        // Back from an app: the grid fades in, matching the system's return animation.
        if (fromApp && !introRunning && !turning) {
            gridLayer.setAlpha(0f);
            gridLayer.animate().alpha(1f).setDuration(RESUME_FADE_MS).start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(lift);
        // A move ends when Home leaves the screen: the tile goes back where it was.
        cancelMove(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        stopped = true;
        okDown = false;
        swallowUp = -1;
        unregisterReceiver(timeReceiver);
        if (livePulse != null) livePulse.pause();
        closeSheet(false);
        // Screen going off (onStop can come before SCREEN_OFF): the white cover, ready for the wake. Otherwise an
        // app came in front.
        PowerManager power = getSystemService(PowerManager.class);
        if (power == null || power.isInteractive()) {
            dropIntro();
        } else {
            introRunning = false;
            coverForIntro();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        unregisterReceiver(screenReceiver);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
        stopLivePulse();
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
        String ssid = null, ip = null;
        int rssi = Integer.MIN_VALUE;
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
                if (ip == null) ip = ipv4(cm.getLinkProperties(net));
            }
            if (wifi) {
                WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                ssid = info != null ? info.getSSID() : null;
                if (info != null) rssi = info.getRssi();
            }
        } catch (SecurityException e) {
            Log.w(TAG, "network state not readable", e);
        }
        String label = NetworkLabel.of(online, ethernet, wifi, ssid);
        wifiText.setText(label);
        netName.setText(label);
        netSignal.setText("SIGNAL  " + (wifi && !ethernet && rssi != Integer.MIN_VALUE ? signal(rssi) : "—"));
        netIp.setText("IP  " + (ip != null ? ip : "—"));
    }

    private static String ipv4(LinkProperties lp) {
        if (lp == null) return null;
        for (LinkAddress a : lp.getLinkAddresses()) {
            if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
        }
        return null;
    }

    @SuppressWarnings("deprecation")   // calculateSignalLevel(int, int): the instance form needs API 30
    private static String signal(int rssi) {
        switch (WifiManager.calculateSignalLevel(rssi, 4)) {
            case 3: return "EXCELLENT";
            case 2: return "GOOD";
            case 1: return "FAIR";
            default: return "WEAK";
        }
    }
}
