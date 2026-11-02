package com.stellar.platform.desktop.project;

import com.stellar.platform.domain.inventory.CanIdMatchDiagnostic;

import java.util.List;
import java.util.Objects;

/**
 * Read-only ID-level comparison for one SourceChannelKey &times; DefinitionSourceId.
 * Built from READY inventory + already-parsed DBC; never reopens measurement files.
 */
public final class SourceChannelDefinitionComparison {
    private final SourceChannelKey sourceChannelKey;
    private final DefinitionSourceId definitionSourceId;
    private final String definitionDisplayName;
    private final int definitionMessageCount;
    private final DefinitionCandidateKind candidateKind;
    private final long observedUniqueMessageCount;
    private final long matchedUniqueMessageCount;
    private final double coverageRatio;
    private final List<CanIdMatchDiagnostic> diagnostics;

    public SourceChannelDefinitionComparison(
            SourceChannelKey sourceChannelKey,
            DefinitionSourceId definitionSourceId,
            String definitionDisplayName,
            int definitionMessageCount,
            DefinitionCandidateKind candidateKind,
            long observedUniqueMessageCount,
            long matchedUniqueMessageCount,
            double coverageRatio,
            List<CanIdMatchDiagnostic> diagnostics
    ) {
        this.sourceChannelKey = Objects.requireNonNull(sourceChannelKey, "sourceChannelKey");
        this.definitionSourceId = Objects.requireNonNull(definitionSourceId, "definitionSourceId");
        this.definitionDisplayName = definitionDisplayName == null ? "" : definitionDisplayName;
        this.definitionMessageCount = Math.max(0, definitionMessageCount);
        this.candidateKind = Objects.requireNonNull(candidateKind, "candidateKind");
        this.observedUniqueMessageCount = Math.max(0L, observedUniqueMessageCount);
        this.matchedUniqueMessageCount = Math.max(0L, matchedUniqueMessageCount);
        this.coverageRatio = coverageRatio;
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public SourceChannelKey sourceChannelKey() {
        return sourceChannelKey;
    }

    public DefinitionSourceId definitionSourceId() {
        return definitionSourceId;
    }

    public String definitionDisplayName() {
        return definitionDisplayName;
    }

    public int definitionMessageCount() {
        return definitionMessageCount;
    }

    public DefinitionCandidateKind candidateKind() {
        return candidateKind;
    }

    public long observedUniqueMessageCount() {
        return observedUniqueMessageCount;
    }

    public long matchedUniqueMessageCount() {
        return matchedUniqueMessageCount;
    }

    public double coverageRatio() {
        return coverageRatio;
    }

    public double coveragePercent() {
        return coverageRatio * 100.0;
    }

    public List<CanIdMatchDiagnostic> diagnostics() {
        return diagnostics;
    }

    public String explanation() {
        return switch (candidateKind) {
            case EMPTY_DEFINITION -> "Definition contains no CAN messages.";
            case NO_OVERLAP -> "No overlapping CAN IDs found.";
            case HAS_OVERLAP -> matchedUniqueMessageCount + " / " + observedUniqueMessageCount
                    + " overlapping CAN IDs.";
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SourceChannelDefinitionComparison that)) {
            return false;
        }
        return definitionMessageCount == that.definitionMessageCount
                && observedUniqueMessageCount == that.observedUniqueMessageCount
                && matchedUniqueMessageCount == that.matchedUniqueMessageCount
                && Double.compare(coverageRatio, that.coverageRatio) == 0
                && sourceChannelKey.equals(that.sourceChannelKey)
                && definitionSourceId.equals(that.definitionSourceId)
                && definitionDisplayName.equals(that.definitionDisplayName)
                && candidateKind == that.candidateKind
                && diagnostics.equals(that.diagnostics);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                sourceChannelKey,
                definitionSourceId,
                definitionDisplayName,
                definitionMessageCount,
                candidateKind,
                observedUniqueMessageCount,
                matchedUniqueMessageCount,
                coverageRatio,
                diagnostics
        );
    }
}
