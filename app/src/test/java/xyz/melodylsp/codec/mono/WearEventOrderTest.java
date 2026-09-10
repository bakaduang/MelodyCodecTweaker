package xyz.melodylsp.codec.mono;

import org.junit.Test;
import static org.junit.Assert.*;

public final class WearEventOrderTest {
    private static final String MAC = "00:11:22:33:44:55";

    @Test public void packetCapturedBeforeDisconnectCannotReviveSession() {
        WearEventOrder order = new WearEventOrder();
        assertTrue(order.accept(MAC, 1, 100));
        assertTrue(order.boundary(MAC, 3, 300, false));
        assertFalse(order.accept(MAC, 2, 100));
        assertFalse(order.accept(MAC, 4, 100));
    }

    @Test public void latePacketCannotRollBackReconnectedSession() {
        WearEventOrder order = new WearEventOrder();
        order.boundary(MAC, 3, 300, false);
        order.boundary(MAC, 5, 500, true);
        assertFalse(order.accept(MAC, 2, 100));
        assertFalse(order.accept(MAC, 6, 100));
        assertTrue(order.accept(MAC, 7, 500));
    }

    @Test public void earlierBoundaryCallbackCannotEraseNewPacket() {
        WearEventOrder order = new WearEventOrder();
        order.boundary(MAC, 2, 100, true);
        assertTrue(order.accept(MAC, 3, 100));
        assertFalse(order.currentBoundary(MAC, 2));
        assertFalse(order.boundary(MAC, 1, 50, false));
    }

    @Test public void sameMillisecondEventsUseOrderInsteadOfTimestamp() {
        WearEventOrder order = new WearEventOrder();
        order.boundary(MAC, 2, 100, false);
        order.boundary(MAC, 3, 100, true);
        assertFalse(order.accept(MAC, 1, 100));
        assertTrue(order.accept(MAC, 4, 100));
    }

    @Test public void unknownPriorConnectionMayStartFromLiveProtocolPacket() {
        WearEventOrder order = new WearEventOrder();
        assertTrue(order.accept(MAC, 1, 100));
        assertFalse(order.accept(MAC, 1, 100));
    }

    @Test public void protocolReconnectClearsOldEarEvenWhenAggregateMarkerDoesNotChange() {
        WearEventOrder order = new WearEventOrder();
        EarWearStateTracker tracker = new EarWearStateTracker();
        order.boundary(MAC, 1, 100, true);
        long appliedBoundary = order.boundaryOrder(MAC);
        tracker.beginSession(MAC, 1, 100);
        order.accept(MAC, 2, 100);
        tracker.update(MAC, 1, 110, 3, 3, 0);

        // A2DP stays connected: a protocol boundary carries the same aggregate marker.
        order.boundary(MAC, 3, 100, true);
        assertTrue(order.accept(MAC, 4, 100));
        assertEquals(3, order.acceptedBoundaryOrder(MAC, 4));
        assertFalse(order.currentBoundary(MAC, 3)); // The delayed Handler callback is skipped.
        assertTrue(order.hasUnappliedBoundary(MAC, appliedBoundary));
        tracker.beginSession(MAC, 2, 120); // The packet path must first apply this boundary.
        appliedBoundary = order.boundaryOrder(MAC);
        EarWearStateTracker.Snapshot next = tracker.update(MAC, 2, 120, 1, 0, 0);
        assertFalse(next.isSingleEar());
        assertEquals(EarWearStateTracker.State.UNKNOWN, next.state());
        assertFalse(order.hasUnappliedBoundary(MAC, appliedBoundary));
    }

    @Test public void concurrentBoundaryCannotBeMarkedAppliedByAnOlderAdmittedPacket() {
        WearEventOrder order = new WearEventOrder();
        order.boundary(MAC, 1, 100, true);
        order.accept(MAC, 2, 100);
        order.boundary(MAC, 3, 100, true);
        assertEquals(1, order.acceptedBoundaryOrder(MAC, 2));
        assertTrue(order.accept(MAC, 4, 100));
        assertEquals(3, order.acceptedBoundaryOrder(MAC, 4));
    }
}
