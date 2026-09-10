package xyz.melodylsp.codec.mono;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import static xyz.melodylsp.codec.mono.EarWearStateTracker.BOTH;
import static xyz.melodylsp.codec.mono.EarWearStateTracker.LEFT;
import static xyz.melodylsp.codec.mono.EarWearStateTracker.RIGHT;

import org.junit.Test;

import xyz.melodylsp.codec.mono.EarWearStateTracker.Snapshot;
import xyz.melodylsp.codec.mono.EarWearStateTracker.State;

public final class EarWearStateTrackerTest {

    private static final String MAC = "AA:BB:CC:DD:EE:01";
    private static final String OTHER_MAC = "AA:BB:CC:DD:EE:02";

    @Test
    public void firstSingleSidedReportCannotInferTheMissingEar() {
        EarWearStateTracker tracker = connectedTracker();

        Snapshot left = tracker.update(MAC, 1L, 1_100L, LEFT, LEFT, 0);

        assertEquals(State.UNKNOWN, left.state());
        assertEquals(LEFT, left.knownMask);
        assertFalse(left.isKnown());
        assertFalse(left.isSingleEar());

        Snapshot completed = tracker.update(MAC, 1L, 1_200L, RIGHT, 0, RIGHT);

        assertEquals(State.LEFT_ONLY, completed.state());
        assertTrue(completed.isKnown());
        assertTrue(completed.isSingleEar());
        assertEquals(RIGHT, completed.inBoxMask);
        // Previously handed-out snapshots remain safe to use on another thread.
        assertEquals(State.UNKNOWN, left.state());
        assertEquals(LEFT, left.knownMask);
    }

    @Test
    public void removingAndReplacingEitherEarUpdatesOnlyTheReportedSide() {
        EarWearStateTracker tracker = connectedTracker();
        assertEquals(State.BOTH, tracker.update(MAC, 1L, 1_100L, BOTH, BOTH, 0).state());

        Snapshot leftOnly = tracker.update(MAC, 1L, 1_200L, RIGHT, 0, 0);
        assertEquals(State.LEFT_ONLY, leftOnly.state());
        assertEquals(BOTH, leftOnly.knownMask);

        Snapshot bothAgain = tracker.update(MAC, 1L, 1_300L, RIGHT, RIGHT, 0);
        assertEquals(State.BOTH, bothAgain.state());
        assertFalse(bothAgain.isSingleEar());

        Snapshot rightOnly = tracker.update(MAC, 1L, 1_400L, LEFT, 0, LEFT);
        assertEquals(State.RIGHT_ONLY, rightOnly.state());
        assertTrue(rightOnly.isSingleEar());
    }

    @Test
    public void unwornEarMayBeOnTheDeskOrInTheCase() {
        EarWearStateTracker tracker = connectedTracker();

        Snapshot onDesk = tracker.update(MAC, 1L, 1_100L, BOTH, LEFT, 0);
        Snapshot inCase = tracker.update(MAC, 1L, 1_200L, RIGHT, 0, RIGHT);

        assertEquals(State.LEFT_ONLY, onDesk.state());
        assertEquals(State.LEFT_ONLY, inCase.state());
        assertEquals(0, onDesk.inBoxMask);
        assertEquals(RIGHT, inCase.inBoxMask);

        Snapshot neitherWorn = tracker.update(MAC, 1L, 1_300L, LEFT, 0, 0);
        assertEquals(State.NONE, neitherWorn.state());
        assertTrue(neitherWorn.isKnown());
        assertFalse(neitherWorn.isSingleEar());
    }

    @Test
    public void repeatedConnectionEventDoesNotEraseUsefulReports() {
        EarWearStateTracker tracker = connectedTracker();
        Snapshot worn = tracker.update(MAC, 1L, 1_100L, BOTH, LEFT, RIGHT);

        tracker.beginSession(MAC.toLowerCase(java.util.Locale.ROOT), 1L, 5_000L);

        assertSame(worn, tracker.snapshot(MAC));
        assertEquals(1_000L, tracker.snapshot(MAC).connectedAtMs);
    }

    @Test
    public void newConnectionCannotInheritOrAcceptReportsFromTheOldConnection() {
        EarWearStateTracker tracker = connectedTracker();
        tracker.update(MAC, 1L, 1_100L, BOTH, LEFT, RIGHT);
        tracker.beginSession(MAC, 2L, 2_000L);

        Snapshot newConnection = tracker.snapshot(MAC);
        assertEquals(State.UNKNOWN, newConnection.state());
        assertEquals(0, newConnection.knownMask);
        assertSame(newConnection, tracker.update(MAC, 1L, 2_100L, BOTH, LEFT, RIGHT));
        assertSame(newConnection, tracker.update(MAC, 0L, 2_100L, BOTH, LEFT, RIGHT));

        Snapshot partial = tracker.update(MAC, 2L, 2_200L, RIGHT, 0, RIGHT);
        assertEquals(State.UNKNOWN, partial.state());
        assertEquals(RIGHT, partial.knownMask);
    }

    @Test
    public void reportsCannotStartOrResurrectAConnection() {
        EarWearStateTracker tracker = new EarWearStateTracker();
        Snapshot absent = tracker.snapshot(MAC);
        assertSame(absent, tracker.update(MAC, 1L, 1_100L, BOTH, LEFT, RIGHT));
        assertEquals(State.DISCONNECTED, absent.state());

        tracker.beginSession(MAC, 1L, 1_000L);
        tracker.update(MAC, 1L, 1_100L, BOTH, LEFT, RIGHT);
        tracker.endSession(MAC);
        Snapshot ended = tracker.snapshot(MAC);

        assertEquals(State.DISCONNECTED, ended.state());
        assertFalse(ended.connected);
        assertEquals(0L, ended.sessionId);
        assertEquals(0, ended.knownMask);
        assertFalse(ended.isKnown());
        assertFalse(ended.isSingleEar());
        assertSame(ended, tracker.update(MAC, 1L, 2_000L, BOTH, LEFT, RIGHT));
        tracker.endSession(MAC);
        assertSame(ended, tracker.snapshot(MAC));
    }

    @Test
    public void olderTimestampsAreRejectedButSameMillisecondReportsCanCompleteEachOther() {
        EarWearStateTracker tracker = connectedTracker();
        Snapshot initial = tracker.snapshot(MAC);
        assertSame(initial, tracker.update(MAC, 1L, 999L, BOTH, LEFT, RIGHT));

        tracker.update(MAC, 1L, 1_100L, LEFT, LEFT, 0);
        Snapshot completed = tracker.update(MAC, 1L, 1_100L, RIGHT, 0, RIGHT);
        assertEquals(State.LEFT_ONLY, completed.state());

        assertSame(completed, tracker.update(MAC, 1L, 1_099L, BOTH, BOTH, 0));
        assertSame(completed, tracker.update(MAC, 1L, 1_099L, 4, 4, 0));
        assertEquals(1_100L, tracker.snapshot(MAC).receivedAtMs);
    }

    @Test
    public void contradictoryEarReportRequiresFreshConsistentEvidenceForThatEar() {
        EarWearStateTracker tracker = connectedTracker();
        tracker.update(MAC, 1L, 1_100L, BOTH, LEFT, RIGHT);

        Snapshot contradiction = tracker.update(MAC, 1L, 1_200L, RIGHT, RIGHT, RIGHT);
        assertEquals(State.UNKNOWN, contradiction.state());
        assertEquals(LEFT, contradiction.knownMask);
        assertEquals(LEFT, contradiction.inEarMask);
        assertEquals(0, contradiction.inBoxMask);
        assertFalse(contradiction.isSingleEar());

        Snapshot recovered = tracker.update(MAC, 1L, 1_300L, RIGHT, RIGHT, 0);
        assertEquals(State.BOTH, recovered.state());
    }

    @Test
    public void invalidMasksRevokeSingleEarDecisionsInsteadOfGuessing() {
        int[][] invalidReports = {
                {4, 4, 0}, {BOTH, 4, 0}, {BOTH, 0, 4},
                {LEFT, RIGHT, 0}, {LEFT, 0, RIGHT}, {-1, 0, 0},
                {0, LEFT, 0}, {BOTH, -1, 0}, {BOTH, 0, -1}
        };
        for (int[] report : invalidReports) {
            EarWearStateTracker tracker = connectedTracker();
            tracker.update(MAC, 1L, 1_100L, BOTH, LEFT, RIGHT);

            Snapshot invalid = tracker.update(MAC, 1L, 1_200L,
                    report[0], report[1], report[2]);

            assertEquals(State.UNKNOWN, invalid.state());
            assertEquals(0, invalid.knownMask);
            assertEquals(0, invalid.inEarMask);
            assertEquals(0, invalid.inBoxMask);
            assertTrue(invalid.connected);
            assertFalse(invalid.isSingleEar());
            assertEquals(State.UNKNOWN,
                    tracker.update(MAC, 1L, 1_300L, LEFT, LEFT, 0).state());
        }
    }

    @Test
    public void emptyReportsAndLongSilenceDoNotExpireStableWearState() {
        EarWearStateTracker tracker = connectedTracker();
        Snapshot worn = tracker.update(MAC, 1L, 1_100L, BOTH, LEFT, RIGHT);

        assertSame(worn, tracker.update(MAC, 1L, 86_400_000L, 0, 0, 0));
        assertSame(worn, tracker.snapshot(MAC));
        assertTrue(tracker.snapshot(MAC).isSingleEar());

        Snapshot nextDay = tracker.update(MAC, 1L, 86_400_001L, RIGHT, RIGHT, 0);
        assertEquals(State.BOTH, nextDay.state());
        assertEquals(BOTH, nextDay.knownMask);
    }

    @Test
    public void macNormalizationAndPerDeviceStatePreventCrossDeviceMixing() {
        EarWearStateTracker tracker = new EarWearStateTracker();
        tracker.beginSession("  aa:bb:cc:dd:ee:01  ", 1L, 1_000L);
        tracker.beginSession(OTHER_MAC, 1L, 1_000L);

        Snapshot first = tracker.update(MAC, 1L, 1_100L, LEFT, LEFT, 0);
        Snapshot other = tracker.update(OTHER_MAC, 1L, 1_100L, RIGHT, 0, RIGHT);

        assertEquals(MAC, first.mac);
        assertEquals(LEFT, first.knownMask);
        assertEquals(RIGHT, other.knownMask);
        assertEquals(State.UNKNOWN, first.state());
        assertEquals(State.UNKNOWN, other.state());

        tracker.endSession("aa:bb:cc:dd:ee:01");
        assertEquals(State.DISCONNECTED, tracker.snapshot(MAC).state());
        assertSame(other, tracker.snapshot(OTHER_MAC));
    }

    @Test
    public void malformedAddressesAndInvalidConnectionIdsCannotCreateSessions() {
        EarWearStateTracker tracker = new EarWearStateTracker();
        String[] invalidAddresses = {null, "", "AA:BB:CC:DD:EE", "AA-BB-CC-DD-EE-01",
                "GG:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:001"};
        for (String address : invalidAddresses) {
            tracker.beginSession(address, 1L, 1_000L);
            assertEquals(State.DISCONNECTED,
                    tracker.update(address, 1L, 1_100L, BOTH, LEFT, RIGHT).state());
            tracker.endSession(address);
        }

        tracker.beginSession(MAC, 0L, 1_000L);
        tracker.beginSession(MAC, -1L, 1_000L);
        tracker.beginSession(MAC, 1L, -1L);
        assertEquals(State.DISCONNECTED, tracker.snapshot(MAC).state());
    }

    private static EarWearStateTracker connectedTracker() {
        EarWearStateTracker tracker = new EarWearStateTracker();
        tracker.beginSession(MAC, 1L, 1_000L);
        return tracker;
    }
}
