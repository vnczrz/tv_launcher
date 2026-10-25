package tv.voxclean.launcher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Which app was in front when the screensaver started, from UsageStats events (spikes/vox/FINDINGS.md, b): the
 * package of the last ACTIVITY_RESUMED before the dream's own one. Pure: the dream turns UsageEvents into rows.
 */
final class LastApp {
    /**
     * The fallback when UsageStats has nothing: Home's own record in SharedPreferences PREFS. KEY_HOME_FRONT is true
     * while Home is resumed; when Home leaves the screen it turns false, and KEY_APP holds the package only when Home
     * itself opened it (removed otherwise: an app the remote or voice opened is not known without UsageStats).
     */
    static final String PREFS = "vox", KEY_APP = "last_app", KEY_HOME_FRONT = "home_front";
    /**
     * The class the dream's own resume carries, read on the TV (Google TV 14): both logged runs showed
     * "tv.voxclean.launcher/android.service.dreams.DreamActivity" as the newest resume when the dream started.
     */
    static final String DREAM_CLASS = "android.service.dreams.DreamActivity";

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

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Event)) return false;
            Event e = (Event) o;
            return e.resumed == resumed && e.time == time && Objects.equals(e.pkg, pkg) && Objects.equals(e.cls, cls);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pkg, cls, resumed, time);
        }
    }

    private LastApp() {
    }

    /**
     * @param events oldest first
     * @param ownPkg Vox's package: Home, the Library and the dream all run in it
     * @return the app's package; ownPkg when Vox itself (Home or the Library) was in front; null when the events do
     *     not say (no resume other than the dream's), so the caller uses Home's own record
     */
    static String from(List<Event> events, String ownPkg) {
        for (int i = events.size() - 1; i >= 0; i--) {
            Event e = events.get(i);
            if (!e.resumed) continue;
            boolean own = ownPkg.equals(e.pkg);
            if (own && DREAM_CLASS.equals(e.cls)) continue;
            return own ? ownPkg : e.pkg;
        }
        return null;
    }

    /**
     * Both query windows together (a daily bucket filed under a wrong date answers only to the wide one, current
     * events to either): duplicates dropped, anything stamped after notAfter dropped, oldest first.
     */
    static List<Event> merge(List<Event> a, List<Event> b, long notAfter) {
        Set<Event> all = new LinkedHashSet<>(a);
        all.addAll(b);
        List<Event> out = new ArrayList<>();
        for (Event e : all) if (e.time <= notAfter) out.add(e);
        out.sort(Comparator.comparingLong(e -> e.time));
        return out;
    }
}
