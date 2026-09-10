package xyz.melodylsp.codec.mono;

/** A hook installation or an old generation's counter cannot prove current mixing. */
final class PcmEvidence {
    static boolean valid(long[] stats, long now) {
        if (stats == null || stats.length != 9 || (stats[0] != 0 && stats[0] != 1)) return false;
        for (long value : stats) if (value < 0) return false;
        return stats[3] <= now;
    }

    static boolean mixing(long[] stats, long expectedGeneration, long receivedAt, long now) {
        return valid(stats, now) && stats[0] == 1 && expectedGeneration > 0
                && stats[1] == expectedGeneration && stats[2] > 0 && stats[3] > 0
                && receivedAt <= now && now - receivedAt <= PcmMonoIpc.LEASE_MS
                && now - stats[3] <= PcmMonoIpc.LEASE_MS;
    }

    private PcmEvidence() {}
}
