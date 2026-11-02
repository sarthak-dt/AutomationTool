package com.stellar.platform.desktop.ui.inventory;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Presentation-only regression checks for the Detailed Mapping v10 chrome. */
class DetailedMappingRedesignUiTest {

    @BeforeAll
    static void initJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        CountDownLatch configured = new CountDownLatch(1);
        Platform.runLater(() -> {
            Platform.setImplicitExit(false);
            configured.countDown();
        });
        assertTrue(configured.await(5, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(20)
    void detailedChromeKeepsSearchVisibleFiltersCollapsibleAndActionBarGlobal() throws Exception {
        runOnFx(() -> {
            SourceInventoryWorkspace workspace = new SourceInventoryWorkspace();

            assertTrue(workspace.navigatorSearchFieldForTest().isVisible());
            assertTrue(workspace.navigatorSearchFieldForTest().isManaged());
            assertFalse(workspace.detailedFilterDrawerForTest().isVisible());
            assertFalse(workspace.detailedFilterDrawerForTest().isManaged());

            workspace.detailedFiltersButtonForTest().fire();
            assertTrue(workspace.detailedFilterDrawerForTest().isVisible());
            assertTrue(workspace.detailedFilterDrawerForTest().isManaged());
            assertTrue(workspace.detailedFiltersButtonForTest().getStyleClass()
                    .contains("detailed-filters-toggle-active"));

            workspace.detailedFiltersButtonForTest().fire();
            assertFalse(workspace.detailedFilterDrawerForTest().isVisible());
            assertFalse(workspace.detailedFilterDrawerForTest().isManaged());

            assertSame(workspace.detailedModePaneForTest(), workspace.actionBarForTest().getParent(),
                    "Detailed action bar must span the full mode workspace, not only the CAN table");
        });
    }

    private static void runOnFx(Runnable body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS), "JavaFX task timed out");
        if (failure.get() != null) {
            if (failure.get() instanceof Exception ex) {
                throw ex;
            }
            if (failure.get() instanceof Error err) {
                throw err;
            }
            throw new AssertionError(failure.get());
        }
    }
}
