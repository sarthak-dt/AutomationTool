package com.stellar.platform.desktop.ui.theme;

import javafx.application.Platform;
import com.stellar.platform.desktop.StellarDesktopApp;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypographyConsistencyTest {

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
    @Timeout(30)
    void rolesShareOneFamilyAndStayDistinguishable() throws Exception {
        URL css = TypographyConsistencyTest.class.getResource("/css/stellar-desktop.css");
        assertNotNull(css);

        Label body = labeled("type-body");
        Label page = labeled("type-page-title");
        Label section = labeled("type-section-title");
        Label panel = labeled("type-panel-title");
        Label kpiLabel = labeled("metric-label");
        Label kpiValue = labeled("metric-value");
        Label project = labeled("project-name");
        Label meta = labeled("project-meta");
        Label header = labeled("project-table-header-cell");
        Label activity = labeled("activity-title");
        Label activityMeta = labeled("activity-meta");
        Label action = labeled("quick-action-label");
        Button nav = new Button("Workspace");
        nav.getStyleClass().add("sidebar-item");
        Button navSelected = new Button("Sources");
        navSelected.getStyleClass().addAll("sidebar-item", "sidebar-item-selected");
        Button primary = new Button("Save");
        primary.getStyleClass().add("primary-button");
        Button danger = new Button("Delete");
        danger.getStyleClass().add("danger-button");
        Label badge = labeled("status-badge");
        VBox root = new VBox(body, page, section, panel, kpiLabel, kpiValue, project, meta, header,
                activity, activityMeta, action, nav, navSelected, primary, danger, badge);

        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Scene scene = new Scene(root, 900, 700);
                scene.getStylesheets().add(css.toExternalForm());
                root.applyCss();
                root.layout();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }

        assertTrue(body.getFont().getFamily().contains("Segoe UI"), body.getFont().getFamily());
        assertEquals(13, body.getFont().getSize(), 0.2);
        assertTrue(page.getFont().getFamily().contains("Segoe UI"), page.getFont().getFamily());
        assertNotBlack(text(page));
        assertColor(text(page), "#0F172A");
        assertEquals(20, page.getFont().getSize(), 0.2);
        assertEquals(16, section.getFont().getSize(), 0.2);
        assertEquals(14, panel.getFont().getSize(), 0.2);

        assertEquals(12, kpiLabel.getFont().getSize(), 0.2);
        assertEquals(22, kpiValue.getFont().getSize(), 0.2);
        assertColor(text(kpiLabel), "#334155");
        assertColor(text(kpiValue), "#0F172A");
        assertTrue(kpiValue.getFont().getSize() > kpiLabel.getFont().getSize());

        assertEquals(13, project.getFont().getSize(), 0.2);
        assertEquals(12, meta.getFont().getSize(), 0.2);
        assertColor(text(project), "#0F172A");
        assertColor(text(meta), "#334155");
        assertEquals(12, header.getFont().getSize(), 0.2);
        assertColor(text(header), "#0F172A");

        assertEquals(13, activity.getFont().getSize(), 0.2);
        assertEquals(12, activityMeta.getFont().getSize(), 0.2);
        assertEquals(13, action.getFont().getSize(), 0.2);
        assertEquals(13, nav.getFont().getSize(), 0.2);
        assertEquals(13, navSelected.getFont().getSize(), 0.2);
        assertColor(text(navSelected), "#0F6CBD");
        assertEquals(13, primary.getFont().getSize(), 0.2);
        assertColor(text(primary), "#FFFFFF");
        assertColor(text(danger), "#FFFFFF");
        assertEquals(12, badge.getFont().getSize(), 0.2);

        assertNotEquals(StellarTheme.css(StellarTheme.TEXT), StellarTheme.css(StellarTheme.TEXT_SECONDARY));
        assertNotEquals(StellarTheme.css(StellarTheme.TEXT_SECONDARY), StellarTheme.css(StellarTheme.TEXT_MUTED));
        assertNotEquals("#000000", StellarTheme.css(StellarTheme.TEXT));
    }

    @Test
    @Timeout(30)
    void dialogUsesApplicationTypography() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<Double> headerSize = new AtomicReference<>();
        AtomicReference<String> family = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            TextInputDialog dialog = new TextInputDialog("Sample");
            try {
                dialog.setTitle("New Project");
                dialog.setHeaderText("Create a new working project");
                dialog.setContentText("Project name:");
                StellarDesktopApp.installSharedTypography();
                dialog.show();
                dialog.getDialogPane().applyCss();
                dialog.getDialogPane().layout();
                Labeled header = (Labeled) dialog.getDialogPane().lookup(".header-panel .label");
                assertNotNull(header);
                headerSize.set(header.getFont().getSize());
                family.set(header.getFont().getFamily());
                dialog.close();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        assertTrue(family.get().contains("Segoe UI"), family.get());
        assertEquals(16, headerSize.get(), 0.2);
    }

    @Test
    void pageStylesheetsDoNotIntroduceAnotherFontFamily() throws Exception {
        String css = read("/css/stellar-desktop.css");
        Matcher families = Pattern.compile("-fx-font-family:\\s*([^;]+);").matcher(css);
        while (families.find()) {
            String family = families.group(1);
            boolean allowed = family.contains("Segoe UI")
                    || family.contains("Consolas")
                    || family.contains("Segoe UI Symbol");
            assertTrue(allowed, "font-family drift: " + family);
        }
        assertTrue(css.contains("-stellar-text: #0F172A;"));
        assertTrue(css.contains("-stellar-text-body: #1E293B;"));
        assertTrue(css.contains("-stellar-text-secondary: #334155;"));
        assertTrue(css.contains("-stellar-text-muted: #475569;"));
    }

    @Test
    @Timeout(30)
    void majorSurfacesShareSignalsTypography() throws Exception {
        URL css = TypographyConsistencyTest.class.getResource("/css/stellar-desktop.css");
        Label signals = labeled("toolbar-field-label");
        Label workspace = labeled("project-name");
        Label sources = labeled("meas-source-name");
        Label graph = labeled("graph-panel-title");
        Label processing = labeled("processing-page-title");
        Label header = labeled("project-table-header-cell");
        VBox root = new VBox(signals, workspace, sources, graph, processing, header);

        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Scene scene = new Scene(root, 800, 400);
                scene.getStylesheets().add(css.toExternalForm());
                root.applyCss();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        for (Label label : java.util.List.of(signals, workspace, sources, graph, processing)) {
            assertTrue(label.getFont().getFamily().contains("Segoe UI"), label.getStyleClass() + " " + label.getFont().getFamily());
            assertEquals(13, label.getFont().getSize(), 0.2, label.getStyleClass().toString());
        }
        assertTrue(header.getFont().getFamily().contains("Segoe UI"), header.getFont().getFamily());
        assertEquals(12, header.getFont().getSize(), 0.2);
        assertColor(text(header), "#0F172A");
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = TypographyConsistencyTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Label labeled(String styleClass) {
        Label label = new Label(styleClass);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static Color text(javafx.scene.control.Labeled labeled) {
        return (Color) labeled.getTextFill();
    }

    private static void assertNotBlack(Color color) {
        assertTrue(color.getRed() > 0.02 || color.getGreen() > 0.02 || color.getBlue() > 0.02);
    }

    private static void assertColor(Color actual, String hex) {
        Color expected = Color.web(hex);
        assertEquals(expected.getRed(), actual.getRed(), 0.02, hex);
        assertEquals(expected.getGreen(), actual.getGreen(), 0.02, hex);
        assertEquals(expected.getBlue(), actual.getBlue(), 0.02, hex);
    }
}
