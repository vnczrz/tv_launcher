package tv.voxclean.launcher;

/** The Wi-Fi chip's text: the network name when Android tells us, else what kind of connection there is. */
final class NetworkLabel {
    static final String OFFLINE = "OFFLINE", ETHERNET = "ETHERNET", WIFI = "WI-FI";

    private NetworkLabel() {
    }

    /**
     * @param ssid WifiInfo.getSSID(): quoted, or "<unknown ssid>" without location permission or before Wi-Fi is up
     */
    static String of(boolean online, boolean ethernet, boolean wifi, String ssid) {
        if (!online) return OFFLINE;
        if (ethernet) return ETHERNET;
        if (!wifi || ssid == null) return WIFI;
        String name = ssid.trim();
        if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) name = name.substring(1, name.length() - 1);
        if (name.isEmpty() || name.equals("<unknown ssid>") || name.equals("0x")) return WIFI;
        return name;
    }
}
