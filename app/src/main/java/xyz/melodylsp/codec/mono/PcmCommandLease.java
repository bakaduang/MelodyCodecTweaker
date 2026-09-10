package xyz.melodylsp.codec.mono;

/** Monotonic, short-lived control messages cannot revive an old Bluetooth owner. */
final class PcmCommandLease {
    private long server, revision;

    boolean accept(long nextServer, long nextRevision, long sentAt, long expiresAt, long now) {
        if (nextServer <= 0 || nextRevision <= 0 || sentAt <= 0 || sentAt > now
                || expiresAt <= now || expiresAt < sentAt
                || expiresAt - sentAt > PcmMonoIpc.LEASE_MS
                || nextServer < server
                || (nextServer == server && nextRevision <= revision)) return false;
        server = nextServer;
        revision = nextRevision;
        return true;
    }
}
