package xyz.melodylsp.codec.diag;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class NativeFeedbackArchiveTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();

    @Test public void audioClientEvidenceIsActuallyIncludedInFeedbackZip() throws Exception {
        NativeLibraryCollector.CollectionResult result = new NativeLibraryCollector.CollectionResult();
        byte[] audio = {0x7f, 'E', 'L', 'F', 2, 1, 0, 7};
        for (String name : new String[]{NativeLibraryCollector.LIB_BLUETOOTH_JNI,
                NativeLibraryCollector.LIB_LHDC_ENC, NativeLibraryCollector.LIB_AUDIO_CLIENT}) {
            File file = directory.newFile(name);
            Files.write(file.toPath(), audio);
            result.libraries.put(name, new NativeLibraryCollector.CollectedLibrary(name,
                    "/system/lib64/" + name, file, audio.length, "fixture-sha256"));
        }
        assertTrue(result.succeeded());
        Map<String, byte[]> entries = archive(result);
        assertArrayEquals(audio, entries.get("native/libaudioclient.so"));
        assertArrayEquals(audio, entries.get("native/libbluetooth_jni.so"));
        assertArrayEquals(audio, entries.get("native/liblhdcv5BT_enc.so"));
        String manifest = new String(entries.get("native/manifest.txt"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("libaudioclient.so\n  source=/system/lib64/libaudioclient.so"));
        assertTrue(manifest.contains("sha256=fixture-sha256"));
    }

    @Test public void unavailableOptionalLibraryIsRecordedAsMissing() throws Exception {
        Map<String, byte[]> entries = archive(new NativeLibraryCollector.CollectionResult());
        assertFalse(entries.containsKey("native/libaudioclient.so"));
        String manifest = new String(entries.get("native/manifest.txt"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("libaudioclient.so=missing\n"));
    }

    private static Map<String, byte[]> archive(NativeLibraryCollector.CollectionResult result)
            throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            FeedbackCollector.writeNativeLibraries(zip, result);
        }
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) entries.put(entry.getName(), zip.readAllBytes());
        }
        return entries;
    }
}
