package tv.voxclean.launcher;

import java.util.List;

/**
 * Which app was in front when the screensaver started, from UsageStats events (spikes/vox/FINDINGS.md, b): the
 * package of the last ACTIVITY_RESUMED before the dream's own one. Pure: the dream turns UsageEvents into rows.
 */
final class LastApp {
    /**
     * The fallback when UsageStats has nothing: Home's own record in SharedPreferences PREFS, the package it last
     * opened (KEY_APP) and whether Home itself is in front (KEY_HOME_FRONT, true from onResume to onPause).
     */
    static final String PREFS = "vox", KEY_APP = "last_app", KEY_HOME_FRONT = "home_front";

    static final class Event {
        final String pkg, cls;
        final boolean resumed;
        final long time;

        Event(String pkg, String cls, boolean resumed, long time) {
            this.pkg = pkg;
            this.cls = cls;
            this.resumed = resumed;
            this.time = time;
        }
    }

    private LastApp() {
    }

    /**
     * @param events oldest first
     * @param ownPkg Vox's package: the dream runs in it, whatever activity class the system gives it
     * @param homeClass Vox's Home activity: the one own activity that is not the dream
     * @return the package, or null when Home (or nothing known) was in front
     */
    static String from(List<Event> events, String ownPkg, String homeClass) {
        boolean dreamSkipped = false;
        for (int i = events.size() - 1; i >= 0; i--) {
            Event e = events.get(i);
            if (!e.resumed) continue;
            boolean own = ownPkg.equals(e.pkg);
            if (own && !homeClass.equals(e.cls) && !dreamSkipped) {
                dreamSkipped = true;
                continue;
            }
            return own ? null : e.pkg;
        }
        return null;
    }
}
