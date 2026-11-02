package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.investigation.EngineeringFormats;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphEventGps;
import com.stellar.platform.desktop.presentation.GnssEventCorrelation;
import com.stellar.platform.desktop.ui.graph.map.MapTimeFormat;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * Requests, reviews, and links one GNSS sample for the selected graph event.
 * The lookup runs off the JavaFX thread. Linking writes the existing bookmark.
 */
final class GraphEventGpsDialog {

    private final DesktopAppService service;
    private final String bookmarkId;
    private final Supplier<GraphEventGps.RouteView> route;
    private final BooleanSupplier routeChecked;
    private final LongConsumer showOnMap;
    private final Label detail = new Label();
    private final Button link = new Button("Link sample");
    private final Button remove = new Button("Remove GPS link");
    private final Button show = new Button("Show on map");
    private GraphEventGps.Evidence reviewed = GraphEventGps.notRequested();

    GraphEventGpsDialog(
            DesktopAppService service,
            String bookmarkId,
            Supplier<GraphEventGps.RouteView> route,
            BooleanSupplier routeChecked,
            LongConsumer showOnMap
    ) {
        this.service = service;
        this.bookmarkId = bookmarkId;
        this.route = route == null ? GraphEventGps.RouteView::none : route;
        this.routeChecked = routeChecked == null ? () -> false : routeChecked;
        this.showOnMap = showOnMap;
    }

    Dialog<ButtonType> build() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Event GPS");
        dialog.setHeaderText("Match this event to a stored GNSS sample");
        detail.setWrapText(true);
        detail.setMaxWidth(460);
        Label tolerance = new Label("Point tolerance is " + EngineeringFormats.seconds(GnssEventCorrelation.POINT_TOLERANCE_NANOS)
                + ". A sample at exactly that difference is accepted. "
                + "A window uses the first stored sample inside the interval. Coordinates are not interpolated.");
        tolerance.setWrapText(true);
        tolerance.setMaxWidth(460);
        Button request = new Button("Request GPS");
        request.setOnAction(event -> requestOnBackground());
        link.setOnAction(event -> commitLink());
        remove.setOnAction(event -> commitRemove());
        show.setOnAction(event -> commitShow());
        HBox actions = new HBox(8, request, link, remove, show);
        VBox box = new VBox(10, tolerance, detail, actions);
        box.setPadding(new Insets(4, 0, 0, 0));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.setOnShown(event -> showStored());
        dialog.getDialogPane().getProperties().put("stellar.gps.link", this);
        showStored();
        return dialog;
    }

    private void requestOnBackground() {
        GraphEventGps.RouteView current = route.get();
        var bookmark = service.findEngineeringBookmark(bookmarkId).orElse(null);
        link.setDisable(true);
        Task<GraphEventGps.Evidence> task = new Task<>() {
            @Override
            protected GraphEventGps.Evidence call() {
                return GraphEventGps.review(bookmark, current);
            }
        };
        task.setOnSucceeded(event -> applyReview(task.getValue()));
        task.setOnFailed(event -> applyReview(GraphEventGps.requested()));
        Thread thread = new Thread(task, "gnss-event-match");
        thread.setDaemon(true);
        thread.start();
    }

    private void showStored() {
        var bookmark = service.findEngineeringBookmark(bookmarkId).orElse(null);
        GraphEventGps.Evidence saved = GraphEventGps.read(bookmark);
        GraphEventGps.Evidence checked = GraphEventGps.revalidate(bookmark, route.get(), routeChecked.getAsBoolean());
        if (GraphEventGps.STALE.equals(checked.status()) && !GraphEventGps.STALE.equals(saved.status())) {
            service.saveGraphEventGps(bookmarkId, checked);
            showReview(checked);
            return;
        }
        showReview(checked.status().equals(saved.status()) ? saved : checked);
    }

    private void showReview(GraphEventGps.Evidence evidence) {
        reviewed = evidence == null ? GraphEventGps.notRequested() : evidence;
        detail.setText(text(reviewed));
        link.setDisable(!GraphEventGps.MATCHED.equals(reviewed.status()));
        boolean canShow = GraphEventGps.MATCHED.equals(reviewed.status()) && showOnMap != null;
        show.setDisable(!canShow);
        remove.setDisable(GraphEventGps.NOT_REQUESTED.equals(reviewed.status()));
    }

    private String commitLink() {
        if (!GraphEventGps.MATCHED.equals(reviewed.status())) {
            return "No GNSS sample is ready to link.";
        }
        String saved = service.saveGraphEventGps(bookmarkId, reviewed);
        if (!saved.isBlank()) {
            detail.setText(saved);
            return saved;
        }
        showStored();
        return "";
    }

    private String commitRemove() {
        String saved = service.clearGraphEventGps(bookmarkId);
        if (!saved.isBlank()) {
            detail.setText(saved);
            return saved;
        }
        showReview(GraphEventGps.notRequested());
        return "";
    }

    private boolean commitShow() {
        if (showOnMap == null || !GraphEventGps.MATCHED.equals(reviewed.status())) {
            return false;
        }
        showOnMap.accept(reviewed.sampleTimestampNanos());
        return true;
    }

    private static String text(GraphEventGps.Evidence evidence) {
        StringBuilder body = new StringBuilder();
        body.append(evidence.status()).append('\n');
        body.append(evidence.statement()).append('\n');
        if (GraphEventGps.MATCHED.equals(evidence.status())) {
            body.append("GNSS sample ").append(MapTimeFormat.withMillis(evidence.sampleTimestampNanos()));
            body.append(" (").append(evidence.sampleTimestampNanos()).append(" ns)\n");
            body.append("Latitude ").append(evidence.latitude()).append("  Longitude ").append(evidence.longitude()).append('\n');
            if (GraphEventGps.KIND_NEAREST.equals(evidence.kind()) && evidence.deltaNanos() != Long.MIN_VALUE) {
                body.append("Time difference ").append(EngineeringFormats.seconds(Math.abs(evidence.deltaNanos())));
                if (evidence.deltaNanos() == 0L) {
                    body.append(". The GNSS sample is at the event timestamp.");
                } else {
                    body.append(evidence.deltaNanos() > 0L ? " after the event." : " before the event.");
                    body.append(" The sample is not at the event timestamp.");
                }
                body.append('\n');
            } else if (GraphEventGps.KIND_WINDOW.equals(evidence.kind())) {
                body.append("This is the first GNSS sample inside the window, not the location of the whole event.\n");
            }
            if (evidence.policy() != null && !evidence.policy().isBlank()) {
                body.append("Policy: ").append(evidence.policy()).append('\n');
            }
        }
        return body.toString().strip();
    }

    String detailForTest() {
        return detail.getText() == null ? "" : detail.getText();
    }

    boolean linkDisabledForTest() {
        return link.isDisabled();
    }

    void requestForTest() {
        var bookmark = service.findEngineeringBookmark(bookmarkId).orElse(null);
        applyReview(GraphEventGps.review(bookmark, route.get()));
    }

    private void applyReview(GraphEventGps.Evidence evidence) {
        GraphEventGps.Evidence shown = evidence == null ? GraphEventGps.requested() : evidence;
        if (!GraphEventGps.MATCHED.equals(shown.status())) {
            service.saveGraphEventGps(bookmarkId, shown);
        }
        showReview(shown);
    }

    String commitLinkForTest() {
        return commitLink();
    }

    String commitRemoveForTest() {
        return commitRemove();
    }

    boolean commitShowForTest() {
        return commitShow();
    }
}
