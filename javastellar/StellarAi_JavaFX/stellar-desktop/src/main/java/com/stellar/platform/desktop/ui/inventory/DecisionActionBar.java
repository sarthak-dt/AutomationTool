package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.desktop.ui.StellarIcons;
import com.stellar.platform.desktop.ui.WorkstationLayoutMetrics;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Sticky Decision Workspace action bar. Presentation only — callbacks are wired by the host.
 */
public final class DecisionActionBar extends VBox {

    private final Label selectionLabel = new Label("No channel selected");
    private final Label dbcLabel = new Label("");
    private final Label acceptedLabel = new Label("Confirmed \u2713");
    private final Button confirmSelected = new Button("Confirm Recommended");
    private final Button applyReview = new Button("Apply to Review channels");
    private final Button chooseDbcForAll = new Button("Change DBC");
    private final Button compareCandidates = new Button("Compare");
    private final Button markNeedsAttention = new Button("Mark for Review");
    private final Button exportMatrix = new Button("Export Matrix");
    private final Button proceed = new Button("Review & Process");
    private final Label savedHint = new Label("");
    private final Tooltip proceedTooltip = new Tooltip();
    private final Tooltip selectionTooltip = new Tooltip();
    private final Tooltip dbcTooltip = new Tooltip();
    private final Tooltip savedHintTooltip = new Tooltip();

    private Runnable onConfirmSelected = () -> { };
    private Runnable onApplyReview = () -> { };
    private Runnable onChooseDbcForAll = () -> { };
    private Runnable onCompare = () -> { };
    private Runnable onMarkNeedsAttention = () -> { };
    private Runnable onExport = () -> { };
    private Runnable onProceed = () -> { };
    private final HBox labelRow = new HBox();
    private final HBox actionRow = new HBox();
    private final HBox singleRow = new HBox();
    private final HBox buttonLine = new HBox();
    private final Region spacer = new Region();
    private boolean wrapped;

    public DecisionActionBar() {
        getStyleClass().addAll("decision-action-bar", "workstation-action-bar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(WorkstationLayoutMetrics.CONTROL_GAP);
        setPadding(new Insets(6, 10, 6, 10));
        setFillWidth(true);
        setMinHeight(44);
        setPrefHeight(Region.USE_COMPUTED_SIZE);
        setMaxWidth(Double.MAX_VALUE);
        setMinWidth(0);

        // Filename and DBC text shrink and ellipsize. The action cluster keeps its preferred
        // width. A right-aligned cluster with min width 0 used to be compressed and its buttons
        // were painted back over Confirm Recommended.
        selectionLabel.getStyleClass().add("decision-action-selection");
        selectionLabel.setMinWidth(48);
        selectionLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        selectionLabel.setTooltip(selectionTooltip);
        dbcLabel.getStyleClass().addAll("decision-action-dbc", "dbc-definition");
        dbcLabel.setMinWidth(72);
        dbcLabel.setMaxWidth(200);
        dbcLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        dbcLabel.setTooltip(dbcTooltip);
        acceptedLabel.getStyleClass().addAll("decision-action-accepted", "confirmed");
        acceptedLabel.setMinWidth(Region.USE_PREF_SIZE);
        acceptedLabel.setVisible(false);
        acceptedLabel.setManaged(false);
        savedHint.getStyleClass().addAll("muted", "decision-action-saved");
        savedHint.setWrapText(false);
        savedHint.setMinWidth(0);
        savedHint.setMaxWidth(420);
        savedHint.setTextOverrun(OverrunStyle.ELLIPSIS);
        savedHint.setTooltip(savedHintTooltip);

        style(confirmSelected, false);
        confirmSelected.getStyleClass().add("secondary-action");
        style(applyReview, false);
        applyReview.getStyleClass().add("secondary-action");
        applyReview.setVisible(false);
        applyReview.setManaged(false);
        applyReview.setTooltip(new Tooltip(
                "Apply the selected DBC only to Review channels where it matches CAN IDs. No Match channels are not changed."));
        style(chooseDbcForAll, false);
        chooseDbcForAll.setVisible(false);
        chooseDbcForAll.setManaged(false);
        style(compareCandidates, false);
        compareCandidates.getStyleClass().add("secondary-action");
        style(markNeedsAttention, false);
        markNeedsAttention.getStyleClass().add("needs-attention");
        markNeedsAttention.setTooltip(new Tooltip(
                "Add this channel to the Needs Attention filter for this session. The DBC mapping is not changed."));
        style(exportMatrix, false);
        exportMatrix.setVisible(false);
        exportMatrix.setManaged(false);
        proceed.getStyleClass().addAll("primary-button", "primary-action", "compact-button",
                "decision-proceed-button", "workstation-primary-button");
        proceed.setGraphic(StellarIcons.check());
        proceed.setGraphicTextGap(6);
        proceed.setMinHeight(WorkstationLayoutMetrics.CONTROL_HEIGHT);
        proceed.setPrefHeight(WorkstationLayoutMetrics.CONTROL_HEIGHT);
        proceed.setMinWidth(Region.USE_PREF_SIZE);
        proceed.setMaxWidth(Region.USE_PREF_SIZE);
        lockToLabelWidth(confirmSelected);
        lockToLabelWidth(compareCandidates);
        lockToLabelWidth(markNeedsAttention);
        lockToLabelWidth(proceed);
        proceed.setTextOverrun(OverrunStyle.CLIP);
        proceed.setWrapText(false);
        Tooltip.install(proceed, proceedTooltip);

        confirmSelected.setOnAction(e -> onConfirmSelected.run());
        applyReview.setOnAction(e -> onApplyReview.run());
        chooseDbcForAll.setOnAction(e -> onChooseDbcForAll.run());
        compareCandidates.setOnAction(e -> onCompare.run());
        markNeedsAttention.setOnAction(e -> onMarkNeedsAttention.run());
        exportMatrix.setOnAction(e -> onExport.run());
        proceed.setOnAction(e -> onProceed.run());

        HBox.setHgrow(selectionLabel, Priority.ALWAYS);
        HBox.setHgrow(dbcLabel, Priority.NEVER);
        savedHint.setMaxWidth(220);
        compareCandidates.getStyleClass().add("workstation-secondary-button");
        markNeedsAttention.getStyleClass().add("workstation-secondary-button");
        spacer.setMinWidth(8);
        HBox.setHgrow(spacer, Priority.ALWAYS);

        labelRow.setAlignment(Pos.CENTER_LEFT);
        labelRow.setSpacing(WorkstationLayoutMetrics.CONTROL_GAP);
        labelRow.setMinWidth(0);
        labelRow.setMaxWidth(Double.MAX_VALUE);
        actionRow.setAlignment(Pos.CENTER_RIGHT);
        actionRow.setSpacing(WorkstationLayoutMetrics.CONTROL_GAP);
        actionRow.setMinWidth(Region.USE_PREF_SIZE);
        actionRow.setMaxWidth(Region.USE_PREF_SIZE);
        actionRow.getChildren().setAll(chooseDbcForAll, compareCandidates, markNeedsAttention, exportMatrix, proceed);
        singleRow.setAlignment(Pos.CENTER_LEFT);
        singleRow.setSpacing(WorkstationLayoutMetrics.CONTROL_GAP);
        singleRow.setMinWidth(0);
        singleRow.setMaxWidth(Double.MAX_VALUE);
        buttonLine.setAlignment(Pos.CENTER_LEFT);
        buttonLine.setSpacing(WorkstationLayoutMetrics.CONTROL_GAP);
        buttonLine.setMinWidth(0);
        buttonLine.setMaxWidth(Double.MAX_VALUE);
        installSingleRow();
        widthProperty().addListener((obs, previous, width) -> arrange(width.doubleValue()));
        refresh(new ActionState("No channel selected", "", "", "Confirm Recommended",
                false, false, false, false, "", ""));
    }

    /**
     * One row while the filename can yield space. A second row only when the buttons
     * themselves no longer fit beside a readable filename and DBC label.
     */
    private void arrange(double width) {
        if (width <= 1) {
            return;
        }
        boolean wrap = width < singleRowMinimum();
        if (wrap == wrapped) {
            return;
        }
        if (wrap) {
            installWrapped();
        } else {
            installSingleRow();
        }
    }

    @Override
    protected void layoutChildren() {
        arrange(getWidth());
        super.layoutChildren();
    }

    private double singleRowMinimum() {
        double labels = selectionLabel.getMinWidth();
        if (dbcLabel.isManaged()) {
            labels += dbcLabel.getMinWidth();
        }
        if (acceptedLabel.isManaged()) {
            labels += acceptedLabel.prefWidth(-1);
        }
        if (savedHint.isManaged()) {
            labels += Math.min(80, Math.max(0, savedHint.prefWidth(-1)));
        }
        double buttons = Math.max(0, actionRow.prefWidth(-1));
        if (confirmSelected.isManaged()) {
            buttons += WorkstationLayoutMetrics.CONTROL_GAP + confirmSelected.prefWidth(-1);
        }
        if (applyReview.isManaged()) {
            buttons += WorkstationLayoutMetrics.CONTROL_GAP + applyReview.prefWidth(-1);
        }
        return labels + buttons + WorkstationLayoutMetrics.CONTROL_GAP + spacer.getMinWidth()
                + snappedLeftInset() + snappedRightInset();
    }

    private void installSingleRow() {
        wrapped = false;
        getStyleClass().remove("decision-action-bar-wrapped");
        dbcLabel.setMaxWidth(200);
        HBox.setHgrow(dbcLabel, Priority.NEVER);
        actionRow.setMaxWidth(Region.USE_PREF_SIZE);
        singleRow.getChildren().setAll(selectionLabel, dbcLabel, acceptedLabel, confirmSelected,
                applyReview, savedHint, spacer, actionRow);
        getChildren().setAll(singleRow);
        setMinHeight(44);
        setPrefHeight(Region.USE_COMPUTED_SIZE);
        setMaxHeight(56);
    }

    private void installWrapped() {
        wrapped = true;
        if (!getStyleClass().contains("decision-action-bar-wrapped")) {
            getStyleClass().add("decision-action-bar-wrapped");
        }
        dbcLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(dbcLabel, Priority.SOMETIMES);
        actionRow.setMaxWidth(Region.USE_PREF_SIZE);
        labelRow.getChildren().setAll(selectionLabel, dbcLabel, acceptedLabel, savedHint);
        buttonLine.getChildren().setAll(confirmSelected, applyReview, spacer, actionRow);
        getChildren().setAll(labelRow, buttonLine);
        setMinHeight(84);
        setPrefHeight(92);
        setMaxHeight(104);
    }

    public void setOnConfirmSelected(Runnable action) {
        this.onConfirmSelected = action == null ? () -> { } : action;
    }

    public void setOnApplyReview(Runnable action) {
        this.onApplyReview = action == null ? () -> { } : action;
    }

    public void setOnChooseDbcForAll(Runnable action) {
        this.onChooseDbcForAll = action == null ? () -> { } : action;
    }

    public void setOnCompare(Runnable action) {
        this.onCompare = action == null ? () -> { } : action;
    }

    public void setOnMarkNeedsAttention(Runnable action) {
        this.onMarkNeedsAttention = action == null ? () -> { } : action;
    }

    public void setOnExport(Runnable action) {
        this.onExport = action == null ? () -> { } : action;
    }

    public void setOnProceed(Runnable action) {
        this.onProceed = action == null ? () -> { } : action;
    }

    public record ActionState(
            String selectionText,
            String dbcName,
            String dbcStatus,
            String confirmLabel,
            boolean canConfirm,
            boolean accepted,
            boolean canCompare,
            boolean canProceed,
            String blockedHint,
            String readyTooltip,
            int compatibleReviewChannels
    ) {
        public ActionState(
                String selectionText,
                String dbcName,
                String dbcStatus,
                String confirmLabel,
                boolean canConfirm,
                boolean accepted,
                boolean canCompare,
                boolean canProceed,
                String blockedHint,
                String readyTooltip
        ) {
            this(selectionText, dbcName, dbcStatus, confirmLabel, canConfirm, accepted, canCompare,
                    canProceed, blockedHint, readyTooltip, 0);
        }
    }

    public void refresh(
            String selectionText,
            boolean canConfirm,
            boolean canChoose,
            boolean canCompare,
            boolean canProceed
    ) {
        refresh(selectionText, "", canConfirm, canChoose, canCompare, canProceed, "");
    }

    public void refresh(
            String selectionText,
            boolean canConfirm,
            boolean canChoose,
            boolean canCompare,
            boolean canProceed,
            String proceedHint
    ) {
        refresh(selectionText, "", canConfirm, canChoose, canCompare, canProceed, proceedHint);
    }

    public void refresh(
            String selectionText,
            String dbcName,
            boolean canConfirm,
            boolean canChoose,
            boolean canCompare,
            boolean canProceed,
            String proceedHint
    ) {
        refresh(new ActionState(
                selectionText,
                dbcName,
                "",
                "Confirm Recommended",
                canConfirm,
                false,
                canCompare,
                canProceed,
                proceedHint,
                canProceed ? "Continue to Processing with the current mapping selection." : ""));
        chooseDbcForAll.setDisable(!canChoose);
    }

    public void refresh(ActionState state) {
        ActionState s = state == null
                ? new ActionState("No channel selected", "", "", "Confirm Recommended",
                false, false, false, false, "", "")
                : state;
        selectionLabel.setText(s.selectionText() == null || s.selectionText().isBlank()
                ? "No channel selected" : s.selectionText());
        selectionTooltip.setText(selectionLabel.getText());
        boolean hasDbc = s.dbcName() != null && !s.dbcName().isBlank();
        String status = s.dbcStatus() == null ? "" : s.dbcStatus().trim();
        dbcLabel.setText(hasDbc
                ? (status.isBlank() ? "DBC: " + s.dbcName() : "DBC: " + s.dbcName() + " \u00b7 " + status)
                : "");
        dbcTooltip.setText(dbcLabel.getText());
        dbcLabel.setVisible(hasDbc);
        dbcLabel.setManaged(hasDbc);
        dbcLabel.getStyleClass().removeAll("recommended", "confirmed", "manual");
        if ("RECOMMENDED".equalsIgnoreCase(status)) {
            dbcLabel.getStyleClass().add("recommended");
        } else if ("CONFIRMED".equalsIgnoreCase(status)) {
            dbcLabel.getStyleClass().add("confirmed");
        } else if ("MANUAL".equalsIgnoreCase(status) || "SELECTED".equalsIgnoreCase(status)) {
            dbcLabel.getStyleClass().add("manual");
        }
        boolean showConfirm = !s.accepted() && s.confirmLabel() != null && !s.confirmLabel().isBlank();
        confirmSelected.setText(showConfirm ? s.confirmLabel() : "Confirm Recommended");
        confirmSelected.setDisable(!s.canConfirm());
        confirmSelected.setVisible(showConfirm);
        confirmSelected.setManaged(showConfirm);
        acceptedLabel.setVisible(s.accepted());
        acceptedLabel.setManaged(s.accepted());
        int reviewTargets = Math.max(0, s.compatibleReviewChannels());
        applyReview.setText(reviewTargets == 1
                ? "Apply to 1 Review channel"
                : "Apply to " + reviewTargets + " Review channels");
        applyReview.setVisible(reviewTargets > 0);
        applyReview.setManaged(reviewTargets > 0);
        applyReview.setDisable(reviewTargets <= 0);
        compareCandidates.setDisable(!s.canCompare());
        markNeedsAttention.setDisable(s.selectionText() == null || s.selectionText().isBlank()
                || s.selectionText().startsWith("No "));
        exportMatrix.setDisable(false);
        proceed.setDisable(!s.canProceed());
        String blocked = s.blockedHint() == null ? "" : s.blockedHint().trim();
        String ready = s.readyTooltip() == null || s.readyTooltip().isBlank()
                ? "Continue to Processing with the current mapping selection."
                : s.readyTooltip().trim();
        proceedTooltip.setText(s.canProceed()
                ? ready
                : (blocked.isBlank() ? "Processing is not ready yet." : blocked));
        boolean showReadyNote = s.canProceed() && ready.contains("recommended DBC");
        savedHint.setText(s.canProceed() ? (showReadyNote ? ready : "") : blocked);
        savedHintTooltip.setText(savedHint.getText());
        savedHint.setVisible(!savedHint.getText().isBlank());
        savedHint.setManaged(savedHint.isVisible());
        arrange(getWidth());
    }

    public Button confirmButtonForTest() {
        return confirmSelected;
    }

    public Button applyReviewButtonForTest() {
        return applyReview;
    }

    public Button proceedButtonForTest() {
        return proceed;
    }

    public Button chooseDbcForAllButtonForTest() {
        return chooseDbcForAll;
    }

    public Button compareButtonForTest() {
        return compareCandidates;
    }

    public Button markNeedsAttentionForTest() {
        return markNeedsAttention;
    }

    public Button exportButtonForTest() {
        return exportMatrix;
    }

    public String selectionTextForTest() {
        return selectionLabel.getText();
    }

    public String proceedHintForTest() {
        return proceedTooltip.getText();
    }

    public String confirmLabelForTest() {
        return confirmSelected.getText();
    }

    public boolean acceptedVisibleForTest() {
        return acceptedLabel.isVisible();
    }

    public String dbcTextForTest() {
        return dbcLabel.getText();
    }

    public String selectionTooltipForTest() {
        return selectionTooltip.getText();
    }

    public String dbcTooltipForTest() {
        return dbcTooltip.getText();
    }

    public boolean actionsWrappedForTest() {
        return wrapped;
    }

    public double selectionLabelWidthForTest() {
        return selectionLabel.getWidth();
    }

    public double selectionLabelPrefWidthForTest() {
        return selectionLabel.prefWidth(-1);
    }

    public double dbcLabelWidthForTest() {
        return dbcLabel.getWidth();
    }

    public String savedHintForTest() {
        return savedHint.getText();
    }

    public void fireProceedForTest() {
        onProceed.run();
    }

    /**
     * Stylesheet min-widths (110px / 168px) would otherwise win over the label size and
     * either stretch the button or let the row compress the text.
     */
    private static void lockToLabelWidth(Button button) {
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setMaxWidth(Region.USE_PREF_SIZE);
        button.minWidthProperty().addListener((obs, previous, value) -> {
            if (value == null || value.doubleValue() != Region.USE_PREF_SIZE) {
                button.setMinWidth(Region.USE_PREF_SIZE);
            }
        });
        button.maxWidthProperty().addListener((obs, previous, value) -> {
            if (value == null || value.doubleValue() != Region.USE_PREF_SIZE) {
                button.setMaxWidth(Region.USE_PREF_SIZE);
            }
        });
    }

    private static void style(Button button, boolean primary) {
        button.getStyleClass().addAll(
                primary ? "primary-button" : "secondary-button",
                "compact-button",
                "decision-action-button");
        button.setMinHeight(WorkstationLayoutMetrics.CONTROL_HEIGHT);
        button.setPrefHeight(WorkstationLayoutMetrics.CONTROL_HEIGHT);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setMaxWidth(Region.USE_PREF_SIZE);
        button.setTextOverrun(OverrunStyle.CLIP);
        button.setWrapText(false);
    }
}
