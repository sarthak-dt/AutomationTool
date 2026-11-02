package com.stellar.platform.desktop.session;

import com.stellar.platform.api.StellarPlatformClients;
import com.stellar.platform.processing.ExecutionMode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("release-acceptance")
class CoreOfflineNetworkAuditTest {

    @Test
    void defaultExecutionModeIsLocalNotRemote() {
        System.clearProperty(StellarPlatformClients.PROP_MODE);
        assertEquals(ExecutionMode.LOCAL, StellarPlatformClients.resolveMode());
    }

    @Test
    void desktopPomDoesNotDependOnStellarServer() throws Exception {
        Path pom = locate("stellar-desktop/pom.xml");
        String text = Files.readString(pom);
        assertFalse(text.contains("stellar-server"), text);
        assertFalse(text.contains("postgresql"), text.toLowerCase());
        assertFalse(text.contains("spring-boot"), text.toLowerCase());
    }

    @Test
    void packagedJavaOptionsForceLocalMode() throws Exception {
        Path script = locate("BUILD_WINDOWS_TESTER_INSTALLER.ps1");
        String text = Files.readString(script);
        assertTrue(text.contains("-Dstellar.execution.mode=LOCAL"));
    }

    private static Path locate(String relative) throws Exception {
        Path cursor = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (int i = 0; i < 8 && cursor != null; i++) {
            Path candidate = cursor.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("Cannot locate " + relative);
    }
}
