package com.stellar.platform.desktop.session;

import com.stellar.platform.api.http.HttpStellarPlatformClient;
import com.stellar.platform.api.local.LocalStellarPlatformClient;
import com.stellar.platform.desktop.processing.LocalProcessingGateway;
import com.stellar.platform.desktop.processing.RemoteProcessingGateway;
import com.stellar.platform.processing.ExecutionMode;
import com.stellar.platform.processing.ParserRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopSessionExecutionModeTest {

    @Test
    void localModeUsesLocalGatewayAndClient() {
        DesktopSession session = new DesktopSession(ParserRegistry.load(), ExecutionMode.LOCAL, "http://127.0.0.1:9");
        assertEquals(ExecutionMode.LOCAL, session.executionMode());
        assertInstanceOf(LocalProcessingGateway.class, session.processingGateway());
        assertInstanceOf(LocalStellarPlatformClient.class, session.platformClient());
    }

    @Test
    void remoteModeUsesRemoteGatewayAndHttpClient() {
        DesktopSession session = new DesktopSession(ParserRegistry.load(), ExecutionMode.REMOTE, "http://127.0.0.1:18080");
        assertEquals(ExecutionMode.REMOTE, session.executionMode());
        assertInstanceOf(RemoteProcessingGateway.class, session.processingGateway());
        assertInstanceOf(HttpStellarPlatformClient.class, session.platformClient());
    }
}
