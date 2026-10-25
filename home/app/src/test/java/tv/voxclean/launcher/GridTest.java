package tv.voxclean.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GridTest {
    private static Grid tile(int page, int index) {
        return new Grid(page, index, false, Grid.CHIP_SETTINGS);
    }

    private static Grid header(int page, int index, int chip) {
        return new Grid(page, index, true, chip);
    }

    @Test
    public void paging() {
        assertEquals(1, Grid.pages(0));
        assertEquals(1, Grid.pages(8));
        assertEquals(2, Grid.pages(9));
        assertEquals(3, Grid.pages(17));
        assertEquals(8, Grid.onPage(17, 1));
        assertEquals(1, Grid.onPage(17, 2));
        assertEquals(0, Grid.onPage(17, 3));
        assertEquals(10, tile(1, 2).position());
        assertEquals(tile(1, 2), Grid.at(10, Grid.first()));
    }

    @Test
    public void leftAndRightStayInTheRow() {
        assertEquals(tile(0, 1), tile(0, 0).move(Grid.Key.RIGHT, 17));
        assertEquals(tile(0, 4), tile(0, 5).move(Grid.Key.LEFT, 17));
    }

    @Test
    public void rightPastTheLastColumnTurnsThePageInTheSameRow() {
        assertEquals(tile(1, 0), tile(0, 3).move(Grid.Key.RIGHT, 17));
        assertEquals(tile(1, 4), tile(0, 7).move(Grid.Key.RIGHT, 17));
    }

    @Test
    public void rightOntoAShortLastPageClampsToItsLastTile() {
        // 17 tiles: page 3 holds one tile, so the bottom row lands on it.
        assertEquals(tile(2, 0), tile(1, 7).move(Grid.Key.RIGHT, 17));
        // 22 tiles: page 3 holds six, its bottom row two.
        assertEquals(tile(2, 4), tile(1, 7).move(Grid.Key.RIGHT, 22));
        assertEquals(tile(2, 2), new Grid(1, 7, false, 1).move(Grid.Key.RIGHT, 19));
    }

    @Test
    public void leftPastTheFirstColumnGoesBackAPage() {
        assertEquals(tile(0, 3), tile(1, 0).move(Grid.Key.LEFT, 17));
        assertEquals(tile(0, 7), tile(1, 4).move(Grid.Key.LEFT, 17));
    }

    @Test
    public void edgesOfTheFirstAndLastPageStop() {
        assertEquals(tile(0, 0), tile(0, 0).move(Grid.Key.LEFT, 17));
        assertEquals(tile(2, 0), tile(2, 0).move(Grid.Key.RIGHT, 17));
        // A short row ends where its tiles end.
        assertEquals(tile(2, 1), tile(2, 1).move(Grid.Key.RIGHT, 18));
        assertEquals(tile(0, 7), tile(0, 7).move(Grid.Key.RIGHT, 8));
    }

    @Test
    public void downOnlyWhereATileIsBelow() {
        assertEquals(tile(0, 5), tile(0, 1).move(Grid.Key.DOWN, 17));
        assertEquals(tile(0, 5), tile(0, 5).move(Grid.Key.DOWN, 17));
        assertEquals(tile(2, 0), tile(2, 0).move(Grid.Key.DOWN, 17));
    }

    @Test
    public void upFromTheTopRowReachesTheHeaderAndDownComesBack() {
        assertEquals(tile(0, 2), tile(0, 6).move(Grid.Key.UP, 17));
        Grid up = tile(1, 2).move(Grid.Key.UP, 17);
        assertEquals(header(1, 2, Grid.CHIP_SETTINGS), up);
        assertEquals(tile(1, 2), up.move(Grid.Key.DOWN, 17));
        // Moving between header items keeps the tile it came from.
        assertEquals(new Grid(1, 2, false, Grid.CHIP_WIFI), up.move(Grid.Key.LEFT, 17).move(Grid.Key.DOWN, 17));
    }

    @Test
    public void headerEndsDoNotFallThrough() {
        assertEquals(header(0, 0, Grid.CHIP_WIFI), header(0, 0, Grid.CHIP_WIFI).move(Grid.Key.LEFT, 17));
        assertEquals(header(0, 0, Grid.CHIP_SETTINGS), header(0, 0, Grid.CHIP_SETTINGS).move(Grid.Key.RIGHT, 17));
        assertEquals(header(0, 0, Grid.CHIP_SETTINGS), header(0, 0, Grid.CHIP_WIFI).move(Grid.Key.RIGHT, 17));
        assertEquals(header(0, 0, Grid.CHIP_WIFI), header(0, 0, Grid.CHIP_WIFI).move(Grid.Key.UP, 17));
    }

    @Test
    public void noTilesKeepsFocusInTheHeader() {
        Grid g = Grid.first().clamp(0);
        assertTrue(g.header);
        assertEquals(g, g.move(Grid.Key.DOWN, 0));
    }

    @Test
    public void aPageTurnTargetIsClampedWhenAppsGoAwayDuringTheFade() {
        // Right from page 2's bottom row aims at page 3 (17 tiles); then 1 app is removed, then 9 more.
        Grid target = tile(1, 7).move(Grid.Key.RIGHT, 17);
        assertEquals(tile(2, 0), target);
        assertEquals(tile(1, 0), target.clamp(16));
        assertEquals(tile(0, 0), target.clamp(7));
        assertTrue(target.clamp(0).header);
    }

    @Test
    public void clampKeepsFocusInsideFewerTiles() {
        assertEquals(tile(1, 0), tile(2, 0).clamp(9));
        assertEquals(tile(0, 4), tile(0, 7).clamp(5));
        assertEquals(tile(0, 3), tile(0, 3).clamp(17));
    }
}
