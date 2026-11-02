package com.stellar.platform.desktop.support;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.ui.MainShellController;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deterministic create / dispose / shutdown cycle for main-shell JavaFX tests.
 * UI work runs on the FX thread; {@link DesktopAppService#shutdown()} runs on
 * the test thread so executor join cannot block the shared pulse.
 */
public final class MainShellFxScenario {

    private MainShellFxScenario() {
    }

    public static void run(Path temp, String slug, ShellWork work) throws Exception {
        JavaFxTestSupport.startToolkit();
        AtomicReference<DesktopAppService> serviceRef = new AtomicReference<>();
        Path db = temp.resolve(slug + ".db");
        Throwable scenarioFailure = null;
        try {
            JavaFxTestSupport.runOnFx(slug, () -> {
                String previousHome = System.getProperty("stellarai.home");
                String previousDb = System.getProperty("stellarai.metadata.db");
                DesktopAppService service = null;
                MainShellController shell = null;
                try {
                    Path home = temp.resolve(slug);
                    Files.createDirectories(home);
                    System.setProperty("stellarai.home", home.toAbsolutePath().toString());
                    System.setProperty("stellarai.metadata.db", db.toAbsolutePath().toString());
                    service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
                    serviceRef.set(service);
                    FXMLLoader loader = new FXMLLoader(
                            MainShellController.class.getResource("/fxml/main-shell.fxml"));
                    loader.load();
                    shell = loader.getController();
                    shell.init(service);
                    work.run(service, shell);
                } finally {
                    if (service != null) {
                        service.discardUnsavedLogicalChanges();
                    }
                    if (shell != null) {
                        shell.dispose();
                    }
                    restoreProperty("stellarai.home", previousHome);
                    restoreProperty("stellarai.metadata.db", previousDb);
                }
            });
        } catch (Throwable failure) {
            scenarioFailure = failure;
        }
        try {
            DesktopAppService service = serviceRef.get();
            if (service != null) {
                if (Platform.isFxApplicationThread()) {
                    throw new AssertionError("shutdown must not run on the FX thread");
                }
                service.shutdown();
            }
            JavaFxTestSupport.drainFxPulse();
            JavaFxTestSupport.assertNoLiveStellarThreads("after " + slug);
        } catch (Throwable cleanupFailure) {
            if (scenarioFailure != null) {
                scenarioFailure.addSuppressed(cleanupFailure);
            } else {
                scenarioFailure = cleanupFailure;
            }
        }
        if (scenarioFailure instanceof Exception exception) {
            throw exception;
        }
        if (scenarioFailure instanceof Error error) {
            throw error;
        }
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    @FunctionalInterface
    public interface ShellWork {
        void run(DesktopAppService service, MainShellController shell) throws Exception;
    }
}
