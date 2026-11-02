package com.stellar.platform.testkit;

import com.stellar.platform.processing.ExternalTestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Optional real-file compatibility hook. Skips cleanly when external data is absent.
 */
class ExternalTestDataHookTest {

    static boolean externalRootPresent() {
        return ExternalTestData.isAvailable();
    }

    @Test
    void skipsWhenExternalDirectoryMissing() {
        assumeTrue(ExternalTestData.isAvailable(), "external-test-data not present — skipping");
        assertTrue(Files.isDirectory(ExternalTestData.root()));
    }

    @Test
    @EnabledIf("externalRootPresent")
    void discoversOptionalPairWhenConfigured() {
        assumeTrue(ExternalTestData.hasFile("sample.mf4") && ExternalTestData.hasFile("sample.dbc"),
                "sample.mf4/sample.dbc not provided — skipping");
        assertTrue(Files.isRegularFile(ExternalTestData.resolve("sample.mf4")));
        assertTrue(Files.isRegularFile(ExternalTestData.resolve("sample.dbc")));
    }
}
