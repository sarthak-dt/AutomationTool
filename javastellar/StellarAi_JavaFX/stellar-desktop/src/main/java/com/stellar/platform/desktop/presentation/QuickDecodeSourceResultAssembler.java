package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Aggregates completed Quick Decode channel results into one source-centric result per measurement file.
 * <p>
 * This is presentation aggregation only. Every underlying {@link IndependentJobResult}, signal catalog,
 * channel id, DBC identity, timestamp and sample remains independent and unchanged.
 */
public final class QuickDecodeSourceResultAssembler {

    public record DefinitionUsage(
            String definitionSourceId,
            String displayName,
            int channelCount
    ) {
        public DefinitionUsage {
            definitionSourceId = definitionSourceId == null ? "" : definitionSourceId;
            displayName = displayName == null || displayName.isBlank() ? "DBC" : displayName.trim();
            if (channelCount < 1) {
                throw new IllegalArgumentException("channelCount must be >= 1");
            }
        }
    }

    public record SourceResult(
            String projectSourceId,
            List<IndependentJobResult> channelResults,
            List<DefinitionUsage> definitions,
            int successfulChannels,
            int warningChannels,
            int failedChannels,
            long selectedFrames,
            long dbcMatchedFrames,
            long decodedSamples
    ) {
        public SourceResult {
            projectSourceId = Objects.requireNonNull(projectSourceId, "projectSourceId");
            channelResults = List.copyOf(channelResults == null ? List.of() : channelResults);
            definitions = List.copyOf(definitions == null ? List.of() : definitions);
            if (successfulChannels < 0 || warningChannels < 0 || failedChannels < 0) {
                throw new IllegalArgumentException("channel counts must be >= 0");
            }
            selectedFrames = Math.max(0L, selectedFrames);
            dbcMatchedFrames = Math.max(0L, dbcMatchedFrames);
            decodedSamples = Math.max(0L, decodedSamples);
        }

        public int channelCount() {
            return channelResults.size();
        }

        public boolean hasSignalCatalog() {
            return channelResults.stream().anyMatch(result -> result.successful() && result.catalogHandle().isPresent());
        }

        public int affectedChannels() {
            return diagnosticResults().size();
        }

        public List<IndependentJobResult> diagnosticResults() {
            return channelResults.stream()
                    .filter(result -> !result.diagnostics().isEmpty()
                            || !result.technicalDetail().isBlank()
                            || result.status() == JobExecutionStatus.FAILED
                            || result.status() == JobExecutionStatus.CANCELLED
                            || result.status() == JobExecutionStatus.BLOCKED)
                    .toList();
        }

        public String resultLabel() {
            if (failedChannels > 0) {
                return ProcessingStatusLabels.ERROR;
            }
            if (channelCount() == 0) {
                return "";
            }
            return ProcessingStatusLabels.COMPLETED;
        }

        /**
         * Compact result cell: status plus a short runtime diagnostic reason when present.
         */
        public String resultDisplayText() {
            String label = resultLabel();
            String reason = primaryDiagnosticReason();
            if (reason.isBlank()) {
                return label;
            }
            return label + "\n" + reason;
        }

        public String primaryDiagnosticReason() {
            for (IndependentJobResult result : diagnosticResults()) {
                for (var diagnostic : result.diagnostics()) {
                    String detail = diagnostic.detail() == null ? "" : diagnostic.detail().trim();
                    if (!detail.isBlank()) {
                        return shortenReason(detail);
                    }
                    return shortenReason(diagnostic.code().name().replace('_', ' ').toLowerCase());
                }
                if (!result.technicalDetail().isBlank()) {
                    return shortenReason(result.technicalDetail());
                }
            }
            return "";
        }

        private static String shortenReason(String text) {
            String cleaned = text == null ? "" : text.trim().replace('\n', ' ');
            if (cleaned.length() <= 64) {
                return cleaned;
            }
            return cleaned.substring(0, 61) + "...";
        }

        public String resultStyle() {
            if (failedChannels > 0) {
                return ProcessingStatusLabels.STYLE_ERROR;
            }
            return ProcessingStatusLabels.STYLE_COMPLETED;
        }
    }

    public List<SourceResult> assemble(MultiSourceProcessingResult batch) {
        return batch == null ? List.of() : assembleResults(batch.jobResults());
    }

    /** Aggregate the exact result set supplied by the current processing plan. */
    public List<SourceResult> assembleResults(List<IndependentJobResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }

        Map<String, List<IndependentJobResult>> bySource = new LinkedHashMap<>();
        // The caller supplies immutable plan order. Preserve it so Source A/B/C remains stable
        // instead of sorting by opaque project-source identifiers.
        for (IndependentJobResult result : results) {
            bySource.computeIfAbsent(result.projectSourceId(), ignored -> new ArrayList<>()).add(result);
        }

        List<SourceResult> out = new ArrayList<>(bySource.size());
        for (Map.Entry<String, List<IndependentJobResult>> entry : bySource.entrySet()) {
            out.add(assembleSource(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(out);
    }

    public Optional<SourceResult> source(MultiSourceProcessingResult batch, String projectSourceId) {
        if (projectSourceId == null || projectSourceId.isBlank()) {
            return Optional.empty();
        }
        return assemble(batch).stream()
                .filter(result -> projectSourceId.equals(result.projectSourceId()))
                .findFirst();
    }

    private static SourceResult assembleSource(String sourceId, List<IndependentJobResult> results) {
        int successful = 0;
        int warnings = 0;
        int failed = 0;
        long selectedFrames = 0L;
        long matchedFrames = 0L;
        long decodedSamples = 0L;

        Map<String, DefinitionAccumulator> definitions = new LinkedHashMap<>();
        Set<Integer> seenChannels = new LinkedHashSet<>();
        for (IndependentJobResult result : results) {
            if (!seenChannels.add(result.channelId())) {
                throw new IllegalStateException(
                        "Quick Decode result contains more than one result for source/channel: "
                                + sourceId + "/Ch" + result.channelId());
            }
            selectedFrames = safeAdd(selectedFrames, result.selectedChannelFrames());
            matchedFrames = safeAdd(matchedFrames, result.dbcMatchedFrames());
            decodedSamples = safeAdd(decodedSamples, result.signalsProduced());

            if (result.status() == JobExecutionStatus.SUCCESS_WITH_WARNINGS) {
                successful++;
                warnings++;
            } else if (result.status() == JobExecutionStatus.SUCCESS) {
                successful++;
            } else if (result.status() == JobExecutionStatus.FAILED
                    || result.status() == JobExecutionStatus.CANCELLED
                    || result.status() == JobExecutionStatus.BLOCKED) {
                failed++;
            }

            String definitionId = result.definitionSourceId().orElse("");
            if (!definitionId.isBlank()) {
                DefinitionAccumulator accumulator = definitions.computeIfAbsent(
                        definitionId,
                        ignored -> new DefinitionAccumulator(
                                definitionId,
                                result.definitionDisplayName().isBlank()
                                        ? "DBC"
                                        : result.definitionDisplayName()));
                accumulator.channels++;
            }
        }

        List<DefinitionUsage> usages = definitions.values().stream()
                .map(acc -> new DefinitionUsage(acc.id, acc.displayName, acc.channels))
                .toList();
        return new SourceResult(
                sourceId,
                results,
                usages,
                successful,
                warnings,
                failed,
                selectedFrames,
                matchedFrames,
                decodedSamples);
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, Math.max(0L, right));
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static final class DefinitionAccumulator {
        private final String id;
        private final String displayName;
        private int channels;

        private DefinitionAccumulator(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }
    }
}
