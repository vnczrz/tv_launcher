package tv.voxclean.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

public class TypingTest {
    @Test
    public void everyBuiltInLineHasBeats() {
        for (String slot : Greeting.SLOTS) {
            for (String raw : Greeting.builtIn(slot)) {
                String line = raw.toUpperCase(Locale.ROOT);
                for (long seed = 0; seed < 50; seed++) {
                    int[] t = Typing.schedule(line, new Random(seed));
                    assertEquals(line.length(), t.length);
                    assertTrue(line, t[0] >= Typing.LEAD_MS + Typing.KEY_MIN_MS);
                    Set<Integer> gaps = new HashSet<>();
                    for (int i = 0; i < t.length; i++) {
                        int gap = t[i] - (i == 0 ? Typing.LEAD_MS : t[i - 1]);
                        gaps.add(gap);
                        assertTrue(line + " " + i, gap >= Typing.KEY_MIN_MS);
                        if (line.charAt(i) == ' ') assertTrue(line, gap >= Typing.KEY_MIN_MS + Typing.WORD_MIN_MS);
                        if (i + 1 < line.length() && ".?,!".indexOf(line.charAt(i + 1)) >= 0) {
                            assertTrue(line, gap >= Typing.KEY_MIN_MS + Typing.PUNCT_MIN_MS);
                        }
                    }
                    if (t.length > 2) assertTrue(line + " uneven", gaps.size() > 1);
                    int total = Typing.typed(t) + Typing.BLINK_AFTER_MS + Typing.BEAT_MS + Typing.FADE_MS;
                    assertTrue(line + " total " + total, total <= 6500);
                }
            }
        }
    }

    @Test
    public void emptyLineIsTheLeadAlone() {
        assertEquals(Typing.LEAD_MS, Typing.typed(Typing.schedule("", new Random(1))));
    }
}
