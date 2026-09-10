package xyz.melodylsp.codec.mono;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import xyz.melodylsp.codec.util.MLog;
import xyz.melodylsp.codec.util.TrustedBroadcasts;

/** Process-local UI client. Only the primary Melody process attaches a wear producer. */
public final class AutoMonoHostClient {
    public interface Listener { void onAutoMonoChanged(String mac); }
    interface Producer { void onEnabledDevices(Set<String> macs); }

    public static final class Status {
        public final boolean enabled, pending, available, hookReady;
        public final int knownMask, inEarMask, inBoxMask;
        public final String mode, reason;
        public final long updatedAtMs;

        Status(boolean enabled, boolean pending, boolean available, boolean hookReady,
                int known, int ear, int box, String mode, String reason, long time) {
            this.enabled = enabled;
            this.pending = pending;
            this.available = available;
            this.hookReady = hookReady;
            this.knownMask = known;
            this.inEarMask = ear;
            this.inBoxMask = box;
            this.mode = mode;
            this.reason = reason;
            this.updatedAtMs = time;
        }
    }

    private static AutoMonoHostClient instance;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long clientId = SystemClock.elapsedRealtimeNanos();
    private final Map<String, Status> statuses = new HashMap<>();
    private final Map<String, Pending> pending = new HashMap<>();
    private final Map<String, Long> queriedAt = new HashMap<>();
    private final Map<String, Long> responseRevisions = new HashMap<>();
    private final ArrayList<Listener> listeners = new ArrayList<>();
    private final Set<String> enabledDevices = new HashSet<>();
    private long nextRequest, sequence, serverId, serverRevision;
    private Producer producer;
    private boolean hookReady;

    public static synchronized AutoMonoHostClient get(Context context) {
        if (instance == null) instance = new AutoMonoHostClient(context.getApplicationContext());
        return instance;
    }

    private AutoMonoHostClient(Context context) {
        this.context = context;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                if (intent == null || !AutoMonoIpc.STATUS.equals(intent.getAction())) return;
                if (TrustedBroadcasts.supportsSenderIdentity()
                        && !TrustedBroadcasts.isTrustedSender(ctx,
                        TrustedBroadcasts.captureSender(this), AutoMonoIpc.BLUETOOTH)) return;
                receive(intent);
            }
        };
        boolean registered = TrustedBroadcasts.registerExportedReceiver(context, receiver,
                new IntentFilter(AutoMonoIpc.STATUS),
                TrustedBroadcasts.PERMISSION_BLUETOOTH_PRIVILEGED, handler);
        MLog.event("mono.host.bridge", "ok", registered);
        handler.post(() -> query(""));
        for (long delay : new long[]{1_500L, 5_000L, 15_000L}) {
            handler.postDelayed(() -> { if (serverId == 0L) query(""); }, delay);
        }
    }

    public synchronized Status status(String address) {
        String mac = AutoMonoIpc.normalizeMac(address);
        Status found = statuses.get(mac);
        return found != null ? found : new Status(false, false, false, hookReady,
                0, 0, 0, "off", "query_timeout", 0L);
    }

    public synchronized void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public synchronized void removeListener(Listener listener) { listeners.remove(listener); }

    public void query(String address) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> query(address));
            return;
        }
        String mac = AutoMonoIpc.normalizeMac(address);
        String key = mac == null ? "" : mac;
        long now = SystemClock.elapsedRealtime();
        Long previous = queriedAt.get(key);
        if (previous != null && now - previous < 750L) return;
        queriedAt.put(key, now);
        Intent intent = request("query", key);
        intent.putExtra("request", ++nextRequest);
        send(intent);
    }

    public void setEnabled(String address, boolean enabled) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> setEnabled(address, enabled));
            return;
        }
        String mac = AutoMonoIpc.normalizeMac(address);
        if (mac == null) return;
        Status old = status(mac);
        Pending change = new Pending(++nextRequest, SystemClock.elapsedRealtimeNanos(), old.enabled, enabled);
        pending.put(mac, change);
        put(mac, new Status(enabled, true, old.available, old.hookReady, old.knownMask,
                old.inEarMask, old.inBoxMask, old.mode, old.reason, old.updatedAtMs));
        sendSetting(mac, change);
        handler.postDelayed(() -> {
            if (pending.get(mac) == change) sendSetting(mac, change);
        }, 2_000L);
        handler.postDelayed(() -> {
            if (pending.get(mac) != change) return;
            pending.remove(mac);
            Status current = status(mac);
            put(mac, new Status(change.previous, false, false, current.hookReady,
                    current.knownMask, current.inEarMask, current.inBoxMask,
                    "unavailable", "query_timeout", SystemClock.elapsedRealtime()));
        }, 8_000L);
    }

    private void sendSetting(String mac, Pending change) {
        Intent intent = request("set", mac);
        intent.putExtra("request", change.id);
        intent.putExtra("config_at", change.issuedAt);
        intent.putExtra("enabled", change.desired);
        send(intent);
    }

    void attachProducer(Producer producer, boolean ready) {
        handler.post(() -> {
            this.producer = producer;
            this.hookReady = ready;
            send(request("hello", ""));
            producer.onEnabledDevices(new HashSet<>(enabledDevices));
        });
    }

    void sendSnapshot(EarWearStateTracker.Snapshot snapshot) {
        if (snapshot == null || AutoMonoIpc.normalizeMac(snapshot.mac) == null) return;
        Intent intent = request("wear", snapshot.mac);
        intent.putExtra("connected", snapshot.connected);
        intent.putExtra("session", snapshot.sessionId);
        intent.putExtra("connected_at", snapshot.connectedAtMs);
        intent.putExtra("reported_at", snapshot.receivedAtMs);
        intent.putExtra("known", snapshot.knownMask);
        intent.putExtra("ear", snapshot.inEarMask);
        intent.putExtra("box", snapshot.inBoxMask);
        send(intent);
    }

    private Intent request(String op, String mac) {
        Intent intent = new Intent(AutoMonoIpc.REQUEST).setPackage(AutoMonoIpc.BLUETOOTH);
        intent.putExtra("version", AutoMonoIpc.VERSION);
        intent.putExtra("op", op);
        intent.putExtra("mac", mac);
        intent.putExtra("client", clientId);
        intent.putExtra("sequence", ++sequence);
        intent.putExtra("user", android.os.Process.myUid() / 100000);
        intent.putExtra("sent_at", SystemClock.elapsedRealtime());
        intent.putExtra("hook_ready", hookReady);
        return intent;
    }

    private void send(Intent intent) { TrustedBroadcasts.send(context, intent); }

    private void receive(Intent intent) {
        try {
            if (intent.getIntExtra("version", 0) != AutoMonoIpc.VERSION) return;
            long server = intent.getLongExtra("server", 0);
            long revision = intent.getLongExtra("revision", 0);
            if (server <= 0 || server < serverId) return;
            if (server != serverId) { responseRevisions.clear(); serverRevision = 0L; }
            serverId = server;
            String responseMac = AutoMonoIpc.normalizeMac(intent.getStringExtra("mac"));
            String responseKey = responseMac == null ? "" : responseMac;
            if (revision < responseRevisions.getOrDefault(responseKey, 0L)) return;
            responseRevisions.put(responseKey, revision);
            String[] enabled = intent.getStringArrayExtra("enabled_devices");
            if (enabled == null || enabled.length > 128) return;
            Set<String> nextEnabled = new HashSet<>();
            for (String value : enabled) {
                String key = AutoMonoIpc.normalizeMac(value);
                if (key != null) nextEnabled.add(key);
            }
            if (revision >= serverRevision) {
                serverRevision = revision;
                boolean changed = !nextEnabled.equals(enabledDevices);
                enabledDevices.clear();
                enabledDevices.addAll(nextEnabled);
                if (producer != null && changed) {
                    producer.onEnabledDevices(Collections.unmodifiableSet(nextEnabled));
                }
            }
            String mac = AutoMonoIpc.normalizeMac(intent.getStringExtra("mac"));
            if (mac == null) return;
            int known = intent.getIntExtra("known", 0);
            int ear = intent.getIntExtra("ear", 0);
            int box = intent.getIntExtra("box", 0);
            if (!AutoMonoIpc.validMasks(known, ear, box)) return;
            Pending change = pending.get(mac);
            boolean matching = change != null
                    && intent.getLongExtra("reply_client", 0) == clientId
                    && intent.getLongExtra("reply_request", 0) == change.id;
            if (matching) pending.remove(mac);
            boolean isPending = change != null && !matching;
            put(mac, new Status(isPending ? change.desired : intent.getBooleanExtra("enabled", false),
                    isPending, true, intent.getBooleanExtra("hook_ready", false), known, ear, box,
                    safe(intent.getStringExtra("mode"), "unavailable"),
                    safe(intent.getStringExtra("reason"), "audio_api_unavailable"),
                    SystemClock.elapsedRealtime()));
        } catch (Throwable error) {
            MLog.w("invalid auto mono response", error);
        }
    }

    private static String safe(String value, String fallback) {
        return value != null && value.length() <= 80 ? value : fallback;
    }

    private void put(String mac, Status status) {
        ArrayList<Listener> copy;
        synchronized (this) {
            statuses.put(mac, status);
            copy = new ArrayList<>(listeners);
        }
        for (Listener listener : copy) {
            try { listener.onAutoMonoChanged(mac); }
            catch (Throwable error) { MLog.w("auto mono listener failed", error); }
        }
    }

    private static final class Pending {
        final long id, issuedAt;
        final boolean previous, desired;
        Pending(long id, long issuedAt, boolean previous, boolean desired) {
            this.id = id; this.issuedAt = issuedAt; this.previous = previous; this.desired = desired;
        }
    }
}
