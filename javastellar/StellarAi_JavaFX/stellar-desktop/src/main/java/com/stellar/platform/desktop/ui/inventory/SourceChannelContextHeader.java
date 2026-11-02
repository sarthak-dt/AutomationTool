package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.desktop.ui.StellarIcons;
import com.stellar.platform.desktop.ui.theme.StellarTheme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Compact Source / Channel context header for Source Inventory.
 * Presentation only — consumes already-computed model values.
 */
public final class SourceChannelContextHeader extends VBox {

    private final Label titleLabel = new Label("");
    private final Label readyBadge = new Label("");
    private final Label activeChannelHint = new Label("Active Channel");
    private final HBox titleRow = new HBox(8);
    private final Region titleSpacer = new Region();
    private final HBox trailingSlot = new HBox(6);
    private final Button overflowButton = new Button("\u22EF");
    private final HBox metadataRow = new HBox(8);
    private final ScrollPane metadataScroll = new ScrollPane(metadataRow);
    private final Label aggregateLine = new Label("");

    private final MetaTile measurementTile = MetaTile.measurement();
    private final MetaTile channelTile = MetaTile.neutral();
    private final MetaTile idsTile = MetaTile.neutral();
    private final MetaTile framesTile = MetaTile.neutral();
    private final MetaTile dbcTile = MetaTile.definition();
    private final Label mappingBadge = new Label("");
    private final Label matchStat = new Label("");
    private final Label coverageStat = new Label("");
    private final HBox mappingCluster = new HBox(8);

    public SourceChannelContextHeader() {
        getStyleClass().add("source-channel-context-header");
        setSpacing(4);
        setPadding(new Insets(2, 0, 2, 0));
        setMinHeight(0);
        setPrefHeight(Region.USE_COMPUTED_SIZE);
        setMaxHeight(Region.USE_PREF_SIZE);
        setFillWidth(true);

        titleLabel.getStyleClass().add("source-channel-context-title");
        readyBadge.getStyleClass().addAll("status-badge", "badge", "source-channel-status-badge");
        activeChannelHint.getStyleClass().add("source-channel-active-hint");
        activeChannelHint.setGraphic(dot());
        activeChannelHint.setGraphicTextGap(5);

        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        trailingSlot.setAlignment(Pos.CENTER_RIGHT);
        overflowButton.getStyleClass().addAll("secondary-button", "compact-button", "source-channel-overflow");
        overflowButton.setFocusTraversable(false);
        overflowButton.setVisible(false);
        overflowButton.setManaged(false);

        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.getStyleClass().add("source-channel-context-title-row");
        titleRow.getChildren().setAll(
                titleLabel, readyBadge, activeChannelHint, titleSpacer, trailingSlot, overflowButton);

        metadataRow.getStyleClass().add("source-channel-context-tiles");
        metadataRow.setAlignment(Pos.CENTER_LEFT);
        metadataRow.setMinHeight(Region.USE_PREF_SIZE);
        metadataRow.setMaxHeight(Region.USE_PREF_SIZE);
        metadataScroll.setFitToHeight(true);
        metadataScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        metadataScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        metadataScroll.getStyleClass().add("source-channel-metadata-scroll");
        metadataScroll.setMinHeight(0);
        metadataScroll.setPrefHeight(Region.USE_COMPUTED_SIZE);
        metadataScroll.setMaxHeight(Region.USE_PREF_SIZE);
        metadataScroll.setMinWidth(0);

        mappingBadge.getStyleClass().addAll("inventory-mapping-badge", "source-channel-mapping-badge");
        matchStat.getStyleClass().addAll("match-stat", "source-channel-match-stat");
        coverageStat.getStyleClass().addAll("match-stat", "source-channel-coverage-stat");
        mappingCluster.setAlignment(Pos.CENTER_LEFT);
        mappingCluster.getStyleClass().add("source-channel-mapping-cluster");
        mappingCluster.getChildren().setAll(mappingBadge, matchStat, coverageStat);

        aggregateLine.getStyleClass().addAll("muted", "source-channel-aggregate-line");
        aggregateLine.setWrapText(true);
        aggregateLine.setMaxWidth(Double.MAX_VALUE);
        aggregateLine.setVisible(false);
        aggregateLine.setManaged(false);

        mappingCluster.setVisible(false);
        mappingCluster.setManaged(false);
        getChildren().setAll(titleRow, metadataScroll, aggregateLine);
        clear();
    }

    public void setTrailingActions(Node... nodes) {
        trailingSlot.getChildren().setAll(nodes == null ? new Node[0] : nodes);
    }

    public void clear() {
        titleLabel.setText("");
        readyBadge.setText("");
        readyBadge.setVisible(false);
        readyBadge.setManaged(false);
        activeChannelHint.setVisible(false);
        activeChannelHint.setManaged(false);
        measurementTile.hide();
        channelTile.hide();
        idsTile.hide();
        framesTile.hide();
        dbcTile.hide();
        mappingBadge.setText("");
        mappingBadge.setVisible(false);
        mappingBadge.setManaged(false);
        matchStat.setText("");
        matchStat.setVisible(false);
        matchStat.setManaged(false);
        coverageStat.setText("");
        coverageStat.setVisible(false);
        coverageStat.setManaged(false);
        aggregateLine.setText("");
        aggregateLine.setVisible(false);
        aggregateLine.setManaged(false);
        metadataRow.getChildren().clear();
        overflowButton.setTooltip(null);
        overflowButton.setVisible(false);
        overflowButton.setManaged(false);
    }

    public void showMessage(String title, String message) {
        clear();
        titleLabel.setText(title == null ? "" : title);
        if (message != null && !message.isBlank()) {
            aggregateLine.setText(message);
            aggregateLine.setVisible(true);
            aggregateLine.setManaged(true);
        }
    }

    public void show(
            String title,
            String statusLabel,
            String statusStyle,
            boolean showActiveChannel,
            String formatBadge,
            String compactFileName,
            String fullFileName,
            String measurementTooltip,
            String channelPrimary,
            String channelCaption,
            String idsValue,
            String idsCaption,
            String framesValue,
            String framesCaption,
            boolean showDbcTile,
            String dbcName,
            String dbcCaption,
            String mappingBadgeText,
            String mappingStyle,
            String matchText,
            String coverageText,
            String matchTooltip,
            String mappingTooltip,
            String aggregateDetail
    ) {
        titleLabel.setText(title == null ? "" : title);

        boolean hasStatus = statusLabel != null && !statusLabel.isBlank();
        readyBadge.setText(hasStatus ? statusLabel : "");
        readyBadge.setVisible(hasStatus);
        readyBadge.setManaged(hasStatus);
        readyBadge.getStyleClass().removeAll(
                "badge-ready", "badge-running", "badge-unsupported", "inventory-status-ready",
                "inventory-status-warning", "status-ready", "status-attention");
        if (hasStatus) {
            switch (statusStyle == null ? "" : statusStyle) {
                case "ready" -> readyBadge.getStyleClass().addAll(
                        "badge-ready", "status-ready", "inventory-status-ready");
                case "running" -> readyBadge.getStyleClass().add("badge-running");
                default -> readyBadge.getStyleClass().addAll(
                        "badge-unsupported", "status-attention", "inventory-status-warning");
            }
        }

        activeChannelHint.setVisible(showActiveChannel);
        activeChannelHint.setManaged(showActiveChannel);

        boolean showMeas = (formatBadge != null && !formatBadge.isBlank())
                || (compactFileName != null && !compactFileName.isBlank());
        measurementTile.applyMeasurement(
                formatBadge == null ? "" : formatBadge,
                compactFileName == null ? "" : compactFileName,
                measurementTooltip);
        measurementTile.setVisible(showMeas);
        measurementTile.setManaged(showMeas);

        boolean showChannel = channelPrimary != null && !channelPrimary.isBlank();
        channelTile.applyValue(channelPrimary, channelCaption == null ? "Channel" : channelCaption, null);
        channelTile.setVisible(showChannel);
        channelTile.setManaged(showChannel);

        boolean showIds = idsValue != null && !idsValue.isBlank();
        idsTile.applyValue(idsValue, idsCaption == null ? "CAN IDs" : idsCaption, null);
        idsTile.setVisible(showIds);
        idsTile.setManaged(showIds);

        boolean showFrames = framesValue != null && !framesValue.isBlank();
        framesTile.applyValue(framesValue, framesCaption == null ? "Frames" : framesCaption, null);
        framesTile.setVisible(showFrames);
        framesTile.setManaged(showFrames);

        boolean dbcVisible = showDbcTile && dbcName != null && !dbcName.isBlank();
        dbcTile.applyDefinition(
                dbcName == null ? "" : dbcName,
                dbcCaption == null ? "DBC File" : dbcCaption,
                dbcName == null || dbcName.isBlank() ? null : "DBC: " + dbcName);
        dbcTile.setVisible(dbcVisible);
        dbcTile.setManaged(dbcVisible);

        mappingBadge.getStyleClass().removeAll(
                "mapping-confirmed", "mapping-recommended", "mapping-manual",
                "mapping-attention", "mapping-mapped",
                "status-recommended", "status-confirmed", "status-attention");
        boolean hasBadge = mappingBadgeText != null && !mappingBadgeText.isBlank();
        mappingBadge.setText(hasBadge ? mappingBadgeText : "");
        mappingBadge.setVisible(hasBadge);
        mappingBadge.setManaged(hasBadge);
        if (hasBadge) {
            String style = mappingStyle == null ? "" : mappingStyle;
            mappingBadge.getStyleClass().add("mapping-" + style);
            switch (style) {
                case "recommended" -> mappingBadge.getStyleClass().add("status-recommended");
                case "confirmed", "manual", "mapped" -> mappingBadge.getStyleClass().add("status-confirmed");
                case "attention" -> mappingBadge.getStyleClass().add("status-attention");
                default -> {
                }
            }
            mappingBadge.setTooltip(mappingTooltip == null || mappingTooltip.isBlank()
                    ? null : new Tooltip(mappingTooltip));
        } else {
            mappingBadge.setTooltip(null);
        }

        boolean hasMatch = matchText != null && !matchText.isBlank();
        matchStat.setText(hasMatch ? matchText : "");
        matchStat.setVisible(hasMatch);
        matchStat.setManaged(hasMatch);
        matchStat.setTooltip(matchTooltip == null || matchTooltip.isBlank() ? null : new Tooltip(matchTooltip));

        boolean hasCoverage = coverageText != null && !coverageText.isBlank();
        coverageStat.setText(hasCoverage ? coverageText : "");
        coverageStat.setVisible(hasCoverage);
        coverageStat.setManaged(hasCoverage);

        metadataRow.getChildren().clear();
        if (showMeas) {
            metadataRow.getChildren().add(measurementTile);
        }
        if (showChannel) {
            metadataRow.getChildren().add(channelTile);
        }
        if (showIds) {
            metadataRow.getChildren().add(idsTile);
        }
        if (showFrames) {
            metadataRow.getChildren().add(framesTile);
        }
        if (dbcVisible) {
            metadataRow.getChildren().add(dbcTile);
        }
        // Mapping state / coverage live on the dedicated DBC summary row — never in this header.

        boolean hasAggregate = aggregateDetail != null && !aggregateDetail.isBlank();
        aggregateLine.setText(hasAggregate ? aggregateDetail : "");
        aggregateLine.setVisible(hasAggregate);
        aggregateLine.setManaged(hasAggregate);

        if (measurementTooltip != null && !measurementTooltip.isBlank()) {
            overflowButton.setTooltip(new Tooltip(measurementTooltip));
            overflowButton.setVisible(true);
            overflowButton.setManaged(true);
        } else {
            overflowButton.setTooltip(null);
            overflowButton.setVisible(false);
            overflowButton.setManaged(false);
        }
    }

    public Label titleLabelForTest() {
        return titleLabel;
    }

    public Label readyBadgeForTest() {
        return readyBadge;
    }

    public MetaTile measurementTileForTest() {
        return measurementTile;
    }

    public MetaTile dbcTileForTest() {
        return dbcTile;
    }

    public Label mappingBadgeForTest() {
        return mappingBadge;
    }

    public Label matchStatForTest() {
        return matchStat;
    }

    public Label coverageStatForTest() {
        return coverageStat;
    }

    public Label idsValueForTest() {
        return idsTile.primaryLabel();
    }

    public Label framesValueForTest() {
        return framesTile.primaryLabel();
    }

    private static Node dot() {
        Region r = new Region();
        r.getStyleClass().add("source-channel-active-dot");
        r.setMinSize(7, 7);
        r.setPrefSize(7, 7);
        r.setMaxSize(7, 7);
        return r;
    }

    /** Compact metadata tile used by the context header. */
    public static final class MetaTile extends HBox {
        private final Label formatOrIcon = new Label();
        private final Label primary = new Label();
        private final Label caption = new Label();
        private final VBox textCol = new VBox(0);

        private MetaTile(String roleClass) {
            getStyleClass().addAll("source-channel-meta-tile", roleClass);
            setAlignment(Pos.CENTER_LEFT);
            setSpacing(6);
            setPadding(new Insets(4, 8, 4, 8));
            setMaxHeight(Region.USE_PREF_SIZE);

            formatOrIcon.getStyleClass().add("source-channel-meta-format");
            primary.getStyleClass().add("source-channel-meta-primary");
            primary.setTextOverrun(OverrunStyle.ELLIPSIS);
            primary.setMaxWidth(160);
            caption.getStyleClass().add("source-channel-meta-caption");
            textCol.getChildren().setAll(primary, caption);
            getChildren().setAll(formatOrIcon, textCol);
        }

        static MetaTile measurement() {
            MetaTile t = new MetaTile("measurement-source");
            t.formatOrIcon.setGraphic(StellarIcons.tinted(StellarIcons.file(), StellarTheme.MEASUREMENT));
            return t;
        }

        static MetaTile definition() {
            MetaTile t = new MetaTile("definition-source");
            t.formatOrIcon.setGraphic(StellarIcons.tinted(StellarIcons.database(), StellarTheme.DEFINITION));
            return t;
        }

        static MetaTile neutral() {
            MetaTile t = new MetaTile("source-channel-meta-neutral");
            t.formatOrIcon.setVisible(false);
            t.formatOrIcon.setManaged(false);
            return t;
        }

        void applyMeasurement(String format, String fileName, String tooltip) {
            formatOrIcon.setVisible(true);
            formatOrIcon.setManaged(true);
            formatOrIcon.setText(format == null ? "" : format);
            primary.setText(fileName == null ? "" : fileName);
            caption.setText("");
            caption.setVisible(false);
            caption.setManaged(false);
            installTip(tooltip);
        }

        void applyDefinition(String name, String captionText, String tooltip) {
            formatOrIcon.setVisible(true);
            formatOrIcon.setManaged(true);
            formatOrIcon.setText("");
            primary.setText(name == null ? "" : name);
            caption.setText(captionText == null ? "" : captionText);
            caption.setVisible(captionText != null && !captionText.isBlank());
            caption.setManaged(caption.isVisible());
            installTip(tooltip);
        }

        void applyValue(String value, String captionText, String tooltip) {
            formatOrIcon.setVisible(false);
            formatOrIcon.setManaged(false);
            primary.setText(value == null ? "" : value);
            caption.setText(captionText == null ? "" : captionText);
            caption.setVisible(captionText != null && !captionText.isBlank());
            caption.setManaged(caption.isVisible());
            installTip(tooltip);
        }

        private void installTip(String tooltip) {
            Object prev = getProperties().remove("stellar.tooltip");
            if (prev instanceof Tooltip oldTip) {
                Tooltip.uninstall(this, oldTip);
            }
            if (tooltip == null || tooltip.isBlank()) {
                return;
            }
            Tooltip tip = new Tooltip(tooltip);
            getProperties().put("stellar.tooltip", tip);
            Tooltip.install(this, tip);
        }

        void hide() {
            setVisible(false);
            setManaged(false);
        }

        public Label primaryLabel() {
            return primary;
        }

        public Label formatLabel() {
            return formatOrIcon;
        }

        public boolean hasRoleClass(String styleClass) {
            return getStyleClass().contains(styleClass);
        }
    }
}