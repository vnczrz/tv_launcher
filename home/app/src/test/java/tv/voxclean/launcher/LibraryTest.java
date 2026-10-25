package tv.voxclean.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Set;

import org.junit.Test;

/** The Library's saved choices (HiddenApps) over the starting hidden set. */
public class LibraryTest {
    @Test
    public void linesRoundTrip() {
        HiddenApps h = HiddenApps.parse(Arrays.asList("+com.gathin.tvhome", "-com.netflix.ninja"));
        assertEquals(Arrays.asList("+com.gathin.tvhome", "-com.netflix.ninja"), h.lines());
        assertEquals(h.lines(), HiddenApps.parse(h.lines()).lines());
    }

    @Test
    public void badLinesAreIgnored() {
        HiddenApps h = HiddenApps.parse(Arrays.asList("", "  ", "+", "com.tubitv", "*com.x", " -com.tubitv "));
        assertEquals(Arrays.asList("-com.tubitv"), h.lines());
    }

    @Test
    public void choicesGoOnTopOfTheStartingSet() {
        Set<String> hidden = HiddenApps.parse(Arrays.asList("+com.gathin.tvhome", "-com.netflix.ninja"))
                .apply(VoxConfig.DEFAULT_HIDDEN);
        assertFalse(hidden.contains("com.gathin.tvhome"));
        assertTrue(hidden.contains("com.netflix.ninja"));
        // Untouched packages follow the starting set.
        assertTrue(hidden.contains("com.android.settings"));
        assertTrue(hidden.contains("com.android.tv.settings"));
        assertFalse(hidden.contains("com.tubitv"));
    }

    @Test
    public void theLastChoiceForAPackageWins() {
        HiddenApps h = HiddenApps.parse(Arrays.asList("-com.tubitv"));
        h.set("com.tubitv", false);
        assertEquals(Arrays.asList("+com.tubitv"), h.lines());
        assertFalse(h.apply(VoxConfig.DEFAULT_HIDDEN).contains("com.tubitv"));
    }
}
