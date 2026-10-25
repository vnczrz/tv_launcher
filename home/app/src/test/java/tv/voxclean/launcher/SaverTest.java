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
    private static final String DREAM = "android.service.dreams.DreamActivity";

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
                resumed("com.netflix.ninja", "Main", 3), paused("com.netflix.ninja", "Main", 100),
                resumed(VOX, DREAM, 120));
        assertEquals("com.netflix.ninja", LastApp.from(events, VOX, HOME));
    }

    @Test
    public void homeInFrontMeansTheTimeLines() {
        // Netflix was open minutes earlier; taking "the last resume that is not ours" would wrongly say Netflix.
        List<LastApp.Event> events = Arrays.asList(resumed("com.netflix.ninja", "Main", 1),
                paused("com.netflix.ninja", "Main", 2), resumed(VOX, HOME, 3), paused(VOX, HOME, 100),
                resumed(VOX, DREAM, 120));
        assertNull(LastApp.from(events, VOX, HOME));
    }

    @Test
    public void noDreamRowYetStillReadsTheLastResume() {
        assertEquals("com.plexapp.android", LastApp.from(Arrays.asList(resumed(VOX, HOME, 1),
                resumed("com.plexapp.android", "Main", 2)), VOX, HOME));
        assertNull(LastApp.from(Arrays.asList(resumed("com.plexapp.android", "Main", 1), resumed(VOX, HOME, 2)),
                VOX, HOME));
    }

    @Test
    public void noEventsIsNotKnown() {
        assertNull(LastApp.from(Collections.emptyList(), VOX, HOME));
        assertNull(LastApp.from(Arrays.asList(resumed(VOX, DREAM, 1)), VOX, HOME));
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
