package tv.voxclean.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ConfigTest {
    private static void assertDefaults(VoxConfig c) {
        assertEquals(0.95f, c.focusScale, 0f);
        assertEquals(VoxConfig.DEFAULT_LIVE_URI, c.liveUri);
        assertEquals(VoxConfig.DEFAULT_HIDDEN, c.hiddenApps);
        assertTrue(c.greetings.isEmpty());
        assertTrue(c.appLines.isEmpty());
        assertEquals(0.8f, c.saverEntrySpeed, 0f);
        assertEquals(0.8f, c.saverTrackingEm, 0f);
        assertEquals(2000, c.introDelayMs);
        assertEquals(2, c.introCursorBlinks);
    }

    @Test
    public void introDelayIsMillisecondsUpToFiveSeconds() {
        assertEquals(0, VoxConfig.parse("{\"intro_delay_ms\": 0}").introDelayMs);
        assertEquals(2500, VoxConfig.parse("{\"intro_delay_ms\": 2500}").introDelayMs);
        assertEquals(5000, VoxConfig.parse("{\"intro_delay_ms\": 5000}").introDelayMs);
        assertEquals(2000, VoxConfig.parse("{\"intro_delay_ms\": 5001}").introDelayMs);
        assertEquals(2000, VoxConfig.parse("{\"intro_delay_ms\": -1}").introDelayMs);
        assertEquals(2000, VoxConfig.parse("{\"intro_delay_ms\": \"slow\"}").introDelayMs);
    }

    @Test
    public void introCursorBlinksFromNoneToSix() {
        assertEquals(0, VoxConfig.parse("{\"intro_cursor_blinks\": 0}").introCursorBlinks);
        assertEquals(6, VoxConfig.parse("{\"intro_cursor_blinks\": 6}").introCursorBlinks);
        assertEquals(2, VoxConfig.parse("{\"intro_cursor_blinks\": 7}").introCursorBlinks);
        assertEquals(2, VoxConfig.parse("{\"intro_cursor_blinks\": -1}").introCursorBlinks);
    }

    @Test
    public void noFileIsTheDefaults() {
        VoxConfig c = VoxConfig.parse(null);
        assertDefaults(c);
        assertFalse(c.fileIgnored);
    }

    @Test
    public void everyKey() {
        VoxConfig c = VoxConfig.parse("{\"focus_scale\": 1.0, \"live_uri\": \"https://tv.youtube.com/library\","
                + " \"hidden_apps\": [\"com.tubitv\"], \"app_lines\": [\"BACK TO {APP}?\"],"
                + " \"saver_entry_speed\": 1.5, \"saver_tracking_em\": 0.5,"
                + " \"greetings\": {\"late\": [\"Bed?\"], \"evening\": [\"Hi.\", \"Yo.\"]}}");
        assertFalse(c.fileIgnored);
        assertEquals(1.0f, c.focusScale, 0f);
        assertEquals("https://tv.youtube.com/library", c.liveUri);
        assertTrue(c.hiddenApps.containsAll(VoxConfig.DEFAULT_HIDDEN));
        assertTrue(c.hiddenApps.contains("com.tubitv"));
        assertEquals(Arrays.asList("BACK TO {APP}?"), c.appLines);
        assertEquals(1.5f, c.saverEntrySpeed, 0f);
        assertEquals(0.5f, c.saverTrackingEm, 0f);
        assertEquals(Arrays.asList("Bed?"), c.greetings.get(Greeting.LATE));
        assertEquals(Arrays.asList("Hi.", "Yo."), c.greetings.get(Greeting.EVENING));
        assertFalse(c.greetings.containsKey(Greeting.MORNING));
    }

    @Test
    public void aBadKeyIsIgnoredAndTheRestApply() {
        VoxConfig c = VoxConfig.parse("{\"focus_scale\": \"big\", \"live_uri\": \"not a link\","
                + " \"hidden_apps\": [\"com.tubitv\", 3], \"app_lines\": \"x\", \"saver_entry_speed\": -1,"
                + " \"greetings\": {\"morning\": [], \"evening\": [\"Hi.\"], \"noon\": [\"x\"]},"
                + " \"unknown_key\": true, \"saver_tracking_em\": 0.6}");
        assertFalse(c.fileIgnored);
        assertEquals(0.95f, c.focusScale, 0f);
        assertEquals(VoxConfig.DEFAULT_LIVE_URI, c.liveUri);
        assertEquals(VoxConfig.DEFAULT_HIDDEN, c.hiddenApps);
        assertTrue(c.appLines.isEmpty());
        assertEquals(0.8f, c.saverEntrySpeed, 0f);
        assertEquals(0.6f, c.saverTrackingEm, 0f);
        assertEquals(1, c.greetings.size());
        assertEquals(Arrays.asList("Hi."), c.greetings.get(Greeting.EVENING));
    }

    @Test
    public void focusScaleMustGrowTheTile() {
        assertEquals(0.95f, VoxConfig.parse("{\"focus_scale\": 0.72}").focusScale, 0f);
        assertEquals(0.73f, VoxConfig.parse("{\"focus_scale\": 0.73}").focusScale, 0f);
        assertEquals(1.0f, VoxConfig.parse("{\"focus_scale\": 1.0}").focusScale, 0f);
        assertEquals(0.95f, VoxConfig.parse("{\"focus_scale\": 1.01}").focusScale, 0f);
        assertEquals(0.95f, VoxConfig.parse("{\"focus_scale\": 3}").focusScale, 0f);
    }

    @Test
    public void aFileThatDoesNotParseIsIgnoredAsAWhole() {
        for (String bad : new String[]{"", "{", "{\"focus_scale\": 1.0", "{\"focus_scale\": 1.0,}", "[1, 2]",
                "\"text\"", "{\"focus_scale\": 1.0} trailing", "{focus_scale: 1.0}", "{\"a\": tru}"}) {
            VoxConfig c = VoxConfig.parse(bad);
            assertTrue(bad, c.fileIgnored);
            assertDefaults(c);
        }
    }

    @Test
    public void jsonValues() {
        Map<?, ?> m = (Map<?, ?>) Json.parse(" {\"s\": \"a\\\"b\\u00e9\\n\", \"n\": -1.5e2, \"t\": true, \"f\": false,"
                + " \"z\": null, \"l\": [1, {\"x\": []}]} ");
        assertEquals("a\"bé\n", m.get("s"));
        assertEquals(-150.0, (Double) m.get("n"), 0);
        assertEquals(Boolean.TRUE, m.get("t"));
        assertEquals(Boolean.FALSE, m.get("f"));
        assertTrue(m.containsKey("z"));
        assertEquals(2, ((List<?>) m.get("l")).size());
        try {
            Json.parse("{\"s\": \"unterminated}");
            fail();
        } catch (IllegalArgumentException expected) {
            // a bad file
        }
    }
}
