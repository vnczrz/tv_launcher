package tv.voxclean.launcher;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** The header clock: SAT 24 OCT 21:32. Day, date, month, 24-hour time, no comma, English on every locale. */
final class ClockText {
    /** 2025-01-01 UTC, as upstream: earlier means the network time has not arrived yet, so the clock stays hidden. */
    static final long VALID_AFTER_MS = 1_735_689_600_000L;
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH);

    private ClockText() {
    }

    static boolean valid(long millis) {
        return millis >= VALID_AFTER_MS;
    }

    static String format(long millis, ZoneId zone) {
        return FORMAT.format(Instant.ofEpochMilli(millis).atZone(zone)).toUpperCase(Locale.ROOT);
    }
}
