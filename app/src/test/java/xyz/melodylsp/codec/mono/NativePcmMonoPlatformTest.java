package xyz.melodylsp.codec.mono;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativePcmMonoPlatformTest {
    @Test public void supportsInspectedPlatformsIn64BitProcessesOnly() {
        assertTrue(NativePcmMono.supportsPlatform(36, true));
        assertTrue(NativePcmMono.supportsPlatform(37, true));
        assertFalse(NativePcmMono.supportsPlatform(36, false));
        assertFalse(NativePcmMono.supportsPlatform(37, false));
        assertFalse(NativePcmMono.supportsPlatform(35, true));
        assertFalse(NativePcmMono.supportsPlatform(38, true));
    }

    @Test public void wearQueryRemainsLimitedToInspectedHostVersions() {
        assertTrue(WearStateHookInstaller.supportsStatusQuery(16008003L));
        assertTrue(WearStateHookInstaller.supportsStatusQuery(17005001L));
        assertFalse(WearStateHookInstaller.supportsStatusQuery(17005000L));
        assertFalse(WearStateHookInstaller.supportsStatusQuery(17005002L));
        assertFalse(WearStateHookInstaller.supportsStatusQuery(0L));
    }
}
