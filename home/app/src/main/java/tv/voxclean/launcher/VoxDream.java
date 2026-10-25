package tv.voxclean.launcher;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.service.dreams.DreamService;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Vox's screensaver: a line about the app that was in front when it started (else a time-of-day greeting), streamed
 * in and out with ColdHarbor's motion over the warm beige page.
 */
public class VoxDream extends DreamService {
    private static final String TAG = "Vox";
    private static final long LOOKBACK_MS = 10 * 60_000L;
    /** The wide window of spikes/vox/FINDINGS.md (b): a daily bucket filed under a future date answers only to it. */
    private static final long WIDE_END_MS = 2 * 366 * 24 * 3600_000L, SLACK_MS = 60_000L;

    private final Random random = new Random();
    private SaverView view;
    private VoxConfig config;
    private String previous;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        config = VoxConfig.load(this);
        view = new SaverView(this, getResources().getFont(R.font.plex_mono_light), config);
        setContentView(view);
    }

    /** Asked here, not on attach: by now the dream's own resume is in the event log, so it can be stepped over. */
    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        String label = label(lastApp());
        view.start(() -> nextLine(label));
    }

    @Override
    public void onDreamingStopped() {
        view.stop();
        super.onDreamingStopped();
    }

    /** A new line each loop, never the same one twice running when the set has another. */
    private String nextLine(String app) {
        String line = null;
        for (int tries = 0; tries < 6 && (line == null || line.equals(previous)); tries++) {
            line = app != null ? Greeting.appLine(app, config.appLines, random)
                    : Greeting.pick(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), config.greetings, random);
        }
        previous = line;
        return line;
    }

    private String label(String pkg) {
        if (pkg == null) return null;
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))).toUpperCase(Locale.ROOT);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "screensaver: no label for " + pkg + "; using the time-of-day lines", e);
            return null;
        }
    }

    /** The package in front when the dream started, or null for Home or not known. */
    private String lastApp() {
        long now = System.currentTimeMillis();
        try {
            UsageStatsManager usm = getSystemService(UsageStatsManager.class);
            if (usm != null) {
                String window = "narrow";
                List<LastApp.Event> events = events(usm.queryEvents(now - LOOKBACK_MS, now), Long.MAX_VALUE);
                if (events.isEmpty()) {
                    window = "wide";
                    events = events(usm.queryEvents(now - LOOKBACK_MS, now + WIDE_END_MS), now + SLACK_MS);
                }
                if (!events.isEmpty()) {
                    Log.i(TAG, "usage: " + window + " " + events.size() + " events; last resumed " + lastResumed(events));
                    return LastApp.from(events, getPackageName(), MainActivity.class.getName());
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "usage stats not allowed (appops GET_USAGE_STATS); using Home's own record", e);
        }
        SharedPreferences prefs = getSharedPreferences(LastApp.PREFS, MODE_PRIVATE);
        String app = prefs.getBoolean(LastApp.KEY_HOME_FRONT, true) ? null : prefs.getString(LastApp.KEY_APP, null);
        Log.i(TAG, "usage: none, fallback " + app);
        return app;
    }

    @SuppressWarnings("deprecation")   // MOVE_TO_FOREGROUND/BACKGROUND: ACTIVITY_RESUMED/PAUSED's values, API 26 names
    private static List<LastApp.Event> events(UsageEvents usage, long notAfter) {
        List<LastApp.Event> out = new ArrayList<>();
        if (usage == null) return out;
        UsageEvents.Event e = new UsageEvents.Event();
        while (usage.hasNextEvent()) {
            usage.getNextEvent(e);
            int type = e.getEventType();
            boolean resumed = type == UsageEvents.Event.MOVE_TO_FOREGROUND;
            if (!resumed && type != UsageEvents.Event.MOVE_TO_BACKGROUND) continue;
            if (e.getTimeStamp() > notAfter) continue;
            out.add(new LastApp.Event(e.getPackageName(), e.getClassName(), resumed, e.getTimeStamp()));
        }
        out.sort(Comparator.comparingLong(ev -> ev.time));
        return out;
    }

    /** The last four resumes, newest first: shows on the device which class the dream's own resume carries. */
    private static String lastResumed(List<LastApp.Event> events) {
        StringBuilder b = new StringBuilder();
        int shown = 0;
        for (int i = events.size() - 1; i >= 0 && shown < 4; i--) {
            LastApp.Event e = events.get(i);
            if (!e.resumed) continue;
            if (shown++ > 0) b.append(", ");
            b.append(e.pkg).append('/').append(e.cls);
        }
        return b.toString();
    }
}
