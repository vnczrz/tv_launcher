package tv.voxclean.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.Test;

/** The screensaver's choices: which app was in front, and its line. */
public class SaverTest {
    private static final String VOX = "tv.voxclean.launcher", HOME = VOX + ".MainActivity";
    private static final String LIBRARY = VOX + ".LibraryActivity", DREAM = LastApp.DREAM_CLASS;
    private static final String NETFLIX = "com.netflix.ninja";

    private static LastApp.Event resumed(String pkg, String cls, long t) {
        return new LastApp.Event(pkg, cls, true, t);
    }

    private static LastApp.Event paused(String pkg, String cls, long t) {
        return new LastApp.Event(pkg, cls, false, t);
    }

    @Test
    public void theAppBeforeTheDreamsOwnResume() {
        // As logged on the TV (FINDINGS b): Netflix paused, then the dream resumed in Vox's package.
        List<LastApp.Event> events = Arrays.asList(resumed(VOX, HOME, 1), paused(VOX, HOME, 2),
                resumed(NETFLIX, "Main", 3), paused(NETFLIX, "Main", 100), resumed(VOX, DREAM, 120));
        assertEquals(NETFLIX, LastApp.from(events, VOX));
    }

    @Test
    public void aLongWatchStillNamesTheApp() {
        // Netflix resumed 40 minutes before the dream: only its one resume is in the log, long before.
        long min = 60_000L;
        List<LastApp.Event> events = Arrays.asList(resumed(VOX, HOME, 0), paused(VOX, HOME, 1),
                resumed(NETFLIX, "Main", 2), paused(NETFLIX, "Main", 40 * min), resumed(VOX, DREAM, 40 * min + 100));
        assertEquals(NETFLIX, LastApp.from(events, VOX));
    }

    @Test
    public void homeInFrontIsVoxNotTheAppBefore() {
        // Netflix was open minutes earlier; taking "the last resume that is not ours" would wrongly say Netflix.
        List<LastApp.Event> events = Arrays.asList(resumed(NETFLIX, "Main", 1), paused(NETFLIX, "Main", 2),
                resumed(VOX, HOME, 3), paused(VOX, HOME, 100), resumed(VOX, DREAM, 120));
        assertEquals(VOX, LastApp.from(events, VOX));
    }

    @Test
    public void theLibraryInFrontIsVoxNotTheAppBefore() {
        // Only the dream's own class is stepped over: a Library resume is Vox in front, never mistaken for the dream.
        List<LastApp.Event> events = Arrays.asList(resumed(NETFLIX, "Main", 1), paused(NETFLIX, "Main", 2),
                resumed(VOX, HOME, 3), paused(VOX, HOME, 4), resumed(VOX, LIBRARY, 5), paused(VOX, LIBRARY, 100),
                resumed(VOX, DREAM, 120));
        assertEquals(VOX, LastApp.from(events, VOX));
    }

    @Test
    public void noDreamRowYetStillReadsTheLastResume() {
        assertEquals("com.plexapp.android", LastApp.from(Arrays.asList(resumed(VOX, HOME, 1),
                resumed("com.plexapp.android", "Main", 2)), VOX));
        assertEquals(VOX, LastApp.from(Arrays.asList(resumed("com.plexapp.android", "Main", 1),
                resumed(VOX, HOME, 2)), VOX));
    }

    @Test
    public void nothingButTheDreamIsNotKnownSoTheFallbackDecides() {
        assertNull(LastApp.from(Collections.emptyList(), VOX));
        assertNull(LastApp.from(Arrays.asList(resumed(VOX, DREAM, 1)), VOX));
        assertNull(LastApp.from(Arrays.asList(paused(NETFLIX, "Main", 1), resumed(VOX, DREAM, 2)), VOX));
    }

    @Test
    public void bothWindowsMergeWithoutDuplicatesOrFutureRows() {
        List<LastApp.Event> narrow = Arrays.asList(resumed(NETFLIX, "Main", 10), resumed(VOX, DREAM, 30));
        List<LastApp.Event> wide = Arrays.asList(resumed(VOX, HOME, 5), resumed(NETFLIX, "Main", 10),
                resumed(VOX, DREAM, 30), resumed("com.tubitv", "Main", 999));
        List<LastApp.Event> merged = LastApp.merge(narrow, wide, 100);
        assertEquals(Arrays.asList(resumed(VOX, HOME, 5), resumed(NETFLIX, "Main", 10), resumed(VOX, DREAM, 30)),
                merged);
        assertEquals(NETFLIX, LastApp.from(merged, VOX));
        // Same moment, other type or class: both kept.
        assertEquals(2, LastApp.merge(Arrays.asList(resumed(NETFLIX, "A", 1)),
                Arrays.asList(paused(NETFLIX, "A", 1)), 100).size());
    }

    private static Random always(int n) {
        return new Random() {
            @Override
            public int nextInt(int bound) {
                return n;
            }
        };
    }

    @Test
    public void appLinesNameTheAppInCaps() {
        assertEquals("BACK TO NETFLIX?", Greeting.appLine("Netflix", Collections.emptyList(), always(0)));
        assertEquals("NETFLIX, PAUSED.", Greeting.appLine("NETFLIX", Collections.emptyList(), always(3)));
        assertEquals("ONE MORE PLEX", Greeting.appLine("PLEX", Arrays.asList("One more {APP}"), always(0)));
    }
}
