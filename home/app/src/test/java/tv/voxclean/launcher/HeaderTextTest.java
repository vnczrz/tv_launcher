package tv.voxclean.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

/** Greeting, clock and network label: the header's text. */
public class HeaderTextTest {
    @Test
    public void slotsFollowTheSpecHours() {
        assertEquals(Greeting.LATE, Greeting.slot(4));
        assertEquals(Greeting.MORNING, Greeting.slot(5));
        assertEquals(Greeting.MORNING, Greeting.slot(11));
        assertEquals(Greeting.AFTERNOON, Greeting.slot(12));
        assertEquals(Greeting.AFTERNOON, Greeting.slot(16));
        assertEquals(Greeting.EVENING, Greeting.slot(17));
        assertEquals(Greeting.EVENING, Greeting.slot(21));
        assertEquals(Greeting.LATE, Greeting.slot(22));
        assertEquals(Greeting.LATE, Greeting.slot(0));
    }

    @Test
    public void pickTakesEveryLineOfTheSlotInCaps() {
        Set<String> seen = new HashSet<>();
        Random random = new Random(1);
        for (int i = 0; i < 200; i++) seen.add(Greeting.pick(23, Collections.emptyMap(), random));
        assertEquals(new HashSet<>(Arrays.asList("LATE ONE TONIGHT.", "STILL UP.", "ONE MORE EPISODE.", "QUIET HOURS.")),
                seen);
        assertEquals("WHAT'S ON?", Greeting.pick(19, Collections.emptyMap(), new Random() {
            @Override
            public int nextInt(int bound) {
                return 1;
            }
        }));
    }

    @Test
    public void configLinesReplaceOnlyTheirSlot() {
        Map<String, List<String>> overrides = Collections.singletonMap(Greeting.MORNING, Arrays.asList("Hello there."));
        assertEquals("HELLO THERE.", Greeting.pick(8, overrides, new Random(3)));
        assertTrue(Greeting.builtIn(Greeting.EVENING).contains("Evening."));
        assertTrue(Arrays.asList("EVENING.", "WHAT'S ON?", "DONE FOR THE DAY?")
                .contains(Greeting.pick(18, overrides, new Random(3))));
    }

    @Test
    public void clockIsDayDateMonthAnd24HourTime() {
        ZoneId zone = ZoneId.of("Europe/London");
        long t = ZonedDateTime.of(2026, 10, 24, 21, 32, 5, 0, zone).toInstant().toEpochMilli();
        assertEquals("SAT 24 OCT 21:32", ClockText.format(t, zone));
        long morning = ZonedDateTime.of(2026, 11, 2, 7, 5, 0, 0, zone).toInstant().toEpochMilli();
        assertEquals("MON 2 NOV 07:05", ClockText.format(morning, zone));
        // Same instant, another zone.
        assertEquals("SAT 24 OCT 13:32", ClockText.format(t, ZoneId.of("America/Los_Angeles")));
    }

    @Test
    public void clockWaitsForRealTime() {
        assertFalse(ClockText.valid(0));
        assertFalse(ClockText.valid(ClockText.VALID_AFTER_MS - 1));
        assertTrue(ClockText.valid(ClockText.VALID_AFTER_MS));
    }

    @Test
    public void networkLabel() {
        assertEquals("Living Room", NetworkLabel.of(true, false, true, "\"Living Room\""));
        assertEquals(NetworkLabel.WIFI, NetworkLabel.of(true, false, true, "<unknown ssid>"));
        assertEquals(NetworkLabel.WIFI, NetworkLabel.of(true, false, true, null));
        assertEquals(NetworkLabel.WIFI, NetworkLabel.of(true, false, true, "\"\""));
        assertEquals(NetworkLabel.ETHERNET, NetworkLabel.of(true, true, false, null));
        assertEquals(NetworkLabel.ETHERNET, NetworkLabel.of(true, true, true, "\"Living Room\""));
        assertEquals(NetworkLabel.OFFLINE, NetworkLabel.of(false, false, false, null));
    }
}
