package tv.voxclean.launcher;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class TilesTest {
    private static Tiles.Tile app(String pkg, String label) {
        return new Tiles.Tile(pkg, pkg + ".Main", label);
    }

    private static List<String> labels(List<Tiles.Tile> tiles) {
        List<String> out = new ArrayList<>();
        for (Tiles.Tile t : tiles) out.add(t.label);
        return out;
    }

    @Test
    public void liveFirstThenTheDefaultOrderThenByName() {
        List<Tiles.Tile> found = Arrays.asList(
                app("com.android.vending", "Google Play Store"), app("com.zz.app", "Another"),
                app("com.netflix.ninja", "Netflix"), app(Tiles.YOUTUBE_TV, "YouTube TV"),
                app("com.android.tv.settings", "Settings"), app("com.gathin.tvhome", "Home"));
        assertEquals(Arrays.asList("LIVE", "YOUTUBE TV", "NETFLIX", "ANOTHER", "GOOGLE PLAY STORE"),
                labels(Tiles.arrange(found, VoxConfig.DEFAULT_HIDDEN)));
    }

    @Test
    public void noLiveTileWithoutYouTubeTv() {
        List<Tiles.Tile> found = Arrays.asList(app("com.netflix.ninja", "Netflix"));
        assertEquals(Arrays.asList("NETFLIX"), labels(Tiles.arrange(found, VoxConfig.DEFAULT_HIDDEN)));
    }

    @Test
    public void noLiveTileWhenYouTubeTvIsHidden() {
        List<Tiles.Tile> found = Arrays.asList(app(Tiles.YOUTUBE_TV, "YouTube TV"), app("com.netflix.ninja", "Netflix"));
        Set<String> hidden = new HashSet<>(VoxConfig.DEFAULT_HIDDEN);
        hidden.add(Tiles.YOUTUBE_TV);
        assertEquals(Arrays.asList("NETFLIX"), labels(Tiles.arrange(found, hidden)));
    }

    @Test
    public void oneTilePerPackageAndName() {
        List<Tiles.Tile> found = Arrays.asList(new Tiles.Tile("com.sony.timer", ".A", "Timers"),
                new Tiles.Tile("com.sony.timer", ".B", "Timers"), new Tiles.Tile("com.sony.timer", ".C", "Clock"));
        List<Tiles.Tile> tiles = Tiles.arrange(found, VoxConfig.DEFAULT_HIDDEN);
        assertEquals(Arrays.asList("CLOCK", "TIMERS"), labels(tiles));
        assertEquals("com.sony.timer/.A", tiles.get(1).key);
        assertEquals(1, Tiles.indexOf(tiles, "com.sony.timer/.A"));
        assertEquals(-1, Tiles.indexOf(tiles, "gone/.X"));
    }
}
