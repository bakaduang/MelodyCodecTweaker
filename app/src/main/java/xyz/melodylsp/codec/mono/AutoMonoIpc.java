package xyz.melodylsp.codec.mono;

import java.util.Locale;
import java.util.regex.Pattern;

/** Narrow, authenticated protocol between Melody and the Bluetooth process. */
public final class AutoMonoIpc {
    public static final String HOST = "com.oplus.melody";
    public static final String BLUETOOTH = "com.android.bluetooth";
    public static final String REQUEST = "xyz.melodylsp.codec.action.AUTO_MONO_REQUEST";
    public static final String STATUS = "xyz.melodylsp.codec.action.AUTO_MONO_STATUS";
    // v2: mode=mono requires current player PCM frames, never an AudioSystem flag alone.
    public static final int VERSION = 2;
    public static final long HEARTBEAT_MS = 4_000L;
    public static final long LEASE_MS = 15_000L;
    public static final long DEBOUNCE_MS = 650L;
    private static final Pattern ADDRESS = Pattern.compile("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}");

    private AutoMonoIpc() {}

    public static String normalizeMac(String value) {
        if (value == null) return null;
        String mac = value.trim().toUpperCase(Locale.ROOT);
        return ADDRESS.matcher(mac).matches() ? mac : null;
    }

    public static String redact(String value) {
        String mac = normalizeMac(value);
        return mac == null ? "?" : "**:**:**:**:" + mac.substring(12);
    }

    public static boolean validMasks(int known, int ear, int box) {
        return (known & ~3) == 0 && (ear & ~known) == 0 && (box & ~known) == 0
                && (ear & box) == 0;
    }

    public static boolean recent(long sentAt, long now) {
        return sentAt > 0 && sentAt <= now && now - sentAt <= LEASE_MS;
    }
}
