package tv.voxclean.launcher;

/**
 * The bundled voxel image for a tile, by package (res/drawable-nodpi/vox_*.png, 400 px, background exactly
 * #F4F4F4). One line per app; 0 means the tile shows the app's own icon.
 */
final class Voxel {
    private Voxel() {
    }

    static int forTile(Tiles.Tile tile) {
        if (tile.isLive()) return 0;
        switch (tile.pkg) {
            case "com.netflix.ninja": return R.drawable.vox_netflix;
            default: return 0;
        }
    }
}
