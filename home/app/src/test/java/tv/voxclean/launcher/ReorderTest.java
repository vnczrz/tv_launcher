package tv.voxclean.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class ReorderTest {
    private static Tiles.Tile app(String pkg) {
        return new Tiles.Tile(pkg, pkg + ".Main", pkg);
    }

    @Test
    public void arrowsWithinAPage() {
        assertEquals(1, Reorder.target(0, Grid.Key.RIGHT, 17));
        assertEquals(4, Reorder.target(5, Grid.Key.LEFT, 17));
        assertEquals(5, Reorder.target(1, Grid.Key.DOWN, 17));
        assertEquals(1, Reorder.target(5, Grid.Key.UP, 17));
    }

    @Test
    public void rightPastTheLastColumnGoesToTheSameRowOfTheNextPage() {
        assertEquals(8, Reorder.target(3, Grid.Key.RIGHT, 17));
        assertEquals(12, Reorder.target(7, Grid.Key.RIGHT, 22));
    }

    @Test
    public void leftFromTheFirstColumnGoesToTheLastColumnOfThePreviousPage() {
        assertEquals(3, Reorder.target(8, Grid.Key.LEFT, 17));
        assertEquals(7, Reorder.target(12, Grid.Key.LEFT, 17));
        assertEquals(-1, Reorder.target(0, Grid.Key.LEFT, 17));
        assertEquals(-1, Reorder.target(4, Grid.Key.LEFT, 17));
    }

    @Test
    public void upAndDownStayOnThePage() {
        assertEquals(-1, Reorder.target(2, Grid.Key.UP, 17));
        assertEquals(-1, Reorder.target(6, Grid.Key.DOWN, 17));
        // 10 tiles: page 2 has 0..1 in its top row only, so nothing below.
        assertEquals(-1, Reorder.target(9, Grid.Key.DOWN, 10));
    }

    @Test
    public void rightOntoAShortLastPageLandsOnTheLastTile() {
        // 17 tiles: page 3 holds only position 16; the bottom row target (20) is past the end.
        assertEquals(16, Reorder.target(15, Grid.Key.RIGHT, 17));
        // Same page, a row that does not exist yet: refused.
        assertEquals(-1, Reorder.target(16, Grid.Key.RIGHT, 17));
        // Last page full of one row: Right at its end has no next page.
        assertEquals(-1, Reorder.target(7, Grid.Key.RIGHT, 8));
    }

    @Test
    public void rightAtTheEndOfTheLastPageStays() {
        // 22 tiles: the last page holds 16-21; its top-right tile (19) has no next page to go to.
        assertEquals(-1, Reorder.target(19, Grid.Key.RIGHT, 22));
        assertEquals(-1, Reorder.target(21, Grid.Key.RIGHT, 22));
        assertEquals(-1, Reorder.target(7, Grid.Key.RIGHT, 8));
    }

    @Test
    public void targetNeverEqualsPos() {
        for (int count = 1; count <= 24; count++) {
            for (int pos = 0; pos < count; pos++) {
                for (Grid.Key key : Grid.Key.values()) {
                    int to = Reorder.target(pos, key, count);
                    assertNotEquals(pos, to);
                    if (to != -1) assertEquals(true, to >= 0 && to < count);
                }
            }
        }
    }

    @Test
    public void moveReinsertsBothWays() {
        List<String> list = new ArrayList<>(Arrays.asList("a", "b", "c", "d"));
        Reorder.move(list, 0, 2);
        assertEquals(Arrays.asList("b", "c", "a", "d"), list);
        Reorder.move(list, 3, 0);
        assertEquals(Arrays.asList("d", "b", "c", "a"), list);
    }

    @Test
    public void applyEmptySavedIsUnchanged() {
        List<Tiles.Tile> arranged = Arrays.asList(app("a"), app("b"));
        assertSame(arranged, Reorder.apply(arranged, Collections.emptyList()));
    }

    @Test
    public void applySavedFirstThenNewAtTheEnd() {
        Tiles.Tile live = Tiles.Tile.live();
        List<Tiles.Tile> arranged = Arrays.asList(live, app("a"), app("b"), app("c"), app("new"));
        List<String> saved = Arrays.asList(app("c").key, "gone/.Main", Tiles.LIVE_KEY, app("a").key);
        List<String> keys = Reorder.keys(Reorder.apply(arranged, saved));
        assertEquals(Arrays.asList(app("c").key, Tiles.LIVE_KEY, app("a").key, app("b").key, app("new").key), keys);
    }
}
