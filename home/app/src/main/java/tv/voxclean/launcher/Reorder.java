package tv.voxclean.launcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Moving a tile by re-insertion (the tiles in between shift along), and the saved order applied to the tiles. */
final class Reorder {
    private Reorder() {
    }

    /**
     * Where an arrow carries the tile at position pos, or -1 when it stays. Right past the last column goes to the
     * same row of the next page, Left past the first to the last column of the previous page; Up and Down stay on
     * the page. A target past the last tile lands on the last tile when it is on another page (a short last page),
     * and is refused on the same page (a row that does not exist yet).
     */
    static int target(int pos, Grid.Key key, int count) {
        int page = pos / Grid.PER_PAGE, index = pos % Grid.PER_PAGE;
        int col = index % Grid.COLS, rowStart = index - col;
        int to;
        switch (key) {
            case RIGHT:
                to = col < Grid.COLS - 1 ? pos + 1 : (page + 1) * Grid.PER_PAGE + rowStart;
                break;
            case LEFT:
                if (col > 0) to = pos - 1;
                else if (page > 0) to = (page - 1) * Grid.PER_PAGE + rowStart + Grid.COLS - 1;
                else return -1;
                break;
            case DOWN:
                if (index >= Grid.COLS) return -1;
                to = pos + Grid.COLS;
                break;
            case UP:
                if (index < Grid.COLS) return -1;
                to = pos - Grid.COLS;
                break;
            default:
                return -1;
        }
        if (to >= count) {
            if (to / Grid.PER_PAGE == page) return -1;
            to = count - 1;
        }
        return to == pos ? -1 : to;
    }

    /** Takes the item out at from and slots it in at to. */
    static <T> void move(List<T> list, int from, int to) {
        list.add(to, list.remove(from));
    }

    /**
     * @param saved the keys in the order the owner left them (empty: never reordered)
     * @return the saved tiles first in the saved order, then every tile the order does not know, in arranged order
     */
    static List<Tiles.Tile> apply(List<Tiles.Tile> arranged, List<String> saved) {
        if (saved.isEmpty()) return arranged;
        Map<String, Tiles.Tile> byKey = new HashMap<>();
        for (Tiles.Tile t : arranged) byKey.put(t.key, t);
        List<Tiles.Tile> out = new ArrayList<>(arranged.size());
        for (String key : saved) {
            Tiles.Tile t = byKey.remove(key);
            if (t != null) out.add(t);
        }
        for (Tiles.Tile t : arranged) if (byKey.containsKey(t.key)) out.add(t);
        return out;
    }

    static List<String> keys(List<Tiles.Tile> tiles) {
        List<String> out = new ArrayList<>(tiles.size());
        for (Tiles.Tile t : tiles) out.add(t.key);
        return out;
    }
}
