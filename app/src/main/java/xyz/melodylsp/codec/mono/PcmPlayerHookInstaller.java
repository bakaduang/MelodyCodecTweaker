package xyz.melodylsp.codec.mono;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.lang.reflect.Method;

import xyz.melodylsp.codec.MelodyCodecLspEntry;
import xyz.melodylsp.codec.util.MLog;
import xyz.melodylsp.codec.util.TrustedBroadcasts;

/** Hooks both players' main and native playback processes, before they create AudioTracks. */
public final class PcmPlayerHookInstaller {
    private static PcmPlayerHookInstaller instance;
    private final Context context;
    private final Handler handler;
    private final long client = SystemClock.elapsedRealtimeNanos();
    private final PcmCommandLease lease = new PcmCommandLease();
    private long sequence, expiresAt, generation;
    private long nextInstallAt;
    private int installAttempts = 1;
    private boolean controlling;
    private final Runnable pulse = this::poll;

    public static boolean supportsPackage(String packageName) { return PcmMonoIpc.isPlayer(packageName); }

    public static void install(MelodyCodecLspEntry module) {
        if (Build.VERSION.SDK_INT < 34) return;
        NativePcmMono.prepare(module);
        NativePcmMono.install();
        try {
            Method onCreate = Application.class.getMethod("onCreate");
            module.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    if (chain.getThisObject() instanceof Context) {
                        synchronized (PcmPlayerHookInstaller.class) {
                            if (instance == null) instance = new PcmPlayerHookInstaller(
                                    ((Context) chain.getThisObject()).getApplicationContext());
                        }
                    }
                } catch (Throwable failure) { MLog.w("PCM player startup failed", failure); }
                return result;
            });
        } catch (Throwable failure) { MLog.w("PCM player application hook failed", failure); }
    }

    private PcmPlayerHookInstaller(Context context) {
        this.context = context;
        HandlerThread thread = new HandlerThread("Melody-player-mono");
        thread.start();
        handler = new Handler(thread.getLooper());
        BroadcastReceiver control = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                TrustedBroadcasts.SenderIdentity sender = TrustedBroadcasts.captureSender(this);
                if (!sender.available || sender.uid % 100000 != 1002
                        || !AutoMonoIpc.BLUETOOTH.equals(sender.packageName)) return;
                receive(intent);
            }
        };
        boolean registered = TrustedBroadcasts.registerExportedReceiver(context, control,
                new IntentFilter(PcmMonoIpc.CONTROL),
                TrustedBroadcasts.PERMISSION_BLUETOOTH_PRIVILEGED, handler);
        MLog.eventLogOnly("mono.pcm.player", "package", context.getPackageName(),
                "process", Application.getProcessName(), "receiver", registered,
                "backend", NativePcmMono.status());
        handler.post(pulse);
    }

    private void poll() {
        long now = SystemClock.elapsedRealtime();
        if (controlling && now >= expiresAt) {
            controlling = false;
            NativePcmMono.configure(false, 0, generation, new int[0]);
        }
        if (NativePcmMono.snapshot()[0] == 0L && installAttempts < 4 && now >= nextInstallAt) {
            NativePcmMono.install();
            installAttempts++;
            nextInstallAt = now + 10_000L;
        }
        report();
        handler.postDelayed(pulse, controlling ? 1_000L : 3_000L);
    }

    private void receive(Intent intent) {
        try {
            if (intent == null || !PcmMonoIpc.CONTROL.equals(intent.getAction())
                    || intent.getIntExtra("version", 0) != PcmMonoIpc.VERSION
                    || intent.getLongExtra("client", 0) != client
                    || intent.getIntExtra("user", -1) != android.os.Process.myUid() / 100000) return;
            long now = SystemClock.elapsedRealtime();
            long until = intent.getLongExtra("expires_at", 0);
            long nextGeneration = intent.getLongExtra("generation", 0);
            int[] devices = intent.getIntArrayExtra("devices");
            boolean enabled = intent.getBooleanExtra("enabled", false);
            if (nextGeneration <= 0 || devices == null || devices.length > 16
                    || (enabled && devices.length != 1)) return;
            for (int device : devices) if (device <= 0) return;
            if (!lease.accept(intent.getLongExtra("server", 0), intent.getLongExtra("revision", 0),
                    intent.getLongExtra("sent_at", 0), until, now)) return;
            boolean changed = generation != nextGeneration || controlling != enabled;
            generation = nextGeneration;
            expiresAt = until;
            controlling = enabled;
            NativePcmMono.configure(enabled, until, generation, devices);
            if (changed) {
                handler.removeCallbacks(pulse);
                handler.post(pulse);
            }
        } catch (Throwable failure) {
            controlling = false;
            NativePcmMono.configure(false, 0, generation, new int[0]);
            MLog.eventLogOnly("mono.pcm.control", "error", MLog.compactThrowable(failure));
        }
    }

    private void report() {
        long[] stats = NativePcmMono.snapshot();
        Intent intent = new Intent(PcmMonoIpc.REQUEST).setPackage(AutoMonoIpc.BLUETOOTH);
        intent.putExtra("version", PcmMonoIpc.VERSION);
        intent.putExtra("client", client);
        intent.putExtra("sequence", ++sequence);
        intent.putExtra("sent_at", SystemClock.elapsedRealtime());
        intent.putExtra("pid", android.os.Process.myPid());
        intent.putExtra("backend", NativePcmMono.status());
        intent.putExtra("native_detail", NativePcmMono.diagnostic());
        intent.putExtra("stats", stats);
        TrustedBroadcasts.send(context, intent);
    }
}
