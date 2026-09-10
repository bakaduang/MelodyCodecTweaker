package xyz.melodylsp.codec.mono;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Requires the actual media route; a paired or merely connected device is insufficient. */
final class MonoRouteResolver {
    private final Context context;
    final AudioManager audio;
    private final AudioAttributes media = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    private BluetoothAdapter adapter;
    private volatile Object leProxy;
    private boolean leRequested;

    MonoRouteResolver(Context context) {
        this.context = context;
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        try {
            BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (manager != null) adapter = manager.getAdapter();
        } catch (Throwable ignored) {}
    }

    String match(String mac) {
        try {
            if (audio == null || adapter == null || !adapter.isEnabled()) return "disconnected";
            if (audio.getMode() != AudioManager.MODE_NORMAL) return "call_active";
            List<?> devices = mediaDevices();
            if (devices == null || devices.isEmpty()) return "route_unknown";
            if (devices.size() != 1) return "multiple_outputs";
            Object device = devices.get(0);
            int type = ((Number) device.getClass().getMethod("getType").invoke(device)).intValue();
            boolean a2dp = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
            boolean le = type == AudioDeviceInfo.TYPE_BLE_HEADSET;
            if (!a2dp && !le) return "other_route";
            String address = (String) device.getClass().getMethod("getAddress").invoke(device);
            String routed = AutoMonoIpc.normalizeMac(address);
            if (mac.equals(routed)) return "";
            List<BluetoothDevice> active = activeDevices(le ? 22 : BluetoothProfile.A2DP);
            if (active.isEmpty()) return "route_unknown";
            if (a2dp) {
                if (routed != null) return "other_route";
                return active.size() == 1 && mac.equals(AutoMonoIpc.normalizeMac(active.get(0).getAddress()))
                        ? "" : "other_route";
            }
            // LE may expose another member's address. Accept only a verified common group.
            ensureLeProxy();
            int group = groupId(mac);
            if (group < 0) return "route_unknown";
            if (routed != null && groupId(routed) != group) return "other_route";
            for (BluetoothDevice member : active) {
                if (groupId(member.getAddress()) != group) return "other_route";
            }
            return "";
        } catch (Throwable ignored) { return "route_unknown"; }
    }

    private List<?> mediaDevices() {
        for (String name : new String[]{"getAudioDevicesForAttributes", "getDevicesForAttributes"}) {
            try {
                Method method = AudioManager.class.getMethod(name, AudioAttributes.class);
                method.setAccessible(true);
                Object value = method.invoke(audio, media);
                if (value instanceof List<?>) return (List<?>) value;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Physical device port IDs let the player reject explicitly routed non-headset tracks. */
    int[] outputDeviceIds(String mac) {
        if (!match(mac).isEmpty() || audio == null) return new int[0];
        try {
            ArrayList<AudioDeviceInfo> exact = new ArrayList<>();
            ArrayList<AudioDeviceInfo> anonymousA2dp = new ArrayList<>();
            for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                String address = AutoMonoIpc.normalizeMac(device.getAddress());
                if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                    if (mac.equals(address)) exact.add(device);
                    else if (address == null) anonymousA2dp.add(device);
                } else if (device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    if (mac.equals(address) || (address != null && groupId(mac) >= 0
                            && groupId(mac) == groupId(address))) exact.add(device);
                }
            }
            if (exact.size() == 1) return new int[]{exact.get(0).getId()};
            if (exact.isEmpty() && anonymousA2dp.size() == 1) {
                return new int[]{anonymousA2dp.get(0).getId()};
            }
        } catch (Throwable ignored) {}
        return new int[0];
    }

    private List<BluetoothDevice> activeDevices(int profile) {
        ArrayList<BluetoothDevice> result = new ArrayList<>();
        try {
            Object values = adapter.getClass().getMethod("getActiveDevices", int.class).invoke(adapter, profile);
            if (values instanceof Iterable<?>) {
                for (Object item : (Iterable<?>) values) if (item instanceof BluetoothDevice) result.add((BluetoothDevice) item);
            }
        } catch (Throwable ignored) {}
        return result;
    }

    private void ensureLeProxy() {
        if (leRequested || adapter == null) return;
        leRequested = true;
        try {
            if (!adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) { leProxy = proxy; }
                @Override public void onServiceDisconnected(int profile) { leProxy = null; leRequested = false; }
            }, 22)) leRequested = false;
        } catch (Throwable ignored) { leRequested = false; }
    }

    private int groupId(String mac) {
        Object proxy = leProxy;
        String address = AutoMonoIpc.normalizeMac(mac);
        if (proxy == null || address == null) return -1;
        try {
            Object id = proxy.getClass().getMethod("getGroupId", BluetoothDevice.class)
                    .invoke(proxy, adapter.getRemoteDevice(address));
            return ((Number) id).intValue();
        } catch (Throwable ignored) { return -1; }
    }
}
