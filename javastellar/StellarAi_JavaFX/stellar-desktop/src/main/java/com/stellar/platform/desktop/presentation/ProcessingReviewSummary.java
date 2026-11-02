package com.stellar.platform.desktop.presentation;

import java.util.List;

/**
 * Read-only answer to "what exactly will StellarAI process if I start now?".
 *
 * <p>Every field is a projection of state the application already owns. Counts that the current
 * architecture cannot establish are represented as absent rather than guessed: notably Custom
 * Decode has no exclusion semantics, so {@link #ignoredChannels()} is only meaningful when
 * {@link #reportsIgnoredChannels()} is true.</p>
 */
public record ProcessingReviewSummary(
        int sourceCount,
        int usableSourceCount,
        int discoveredChannels,
        DecisionWorkspaceMode strategy,
        int definitionCount,
        int includedChannels,
        int ignoredChannels,
        boolean reportsIgnoredChannels,
        int configuredChannels,
        int needsSetupChannels,
        int nativeOnlyChannels,
        int scopeJobs,
        int scopeSources,
        int scopeChannels,
        List<ReviewIssue> issues
) {

    /** One user-facing finding about the configuration being reviewed. */
    public record ReviewIssue(ReviewIssueSeverity severity, String title, String detail) {
        public ReviewIssue {
            severity = severity == null ? ReviewIssueSeverity.INFORMATION : severity;
            title = title == null ? "" : title;
            detail = detail == null ? "" : detail;
        }
    }

    public ProcessingReviewSummary {
        issues = List.copyOf(issues == null ? List.of() : issues);
        strategy = strategy == null ? DecisionWorkspaceMode.QUICK_DECODE : strategy;
    }

    public String strategyDisplayName() {
        return strategy == DecisionWorkspaceMode.QUICK_DECODE ? "Auto Decode" : "Custom Decode";
    }

    /** No blocking issue stands in the way of starting processing. */
    public boolean readyToProcess() {
        return scopeJobs > 0 && issues.stream().noneMatch(issue -> issue.severity().blocking());
    }

    public List<ReviewIssue> blockingIssues() {
        return issues.stream().filter(issue -> issue.severity().blocking()).toList();
    }

    public String readinessText() {
        if (readyToProcess()) {
            boolean warned = issues.stream().anyMatch(issue -> issue.severity() == ReviewIssueSeverity.WARNING);
            return warned ? "Ready with warnings" : "Ready to process";
        }
        List<ReviewIssue> blocking = blockingIssues();
        if (!blocking.isEmpty()) {
            return blocking.getFirst().title();
        }
        return scopeJobs == 0 ? "Nothing to process" : "Setup incomplete";
    }
}
