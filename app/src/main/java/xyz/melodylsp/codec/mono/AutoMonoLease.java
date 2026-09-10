package xyz.melodylsp.codec.mono;

/** Rejects delayed traffic from a previous host process, including delayed disconnects. */
public final class AutoMonoLease {
    private long producer, sequence;

    public boolean accept(long producer, long sequence, long sentAt, long now) {
        if (producer <= 0 || sequence <= 0 || !AutoMonoIpc.recent(sentAt, now)) return false;
        if (producer < this.producer || (producer == this.producer && sequence <= this.sequence)) return false;
        this.producer = producer;
        this.sequence = sequence;
        return true;
    }

    public long producer() { return producer; }

    public void reset() { producer = 0L; sequence = 0L; }

    public static boolean validReport(boolean connected, long session, long connectedAt,
            long reportedAt, int known, int ear, int box, long sentAt) {
        if (!AutoMonoIpc.validMasks(known, ear, box)) return false;
        if (!connected) return session == 0 && known == 0 && ear == 0 && box == 0;
        if (session <= 0 || connectedAt <= 0 || connectedAt > sentAt) return false;
        return known == 0 ? reportedAt >= 0 && reportedAt <= sentAt
                : reportedAt >= connectedAt && reportedAt <= sentAt;
    }
}
