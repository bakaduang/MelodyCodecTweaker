package xyz.melodylsp.codec.mono;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import xyz.melodylsp.codec.MelodyCodecLspEntry;
import xyz.melodylsp.codec.util.MLog;
import xyz.melodylsp.codec.util.TrustedBroadcasts;

/** One audio owner in Bluetooth; host heartbeats are liveness leases, not wear-state TTLs. */
public final class AutoMonoSystemBridge {
    private static AutoMonoSystemBridge instance;
    private final MelodyCodecLspEntry module;
    private final Context context;
    private final Handler handler;
    private final SharedPreferences preferences;
    private final SystemMonoAudio audio;
    private final MonoOverride override;
    private final MonoRouteResolver routes;
    private final PcmMonoCoordinator pcm;
    private final AutoMonoLease source = new AutoMonoLease();
    private final Map<String, Report> reports = new HashMap<>();
    private final Map<String, View> views = new HashMap<>();
    private final Map<String, Long> suspendedSessions = new HashMap<>();
    private final Set<String> enabled = new HashSet<>();
    private final long server = SystemClock.elapsedRealtimeNanos();
    private long revision, candidateSince;
    private String lastAudioMode = "", lastAudioReason = "";
    private String candidateKey = "";
    private int user = -1;
    private int observedUser = -1;
    private ContentObserver savedObserver;
    private boolean hookReady, recoveryPending;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            reconcile();
            if (!enabled.isEmpty() || override.isOwned() || recoveryPending) {
                handler.postDelayed(this, 1_000L);
            }
        }
    };

    /** Always install recovery, even when the module's master switch is off. */
    public static void install(MelodyCodecLspEntry module) {
        try {
            Method onCreate = Application.class.getMethod("onCreate");
            module.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    if (chain.getThisObject() instanceof Context
                            && AutoMonoIpc.BLUETOOTH.equals(Application.getProcessName())) {
                        synchronized (AutoMonoSystemBridge.class) {
                            if (instance == null) instance = new AutoMonoSystemBridge(
                                    module, ((Context) chain.getThisObject()).getApplicationContext());
                        }
                    }
                } catch (Throwable error) { MLog.w("auto mono bridge startup failed", error); }
                return result;
            });
        } catch (Throwable error) { MLog.w("auto mono bridge hook unavailable", error); }
    }

    private AutoMonoSystemBridge(MelodyCodecLspEntry module, Context context) {
        this.module = module;
        this.context = context;
        HandlerThread thread = new HandlerThread("Melody-auto-mono");
        thread.start();
        handler = new Handler(thread.getLooper());
        preferences = context.createDeviceProtectedStorageContext()
                .getSharedPreferences("melody_auto_mono", Context.MODE_PRIVATE);
        audio = new SystemMonoAudio(context, preferences);
        override = new MonoOverride(audio, audio);
        routes = new MonoRouteResolver(context);
        pcm = new PcmMonoCoordinator(context, handler, () -> user, this::wake);
        BroadcastReceiver requests = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                TrustedBroadcasts.SenderIdentity sender = TrustedBroadcasts.captureSender(this);
                if (TrustedBroadcasts.supportsSenderIdentity()
                        && !TrustedBroadcasts.isTrustedSender(ctx, sender, AutoMonoIpc.HOST)) return;
                receive(intent, sender);
            }
        };
        boolean registered = TrustedBroadcasts.registerExportedReceiver(context, requests,
                new IntentFilter(AutoMonoIpc.REQUEST),
                TrustedBroadcasts.PERMISSION_OPLUS_COMPONENT_SAFE, handler);
        registerRecoveryObservers();
        handler.post(() -> {
            MLog.setDiagnosticContext(context, "bluetooth");
            MonoOverride.Result recovered = override.recover();
            recoveryPending = recovered.owned || "journal_failed".equals(recovered.reason);
            loadUser();
            MLog.event("mono.bridge", "ok", registered, "recovery", recovered.mode);
            wake();
        });
    }

    private void registerRecoveryObservers() {
        savedObserver = new ContentObserver(handler) {
                @Override public void onChange(boolean selfChange) {
                    for (Map.Entry<String, Report> entry : reports.entrySet()) {
                        suspendedSessions.put(entry.getKey(), entry.getValue().session);
                    }
                    wake();
                }
            };
        try {
            if (routes.audio != null) {
                routes.audio.registerAudioDeviceCallback(new AudioDeviceCallback() {
                    @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { wake(); }
                    @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { wake(); }
                }, handler);
                routes.audio.addOnModeChangedListener(command -> handler.post(command), mode -> wake());
            }
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) { wake(); }
            };
            IntentFilter filter = new IntentFilter("android.bluetooth.adapter.action.STATE_CHANGED");
            filter.addAction("android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED");
            filter.addAction("android.bluetooth.action.LE_AUDIO_ACTIVE_DEVICE_CHANGED");
            filter.addAction("android.bluetooth.device.action.ACL_DISCONNECTED");
            filter.addAction("android.intent.action.USER_SWITCHED");
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED);
            } else context.registerReceiver(receiver, filter, null, handler);
        } catch (Throwable error) { MLog.w("auto mono route observer unavailable; using polling", error); }
    }

    private volatile boolean observerUnavailable;

    private void observeUserSetting() {
        if (savedObserver == null || user < 0) { observerUnavailable = true; return; }
        if (observedUser == user && !observerUnavailable) return;
        if (observedUser >= 0) {
            try { context.getContentResolver().unregisterContentObserver(savedObserver); }
            catch (Throwable ignored) {}
        }
        observedUser = -1;
        try {
            if (user == android.os.Process.myUid() / 100000) {
                context.getContentResolver().registerContentObserver(
                        Settings.System.getUriFor(SystemMonoAudio.SAVED_MONO), false, savedObserver);
            } else {
                Method register = android.content.ContentResolver.class.getMethod("registerContentObserver",
                        android.net.Uri.class, boolean.class, ContentObserver.class, int.class);
                register.invoke(context.getContentResolver(), Settings.System.getUriFor(SystemMonoAudio.SAVED_MONO),
                        false, savedObserver, user);
            }
            observedUser = user;
            observerUnavailable = false;
        } catch (Throwable error) {
            observerUnavailable = true;
            MLog.w("auto mono user preference observer unavailable", error);
        }
    }

    private void loadUser() {
        int next = audio.currentUser();
        if (next == user) return;
        pcm.clearUser();
        override.update(false);
        user = next;
        reports.clear();
        source.reset();
        views.clear();
        suspendedSessions.clear();
        candidateKey = "";
        enabled.clear();
        if (user >= 0) {
            Set<String> saved = preferences.getStringSet("enabled_u" + user, Collections.emptySet());
            for (String value : saved) {
                String mac = AutoMonoIpc.normalizeMac(value);
                if (mac != null && enabled.size() < 128) enabled.add(mac);
            }
        }
        observeUserSetting();
    }

    private void receive(Intent intent, TrustedBroadcasts.SenderIdentity sender) {
        try {
            if (intent == null || !AutoMonoIpc.REQUEST.equals(intent.getAction())
                    || intent.getIntExtra("version", 0) != AutoMonoIpc.VERSION) return;
            loadUser();
            long now = SystemClock.elapsedRealtime();
            long sentAt = intent.getLongExtra("sent_at", 0);
            if (!AutoMonoIpc.recent(sentAt, now) || user < 0
                    || intent.getIntExtra("user", -1) != user) return;
            if (TrustedBroadcasts.supportsSenderIdentity() && sender.uid / 100000 != user) return;
            String op = intent.getStringExtra("op");
            String mac = AutoMonoIpc.normalizeMac(intent.getStringExtra("mac"));
            long client = intent.getLongExtra("client", 0);
            long request = intent.getLongExtra("request", 0);
            if ("query".equals(op)) {
                reconcile();
                reply(mac, client, request);
                return;
            }
            if ("set".equals(op)) {
                if (mac == null || client <= 0 || request <= 0) return;
                long issuedAt = intent.getLongExtra("config_at", 0L);
                long nowNanos = SystemClock.elapsedRealtimeNanos();
                if (issuedAt <= 0 || issuedAt > nowNanos) return;
                if (nowNanos - issuedAt > AutoMonoIpc.LEASE_MS * 1_000_000L) {
                    reconcile(); reply(mac, client, request); return;
                }
                int boot = Settings.Global.getInt(context.getContentResolver(), "boot_count", -1);
                String stampKey = "config_at_u" + user + "_" + mac;
                long last = boot >= 0 && preferences.getInt(stampKey + "_boot", -2) == boot
                        ? preferences.getLong(stampKey, 0L) : 0L;
                if (issuedAt <= last) {
                    // A transport retry must not undo a newer user choice or manual suspension.
                    reconcile(); reply(mac, client, request); return;
                }
                boolean want = intent.getBooleanExtra("enabled", false);
                Set<String> next = new HashSet<>(enabled);
                if (want) next.add(mac); else next.remove(mac);
                boolean committed = next.size() <= 128 && preferences.edit()
                        .putStringSet("enabled_u" + user, next)
                        .putInt(stampKey + "_boot", boot).putLong(stampKey, issuedAt).commit();
                if (committed) {
                    enabled.clear(); enabled.addAll(next);
                    suspendedSessions.remove(mac);
                }
                reconcile();
                if (!committed) views.put(mac, new View("unavailable", "journal_failed"));
                reply(mac, client, request);
                wake();
                return;
            }
            if (!"wear".equals(op) && !"hello".equals(op)) return;
            long previousProducer = source.producer();
            if (!source.accept(client, intent.getLongExtra("sequence", 0), sentAt, now)) return;
            if (previousProducer != source.producer()) {
                reports.clear();
                suspendedSessions.clear();
                candidateKey = "";
                override.update(false);
            }
            hookReady = intent.getBooleanExtra("hook_ready", false);
            if ("hello".equals(op)) {
                reply(null, client, request);
            } else if (mac != null) {
                Report report = new Report(intent, now);
                if (AutoMonoLease.validReport(report.connected, report.session, report.connectedAt,
                        report.reportedAt, report.known, report.ear, report.box, sentAt)) {
                    if (reports.size() < 128 || reports.containsKey(mac)) reports.put(mac, report);
                } else {
                    reports.remove(mac);
                    views.put(mac, new View("waiting_state", "state_conflict"));
                }
            }
            wake();
        } catch (Throwable error) {
            MLog.w("auto mono request failed", error);
            override.update(false);
        }
    }

    private void wake() {
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    private boolean masterEnabled() {
        try { return module.getRemotePreferences("module_prefs").getBoolean("enabled", true); }
        catch (Throwable ignored) { return false; }
    }

    private void reconcile() {
        try {
            loadUser();
            long now = SystemClock.elapsedRealtime();
            boolean master = masterEnabled();
            Set<String> all = new HashSet<>(reports.keySet());
            all.addAll(enabled);
            all.addAll(views.keySet());
            Map<String, View> next = new HashMap<>();
            ArrayList<String> candidates = new ArrayList<>();
            for (String mac : all) {
                Report report = reports.get(mac);
                View view;
                if (!enabled.contains(mac)) view = new View("off", "");
                else if (!master) view = new View("suspended", "master_disabled");
                else if (observerUnavailable) view = new View("unavailable", "audio_api_unavailable");
                else if (!hookReady) view = new View("unavailable", "hook_unavailable");
                else if (report == null || !report.connected) view = new View("waiting_state", "disconnected");
                else if (now < report.received || now - report.received > AutoMonoIpc.LEASE_MS) view = new View("suspended", "host_timeout");
                else if (suspendedSessions.getOrDefault(mac, -1L) == report.session) view = new View("suspended", "user_override");
                else if (report.known != 3) view = new View("waiting_state", "unknown_ears");
                else if (report.ear == 3) view = new View("stereo", "both_ears");
                else if (report.ear == 0) view = new View("stereo", "neither_ear");
                else {
                    String reason = routes.match(mac);
                    if (!reason.isEmpty()) view = new View("call_active".equals(reason) ? "suspended" : "waiting_route", reason);
                    else {
                        candidates.add(mac);
                        view = new View("waiting_state", "debouncing");
                    }
                }
                next.put(mac, view);
            }
            String candidate = candidates.size() == 1 ? candidates.get(0) : null;
            if (candidates.size() > 1) {
                for (String mac : candidates) next.put(mac, new View("waiting_route", "multiple_outputs"));
            }
            Report report = candidate == null ? null : reports.get(candidate);
            String key = candidate == null ? "" : candidate + ":" + source.producer() + ":" + report.session;
            if (!key.equals(candidateKey)) {
                candidateKey = key; candidateSince = now;
            }
            boolean desired = candidate != null && now - candidateSince >= AutoMonoIpc.DEBOUNCE_MS;
            // System mono remains solely for recovery of preview.1-3 ownership. New playback
            // mixing is performed by the player PCM hooks; never toggle the global flag here.
            String audioMode = "off", audioReason = "";
            if (recoveryPending || override.isOwned()) {
                MonoOverride.Result recovered = override.recover();
                recoveryPending = recovered.owned || "journal_failed".equals(recovered.reason);
                if (recoveryPending) { audioMode = "restoring"; audioReason = recovered.reason; }
            }
            PcmMonoCoordinator.Result mixed = pcm.update(desired && !recoveryPending, key,
                    desired ? routes.outputDeviceIds(candidate) : new int[0], now);
            if (desired) next.put(candidate, new View(mixed.mode, mixed.reason));
            if (recoveryPending) {
                for (String mac : all) next.put(mac, new View("restoring", audioReason));
            }
            if (all.isEmpty() && !audioMode.equals(lastAudioMode)
                    && ("restoring".equals(lastAudioMode) || "restoring".equals(audioMode))) {
                MLog.event("mono.state", "mac", "global", "enabled", false,
                        "mode", audioMode, "reason", audioReason, "owned", override.isOwned());
            }
            lastAudioMode = audioMode;
            lastAudioReason = audioReason;
            for (String mac : all) {
                View view = next.get(mac);
                View previous = views.put(mac, view);
                Report state = reports.get(mac);
                String signature = view.mode + ":" + view.reason + ":" + enabled.contains(mac)
                        + ":" + (state == null ? "none" : state.known + ":" + state.ear + ":" + state.box);
                if (previous == null || !signature.equals(previous.signature)) {
                    view.signature = signature;
                    MLog.event("mono.state", "mac", AutoMonoIpc.redact(mac), "enabled", enabled.contains(mac),
                            "mode", view.mode, "reason", view.reason,
                            "known", state == null ? 0 : state.known, "ear", state == null ? 0 : state.ear,
                            "box", state == null ? 0 : state.box, "owned", override.isOwned(), "backend", "player_pcm");
                    reply(mac, 0L, 0L);
                } else view.signature = previous.signature;
            }
        } catch (Throwable error) {
            MLog.w("auto mono reconciliation failed", error);
            pcm.update(false, "", new int[0], SystemClock.elapsedRealtime());
            MonoOverride.Result result = override.update(false);
            recoveryPending = result.owned;
        }
    }

    private void reply(String mac, long client, long request) {
        Intent intent = new Intent(AutoMonoIpc.STATUS).setPackage(AutoMonoIpc.HOST);
        intent.putExtra("version", AutoMonoIpc.VERSION);
        intent.putExtra("server", server);
        intent.putExtra("revision", ++revision);
        intent.putExtra("reply_client", client);
        intent.putExtra("reply_request", request);
        intent.putExtra("mac", mac == null ? "" : mac);
        intent.putExtra("enabled_devices", enabled.toArray(new String[0]));
        intent.putExtra("enabled", enabled.contains(mac));
        intent.putExtra("hook_ready", hookReady);
        View view = views.get(mac);
        if (view == null) view = new View(enabled.contains(mac) ? "waiting_state" : "off", "disconnected");
        if ("restoring".equals(lastAudioMode)) view = new View("restoring", lastAudioReason);
        intent.putExtra("mode", view.mode);
        intent.putExtra("reason", view.reason);
        Report report = reports.get(mac);
        intent.putExtra("known", report == null ? 0 : report.known);
        intent.putExtra("ear", report == null ? 0 : report.ear);
        intent.putExtra("box", report == null ? 0 : report.box);
        Context target = userContext();
        if (target != null) TrustedBroadcasts.send(target, intent);
    }

    private Context userContext() {
        if (user == android.os.Process.myUid() / 100000) return context;
        try {
            Object handle = android.os.UserHandle.class.getMethod("of", int.class).invoke(null, user);
            return (Context) Context.class.getMethod("createContextAsUser", android.os.UserHandle.class, int.class)
                    .invoke(context, handle, 0);
        } catch (Throwable ignored) { return null; }
    }

    private static final class View {
        final String mode, reason;
        String signature = "";
        View(String mode, String reason) { this.mode = mode; this.reason = reason; }
    }

    private static final class Report {
        final boolean connected;
        final long session, connectedAt, reportedAt, received;
        final int known, ear, box;
        Report(Intent intent, long now) {
            connected = intent.getBooleanExtra("connected", false);
            session = intent.getLongExtra("session", 0);
            connectedAt = intent.getLongExtra("connected_at", 0);
            reportedAt = intent.getLongExtra("reported_at", 0);
            known = intent.getIntExtra("known", 0);
            ear = intent.getIntExtra("ear", 0);
            box = intent.getIntExtra("box", 0);
            received = now;
        }
    }
}
