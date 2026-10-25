package tv.voxclean.launcher;

/**
 * Paging and D-pad rules for the 4x2 grid and the header above it. Immutable: move() returns where focus goes.
 * A tile is (page, index on the page); while the header has focus the tile is kept, so Down returns to it.
 */
final class Grid {
    static final int COLS = 4, PER_PAGE = 8;
    static final int CHIP_WIFI = 0, CHIP_SETTINGS = 1, CHIPS = 2;
    enum Key { UP, DOWN, LEFT, RIGHT }

    final int page, index, chip;
    final boolean header;

    Grid(int page, int index, boolean header, int chip) {
        this.page = page;
        this.index = index;
        this.header = header;
        this.chip = chip;
    }

    /** Page 1, first tile: where Home on Home goes. The gear is the header item reached first, as in the mockup. */
    static Grid first() {
        return new Grid(0, 0, false, CHIP_SETTINGS);
    }

    static int pages(int count) {
        return Math.max(1, (count + PER_PAGE - 1) / PER_PAGE);
    }

    /** How many tiles the page holds: 8, fewer on a short last page, 0 past the end. */
    static int onPage(int count, int page) {
        return Math.max(0, Math.min(PER_PAGE, count - page * PER_PAGE));
    }

    /** Position in the whole tile list. */
    int position() {
        return page * PER_PAGE + index;
    }

    static Grid at(int position, Grid like) {
        return new Grid(position / PER_PAGE, position % PER_PAGE, like.header, like.chip);
    }

    /** The same focus kept inside the tiles there are now (after apps were added or removed). */
    Grid clamp(int count) {
        if (count == 0) return new Grid(0, 0, true, chip);
        int p = Math.min(page, pages(count) - 1);
        return new Grid(p, Math.min(index, onPage(count, p) - 1), header, chip);
    }

    Grid move(Key key, int count) {
        if (header || count == 0) {
            switch (key) {
                case LEFT: return new Grid(page, index, true, Math.max(0, chip - 1));
                case RIGHT: return new Grid(page, index, true, Math.min(CHIPS - 1, chip + 1));
                case DOWN: return count == 0 ? this : new Grid(page, index, false, chip);
                default: return this;   // header ends and Up do not fall through
            }
        }
        int col = index % COLS, row = index / COLS, here = onPage(count, page);
        switch (key) {
            case UP:
                return row == 0 ? new Grid(page, index, true, chip) : new Grid(page, index - COLS, false, chip);
            case DOWN:
                return row == 0 && index + COLS < here ? new Grid(page, index + COLS, false, chip) : this;
            case RIGHT:
                if (col < COLS - 1 && index + 1 < here) return new Grid(page, index + 1, false, chip);
                if (page < pages(count) - 1) {
                    // Next page, same row, clamped to its last tile.
                    return new Grid(page + 1, Math.min(row * COLS, onPage(count, page + 1) - 1), false, chip);
                }
                return this;
            case LEFT:
                if (col > 0) return new Grid(page, index - 1, false, chip);
                // Previous page, same row, last column: every page before the last is full.
                if (page > 0) return new Grid(page - 1, row * COLS + COLS - 1, false, chip);
                return this;
            default:
                return this;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Grid)) return false;
        Grid g = (Grid) o;
        return g.page == page && g.index == index && g.header == header && g.chip == chip;
    }

    @Override
    public int hashCode() {
        return ((page * 31 + index) * 31 + chip) * 2 + (header ? 1 : 0);
    }

    @Override
    public String toString() {
        return header ? "header chip " + chip + " (tile " + page + "/" + index + ")" : "tile " + page + "/" + index;
    }
}
