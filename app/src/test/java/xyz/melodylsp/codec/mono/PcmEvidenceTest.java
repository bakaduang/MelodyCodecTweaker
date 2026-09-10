package xyz.melodylsp.codec.mono;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PcmEvidenceTest {
    @Test public void readyHooksWithNoProcessedAudioDoNotClaimMono() {
        long[] stats = {1, 20, 0, 0, 1, 1, 0, 0, 0};
        assertFalse(PcmEvidence.mixing(stats, 20, 1_000, 1_010));
    }

    @Test public void previousDeviceOrWearSessionEvidenceDoesNotCarryOver() {
        long[] stats = {1, 20, 960, 1_000, 1, 1, 0, 0, 0};
        assertTrue(PcmEvidence.mixing(stats, 20, 1_010, 1_020));
        assertFalse(PcmEvidence.mixing(stats, 21, 1_010, 1_020));
        assertFalse(PcmEvidence.mixing(stats, 0, 1_010, 1_020));
    }

    @Test public void stalledAudioOrDeadPlayerStopsProvingMono() {
        long[] stats = {1, 20, 960, 1_000, 1, 1, 0, 0, 0};
        assertFalse(PcmEvidence.mixing(stats, 20, 5_000, 5_001));
        stats[3] = 5_000;
        assertFalse(PcmEvidence.mixing(stats, 20, 1_000, 5_001));
        assertTrue(PcmEvidence.mixing(stats, 20, 5_000, 5_001));
    }

    @Test public void malformedOrFutureCountersAreNotAccepted() {
        assertFalse(PcmEvidence.valid(new long[8], 1_000));
        assertFalse(PcmEvidence.valid(new long[]{2, 1, 1, 900, 1, 1, 0, 0, 0}, 1_000));
        assertFalse(PcmEvidence.valid(new long[]{1, 1, -1, 900, 1, 1, 0, 0, 0}, 1_000));
        assertFalse(PcmEvidence.valid(new long[]{1, 1, 1, 1_001, 1, 1, 0, 0, 0}, 1_000));
    }
}
