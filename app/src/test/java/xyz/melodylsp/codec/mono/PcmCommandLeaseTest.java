package xyz.melodylsp.codec.mono;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PcmCommandLeaseTest {
    @Test public void expiredCommandsCannotReviveMixingOrAdvanceTheHighWaterMark() {
        PcmCommandLease lease = new PcmCommandLease();
        assertTrue(lease.accept(10, 1, 1_000, 5_000, 1_010));
        assertFalse(lease.accept(10, 100, 1_000, 5_000, 5_000));
        assertTrue(lease.accept(10, 2, 5_010, 9_010, 5_020));
    }

    @Test public void aRestartedOwnerSupersedesOldRepliesRegardlessOfTheirRevision() {
        PcmCommandLease lease = new PcmCommandLease();
        assertTrue(lease.accept(10, 100, 1_000, 5_000, 1_010));
        assertTrue(lease.accept(20, 1, 1_020, 5_020, 1_030));
        assertFalse(lease.accept(10, 101, 1_030, 5_030, 1_040));
        assertFalse(lease.accept(20, 1, 1_030, 5_030, 1_040));
        assertTrue(lease.accept(20, 2, 1_040, 5_040, 1_050));
    }

    @Test public void futureOrUnboundedPermissionsAreRejected() {
        PcmCommandLease lease = new PcmCommandLease();
        assertFalse(lease.accept(1, 1, 2_000, 6_000, 1_999));
        assertFalse(lease.accept(1, 1, 1_000, 5_001, 1_001));
        assertFalse(lease.accept(1, 1, 1_000, Long.MAX_VALUE, 1_001));
        assertFalse(lease.accept(0, 1, 1_000, 5_000, 1_001));
        assertTrue(lease.accept(1, 1, 1_000, 5_000, 1_001));
    }
}
