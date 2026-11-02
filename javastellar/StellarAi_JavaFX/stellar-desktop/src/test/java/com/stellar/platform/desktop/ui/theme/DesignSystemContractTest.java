package com.stellar.platform.desktop.ui.theme;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesignSystemContractTest {

    private static final String[] REQUIRED_TOKENS = {
            "-stellar-bg",
            "-stellar-surface",
            "-stellar-surface-subtle",
            "-stellar-border",
            "-stellar-border-strong",
            "-stellar-text",
            "-stellar-text-secondary",
            "-stellar-text-muted",
            "-stellar-accent",
            "-stellar-accent-hover",
            "-stellar-accent-soft",
            "-stellar-measurement",
            "-stellar-definition",
            "-stellar-success",
            "-stellar-success-bg",
            "-stellar-warning",
            "-stellar-warning-bg",
            "-stellar-danger",
            "-stellar-danger-bg",
            "-stellar-info",
            "-stellar-info-bg"
    };

    private static final String[] REQUIRED_SELECTORS = {
            ".primary-button",
            ".secondary-button",
            ".danger-button",
            ".modern-card",
            ".sidebar-item",
            ".form-error",
            ".graph-chip-dot",
            ".graph-chip-dot-hidden"
    };

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
    void stylesheetLoadsInJavaFxWithoutErrors() throws Exception {
        URL css = DesignSystemContractTest.class.getResource("/css/stellar-desktop.css");
        assertNotNull(css);
        AtomicReference<String> font = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Button primary = new Button("Go");
                primary.getStyleClass().add("primary-button");
                Label errorLabel = new Label("Name required");
                errorLabel.getStyleClass().add("form-error");
                Scene scene = new Scene(new VBox(primary, errorLabel));
                scene.getStylesheets().add(css.toExternalForm());
                primary.applyCss();
                font.set(primary.getFont() == null ? "" : primary.getFont().getFamily());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS));
        assertNull(error.get());
        assertNotNull(font.get());
    }

    @Test
    void entryPointExistsAndIsAggregator() throws Exception {
        String entry = read("/css/stellar-desktop.css");
        assertTrue(entry.contains("Production load point only"));
        assertTrue(entry.contains("==== stellar-tokens.css ===="));
        assertTrue(entry.contains("==== stellar-typography.css ===="));
        assertTrue(entry.contains("==== pages/signals.css ===="));
        assertEquals(1, count(entry, "(?m)^\\.root\\s*\\{"),
                "production stylesheet must have one canonical .root");
        assertTrue(entry.contains("-stellar-accent: #0F6CBD;"));
        assertTrue(entry.contains("-fx-font-family: \"Segoe UI\", \"Helvetica Neue\", Arial, sans-serif;"));
        assertTrue(entry.contains("-fx-font-size: 13px;"));
    }

    @Test
    void oneCanonicalRootTypographyContract() throws Exception {
        String typography = read("/css/stellar-typography.css");
        assertEquals(1, count(typography, "(?m)^\\.root\\s*\\{"));
        assertTrue(typography.contains("-fx-font-family: \"Segoe UI\", \"Helvetica Neue\", Arial, sans-serif;"));
        assertTrue(typography.contains("-fx-font-size: 13px;"));
        assertTrue(typography.contains("\"Consolas\", \"Courier New\", monospace"));
        Matcher families = Pattern.compile("-fx-font-family:\\s*([^;]+);").matcher(typography);
        while (families.find()) {
            assertFalse(families.group(1).contains("Inter"),
                    "canonical font stack must not claim Inter: " + families.group(1));
        }
    }

    @Test
    void requiredTokensAreDefined() throws Exception {
        String tokens = read("/css/stellar-tokens.css");
        for (String token : REQUIRED_TOKENS) {
            assertTrue(tokens.contains(token + ":"), "missing token definition: " + token);
        }
    }

    @Test
    void requiredComponentSelectorsExist() throws Exception {
        String flattened = flatten("/css/stellar-desktop.css");
        for (String selector : REQUIRED_SELECTORS) {
            assertTrue(flattened.contains(selector), "missing selector: " + selector);
        }
    }

    @Test
    void referencedStellarTokensAreDefined() throws Exception {
        String flattened = flatten("/css/stellar-desktop.css");
        Set<String> defined = new LinkedHashSet<>();
        Matcher def = Pattern.compile("(-stellar-[a-z0-9-]+)\\s*:").matcher(read("/css/stellar-tokens.css")
                + read("/css/stellar-typography.css"));
        while (def.find()) {
            defined.add(def.group(1));
        }
        Matcher used = Pattern.compile("(?<!\\w)(-stellar-[a-z0-9-]+)").matcher(flattened);
        List<String> undefined = new ArrayList<>();
        while (used.find()) {
            String name = used.group(1);
            if (!defined.contains(name)) {
                undefined.add(name);
            }
        }
        assertTrue(undefined.isEmpty(), "undefined -stellar-* tokens: " + undefined);
    }

    @Test
    void javaThemeMirrorsCanonicalAccentAndText() {
        assertEquals("#0F6CBD", StellarTheme.css(StellarTheme.ACCENT));
        assertEquals("#0F172A", StellarTheme.css(StellarTheme.TEXT));
        assertEquals("Segoe UI", StellarTheme.FONT_UI_FAMILY);
    }

    private static String flatten(String entry) throws IOException {
        return read(entry);
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = DesignSystemContractTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing resource " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int count(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        int n = 0;
        while (matcher.find()) {
            n++;
        }
        return n;
    }
}
