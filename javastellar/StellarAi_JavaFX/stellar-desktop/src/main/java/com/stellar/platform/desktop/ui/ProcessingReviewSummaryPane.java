package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.ProcessingReviewSummary;
import com.stellar.platform.desktop.presentation.ReviewIssueSeverity;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Read-only "what will be processed" panel for the Review &amp; Process checkpoint.
 *
 * <p>Nothing here mutates configuration: the only interactions are navigation back to Sources or
 * Decode.</p>
 */
final class ProcessingReviewSummaryPane extends VBox {

    private final Label sourcesFacts = new Label();
    private final Label decodeFacts = new Label();
    private final Label scopeFacts = new Label();
    private final Label readinessLabel = new Label();
    private final VBox issueList = new VBox(2);
    private final Button editSourcesButton = new Button("Edit Sources");
    private final Button editDecodeButton = new Button("Edit Decode");

    private Runnable onEditSources = () -> { };
    private Runnable onEditDecode = () -> { };
    private ProcessingReviewSummary summary;

    ProcessingReviewSummaryPane() {
        setId("processing-review-summary");
        getStyleClass().add("processing-review-summary");
        setSpacing(4);
        setPadding(new Insets(8, 10, 8, 10));
        setMaxHeight(Region.USE_PREF_SIZE);

        editSourcesButton.setId("review-edit-sources-button");
        editSourcesButton.getStyleClass().addAll("secondary-button", "secondary-action", "compact-button");
        editSourcesButton.setOnAction(e -> onEditSources.run());

        editDecodeButton.setId("review-edit-decode-button");
        editDecodeButton.getStyleClass().addAll("secondary-button", "secondary-action", "compact-button");
        editDecodeButton.setOnAction(e -> onEditDecode.run());

        readinessLabel.setId("review-readiness-label");
        readinessLabel.getStyleClass().addAll("badge", "plan-status-badge");

        issueList.setId("review-issue-list");
        issueList.getStyleClass().add("review-issue-list");

        getChildren().addAll(
                sectionRow("SOURCES", sourcesFacts, editSourcesButton),
                sectionRow("DECODE", decodeFacts, editDecodeButton),
                sectionRow("PROCESSING SCOPE", scopeFacts, null),
                sectionRow("READINESS", readinessLabel, null),
                issueList);
    }

    private HBox sectionRow(String caption, Label facts, Button action) {
        Label captionLabel = new Label(caption);
        captionLabel.getStyleClass().add("processing-review-caption");
        captionLabel.setMinWidth(140);
        facts.getStyleClass().add("processing-review-facts");
        facts.setWrapText(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, captionLabel, facts, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        if (action != null) {
            row.getChildren().add(action);
        }
        return row;
    }

    void setEditHandlers(Runnable editSources, Runnable editDecode) {
        this.onEditSources = editSources == null ? () -> { } : editSources;
        this.onEditDecode = editDecode == null ? () -> { } : editDecode;
    }

    void apply(ProcessingReviewSummary next) {
        this.summary = next;
        if (next == null) {
            return;
        }
        sourcesFacts.setText(sourcesText(next));
        decodeFacts.setText(decodeText(next));
        scopeFacts.setText(scopeText(next));
        readinessLabel.setText(next.readinessText());
        boolean warned = next.issues().stream()
                .anyMatch(issue -> issue.severity() == ReviewIssueSeverity.WARNING);
        readinessLabel.getStyleClass().setAll("badge", "plan-status-badge",
                next.readyToProcess()
                        ? (warned ? "badge-warning" : "badge-ready")
                        : "badge-invalid");
        applyIssues(next.issues());
    }

    private static String sourcesText(ProcessingReviewSummary s) {
        StringBuilder sb = new StringBuilder();
        sb.append(s.sourceCount()).append(s.sourceCount() == 1 ? " measurement file" : " measurement files");
        if (s.usableSourceCount() != s.sourceCount()) {
            sb.append("   ·   ").append(s.usableSourceCount()).append(" usable");
        }
        if (s.discoveredChannels() > 0) {
            sb.append("   ·   ").append(s.discoveredChannels())
                    .append(s.discoveredChannels() == 1 ? " channel" : " channels");
        }
        return sb.toString();
    }

    private static String decodeText(ProcessingReviewSummary s) {
        StringBuilder sb = new StringBuilder(s.strategyDisplayName());
        if (s.definitionCount() == 0) {
            sb.append("   ·   DBC: none");
        } else {
            sb.append("   ·   ").append(s.definitionCount())
                    .append(s.definitionCount() == 1 ? " DBC definition" : " DBC definitions");
        }
        if (s.configuredChannels() > 0) {
            sb.append("   ·   ").append(s.configuredChannels()).append(" configured");
        }
        if (s.nativeOnlyChannels() > 0) {
            sb.append("   ·   native/raw outputs only");
        }
        if (s.needsSetupChannels() > 0) {
            sb.append("   ·   ").append(s.needsSetupChannels()).append(" need setup");
        }
        // Custom Decode has no exclusion semantics, so no ignored count is claimed for it.
        if (s.reportsIgnoredChannels() && s.ignoredChannels() > 0) {
            sb.append("   ·   ").append(s.ignoredChannels()).append(" ignored");
        }
        return sb.toString();
    }

    private static String scopeText(ProcessingReviewSummary s) {
        if (s.scopeJobs() == 0) {
            return "Nothing is currently in scope.";
        }
        return s.scopeSources() + (s.scopeSources() == 1 ? " file" : " files")
                + "   ·   " + s.scopeChannels() + (s.scopeChannels() == 1 ? " channel" : " channels")
                + "   ·   " + s.scopeJobs() + (s.scopeJobs() == 1 ? " job" : " jobs");
    }

    private void applyIssues(List<ProcessingReviewSummary.ReviewIssue> issues) {
        issueList.getChildren().clear();
        for (ProcessingReviewSummary.ReviewIssue issue : issues) {
            Label label = new Label(issue.severity().display() + ": " + issue.title()
                    + (issue.detail().isBlank() ? "" : "  —  " + issue.detail()));
            label.setWrapText(true);
            label.getStyleClass().addAll("processing-review-issue", issue.severity().styleClass());
            issueList.getChildren().add(label);
        }
        boolean any = !issues.isEmpty();
        issueList.setVisible(any);
        issueList.setManaged(any);
    }

    ProcessingReviewSummary summaryForTest() {
        return summary;
    }

    String readinessTextForTest() {
        return readinessLabel.getText();
    }

    String sourcesTextForTest() {
        return sourcesFacts.getText();
    }

    String decodeTextForTest() {
        return decodeFacts.getText();
    }

    String scopeTextForTest() {
        return scopeFacts.getText();
    }

    Button editSourcesButtonForTest() {
        return editSourcesButton;
    }

    Button editDecodeButtonForTest() {
        return editDecodeButton;
    }

    List<String> issueTextsForTest() {
        return issueList.getChildren().stream()
                .filter(node -> node instanceof Label)
                .map(node -> ((Label) node).getText())
                .toList();
    }

    boolean hasBlockingIssueForTest() {
        return summary != null && !summary.blockingIssues().isEmpty();
    }

    static boolean blocks(ProcessingReviewSummary summary) {
        return summary != null && summary.issues().stream()
                .anyMatch(issue -> issue.severity() == ReviewIssueSeverity.BLOCKING);
    }
}
