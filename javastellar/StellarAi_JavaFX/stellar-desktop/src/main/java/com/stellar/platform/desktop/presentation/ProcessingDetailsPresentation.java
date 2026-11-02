package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnostic;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnosticCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Presentation-only grouping of Processing Complete diagnostics. Messages are not rewritten.
 */
public final class ProcessingDetailsPresentation {

    public record DiagnosticGroup(
            String severity,
            String code,
            String message,
            int occurrences,
            List<String> originals
    ) {
        public DiagnosticGroup {
            severity = severity == null || severity.isBlank() ? "Warning" : severity;
            code = code == null ? "" : code;
            message = message == null ? "" : message;
            occurrences = Math.max(1, occurrences);
            originals = List.copyOf(originals == null ? List.of() : originals);
        }
    }

    public record Model(
            String sourceName,
            String sourceFile,
            String result,
            String channels,
            String dbcUsed,
            String selectedFrames,
            String dbcMatched,
            String decodedSignals,
            String elapsed,
            List<DiagnosticGroup> groups,
            String rawText
    ) {
        public Model {
            sourceName = nullToEmpty(sourceName);
            sourceFile = nullToEmpty(sourceFile);
            result = nullToEmpty(result);
            channels = nullToEmpty(channels);
            dbcUsed = nullToEmpty(dbcUsed);
            selectedFrames = nullToEmpty(selectedFrames);
            dbcMatched = nullToEmpty(dbcMatched);
            decodedSignals = nullToEmpty(decodedSignals);
            elapsed = elapsed == null || elapsed.isBlank() ? ProcessingElapsed.UNAVAILABLE : elapsed;
            groups = List.copyOf(groups == null ? List.of() : groups);
            rawText = nullToEmpty(rawText);
        }

        public int warningCount() {
            int n = 0;
            for (DiagnosticGroup group : groups) {
                n += group.occurrences();
            }
            return n;
        }
    }

    private ProcessingDetailsPresentation() {
    }

    public static Model fromJobs(
            String sourceName,
            String sourceFile,
            String result,
            String channels,
            String dbcUsed,
            String selectedFrames,
            String dbcMatched,
            String decodedSignals,
            String elapsed,
            List<IndependentJobResult> jobs
    ) {
        List<IndependentJobResult> rows = jobs == null ? List.of() : jobs;
        List<Entry> entries = new ArrayList<>();
        StringBuilder raw = new StringBuilder();
        for (IndependentJobResult job : rows) {
            if (job == null) {
                continue;
            }
            if (!raw.isEmpty()) {
                raw.append("\n\n");
            }
            raw.append("Channel Ch").append(job.channelId());
            if (!job.definitionDisplayName().isBlank()) {
                raw.append(" · DBC ").append(job.definitionDisplayName());
            }
            raw.append('\n');
            for (ProcessingPlanDiagnostic diagnostic : job.diagnostics()) {
                Entry entry = fromDiagnostic(diagnostic);
                entries.add(entry);
                raw.append(entry.code()).append(" — ").append(entry.message()).append('\n');
            }
            if (!job.technicalDetail().isBlank()) {
                raw.append("Technical: ").append(job.technicalDetail()).append('\n');
            }
        }
        return new Model(sourceName, sourceFile, result, channels, dbcUsed, selectedFrames,
                dbcMatched, decodedSignals, elapsed, group(entries), raw.toString().trim());
    }

    public static String copyText(Model model) {
        if (model == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Processing Details\n");
        sb.append("Source: ").append(model.sourceName()).append('\n');
        if (!model.sourceFile().isBlank() && !model.sourceFile().equals(model.sourceName())) {
            sb.append("File: ").append(model.sourceFile()).append('\n');
        }
        sb.append("Result: ").append(model.result()).append('\n');
        sb.append("Channels: ").append(model.channels()).append('\n');
        sb.append("DBC Used: ").append(model.dbcUsed()).append('\n');
        sb.append("Selected Frames: ").append(model.selectedFrames()).append('\n');
        sb.append("DBC Matched: ").append(model.dbcMatched()).append('\n');
        sb.append("Decoded Signals: ").append(model.decodedSignals()).append('\n');
        sb.append("Elapsed: ").append(model.elapsed()).append('\n');
        sb.append("Warnings: ").append(model.warningCount()).append('\n');
        sb.append('\n');
        for (DiagnosticGroup group : model.groups()) {
            sb.append(group.severity()).append('\t').append(group.code());
            if (group.occurrences() > 1) {
                sb.append(" (Occurrences: ").append(group.occurrences()).append(')');
            }
            sb.append('\n').append(group.message()).append('\n');
            if (group.occurrences() > 1) {
                for (String original : group.originals()) {
                    sb.append("  - ").append(original).append('\n');
                }
            }
            sb.append('\n');
        }
        if (!model.rawText().isBlank()) {
            sb.append("Raw Details\n").append(model.rawText()).append('\n');
        }
        return sb.toString();
    }

    private static List<DiagnosticGroup> group(List<Entry> entries) {
        Map<String, List<Entry>> byKey = new LinkedHashMap<>();
        for (Entry entry : entries) {
            byKey.computeIfAbsent(entry.severity() + '\0' + entry.code() + '\0' + entry.message(),
                    key -> new ArrayList<>()).add(entry);
        }
        List<DiagnosticGroup> groups = new ArrayList<>();
        for (List<Entry> bucket : byKey.values()) {
            Entry first = bucket.getFirst();
            List<String> originals = new ArrayList<>();
            for (Entry entry : bucket) {
                originals.add(entry.original());
            }
            groups.add(new DiagnosticGroup(first.severity(), first.code(), first.message(),
                    bucket.size(), originals));
        }
        return groups;
    }

    private static Entry fromDiagnostic(ProcessingPlanDiagnostic diagnostic) {
        if (diagnostic == null) {
            return new Entry("Warning", "UNKNOWN", "", "");
        }
        String original = diagnostic.code().name();
        if (diagnostic.detail() != null && !diagnostic.detail().isBlank()) {
            original = original + " — " + diagnostic.detail();
        }
        String code = diagnostic.code().name();
        String message = diagnostic.detail() == null ? "" : diagnostic.detail().trim();
        if (diagnostic.code() == ProcessingPlanDiagnosticCode.PIPELINE_DIAGNOSTIC) {
            int split = message.indexOf(": ");
            if (split > 0) {
                code = message.substring(0, split).trim();
                message = message.substring(split + 2).trim();
            }
        }
        if (message.isBlank()) {
            message = code.replace('_', ' ');
        }
        return new Entry(severityOf(code), code, message, original);
    }

    static String severityOf(String code) {
        String token = code == null ? "" : code.toUpperCase(Locale.ROOT);
        if (token.contains("RECOVER") || token.contains("UNFINALIZED") || token.contains("BOUNDARY")) {
            return "Recovery";
        }
        if (token.contains("FAIL") || token.contains("ERROR") || token.contains("NOT_READY")) {
            return "Error";
        }
        return "Warning";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record Entry(String severity, String code, String message, String original) {
        private Entry {
            Objects.requireNonNull(severity);
            Objects.requireNonNull(code);
            Objects.requireNonNull(message);
            Objects.requireNonNull(original);
        }
    }
}
