package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.desktop.presentation.CoverageClassificationPolicy;
import com.stellar.platform.desktop.presentation.CoverageClassificationPolicy.CoverageQuality;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.WorkingDbcSelectionStore.DisplayState;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.SourceChannelDefinitionCandidate;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Compact DBC dropdown for the active mapping channel.
 * Uses existing candidate/comparison results — no rematch or reparse.
 * Working selection is stored on {@link DesktopAppService}, not only in this control.
 */
public final class DbcCandidateStrip extends VBox {

    public record DbcOption(
            SourceChannelDefinitionCandidate candidate,
            boolean recommended,
            String statusLabel,
            String statusStyle
    ) {
        public String displayName() {
            return candidate == null ? "" : candidate.definitionDisplayName();
        }
    }

    private final Label title = new Label("Select DBC");
    private final ComboBox<DbcOption> combo = new ComboBox<>();
    private final Label mappingState = new Label("");
    private final Label coverageSummary = new Label("");
    private final Label qualityBadge = new Label("");
    private final HBox mappingSummaryRow = new HBox(10);
    private final Label empty = new Label("No DBC candidates available for this channel.");
    private final Button compareButton = new Button("Compare");
    private final Label recommendedHint = new Label("");
    private final Button useRecommendedButton = new Button("Use Recommended");
    private final HBox useRecommendedRow = new HBox(8);
    private final Label actionHint = new Label("");
    private final CoverageClassificationPolicy coveragePolicy = CoverageClassificationPolicy.defaults();
    private final NumberFormat numberFormat = NumberFormat.getIntegerInstance(Locale.US);
    private final NumberFormat percentFormat = NumberFormat.getNumberInstance(Locale.US);

    private DesktopAppService appService;
    private SourceChannelKey activeKey;
    private List<SourceChannelDefinitionCandidate> visible = List.of();
    private Runnable onMappingChanged = () -> { };
    private boolean syncing;

    public DbcCandidateStrip() {
        getStyleClass().add("dbc-selector-row");
        setSpacing(4);
        setPadding(new Insets(2, 0, 2, 0));
        setMinHeight(0);
        setPrefHeight(Region.USE_COMPUTED_SIZE);
        setMaxHeight(Region.USE_PREF_SIZE);
        percentFormat.setMaximumFractionDigits(2);
        percentFormat.setMinimumFractionDigits(0);

        title.getStyleClass().add("dbc-selector-title");
        empty.getStyleClass().addAll("muted", "dbc-candidate-empty");
        mappingState.getStyleClass().addAll("inventory-mapping-badge", "source-channel-mapping-badge");
        mappingState.setMinWidth(Region.USE_PREF_SIZE);
        coverageSummary.getStyleClass().add("dbc-selector-coverage");
        coverageSummary.setMinWidth(Region.USE_PREF_SIZE);
        qualityBadge.getStyleClass().addAll("coverage-quality-badge");
        qualityBadge.setMinWidth(Region.USE_PREF_SIZE);
        compareButton.getStyleClass().addAll("secondary-button", "secondary-action", "compact-button");
        compareButton.setOnAction(e -> compareVisible());
        recommendedHint.getStyleClass().addAll("muted", "dbc-recommended-hint");
        recommendedHint.setMinWidth(Region.USE_PREF_SIZE);
        useRecommendedButton.getStyleClass().addAll(
                "secondary-button", "secondary-action", "compact-button", "dbc-use-recommended");
        useRecommendedButton.setOnAction(e -> useRecommended());
        useRecommendedRow.setAlignment(Pos.CENTER_LEFT);
        useRecommendedRow.getStyleClass().add("dbc-use-recommended-row");
        useRecommendedRow.getChildren().setAll(recommendedHint, useRecommendedButton);
        hideUseRecommended();

        combo.getStyleClass().addAll("dbc-selector-combo", "dbc-definition");
        combo.setMinHeight(28);
        combo.setPrefHeight(30);
        combo.setMaxHeight(32);
        combo.setMinWidth(280);
        combo.setPrefWidth(420);
        combo.setMaxWidth(560);
        HBox.setHgrow(combo, Priority.SOMETIMES);
        combo.setButtonCell(optionCell(true));
        combo.setCellFactory(list -> optionCell(false));
        combo.setPromptText("Choose a DBC\u2026");
        combo.valueProperty().addListener((o, a, b) -> onComboChanged(b));
        actionHint.getStyleClass().addAll("muted", "dbc-action-hint");
        actionHint.setWrapText(true);
        actionHint.setMinHeight(Region.USE_PREF_SIZE);
        actionHint.setMaxWidth(Double.MAX_VALUE);

        HBox selector = new HBox(8, combo, compareButton);
        selector.setAlignment(Pos.CENTER_LEFT);
        selector.setMinHeight(Region.USE_PREF_SIZE);
        mappingSummaryRow.getStyleClass().add("decision-mapping-summary-row");
        mappingSummaryRow.setAlignment(Pos.CENTER_LEFT);
        mappingSummaryRow.setMinHeight(Region.USE_PREF_SIZE);
        mappingSummaryRow.getChildren().setAll(mappingState, coverageSummary, qualityBadge);
        getChildren().setAll(title, selector, useRecommendedRow, mappingSummaryRow, actionHint, empty);
        hideActionHint();
        showEmpty("No DBC candidates available for this channel.");
    }

    public void bind(DesktopAppService appService) {
        this.appService = Objects.requireNonNull(appService, "appService");
    }

    public void setOnMappingChanged(Runnable onMappingChanged) {
        this.onMappingChanged = onMappingChanged == null ? () -> { } : onMappingChanged;
    }

    public void clear() {
        activeKey = null;
        visible = List.of();
        syncing = true;
        try {
            combo.getItems().clear();
            combo.setValue(null);
        } finally {
            syncing = false;
        }
        hideUseRecommended();
        showEmpty("No DBC candidates available for this channel.");
    }

    public void refresh(SourceChannelKey key) {
        this.activeKey = key;
        if (appService == null || key == null) {
            clear();
            return;
        }
        Optional<SourceChannelMappingEntry> mapping = appService.channelMapping(key);
        List<SourceChannelDefinitionCandidate> ranked = orderCandidates(
                appService.candidatesFor(key), mapping.orElse(null));
        visible = ranked;
        title.setText("Select DBC for Ch" + key.channelId());
        if (ranked.isEmpty()) {
            showEmpty("No DBC candidates available for this channel.");
            hideUseRecommended();
            return;
        }
        empty.setVisible(false);
        empty.setManaged(false);
        combo.setVisible(true);
        combo.setManaged(true);
        compareButton.setDisable(false);
        mappingSummaryRow.setVisible(true);
        mappingSummaryRow.setManaged(true);
        mappingState.setVisible(true);
        mappingState.setManaged(true);
        coverageSummary.setVisible(true);
        coverageSummary.setManaged(true);
        qualityBadge.setVisible(true);
        qualityBadge.setManaged(true);

        Optional<DefinitionSourceId> displayed = displayedDefinition(key, mapping.orElse(null));
        List<DbcOption> options = new ArrayList<>();
        for (SourceChannelDefinitionCandidate candidate : ranked) {
            options.add(toOption(candidate, mapping.orElse(null), displayed.orElse(null)));
        }
        // An undecided tie / no-match has no DBC to show: a pre-filled value would look chosen
        // and re-picking it fires no change, leaving the engineer without a Confirm action.
        boolean awaitingChoice = displayState(key, mapping.orElse(null)) == DisplayState.NEEDS_ATTENTION;
        DbcOption selected = awaitingChoice ? null : currentSelection(options, displayed.orElse(null));
        syncing = true;
        try {
            combo.getItems().setAll(options);
            combo.setValue(selected);
        } finally {
            syncing = false;
        }
        if (awaitingChoice) {
            paintAwaitingChoice(mapping.orElse(null), ranked);
        } else {
            paintSummary(selected, mapping.orElse(null));
        }
        refreshUseRecommended(mapping.orElse(null), displayed.orElse(null));
    }

    public void compareVisible() {
        if (appService == null || activeKey == null) {
            return;
        }
        var comparisons = appService.comparisonsFor(activeKey);
        if (comparisons.isEmpty()) {
            return;
        }
        DbcCompareDialog.show(activeKey, comparisons);
    }

    public boolean hasCandidatesForTest() {
        return !visible.isEmpty();
    }

    public int candidateCountForTest() {
        return visible.size();
    }

    public Button compareButtonForTest() {
        return compareButton;
    }

    public Button useRecommendedButtonForTest() {
        return useRecommendedButton;
    }

    public boolean useRecommendedVisibleForTest() {
        return useRecommendedRow.isVisible();
    }

    public String recommendedHintForTest() {
        return recommendedHint.getText();
    }

    public void fireUseRecommendedForTest() {
        useRecommended();
    }

    public String emptyTextForTest() {
        return empty.getText();
    }

    public ComboBox<DbcOption> comboForTest() {
        return combo;
    }

    public String selectedDisplayNameForTest() {
        DbcOption value = combo.getValue();
        return value == null ? "" : value.displayName();
    }

    public List<String> optionNamesForTest() {
        return combo.getItems().stream().map(DbcOption::displayName).toList();
    }

    public List<String> optionStatusLabelsForTest() {
        return combo.getItems().stream().map(DbcOption::statusLabel).toList();
    }

    public void selectIndexForTest(int index) {
        if (index < 0 || index >= combo.getItems().size()) {
            return;
        }
        combo.getSelectionModel().select(index);
    }

    public DbcOption selectedOption() {
        return combo.getValue();
    }

    public Optional<DefinitionSourceId> selectedDefinitionId() {
        DbcOption value = combo.getValue();
        if (value == null || value.candidate() == null) {
            return Optional.empty();
        }
        return Optional.of(value.candidate().definitionSourceId());
    }

    public boolean selectedIsRecommended() {
        DbcOption value = combo.getValue();
        return value != null && value.recommended();
    }

    private void onComboChanged(DbcOption option) {
        if (syncing || option == null || option.candidate() == null) {
            return;
        }
        if (appService != null && activeKey != null) {
            appService.setWorkingDbcSelection(activeKey, option.candidate().definitionSourceId());
        }
        SourceChannelMappingEntry mapping = appService == null || activeKey == null
                ? null : appService.channelMapping(activeKey).orElse(null);
        paintSummary(option, mapping);
        refreshUseRecommended(mapping, option.candidate().definitionSourceId());
        onMappingChanged.run();
    }

    private void useRecommended() {
        if (appService == null || activeKey == null) {
            return;
        }
        SourceChannelMappingEntry mapping = appService.channelMapping(activeKey).orElse(null);
        if (!hasClearRecommendation(mapping)) {
            return;
        }
        DefinitionSourceId recommendedId = mapping.recommendedDefinitionId().get();
        appService.setWorkingDbcSelection(activeKey, recommendedId);
        DbcOption recommended = null;
        for (DbcOption option : combo.getItems()) {
            if (option.candidate() != null
                    && recommendedId.equals(option.candidate().definitionSourceId())) {
                recommended = option;
                break;
            }
        }
        if (recommended != null) {
            syncing = true;
            try {
                combo.setValue(recommended);
            } finally {
                syncing = false;
            }
            paintSummary(recommended, mapping);
            refreshUseRecommended(mapping, recommendedId);
        }
        onMappingChanged.run();
    }

    private void paintAwaitingChoice(SourceChannelMappingEntry mapping, List<SourceChannelDefinitionCandidate> ranked) {
        mappingState.setText("NEEDS ATTENTION");
        mappingState.getStyleClass().removeAll(
                "mapping-confirmed", "mapping-recommended", "mapping-manual",
                "mapping-attention", "mapping-mapped", "mapping-candidate",
                "status-recommended", "status-confirmed", "status-attention", "status-candidate",
                "status-selected");
        mappingState.getStyleClass().addAll("mapping-attention", "status-attention");
        mappingState.setVisible(true);
        mappingState.setManaged(true);
        coverageSummary.setText("");
        qualityBadge.setText("");
        qualityBadge.setVisible(false);
        qualityBadge.setManaged(false);
        boolean noMatch = mapping != null && mapping.status() == ChannelMappingStatus.NO_MATCH;
        if (noMatch) {
            showActionHint("No uploaded DBC matches any CAN ID on this channel. It will process without "
                    + "DBC-decoded signals and does not block the other channels. No DBC is assigned.");
            return;
        }
        long tied = ranked.stream().filter(c -> isTiedBest(c, mapping)).count();
        long matched = mapping == null ? 0L : mapping.matchedUniqueMessageCount();
        long observed = mapping == null ? 0L : mapping.observedUniqueMessageCount();
        showActionHint((tied > 1 ? tied + " DBCs match" : "Several DBCs match") + " this channel equally ("
                + numberFormat.format(matched) + " of " + numberFormat.format(observed) + " CAN IDs"
                + coveragePercentText(matched, observed) + "), so StellarAI will not pick one. "
                + "Choose a DBC above, review it with Compare, then use Confirm Selected DBC.");
    }

    private void paintSummary(DbcOption option, SourceChannelMappingEntry mapping) {
        if (option == null || option.candidate() == null) {
            mappingState.setText("");
            coverageSummary.setText("");
            qualityBadge.setText("");
            mappingState.setVisible(false);
            mappingState.setManaged(false);
            qualityBadge.setVisible(false);
            qualityBadge.setManaged(false);
            hideActionHint();
            return;
        }
        SourceChannelDefinitionCandidate candidate = option.candidate();
        CoverageQuality quality = coveragePolicy.classify(candidate.coverageRatio());
        DisplayState state = displayState(activeKey, mapping);
        String badge = switch (state) {
            case SELECTED -> "SELECTED";
            case CONFIRMED -> "CONFIRMED";
            case NEEDS_ATTENTION -> "NEEDS ATTENTION";
            case RECOMMENDED -> "RECOMMENDED";
            case NONE -> option.recommended() ? "RECOMMENDED" : "SELECTED";
        };
        mappingState.setText(badge);
        mappingState.getStyleClass().removeAll(
                "mapping-confirmed", "mapping-recommended", "mapping-manual",
                "mapping-attention", "mapping-mapped", "mapping-candidate",
                "status-recommended", "status-confirmed", "status-attention", "status-candidate",
                "status-selected");
        if (state == DisplayState.CONFIRMED) {
            mappingState.getStyleClass().addAll("mapping-confirmed", "status-confirmed");
        } else if (state == DisplayState.RECOMMENDED || option.recommended() && state != DisplayState.SELECTED) {
            mappingState.getStyleClass().addAll("mapping-recommended", "status-recommended");
        } else if (state == DisplayState.NEEDS_ATTENTION) {
            mappingState.getStyleClass().addAll("mapping-attention", "status-attention");
        } else {
            mappingState.getStyleClass().addAll("mapping-candidate", "status-candidate", "status-selected");
        }
        mappingState.setVisible(true);
        mappingState.setManaged(true);
        coverageSummary.setText(numberFormat.format(candidate.matchedUniqueMessageCount())
                + " of " + numberFormat.format(candidate.observedUniqueMessageCount())
                + " CAN IDs matched (" + percentFormat.format(candidate.coveragePercent()) + "%)");
        qualityBadge.setText(quality.badgeLabel());
        qualityBadge.getStyleClass().removeAll(
                "coverage-high", "coverage-medium", "coverage-low", "coverage-none");
        qualityBadge.getStyleClass().add(quality.styleClass());
        qualityBadge.setVisible(true);
        qualityBadge.setManaged(true);
        paintSelectionHint(candidate, state);
    }

    private void paintSelectionHint(SourceChannelDefinitionCandidate candidate, DisplayState state) {
        if (state == DisplayState.CONFIRMED) {
            long matched = candidate.matchedUniqueMessageCount();
            long observed = candidate.observedUniqueMessageCount();
            CoverageQuality quality = coveragePolicy.classify(candidate.coverageRatio());
            if (matched < observed && quality != CoverageQuality.HIGH) {
                showActionHint("Confirmed with " + quality.badgeLabel().toLowerCase(Locale.ROOT)
                        + ". Selected DBC matches " + numberFormat.format(matched) + " of "
                        + numberFormat.format(observed) + " CAN IDs" + coveragePercentText(matched, observed)
                        + ". This channel is processable.");
            } else {
                hideActionHint();
            }
            return;
        }
        long matched = candidate.matchedUniqueMessageCount();
        long observed = candidate.observedUniqueMessageCount();
        String coverage = "Selected DBC matches " + numberFormat.format(matched) + " of "
                + numberFormat.format(observed) + " CAN IDs" + coveragePercentText(matched, observed) + ". ";
        String confirm = state == DisplayState.RECOMMENDED ? "Confirm Recommended" : "Confirm Selected DBC";
        if (matched <= 0L) {
            showActionHint(coverage + "It decodes nothing on this channel; choose another DBC or use Auto Decode "
                    + "to process this channel without DBC-decoded signals.");
        } else if (matched < observed) {
            CoverageQuality quality = coveragePolicy.classify(candidate.coverageRatio());
            if (quality == CoverageQuality.LOW || quality == CoverageQuality.NONE) {
                String lead = state == DisplayState.RECOMMENDED
                        ? "Best available match: " + candidate.definitionDisplayName() + ". "
                        : "";
                showActionHint(lead + numberFormat.format(matched) + " of "
                        + numberFormat.format(observed) + " CAN IDs matched"
                        + coveragePercentText(matched, observed)
                        + ". Low coverage — review unmatched IDs before confirming.");
            } else {
                showActionHint(coverage + "Review the unmatched IDs with Compare, or use " + confirm
                        + " to accept this DBC for Ch" + activeKey.channelId() + ".");
            }
        } else {
            showActionHint(coverage + "Use " + confirm + " to accept this DBC for Ch" + activeKey.channelId() + ".");
        }
    }

    private String coveragePercentText(long matched, long observed) {
        return observed <= 0L ? "" : " (" + percentFormat.format(matched * 100.0 / observed) + "%)";
    }

    private void showActionHint(String text) {
        actionHint.setText(text);
        actionHint.setVisible(true);
        actionHint.setManaged(true);
    }

    private void hideActionHint() {
        actionHint.setText("");
        actionHint.setVisible(false);
        actionHint.setManaged(false);
    }

    private static boolean isTiedBest(SourceChannelDefinitionCandidate candidate, SourceChannelMappingEntry mapping) {
        return mapping != null
                && mapping.recommendationKind() == ChannelRecommendationKind.AMBIGUOUS
                && candidate.matchedUniqueMessageCount() > 0L
                && candidate.matchedUniqueMessageCount() == mapping.matchedUniqueMessageCount();
    }

    private void refreshUseRecommended(SourceChannelMappingEntry mapping, DefinitionSourceId displayed) {
        if (!hasClearRecommendation(mapping)) {
            hideUseRecommended();
            return;
        }
        DefinitionSourceId recommendedId = mapping.recommendedDefinitionId().get();
        if (displayed != null && displayed.equals(recommendedId)) {
            hideUseRecommended();
            return;
        }
        SourceChannelDefinitionCandidate recommended = null;
        for (SourceChannelDefinitionCandidate candidate : visible) {
            if (recommendedId.equals(candidate.definitionSourceId())) {
                recommended = candidate;
                break;
            }
        }
        String name = mapping.recommendedDisplayName();
        if (name == null || name.isBlank()) {
            name = recommended == null ? "recommended DBC" : recommended.definitionDisplayName();
        }
        if (recommended != null) {
            recommendedHint.setText("Recommended: " + name + " \u00b7 "
                    + percentFormat.format(recommended.coveragePercent()) + "%");
        } else {
            recommendedHint.setText("Recommended: " + name);
        }
        useRecommendedRow.setVisible(true);
        useRecommendedRow.setManaged(true);
        useRecommendedButton.setDisable(false);
    }

    private void hideUseRecommended() {
        recommendedHint.setText("");
        useRecommendedRow.setVisible(false);
        useRecommendedRow.setManaged(false);
    }

    private void showEmpty(String message) {
        empty.setText(message);
        empty.setVisible(true);
        empty.setManaged(true);
        combo.setVisible(false);
        combo.setManaged(false);
        compareButton.setDisable(true);
        hideActionHint();
        mappingState.setText("");
        mappingState.setVisible(false);
        mappingState.setManaged(false);
        coverageSummary.setText("");
        coverageSummary.setVisible(false);
        coverageSummary.setManaged(false);
        qualityBadge.setVisible(false);
        qualityBadge.setManaged(false);
        mappingSummaryRow.setVisible(false);
        mappingSummaryRow.setManaged(false);
        hideUseRecommended();
    }

    private Optional<DefinitionSourceId> displayedDefinition(
            SourceChannelKey key,
            SourceChannelMappingEntry mapping
    ) {
        if (appService == null) {
            return Optional.empty();
        }
        return appService.workingDbcSelections().displayedDefinition(key, mapping);
    }

    private DisplayState displayState(SourceChannelKey key, SourceChannelMappingEntry mapping) {
        if (appService == null) {
            return DisplayState.NONE;
        }
        return appService.workingDbcSelections().displayState(key, mapping);
    }

    private static List<SourceChannelDefinitionCandidate> orderCandidates(
            List<SourceChannelDefinitionCandidate> candidates,
            SourceChannelMappingEntry mapping
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Optional<DefinitionSourceId> recommended = mapping == null
                ? Optional.empty() : mapping.recommendedDefinitionId();
        if (recommended.isEmpty()) {
            return List.copyOf(candidates);
        }
        List<SourceChannelDefinitionCandidate> ordered = new ArrayList<>(candidates.size());
        SourceChannelDefinitionCandidate first = null;
        for (SourceChannelDefinitionCandidate candidate : candidates) {
            if (recommended.get().equals(candidate.definitionSourceId())) {
                first = candidate;
            } else {
                ordered.add(candidate);
            }
        }
        if (first != null) {
            ordered.add(0, first);
        }
        return List.copyOf(ordered);
    }

    /**
     * Only a clear best match is a recommendation. An ambiguous mapping also stores the top
     * candidate, but that winner comes from a name tie-break, not from engineering evidence.
     */
    private static boolean hasClearRecommendation(SourceChannelMappingEntry mapping) {
        return mapping != null
                && mapping.recommendationKind() == ChannelRecommendationKind.CLEAR_BEST_MATCH
                && mapping.recommendedDefinitionId().isPresent();
    }

    private static DbcOption toOption(
            SourceChannelDefinitionCandidate candidate,
            SourceChannelMappingEntry mapping,
            DefinitionSourceId displayed
    ) {
        boolean recommended = hasClearRecommendation(mapping)
                && mapping.recommendedDefinitionId().get().equals(candidate.definitionSourceId());
        boolean selected = displayed != null && displayed.equals(candidate.definitionSourceId());
        String status;
        String style;
        if (recommended) {
            status = "\u2605 RECOMMENDED";
            style = "status-recommended";
        } else if (selected) {
            status = "\u2713 SELECTED";
            style = "status-selected";
        } else if (isTiedBest(candidate, mapping)) {
            status = "TIED MATCH";
            style = "status-candidate";
        } else {
            status = "CANDIDATE";
            style = "status-candidate";
        }
        return new DbcOption(candidate, recommended, status, style);
    }

    private static DbcOption currentSelection(List<DbcOption> options, DefinitionSourceId displayed) {
        if (options.isEmpty()) {
            return null;
        }
        if (displayed != null) {
            for (DbcOption option : options) {
                if (option.candidate() != null
                        && displayed.equals(option.candidate().definitionSourceId())) {
                    return option;
                }
            }
        }
        for (DbcOption option : options) {
            if (option.recommended()) {
                return option;
            }
        }
        return options.getFirst();
    }

    private static ListCell<DbcOption> optionCell(boolean button) {
        return new ListCell<>() {
            @Override
            protected void updateItem(DbcOption item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("dbc-selector-option", "dbc-selector-button-cell");
                if (empty || item == null || item.candidate() == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                getStyleClass().add(button ? "dbc-selector-button-cell" : "dbc-selector-option");
                SourceChannelDefinitionCandidate candidate = item.candidate();
                NumberFormat numbers = NumberFormat.getIntegerInstance(Locale.US);
                NumberFormat percent = NumberFormat.getNumberInstance(Locale.US);
                percent.setMaximumFractionDigits(2);
                percent.setMinimumFractionDigits(0);
                if (button) {
                    String closedStatus = item.statusLabel();
                    if (closedStatus.startsWith("\u2605 ")) {
                        closedStatus = closedStatus.substring(2);
                    } else if (closedStatus.startsWith("\u2713 ")) {
                        closedStatus = closedStatus.substring(2);
                    }
                    setText(item.displayName() + "   " + closedStatus);
                    setGraphic(null);
                    return;
                }
                Label name = new Label(item.displayName());
                name.getStyleClass().add("dbc-selector-name");
                Label status = new Label(item.statusLabel());
                status.getStyleClass().addAll("decision-status-badge", item.statusStyle());
                Label ids = new Label(numbers.format(candidate.matchedUniqueMessageCount())
                        + " / " + numbers.format(candidate.observedUniqueMessageCount()) + " matched");
                ids.getStyleClass().add("muted");
                Label coverage = new Label(percent.format(candidate.coveragePercent()) + "%");
                coverage.getStyleClass().add("dbc-selector-coverage");
                HBox top = new HBox(8, name, status);
                top.setAlignment(Pos.CENTER_LEFT);
                HBox bottom = new HBox(10, ids, coverage);
                bottom.setAlignment(Pos.CENTER_LEFT);
                VBox block = new VBox(2, top, bottom);
                block.getStyleClass().add("dbc-selector-option-block");
                setText(null);
                setGraphic(block);
                setMinHeight(44);
                setPrefHeight(48);
            }
        };
    }

    public String mappingStateForTest() {
        return mappingState.getText();
    }

    public String coverageSummaryForTest() {
        return coverageSummary.getText();
    }

    public String qualityForTest() {
        return qualityBadge.getText();
    }

    public HBox mappingSummaryRowForTest() {
        return mappingSummaryRow;
    }

    public Label mappingStateLabelForTest() {
        return mappingState;
    }

    public Label coverageSummaryLabelForTest() {
        return coverageSummary;
    }

    public Label qualityBadgeForTest() {
        return qualityBadge;
    }

    public String actionHintForTest() {
        return actionHint.isVisible() ? actionHint.getText() : "";
    }
}
