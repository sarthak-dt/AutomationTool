package com.stellar.platform.desktop.ui.signals;

import com.stellar.platform.desktop.presentation.SignalBrowserFilter;
import com.stellar.platform.desktop.presentation.SignalChannelLabels;
import com.stellar.platform.desktop.presentation.SignalInspectorPresentation;
import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.desktop.ui.StellarIcons;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Right-side Signal Details inspector. Consumes catalog metadata only —
 * never reparses, realigns, or loads sample pages.
 */
public final class SignalInspectorPane extends VBox {

    public static final double PREF_WIDTH = SignalsLayoutMetrics.INSPECTOR_PREF_WIDTH;
    public static final double MIN_WIDTH = SignalsLayoutMetrics.INSPECTOR_MIN_WIDTH;
    public static final double MAX_WIDTH = SignalsLayoutMetrics.INSPECTOR_MAX_WIDTH;
    private static final double COLLAPSED_WIDTH = 28;

    private enum InspectorTab {
        OVERVIEW, STATISTICS, QUALITY, LINEAGE
    }

    private final Label headerLabel = new Label("Signal Details");
    private final Button collapseButton = new Button("\u00AB");
    private final Button copyNameButton = new Button("Copy");
    private final Label signalNameLabel = new Label();
    private final Label sourceBadge = new Label();
    private final Label metaLine = new Label();
    private final ToggleGroup tabGroup = new ToggleGroup();
    private final ToggleButton overviewTab = new ToggleButton("Overview");
    private final ToggleButton statisticsTab = new ToggleButton("Statistics");
    private final ToggleButton qualityTab = new ToggleButton("Quality");
    private final ToggleButton lineageTab = new ToggleButton("Lineage");
    private final HBox tabRow = new HBox(4);
    private final VBox sectionsBox = new VBox(10);
    private final VBox contentBox = new VBox(10);
    private final VBox emptyState = new VBox(8);
    private final Label emptyTitle = new Label("Signal Details");
    private final Label emptyBody = new Label("Select a signal in the browser or workspace to inspect its metadata.");
    private final Label emptyHint = new Label("Details stay in sync with the focused signal without reloading samples.");
    private final ScrollPane scroll = new ScrollPane();
    private final VBox expandedBody = new VBox(10);
    private final Button addFavoriteButton = new Button("Add to Favorites");

    private SignalsScreenModel model;
    private Consumer<Boolean> collapseListener = collapsed -> { };
    private boolean collapsed;
    private double restoredWidth = PREF_WIDTH;
    private Runnable favoritesListener;
    private InspectorTab activeTab = InspectorTab.OVERVIEW;
    private SignalCatalogEntry currentEntry;
    private boolean disposed;
    private final List<InspectorSectionView> sectionViews = new java.util.ArrayList<>();
    private final InspectorPlaceholderView placeholderView = new InspectorPlaceholderView();
    private final InspectorSectionView lineageView = new InspectorSectionView();

    public SignalInspectorPane() {
        getStyleClass().addAll("signal-inspector-pane");
        setSpacing(8);
        setPadding(new Insets(SignalsLayoutMetrics.INNER_PADDING));
        setMinWidth(MIN_WIDTH);
        setPrefWidth(PREF_WIDTH);
        setMaxWidth(MAX_WIDTH);
        setFillWidth(true);
        setMinHeight(0);

        headerLabel.getStyleClass().add("inspector-header");
        collapseButton.setText(null);
        collapseButton.setGraphic(StellarIcons.collapseLeft());
        collapseButton.getStyleClass().addAll("compact-button", "inspector-collapse", "icon-button");
        collapseButton.setFocusTraversable(false);
        collapseButton.setTooltip(new Tooltip("Hide inspector"));
        collapseButton.setOnAction(e -> setCollapsed(!collapsed));
        copyNameButton.setText(null);
        copyNameButton.setGraphic(StellarIcons.copy());
        copyNameButton.getStyleClass().addAll("compact-button", "inspector-copy", "icon-button");
        copyNameButton.setFocusTraversable(false);
        copyNameButton.setMinWidth(28);
        copyNameButton.setPrefWidth(28);
        copyNameButton.setTooltip(new Tooltip("Copy signal name"));
        copyNameButton.setOnAction(e -> copyText(signalNameLabel.getText()));
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(6, headerLabel, headerSpacer, copyNameButton, collapseButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("inspector-header-row");

        signalNameLabel.getStyleClass().add("inspector-signal-name");
        signalNameLabel.setWrapText(false);
        signalNameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        signalNameLabel.setMaxWidth(Double.MAX_VALUE);
        signalNameLabel.setMinWidth(0);
        sourceBadge.getStyleClass().add("source-badge");
        metaLine.getStyleClass().add("inspector-meta-line");
        metaLine.setTextOverrun(OverrunStyle.ELLIPSIS);
        metaLine.setMaxWidth(Double.MAX_VALUE);

        configureTab(overviewTab, InspectorTab.OVERVIEW);
        configureTab(statisticsTab, InspectorTab.STATISTICS);
        configureTab(qualityTab, InspectorTab.QUALITY);
        configureTab(lineageTab, InspectorTab.LINEAGE);
        overviewTab.setSelected(true);
        tabRow.getStyleClass().add("inspector-tab-row");
        tabRow.setAlignment(Pos.CENTER_LEFT);
        tabRow.getChildren().setAll(overviewTab, statisticsTab, qualityTab, lineageTab);

        emptyTitle.getStyleClass().add("empty-state-title");
        emptyBody.getStyleClass().add("empty-state-body");
        emptyHint.getStyleClass().add("muted");
        emptyBody.setWrapText(true);
        emptyHint.setWrapText(true);
        emptyState.getChildren().setAll(emptyTitle, emptyBody, emptyHint);
        emptyState.getStyleClass().addAll("empty-state", "inspector-empty-card");
        emptyState.setAlignment(Pos.TOP_LEFT);
        emptyState.setPadding(new Insets(8, 4, 8, 4));

        addFavoriteButton.getStyleClass().addAll("primary-button", "inspector-favorite-button");
        addFavoriteButton.setMaxWidth(Double.MAX_VALUE);
        addFavoriteButton.setOnAction(e -> toggleFavorite());

        contentBox.getChildren().setAll(signalNameLabel, sourceBadge, metaLine, tabRow, sectionsBox);
        expandedBody.getChildren().setAll(contentBox, emptyState);
        VBox.setVgrow(expandedBody, Priority.ALWAYS);

        scroll.setContent(expandedBody);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("inspector-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(header, scroll, addFavoriteButton);
        showEmpty();
    }

    private void configureTab(ToggleButton tab, InspectorTab kind) {
        tab.setToggleGroup(tabGroup);
        tab.getStyleClass().add("inspector-tab");
        tab.setFocusTraversable(false);
        tab.setOnAction(e -> {
            activeTab = kind;
            if (currentEntry != null) {
                renderSections(currentEntry);
            }
        });
    }

    public void setModel(SignalsScreenModel model) {
        if (this.model != null) {
            this.model.removeFavoritesListener(favoritesListener);
        }
        this.model = Objects.requireNonNull(model, "model");
        favoritesListener = () -> javafx.application.Platform.runLater(this::refreshFavoritePresentation);
        this.model.addFavoritesListener(favoritesListener);
        refresh();
    }

    /** Update star only — no catalog/sample I/O. */
    public void refreshFavoritePresentation() {
        if (disposed || model == null) {
            return;
        }
        model.focusedSignalKey().ifPresent(this::updateFavoriteButton);
    }

    public void setCollapseListener(Consumer<Boolean> listener) {
        this.collapseListener = listener == null ? c -> { } : listener;
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (model != null && favoritesListener != null) {
            model.removeFavoritesListener(favoritesListener);
        }
        favoritesListener = null;
        model = null;
        collapseListener = collapsed -> { };
        currentEntry = null;
        sectionsBox.getChildren().clear();
        sectionViews.clear();
    }

    boolean disposedForTest() {
        return disposed;
    }

    int sectionNodeCountForTest() {
        return sectionsBox.getChildren().size();
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    private boolean applyingCollapse;

    public void setCollapsed(boolean collapse) {
        if (this.collapsed == collapse || applyingCollapse) {
            return;
        }
        applyingCollapse = true;
        this.collapsed = collapse;
        if (collapse) {
            if (!getStyleClass().contains("inspector-collapsed")) {
                getStyleClass().add("inspector-collapsed");
            }
            restoredWidth = Math.max(MIN_WIDTH, getWidth() > 40 ? getWidth() : getPrefWidth());
            scroll.setVisible(false);
            scroll.setManaged(false);
            addFavoriteButton.setVisible(false);
            addFavoriteButton.setManaged(false);
            headerLabel.setVisible(false);
            headerLabel.setManaged(false);
            copyNameButton.setVisible(false);
            copyNameButton.setManaged(false);
            collapseButton.setText(null);
            collapseButton.setGraphic(StellarIcons.expandRight());
            collapseButton.setTooltip(new Tooltip("Show inspector"));
            setMinWidth(COLLAPSED_WIDTH);
            setPrefWidth(COLLAPSED_WIDTH);
            setMaxWidth(COLLAPSED_WIDTH);
            setPadding(new Insets(8, 4, 8, 4));
        } else {
            getStyleClass().remove("inspector-collapsed");
            scroll.setVisible(true);
            scroll.setManaged(true);
            addFavoriteButton.setVisible(currentEntry != null);
            addFavoriteButton.setManaged(currentEntry != null);
            headerLabel.setVisible(true);
            headerLabel.setManaged(true);
            copyNameButton.setVisible(currentEntry != null);
            copyNameButton.setManaged(currentEntry != null);
            collapseButton.setText(null);
            collapseButton.setGraphic(StellarIcons.collapseLeft());
            collapseButton.setTooltip(new Tooltip("Hide inspector"));
            setMinWidth(MIN_WIDTH);
            setPrefWidth(restoredWidth);
            setMaxWidth(MAX_WIDTH);
            setPadding(new Insets(12, 12, 12, 12));
        }
        try {
            collapseListener.accept(collapsed);
        } finally {
            applyingCollapse = false;
        }
    }

    /** Lightweight metadata refresh from current focused key — no sample I/O. */
    public void refresh() {
        if (disposed || model == null) {
            showEmpty();
            return;
        }
        Optional<SignalCatalogKey> focused = model.focusedSignalKey();
        if (focused.isEmpty()) {
            showEmpty();
            return;
        }
        Optional<SignalCatalogEntry> entry = model.findEntry(focused.get());
        if (entry.isEmpty()) {
            showEmpty();
            return;
        }
        showEntry(entry.get());
    }

    private void showEmpty() {
        currentEntry = null;
        contentBox.setVisible(false);
        contentBox.setManaged(false);
        emptyState.setVisible(true);
        emptyState.setManaged(true);
        sectionsBox.getChildren().clear();
        signalNameLabel.setText("");
        sourceBadge.setText("");
        sourceBadge.setVisible(false);
        sourceBadge.setManaged(false);
        metaLine.setText("");
        metaLine.setVisible(false);
        metaLine.setManaged(false);
        copyNameButton.setVisible(false);
        copyNameButton.setManaged(false);
        addFavoriteButton.setVisible(false);
        addFavoriteButton.setManaged(false);
    }

    private void showEntry(SignalCatalogEntry entry) {
        currentEntry = entry;
        emptyState.setVisible(false);
        emptyState.setManaged(false);
        contentBox.setVisible(true);
        contentBox.setManaged(true);
        addFavoriteButton.setVisible(!collapsed);
        addFavoriteButton.setManaged(!collapsed);
        copyNameButton.setVisible(!collapsed);
        copyNameButton.setManaged(!collapsed);

        signalNameLabel.setText(entry.signalName());
        signalNameLabel.setTooltip(new Tooltip(entry.signalName()));
        updateFavoriteButton(entry.key());

        sourceBadge.setText(SignalBrowserFilter.sourceBadge(entry.sourceType()));
        sourceBadge.getStyleClass().setAll("source-badge", "badge", SignalBrowserFilter.sourceStyleClass(entry.sourceType()));
        if (entry.sourceType() == com.stellar.platform.domain.catalog.SignalSourceType.DBC_CAN) {
            sourceBadge.getStyleClass().addAll("badge-dbc", "dbc-badge");
        } else if (entry.sourceType() == com.stellar.platform.domain.catalog.SignalSourceType.MF4_NATIVE) {
            sourceBadge.getStyleClass().addAll("badge-native", "native-badge");
        }
        sourceBadge.setVisible(true);
        sourceBadge.setManaged(true);

        String channel = SignalChannelLabels.compact(entry);
        String unit = entry.unit() == null || entry.unit().isBlank() ? "" : entry.unit();
        String meta = channel;
        if (!unit.isBlank()) {
            meta = "—".equals(channel) ? unit : channel + " · " + unit;
        }
        metaLine.setText(meta);
        metaLine.setVisible(meta != null && !meta.isBlank());
        metaLine.setManaged(metaLine.isVisible());

        renderSections(entry);
    }

    private void renderSections(SignalCatalogEntry entry) {
        sectionsBox.getChildren().clear();
        List<SignalInspectorPresentation.Section> all = SignalInspectorPresentation.sections(
                entry, model == null ? List.of(entry) : model.catalog().entries());
        switch (activeTab) {
            case STATISTICS -> {
                Optional<SignalInspectorPresentation.Section> stats = all.stream()
                        .filter(s -> s.title().equals("Statistics"))
                        .findFirst();
                if (stats.isPresent()) {
                    InspectorSectionView view = sectionView(0);
                    view.bind(stats.get());
                    sectionsBox.getChildren().add(view.root);
                } else {
                    placeholderView.bind("Statistics", "No statistics are available for this signal.");
                    sectionsBox.getChildren().add(placeholderView.root);
                }
            }
            case QUALITY -> {
                placeholderView.bind(
                        "Quality",
                        "Quality metrics are not available in the current catalog metadata.");
                sectionsBox.getChildren().add(placeholderView.root);
            }
            case LINEAGE -> {
                List<SignalInspectorPresentation.Section> coverage = all.stream()
                        .filter(s -> s.title().equals("Measurement") || s.title().equals("Measurement Coverage"))
                        .toList();
                if (coverage.isEmpty()) {
                    String measurement = entry.sourceFile() == null || entry.sourceFile().isBlank()
                            ? "—"
                            : entry.sourceFile();
                    lineageView.bind(new SignalInspectorPresentation.Section("Lineage", List.of(
                            new SignalInspectorPresentation.Row("Measurement", measurement, true),
                            new SignalInspectorPresentation.Row(
                                    "Type", SignalBrowserFilter.sourceBadge(entry.sourceType())))));
                    sectionsBox.getChildren().add(lineageView.root);
                } else {
                    InspectorSectionView view = sectionView(0);
                    view.bind(coverage.getFirst());
                    sectionsBox.getChildren().add(view.root);
                }
            }
            case OVERVIEW -> {
                int used = 0;
                for (SignalInspectorPresentation.Section section : all) {
                    if ("Statistics".equals(section.title()) || "Technical".equals(section.title())) {
                        continue;
                    }
                    InspectorSectionView view = sectionView(used++);
                    view.bind(section);
                    sectionsBox.getChildren().add(view.root);
                }
            }
        }
    }

    private InspectorSectionView sectionView(int index) {
        while (sectionViews.size() <= index) {
            sectionViews.add(new InspectorSectionView());
        }
        return sectionViews.get(index);
    }

    private void toggleFavorite() {
        if (model == null) {
            return;
        }
        model.focusedSignalKey().ifPresent(key -> {
            model.toggleFavorite(key);
            updateFavoriteButton(key);
        });
    }

    private void updateFavoriteButton(SignalCatalogKey key) {
        boolean on = model != null && model.isFavorite(key);
        addFavoriteButton.setText(on ? "Remove from Favorites" : "Add to Favorites");
        addFavoriteButton.setGraphic(on ? StellarIcons.starFilled() : StellarIcons.starOutline());
        addFavoriteButton.setGraphicTextGap(8);
    }

    private static String copyTooltipFor(String label) {
        if (label == null) {
            return "Copy";
        }
        return switch (label) {
            case "CAN ID" -> "Copy CAN ID";
            case "Measurement" -> "Copy measurement name";
            case "Definition", "DBC File" -> "Copy definition";
            default -> "Copy " + label;
        };
    }

    private static void copyText(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private static final class InspectorPlaceholderView {
        private final VBox root = new VBox(6);
        private final Label title = new Label();
        private final Label body = new Label();

        private InspectorPlaceholderView() {
            root.getStyleClass().addAll("inspector-section", "inspector-info-card");
            title.getStyleClass().add("inspector-section-title");
            body.getStyleClass().add("muted");
            body.setWrapText(true);
            root.getChildren().addAll(title, body);
        }

        private void bind(String titleText, String bodyText) {
            title.setText(titleText);
            body.setText(bodyText);
        }
    }

    private static final class InspectorSectionView {
        private final VBox root = new VBox(6);
        private final Label title = new Label();
        private final List<InspectorRowView> rows = new java.util.ArrayList<>();

        private InspectorSectionView() {
            root.getStyleClass().addAll("inspector-section", "inspector-info-card");
            title.getStyleClass().add("inspector-section-title");
            root.getChildren().add(title);
        }

        private void bind(SignalInspectorPresentation.Section section) {
            title.setText(section.title());
            while (rows.size() < section.rows().size()) {
                InspectorRowView row = new InspectorRowView();
                rows.add(row);
            }
            if (root.getChildren().size() > 1) {
                root.getChildren().remove(1, root.getChildren().size());
            }
            for (int i = 0; i < section.rows().size(); i++) {
                InspectorRowView row = rows.get(i);
                row.bind(section.rows().get(i));
                root.getChildren().add(row.root);
            }
        }
    }

    private static final class InspectorRowView {
        private final HBox root = new HBox(8);
        private final Label label = new Label();
        private final Label value = new Label();
        private final Tooltip valueTip = new Tooltip();
        private final Tooltip copyTip = new Tooltip();
        private final Button copy = new Button();
        private String copyExact = "";

        private InspectorRowView() {
            label.getStyleClass().add("inspector-label");
            label.setMinWidth(86);
            label.setPrefWidth(96);
            value.getStyleClass().add("inspector-value");
            value.setWrapText(false);
            value.setTextOverrun(OverrunStyle.ELLIPSIS);
            value.setMaxWidth(Double.MAX_VALUE);
            value.setTooltip(valueTip);
            HBox.setHgrow(value, Priority.ALWAYS);
            copy.setGraphic(StellarIcons.copy());
            copy.getStyleClass().addAll("compact-button", "inspector-copy", "icon-button");
            copy.setFocusTraversable(false);
            copy.setMinWidth(28);
            copy.setPrefWidth(28);
            copy.setTooltip(copyTip);
            copy.setOnAction(e -> copyText(copyExact));
            root.setAlignment(Pos.CENTER_LEFT);
            root.getStyleClass().add("inspector-row");
            root.getChildren().addAll(label, value);
        }

        private void bind(SignalInspectorPresentation.Row row) {
            label.setText(row.label());
            value.setText(row.value());
            valueTip.setText(row.exactValue());
            copyExact = row.exactValue();
            copyTip.setText(copyTooltipFor(row.label()));
            if (row.copyable()) {
                if (!root.getChildren().contains(copy)) {
                    root.getChildren().add(copy);
                }
            } else {
                root.getChildren().remove(copy);
            }
        }
    }
}
