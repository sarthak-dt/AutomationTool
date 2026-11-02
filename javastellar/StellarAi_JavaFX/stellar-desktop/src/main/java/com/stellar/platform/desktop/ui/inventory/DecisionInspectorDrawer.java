package com.stellar.platform.desktop.ui.inventory;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * On-demand right inspector. Closed it is not in the SplitPane and consumes no width.
 * Opening changes horizontal allocation only.
 */
public final class DecisionInspectorDrawer extends BorderPane {

    public enum Tab {
        SIGNALS,
        STUDY
    }

    private final Button signalsButton = new Button("Signals");
    private final Button studyButton = new Button("Study Set (0)");
    private final Button closeButton = new Button("\u00D7");
    private final StackPane body = new StackPane();
    private final ChannelSignalExplorerPane signals;
    private final CanInventoryDetailsInspector details;
    private Tab tab = Tab.SIGNALS;
    private boolean inspectActive;
    private Runnable onClose = () -> { };

    public DecisionInspectorDrawer(ChannelSignalExplorerPane signals, CanInventoryDetailsInspector details) {
        this.signals = signals;
        this.details = details;
        getStyleClass().add("decision-inspector-drawer");
        setMinWidth(320);
        setPrefWidth(336);
        setMaxWidth(350);
        setMinHeight(0);
        setMaxHeight(Double.MAX_VALUE);

        signalsButton.getStyleClass().addAll("secondary-button", "secondary-action", "compact-button",
                "decision-inspector-tab");
        studyButton.getStyleClass().addAll("secondary-button", "secondary-action", "compact-button",
                "decision-inspector-tab", "inventory-study-toggle");
        closeButton.getStyleClass().addAll("secondary-button", "compact-button", "decision-inspector-close");
        closeButton.setTooltip(new Tooltip("Close inspector"));
        closeButton.setMinWidth(Region.USE_PREF_SIZE);
        signalsButton.setMinWidth(Region.USE_PREF_SIZE);
        studyButton.setMinWidth(Region.USE_PREF_SIZE);
        signalsButton.setTextOverrun(OverrunStyle.CLIP);
        studyButton.setTextOverrun(OverrunStyle.CLIP);
        signalsButton.setOnAction(e -> showTab(Tab.SIGNALS));
        studyButton.setOnAction(e -> showTab(Tab.STUDY));
        closeButton.setOnAction(e -> onClose.run());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(6, signalsButton, studyButton, spacer, closeButton);
        header.getStyleClass().add("decision-inspector-header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(6, 8, 6, 8));
        header.setMaxHeight(Region.USE_PREF_SIZE);

        details.setModeChromeVisible(false);
        VBox.setVgrow(signals, Priority.ALWAYS);
        VBox.setVgrow(details, Priority.ALWAYS);
        body.getChildren().setAll(signals, details);
        body.setMinHeight(0);
        body.setMaxHeight(Double.MAX_VALUE);

        setTop(header);
        setCenter(body);
        BorderPane.setAlignment(body, Pos.CENTER);
        showTab(Tab.SIGNALS);
        setVisible(false);
        setManaged(false);
    }

    public void setOnClose(Runnable onClose) {
        this.onClose = onClose == null ? () -> { } : onClose;
    }

    public void showTab(Tab next) {
        this.tab = next == null ? Tab.SIGNALS : next;
        inspectActive = false;
        applyBody();
    }

    public void showInspect() {
        this.tab = Tab.SIGNALS;
        inspectActive = true;
        applyBody();
    }

    public void setStudyCount(int count) {
        studyButton.setText("Study Set (" + Math.max(0, count) + ")");
    }

    public Tab tab() {
        return tab;
    }

    public boolean isStudyTab() {
        return tab == Tab.STUDY && !inspectActive;
    }

    public boolean isSignalsTab() {
        return tab == Tab.SIGNALS && !inspectActive;
    }

    public boolean isInspectActive() {
        return inspectActive;
    }

    public Button signalsButtonForTest() {
        return signalsButton;
    }

    public Button studyButtonForTest() {
        return studyButton;
    }

    public Button closeButtonForTest() {
        return closeButton;
    }

    private void applyBody() {
        boolean showSignals = tab == Tab.SIGNALS && !inspectActive;
        signals.setVisible(showSignals);
        signals.setManaged(showSignals);
        details.setVisible(!showSignals);
        details.setManaged(!showSignals);
        if (tab == Tab.STUDY) {
            details.showStudyMode();
        } else if (inspectActive) {
            details.showInspectMode();
        }
        signalsButton.getStyleClass().remove("decision-inspector-tab-active");
        studyButton.getStyleClass().remove("decision-inspector-tab-active");
        if (tab == Tab.STUDY) {
            studyButton.getStyleClass().add("decision-inspector-tab-active");
        } else {
            signalsButton.getStyleClass().add("decision-inspector-tab-active");
        }
    }
}
