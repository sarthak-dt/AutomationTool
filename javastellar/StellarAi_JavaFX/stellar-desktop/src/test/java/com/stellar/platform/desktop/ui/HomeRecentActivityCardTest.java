package com.stellar.platform.desktop.ui;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recent Activity stays inside its dashboard card and scrolls when the history is taller than the card.
 */
class HomeRecentActivityCardTest {

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
    void emptyAndSingleActivityStayInsideTheCard() throws Exception {
        onCard(1280, 720, (controller, scroll, card) -> {
            controller.replaceActivityRowsForTest(List.of(), List.of());
            layout(scroll);
            controller.refresh();
            assertEquals(List.of("No recent activity yet."), controller.activityTitlesForTest());
            assertFalse(verticalBarVisible(scroll), "empty history should not show a scrollbar");
            assertFalse(horizontalBarVisible(scroll));
            assertInsideCard(scroll, card);

            controller.replaceActivityRowsForTest(
                    List.of("Processed measurement file"),
                    List.of("Today 17:02"));
            layout(scroll);
            assertEquals(List.of("Processed measurement file"), controller.activityTitlesForTest());
            assertEquals("Test · Today 17:02", controller.activityTooltipForTest(0));
            assertFalse(horizontalBarVisible(scroll));
            assertInsideCard(scroll, card);
        });
    }

    @Test
    @Timeout(30)
    void manyActivitiesScrollInsideTheCardAndKeepOrder() throws Exception {
        List<String> titles = new ArrayList<>();
        List<String> details = new ArrayList<>();
        titles.add("Processed measurement file");
        details.add("Today 17:02");
        titles.add("Added signals");
        details.add("8 signals");
        titles.add("Imported DBC");
        details.add("11_bit_OBD2 2.dbc");
        for (int i = 4; i <= 24; i++) {
            titles.add(i % 2 == 0 ? "Processed measurement file" : "Imported DBC");
            details.add("entry-" + i);
        }
        onCard(1366, 768, (controller, scroll, card) -> {
            controller.replaceActivityRowsForTest(titles.subList(0, 5), details.subList(0, 5));
            layout(scroll);
            assertEquals(titles.subList(0, 5), controller.activityTitlesForTest());
            assertInsideCard(scroll, card);

            controller.replaceActivityRowsForTest(titles, details);
            layout(scroll);
            assertEquals(titles, controller.activityTitlesForTest());
            assertTrue(contentTallerThanViewport(scroll), "20 rows should exceed the card body");
            assertTrue(verticalBarVisible(scroll), "vertical scrollbar should appear");
            assertFalse(horizontalBarVisible(scroll));
            assertInsideCard(scroll, card);
            assertTrue(card.getHeight() < 400, "card grew without a bound: " + card.getHeight());
        });
    }

    @Test
    @Timeout(30)
    void longNamesEllipsizeAndDoNotWidenTheCard() throws Exception {
        String mf4 = "MF4_KA04NC7760_Bengaluru_0002360_20230814_164651_processed_measurement_file.mf4";
        String dbc = "11_bit_OBD2_very_long_database_name_that_must_not_stretch_the_dashboard_card.dbc";
        onCard(1280, 720, (controller, scroll, card) -> {
            controller.replaceActivityRowsForTest(
                    List.of("Processed measurement file", "Imported DBC"),
                    List.of("short.mf4", "short.dbc"));
            layout(scroll);
            double shortWidth = card.getWidth();
            controller.replaceActivityRowsForTest(
                    List.of("Processed measurement file", "Imported DBC"),
                    List.of(mf4, dbc));
            layout(scroll);
            assertEquals("Test · " + mf4, controller.activityTooltipForTest(0));
            assertEquals("Test · " + dbc, controller.activityTooltipForTest(1));
            assertFalse(horizontalBarVisible(scroll));
            assertInsideCard(scroll, card);
            assertEquals(shortWidth, card.getWidth(), 1.0, "long names widened the card");
            double viewport = scroll.getViewportBounds().getWidth();
            assertTrue(viewport > 40, "viewport not laid out: " + viewport);
            for (Node row : ((VBox) scroll.getContent()).getChildren()) {
                assertTrue(row.prefWidth(-1) <= viewport + 8 || row.getLayoutBounds().getWidth() <= viewport + 2,
                        "row wider than the card body pref=" + row.prefWidth(-1)
                                + " width=" + row.getLayoutBounds().getWidth() + " viewport=" + viewport);
            }
        });
        onCard(1366, 768, (controller, scroll, card) -> {
            controller.replaceActivityRowsForTest(
                    List.of("Processed measurement file"),
                    List.of(mf4));
            layout(scroll);
            assertFalse(horizontalBarVisible(scroll));
            assertInsideCard(scroll, card);
        });
    }

    private static void assertInsideCard(ScrollPane scroll, VBox card) {
        assertTrue(scroll.getLayoutX() >= -1);
        assertTrue(scroll.getLayoutY() >= -1);
        assertTrue(scroll.getLayoutX() + scroll.getWidth() <= card.getWidth() + 1.5,
                "scroll leaked past the card width");
        assertTrue(scroll.getLayoutY() + scroll.getHeight() <= card.getHeight() + 1.5,
                "scroll leaked past the card height " + scroll.getHeight() + " card " + card.getHeight());
        assertEquals(ScrollPane.ScrollBarPolicy.NEVER, scroll.getHbarPolicy());
        assertEquals(ScrollPane.ScrollBarPolicy.AS_NEEDED, scroll.getVbarPolicy());
    }

    private static boolean contentTallerThanViewport(ScrollPane scroll) {
        if (!(scroll.getContent() instanceof VBox content)) {
            return false;
        }
        return content.prefHeight(-1) > scroll.getViewportBounds().getHeight() + 4;
    }

    private static boolean verticalBarVisible(ScrollPane scroll) {
        return barVisible(scroll, Orientation.VERTICAL);
    }

    private static boolean horizontalBarVisible(ScrollPane scroll) {
        return barVisible(scroll, Orientation.HORIZONTAL);
    }

    private static boolean barVisible(ScrollPane scroll, Orientation orientation) {
        for (Node node : scroll.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == orientation) {
                return bar.isVisible();
            }
        }
        return false;
    }

    private static void layout(ScrollPane scroll) {
        scroll.applyCss();
        scroll.layout();
        if (scroll.getScene() != null && scroll.getScene().getRoot() != null) {
            scroll.getScene().getRoot().applyCss();
            scroll.getScene().getRoot().layout();
        }
    }

    private void onCard(double width, double height, CardCheck check) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                        HomeDashboardController.class.getResource("/fxml/home-dashboard-view.fxml"));
                ScrollPane root = loader.load();
                HomeDashboardController controller = loader.getController();
                Scene scene = new Scene(root, width, height);
                root.getStylesheets().add(HomeDashboardController.class.getResource("/css/stellar-desktop.css").toExternalForm());
                root.applyCss();
                root.resize(width, height);
                root.layout();
                check.check(controller, controller.activityScrollForTest(), controller.activityPaneForTest());
                assertTrue(scene.getWidth() > 0);
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @FunctionalInterface
    private interface CardCheck {
        void check(HomeDashboardController controller, ScrollPane scroll, VBox card) throws Exception;
    }
}
