package xyz.melodylsp.codec.mono;

/** Player processes report evidence only; Bluetooth alone decides whether to mix. */
final class PcmMonoIpc {
    static final String REQUEST = "xyz.melodylsp.codec.MONO_PCM_REPORT";
    static final String CONTROL = "xyz.melodylsp.codec.MONO_PCM_CONTROL";
    static final String[] PLAYERS = {"com.tencent.qqmusic", "com.netease.cloudmusic"};
    static final int VERSION = 1;
    static final long LEASE_MS = 4_000L;

    static boolean isPlayer(String packageName) {
        for (String player : PLAYERS) if (player.equals(packageName)) return true;
        return false;
    }

    private PcmMonoIpc() {}
}
