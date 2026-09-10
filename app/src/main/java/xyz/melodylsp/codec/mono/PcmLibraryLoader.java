package xyz.melodylsp.codec.mono;

/** Load extracted module code before consulting the module class loader's APK-only search path. */
final class PcmLibraryLoader {
    static final String NAME = "melody_pcm_mono";
    static final String FILENAME = "lib" + NAME + ".so";

    interface Loader {
        void loadPath(String path) throws Throwable;
        void loadName(String name) throws Throwable;
    }

    static final class Result {
        final boolean loaded;
        final String source, path, error;
        Result(boolean loaded, String source, String path, String error) {
            this.loaded = loaded;
            this.source = source;
            this.path = path;
            this.error = error;
        }
    }

    static Result load(String moduleNativeDirectory, Loader loader) {
        String path = modulePath(moduleNativeDirectory);
        String pathError = "module_native_dir_unavailable";
        if (path != null) {
            try {
                loader.loadPath(path);
                return new Result(true, "module_native_dir", path, "");
            } catch (Throwable failure) {
                pathError = describe(failure);
            }
        }
        try {
            // Covers module loaders with their own native path and uncompressed APK entries.
            loader.loadName(NAME);
            return new Result(true, "loadLibrary", "", "");
        } catch (Throwable failure) {
            return new Result(false, "none", path == null ? "" : path,
                    "module_path=" + pathError + "|loadLibrary=" + describe(failure));
        }
    }

    static String modulePath(String directory) {
        if (directory == null || !directory.startsWith("/") || directory.indexOf('\0') >= 0) return null;
        return directory + (directory.endsWith("/") ? "" : "/") + FILENAME;
    }

    static String describe(Throwable failure) {
        if (failure == null) return "unknown";
        Throwable cause = failure;
        for (int depth = 0; depth < 8 && cause.getCause() != null && cause.getCause() != cause; depth++) {
            cause = cause.getCause();
        }
        String message = cause.getClass().getSimpleName() + ":" + String.valueOf(cause.getMessage());
        return compact(message, 700);
    }

    static String compact(String value, int limit) {
        if (value == null || value.isEmpty()) return "none";
        String message = value.replaceAll("[\\s\\p{Cntrl}]+", "_");
        // Keep both the searched path and the linker reason at the end of long loader messages.
        if (message.length() <= limit) return message;
        int head = (limit - 3) / 2;
        return message.substring(0, head) + "..."
                + message.substring(message.length() - (limit - 3 - head));
    }

    private PcmLibraryLoader() {}
}
