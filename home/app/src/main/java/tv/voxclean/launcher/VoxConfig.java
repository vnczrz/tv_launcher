package tv.voxclean.launcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * config.json, pushed by home/home-config.sh. Every key is optional; a key with a bad value is ignored on its
 * own, and a file that does not parse is ignored as a whole (upstream's theme rule).
 */
final class VoxConfig {
    static final String FILE_NAME = "config.json";
    static final float REST_SCALE = 0.90f;
    static final String DEFAULT_LIVE_URI = "https://tv.youtube.com/live";
    /** Started hidden; config's hidden_apps adds to these. The Library (lane 4) will let the owner change them. */
    static final Set<String> DEFAULT_HIDDEN = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "com.android.settings", "com.android.tv.settings", "com.gathin.tvhome")));

    float focusScale = 0.954f;
    /** Per time slot (Greeting.MORNING ...); a slot not given keeps the built-in lines. */
    final Map<String, List<String>> greetings = new HashMap<>();
    List<String> appLines = Collections.emptyList();
    String liveUri = DEFAULT_LIVE_URI;
    Set<String> hiddenApps = DEFAULT_HIDDEN;
    float saverEntrySpeed = 0.8f;
    float saverTrackingEm = 0.8f;
    /** The text was given but is not a JSON object, so every value is the default. */
    boolean fileIgnored;

    /** @param text the file's contents, or null for no file */
    static VoxConfig parse(String text) {
        VoxConfig c = new VoxConfig();
        if (text == null) return c;
        Object root;
        try {
            root = Json.parse(text);
        } catch (IllegalArgumentException e) {
            c.fileIgnored = true;
            return c;
        }
        if (!(root instanceof Map)) {
            c.fileIgnored = true;
            return c;
        }
        Map<?, ?> m = (Map<?, ?>) root;
        c.focusScale = number(m.get("focus_scale"), REST_SCALE, 1.2f, c.focusScale, false);
        c.saverEntrySpeed = number(m.get("saver_entry_speed"), 0f, 5f, c.saverEntrySpeed, false);
        c.saverTrackingEm = number(m.get("saver_tracking_em"), 0f, 2f, c.saverTrackingEm, true);
        Object live = m.get("live_uri");
        if (live instanceof String && ((String) live).matches("[A-Za-z][A-Za-z0-9+.-]*://\\S+")) c.liveUri = (String) live;
        List<String> hidden = strings(m.get("hidden_apps"));
        if (hidden != null) {
            Set<String> all = new LinkedHashSet<>(DEFAULT_HIDDEN);
            all.addAll(hidden);
            c.hiddenApps = Collections.unmodifiableSet(all);
        }
        List<String> lines = strings(m.get("app_lines"));
        if (lines != null && !lines.isEmpty()) c.appLines = lines;
        Object g = m.get("greetings");
        if (g instanceof Map) {
            for (String slot : Greeting.SLOTS) {
                List<String> slotLines = strings(((Map<?, ?>) g).get(slot));
                if (slotLines != null && !slotLines.isEmpty()) c.greetings.put(slot, slotLines);
            }
        }
        return c;
    }

    /** The value when it is a number in (min, max] (or [min, max] when minInclusive), else the fallback. */
    private static float number(Object v, float min, float max, float fallback, boolean minInclusive) {
        if (!(v instanceof Double)) return fallback;
        // Compared as floats: 0.9 as a double is above 0.9f, which would let "focus_scale": 0.9 through.
        float f = (float) (double) (Double) v;
        boolean aboveMin = minInclusive ? f >= min : f > min;
        return aboveMin && f <= max ? f : fallback;
    }

    /** A list of non-blank strings, or null when the value is anything else. */
    private static List<String> strings(Object v) {
        if (!(v instanceof List)) return null;
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) v) {
            if (!(o instanceof String) || ((String) o).trim().isEmpty()) return null;
            out.add(((String) o).trim());
        }
        return Collections.unmodifiableList(out);
    }
}
