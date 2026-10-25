package tv.voxclean.launcher;

/**
 * The bundled voxel image for a tile, by package (res/drawable-nodpi/vox_*.png, 400 px, background exactly the tile
 * colour; made by home/icons/bundle.py). 0 means the tile shows the app's own icon.
 */
final class Voxel {
    private Voxel() {
    }

    static int forTile(Tiles.Tile tile) {
        return tile.isLive() ? R.drawable.vox_live : forPackage(tile.pkg);
    }

    static int forPackage(String pkg) {
        switch (pkg) {
            // Installed on the TV this was built on.
            case Tiles.YOUTUBE_TV: return R.drawable.vox_youtube_tv;
            case "com.netflix.ninja": return R.drawable.vox_netflix;
            case "com.google.android.youtube.tv": return R.drawable.vox_youtube;
            case "com.google.android.youtube.tvmusic": return R.drawable.vox_youtube_music;
            case "com.amazon.amazonvideo.livingroom": return R.drawable.vox_prime_video;
            case "com.apple.atve.androidtv.appletv": return R.drawable.vox_apple_tv;
            case "com.wbd.stream": return R.drawable.vox_hbo_max;
            case "com.cbs.ott": return R.drawable.vox_paramount_plus;
            case "com.peacocktv.peacockandroid": return R.drawable.vox_peacock;
            case "com.espn.score_center": return R.drawable.vox_espn;
            case "com.neulion.smartphone.ufc.android": return R.drawable.vox_ufc;
            case "com.plexapp.android": return R.drawable.vox_plex;
            case "com.spotify.tv.android": return R.drawable.vox_spotify;
            case "com.tubitv": return R.drawable.vox_tubi;
            case "tv.pluto.android": return R.drawable.vox_pluto_tv;
            case "com.android.vending": return R.drawable.vox_play_store;
            // Not installed there: the Android TV package names as published, unconfirmed on a device.
            case "com.disney.disneyplus": return R.drawable.vox_disney_plus;
            case "com.hulu.livingroomplus": return R.drawable.vox_hulu;
            case "com.crunchyroll.crunchyroid": return R.drawable.vox_crunchyroll;
            case "tv.twitch.android.app": return R.drawable.vox_twitch;
            case "com.dazn": return R.drawable.vox_dazn;
            case "com.fubo.firetv.screen": return R.drawable.vox_fubo;
            case "com.sling": return R.drawable.vox_sling;
            case "com.philo.philo.google": return R.drawable.vox_philo;
            case "com.bydeluxe.d3.android.program.starz": return R.drawable.vox_starz;
            case "com.apple.android.music": return R.drawable.vox_apple_music;
            case "com.stremio.one": return R.drawable.vox_stremio;
            default: return 0;
        }
    }
}
