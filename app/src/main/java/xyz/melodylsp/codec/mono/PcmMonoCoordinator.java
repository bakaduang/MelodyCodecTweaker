package xyz.melodylsp.codec.mono;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.IntSupplier;

import xyz.melodylsp.codec.util.MLog;
import xyz.melodylsp.codec.util.TrustedBroadcasts;

/** Bluetooth sends expiring permissions to mix; player messages can only supply telemetry. */
final class PcmMonoCoordinator {
    static final class Result {
        final String mode, reason;
        Result(String mode, String reason) { this.mode = mode; this.reason = reason; }
    }

    private final Context context;
    private final IntSupplier currentUser;
    private final Runnable changed;
    private final Map<String, Player> players = new HashMap<>();
    private final long server = SystemClock.elapsedRealtimeNanos();
    private long revision, generation = server, lastSendAt, lastLogAt;
    private String targetKey = "", lastLog = "";
    private boolean desired, registered;
    private int[] devices = new int[0];

    PcmMonoCoordinator(Context context, Handler handler, IntSupplier currentUser, Runnable changed) {
        this.context = context;
        this.currentUser = currentUser;
        this.changed = changed;
        if (Build.VERSION.SDK_INT < 34) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                TrustedBroadcasts.SenderIdentity sender = TrustedBroadcasts.captureSender(this);
                if (!TrustedBroadcasts.isTrustedSender(ctx, sender, PcmMonoIpc.PLAYERS)
                        || sender.uid / 100000 != currentUser.getAsInt()) return;
                receive(intent, sender);
            }
        };
        try {
            // Ordinary player UIDs lack signature permissions. Exact Android sender identity is
            // mandatory; this receiver has no setting/audio write operations.
            context.registerReceiver(receiver, new IntentFilter(PcmMonoIpc.REQUEST), null,
                    handler, Context.RECEIVER_EXPORTED);
            registered = true;
        } catch (Throwable failure) { MLog.w("PCM coordinator registration failed", failure); }
    }

    private void receive(Intent intent, TrustedBroadcasts.SenderIdentity sender) {
        try {
            long now = SystemClock.elapsedRealtime();
            if (intent == null || !PcmMonoIpc.REQUEST.equals(intent.getAction())
                    || intent.getIntExtra("version", 0) != PcmMonoIpc.VERSION) return;
            long client = intent.getLongExtra("client", 0);
            long sequence = intent.getLongExtra("sequence", 0);
            long sentAt = intent.getLongExtra("sent_at", 0);
            long[] stats = intent.getLongArrayExtra("stats");
            if (client <= 0 || sequence <= 0 || sentAt <= 0 || sentAt > now
                    || now - sentAt > PcmMonoIpc.LEASE_MS || !PcmEvidence.valid(stats, now)) return;
            String key = sender.packageName + ":" + client;
            Player player = players.get(key);
            if (player != null && sequence <= player.sequence) return;
            if (player == null) {
                if (players.size() >= 16) return;
                player = new Player(sender.packageName, sender.uid / 100000, client);
                players.put(key, player);
            }
            player.sequence = sequence;
            player.receivedAt = now;
            player.stats = stats;
            String backend = intent.getStringExtra("backend");
            player.backend = backend == null ? "unknown" : backend.replaceAll("[^a-zA-Z0-9_]", "_");
            if (player.backend.length() > 64) player.backend = player.backend.substring(0, 64);
            player.pid = Math.max(0, intent.getIntExtra("pid", 0));
            String nativeDetail = PcmLibraryLoader.compact(intent.getStringExtra("native_detail"), 1_800);
            if (!nativeDetail.equals(player.nativeDetail)) {
                MLog.eventLogOnly("mono.pcm.native", "package", player.packageName,
                        "pid", player.pid, "backend", player.backend, "detail", nativeDetail);
            }
            player.nativeDetail = nativeDetail;
            send(player, now);
            changed.run();
        } catch (Throwable failure) { MLog.w("PCM player report rejected", failure); }
    }

    Result update(boolean want, String scopeKey, int[] targetDevices, long now) {
        boolean allowed = want && targetDevices != null && targetDevices.length == 1
                && targetDevices[0] > 0;
        String key = allowed ? scopeKey + ":" + Arrays.toString(targetDevices) : "";
        boolean changedTarget = !key.equals(targetKey);
        if (changedTarget) {
            targetKey = key;
            generation = Math.max(generation + 1L, SystemClock.elapsedRealtimeNanos());
        }
        desired = allowed;
        devices = allowed ? targetDevices.clone() : new int[0];
        Iterator<Player> it = players.values().iterator();
        boolean ready = false, mixed = false, unsupported = false, routeBlocked = false;
        long frames = 0;
        int live = 0;
        String backend = "not_loaded";
        StringBuilder playerSummary = new StringBuilder();
        Player nativeReport = null;
        while (it.hasNext()) {
            Player player = it.next();
            if (now - player.receivedAt > 12_000L) { it.remove(); continue; }
            if (changedTarget || now - lastSendAt >= 1_000L) send(player, now);
            if (now - player.receivedAt > PcmMonoIpc.LEASE_MS) continue;
            live++;
            backend = player.backend;
            if (playerSummary.length() < 1_536) {
                if (playerSummary.length() > 0) playerSummary.append('|');
                playerSummary.append(player.packageName).append(":pid=").append(player.pid)
                        .append(':').append(player.backend)
                        .append(":tracks=").append(player.stats[5])
                        .append(":generation=").append(player.stats[1])
                        .append(":frames=").append(player.stats[2]);
            }
            ready |= player.stats[0] == 1;
            // Surface one complete load/install result in the persistent status row. Prefer a
            // failed process; retain separate per-process changes in logcat above.
            if (nativeReport == null || player.stats[0] < nativeReport.stats[0]
                    || (player.stats[0] == nativeReport.stats[0]
                    && (player.packageName + ":" + player.client).compareTo(
                    nativeReport.packageName + ":" + nativeReport.client) < 0)) nativeReport = player;
            if (player.stats[1] == generation) {
                frames += player.stats[2];
                unsupported |= player.stats[6] > 0;
                routeBlocked |= player.stats[7] > 0;
            }
            mixed |= PcmEvidence.mixing(player.stats, generation, player.receivedAt, now);
        }
        if (changedTarget || now - lastSendAt >= 1_000L) lastSendAt = now;
        Result result;
        if (!want) result = new Result("off", "");
        else if (!registered) result = new Result("unavailable", "pcm_bridge_unavailable");
        else if (!allowed) result = new Result("waiting_route", "route_unknown");
        else if (mixed) result = new Result("mono", "pcm_mixed");
        else if (live == 0) result = new Result("waiting_state", "pcm_player_not_loaded");
        else if (!ready) result = new Result("unavailable", "pcm_hook_unavailable");
        else if (unsupported) result = new Result("unavailable", "pcm_format_unsupported");
        else if (routeBlocked) result = new Result("waiting_route", "pcm_track_route");
        else result = new Result("waiting_state", "pcm_waiting_audio");
        String nativeSummary = nativeReport == null ? "none" : nativeReport.packageName
                + ":pid=" + nativeReport.pid + ":backend=" + nativeReport.backend + ":" + nativeReport.nativeDetail;
        String log = result.mode + ":" + result.reason + ":" + live + ":" + backend + ":" + nativeSummary;
        if (!log.equals(lastLog) || (desired && now - lastLogAt >= 10_000L)) {
            lastLog = log;
            lastLogAt = now;
            MLog.event("mono.pcm.backend", "mode", result.mode, "reason", result.reason,
                    "players", live, "backend", backend, "generation", generation, "frames", frames,
                    "player_detail", playerSummary.length() == 0 ? "none" : playerSummary,
                    "native_detail", nativeSummary);
        }
        return result;
    }

    void clearUser() {
        desired = false;
        devices = new int[0];
        targetKey = "";
        generation = Math.max(generation + 1L, SystemClock.elapsedRealtimeNanos());
        long now = SystemClock.elapsedRealtime();
        for (Player player : players.values()) send(player, now);
        players.clear();
    }

    private void send(Player player, long now) {
        if (player.lastSentGeneration == generation && now - player.lastSentAt < 750L) return;
        Context target = userContext(player.user);
        if (target == null) return;
        Intent intent = new Intent(PcmMonoIpc.CONTROL).setPackage(player.packageName);
        intent.putExtra("version", PcmMonoIpc.VERSION);
        intent.putExtra("client", player.client);
        intent.putExtra("server", server);
        intent.putExtra("revision", ++revision);
        intent.putExtra("user", player.user);
        intent.putExtra("sent_at", now);
        intent.putExtra("expires_at", now + PcmMonoIpc.LEASE_MS);
        intent.putExtra("generation", generation);
        intent.putExtra("enabled", desired);
        intent.putExtra("devices", devices);
        if (TrustedBroadcasts.send(target, intent)) {
            player.lastSentAt = now;
            player.lastSentGeneration = generation;
        }
    }

    private Context userContext(int user) {
        if (user == android.os.Process.myUid() / 100000) return context;
        try {
            Object handle = android.os.UserHandle.class.getMethod("of", int.class).invoke(null, user);
            return (Context) Context.class.getMethod("createContextAsUser", android.os.UserHandle.class, int.class)
                    .invoke(context, handle, 0);
        } catch (Throwable ignored) { return null; }
    }

    private static final class Player {
        final String packageName;
        final int user;
        final long client;
        int pid;
        long sequence, receivedAt, lastSentAt, lastSentGeneration;
        String backend = "unknown";
        String nativeDetail = "none";
        long[] stats = new long[9];
        Player(String packageName, int user, long client) {
            this.packageName = packageName;
            this.user = user;
            this.client = client;
        }
    }
}
