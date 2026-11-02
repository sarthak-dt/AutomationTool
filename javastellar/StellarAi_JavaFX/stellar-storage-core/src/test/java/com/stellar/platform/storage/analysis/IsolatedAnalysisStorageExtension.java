package com.stellar.platform.storage.analysis;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auto-detected test isolation: every test class gets its own {@code stellarai.home}
 * and {@code stellarai.test.isolate=true}. Operator Analysis Storage is unreachable.
 */
public final class IsolatedAnalysisStorageExtension
        implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, AfterAllCallback {

    private static final ConcurrentHashMap<Class<?>, ClassIsolation> STATE = new ConcurrentHashMap<>();

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        Class<?> type = context.getRequiredTestClass();
        Path home = Files.createTempDirectory("stellarai-test-home-").toAbsolutePath().normalize();
        STATE.put(type, new ClassIsolation(
                System.getProperty("stellarai.home"),
                System.getProperty(AnalysisStorageIsolation.PROPERTY),
                home));
        isolate(home);
        AnalysisStorageRoots.clear();
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        ClassIsolation isolation = STATE.get(context.getRequiredTestClass());
        if (isolation == null) {
            return;
        }
        isolate(isolation.home);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        AnalysisStorageRoots.clear();
        ClassIsolation isolation = STATE.get(context.getRequiredTestClass());
        if (isolation != null && blank(System.getProperty("stellarai.home"))) {
            isolate(isolation.home);
        } else {
            AnalysisStorageIsolation.enable();
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        AnalysisStorageRoots.clear();
        ClassIsolation isolation = STATE.remove(context.getRequiredTestClass());
        if (isolation == null) {
            return;
        }
        restore("stellarai.home", isolation.previousHome);
        restore(AnalysisStorageIsolation.PROPERTY, isolation.previousIsolate);
        deleteQuietly(isolation.home);
    }

    private static void isolate(Path home) {
        AnalysisStorageIsolation.enable();
        System.setProperty("stellarai.home", home.toString());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    private static void deleteQuietly(Path root) {
        try {
            if (!Files.exists(root)) {
                return;
            }
            Files.walk(root)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private record ClassIsolation(String previousHome, String previousIsolate, Path home) {
    }
}
