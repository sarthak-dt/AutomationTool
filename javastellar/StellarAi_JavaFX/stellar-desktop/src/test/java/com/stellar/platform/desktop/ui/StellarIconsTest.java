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

class StellarIconsTest {

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
    void iconsAreUniformStrokeGlyphs() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                Node[] icons = {
                        StellarIcons.search(),
                        StellarIcons.filter(),
                        StellarIcons.starOutline(),
                        StellarIcons.starFilled(),
                        StellarIcons.clock(),
                        StellarIcons.file(),
                        StellarIcons.database(),
                        StellarIcons.channel(),
                        StellarIcons.sliders(),
                        StellarIcons.activity(),
                        StellarIcons.selectedSignals(),
                        StellarIcons.alignment(),
                        StellarIcons.rows(),
                        StellarIcons.timeStart(),
                        StellarIcons.timeEnd(),
                        StellarIcons.locate(),
                        StellarIcons.copy(),
                        StellarIcons.first(),
                        StellarIcons.previous(),
                        StellarIcons.next(),
                        StellarIcons.last(),
                        StellarIcons.plus(),
                        StellarIcons.collapseLeft(),
                        StellarIcons.expandRight()
                };
                for (Node icon : icons) {
                    assertTrue(icon instanceof Pane);
                    assertTrue(((Pane) icon).getPrefWidth() >= 13.0);
                    assertTrue(((Pane) icon).getPrefHeight() >= 13.0);
                    assertTrue(icon.getStyleClass().contains("stellar-icon-host"));
                    assertFalse(((Pane) icon).getChildren().isEmpty());
                }
                assertEquals(StellarIcons.SIZE_SM, ((Pane) StellarIcons.search()).getPrefWidth(), 0.01);
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