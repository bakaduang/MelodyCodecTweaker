package xyz.melodylsp.codec.mono;

import android.content.pm.ApplicationInfo;
import android.os.Build;

import xyz.melodylsp.codec.MelodyCodecLspEntry;
import xyz.melodylsp.codec.util.MLog;

/** No audio state is changed by loading or installing the native hooks. */
public final class NativePcmMono {
    private static boolean loaded;
    private static String status = "not_loaded";
    private static String moduleNativeDirectory;
    private static String moduleInfoError = "";
    private static String source = "none", loadedPath = "", error = "", stage = "not_started";

    static synchronized void prepare(MelodyCodecLspEntry module) {
        moduleNativeDirectory = null;
        try {
            ApplicationInfo info = module.getModuleApplicationInfo();
            moduleNativeDirectory = info == null ? null : info.nativeLibraryDir;
            moduleInfoError = info == null ? "module_info_null" : "";
        } catch (Throwable failure) {
            moduleInfoError = PcmLibraryLoader.describe(failure);
        }
    }

    static synchronized String install() {
        if (!supportsPlatform(Build.VERSION.SDK_INT, android.os.Process.is64Bit())) {
            stage = "platform";
            error = "sdk=" + Build.VERSION.SDK_INT + ";process64=" + android.os.Process.is64Bit();
            status = "unsupported_platform";
            return status;
        }
        if (!loaded) {
            stage = "load";
            PcmLibraryLoader.Result result = PcmLibraryLoader.load(moduleNativeDirectory,
                    new PcmLibraryLoader.Loader() {
                        @Override public void loadPath(String path) { System.load(path); }
                        @Override public void loadName(String name) { System.loadLibrary(name); }
                    });
            loaded = result.loaded;
            source = result.source;
            loadedPath = result.path;
            error = result.error;
            if (!loaded) {
                status = "native_load_failed";
                MLog.eventLogOnly("mono.pcm.load", "error", diagnostic());
                return status;
            }
            MLog.eventLogOnly("mono.pcm.load", "source", source, "path", loadedPath, "ok", true);
        }
        stage = "install";
        try {
            status = nativeInstall(Build.VERSION.SDK_INT);
            error = "";
        } catch (Throwable failure) {
            status = failure instanceof UnsatisfiedLinkError ? "native_binding_failed" : "native_install_failed";
            error = PcmLibraryLoader.describe(failure);
            MLog.eventLogOnly("mono.pcm.install", "error", diagnostic());
        }
        return status;
    }

    static synchronized void configure(boolean enabled, long expiresAt, long generation, int[] devices) {
        if (!loaded) return;
        try { nativeConfigure(enabled, expiresAt, generation, devices); }
        catch (Throwable failure) {
            status = "native_api_unavailable";
            stage = "configure";
            error = PcmLibraryLoader.describe(failure);
        }
    }

    static synchronized long[] snapshot() {
        if (!loaded) return new long[9];
        try {
            long[] value = nativeSnapshot();
            return value != null && value.length == 9 ? value : new long[9];
        } catch (Throwable failure) {
            status = "native_binding_failed";
            stage = "snapshot";
            error = PcmLibraryLoader.describe(failure);
            return new long[9];
        }
    }

    static synchronized String status() { return status; }

    static boolean supportsPlatform(int sdk, boolean process64Bit) {
        // Native installation still requires a complete, recognized AudioTrack ABI.
        return process64Bit && (sdk == 36 || sdk == 37);
    }

    static synchronized String diagnostic() {
        return "stage=" + stage + ";loaded=" + loaded + ";source=" + source
                + ";sdk=" + Build.VERSION.SDK_INT + ";process64=" + android.os.Process.is64Bit()
                + ";path=" + loadedPath + ";module_info=" + (moduleInfoError.isEmpty() ? "ok" : moduleInfoError)
                + ";error=" + (error.isEmpty() ? "none" : error);
    }

    public static native String nativeInstall(int sdk);
    public static native void nativeConfigure(boolean enabled, long expiresAtMs, long generation, int[] outputDeviceIds);
    public static native long[] nativeSnapshot();

    private NativePcmMono() {}
}
