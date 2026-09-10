package xyz.melodylsp.codec.mono;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

import org.junit.Test;

public final class MonoOverrideTest {

    @Test
    public void ownershipIsCommittedBeforeTheFirstAudioWrite() {
        Fixture fixture = new Fixture();
        MonoOverride override = fixture.create();

        assertResult("mono", "", true, override.update(true));

        assertTrue(override.isOwned());
        assertTrue(fixture.journal.owned);
        assertTrue(fixture.audio.actual);
        assertEquals(1, fixture.journal.marks);
        assertTrue(fixture.events.indexOf("journal.mark")
                < fixture.events.indexOf("audio.set:true"));
        assertEquals(Arrays.asList(true), fixture.audio.written);
    }

    @Test
    public void usersExistingMonoIsNeverAcquiredOrTurnedOff() {
        Fixture fixture = new Fixture();
        fixture.audio.actual = true;
        fixture.audio.saved = true;
        MonoOverride override = fixture.create();

        assertResult("system_mono", "", false, override.update(true));
        assertResult("system_mono", "", false, override.update(false));
        assertResult("system_mono", "", false, override.recover());

        assertFalse(override.isOwned());
        assertTrue(fixture.audio.actual);
        assertTrue(fixture.audio.written.isEmpty());
        assertEquals(0, fixture.journal.marks);
        assertEquals(0, fixture.journal.clears);
    }

    @Test
    public void unownedTransientMonoIsNotMistakenForOurOverride() {
        Fixture fixture = new Fixture();
        fixture.audio.actual = true;
        fixture.audio.saved = false;
        MonoOverride override = fixture.create();

        assertResult("system_mono", "", false, override.update(false));
        assertResult("system_mono", "", false, override.update(true));
        assertResult("system_mono", "", false, override.recover());

        assertTrue(fixture.audio.written.isEmpty());
        assertEquals(0, fixture.journal.marks);
        assertEquals(0, fixture.journal.clears);
    }

    @Test
    public void failedSetterThatChangedAudioStillRollsBack() {
        Fixture fixture = new Fixture();
        fixture.audio.writes.add(new Write(false, true, false));
        MonoOverride override = fixture.create();

        assertResult("unavailable", "audio_write_failed", false, override.update(true));

        assertEquals(Arrays.asList(true, false), fixture.audio.written);
        assertFalse(fixture.audio.actual);
        assertFalse(fixture.journal.owned);
        assertEquals(1, fixture.journal.clears);
        assertTrue(fixture.events.indexOf("audio.set:false")
                < fixture.events.indexOf("journal.clear"));
    }

    @Test
    public void setterExceptionAfterChangingAudioIsContainedAndRolledBack() {
        Fixture fixture = new Fixture();
        fixture.audio.writes.add(new Write(false, true, true));
        MonoOverride override = fixture.create();

        assertResult("unavailable", "audio_write_failed", false, override.update(true));

        assertEquals(Arrays.asList(true, false), fixture.audio.written);
        assertFalse(fixture.audio.actual);
        assertFalse(override.isOwned());
    }

    @Test
    public void setterSuccessWithoutMatchingReadbackIsNotReportedAsMono() {
        Fixture fixture = new Fixture();
        fixture.audio.writes.add(new Write(true, false, false));
        MonoOverride override = fixture.create();

        assertResult("unavailable", "audio_write_failed", false, override.update(true));

        assertEquals(Arrays.asList(true), fixture.audio.written);
        assertFalse(fixture.audio.actual);
        assertFalse(fixture.journal.owned);
    }

    @Test
    public void failedReadbackKeepsTheRecoveryMarkerEvenAfterARestoreWrite() {
        Fixture fixture = new Fixture();
        fixture.audio.actualResults.add(false); // Read before acquiring ownership.
        fixture.audio.actualResults.add(null); // Enable readback is unavailable.
        fixture.audio.actualResults.add(null); // Restoration cannot observe the state either.
        fixture.audio.actualResults.add(null); // The restore write remains unconfirmed.
        MonoOverride override = fixture.create();

        assertResult("restoring", "restore_failed", true, override.update(true));

        assertEquals(Arrays.asList(true, false), fixture.audio.written);
        assertFalse(fixture.audio.actual);
        assertTrue(override.isOwned());
        assertTrue(fixture.journal.owned);
        assertEquals(0, fixture.journal.clears);

        assertResult("off", "", false, override.recover());
        assertEquals(Arrays.asList(true, false), fixture.audio.written);
        assertFalse(fixture.journal.owned);
    }

    @Test
    public void aNewInstanceRecoversThePreviousInstancesOverride() {
        Fixture fixture = new Fixture();
        assertResult("mono", "", true, fixture.create().update(true));
        MonoOverride restarted = fixture.create();

        assertTrue(restarted.isOwned());
        assertResult("off", "", false, restarted.recover());

        assertEquals(Arrays.asList(true, false), fixture.audio.written);
        assertFalse(fixture.audio.actual);
        assertFalse(fixture.journal.owned);
    }

    @Test
    public void inheritedMarkerIsRecoveredBeforeANewEnableRequest() {
        Fixture fixture = new Fixture();
        fixture.journal.owned = true;
        fixture.audio.actual = true;
        MonoOverride restarted = fixture.create();

        assertResult("off", "", false, restarted.update(true));
        assertEquals(Arrays.asList(false), fixture.audio.written);
        assertEquals(0, fixture.journal.marks);

        assertResult("mono", "", true, restarted.update(true));
        assertEquals(Arrays.asList(false, true), fixture.audio.written);
        assertEquals(1, fixture.journal.marks);
    }

    @Test
    public void userEnablingPersistentMonoDuringOwnershipIsPreserved() {
        Fixture fixture = new Fixture();
        MonoOverride override = fixture.create();
        override.update(true);
        fixture.audio.saved = true;

        assertResult("system_mono", "", false, override.update(false));

        assertEquals(Arrays.asList(true), fixture.audio.written);
        assertTrue(fixture.audio.actual);
        assertFalse(fixture.journal.owned);
    }

    @Test
    public void recoveryUsesTheCurrentPreferenceInsteadOfTheValueAtAcquisition() {
        Fixture fixture = new Fixture();
        fixture.audio.saved = true;
        MonoOverride override = fixture.create();
        override.update(true);
        fixture.audio.saved = false;

        assertResult("off", "", false, override.update(false));

        assertEquals(Arrays.asList(true, false), fixture.audio.written);
        assertFalse(fixture.audio.actual);
    }

    @Test
    public void preferenceChangingDuringRecoveryPreventsPrematureJournalClear() {
        Fixture fixture = new Fixture();
        MonoOverride override = fixture.create();
        override.update(true);
        fixture.audio.saved = true;
        fixture.audio.savedResults.add(false); // Preference at the beginning of restoration.
        fixture.audio.savedResults.add(true); // Changed while the audio write was running.

        assertResult("restoring", "restore_failed", true, override.update(false));
        assertEquals(0, fixture.journal.clears);
        assertFalse(fixture.audio.actual);

        assertResult("system_mono", "", false, override.recover());
        assertEquals(Arrays.asList(true, false, true), fixture.audio.written);
        assertTrue(fixture.audio.actual);
    }

    @Test
    public void failedJournalCommitPreventsAnyAudioWrite() {
        Fixture fixture = new Fixture();
        fixture.journal.markSuccess = false;
        MonoOverride override = fixture.create();

        assertResult("unavailable", "journal_failed", false, override.update(true));

        assertTrue(fixture.audio.written.isEmpty());
        assertFalse(fixture.audio.actual);
        assertFalse(fixture.journal.owned);
        assertEquals(0, fixture.journal.clears);
    }

    @Test
    public void throwingJournalCommitPreventsAnyAudioWrite() {
        Fixture fixture = new Fixture();
        fixture.journal.throwMark = true;
        MonoOverride override = fixture.create();

        assertResult("unavailable", "journal_failed", false, override.update(true));

        assertTrue(fixture.audio.written.isEmpty());
        assertFalse(override.isOwned());
    }

    @Test
    public void unknownOwnershipRejectsBothEnableAndRecoveryWithoutMutations() {
        Fixture fixture = new Fixture();
        fixture.journal.throwRead = true;
        fixture.audio.actual = true;
        MonoOverride override = fixture.create();

        assertResult("unavailable", "journal_failed", false, override.update(true));
        assertResult("unavailable", "journal_failed", false, override.update(false));
        assertResult("unavailable", "journal_failed", false, override.recover());

        assertTrue(fixture.audio.written.isEmpty());
        assertEquals(0, fixture.journal.marks);
        assertEquals(0, fixture.journal.clears);
        assertTrue(fixture.audio.actual);
    }

    @Test
    public void ownershipReadCanRecoverAfterAnInitialBackendException() {
        Fixture fixture = new Fixture();
        fixture.journal.throwRead = true;
        fixture.journal.owned = true;
        fixture.audio.actual = true;
        MonoOverride override = fixture.create();
        assertResult("unavailable", "journal_failed", false, override.recover());
        fixture.journal.throwRead = false;

        assertResult("off", "", false, override.recover());

        assertEquals(Arrays.asList(false), fixture.audio.written);
        assertFalse(fixture.journal.owned);
    }

    @Test
    public void failedClearRetainsOwnershipAndRetriesWithoutRedundantAudioWrites() {
        Fixture fixture = new Fixture();
        MonoOverride override = fixture.create();
        override.update(true);
        fixture.journal.clearSuccess = false;

        assertResult("restoring", "journal_failed", true, override.update(false));
        assertTrue(fixture.journal.owned);
        assertFalse(fixture.audio.actual);
        fixture.journal.clearSuccess = true;

        assertResult("off", "", false, override.recover());
        assertEquals(Arrays.asList(true, false), fixture.audio.written);
        assertEquals(2, fixture.journal.clears);
    }

    @Test
    public void clearExceptionIsContainedAndKeepsOwnership() {
        Fixture fixture = new Fixture();
        MonoOverride override = fixture.create();
        override.update(true);
        fixture.journal.throwClear = true;

        assertResult("restoring", "journal_failed", true, override.recover());

        assertTrue(override.isOwned());
        assertTrue(fixture.journal.owned);
        assertFalse(fixture.audio.actual);
    }

    @Test
    public void audioServiceResetCanReapplyAnOwnedOverride() {
        Fixture fixture = new Fixture();
        MonoOverride override = fixture.create();
        override.update(true);
        fixture.audio.actual = false;

        assertResult("mono", "", true, override.update(true));

        assertEquals(Arrays.asList(true, true), fixture.audio.written);
        assertEquals(1, fixture.journal.marks);
        assertTrue(fixture.audio.actual);
    }

    @Test
    public void anEnableRequestCannotCancelAFailedRestore() {
        Fixture fixture = new Fixture();
        MonoOverride override = fixture.create();
        override.update(true);
        fixture.audio.writes.add(new Write(false, false, false));

        assertResult("restoring", "restore_failed", true, override.update(false));
        assertTrue(fixture.audio.actual);

        assertResult("off", "", false, override.update(true));
        assertEquals(Arrays.asList(true, false, false), fixture.audio.written);
        assertFalse(fixture.audio.actual);
        assertEquals(1, fixture.journal.marks);
    }

    @Test
    public void unreadableAudioOrSavedPreferenceCannotAcquireOwnership() {
        Fixture fixture = new Fixture();
        MonoOverride override = fixture.create();
        fixture.audio.actual = null;
        assertResult("unavailable", "audio_api_unavailable", false, override.update(true));
        fixture.audio.actual = false;
        fixture.audio.saved = null;
        assertResult("unavailable", "audio_api_unavailable", false, override.update(true));
        fixture.audio.saved = false;
        fixture.audio.throwActual = true;
        assertResult("unavailable", "audio_api_unavailable", false, override.update(true));
        fixture.audio.throwActual = false;
        fixture.audio.throwSaved = true;
        assertResult("unavailable", "audio_api_unavailable", false, override.update(true));

        assertTrue(fixture.audio.written.isEmpty());
        assertEquals(0, fixture.journal.marks);
    }

    @Test
    public void unreadablePreferenceDuringRecoveryNeverGuessesARestoreValue() {
        Fixture fixture = new Fixture();
        MonoOverride override = fixture.create();
        override.update(true);
        fixture.audio.throwSaved = true;

        assertResult("restoring", "restore_failed", true, override.recover());

        assertEquals(Arrays.asList(true), fixture.audio.written);
        assertTrue(fixture.journal.owned);
        assertEquals(0, fixture.journal.clears);
    }

    private static void assertResult(String mode, String reason, boolean owned,
            MonoOverride.Result result) {
        assertEquals(mode, result.mode);
        assertEquals(reason, result.reason);
        assertEquals(owned, result.owned);
    }

    private static final class Fixture {
        final List<String> events = new ArrayList<>();
        final FakeAudio audio = new FakeAudio(events);
        final FakeJournal journal = new FakeJournal(events);

        MonoOverride create() {
            return new MonoOverride(audio, journal);
        }
    }

    private static final class Write {
        final boolean success;
        final boolean apply;
        final boolean throwAfter;

        Write(boolean success, boolean apply, boolean throwAfter) {
            this.success = success;
            this.apply = apply;
            this.throwAfter = throwAfter;
        }
    }

    private static final class FakeAudio implements MonoOverride.Audio {
        final List<String> events;
        final List<Boolean> written = new ArrayList<>();
        final Deque<Write> writes = new ArrayDeque<>();
        final Deque<Boolean> actualResults = new LinkedList<>();
        final Deque<Boolean> savedResults = new LinkedList<>();
        Boolean actual = false;
        Boolean saved = false;
        boolean throwActual;
        boolean throwSaved;

        FakeAudio(List<String> events) {
            this.events = events;
        }

        @Override
        public Boolean actualMono() {
            events.add("audio.actual");
            if (throwActual) throw new IllegalStateException("Audio read unavailable");
            return actualResults.isEmpty() ? actual : actualResults.removeFirst();
        }

        @Override
        public Boolean savedMono() {
            events.add("audio.saved");
            if (throwSaved) throw new IllegalStateException("Setting unavailable");
            return savedResults.isEmpty() ? saved : savedResults.removeFirst();
        }

        @Override
        public boolean setMono(boolean enabled) {
            events.add("audio.set:" + enabled);
            written.add(enabled);
            Write write = writes.isEmpty() ? new Write(true, true, false) : writes.removeFirst();
            if (write.apply) actual = enabled;
            if (write.throwAfter) throw new IllegalStateException("Audio setter failed");
            return write.success;
        }
    }

    private static final class FakeJournal implements MonoOverride.Journal {
        final List<String> events;
        boolean owned;
        boolean markSuccess = true;
        boolean clearSuccess = true;
        boolean throwRead;
        boolean throwMark;
        boolean throwClear;
        int marks;
        int clears;

        FakeJournal(List<String> events) {
            this.events = events;
        }

        @Override
        public boolean isOwned() {
            events.add("journal.read");
            if (throwRead) throw new IllegalStateException("Journal unavailable");
            return owned;
        }

        @Override
        public boolean markOwned() {
            events.add("journal.mark");
            marks++;
            if (throwMark) throw new IllegalStateException("Journal commit failed");
            if (markSuccess) owned = true;
            return markSuccess;
        }

        @Override
        public boolean clear() {
            events.add("journal.clear");
            clears++;
            if (throwClear) throw new IllegalStateException("Journal clear failed");
            if (clearSuccess) owned = false;
            return clearSuccess;
        }
    }
}
