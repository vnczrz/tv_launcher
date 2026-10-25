package tv.voxclean.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Library's choices on top of the starting set (the built-in hidden apps plus config's hidden_apps). Saved as
 * lines, "+pkg" for an app the owner put on Home and "-pkg" for one taken off, so a later config change still
 * applies to every app the owner never touched.
 */
final class HiddenApps {
    final Map<String, Boolean> choices = new LinkedHashMap<>();

    static HiddenApps parse(List<String> lines) {
        HiddenApps h = new HiddenApps();
        for (String line : lines) {
            String s = line.trim();
            if (s.length() < 2 || (s.charAt(0) != '+' && s.charAt(0) != '-')) continue;
            h.choices.put(s.substring(1), s.charAt(0) == '-');
        }
        return h;
    }

    List<String> lines() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : choices.entrySet()) out.add((e.getValue() ? "-" : "+") + e.getKey());
        return out;
    }

    Set<String> apply(Set<String> base) {
        Set<String> hidden = new LinkedHashSet<>(base);
        for (Map.Entry<String, Boolean> e : choices.entrySet()) {
            if (e.getValue()) hidden.add(e.getKey());
            else hidden.remove(e.getKey());
        }
        return Collections.unmodifiableSet(hidden);
    }

    void set(String pkg, boolean hidden) {
        choices.put(pkg, hidden);
    }
}
