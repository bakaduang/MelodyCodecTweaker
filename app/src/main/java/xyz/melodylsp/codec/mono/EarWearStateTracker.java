package xyz.melodylsp.codec.mono;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Tracks only ear states actually reported during the current connection.
 *
 * <p>All timestamps must use the same monotonic clock. Stable wear states do not expire: the
 * connection observer must call {@link #endSession(String)} when their session ends. Public
 * operations are synchronized and returned snapshots are immutable.
 */
public final class EarWearStateTracker {

    public static final int LEFT = 1;
    public static final int RIGHT = 2;
    public static final int BOTH = LEFT | RIGHT;

    public enum State {
        DISCONNECTED, UNKNOWN, NONE, LEFT_ONLY, RIGHT_ONLY, BOTH
    }

    private static final Snapshot INVALID_ADDRESS = Snapshot.disconnected("");

    private final Map<String, Snapshot> devices = new HashMap<>();

    /** Starts a connection, retaining reports if this connection was already observed. */
    public synchronized void beginSession(String mac, long sessionId, long connectedAtMs) {
        String address = normalizeMac(mac);
        if (address == null || sessionId <= 0L || connectedAtMs < 0L) return;
        Snapshot previous = devices.get(address);
        if (previous != null && previous.connected && previous.sessionId == sessionId) return;
        devices.put(address, new Snapshot(address, sessionId, connectedAtMs, 0L,
                0, 0, 0, true));
    }

    /** Clears all reported state so it cannot be inherited by the next connection. */
    public synchronized void endSession(String mac) {
        String address = normalizeMac(mac);
        if (address == null) return;
        Snapshot previous = devices.get(address);
        if (previous == null || previous.connected) {
            devices.put(address, Snapshot.disconnected(address));
        }
    }

    /**
     * Applies a partial report for a connection explicitly established by {@link #beginSession}.
     *
     * <p>Old connections, out-of-order timestamps and empty reports are ignored. A malformed mask
     * invalidates both ears for the current connection; contradictory in-ear/in-box values
     * invalidate only the affected ear. Neither can leave an unsafe single-ear decision active.
     */
    public synchronized Snapshot update(String mac, long sessionId, long receivedAtMs,
            int reportedMask, int inEarMask, int inBoxMask) {
        Snapshot previous = snapshot(mac);
        if (!previous.connected || sessionId <= 0L || sessionId != previous.sessionId
                || receivedAtMs < previous.connectedAtMs
                || receivedAtMs < previous.receivedAtMs) {
            return previous;
        }

        if (!validMasks(reportedMask, inEarMask, inBoxMask)) {
            Snapshot invalidated = new Snapshot(previous.mac, sessionId, previous.connectedAtMs,
                    receivedAtMs, 0, 0, 0, true);
            devices.put(previous.mac, invalidated);
            return invalidated;
        }
        if (reportedMask == 0) return previous;

        int contradictoryMask = inEarMask & inBoxMask;
        int known = (previous.knownMask | reportedMask) & ~contradictoryMask;
        int inEar = (previous.inEarMask & ~reportedMask) | (inEarMask & ~contradictoryMask);
        int inBox = (previous.inBoxMask & ~reportedMask) | (inBoxMask & ~contradictoryMask);
        Snapshot next = new Snapshot(previous.mac, sessionId, previous.connectedAtMs,
                receivedAtMs, known, inEar, inBox, true);
        devices.put(previous.mac, next);
        return next;
    }

    /** Returns a stable non-null disconnected snapshot for an absent or ended connection. */
    public synchronized Snapshot snapshot(String mac) {
        String address = normalizeMac(mac);
        if (address == null) return INVALID_ADDRESS;
        Snapshot current = devices.get(address);
        if (current == null) {
            current = Snapshot.disconnected(address);
            devices.put(address, current);
        }
        return current;
    }

    private static boolean validMasks(int reported, int inEar, int inBox) {
        return (reported & ~BOTH) == 0
                && (inEar & ~reported) == 0
                && (inBox & ~reported) == 0;
    }

    private static String normalizeMac(String mac) {
        if (mac == null) return null;
        String address = mac.trim();
        if (address.length() != 17) return null;
        for (int i = 0; i < address.length(); i++) {
            char value = address.charAt(i);
            if (i % 3 == 2) {
                if (value != ':') return null;
            } else if (!((value >= '0' && value <= '9')
                    || (value >= 'a' && value <= 'f')
                    || (value >= 'A' && value <= 'F'))) {
                return null;
            }
        }
        return address.toUpperCase(Locale.ROOT);
    }

    public static final class Snapshot {
        public final String mac;
        public final long sessionId;
        public final long connectedAtMs;
        public final long receivedAtMs;
        public final int knownMask;
        public final int inEarMask;
        public final int inBoxMask;
        public final boolean connected;

        private Snapshot(String mac, long sessionId, long connectedAtMs, long receivedAtMs,
                int knownMask, int inEarMask, int inBoxMask, boolean connected) {
            this.mac = mac;
            this.sessionId = sessionId;
            this.connectedAtMs = connectedAtMs;
            this.receivedAtMs = receivedAtMs;
            this.knownMask = knownMask;
            this.inEarMask = inEarMask;
            this.inBoxMask = inBoxMask;
            this.connected = connected;
        }

        private static Snapshot disconnected(String mac) {
            return new Snapshot(mac, 0L, 0L, 0L, 0, 0, 0, false);
        }

        public State state() {
            if (!connected) return State.DISCONNECTED;
            if (!isKnown()) return State.UNKNOWN;
            if (inEarMask == LEFT) return State.LEFT_ONLY;
            if (inEarMask == RIGHT) return State.RIGHT_ONLY;
            if (inEarMask == BOTH) return State.BOTH;
            return State.NONE;
        }

        public boolean isSingleEar() {
            return isKnown() && (inEarMask == LEFT || inEarMask == RIGHT);
        }

        /** True only after both ears have a consistent report in this connection. */
        public boolean isKnown() {
            return connected && knownMask == BOTH;
        }
    }
}
