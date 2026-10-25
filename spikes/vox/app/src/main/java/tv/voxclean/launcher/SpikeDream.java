package tv.voxclean.launcher;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.service.dreams.DreamService;
import android.util.Log;
import android.widget.TextView;

import java.util.Comparator;
import java.util.List;

/** Spike screensaver: on start, log which app UsageStats says was in front. */
public class SpikeDream extends DreamService {
    private static final String TAG = "VoxSpike";
    private static final long WINDOW_MS = 10 * 60 * 1000L;
    /** The device's usage buckets can be dated in the future (clock was wrong early after boot), so ask past "now". */
    private static final long TWO_YEARS_MS = 2 * 365 * 24 * 3600 * 1000L;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setFullscreen(true);
        TextView t = new TextView(this);
        t.setText("VOX DREAM");
        setContentView(t);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        UsageStatsManager usm = getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        logEvents(usm, "narrow", now - WINDOW_MS, now, now);
        logEvents(usm, "wide", now - WINDOW_MS, now + TWO_YEARS_MS, now);
        logStats(usm, "narrow", now - 24 * 3600 * 1000L, now, now);
        logStats(usm, "wide", 0, now + TWO_YEARS_MS, now);
    }

    /** Events from [begin, end], reporting only the ones stamped within the last WINDOW_MS. */
    private void logEvents(UsageStatsManager usm, String tag, long begin, long end, long now) {
        UsageEvents events = usm.queryEvents(begin, end);
        UsageEvents.Event e = new UsageEvents.Event();
        String lastFg = null;
        int n = 0;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (now - e.getTimeStamp() > WINDOW_MS) continue;
            n++;
            int type = e.getEventType();
            if (type == UsageEvents.Event.ACTIVITY_RESUMED || type == UsageEvents.Event.ACTIVITY_PAUSED) {
                Log.i(TAG, "DREAM " + tag + " event type=" + type + " pkg=" + e.getPackageName()
                        + " ago_ms=" + (now - e.getTimeStamp()));
            }
            if (type == UsageEvents.Event.ACTIVITY_RESUMED && !getPackageName().equals(e.getPackageName())) {
                lastFg = e.getPackageName();
            }
        }
        Log.i(TAG, "DREAM " + tag + " events=" + n + " lastResumedNotUs=" + lastFg);
    }

    /** Per-package aggregates, newest lastTimeUsed first. */
    private void logStats(UsageStatsManager usm, String tag, long begin, long end, long now) {
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, begin, end);
        stats.sort(Comparator.comparingLong(UsageStats::getLastTimeUsed).reversed());
        Log.i(TAG, "DREAM " + tag + " stats=" + stats.size());
        for (int i = 0; i < Math.min(4, stats.size()); i++) {
            UsageStats s = stats.get(i);
            Log.i(TAG, "DREAM " + tag + " top" + i + " pkg=" + s.getPackageName()
                    + " lastTimeUsed_ago_ms=" + (now - s.getLastTimeUsed()));
        }
    }
}
