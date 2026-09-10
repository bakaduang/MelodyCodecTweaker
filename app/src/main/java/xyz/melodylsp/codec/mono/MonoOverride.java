package xyz.melodylsp.codec.mono;

/**
 * Temporarily overrides the audio engine's mono state without changing the user's saved setting.
 *
 * <p>The journal must durably record ownership before {@link Audio#setMono(boolean)} is called.
 * A marker surviving process death requests restoration to the current saved preference. Keep
 * calling {@link #recover()} or {@link #update(boolean)} while the result is {@code restoring}.
 * Backend failures are contained so they cannot escape into the Bluetooth process.
 */
public final class MonoOverride {

    public interface Audio {
        /** Returns null when the current audio-engine state cannot be read. */
        Boolean actualMono();

        /** Returns null when the user's persistent mono preference cannot be read. */
        Boolean savedMono();

        /** Changes only the transient audio-engine state; success still requires a readback. */
        boolean setMono(boolean enabled);
    }

    public interface Journal {
        boolean isOwned();

        /** Returns true only after the ownership marker has been committed durably. */
        boolean markOwned();

        /** Returns true only after removal of the ownership marker has been committed. */
        boolean clear();
    }

    public static final class Result {
        public final String mode;
        public final String reason;
        public final boolean owned;

        private Result(String mode, String reason, boolean owned) {
            this.mode = mode;
            this.reason = reason;
            this.owned = owned;
        }
    }

    private final Audio audio;
    private final Journal journal;
    private boolean owned;
    private boolean restoring;

    public MonoOverride(Audio audio, Journal journal) {
        this.audio = audio;
        this.journal = journal;
        // A pre-existing marker belongs to a previous instance and must be recovered first.
        adoptRecordedOwnership();
    }

    /**
     * Applies the requested transient override, or restores the user's saved preference.
     * A pending recovery takes precedence over enabling a new override.
     */
    public synchronized Result update(boolean desired) {
        if (!owned && !adoptRecordedOwnership()) {
            return result("unavailable", "journal_failed");
        }
        if (owned && (restoring || !desired)) {
            return restore();
        }
        if (!desired) {
            return observe();
        }

        Boolean actual = readActual();
        Boolean saved = readSaved();
        if (actual == null || saved == null) {
            return owned ? rollback("audio_api_unavailable")
                    : result("unavailable", "audio_api_unavailable");
        }
        if (actual) {
            return result(owned ? "mono" : "system_mono", "");
        }
        if (!owned) {
            if (!markOwned()) {
                // No audio write is allowed even if a failed journal call had partial effects.
                // A later operation can discover and recover such a marker safely.
                return result("unavailable", "journal_failed");
            }
            owned = true;
        }

        boolean written = writeMono(true);
        Boolean applied = readActual();
        if (written && Boolean.TRUE.equals(applied)) {
            return result("mono", "");
        }
        return rollback(written && applied == null
                ? "audio_api_unavailable" : "audio_write_failed");
    }

    /** Restores a recorded override; without ownership it only observes the current audio state. */
    public synchronized Result recover() {
        if (!owned && !adoptRecordedOwnership()) {
            return result("unavailable", "journal_failed");
        }
        return owned ? restore() : observe();
    }

    /** True until a confirmed restoration and a successful journal clear have both completed. */
    public synchronized boolean isOwned() {
        return owned;
    }

    private Result rollback(String failure) {
        restoring = true;
        Result restored = restore();
        return restored.owned ? restored : result("unavailable", failure);
    }

    private Result restore() {
        restoring = true;
        Boolean target = readSaved();
        if (target == null) {
            return result("restoring", "restore_failed");
        }
        Boolean actual = readActual();
        if (!target.equals(actual)) {
            boolean written = writeMono(target);
            Boolean applied = readActual();
            if (!written || !target.equals(applied)) {
                return result("restoring", "restore_failed");
            }
        }

        // The preference can change while restoring. Do not release ownership of a stale value;
        // the next recovery attempt will apply the newly saved value instead.
        if (!target.equals(readSaved())) {
            return result("restoring", "restore_failed");
        }
        if (!clearJournal()) {
            return result("restoring", "journal_failed");
        }
        owned = false;
        restoring = false;
        return result(target ? "system_mono" : "off", "");
    }

    private Result observe() {
        Boolean actual = readActual();
        return actual == null ? result("unavailable", "audio_api_unavailable")
                : result(actual ? "system_mono" : "off", "");
    }

    /** False means ownership is unknown, so neither audio nor journal mutations are allowed. */
    private boolean adoptRecordedOwnership() {
        try {
            if (journal.isOwned()) {
                owned = true;
                restoring = true;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Boolean readActual() {
        try {
            return audio.actualMono();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Boolean readSaved() {
        try {
            return audio.savedMono();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean writeMono(boolean enabled) {
        try {
            return audio.setMono(enabled);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean markOwned() {
        try {
            return journal.markOwned();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean clearJournal() {
        try {
            return journal.clear();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Result result(String mode, String reason) {
        return new Result(mode, reason, owned);
    }
}
