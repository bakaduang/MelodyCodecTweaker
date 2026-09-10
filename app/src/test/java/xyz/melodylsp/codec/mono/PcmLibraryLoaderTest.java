package xyz.melodylsp.codec.mono;

import static org.junit.Assert.*;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class PcmLibraryLoaderTest {
    private static final String DIRECTORY = "/data/app/~~install/module-token/lib/arm64";
    private static final String PATH = DIRECTORY + "/libmelody_pcm_mono.so";

    private static final class FakeLoader implements PcmLibraryLoader.Loader {
        final List<String> calls = new ArrayList<>();
        Throwable pathFailure, nameFailure;
        @Override public void loadPath(String path) throws Throwable {
            calls.add(path);
            if (pathFailure != null) throw pathFailure;
        }
        @Override public void loadName(String name) throws Throwable {
            calls.add(name);
            if (nameFailure != null) throw nameFailure;
        }
    }

    @Test public void extractedModuleLibraryLoadsWhenCompressedApkCannotBeSearched() {
        FakeLoader loader = new FakeLoader();
        loader.nameFailure = new UnsatisfiedLinkError("class loader skipped compressed APK entry");
        PcmLibraryLoader.Result result = PcmLibraryLoader.load(DIRECTORY, loader);
        assertTrue(result.loaded);
        assertEquals("module_native_dir", result.source);
        assertEquals(PATH, result.path);
        assertEquals(List.of(PATH), loader.calls);
    }

    @Test public void modulePathFailureFallsBackToFrameworkLookup() {
        FakeLoader loader = new FakeLoader();
        loader.pathFailure = new UnsatisfiedLinkError("extracted file unavailable");
        PcmLibraryLoader.Result result = PcmLibraryLoader.load(DIRECTORY, loader);
        assertTrue(result.loaded);
        assertEquals("loadLibrary", result.source);
        assertEquals(List.of(PATH, PcmLibraryLoader.NAME), loader.calls);
    }

    @Test public void missingApplicationInfoStillAllowsNameLookup() {
        FakeLoader loader = new FakeLoader();
        assertTrue(PcmLibraryLoader.load(null, loader).loaded);
        assertEquals(List.of(PcmLibraryLoader.NAME), loader.calls);
    }

    @Test public void bothFailuresRetainTheModulePathAndOriginalLinkerReasons() {
        FakeLoader loader = new FakeLoader();
        loader.pathFailure = new InvocationTargetException(new UnsatisfiedLinkError("wrong ELF class"));
        loader.nameFailure = new UnsatisfiedLinkError("findLibrary returned null");
        PcmLibraryLoader.Result result = PcmLibraryLoader.load(DIRECTORY, loader);
        assertFalse(result.loaded);
        assertEquals("none", result.source);
        assertEquals(PATH, result.path);
        assertTrue(result.error.contains("module_path=UnsatisfiedLinkError:wrong_ELF_class"));
        assertTrue(result.error.contains("loadLibrary=UnsatisfiedLinkError:findLibrary_returned_null"));
    }

    @Test public void invalidDirectoryCannotTurnIntoRelativeCodeLoading() {
        FakeLoader loader = new FakeLoader();
        assertTrue(PcmLibraryLoader.load("lib/arm64", loader).loaded);
        assertEquals(List.of(PcmLibraryLoader.NAME), loader.calls);
        assertNull(PcmLibraryLoader.modulePath("/data/app/\0invalid"));
        assertEquals(PATH, PcmLibraryLoader.modulePath(DIRECTORY + "/"));
    }

    @Test public void longFeedbackKeepsSearchedPathAndFinalReasonInASingleBoundedToken() {
        String message = "searched=/data/app/module\n" + "middle ".repeat(500)
                + "\tlinker:cannot locate symbol\0end";
        String detail = PcmLibraryLoader.compact(message, 1_800);
        assertEquals(1_800, detail.length());
        assertTrue(detail.startsWith("searched=/data/app/module_"));
        assertTrue(detail.endsWith("_linker:cannot_locate_symbol_end"));
        assertFalse(detail.contains("\n"));
        assertFalse(detail.contains("\0"));
        assertEquals("none", PcmLibraryLoader.compact(null, 1_800));
    }
}
