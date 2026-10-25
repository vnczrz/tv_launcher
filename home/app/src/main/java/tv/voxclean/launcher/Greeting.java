package tv.voxclean.launcher;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** The header's greeting: one line per Home visit, picked at random from the lines for the time of day. */
final class Greeting {
    static final String MORNING = "morning", AFTERNOON = "afternoon", EVENING = "evening", LATE = "late";
    static final List<String> SLOTS = Arrays.asList(MORNING, AFTERNOON, EVENING, LATE);

    private Greeting() {
    }

    /** Morning 5-12, afternoon 12-17, evening 17-22, late 22-5. */
    static String slot(int hour) {
        if (hour >= 5 && hour < 12) return MORNING;
        if (hour >= 12 && hour < 17) return AFTERNOON;
        if (hour >= 17 && hour < 22) return EVENING;
        return LATE;
    }

    static List<String> builtIn(String slot) {
        switch (slot) {
            case MORNING: return Arrays.asList("Morning.", "Early one.", "Up already.");
            case AFTERNOON: return Arrays.asList("Afternoon.", "Long lunch?", "Middle of the day.", "Quick one.");
            case EVENING: return Arrays.asList("Evening.", "What's on?", "Done for the day?");
            default: return Arrays.asList("Late one tonight.", "Still up.", "One more episode.", "Quiet hours.");
        }
    }

    /** The screensaver's lines when an app was in front; {APP} is the app's name. */
    static final List<String> APP_LINES = Arrays.asList(
            "Back to {APP}?", "More {APP}?", "Still on {APP}?", "{APP}, paused.", "Pick up {APP}?");

    /** @param overrides config.json's app_lines; empty keeps the built-in ones */
    static String appLine(String app, List<String> overrides, Random random) {
        List<String> lines = overrides.isEmpty() ? APP_LINES : overrides;
        return lines.get(random.nextInt(lines.size())).replace("{APP}", app).toUpperCase(Locale.ROOT);
    }

    /** @param overrides config.json's greetings: a slot listed there replaces the built-in lines. */
    static String pick(int hour, Map<String, List<String>> overrides, Random random) {
        String slot = slot(hour);
        List<String> lines = overrides.containsKey(slot) ? overrides.get(slot) : builtIn(slot);
        return lines.get(random.nextInt(lines.size())).toUpperCase(Locale.ROOT);
    }
}
