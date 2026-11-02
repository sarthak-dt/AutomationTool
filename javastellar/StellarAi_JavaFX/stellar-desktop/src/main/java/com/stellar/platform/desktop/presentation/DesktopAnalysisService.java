package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.AnalysisEngine;
import com.stellar.platform.analysis.advanced.AdvancedAnalyticsRequest;
import com.stellar.platform.analysis.advanced.AdvancedAnalyticsResult;
import com.stellar.platform.analysis.advanced.AdvancedAnalyticsVersion;
import com.stellar.platform.analysis.advanced.AdvancedSignalAnalytics;
import com.stellar.platform.analysis.export.AnalysisCsvExporter;
import com.stellar.platform.analysis.invalidate.AnalysisInvalidationKey;
import com.stellar.platform.analysis.model.AnalysisRequest;
import com.stellar.platform.analysis.model.AnalysisResult;
import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.EngineeringFinding;
import com.stellar.platform.analysis.persist.AdvancedArtifactWriter;
import com.stellar.platform.analysis.persist.AnalysisArtifactStore;
import com.stellar.platform.analysis.report.AdvancedAnalyticsReport;
import com.stellar.platform.analysis.report.AnalysisReportFormatter;
import com.stellar.platform.analysis.source.AnalysisSampleSource;
import com.stellar.platform.parser.spi.SimpleCancellationToken;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.AnalysisFindingRow;
import com.stellar.platform.storage.metadata.AnalysisRunMetadata;
import com.stellar.platform.storage.metadata.AnalysisWorkspaceMetadata;
import com.stellar.platform.storage.metadata.ExportJobMetadata;
import com.stellar.platform.storage.metadata.ExportJobStatus;
import com.stellar.platform.storage.metadata.ProjectMetadataStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Presentation-layer analysis coordinator. No JavaFX. Delegates compute to stellar-analysis-core.
 */
public final class DesktopAnalysisService {

    private final AnalysisEngine engine = new AnalysisEngine();
    private final AdvancedSignalAnalytics advanced = new AdvancedSignalAnalytics();
    private final AnalysisArtifactStore artifacts = new AnalysisArtifactStore();
    private final AdvancedArtifactWriter advancedArtifacts = new AdvancedArtifactWriter();
    private final AnalysisCsvExporter csv = new AnalysisCsvExporter();
    private final AtomicReference<AnalysisResult> last = new AtomicReference<>();
    private final AtomicReference<AdvancedAnalyticsResult> lastAdvanced = new AtomicReference<>();
    private final AtomicReference<SimpleCancellationToken> running = new AtomicReference<>();
    private final AtomicReference<String> lastReport = new AtomicReference<>("");
    private final AtomicReference<String> lastType = new AtomicReference<>("");

    public AnalysisResult run(
            AnalysisRequest request,
            SignalSeriesStore store,
            ProjectMetadataStore metadata
    ) {
        SimpleCancellationToken token = new SimpleCancellationToken();
        running.set(token);
        lastType.set(request.analysisType().name());
        AnalysisSampleSource source = AnalysisSampleSource.of(store);
        AnalysisResult result = engine.execute(request, source, token);
        last.set(result);
        lastReport.set(AnalysisReportFormatter.format(result));
        if (result.status() == AnalysisStatus.CANCELLED) {
            artifacts.discardStaging(AnalysisStorageRoots.projectArtifactsRoot(request.projectId()), request.analysisId());
            persistRun(request, result, metadata, "");
            return result;
        }
        Path committed = null;
        if (result.status().successful()) {
            committed = artifacts.commit(AnalysisStorageRoots.projectArtifactsRoot(request.projectId()), result);
        }
        persistRun(request, result, metadata, committed == null ? "" : committed.toString());
        persistWorkspace(request, result, metadata);
        persistDerivedDefinition(request, result, metadata);
        running.set(null);
        return result;
    }

    public AdvancedAnalyticsResult runAdvanced(
            AdvancedAnalyticsRequest request,
            SignalSeriesStore store,
            ProjectMetadataStore metadata
    ) {
        SimpleCancellationToken token = new SimpleCancellationToken();
        running.set(token);
        lastType.set(request.type().name());
        AnalysisSampleSource source = AnalysisSampleSource.of(store);
        AdvancedAnalyticsResult result = advanced.execute(request, source, token);
        lastAdvanced.set(result);
        lastReport.set(AdvancedAnalyticsReport.format(result));
        Path root = AnalysisStorageRoots.projectArtifactsRoot(request.projectId());
        if (result.status() == AnalysisStatus.CANCELLED) {
            advancedArtifacts.discardStaging(root, request.analysisId());
            persistAdvancedRun(request, result, metadata, "");
            running.set(null);
            return result;
        }
        Path committed = null;
        if (result.status().successful()) {
            committed = advancedArtifacts.commit(root, result, request.persistDerived());
            result = result.toBuilder().artifactPath(committed == null ? "" : committed.toString()).build();
            lastAdvanced.set(result);
        }
        persistAdvancedRun(request, result, metadata, committed == null ? "" : committed.toString());
        persistAdvancedWorkspace(request, result, metadata);
        if (request.persistDerived() && result.derived() != null && metadata != null) {
            metadata.upsertDerivedSignalDefinition(new com.stellar.platform.storage.metadata.DerivedSignalDefinitionMetadata(
                    result.derived().identity().sourceDatabase(),
                    request.projectId(),
                    result.derived().identity().signalName(),
                    result.derived().expression(),
                    "EXPLICIT",
                    AdvancedAnalyticsVersion.CURRENT,
                    result.completedAt() == null ? java.time.Instant.now() : result.completedAt()
            ));
        }
        running.set(null);
        return result;
    }

    public void cancel() {
        SimpleCancellationToken token = running.get();
        if (token != null) {
            token.cancel();
        }
    }

    public AnalysisResult lastResult() {
        return last.get();
    }

    public AdvancedAnalyticsResult lastAdvanced() {
        return lastAdvanced.get();
    }

    public void restoreAdvanced(AdvancedAnalyticsResult result) {
        lastAdvanced.set(result);
        lastReport.set(result == null ? lastReport.get() : AdvancedAnalyticsReport.format(result));
        if (result != null) {
            lastType.set(result.type().name());
        }
    }

    public String lastReport() {
        return lastReport.get() == null ? "" : lastReport.get();
    }

    public String lastAnalysisType() {
        return lastType.get() == null ? "" : lastType.get();
    }

    public void restoreLast(AnalysisResult result) {
        restoreLast(result, result == null ? "" : result.message());
    }

    public void restoreLast(AnalysisResult result, String analysisType) {
        last.set(result);
        lastReport.set(result == null ? "" : AnalysisReportFormatter.format(result));
        if (analysisType != null && !analysisType.isBlank()) {
            lastType.set(analysisType);
        }
    }

    public Path exportSummary(Path destination, ProjectMetadataStore metadata, String projectId) {
        AnalysisResult result = last.get();
        if (result == null) {
            throw new IllegalStateException("No analysis result to export");
        }
        try {
            long bytes = csv.writeSummary(result, destination);
            recordExport(metadata, projectId, "ANALYSIS_SUMMARY_CSV", destination, bytes);
            return destination;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot export analysis summary", e);
        }
    }

    public Path exportSpectrum(Path destination, ProjectMetadataStore metadata, String projectId) {
        AdvancedAnalyticsResult result = lastAdvanced.get();
        if (result == null || result.spectrum() == null) {
            throw new IllegalStateException("No FFT spectrum to export");
        }
        try {
            long bytes = csv.writeSpectrum(result.spectrum(), destination);
            recordExport(metadata, projectId, "FFT_SPECTRUM_CSV", destination, bytes);
            return destination;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot export FFT spectrum", e);
        }
    }

    public Path exportPsd(Path destination, ProjectMetadataStore metadata, String projectId) {
        AdvancedAnalyticsResult result = lastAdvanced.get();
        if (result == null || result.psd() == null) {
            throw new IllegalStateException("No PSD to export");
        }
        try {
            long bytes = csv.writePsd(result.psd(), destination);
            recordExport(metadata, projectId, "PSD_CSV", destination, bytes);
            return destination;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot export PSD", e);
        }
    }

    public Path exportPeaks(Path destination, ProjectMetadataStore metadata, String projectId) {
        AdvancedAnalyticsResult result = lastAdvanced.get();
        if (result == null || result.peaks().isEmpty()) {
            throw new IllegalStateException("No spectral peaks to export");
        }
        try {
            long bytes = csv.writePeaks(result.peaks(), destination);
            recordExport(metadata, projectId, "SPECTRAL_PEAKS_CSV", destination, bytes);
            return destination;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot export spectral peaks", e);
        }
    }

    public Path exportAdvancedDerived(Path destination, ProjectMetadataStore metadata, String projectId) {
        AdvancedAnalyticsResult result = lastAdvanced.get();
        if (result == null || result.derived() == null) {
            throw new IllegalStateException("No derived series to export");
        }
        try {
            long bytes = csv.writeDerived(result.derived().points(), destination);
            recordExport(metadata, projectId, "DERIVED_SIGNAL_CSV", destination, bytes);
            return destination;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot export derived signal", e);
        }
    }

    public Path exportLag(Path destination, ProjectMetadataStore metadata, String projectId) {
        AdvancedAnalyticsResult result = lastAdvanced.get();
        if (result == null || result.lag() == null) {
            throw new IllegalStateException("No lag analysis to export");
        }
        try {
            long bytes = csv.writeLag(result, destination);
            recordExport(metadata, projectId, "LAG_ANALYSIS_CSV", destination, bytes);
            return destination;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot export lag analysis", e);
        }
    }

    public Path exportCycles(Path destination, ProjectMetadataStore metadata, String projectId) {
        AdvancedAnalyticsResult result = lastAdvanced.get();
        if (result == null || result.cycles() == null) {
            throw new IllegalStateException("No cycle analysis to export");
        }
        try {
            long bytes = csv.writeCycles(result.cycles().cycles(), destination);
            recordExport(metadata, projectId, "CYCLE_ANALYSIS_CSV", destination, bytes);
            return destination;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot export cycle analysis", e);
        }
    }

    public AdvancedArtifactWriter advancedArtifacts() {
        return advancedArtifacts;
    }

    public AdvancedAnalyticsResult saveLastDerived(String projectId, ProjectMetadataStore metadata) {
        AdvancedAnalyticsResult result = lastAdvanced.get();
        if (result == null || result.derived() == null) {
            throw new IllegalStateException("No derived series to save. Preview a filter first.");
        }
        Path committed = advancedArtifacts.commit(
                AnalysisStorageRoots.projectArtifactsRoot(projectId), result, true);
        if (metadata != null && result.derived() != null) {
            metadata.upsertDerivedSignalDefinition(new com.stellar.platform.storage.metadata.DerivedSignalDefinitionMetadata(
                    result.derived().identity().sourceDatabase(),
                    projectId,
                    result.derived().identity().signalName(),
                    result.derived().expression(),
                    "EXPLICIT",
                    AdvancedAnalyticsVersion.CURRENT,
                    java.time.Instant.now()
            ));
        }
        if (committed != null) {
            result = result.toBuilder().artifactPath(committed.toString()).build();
            lastAdvanced.set(result);
        }
        return result;
    }

    public Path exportFindings(Path destination, ProjectMetadataStore metadata, String projectId) {
        AnalysisResult result = last.get();
        if (result == null) {
            throw new IllegalStateException("No analysis result to export");
        }
        try {
            long bytes = csv.writeFindings(result, destination);
            recordExport(metadata, projectId, "ANALYSIS_FINDINGS_CSV", destination, bytes);
            return destination;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot export analysis findings", e);
        }
    }

    private static void persistRun(
            AnalysisRequest request,
            AnalysisResult result,
            ProjectMetadataStore metadata,
            String artifactRef
    ) {
        if (metadata == null) {
            return;
        }
        metadata.upsertAnalysisRun(new AnalysisRunMetadata(
                result.analysisId(),
                request.projectId(),
                request.analysisType().name(),
                result.status().name(),
                request.alignmentPolicy().name(),
                request.analysisVersion(),
                request.rangeSource().name(),
                result.effectiveRange().startNanos(),
                result.effectiveRange().endNanos(),
                request.signalKeys().toString(),
                AnalysisInvalidationKey.of(request).toString(),
                AnalysisInvalidationKey.of(request).toString(),
                artifactRef,
                result.message(),
                result.startedAt(),
                result.completedAt()
        ));
        List<AnalysisFindingRow> rows = new ArrayList<>();
        for (EngineeringFinding f : result.findings()) {
            rows.add(new AnalysisFindingRow(
                    f.findingId(), result.analysisId(), request.projectId(),
                    f.findingType().name(), f.severity().name(),
                    f.startTimestampNanos(), f.endTimestampNanos(),
                    f.summary(), f.details(), f.numericMetrics().toString(), f.createdAt()));
        }
        metadata.replaceAnalysisFindings(result.analysisId(), rows);
    }

    private static void persistAdvancedRun(
            AdvancedAnalyticsRequest request,
            AdvancedAnalyticsResult result,
            ProjectMetadataStore metadata,
            String artifactRef
    ) {
        if (metadata == null) {
            return;
        }
        metadata.upsertAnalysisRun(new AnalysisRunMetadata(
                result.analysisId(),
                request.projectId(),
                request.type().name(),
                result.status().name(),
                "EXACT_TIMESTAMP",
                AdvancedAnalyticsVersion.CURRENT,
                "VIEWPORT",
                request.window() == null ? null : request.window().startNanos(),
                request.window() == null ? null : request.window().endNanos(),
                request.signalKeys().toString(),
                result.cacheKey(),
                result.cacheKey(),
                artifactRef,
                result.message(),
                result.startedAt(),
                result.completedAt()
        ));
    }

    private static void persistAdvancedWorkspace(
            AdvancedAnalyticsRequest request,
            AdvancedAnalyticsResult result,
            ProjectMetadataStore metadata
    ) {
        if (metadata == null || !result.status().successful()) {
            return;
        }
        metadata.upsertAnalysisWorkspace(new AnalysisWorkspaceMetadata(
                request.projectId(),
                request.type().name(),
                result.analysisId(),
                true,
                "",
                result.completedAt() == null ? java.time.Instant.now() : result.completedAt()
        ));
    }

    private static void persistWorkspace(
            AnalysisRequest request,
            AnalysisResult result,
            ProjectMetadataStore metadata
    ) {
        if (metadata == null || !result.status().successful()) {
            return;
        }
        String findingId = result.findings().isEmpty() ? "" : result.findings().getFirst().findingId();
        metadata.upsertAnalysisWorkspace(new AnalysisWorkspaceMetadata(
                request.projectId(),
                request.analysisType().name(),
                result.analysisId(),
                true,
                findingId,
                Instant.now()
        ));
    }

    private static void persistDerivedDefinition(
            AnalysisRequest request,
            AnalysisResult result,
            ProjectMetadataStore metadata
    ) {
        if (metadata == null || result.derivedIdentity().isBlank()) {
            return;
        }
        metadata.upsertDerivedSignalDefinition(new com.stellar.platform.storage.metadata.DerivedSignalDefinitionMetadata(
                result.derivedIdentity(),
                request.projectId(),
                result.derivedIdentity(),
                request.parameters().derivedOp(),
                request.alignmentPolicy().name(),
                request.analysisVersion(),
                result.completedAt() == null ? Instant.now() : result.completedAt()
        ));
    }

    private static void recordExport(
            ProjectMetadataStore metadata,
            String projectId,
            String type,
            Path destination,
            long bytes
    ) {
        if (metadata == null || projectId == null || projectId.isBlank()) {
            return;
        }
        ExportJobMetadata job = ExportJobMetadata.queued(projectId, type, "analysis", destination.toString());
        metadata.upsertExportJob(job.withProgress(bytes).withStatus(ExportJobStatus.COMPLETED, "Wrote " + bytes + " bytes"));
    }
}
