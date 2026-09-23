package xyz.melodylsp.codec.system;

import org.junit.Assume;
import org.junit.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Properties;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class NativeLhdcV5SupportTest {
    private static final int BASE = 0x724e3c;

    private static byte[] evidenceImage() throws Exception {
        Properties windows = new Properties();
        try (InputStream input = NativeLhdcV5SupportTest.class.getResourceAsStream(
                "/native/pma120-17.0.0.103-lhdc-equality.properties")) {
            assertNotNull(input);
            windows.load(input);
        }
        byte[] image = new byte[0x98e7c8 - BASE];
        for (String address : windows.stringPropertyNames()) {
            String hex = windows.getProperty(address);
            int offset = Integer.parseInt(address, 16) - BASE;
            for (int i = 0; i < hex.length(); i += 2) {
                image[offset + i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
            }
        }
        return image;
    }

    @Test public void recognizesOriginalNativeSupportWithoutWritingCode() throws Exception {
        byte[] image = evidenceImage();
        byte[] before = image.clone();
        assertEquals(1, NativeLhdcMemoryPatch.countNativeQualitySwitchMatches(image));
        assertArrayEquals(before, image);
    }

    @Test public void rejectsChangedCallerDispatchParserAndComparator() throws Exception {
        for (int address : new int[]{0x98e500, 0x98e724, 0x98e748, 0x724eb0,
                0x7254d4, 0x72559c, 0x729f54}) {
            byte[] image = evidenceImage();
            image[address - BASE] ^= 1;
            assertEquals("changed code at " + Integer.toHexString(address),
                    0, NativeLhdcMemoryPatch.countNativeQualitySwitchMatches(image));
        }
    }

    @Test public void missingCodeCannotAuthorizeNativeSupport() throws Exception {
        byte[] image = evidenceImage();
        assertEquals(0, NativeLhdcMemoryPatch.countNativeQualitySwitchMatches(new byte[0]));
        assertEquals(0, NativeLhdcMemoryPatch.countNativeQualitySwitchMatches(
                Arrays.copyOfRange(image, 0x98e724 - BASE, image.length)));
        assertEquals(0, NativeLhdcMemoryPatch.countNativeQualitySwitchMatches(
                Arrays.copyOf(image, image.length - 1)));
    }

    @Test public void duplicateEvidenceIsReportedAsAmbiguous() throws Exception {
        byte[] image = evidenceImage();
        byte[] duplicate = new byte[image.length * 2];
        System.arraycopy(image, 0, duplicate, 0, image.length);
        System.arraycopy(image, 0, duplicate, image.length, image.length);
        assertEquals(2, NativeLhdcMemoryPatch.countNativeQualitySwitchMatches(duplicate));
    }

    @Test public void oldPatchBlocksRemainOnThePatchPath() {
        for (NativeLhdcMemoryPatch.CodeBlockSpec spec : NativeLhdcMemoryPatch.qualitySwitchSpecsForTest()) {
            assertEquals(0, NativeLhdcMemoryPatch.countNativeQualitySwitchMatches(spec.original));
            assertEquals(0, NativeLhdcMemoryPatch.countNativeQualitySwitchMatches(spec.patched));
        }
    }

    @Test public void suppliedCompleteLibraryMatchesUniquelyWhenAvailable() throws Exception {
        String path = System.getenv("MELODY_ANDROID17_BT_LIBRARY");
        Assume.assumeTrue("optional full device library", path != null && !path.isEmpty());
        byte[] image = Files.readAllBytes(Paths.get(path));
        assertEquals(1, NativeLhdcMemoryPatch.countNativeQualitySwitchMatches(image));
        assertEquals(0, NativeLhdcMemoryPatch.scanQualitySwitchImage(image).size());
    }
}
