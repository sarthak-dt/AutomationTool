package com.stellar.platform.desktop.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavIconsTest {

    @BeforeAll
    static void initJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(20)
    void iconsAreUniformLineGlyphs() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                Node[] icons = {
                        NavIcons.dashboard(),
                        NavIcons.projectFiles(),
                        NavIcons.processing(),
                        NavIcons.signals(),
                        NavIcons.diagnostics(),
                        NavIcons.investigations(),
                        NavIcons.tests(),
                        NavIcons.settings(),
                        NavIcons.collapse(),
                        NavIcons.expand()
                };
                for (Node icon : icons) {
                    assertTrue(icon instanceof Pane);
                    assertEquals(NavIcons.SIZE, ((Pane) icon).getPrefWidth(), 0.01);
                    assertEquals(NavIcons.SIZE, ((Pane) icon).getPrefHeight(), 0.01);
                    assertTrue(icon.getStyleClass().contains("nav-icon-host"));
                    assertFalse(((Pane) icon).getChildren().isEmpty());
                }
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }
}
