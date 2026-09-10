package xyz.melodylsp.codec.mono;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import xyz.melodylsp.codec.MelodyCodecLspEntry;
import xyz.melodylsp.codec.util.MLog;

/** Reads only confirmed protocol updates; BLE advertisements and cached DTOs are excluded. */
public final class WearStateHookInstaller implements AutoMonoHostClient.Producer {
    private static final String DEVICE = "com.oplus.melody.btsdk.api.data.DeviceInfo";
    private static final String CORE = "com.oplus.melody.btsdk.multidevice.HeadsetCoreService";
    private static final String EVENT = "com.oplus.melody.btsdk.api.data.BluetoothReceiveData";
    private static final int EAR_STATUS_EVENT = 0x100016;
    private final MelodyCodecLspEntry module;
    private final ClassLoader loader;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EarWearStateTracker tracker = new EarWearStateTracker();
    private final WearEventOrder eventOrder = new WearEventOrder();
    private final AtomicLong orderCounter = new AtomicLong();
    private final Map<String, Session> sessions = new HashMap<>();
    private final Set<String> enabled = new HashSet<>();
    private final ArrayList<Packet> pending = new ArrayList<>();
    private WeakReference<Object> core = new WeakReference<>(null);
    private AutoMonoHostClient client;
    private Method statusQuery;
    private boolean ready, primary, queryVersionKnown;
    private long nextSession;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (client == null || enabled.isEmpty()) return;
            for (String mac : new HashSet<>(enabled)) {
                client.sendSnapshot(tracker.snapshot(mac));
            }
            handler.postDelayed(this, AutoMonoIpc.HEARTBEAT_MS);
        }
    };

    public WearStateHookInstaller(MelodyCodecLspEntry module, ClassLoader loader) {
        this.module = module;
        this.loader = loader;
    }

    public void install() {
        primary = AutoMonoIpc.HOST.equals(Application.getProcessName());
        if (!primary) return;
        try {
            Class<?> deviceClass = Class.forName(DEVICE, false, loader);
            Method setter = deviceClass.getDeclaredMethod("setStatusInfo", List.class);
            module.hook(setter).intercept(chain -> {
                Object result = chain.proceed();
                try { capture(chain.getThisObject(), chain.getArgs().get(0)); }
                catch (Throwable error) { MLog.w("wear status capture failed", error); }
                return result;
            });
            Class<?> coreClass = Class.forName(CORE, false, loader);
            Class<?> eventClass = Class.forName(EVENT, false, loader);
            Method dispatch = null;
            for (Method method : coreClass.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == void.class
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == eventClass) {
                    if (dispatch != null) throw new NoSuchMethodException("ambiguous wear dispatch");
                    dispatch = method;
                }
            }
            if (dispatch == null) throw new NoSuchMethodException("wear event dispatch");
            module.hook(dispatch).intercept(chain -> {
                try { confirm(chain.getArgs().get(0)); }
                catch (Throwable error) { MLog.w("wear protocol confirmation failed", error); }
                return chain.proceed();
            });
            for (Constructor<?> constructor : coreClass.getDeclaredConstructors()) {
                module.hook(constructor).intercept(chain -> {
                    Object result = chain.proceed();
                    core = new WeakReference<>(chain.getThisObject());
                    return result;
                });
            }
            try {
                statusQuery = coreClass.getDeclaredMethod("P", String.class);
                statusQuery.setAccessible(true);
            } catch (Throwable ignored) { statusQuery = null; }
            hookConnection(deviceClass, "setDeviceAclConnectState", "getDeviceAclConnectState");
            hookConnection(deviceClass, "setDeviceConnectState", "getDeviceConnectState");
            hookConnection(deviceClass, "setSppOverGattConnectionState", "getSppOverGattConnectionState");
            Method aggregate = deviceClass.getDeclaredMethod("updateConnected", String.class);
            module.hook(aggregate).intercept(chain -> {
                Object device = chain.getThisObject();
                Boolean before = null;
                try { before = bool(device, "isConnected"); } catch (Throwable ignored) {}
                Object result = chain.proceed();
                try {
                    boolean after = bool(device, "isConnected");
                    if (before != null && before != after) connectionBoundary(device, after);
                } catch (Throwable error) { MLog.w("wear aggregate connection boundary failed", error); }
                return result;
            });
            ready = true;
        } catch (Throwable error) {
            MLog.w("auto mono wear hooks unavailable", error);
        }
    }

    public void attach(Context context) {
        if (!primary || client != null) return;
        client = AutoMonoHostClient.get(context);
        try {
            queryVersionKnown = context.getPackageManager().getPackageInfo(
                    AutoMonoIpc.HOST, 0).getLongVersionCode() == 16008003L;
        } catch (Throwable ignored) { queryVersionKnown = false; }
        client.attachProducer(this, ready);
        MLog.event("mono.hook", "ok", ready, "query", queryVersionKnown && statusQuery != null);
        // Adapter-off is a second boundary in addition to the official per-device setters.
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                if (intent != null && "android.bluetooth.adapter.action.STATE_CHANGED".equals(intent.getAction())
                        && intent.getIntExtra("android.bluetooth.adapter.extra.STATE", -1) != 12) {
                    for (String mac : new ArrayList<>(sessions.keySet())) {
                        long order = orderCounter.incrementAndGet();
                        eventOrder.boundary(mac, order, 0L, false);
                        disconnect(mac);
                    }
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter("android.bluetooth.adapter.action.STATE_CHANGED");
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter, null, handler);
            }
        } catch (Throwable error) { MLog.w("wear adapter observer unavailable", error); }
    }

    private void hookConnection(Class<?> cls, String setter, String getter) throws Exception {
        Method method = cls.getDeclaredMethod(setter, int.class);
        module.hook(method).intercept(chain -> {
            Object device = chain.getThisObject();
            int before = integer(device, getter, -1);
            Object result = chain.proceed();
            try {
                int after = integer(device, getter, -1);
                if (before != after && (before == 2 || after == 2)) {
                    boolean connected = bool(device, "isConnected") || after == 2;
                    connectionBoundary(device, connected);
                }
            } catch (Throwable error) { MLog.w("wear connection boundary failed", error); }
            return result;
        });
    }

    private void connectionBoundary(Object device, boolean connected) throws Exception {
        String mac = address(device);
        if (mac == null) return;
        long now = SystemClock.elapsedRealtime();
        long marker = longValue(device, "getConnectedChangedMillis", 0L);
        long order = orderCounter.incrementAndGet();
        if (!eventOrder.boundary(mac, order, marker, connected)) return;
        handler.post(() -> {
            if (!eventOrder.currentBoundary(mac, order)) return;
            if (connected) startSession(mac, now, marker, order);
            else disconnect(mac);
        });
    }

    private void capture(Object device, Object value) throws Exception {
        String mac = address(device);
        if (mac == null) return;
        long time = SystemClock.elapsedRealtime();
        long order = orderCounter.incrementAndGet();
        long thread = Thread.currentThread().getId();
        int reported = 0, ear = 0, box = 0;
        boolean invalid = !(value instanceof List<?>);
        if (value instanceof List<?>) {
            List<?> values = (List<?>) value;
            invalid = values.size() > 16;
            if (!invalid) {
                for (Object item : values) {
                    if (item == null) { invalid = true; break; }
                    int type = integer(item, "getDeviceType", -1);
                    int side = type == 1 ? 1 : type == 2 ? 2 : 0;
                    if (side == 0) continue;
                    if ((reported & side) != 0) { invalid = true; break; }
                    reported |= side;
                    if (bool(item, "isInEar")) ear |= side;
                    if (bool(item, "isInBox")) box |= side;
                }
            }
        }
        if (invalid) reported = 4; // The tracker invalidates unsafe input instead of guessing.
        Packet packet = new Packet(device, mac, time, order, thread, reported, ear, box,
                bool(device, "isConnected"), longValue(device, "getConnectedChangedMillis", 0));
        synchronized (pending) {
            pending.removeIf(old -> old.device.get() == null
                    || (old.device.get() == device && old.thread == thread)
                    || time - old.time > 5_000L);
            if (pending.size() >= 32) pending.remove(0);
            pending.add(packet);
        }
    }

    private void confirm(Object event) throws Exception {
        if (integer(event, "getEventId", -1) != EAR_STATUS_EVENT) return;
        Object device = event.getClass().getMethod("getData").invoke(event);
        Packet packet = null;
        synchronized (pending) {
            for (int i = pending.size() - 1; i >= 0; i--) {
                if (pending.get(i).device.get() == device
                        && pending.get(i).thread == Thread.currentThread().getId()) {
                    packet = pending.remove(i);
                    break;
                }
            }
        }
        if (packet == null || SystemClock.elapsedRealtime() - packet.time > 5_000L) return;
        Packet confirmed = packet;
        handler.post(() -> accept(confirmed));
    }

    private void accept(Packet packet) {
        if (!ready || client == null) return;
        if (!packet.connected) {
            if (eventOrder.boundary(packet.mac, packet.order, packet.connectionMarker, false)) disconnect(packet.mac);
            return;
        }
        if (!eventOrder.accept(packet.mac, packet.order, packet.connectionMarker)) return;
        long packetBoundary = eventOrder.acceptedBoundaryOrder(packet.mac, packet.order);
        if (packetBoundary < 0) return;
        Session session = sessions.get(packet.mac);
        if (session == null || packetBoundary > session.boundaryOrder
                || (packet.connectionMarker > 0 && session.marker > 0
                && packet.connectionMarker > session.marker)) {
            startSession(packet.mac, packet.time, packet.connectionMarker, packetBoundary);
            session = sessions.get(packet.mac);
        } else if (session.marker == 0 && packet.connectionMarker > 0) {
            session.marker = packet.connectionMarker;
        }
        EarWearStateTracker.Snapshot snapshot = tracker.update(packet.mac, session.id, packet.time,
                packet.reported, packet.ear, packet.box);
        client.sendSnapshot(snapshot);
        MLog.event("mono.wear", "mac", AutoMonoIpc.redact(packet.mac),
                "session", session.id, "reported", packet.reported,
                "known", snapshot.knownMask, "ear", snapshot.inEarMask,
                "box", snapshot.inBoxMask, "state", snapshot.state().name(), "source", "protocol");
        if (enabled.contains(packet.mac) && !snapshot.isKnown()) scheduleQuery(packet.mac, session);
    }

    private void startSession(String mac, long time, long marker, long boundaryOrder) {
        Session session = new Session(++nextSession, marker, boundaryOrder);
        sessions.put(mac, session);
        tracker.beginSession(mac, session.id, time);
        if (client != null) client.sendSnapshot(tracker.snapshot(mac));
        if (enabled.contains(mac)) scheduleQuery(mac, session);
    }

    private void disconnect(String mac) {
        if (mac == null) return;
        sessions.remove(mac);
        tracker.endSession(mac);
        if (client != null) client.sendSnapshot(tracker.snapshot(mac));
    }

    @Override public void onEnabledDevices(Set<String> macs) {
        Set<String> newlyEnabled = new HashSet<>(macs);
        newlyEnabled.removeAll(enabled);
        enabled.clear();
        enabled.addAll(macs);
        for (String mac : newlyEnabled) {
            Session session = sessions.get(mac);
            if (session != null) {
                session.queryAttempts = 0;
                scheduleQuery(mac, session);
            } else {
                // An already-connected earbud may not have emitted a new event since startup.
                requestStatus(mac);
                handler.postDelayed(() -> {
                    if (enabled.contains(mac) && !sessions.containsKey(mac)) requestStatus(mac);
                }, 3_000L);
            }
        }
        handler.removeCallbacks(heartbeat);
        if (!enabled.isEmpty()) handler.post(heartbeat);
    }

    private void scheduleQuery(String mac, Session session) {
        if (session.queryScheduled || session.queryAttempts >= 3) return;
        session.queryScheduled = true;
        long delay = session.queryAttempts == 0 ? 600L : session.queryAttempts == 1 ? 2_000L : 5_000L;
        handler.postDelayed(() -> {
            session.queryScheduled = false;
            if (sessions.get(mac) != session || !enabled.contains(mac)) return;
            session.queryAttempts++;
            if (!tracker.snapshot(mac).isKnown()) {
                requestStatus(mac);
                scheduleQuery(mac, session);
            }
        }, delay);
    }

    private void requestStatus(String mac) {
        if (!ready || !queryVersionKnown || statusQuery == null) return;
        Object instance = core.get();
        if (instance == null) return;
        try {
            Field field = instance.getClass().getDeclaredField("mWorkHandler");
            field.setAccessible(true);
            Object work = field.get(instance);
            if (!(work instanceof Handler)) return;
            ((Handler) work).post(() -> {
                try {
                    statusQuery.invoke(instance, mac);
                    MLog.event("mono.wear.query", "mac", AutoMonoIpc.redact(mac));
                } catch (Throwable error) { MLog.w("wear state query unavailable", error); }
            });
        } catch (Throwable error) { MLog.w("wear query handler unavailable", error); }
    }

    private static String address(Object value) throws Exception {
        Object address = value.getClass().getMethod("getDeviceAddress").invoke(value);
        return address instanceof String ? AutoMonoIpc.normalizeMac((String) address) : null;
    }

    private static boolean bool(Object value, String method) throws Exception {
        Object result = value.getClass().getMethod(method).invoke(value);
        if (!(result instanceof Boolean)) throw new IllegalArgumentException(method);
        return (Boolean) result;
    }

    private static int integer(Object value, String method, int fallback) {
        try { return ((Number) value.getClass().getMethod(method).invoke(value)).intValue(); }
        catch (Throwable ignored) { return fallback; }
    }

    private static long longValue(Object value, String method, long fallback) {
        try { return ((Number) value.getClass().getMethod(method).invoke(value)).longValue(); }
        catch (Throwable ignored) { return fallback; }
    }

    private static final class Session {
        final long id;
        final long boundaryOrder;
        long marker;
        int queryAttempts;
        boolean queryScheduled;
        Session(long id, long marker, long boundaryOrder) {
            this.id = id; this.marker = marker; this.boundaryOrder = boundaryOrder;
        }
    }

    private static final class Packet {
        final WeakReference<Object> device;
        final String mac;
        final long time, order, thread, connectionMarker;
        final int reported, ear, box;
        final boolean connected;
        Packet(Object device, String mac, long time, long order, long thread, int reported, int ear, int box,
                boolean connected, long connectionMarker) {
            this.device = new WeakReference<>(device); this.mac = mac; this.time = time;
            this.order = order; this.thread = thread;
            this.reported = reported; this.ear = ear; this.box = box;
            this.connected = connected; this.connectionMarker = connectionMarker;
        }
    }
}
