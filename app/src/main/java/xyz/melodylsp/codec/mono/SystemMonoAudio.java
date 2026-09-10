package xyz.melodylsp.codec.mono;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.SystemClock;
import android.provider.Settings;

import java.lang.reflect.Method;

import xyz.melodylsp.codec.util.MLog;

/** Calls AudioSystem from Bluetooth; the user's saved mono setting is read-only. */
final class SystemMonoAudio implements MonoOverride.Audio, MonoOverride.Journal {
    static final String SAVED_MONO = "master_mono";
    private static final String OWNED = "temporary_mono_owned";
    private final Context context;
    private final SharedPreferences preferences;
    private Method getMono, setMono;
    private Boolean lastActual, lastSaved;
    private long writeSequence;
    private PendingWrite pendingWrite;

    SystemMonoAudio(Context context, SharedPreferences preferences) {
        this.context = context;
        this.preferences = preferences;
        try {
            Class<?> audioSystem = Class.forName("android.media.AudioSystem");
            getMono = audioSystem.getDeclaredMethod("getMasterMono");
            setMono = audioSystem.getDeclaredMethod("setMasterMono", boolean.class);
            getMono.setAccessible(true);
            setMono.setAccessible(true);
        } catch (Throwable ignored) { getMono = null; setMono = null; }
    }

    @Override public Boolean actualMono() {
        Boolean value = null;
        String error = "none";
        long identity = Binder.clearCallingIdentity();
        try {
            if (getMono == null) error = "method_unavailable";
            else {
                Object result = getMono.invoke(null);
                if (result instanceof Boolean) value = (Boolean) result;
                else error = "unexpected_result";
            }
        } catch (Throwable failure) { error = MLog.compactThrowable(failure); }
        finally { Binder.restoreCallingIdentity(identity); }
        lastActual = value;
        PendingWrite write = pendingWrite;
        pendingWrite = null;
        if (write != null) {
            // Use the controller's existing verification read: no extra audio calls or retries.
            try {
                MLog.event("mono.audio.write", "sequence", write.sequence,
                        "write_at_ms", write.atMs, "target", write.target,
                        "before", write.before, "after", value, "saved", write.saved,
                        "status", write.status, "error", write.error, "read_error", error,
                        "call_ms", write.durationMs, "uid", android.os.Process.myUid(),
                        "verification", "policy_flag_only");
            } catch (Throwable ignored) { /* Diagnostics must not affect audio ownership. */ }
        }
        return value;
    }

    @Override public boolean setMono(boolean enabled) {
        PendingWrite write = new PendingWrite(++writeSequence, enabled, lastActual, lastSaved);
        boolean success = false;
        long identity = Binder.clearCallingIdentity();
        try {
            if (setMono == null) write.error = "method_unavailable";
            else {
                Object result = setMono.invoke(null, enabled);
                if (result instanceof Number) {
                    int status = ((Number) result).intValue();
                    write.status = Integer.toString(status);
                    success = status == 0;
                } else write.error = "unexpected_result";
            }
        } catch (Throwable failure) { write.error = MLog.compactThrowable(failure); }
        finally {
            Binder.restoreCallingIdentity(identity);
            write.durationMs = SystemClock.elapsedRealtime() - write.atMs;
            pendingWrite = write;
        }
        return success;
    }

    @Override public Boolean savedMono() {
        int user = currentUser();
        if (user < 0) return lastSaved = null;
        long identity = Binder.clearCallingIdentity();
        try {
            Method read = Settings.System.class.getDeclaredMethod("getStringForUser",
                    ContentResolver.class, String.class, int.class);
            read.setAccessible(true);
            Object value = read.invoke(null, context.getContentResolver(), SAVED_MONO, user);
            if (value == null || "0".equals(value)) return lastSaved = false;
            return lastSaved = "1".equals(value) ? Boolean.TRUE : null;
        } catch (Throwable ignored) { return lastSaved = null; }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    int currentUser() {
        long identity = Binder.clearCallingIdentity();
        try {
            Method method = Class.forName("android.app.ActivityManager")
                    .getDeclaredMethod("getCurrentUser");
            method.setAccessible(true);
            return ((Number) method.invoke(null)).intValue();
        } catch (Throwable ignored) { return -1; }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public boolean isOwned() { return preferences.getBoolean(OWNED, false); }
    @Override public boolean markOwned() { return preferences.edit().putBoolean(OWNED, true).commit(); }
    @Override public boolean clear() { return preferences.edit().remove(OWNED).commit(); }

    private static final class PendingWrite {
        final long sequence, atMs = SystemClock.elapsedRealtime();
        final boolean target;
        final Boolean before, saved;
        String status = "unavailable", error = "none";
        long durationMs;

        PendingWrite(long sequence, boolean target, Boolean before, Boolean saved) {
            this.sequence = sequence;
            this.target = target;
            this.before = before;
            this.saved = saved;
        }
    }
}
