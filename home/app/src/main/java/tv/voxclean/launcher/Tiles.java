package tv.voxclean.launcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Which tiles Home shows and in what order. Pure: the activity turns PackageManager results into Tile rows. */
final class Tiles {
    static final String LIVE_KEY = "live";
    static final String YOUTUBE_TV = "com.google.android.youtube.tvunplugged";

    /**
     * The default order until the owner reorders (lane 4 saves an order): Live, then these, then everything
     * else by name.
     */
    static final List<String> DEFAULT_ORDER = Arrays.asList(
            YOUTUBE_TV, "com.netflix.ninja", "com.google.android.youtube.tv", "com.google.android.youtube.tvmusic",
            "com.amazon.amazonvideo.livingroom", "com.apple.atve.androidtv.appletv", "com.wbd.stream", "com.cbs.ott",
            "com.peacocktv.peacockandroid", "com.espn.score_center", "com.neulion.smartphone.ufc.android",
            "com.plexapp.android", "com.spotify.tv.android", "com.tubitv", "tv.pluto.android");

    static final class Tile {
        /** "live" for the Live tile, else the activity's package/class. */
        final String key, pkg, activity, label;

        Tile(String pkg, String activity, String label) {
            this(pkg + "/" + activity, pkg, activity, label);
        }

        private Tile(String key, String pkg, String activity, String label) {
            this.key = key;
            this.pkg = pkg;
            this.activity = activity;
            this.label = label.toUpperCase(Locale.ROOT);
        }

        static Tile live() {
            return new Tile(LIVE_KEY, YOUTUBE_TV, null, "Live");
        }

        boolean isLive() {
            return LIVE_KEY.equals(key);
        }
    }

    private Tiles() {
    }

    /**
     * @param found the exported leanback activities, already without Vox itself
     * @return Live first when YouTube TV is installed and not hidden, then the visible apps in order; one tile per package and name
     */
    static List<Tile> arrange(List<Tile> found, Set<String> hidden) {
        List<Tile> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean youtubeTv = false;
        for (Tile t : found) {
            if (t.pkg.equals(YOUTUBE_TV)) youtubeTv = true;
            if (hidden.contains(t.pkg) || !seen.add(t.pkg + '\n' + t.label)) continue;
            apps.add(t);
        }
        apps.sort((a, b) -> {
            int ra = rank(a.pkg), rb = rank(b.pkg);
            if (ra != rb) return Integer.compare(ra, rb);
            int byName = a.label.compareTo(b.label);
            return byName != 0 ? byName : a.key.compareTo(b.key);
        });
        // Live is a door into YouTube TV, so it follows that app: gone when YouTube TV is missing or hidden.
        if (youtubeTv && !hidden.contains(YOUTUBE_TV)) apps.add(0, Tile.live());
        return apps;
    }

    private static int rank(String pkg) {
        int i = DEFAULT_ORDER.indexOf(pkg);
        return i >= 0 ? i : DEFAULT_ORDER.size();
    }

    /** Index of the tile with this key, or -1. */
    static int indexOf(List<Tile> tiles, String key) {
        for (int i = 0; i < tiles.size(); i++) if (tiles.get(i).key.equals(key)) return i;
        return -1;
    }
}
