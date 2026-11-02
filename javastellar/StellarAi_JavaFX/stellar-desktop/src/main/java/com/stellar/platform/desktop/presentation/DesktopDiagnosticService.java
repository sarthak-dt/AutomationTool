package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.diagnostics.DiagnosticFinding;
import com.stellar.platform.analysis.diagnostics.DiagnosticRuleEngine;
import com.stellar.platform.analysis.diagnostics.DiagnosticRunRequest;
import com.stellar.platform.analysis.diagnostics.DiagnosticRunResult;
import com.stellar.platform.analysis.diagnostics.FindingEvidence;
import com.stellar.platform.analysis.export.DiagnosticCsvExporter;
import com.stellar.platform.analysis.invalidate.DiagnosticInvalidationKey;
import com.stellar.platform.analysis.model.AnalysisSeverity;
import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.EngineeringFinding;
import com.stellar.platform.analysis.model.FindingType;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.report.DiagnosticReportFormatter;
import com.stellar.platform.analysis.rules.DiagnosticRuleDefinition;
import com.stellar.platform.analysis.rules.DiagnosticRulePack;
import com.stellar.platform.analysis.rules.GenericEngineeringPack;
import com.stellar.platform.analysis.rules.RuleCategory;
import com.stellar.platform.analysis.rules.RuleOrigin;
import com.stellar.platform.analysis.rules.RulePackJson;
import com.stellar.platform.analysis.workflow.FindingReviewState;
import com.stellar.platform.analysis.workflow.PackImportPreview;
import com.stellar.platform.analysis.workflow.ProjectCustomPack;
import com.stellar.platform.analysis.workflow.StaleRunReason;
import com.stellar.platform.analysis.source.AnalysisSampleSource;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.parser.spi.SimpleCancellationToken;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.storage.metadata.DiagnosticBindingRow;
import com.stellar.platform.storage.metadata.DiagnosticFindingReviewRow;
import com.stellar.platform.storage.metadata.DiagnosticFindingRow;
import com.stellar.platform.storage.metadata.DiagnosticRulePackRow;
import com.stellar.platform.storage.metadata.DiagnosticRulePreferenceRow;
import com.stellar.platform.storage.metadata.DiagnosticRunMetadata;
import com.stellar.platform.storage.metadata.DiagnosticWorkspaceMetadata;
import com.stellar.platform.storage.metadata.ExportJobMetadata;
import com.stellar.platform.storage.metadata.ExportJobStatus;
import com.stellar.platform.storage.metadata.GraphAnnotationMetadata;
import com.stellar.platform.storage.metadata.GraphAnnotationType;
import com.stellar.platform.storage.metadata.ProjectMetadataStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Presentation coordinator for Phase 5 diagnostics. No JavaFX.
 * Heavy work is invoked off the JavaFX Application Thread by the caller.
 */
public final class DesktopDiagnosticService {

    private final DiagnosticRuleEngine engine = new DiagnosticRuleEngine();
    private final DiagnosticCsvExporter csv = new DiagnosticCsvExporter();
    private final AtomicReference<DiagnosticRunResult> last = new AtomicReference<>();
    private final AtomicReference<SimpleCancellationToken> running = new AtomicReference<>();
    private final AtomicReference<String> lastReport = new AtomicReference<>("");
    private final AtomicReference<String> lastPackId = new AtomicReference<>(GenericEngineeringPack.PACK_ID);

    public DiagnosticRulePack builtInPack() {
        return GenericEngineeringPack.pack();
    }

    public DiagnosticRulePack projectCustomPack(ProjectMetadataStore metadata, String projectId) {
        if (metadata == null || projectId == null || projectId.isBlank()) {
            return ProjectCustomPack.empty();
        }
        return metadata.findDiagnosticRulePack(projectId, ProjectCustomPack.PACK_ID, ProjectCustomPack.PACK_VERSION)
                .map(row -> RulePackJson.read(row.jsonBlob()))
                .orElseGet(ProjectCustomPack::empty);
    }

    public List<DiagnosticRulePack> availablePacks(ProjectMetadataStore metadata, String projectId) {
        List<DiagnosticRulePack> packs = new ArrayList<>();
        packs.add(builtInPack());
        DiagnosticRulePack custom = projectCustomPack(metadata, projectId);
        packs.add(custom);
        if (metadata != null && projectId != null) {
            for (DiagnosticRulePackRow row : metadata.listDiagnosticRulePacks(projectId)) {
                if (ProjectCustomPack.PACK_ID.equals(row.packId())
                        || GenericEngineeringPack.PACK_ID.equals(row.packId())) {
                    continue;
                }
                try {
                    packs.add(RulePackJson.read(row.jsonBlob()));
                } catch (RuntimeException ignored) {
                    // Invalid stored pack is skipped; import preview reports errors.
                }
            }
        }
        return List.copyOf(packs);
    }

    public void saveCustomRule(
            ProjectMetadataStore metadata,
            String projectId,
            DiagnosticRuleDefinition rule
    ) {
        if (metadata == null || projectId == null || projectId.isBlank() || rule == null) {
            return;
        }
        if (rule.origin() != RuleOrigin.PROJECT_CUSTOM) {
            throw new IllegalArgumentException("Built-in rules cannot be modified");
        }
        DiagnosticRulePack next = ProjectCustomPack.withRule(projectCustomPack(metadata, projectId), rule);
        persistPack(metadata, projectId, next, RuleOrigin.PROJECT_CUSTOM);
    }

    public void deleteCustomRule(ProjectMetadataStore metadata, String projectId, String ruleId) {
        DiagnosticRulePack next = ProjectCustomPack.withoutRule(projectCustomPack(metadata, projectId), ruleId);
        persistPack(metadata, projectId, next, RuleOrigin.PROJECT_CUSTOM);
    }

    public PackImportPreview previewImport(String json, ProjectMetadataStore metadata, String projectId) {
        DiagnosticRulePack existing = null;
        try {
            DiagnosticRulePack incoming = RulePackJson.read(json);
            if (metadata != null && projectId != null) {
                existing = metadata.findDiagnosticRulePack(projectId, incoming.packId(), incoming.packVersion())
                        .map(row -> RulePackJson.read(row.jsonBlob()))
                        .orElse(null);
            }
        } catch (RuntimeException ignored) {
            existing = null;
        }
        return PackImportPreview.parse(json, existing);
    }

    public DiagnosticRulePack importPack(
            String json,
            ProjectMetadataStore metadata,
            String projectId
    ) {
        PackImportPreview preview = previewImport(json, metadata, projectId);
        if (!preview.importable() || preview.pack() == null) {
            throw new IllegalArgumentException(preview.conflicts().isEmpty()
                    ? (preview.warnings().isEmpty() ? "Import rejected" : preview.warnings().getFirst())
                    : preview.conflicts().getFirst());
        }
        persistPack(metadata, projectId, preview.pack(), RuleOrigin.PROJECT_CUSTOM);
        return preview.pack();
    }

    public DiagnosticRunResult dryRun(
            DiagnosticRunRequest request,
            SignalSeriesStore store,
            List<SignalCatalogEntry> catalog
    ) {
        SimpleCancellationToken token = new SimpleCancellationToken();
        running.set(token);
        AnalysisSampleSource source = AnalysisSampleSource.of(store);
        DiagnosticRunResult result = engine.execute(request, source, catalog, token);
        running.set(null);
        return result;
    }

    public List<DiagnosticRunMetadata> listRuns(ProjectMetadataStore metadata, String projectId) {
        if (metadata == null || projectId == null) {
            return List.of();
        }
        return metadata.listDiagnosticRuns(projectId);
    }

    public void saveReview(
            ProjectMetadataStore metadata,
            String projectId,
            String findingId,
            FindingReviewState state,
            String note
    ) {
        if (metadata == null || projectId == null || findingId == null || findingId.isBlank()) {
            return;
        }
        metadata.upsertDiagnosticFindingReview(new DiagnosticFindingReviewRow(
                findingId, projectId,
                state == null ? FindingReviewState.NEW.name() : state.name(),
                note == null ? "" : note,
                Instant.now()));
    }

    public Map<String, DiagnosticFindingReviewRow> loadReviews(
            ProjectMetadataStore metadata, String projectId
    ) {
        Map<String, DiagnosticFindingReviewRow> out = new LinkedHashMap<>();
        if (metadata == null || projectId == null) {
            return out;
        }
        for (DiagnosticFindingReviewRow row : metadata.listDiagnosticFindingReviews(projectId)) {
            out.put(row.findingId(), row);
        }
        return out;
    }

    public void saveRulePreference(
            ProjectMetadataStore metadata,
            String projectId,
            String packId,
            String ruleId,
            boolean enabled
    ) {
        if (metadata == null || projectId == null || ruleId == null) {
            return;
        }
        metadata.upsertDiagnosticRulePreference(new DiagnosticRulePreferenceRow(
                projectId, packId, ruleId, enabled, Instant.now()));
    }

    public Map<String, Boolean> loadRulePreferences(
            ProjectMetadataStore metadata, String projectId, String packId
    ) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        if (metadata == null) {
            return out;
        }
        for (DiagnosticRulePreferenceRow row : metadata.listDiagnosticRulePreferences(projectId, packId)) {
            out.put(row.ruleId(), row.enabled());
        }
        return out;
    }

    public Path exportSelectedFindings(
            Path destination,
            List<DiagnosticFinding> findings,
            ProjectMetadataStore metadata,
            String projectId
    ) {
        DiagnosticRunResult base = last.get();
        if (base == null) {
            throw new IllegalStateException("No diagnostic result to export");
        }
        DiagnosticRunResult subset = new DiagnosticRunResult(
                base.runId(), base.status(), base.rulesRequested(), base.rulesExecuted(),
                base.rulesSkipped(), base.outcomes(), findings, base.range(),
                base.startedAt(), base.completedAt(), base.message(),
                base.analysisCacheHits(), base.engineVersion());
        try {
            long bytes = csv.writeFindings(subset, destination);
            recordExport(metadata, projectId, "DIAGNOSTIC_FINDINGS_CSV", destination, bytes);
            return destination;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot export findings", e);
        }
    }

    private void persistPack(
            ProjectMetadataStore metadata,
            String projectId,
            DiagnosticRulePack pack,
            RuleOrigin origin
    ) {
        if (metadata == null || projectId == null || pack == null) {
            return;
        }
        metadata.upsertDiagnosticRulePack(new DiagnosticRulePackRow(
                projectId, pack.packId(), pack.packVersion(), pack.name(), pack.description(),
                origin.name(), pack.engineMinimumVersion(), RulePackJson.write(pack), Instant.now()));
    }

    public DiagnosticRunResult run(
            DiagnosticRunRequest request,
            SignalSeriesStore store,
            List<SignalCatalogEntry> catalog,
            ProjectMetadataStore metadata
    ) {
        SimpleCancellationToken token = new SimpleCancellationToken();
        running.set(token);
        lastPackId.set(request.pack().packId());
        AnalysisSampleSource source = AnalysisSampleSource.of(store);
        DiagnosticRunResult result = engine.execute(request, source, catalog, token);
        last.set(result);
        lastReport.set(DiagnosticReportFormatter.format(result));
        persist(request, result, metadata);
        running.set(null);
        return result;
    }

    public void cancel() {
        SimpleCancellationToken token = running.get();
        if (token != null) {
            token.cancel();
        }
    }

    public DiagnosticRunResult lastResult() {
        return last.get();
    }

    public String lastReport() {
        return lastReport.get() == null ? "" : lastReport.get();
    }

    public String lastPackId() {
        return lastPackId.get() == null ? GenericEngineeringPack.PACK_ID : lastPackId.get();
    }

    public void restoreLast(DiagnosticRunResult result, String packId) {
        last.set(result);
        lastReport.set(result == null ? "" : DiagnosticReportFormatter.format(result));
        if (packId != null && !packId.isBlank()) {
            lastPackId.set(packId);
        }
    }

    public Path exportSummary(Path destination, ProjectMetadataStore metadata, String projectId) {
        return export(destination, metadata, projectId, "DIAGNOSTIC_SUMMARY_CSV", csv::writeSummary);
    }

    public Path exportFindings(Path destination, ProjectMetadataStore metadata, String projectId) {
        return export(destination, metadata, projectId, "DIAGNOSTIC_FINDINGS_CSV", csv::writeFindings);
    }

    public Path exportEvaluation(Path destination, ProjectMetadataStore metadata, String projectId) {
        return export(destination, metadata, projectId, "RULE_EVALUATION_SUMMARY_CSV", csv::writeEvaluation);
    }

    public Path exportPack(Path destination, DiagnosticRulePack pack, ProjectMetadataStore metadata, String projectId) {
        try {
            java.nio.file.Files.writeString(destination, RulePackJson.write(pack));
            long bytes = java.nio.file.Files.size(destination);
            recordExport(metadata, projectId, "RULE_PACK_JSON", destination, bytes);
            return destination;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot export rule pack", e);
        }
    }

    public GraphAnnotationMetadata annotationFromFinding(String projectId, DiagnosticFinding finding) {
        String signal = finding.signalKeys().isEmpty() ? "" : finding.signalKeys().getFirst().toString();
        long end = finding.endTimestampNanos() == null
                ? finding.startTimestampNanos() : finding.endTimestampNanos();
        return new GraphAnnotationMetadata(
                null, projectId, signal, GraphAnnotationType.TIME_RANGE,
                finding.startTimestampNanos(), end, null,
                finding.summary(),
                "finding:" + finding.findingId() + "\n" + finding.finding().details(),
                Instant.now(), Instant.now());
    }

    public void persistBindings(
            ProjectMetadataStore metadata,
            String projectId,
            String packId,
            Map<String, SignalCatalogKey> bindings
    ) {
        if (metadata == null || projectId == null || projectId.isBlank() || bindings == null) {
            return;
        }
        Instant now = Instant.now();
        bindings.forEach((role, key) -> metadata.upsertDiagnosticBinding(
                new DiagnosticBindingRow(projectId, packId, role, encodeKey(key), now)));
    }

    public Map<String, SignalCatalogKey> loadBindings(
            ProjectMetadataStore metadata, String projectId, String packId
    ) {
        Map<String, SignalCatalogKey> out = new LinkedHashMap<>();
        if (metadata == null) {
            return out;
        }
        for (DiagnosticBindingRow row : metadata.listDiagnosticBindings(projectId, packId)) {
            SignalCatalogKey key = decodeKey(row.signalKeyBlob());
            if (key != null) {
                out.put(row.role(), key);
            }
        }
        return out;
    }

    private void persist(DiagnosticRunRequest request, DiagnosticRunResult result, ProjectMetadataStore metadata) {
        if (metadata == null) {
            return;
        }
        metadata.upsertDiagnosticRun(new DiagnosticRunMetadata(
                result.runId(), request.projectId(), request.pack().packId(), request.pack().packVersion(),
                result.status().name(), request.rangeSource().name(),
                result.range().startNanos(), result.range().endNanos(),
                result.rulesRequested(), result.rulesExecuted(), result.rulesSkipped(),
                DiagnosticInvalidationKey.of(request).compact(),
                request.artifactGeneration(), result.engineVersion(), result.message(),
                result.startedAt(), result.completedAt()));
        if (result.status() == AnalysisStatus.CANCELLED) {
            return;
        }
        List<DiagnosticFindingRow> rows = new ArrayList<>();
        for (DiagnosticFinding f : result.findings()) {
            rows.add(new DiagnosticFindingRow(
                    f.findingId(), result.runId(), request.projectId(), f.ruleId(), f.ruleVersion(),
                    f.severity().name(), f.category().name(),
                    f.startTimestampNanos(), f.endTimestampNanos(),
                    f.summary(), compactEvidence(f), compactEvidence(f), f.finding().createdAt()));
        }
        metadata.replaceDiagnosticFindings(result.runId(), rows);
        if (result.status().successful()) {
            String findingId = result.findings().isEmpty() ? "" : result.findings().getFirst().findingId();
            metadata.upsertDiagnosticWorkspace(new DiagnosticWorkspaceMetadata(
                    request.projectId(), request.pack().packId(), result.runId(),
                    String.join(",", request.ruleIds()), true, findingId, Instant.now()));
        }
    }

    public DiagnosticRunResult reconstruct(ProjectMetadataStore metadata, String runId) {
        if (metadata == null || runId == null || runId.isBlank()) {
            return null;
        }
        return metadata.findDiagnosticRun(runId).map(run -> {
            if ("CANCELLED".equals(run.status())) {
                return null;
            }
            boolean stale = StaleRunReason.staleStatus(run.status());
            List<DiagnosticFinding> findings = new ArrayList<>();
            for (DiagnosticFindingRow row : metadata.listDiagnosticFindings(runId)) {
                EngineeringFinding finding = new EngineeringFinding(
                        row.findingId(), runId, FindingType.DIAGNOSTIC,
                        AnalysisSeverity.valueOf(row.severity()),
                        List.of(), row.startTimestampNanos(), row.endTimestampNanos(),
                        row.summary(), row.details(), Map.of(), row.createdAt());
                findings.add(new DiagnosticFinding(
                        finding, runId, row.ruleId(), row.ruleVersion(),
                        RuleCategory.valueOf(row.category()),
                        List.of(new FindingEvidence("", "", row.evidenceBlob(), Map.of())),
                        List.of(), List.of()));
            }
            TimeWindow window = run.startTimestampNanos() == null || run.endTimestampNanos() == null
                    ? TimeWindow.unbounded()
                    : TimeWindow.of(run.startTimestampNanos(), run.endTimestampNanos());
            AnalysisStatus status;
            try {
                status = stale ? AnalysisStatus.COMPLETED_WITH_WARNINGS : AnalysisStatus.valueOf(run.status());
            } catch (IllegalArgumentException ex) {
                status = AnalysisStatus.COMPLETED_WITH_WARNINGS;
            }
            String message = stale ? StaleRunReason.MESSAGE : run.message();
            return new DiagnosticRunResult(
                    runId, status,
                    run.rulesRequested(), run.rulesExecuted(), run.rulesSkipped(),
                    List.of(), findings, window, run.createdAt(), run.completedAt(),
                    message, 0, run.engineVersion());
        }).orElse(null);
    }

    private interface Writer {
        long write(DiagnosticRunResult result, Path destination) throws Exception;
    }

    private Path export(
            Path destination, ProjectMetadataStore metadata, String projectId, String type, Writer writer
    ) {
        DiagnosticRunResult result = last.get();
        if (result == null) {
            throw new IllegalStateException("No diagnostic result to export");
        }
        try {
            long bytes = writer.write(result, destination);
            recordExport(metadata, projectId, type, destination, bytes);
            return destination;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot export diagnostics", e);
        }
    }

    private static void recordExport(
            ProjectMetadataStore metadata, String projectId, String type, Path destination, long bytes
    ) {
        if (metadata == null || projectId == null || projectId.isBlank()) {
            return;
        }
        ExportJobMetadata job = ExportJobMetadata.queued(projectId, type, "diagnostics", destination.toString());
        metadata.upsertExportJob(job.withProgress(bytes).withStatus(ExportJobStatus.COMPLETED, "Wrote " + bytes + " bytes"));
    }

    private static String compactEvidence(DiagnosticFinding finding) {
        StringBuilder sb = new StringBuilder();
        finding.evidence().forEach(e -> {
            if (!sb.isEmpty()) {
                sb.append(" | ");
            }
            sb.append(e.role()).append(": ").append(e.statement());
        });
        return sb.toString();
    }

    static String encodeKey(SignalCatalogKey key) {
        if (key == null) {
            return "";
        }
        return key.signalName() + '|' + key.sourceType() + '|' + key.messageName() + '|'
                + key.canId() + '|' + key.channel() + '|' + key.sourceFile() + '|' + key.sourceDatabase();
    }

    static SignalCatalogKey decodeKey(String blob) {
        if (blob == null || blob.isBlank()) {
            return null;
        }
        String[] p = blob.split("\\|", -1);
        if (p.length < 7) {
            return null;
        }
        try {
            return new SignalCatalogKey(p[0], SignalSourceType.valueOf(p[1]), p[2],
                    Long.parseLong(p[3]), Integer.parseInt(p[4]), p[5], p[6]);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
