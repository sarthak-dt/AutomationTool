package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.session.DesktopSession;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainShellLazyLoadingTest {
    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException alreadyStarted) { started.countDown(); }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(30)
    void startupMaterializesHomeOnlyAndLoadsHeavyPagesOnDemand() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            DesktopAppService service = null;
            MainShellController shell = null;
            try {
                service = new DesktopAppService(new DesktopSession());
                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                loader.load();
                shell = loader.getController();
                shell.init(service);

                assertEquals(1, shell.loadedPageCountForTest());
                assertTrue(shell.pageLoadedForTest(NavigationPage.HOME));
                assertFalse(shell.pageLoadedForTest(NavigationPage.SOURCES));
                assertFalse(shell.pageLoadedForTest(NavigationPage.PROCESSING));
                assertFalse(shell.pageLoadedForTest(NavigationPage.SIGNALS));
                assertFalse(shell.pageLoadedForTest(NavigationPage.GRAPHS));

                shell.show(NavigationPage.SOURCES);
                assertEquals(2, shell.loadedPageCountForTest());
                assertTrue(shell.pageLoadedForTest(NavigationPage.SOURCES));
                assertFalse(shell.pageLoadedForTest(NavigationPage.GRAPHS));

                // Sources and Decode share the Project Files host: Decode must not load a new page.
                shell.show(NavigationPage.DECODE);
                assertEquals(2, shell.loadedPageCountForTest());

                shell.show(NavigationPage.SIGNALS);
                assertEquals(3, shell.loadedPageCountForTest());
                assertTrue(shell.pageLoadedForTest(NavigationPage.SIGNALS));
                assertFalse(shell.pageLoadedForTest(NavigationPage.GRAPHS));
            } catch (Throwable t) {
                error.set(t);
            } finally {
                if (shell != null) shell.dispose();
                if (service != null) service.shutdown();
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }
}
