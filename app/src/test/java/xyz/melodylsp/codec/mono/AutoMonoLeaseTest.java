package xyz.melodylsp.codec.mono;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AutoMonoLeaseTest {
    @Test public void switchingBackToAnExistingUserAllowsItsEarlierProcess() {
        AutoMonoLease gate = new AutoMonoLease();
        assertTrue(gate.accept(200, 1, 1_000, 1_000));
        gate.reset();
        assertTrue(gate.accept(100, 20, 2_000, 2_000));
    }
    @Test public void oldProcessCannotRenewOrDisconnectAfterNewOwner() {
        AutoMonoLease gate = new AutoMonoLease();
        assertTrue(gate.accept(100, 5, 1_000, 1_000));
        assertTrue(gate.accept(200, 1, 1_010, 1_010));
        assertFalse(gate.accept(100, 500, 1_020, 1_020));
        assertEquals(200, gate.producer());
    }

    @Test public void duplicateAndReorderedMessagesCannotRenewLease() {
        AutoMonoLease gate = new AutoMonoLease();
        assertTrue(gate.accept(100, 5, 1_000, 1_000));
        assertFalse(gate.accept(100, 5, 2_000, 2_000));
        assertFalse(gate.accept(100, 4, 2_000, 2_000));
        assertTrue(gate.accept(100, 6, 2_000, 2_000));
    }

    @Test public void delayedOrFutureTrafficCannotTakeOwnership() {
        AutoMonoLease gate = new AutoMonoLease();
        assertFalse(gate.accept(100, 1, 1_000, 1_001 + AutoMonoIpc.LEASE_MS));
        assertFalse(gate.accept(100, 2, 2_001, 2_000));
        assertEquals(0, gate.producer());
    }

    @Test public void stableWearReportSurvivesWhileHeartbeatIsFresh() {
        // A valid status need not be repeated by the earbuds while the state is unchanged.
        assertTrue(AutoMonoLease.validReport(true, 4, 1_000, 1_100, 3, 1, 2, 1_000_000));
        assertTrue(AutoMonoIpc.recent(1_000_000, 1_000_001));
    }

    @Test public void oldConnectionCacheCannotBePresentedAsFreshState() {
        assertFalse(AutoMonoLease.validReport(true, 4, 2_000, 1_100, 3, 1, 2, 2_100));
        assertTrue(AutoMonoLease.validReport(true, 4, 2_000, 0, 0, 0, 0, 2_100));
        assertFalse(AutoMonoLease.validReport(true, 4, 2_000, 0, 3, 1, 2, 2_100));
    }

    @Test public void malformedStatesCannotTurnIntoSingleEarRequests() {
        assertFalse(AutoMonoLease.validReport(false, 0, 0, 0, 3, 1, 2, 2_100));
        assertFalse(AutoMonoLease.validReport(true, 4, 1_000, 1_100, 1, 3, 0, 2_100));
        assertFalse(AutoMonoLease.validReport(true, 4, 1_000, 1_100, 3, 1, 1, 2_100));
        assertTrue(AutoMonoLease.validReport(false, 0, 0, 0, 0, 0, 0, 2_100));
    }
}
