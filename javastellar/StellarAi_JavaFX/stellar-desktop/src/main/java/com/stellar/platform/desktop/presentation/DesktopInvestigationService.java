package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineeringEvidenceItem;
import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.EngineeringObservation;
import com.stellar.platform.analysis.investigation.EvidenceKind;
import com.stellar.platform.analysis.investigation.GraphSnapshotMetadata;
import com.stellar.platform.analysis.investigation.InvestigationCsvExporter;
import com.stellar.platform.analysis.investigation.InvestigationHealth;
import com.stellar.platform.analysis.investigation.InvestigationPackageExporter;
import com.stellar.platform.analysis.investigation.EngineeringReportSection;
import com.stellar.platform.analysis.investigation.InvestigationReportConfig;
import com.stellar.platform.analysis.investigation.kpi.EngineeringKpiCalculator;
import com.stellar.platform.analysis.investigation.kpi.EngineeringKpiConfig;
import com.stellar.platform.analysis.investigation.kpi.EngineeringKpiResult;
import com.stellar.platform.analysis.investigation.kpi.EngineeringKpiSeries;
import com.stellar.platform.analysis.investigation.InvestigationStatus;
import com.stellar.platform.analysis.investigation.InvestigationStoragePaths;
import com.stellar.platform.analysis.investigation.InvestigationStaleness;
import com.stellar.platform.analysis.investigation.PathSanitizer;
import com.stellar.platform.analysis.investigation.report.EngineeringHtmlReportRenderer;
import com.stellar.platform.analysis.investigation.report.EngineeringPdfReportRenderer;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModel;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModelBuilder;
import com.stellar.platform.analysis.investigation.report.GraphImageEmbed;
import com.stellar.platform.analysis.investigation.report.RouteMapSnapshot;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.analysis.source.AnalysisCancelledException;
import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.parser.spi.SimpleCancellationToken;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.AnalysisScopeRecord;
import com.stellar.platform.storage.metadata.AnalysisRunMetadata;
import com.stellar.platform.storage.metadata.DiagnosticFindingRow;
import com.stellar.platform.storage.metadata.DiagnosticRunMetadata;
import com.stellar.platform.storage.metadata.EngineeringInvestigationRow;
import com.stellar.platform.storage.metadata.ExportJobMetadata;
import com.stellar.platform.storage.metadata.ExportJobStatus;
import com.stellar.platform.storage.metadata.GraphAnnotationMetadata;
import com.stellar.platform.storage.metadata.InvestigationEvidenceRow;
import com.stellar.platform.storage.metadata.InvestigationObservationRow;
import com.stellar.platform.storage.metadata.InvestigationReportRow;
import com.stellar.platform.storage.metadata.InvestigationWorkspaceMetadata;
import com.stellar.platform.storage.metadata.ProcessingArtifactMetadata;
import com.stellar.platform.storage.metadata.ProcessingErrorMetadata;
import com.stellar.platform.storage.metadata.ProcessingJobMetadata;
import com.stellar.platform.storage.metadata.ProjectFileMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadataStore;
import com.stellar.platform.storage.metadata.SavedProjectSnapshot;
import com.stellar.platform.storage.metadata.SignalCatalogMetadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Phase 8 investigation coordinator. No JavaFX. Reuses Phase 4/5/7 engines by reference only.
 */
public final class DesktopInvestigationService {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(DesktopInvestigationService.class.getName());

    private final AtomicReference<SimpleCancellationToken> running = new AtomicReference<>();
    private final AtomicReference<String> activeInvestigationId = new AtomicReference<>("");
    private Supplier<SignalSeriesStore> sampleStore = () -> null;

    /** Live report generation reads categorical labels from the reopened artifact, page by page. */
    public void samplesFrom(Supplier<SignalSeriesStore> source) {
        this.sampleStore = source == null ? () -> null : source;
    }
    private final Object reportCommit = new Object();

    /** Test hook. Invoked at named pipeline stages so cancellation can be observed without sleeping. */
    static java.util.function.Consumer<String> reportCheckpoint = stage -> {
    };

    public String activeInvestigationId() {
        return activeInvestigationId.get();
    }

    public void setActiveInvestigationId(String investigationId) {
        activeInvestigationId.set(investigationId == null ? "" : investigationId);
    }

    public List<EngineeringInvestigation> list(ProjectMetadataStore metadata, String projectId) {
        if (metadata == null || projectId == null || projectId.isBlank()) {
            return List.of();
        }
        List<EngineeringInvestigation> out = new ArrayList<>();
        for (EngineeringInvestigationRow row : metadata.listEngineeringInvestigations(projectId)) {
            out.add(hydrate(metadata, row));
        }
        return List.copyOf(out);
    }

    public List<EngineeringInvestigation> search(
            ProjectMetadataStore metadata, String projectId, String query, String status, String sort
    ) {
        List<EngineeringInvestigation> all = list(metadata, projectId);
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String st = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        List<EngineeringInvestigation> filtered = new ArrayList<>();
        for (EngineeringInvestigation inv : all) {
            if (!st.isEmpty() && !st.equals("ALL") && !inv.status().name().equals(st)) {
                continue;
            }
            if (!q.isEmpty()) {
                String hay = (inv.name() + " " + inv.description() + " " + inv.conclusion()).toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) {
                    continue;
                }
            }
            filtered.add(inv);
        }
        Comparator<EngineeringInvestigation> cmp = switch (sort == null ? "updated" : sort) {
            case "created" -> Comparator.comparing(EngineeringInvestigation::createdAt).reversed();
            case "name" -> Comparator.comparing(i -> i.name().toLowerCase(Locale.ROOT));
            default -> Comparator.comparing(EngineeringInvestigation::updatedAt).reversed();
        };
        filtered.sort(cmp);
        return List.copyOf(filtered);
    }

    public Optional<EngineeringInvestigation> find(
            ProjectMetadataStore metadata, String investigationId
    ) {
        if (metadata == null || investigationId == null || investigationId.isBlank()) {
            return Optional.empty();
        }
        return metadata.findEngineeringInvestigation(investigationId).map(row -> hydrate(metadata, row));
    }

    public EngineeringInvestigation save(ProjectMetadataStore metadata, EngineeringInvestigation investigation) {
        if (metadata == null || investigation == null) {
            return investigation;
        }
        EngineeringInvestigation stored = investigation.toBuilder().updatedAt(Instant.now()).build();
        metadata.runInTransaction(() -> {
            metadata.upsertEngineeringInvestigation(toRow(stored));
            metadata.replaceInvestigationSignals(stored.investigationId(), stored.signalKeyBlobs());
            metadata.replaceInvestigationEvidence(stored.investigationId(), toEvidenceRows(stored));
            metadata.replaceInvestigationObservations(stored.investigationId(), toObservationRows(stored));
        });
        return stored;
    }

    public EngineeringInvestigation duplicate(ProjectMetadataStore metadata, EngineeringInvestigation source) {
        Instant now = Instant.now();
        String newId = UUID.randomUUID().toString();
        List<EngineeringEvidenceItem> copied = new ArrayList<>();
        for (EngineeringEvidenceItem item : source.evidence()) {
            copied.add(new EngineeringEvidenceItem(
                    UUID.randomUUID().toString(), newId, item.kind(), item.referenceId(), item.sortOrder(),
                    item.stale(), item.staleReason(), item.sourceFingerprint(), item.artifactGeneration(),
                    item.caption(), item.summary(), now));
        }
        List<EngineeringObservation> notes = new ArrayList<>();
        for (EngineeringObservation o : source.observations()) {
            notes.add(new EngineeringObservation(
                    UUID.randomUUID().toString(), newId, o.startNanos(), o.endNanos(),
                    o.title(), o.note(), now, now));
        }
        EngineeringInvestigation copy = source.toBuilder()
                .investigationId(newId)
                .name(source.name() + " (copy)")
                .status(InvestigationStatus.DRAFT)
                .createdAt(now)
                .updatedAt(now)
                .evidence(copied)
                .observations(notes)
                .build();
        return save(metadata, copy);
    }

    public void delete(ProjectMetadataStore metadata, EngineeringInvestigation investigation) {
        if (metadata == null || investigation == null) {
            return;
        }
        String id = investigation.investigationId();
        Set<String> snapshotRefs = new HashSet<>();
        for (EngineeringInvestigationRow other : metadata.listEngineeringInvestigations(investigation.projectId())) {
            if (id.equals(other.investigationId())) {
                continue;
            }
            for (InvestigationEvidenceRow e : metadata.listInvestigationEvidence(other.investigationId())) {
                if ("GRAPH_SNAPSHOT".equals(e.kind())) {
                    snapshotRefs.add(e.referenceId());
                }
            }
        }
        for (var bookmark : metadata.listBookmarks(investigation.projectId())) {
            var defined = EngineerEventDocuments.decode(bookmark);
            if (defined != null) {
                snapshotRefs.addAll(defined.snapshotIds());
            }
        }
        Path artifacts = AnalysisStorageRoots.projectArtifactsRoot(investigation.projectId());
        for (EngineeringEvidenceItem item : investigation.evidence()) {
            if (item.kind() == EvidenceKind.GRAPH_SNAPSHOT && !snapshotRefs.contains(item.referenceId())) {
                Path png = InvestigationStoragePaths.snapshot(artifacts, id, item.referenceId());
                try {
                    Files.deleteIfExists(png);
                    Files.deleteIfExists(png.resolveSibling(item.referenceId() + ".json"));
                } catch (IOException ignored) {
                }
            }
        }
        for (InvestigationReportRow report : metadata.listInvestigationReports(id)) {
            deleteReportArtifactSafely(artifacts, id, report);
            metadata.deleteInvestigationReport(report.reportId());
        }
        metadata.deleteEngineeringInvestigation(id);
        if (id.equals(activeInvestigationId.get())) {
            activeInvestigationId.set("");
        }
    }

    public EngineeringInvestigation refreshStaleness(
            ProjectMetadataStore metadata, EngineeringInvestigation investigation, Set<String> currentFingerprints
    ) {
        if (investigation == null) {
            return null;
        }
        List<EngineeringEvidenceItem> refreshed =
                InvestigationStaleness.refreshAll(investigation.evidence(), currentFingerprints);
        EngineeringInvestigation updated = investigation.toBuilder().evidence(refreshed).updatedAt(Instant.now()).build();
        return save(metadata, updated);
    }

    public void refreshAllStaleness(
            ProjectMetadataStore metadata, String projectId, Set<String> currentFingerprints
    ) {
        for (EngineeringInvestigation inv : list(metadata, projectId)) {
            refreshStaleness(metadata, inv, currentFingerprints);
        }
    }

    public void rememberWorkspace(ProjectMetadataStore metadata, String projectId, String investigationId) {
        if (metadata == null || projectId == null || projectId.isBlank()) {
            return;
        }
        metadata.upsertInvestigationWorkspace(
                new InvestigationWorkspaceMetadata(projectId, investigationId == null ? "" : investigationId, Instant.now()));
        setActiveInvestigationId(investigationId);
    }

    public Optional<String> lastOpenInvestigationId(ProjectMetadataStore metadata, String projectId) {
        if (metadata == null || projectId == null) {
            return Optional.empty();
        }
        return metadata.findInvestigationWorkspace(projectId)
                .map(InvestigationWorkspaceMetadata::lastInvestigationId)
                .filter(id -> !id.isBlank());
    }

    public EngineeringInvestigation addEvidence(ProjectMetadataStore metadata, EngineeringInvestigation inv,
                                                EngineeringEvidenceItem item) {
        List<EngineeringEvidenceItem> next = new ArrayList<>(inv.evidence());
        int order = next.size();
        next.add(item.withOrder(order));
        return save(metadata, inv.toBuilder().evidence(next).build());
    }

    public EngineeringInvestigation reorderEvidence(ProjectMetadataStore metadata, EngineeringInvestigation inv,
                                                    List<String> evidenceIdsInOrder) {
        LinkedHashMap<String, EngineeringEvidenceItem> byId = new LinkedHashMap<>();
        for (EngineeringEvidenceItem item : inv.evidence()) {
            byId.put(item.evidenceId(), item);
        }
        List<EngineeringEvidenceItem> ordered = new ArrayList<>();
        int i = 0;
        if (evidenceIdsInOrder != null) {
            for (String id : evidenceIdsInOrder) {
                EngineeringEvidenceItem item = byId.remove(id);
                if (item != null) {
                    ordered.add(item.withOrder(i++));
                }
            }
        }
        for (EngineeringEvidenceItem leftover : byId.values()) {
            ordered.add(leftover.withOrder(i++));
        }
        return save(metadata, inv.toBuilder().evidence(ordered).build());
    }

    public Path writeSnapshotPng(String projectId, String investigationId, String snapshotId, byte[] png)
            throws IOException {
        Path dest = InvestigationStoragePaths.snapshot(
                AnalysisStorageRoots.projectArtifactsRoot(projectId), investigationId, snapshotId);
        Files.createDirectories(dest.getParent());
        Path staging = dest.resolveSibling(dest.getFileName() + ".staging");
        Files.write(staging, png == null ? new byte[0] : png);
        try {
            Files.move(staging, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.FileSystemException ex) {
            Files.move(staging, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return dest;
    }

    public void writeSnapshotMetadata(GraphSnapshotMetadata meta) throws IOException {
        Path png = Path.of(meta.fileReference());
        Path json = png.resolveSibling(meta.snapshotId() + ".json");
        Files.writeString(json, meta.toJson(), StandardCharsets.UTF_8);
    }

    public ReportJob startReportJob() {
        SimpleCancellationToken token = new SimpleCancellationToken();
        running.set(token);
        return new ReportJob(token);
    }

    public void cancelReport() {
        synchronized (reportCommit) {
            SimpleCancellationToken token = running.get();
            if (token != null) {
                token.cancel();
            }
        }
    }

    public Path generateHtmlReport(
            ProjectMetadataStore metadata,
            EngineeringInvestigation investigation,
            String author,
            CancellationToken token
    ) throws IOException {
        return generateHtmlReport(metadata, investigation, author, token, null);
    }

    public Path generateHtmlReport(
            ProjectMetadataStore metadata,
            EngineeringInvestigation investigation,
            String author,
            CancellationToken token,
            RouteMapReportAsset automaticMap
    ) throws IOException {
        return generateHtmlReport(metadata, investigation, author, token, automaticMap, List.of());
    }

    public Path generateHtmlReport(
            ProjectMetadataStore metadata,
            EngineeringInvestigation investigation,
            String author,
            CancellationToken token,
            RouteMapReportAsset automaticMap,
            List<SignalReportImage> signalImages
    ) throws IOException {
        checkpoint("start", token);
        String reportId = UUID.randomUUID().toString();
        Path artifacts = AnalysisStorageRoots.projectArtifactsRoot(investigation.projectId());
        Path staging = InvestigationStoragePaths.reportStaging(artifacts, investigation.investigationId(), reportId);
        Path dest = InvestigationStoragePaths.reportDir(artifacts, investigation.investigationId(), reportId);
        InvestigationPackageExporter.deleteTreeQuietly(staging);
        Files.createDirectories(staging.resolve("assets"));
        InvestigationReportRow queued = new InvestigationReportRow(
                reportId, investigation.investigationId(), investigation.projectId(),
                investigation.name(), "HTML", "RUNNING", dest.toString(), "", Instant.now(), null);
        metadata.upsertInvestigationReport(queued);
        try {
            Path assets = staging.resolve("assets");
            checkpoint("signals", token);
            writeSignalImages(signalImages, assets);
            checkpoint("map", token);
            EngineeringReportModel.RouteMapSection route = routeSectionForReport(
                    investigation, artifacts, assets, automaticMap);
            checkpoint("evidence", token);
            List<EngineeringReportModel.Figure> figures = ReportEvidenceAssets.copy(
                    investigation, artifacts, assets, assetHashes(assets, signalImages, automaticMap), token);
            EngineeringReportModel rendered = buildReportModel(metadata, investigation, author, figures);
            if (route != null) {
                rendered = rendered.withRouteMap(route);
            }
            rendered = rendered.withSignalAnalysis(SignalReportRenderer.sections(signalImages));
            rendered = withLinkedSnapshots(rendered, investigation, metadata);
            checkpoint("html", token);
            String html = EngineeringHtmlReportRenderer.render(rendered);
            Files.writeString(staging.resolve("report.html"), html, StandardCharsets.UTF_8);
            if (!Files.isRegularFile(staging.resolve("report.html"))) {
                throw new IOException("Report HTML was not written.");
            }
            ReportGeneratedAssetCleanup.removeLegacyDetailMaps(assets);
            Path htmlPath;
            synchronized (reportCommit) {
                checkpoint("commit", token);
                InvestigationPackageExporter.deleteTreeQuietly(dest);
                Files.createDirectories(dest.getParent());
                try {
                    Files.move(staging, dest, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.FileSystemException ex) {
                    Files.move(staging, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                htmlPath = dest.resolve("report.html");
                long size = ReportPackageSize.bytes(htmlPath);
                metadata.upsertInvestigationReport(new InvestigationReportRow(
                        reportId, investigation.investigationId(), investigation.projectId(),
                        investigation.name(), "HTML", "COMPLETED", htmlPath.toString(), "",
                        queued.createdAt(), Instant.now()));
                ExportJobMetadata job = ExportJobMetadata.queued(
                        investigation.projectId(), "INVESTIGATION_HTML", investigation.investigationId(),
                        htmlPath.toString());
                metadata.upsertExportJob(job.withProgress(Math.max(0L, size))
                        .withStatus(ExportJobStatus.COMPLETED, "Engineering report written"));
                running.set(null);
            }
            return htmlPath;
        } catch (AnalysisCancelledException cancelled) {
            InvestigationPackageExporter.deleteTreeQuietly(staging);
            InvestigationPackageExporter.deleteTreeQuietly(dest);
            metadata.deleteInvestigationReport(reportId);
            throw cancelled;
        } catch (IOException | RuntimeException e) {
            InvestigationPackageExporter.deleteTreeQuietly(staging);
            metadata.upsertInvestigationReport(new InvestigationReportRow(
                    reportId, investigation.investigationId(), investigation.projectId(),
                    investigation.name(), "HTML", "FAILED", "",
                    e.getMessage() == null ? e.toString() : e.getMessage(),
                    queued.createdAt(), Instant.now()));
            throw e;
        } finally {
            running.set(null);
        }
    }

    public Path writeHtmlReport(
            ProjectMetadataStore metadata,
            EngineeringInvestigation investigation,
            String author,
            String projectStatus,
            Path destination,
            CancellationToken token
    ) throws IOException {
        return writeHtmlReport(metadata, investigation, author, projectStatus, destination, token, null);
    }

    public Path writeHtmlReport(
            ProjectMetadataStore metadata,
            EngineeringInvestigation investigation,
            String author,
            String projectStatus,
            Path destination,
            CancellationToken token,
            RouteMapReportAsset automaticMap
    ) throws IOException {
        return writeHtmlReport(metadata, investigation, author, projectStatus, destination, token,
                automaticMap, List.of());
    }

    public Path writeHtmlReport(
            ProjectMetadataStore metadata,
            EngineeringInvestigation investigation,
            String author,
            String projectStatus,
            Path destination,
            CancellationToken token,
            RouteMapReportAsset automaticMap,
            List<SignalReportImage> signalImages
    ) throws IOException {
        return writeHtmlReport(metadata, investigation, author, projectStatus, destination, token,
                automaticMap, signalImages, true);
    }

    public Path writeHtmlReport(
            ProjectMetadataStore metadata,
            EngineeringInvestigation investigation,
            String author,
            String projectStatus,
            Path destination,
            CancellationToken token,
            RouteMapReportAsset automaticMap,
            List<SignalReportImage> signalImages,
            boolean recordHistory
    ) throws IOException {
        if (destination == null) {
            throw new IOException("Choose a destination for the report.");
        }
        Path writing = destination.resolveSibling(destination.getFileName().toString() + ".writing");
        List<Path> created = new ArrayList<>();
        String reportId = UUID.randomUUID().toString();
        try {
            checkpoint("start", token);
            Path parent = destination.getParent() == null ? Path.of(".") : destination.getParent();
            Path assets = parent.resolve("assets");
            Files.createDirectories(assets);
            checkpoint("signals", token);
            created.addAll(writeSignalImages(signalImages, assets));
            checkpoint("map", token);
            Path artifacts = AnalysisStorageRoots.projectArtifactsRoot(investigation.projectId());
            EngineeringReportModel.RouteMapSection automatic = routeSectionForReport(
                    investigation, artifacts, assets, automaticMap);
            if (automaticMap != null && automaticMap.hasImage()) {
                placeAutomaticMap(automaticMap, assets, mirrorRouteAsset(investigation, reportId));
                created.add(assets.resolve(InvestigationStoragePaths.ROUTE_MAP_ASSET));
            }
            checkpoint("evidence", token);
            List<EngineeringReportModel.Figure> figures = ReportEvidenceAssets.copy(
                    investigation, artifacts, assets, assetHashes(assets, signalImages, automaticMap), token);
            EngineeringReportModel model = buildReportModel(metadata, investigation, author, figures);
            if (projectStatus != null && !projectStatus.isBlank()) {
                model = model.withProjectStatus(projectStatus);
            }
            if (automatic != null) {
                model = model.withRouteMap(automatic);
            }
            model = model.withSignalAnalysis(SignalReportRenderer.sections(signalImages));
            model = withLinkedSnapshots(model, investigation, metadata);
            checkpoint("html", token);
            if (Files.exists(destination) && Files.size(destination) > 0) {
                throw new IOException("A report with this name already exists.");
            }
            String html = EngineeringHtmlReportRenderer.render(model);
            Files.writeString(writing, html, StandardCharsets.UTF_8);
            synchronized (reportCommit) {
                checkpoint("commit", token);
                try {
                    Files.move(writing, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException atomic) {
                    try {
                        Files.move(writing, destination, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException fallback) {
                        Files.deleteIfExists(writing);
                        fallback.addSuppressed(atomic);
                        throw fallback;
                    }
                }
                if (!Files.isRegularFile(destination) || Files.size(destination) == 0) {
                    throw new IOException("Report HTML was not written.");
                }
                ReportGeneratedAssetCleanup.removeLegacyDetailMaps(assets);
                if (recordHistory && metadata != null) {
                    String display = com.stellar.platform.analysis.investigation.report.ReportNameAllocator
                            .stemOf(destination.getFileName().toString());
                    long size = ReportPackageSize.bytes(destination);
                    metadata.upsertInvestigationReport(new InvestigationReportRow(
                            reportId, investigation.investigationId(), investigation.projectId(),
                            display, "HTML", "COMPLETED", destination.toString(), "",
                            Instant.now(), Instant.now()));
                    ExportJobMetadata job = ExportJobMetadata.queued(
                            investigation.projectId(), "INVESTIGATION_HTML", investigation.investigationId(),
                            destination.toString());
                    metadata.upsertExportJob(job.withProgress(Math.max(0L, size))
                            .withStatus(ExportJobStatus.COMPLETED, "Engineering report written"));
                }
                running.set(null);
            }
            return destination;
        } catch (AnalysisCancelledException cancelled) {
            Files.deleteIfExists(writing);
            if (Files.isRegularFile(destination) && Files.size(destination) == 0) {
                Files.deleteIfExists(destination);
            }
            for (Path createdFile : created) {
                Files.deleteIfExists(createdFile);
            }
            if (metadata != null) {
                metadata.deleteInvestigationReport(reportId);
            }
            throw cancelled;
        } finally {
            running.set(null);
        }
    }

    public Path writePdfReport(
            ProjectMetadataStore metadata,
            EngineeringInvestigation investigation,
            String author,
            String projectStatus,
            Path destination,
            CancellationToken token,
            RouteMapReportAsset automaticMap,
            List<SignalReportImage> signalImages
    ) throws IOException {
        if (destination == null) {
            throw new IOException("Choose a destination for the report.");
        }
        List<Path> created = new ArrayList<>();
        String reportId = UUID.randomUUID().toString();
        Path staging = null;
        try {
            checkpoint("start", token);
            staging = Files.createTempDirectory("stellarai-pdf-");
            Path assets = staging.resolve("assets");
            Files.createDirectories(assets);
            checkpoint("signals", token);
            created.addAll(writeSignalImages(signalImages, assets));
            checkpoint("map", token);
            Path artifacts = AnalysisStorageRoots.projectArtifactsRoot(investigation.projectId());
            EngineeringReportModel.RouteMapSection automatic = routeSectionForReport(
                    investigation, artifacts, assets, automaticMap);
            if (automaticMap != null && automaticMap.hasImage()) {
                placeAutomaticMap(automaticMap, assets, mirrorRouteAsset(investigation, reportId));
                created.add(assets.resolve(InvestigationStoragePaths.ROUTE_MAP_ASSET));
            }
            checkpoint("evidence", token);
            List<EngineeringReportModel.Figure> figures = ReportEvidenceAssets.copy(
                    investigation, artifacts, assets, assetHashes(assets, signalImages, automaticMap), token);
            EngineeringReportModel model = buildReportModel(metadata, investigation, author, figures);
            if (projectStatus != null && !projectStatus.isBlank()) {
                model = model.withProjectStatus(projectStatus);
            }
            if (automatic != null) {
                model = model.withRouteMap(automatic);
            }
            model = model.withSignalAnalysis(SignalReportRenderer.sections(signalImages));
            model = withLinkedSnapshots(model, investigation, metadata);
            checkpoint("pdf", token);
            if (Files.exists(destination) && Files.size(destination) > 0) {
                throw new IOException("A report with this name already exists.");
            }
            EngineeringPdfReportRenderer.write(model, destination, staging);
            synchronized (reportCommit) {
                checkpoint("commit", token);
                if (!Files.isRegularFile(destination) || Files.size(destination) < 5) {
                    throw new IOException("Report PDF was not written.");
                }
                ReportGeneratedAssetCleanup.removeLegacyDetailMaps(assets);
                if (metadata != null) {
                    String fileName = destination.getFileName().toString();
                    String display = fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")
                            ? fileName.substring(0, fileName.length() - 4) : fileName;
                    long size = ReportPackageSize.bytes(destination);
                    metadata.upsertInvestigationReport(new InvestigationReportRow(
                            reportId, investigation.investigationId(), investigation.projectId(),
                            display, "PDF", "COMPLETED", destination.toString(), "",
                            Instant.now(), Instant.now()));
                    ExportJobMetadata job = ExportJobMetadata.queued(
                            investigation.projectId(), "INVESTIGATION_PDF", investigation.investigationId(),
                            destination.toString());
                    metadata.upsertExportJob(job.withProgress(Math.max(0L, size))
                            .withStatus(ExportJobStatus.COMPLETED, "Engineering report written"));
                }
                running.set(null);
            }
            return destination;
        } catch (AnalysisCancelledException cancelled) {
            if (Files.isRegularFile(destination) && Files.size(destination) == 0) {
                Files.deleteIfExists(destination);
            }
            for (Path createdFile : created) {
                Files.deleteIfExists(createdFile);
            }
            if (metadata != null) {
                metadata.deleteInvestigationReport(reportId);
            }
            throw cancelled;
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(destination.resolveSibling(destination.getFileName().toString() + ".writing"));
            if (metadata != null) {
                metadata.upsertInvestigationReport(new InvestigationReportRow(
                        reportId, investigation.investigationId(), investigation.projectId(),
                        "", "PDF", "FAILED", "",
                        failure.getMessage() == null ? failure.toString() : failure.getMessage(),
                        Instant.now(), Instant.now()));
            }
            throw failure;
        } finally {
            deleteStaging(staging);
            running.set(null);
        }
    }

    private static void deleteStaging(Path staging) {
        if (staging == null || !Files.exists(staging)) {
            return;
        }
        try (var walk = Files.walk(staging)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // The PDF already embeds its images. A leftover staging directory is not report content.
        }
    }

    public Path exportPackage(
            ProjectMetadataStore metadata,
            EngineeringInvestigation investigation,
            Path destination,
            String author,
            CancellationToken token
    ) throws IOException {
        return exportPackage(metadata, investigation, destination, author, token, null, List.of());
    }

    public Path exportPackage(
            ProjectMetadataStore metadata,
            EngineeringInvestigation investigation,
            Path destination,
            String author,
            CancellationToken token,
            RouteMapReportAsset automaticMap,
            List<SignalReportImage> signalImages
    ) throws IOException {
        checkpoint("start", token);
        Path artifacts = AnalysisStorageRoots.projectArtifactsRoot(investigation.projectId());
        String scratchId = "export-" + UUID.randomUUID();
        Path staging = InvestigationStoragePaths.reportStaging(
                artifacts, investigation.investigationId(), scratchId);
        try {
            Path assets = staging.resolve("assets");
            Files.createDirectories(assets);
            checkpoint("signals", token);
            writeSignalImages(signalImages, assets);
            checkpoint("map", token);
            EngineeringReportModel.RouteMapSection route = routeSectionForReport(
                    investigation, artifacts, assets, automaticMap);
            checkpoint("evidence", token);
            List<EngineeringReportModel.Figure> figures = ReportEvidenceAssets.copy(
                    investigation, artifacts, assets, assetHashes(assets, signalImages, automaticMap), token);
            EngineeringReportModel model = buildReportModel(metadata, investigation, author, figures)
                    .withSignalAnalysis(SignalReportRenderer.sections(signalImages));
            if (route != null) {
                model = model.withRouteMap(route);
            }
            model = withLinkedSnapshots(model, investigation, metadata);
            ReportGeneratedAssetCleanup.removeLegacyDetailMaps(assets);
            List<Path> reportAssets = new ArrayList<>();
            try (var walk = Files.list(assets)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> !ReportGeneratedAssetCleanup.isLegacyDetailMap(
                                path.getFileName().toString()))
                        .forEach(reportAssets::add);
            }
            List<Path> snapshots = snapshotFiles(investigation, artifacts);
            List<InvestigationPackageExporter.NamedText> extras = List.of(
                    new InvestigationPackageExporter.NamedText("findings.csv",
                            InvestigationCsvExporter.findings(investigation)),
                    new InvestigationPackageExporter.NamedText("analysis.csv",
                            InvestigationCsvExporter.analysis(investigation)),
                    new InvestigationPackageExporter.NamedText("evidence.csv",
                            InvestigationCsvExporter.evidence(investigation)));
            checkpoint("html", token);
            Path written = InvestigationPackageExporter.write(
                    destination, investigation, model, snapshots, extras, reportAssets, token);
            if (metadata != null) {
                ExportJobMetadata job = ExportJobMetadata.queued(
                        investigation.projectId(), "INVESTIGATION_PACKAGE", investigation.investigationId(),
                        written.toString());
                metadata.upsertExportJob(job.withProgress(Files.size(written))
                        .withStatus(ExportJobStatus.COMPLETED, "Investigation package written"));
            }
            return written;
        } finally {
            InvestigationPackageExporter.deleteTreeQuietly(staging);
            running.set(null);
        }
    }

    public void deleteReport(ProjectMetadataStore metadata, InvestigationReportRow report) throws IOException {
        if (metadata == null || report == null) {
            return;
        }
        new ReportDeleteService().delete(report, row -> metadata.deleteInvestigationReport(row.reportId()));
        cleanupManagedReportDirectory(report);
        deleteSiblingRouteMap(report);
    }

    public EngineeringReportModel buildReportModel(
            ProjectMetadataStore metadata, EngineeringInvestigation investigation, String author
    ) {
        return buildReportModel(metadata, investigation, author,
                snapshotFigures(investigation, snapshotFiles(investigation,
                        AnalysisStorageRoots.projectArtifactsRoot(investigation.projectId()))));
    }

    public record ReportJob(SimpleCancellationToken token) {
        public void cancel() {
            token.cancel();
        }
    }

    private EngineeringReportModel buildReportModel(
            ProjectMetadataStore metadata,
            EngineeringInvestigation investigation,
            String author,
            List<EngineeringReportModel.Figure> figures
    ) {
        SavedProjectSnapshot snap = metadata == null
                ? null
                : metadata.loadProject(investigation.projectId()).orElse(null);
        ProjectMetadata project = snap == null ? null : snap.project();
        List<ProcessingJobMetadata> jobs = metadata == null
                ? List.of() : metadata.listProcessingJobs(investigation.projectId());
        Map<String, JobExecutionStatus> jobBySource = latestJobBySource(jobs);
        List<EngineeringReportModel.SourceRow> sources = new ArrayList<>();
        List<EngineeringReportModel.DefinitionRow> definitions = new ArrayList<>();
        int measurements = 0;
        int definitionCount = 0;
        int unprocessed = 0;
        long canFrames = 0;
        boolean missingUnit = false;
        if (snap != null) {
            for (ProjectFileMetadata file : snap.files()) {
                boolean measurement = file.kind() != null && file.kind().contains("MEASUREMENT");
                boolean definition = file.kind() != null && file.kind().contains("DEFINITION");
                String filename = PathSanitizer.displayName(file.path());
                if (definition) {
                    definitionCount++;
                    definitions.add(new EngineeringReportModel.DefinitionRow(
                            filename, formatLabel(file.kind()), definitionStatus(file)));
                }
                if (!measurement) {
                    continue;
                }
                measurements++;
                CanSourceInventory inventory = inventoryFor(snap, file);
                long frames = inventory == null ? 0L : inventory.totalCanFrames();
                long channels = inventory == null ? 0L : inventory.totalCanChannels();
                canFrames += frames;
                String outcome = measurementOutcome(file, jobBySource,
                        snap.processingArtifacts() == null ? List.of() : snap.processingArtifacts());
                if (!ProcessingStatusLabels.COMPLETED.equals(outcome)
                        && !"Processed".equals(outcome)
                        && !"Processed with warnings".equals(outcome)) {
                    unprocessed++;
                }
                sources.add(new EngineeringReportModel.SourceRow(
                        filename,
                        formatLabel(file.kind()),
                        channels + "\t" + frames,
                        sizeLabel(file.sizeBytes()),
                        outcome));
            }
        }
        List<SignalCatalogMetadata> catalog = metadata == null
                ? List.of() : metadata.listSignalCatalog(investigation.projectId());
        Map<String, String> fileNameById = new LinkedHashMap<>();
        if (snap != null) {
            for (ProjectFileMetadata file : snap.files()) {
                fileNameById.put(file.fileId(), PathSanitizer.displayName(file.path()));
            }
        }
        AnalysisScopeRecord scope = metadata == null
                ? null
                : ReportMapScope.durableScope(metadata, investigation.projectId());
        List<EngineeringReportModel.SignalRow> signals = evidenceSignals(
                investigation, catalog, fileNameById, scope, sampleStore.get());
        for (EngineeringReportModel.SignalRow signal : signals) {
            if (signal.unit() == null || signal.unit().isBlank() || "—".equals(signal.unit())) {
                missingUnit = true;
                break;
            }
        }
        List<EngineeringReportModel.ResultBlock> analysis = new ArrayList<>();
        List<EngineeringReportModel.ResultBlock> diagnostics = new ArrayList<>();
        List<EngineeringReportModel.ResultBlock> frequency = new ArrayList<>();
        if (metadata != null) {
            for (EngineeringEvidenceItem item : investigation.evidence()) {
                if (item.kind() == EvidenceKind.ANALYSIS_RESULT) {
                    analysis.add(analysisBlock(metadata, item));
                } else if (item.kind() == EvidenceKind.DIAGNOSTIC_FINDING) {
                    diagnostics.add(diagnosticBlock(metadata, investigation.projectId(), item));
                } else if (item.kind() == EvidenceKind.SPECTRAL_RESULT) {
                    frequency.add(spectralBlock(metadata, item));
                }
            }
        }
        List<String> warnings = new ArrayList<>();
        List<String> technical = new ArrayList<>();
        if (metadata != null) {
            for (ProcessingErrorMetadata err : metadata.listProcessingErrors(investigation.projectId())) {
                String summary = err.summary() == null || err.summary().isBlank()
                        ? err.severity() : err.summary();
                warnings.add(summary);
                if (err.technicalDetail() != null && !err.technicalDetail().isBlank()) {
                    technical.add(summary + ": " + err.technicalDetail());
                }
            }
            if (snap != null) {
                for (var dq : snap.dataQualityFindings()) {
                    warnings.add(dq.findingType() + ": " + dq.summary());
                }
                for (String diagnostic : persistedDiagnosticWarnings(snap.processingArtifacts())) {
                    if (!warnings.contains(diagnostic)) {
                        warnings.add(diagnostic);
                    }
                }
            }
        }
        JobCounts counts = countMeasurementOutcomes(snap, jobs);
        String processingLabel = processingLabel(snap, counts, warnings.size());
        EngineeringReportModel.ProjectSection projectSection = new EngineeringReportModel.ProjectSection(
                project == null ? "" : project.name(),
                sources.size(), measurements, definitionCount, catalog.size(),
                processingLabel, warnings.size());
        EngineeringReportModel.RouteMapSection routeMap = routeMapSection(
                investigation, AnalysisStorageRoots.projectArtifactsRoot(investigation.projectId()), null);
        EngineeringReportModel model = EngineeringReportModelBuilder.build(
                investigation, new EngineeringReportModelBuilder.Context(
                "StellarAI " + DesktopVersion.displayVersion(),
                author == null ? "" : author,
                projectSection,
                sources,
                signals,
                analysis,
                diagnostics,
                frequency,
                enrichFigures(investigation, figures),
                warnings).withRouteMap(routeMap));
        List<String> limitations = new ArrayList<>();
        if (warnings.size() > 0 || counts.warned > 0) {
            limitations.add("Informational diagnostics were recorded. See processing details.");
        }
        if (unprocessed > 0) {
            limitations.add(unprocessed == 1
                    ? "1 measurement file is not processed."
                    : unprocessed + " measurement files are not processed.");
        }
        if (measurements > 0 && definitionCount == 0) {
            limitations.add("No definition file is attached to this project.");
        }
        if (missingUnit) {
            limitations.add("One or more signals have no unit.");
        }
        List<String> appendix = new ArrayList<>(model.appendix());
        appendix.addAll(technical);
        for (EngineeringReportModel.SignalRow signal : signals) {
            appendix.add("Signal " + signal.name()
                    + (signal.source().isBlank() ? "" : " · " + signal.source()));
        }
        model = model.withProjectFacts(
                definitions,
                new EngineeringReportModel.ProcessingDetail(
                        processingLabel, counts.successful, counts.failed, warnings.size(),
                        processingFinished(snap), canFrames),
                limitations).withAppendix(appendix);
        if (model.shows(EngineeringReportSection.ENGINEERING_KPIS)) {
            model = model.withKpis(kpiResults(investigation));
        }
        model = model.withEvents(eventRows(metadata, investigation, fileNameById))
                .withEvaluations(evaluationRows(metadata, investigation.projectId()));
        java.util.Set<String> sourceNames = new java.util.LinkedHashSet<>();
        java.util.Map<String, java.nio.file.Path> measurementPaths = new java.util.LinkedHashMap<>();
        for (EngineeringReportModel.SourceRow source : sources) {
            if (source.displayName() != null && !source.displayName().isBlank()) {
                sourceNames.add(source.displayName());
            }
        }
        if (snap != null) {
            for (ProjectFileMetadata file : snap.files()) {
                if (file.kind() != null && file.kind().contains("MEASUREMENT")) {
                    measurementPaths.put(file.fileId(), java.nio.file.Path.of(file.path()));
                }
            }
        }
        return model.withFindings(ReportQualityFindings.collect(
                investigation, sampleStore.get(), metadata, sourceNames, definitionCount, unprocessed,
                measurementPaths, snap == null ? java.util.List.of() : snap.channelMappings()));
    }

    private List<EngineeringKpiResult> kpiResults(EngineeringInvestigation investigation) {
        EngineeringKpiConfig config = EngineeringKpiConfig.parse(investigation.reportConfig().kpiConfig());
        SignalSeriesStore store = sampleStore.get();
        List<SignalCatalogEntry> entries = List.of();
        try {
            if (store != null && store.snapshot() != null && store.snapshot().entries() != null) {
                entries = store.snapshot().entries();
            }
        } catch (RuntimeException ex) {
            store = null;
        }
        List<EngineeringKpiSeries> series = new ArrayList<>();
        bind(series, store, entries, EngineeringKpiCalculator.SPEED, config.vehicleSpeed());
        bind(series, store, entries, EngineeringKpiCalculator.GNSS, config.gnssSpeed());
        bind(series, store, entries, EngineeringKpiCalculator.RPM, config.engineRpm());
        bind(series, store, entries, EngineeringKpiCalculator.ACCEL, config.accelerator());
        bind(series, store, entries, EngineeringKpiCalculator.COOLANT, config.coolant());
        bind(series, store, entries, EngineeringKpiCalculator.TRANS, config.transmissionOil());
        for (String name : config.thermocouples()) {
            bind(series, store, entries, EngineeringKpiCalculator.THERMO, name);
        }
        bind(series, store, entries, EngineeringKpiCalculator.ABS, config.abs());
        bind(series, store, entries, EngineeringKpiCalculator.ESP, config.esp());
        bind(series, store, entries, EngineeringKpiCalculator.TCS, config.tcs());
        bind(series, store, entries, EngineeringKpiCalculator.CRUISE, config.cruise());
        bind(series, store, entries, EngineeringKpiCalculator.BRAKE, config.brake());
        return EngineeringKpiCalculator.calculate(config, series);
    }

    private void bind(
            List<EngineeringKpiSeries> series,
            SignalSeriesStore store,
            List<SignalCatalogEntry> entries,
            String role,
            String name
    ) {
        if (name == null || name.isBlank()) {
            return;
        }
        if (store == null) {
            series.add(EngineeringKpiSeries.missing(role, name,
                    EngineeringKpiSeries.Availability.SAMPLES_NOT_LOADED));
            return;
        }
        boolean found = false;
        for (SignalCatalogEntry entry : entries) {
            if (entry == null || !name.equals(entry.signalName())) {
                continue;
            }
            found = true;
            List<EngineeringKpiSeries.Sample> samples = new ArrayList<>();
            store.forEachSample(entry.key(), point -> {
                SimpleCancellationToken token = running.get();
                if (token != null && token.isCancellationRequested()) {
                    throw new AnalysisCancelledException();
                }
                if (point != null) {
                    samples.add(sample(point));
                }
            });
            series.add(EngineeringKpiSeries.present(
                    role, entry.signalName(), entry.unit(), entry.sourceFile(), samples));
        }
        if (!found) {
            series.add(EngineeringKpiSeries.missing(role, name, EngineeringKpiSeries.Availability.NOT_FOUND));
        }
    }

    private static EngineeringKpiSeries.Sample sample(SignalSamplePoint point) {
        return new EngineeringKpiSeries.Sample(
                point.timestampNanos(), point.physicalValue(), point.usableMeasurement(), point.textualValue());
    }

    /**
     * Warning lines already stored on valid artifacts. The processing page reads these
     * diagnostics; the report previously counted only the separate processing-error ledger.
     */
    static List<String> persistedDiagnosticWarnings(List<ProcessingArtifactMetadata> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> lines = new java.util.LinkedHashSet<>();
        for (ProcessingArtifactMetadata artifact : artifacts) {
            if (artifact == null || !ProcessingArtifactMetadata.STATUS_VALID.equals(artifact.status())) {
                continue;
            }
            for (com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnostic diagnostic
                    : ProcessingArtifactDiagnosticsCodec.decode(artifact.diagnosticsSummary())) {
                if (diagnostic == null) {
                    continue;
                }
                String detail = diagnostic.detail() == null ? "" : diagnostic.detail().trim();
                lines.add(detail.isBlank() ? diagnostic.code().name() : detail);
            }
        }
        return List.copyOf(lines);
    }

    static List<EngineeringReportModel.SignalRow> evidenceSignals(
            EngineeringInvestigation investigation,
            List<SignalCatalogMetadata> catalog,
            Map<String, String> fileNameById,
            AnalysisScopeRecord scope,
            SignalSeriesStore samples
    ) {
        String measurement = ReportMapScope.measurementLabel(investigation, scope, fileNameById);
        LinkedHashMap<String, SignalCatalogMetadata> byKey = new LinkedHashMap<>();
        for (SignalCatalogMetadata row : catalog) {
            byKey.put(row.signalKey(), row);
            if (!byKey.containsKey(row.signalName())) {
                byKey.put(row.signalName(), row);
            }
        }
        List<String> blobs = new ArrayList<>(investigation.signalKeyBlobs());
        if (blobs.isEmpty()) {
            for (GraphInvestigationSnapshot.Trace trace : GraphInvestigationCodec.decodeTraces(
                    investigation.graphTracesBlob())) {
                if (trace != null && !trace.hidden() && trace.key() != null) {
                    blobs.add(GraphInvestigationCodec.encodeKey(trace.key()));
                }
            }
        }
        List<EngineeringReportModel.SignalRow> out = new ArrayList<>();
        for (String blob : blobs) {
            SignalCatalogKey decoded = GraphInvestigationCodec.decodeKey(blob);
            SignalCatalogMetadata row = scopedCatalogRow(catalog, decoded, scope);
            if (row == null) {
                row = byKey.get(blob);
            }
            if (row == null && decoded != null
                    && com.stellar.platform.processing.catalog.ProjectMergedSignalStore
                    .restrictsToOneMeasurement(decoded.sourceFile())) {
                for (SignalCatalogMetadata candidate : catalog) {
                    if (decoded.sourceFile().equalsIgnoreCase(candidate.sourceId())
                            && decoded.signalName().equals(candidate.signalName())) {
                        row = candidate;
                        break;
                    }
                }
            }
            if (row == null && decoded != null) {
                row = byKey.get(decoded.signalName());
            }
            if (row == null && decoded != null) {
                out.add(new EngineeringReportModel.SignalRow(
                        decoded.signalName(), liveCatalogUnit(samples, decoded), measurement, "—", "—", "—", "—", "—"));
            } else if (row == null) {
                out.add(new EngineeringReportModel.SignalRow(
                        PathSanitizer.displayName(blob), "", measurement, "—", "—", "—", "—", "—"));
            } else {
                String min = ReportSignalFormat.extremum(row.minValue(), row.sampleCount());
                String max = ReportSignalFormat.extremum(row.maxValue(), row.sampleCount());
                String label = categoricalReportLabel(samples, decoded, row.signalName());
                String source = measurement;
                if (decoded != null
                        && com.stellar.platform.processing.catalog.ProjectMergedSignalStore
                        .restrictsToOneMeasurement(decoded.sourceFile())) {
                    source = fileNameById.getOrDefault(decoded.sourceFile(), decoded.sourceFile());
                }
                String identity = source;
                if (row.messageName() != null && !row.messageName().isBlank()) {
                    identity = identity + " · " + row.messageName();
                }
                if (row.canId() > 0) {
                    identity = identity + " · 0x" + Long.toHexString(row.canId());
                }
                identity = identity + " · " + row.sampleCount() + " samples";
                String unit = row.unit();
                if (unit == null || unit.isBlank()) {
                    unit = liveCatalogUnit(samples, decoded);
                }
                out.add(new EngineeringReportModel.SignalRow(
                        row.signalName(), unit, identity, min, max, "—", "—", "—", label));
            }
        }
        return out;
    }

    private static String liveCatalogUnit(SignalSeriesStore samples, SignalCatalogKey decoded) {
        if (samples == null || decoded == null) {
            return "";
        }
        try {
            SignalCatalog catalog = samples.snapshot();
            if (catalog == null || catalog.entries() == null) {
                return "";
            }
            for (SignalCatalogEntry entry : catalog.entries()) {
                if (entry != null && decoded.equals(entry.key()) && entry.unit() != null && !entry.unit().isBlank()) {
                    return entry.unit();
                }
            }
        } catch (RuntimeException ex) {
            return "";
        }
        return "";
    }

    private static String categoricalReportLabel(
            SignalSeriesStore samples, SignalCatalogKey decoded, String signalName
    ) {
        if (samples == null) {
            return "";
        }
        SignalCatalogKey key = decoded;
        if (decoded != null && com.stellar.platform.processing.catalog.ProjectMergedSignalStore
                .restrictsToOneMeasurement(decoded.sourceFile())) {
            CategoricalSeriesSummary.Result scoped = CategoricalSeriesSummary.scan(samples, decoded);
            return scoped == null ? "" : scoped.reportLabel();
        }
        try {
            SignalCatalog catalog = samples.snapshot();
            if (catalog != null && catalog.entries() != null) {
                SignalCatalogKey named = null;
                for (SignalCatalogEntry entry : catalog.entries()) {
                    if (decoded != null && entry.key().equals(decoded)) {
                        key = entry.key();
                        named = null;
                        break;
                    }
                    if (signalName != null && signalName.equals(entry.signalName())) {
                        named = entry.key();
                    }
                }
                if (named != null) {
                    key = named;
                }
            }
        } catch (RuntimeException ex) {
            key = decoded;
        }
        CategoricalSeriesSummary.Result result = CategoricalSeriesSummary.scan(samples, key);
        return result == null ? "" : result.reportLabel();
    }

    /** Stats for one persisted measurement come from that source. Project scope does not. */
    private static SignalCatalogMetadata scopedCatalogRow(
            List<SignalCatalogMetadata> catalog,
            SignalCatalogKey decoded,
            AnalysisScopeRecord scope
    ) {
        if (decoded == null || scope == null || scope.allMeasurements() || scope.sourceId().isBlank()) {
            return null;
        }
        for (SignalCatalogMetadata candidate : catalog) {
            if (scope.sourceId().equalsIgnoreCase(candidate.sourceId())
                    && decoded.signalName().equals(candidate.signalName())) {
                return candidate;
            }
        }
        return null;
    }

    private static EngineeringReportModel.ResultBlock analysisBlock(
            ProjectMetadataStore metadata, EngineeringEvidenceItem item
    ) {
        AnalysisRunMetadata run = metadata.findAnalysisRun(item.referenceId()).orElse(null);
        String body = run == null ? item.summary() : run.message();
        String repro = run == null
                ? ""
                : ("Analysis version " + run.analysisVersion()
                + " · alignment " + run.alignmentPolicy()
                + " · range " + run.rangeSource());
        return new EngineeringReportModel.ResultBlock(
                item.caption().isBlank() ? item.kind().name() : item.caption(),
                body, item.stale(), item.staleReason(), repro);
    }

    private static EngineeringReportModel.ResultBlock diagnosticBlock(
            ProjectMetadataStore metadata, String projectId, EngineeringEvidenceItem item
    ) {
        String body = item.summary();
        String repro = "";
        Optional<DiagnosticFindingRow> found = lookupFinding(metadata, projectId, item.referenceId());
        if (found.isPresent()) {
            DiagnosticFindingRow finding = found.get();
            body = finding.severity() + "  " + finding.ruleId() + "  " + finding.summary()
                    + "\n" + finding.details()
                    + "\nEvidence: " + finding.evidenceBlob();
            DiagnosticRunMetadata run = metadata.findDiagnosticRun(finding.runId()).orElse(null);
            repro = "Rule " + finding.ruleId() + " version " + finding.ruleVersion()
                    + (run == null ? "" : " · engine " + run.engineVersion()
                    + " · pack " + run.packId() + " " + run.packVersion());
        }
        return new EngineeringReportModel.ResultBlock(
                item.caption().isBlank() ? "Diagnostic finding" : item.caption(),
                body, item.stale(), item.staleReason(), repro);
    }

    private static EngineeringReportModel.ResultBlock spectralBlock(
            ProjectMetadataStore metadata, EngineeringEvidenceItem item
    ) {
        AnalysisRunMetadata run = metadata.findAnalysisRun(item.referenceId()).orElse(null);
        String body = run == null ? item.summary() : run.message();
        String repro = run == null
                ? item.summary()
                : ("Type " + run.analysisType()
                + " · version " + run.analysisVersion()
                + " · parameters " + run.parametersBlob());
        return new EngineeringReportModel.ResultBlock(
                item.caption().isBlank() ? "Frequency analysis" : item.caption(),
                body, item.stale(), item.staleReason(), repro);
    }

    private static Optional<DiagnosticFindingRow> lookupFinding(
            ProjectMetadataStore metadata, String projectId, String findingId
    ) {
        if (metadata == null || findingId == null) {
            return Optional.empty();
        }
        for (DiagnosticRunMetadata run : metadata.listDiagnosticRuns(projectId)) {
            for (DiagnosticFindingRow row : metadata.listDiagnosticFindings(run.runId())) {
                if (findingId.equals(row.findingId())) {
                    return Optional.of(row);
                }
            }
        }
        return Optional.empty();
    }

    private static EngineeringReportModel embedGraphImages(
            EngineeringInvestigation investigation, EngineeringReportModel model
    ) throws IOException {
        Path artifacts = AnalysisStorageRoots.projectArtifactsRoot(investigation.projectId());
        List<Path> pngs = snapshotFiles(investigation, artifacts);
        if (pngs.isEmpty()) {
            return model;
        }
        List<EngineeringReportModel.Figure> embedded = new ArrayList<>();
        List<EngineeringReportModel.Figure> current = model.figures();
        int pngIndex = 0;
        for (EngineeringReportModel.Figure figure : current) {
            String src = figure.assetRelativePath();
            if (src != null && !src.isBlank() && pngIndex < pngs.size()) {
                String uri = GraphImageEmbed.pngDataUri(Files.readAllBytes(pngs.get(pngIndex++)));
                if (!uri.isBlank()) {
                    src = uri;
                }
            }
            embedded.add(new EngineeringReportModel.Figure(
                    figure.number(), figure.caption(), src, figure.timeRange()));
        }
        return model.withFigures(embedded);
    }

    private static List<EngineeringReportModel.Figure> enrichFigures(
            EngineeringInvestigation investigation, List<EngineeringReportModel.Figure> figures
    ) {
        if (figures == null || figures.isEmpty()) {
            return figures;
        }
        String signals = visibleSignalNames(investigation);
        List<EngineeringReportModel.Figure> out = new ArrayList<>();
        for (EngineeringReportModel.Figure figure : figures) {
            String range = figure.timeRange();
            if ((range == null || range.isBlank()) && !signals.isBlank()) {
                range = signals;
            }
            out.add(new EngineeringReportModel.Figure(
                    figure.number(), figure.caption(), figure.assetRelativePath(), range));
        }
        return out;
    }

    private static String visibleSignalNames(EngineeringInvestigation investigation) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (GraphInvestigationSnapshot.Trace trace : GraphInvestigationCodec.decodeTraces(
                investigation.graphTracesBlob())) {
            if (trace != null && !trace.hidden() && trace.key() != null && !trace.key().signalName().isBlank()) {
                names.add(trace.key().signalName());
            }
        }
        return String.join(", ", names);
    }

    private static Map<String, JobExecutionStatus> latestJobBySource(List<ProcessingJobMetadata> jobs) {
        Map<String, JobExecutionStatus> out = new LinkedHashMap<>();
        if (jobs == null) {
            return out;
        }
        for (ProcessingJobMetadata job : jobs) {
            JobExecutionStatus current = out.get(job.sourceId());
            out.put(job.sourceId(), SourceStatusPresenter.prefer(current, job.status()));
        }
        return out;
    }

    private static CanSourceInventory inventoryFor(SavedProjectSnapshot snap, ProjectFileMetadata file) {
        if (snap.inventories() == null) {
            return null;
        }
        CanSourceInventory direct = snap.inventories().get(file.fileId());
        if (direct != null) {
            return direct;
        }
        String name = PathSanitizer.displayName(file.path());
        for (CanSourceInventory inventory : snap.inventories().values()) {
            if (inventory != null && name.equals(inventory.sourceFileName())) {
                return inventory;
            }
        }
        return null;
    }

    private static String formatLabel(String kind) {
        if (kind == null || kind.isBlank()) {
            return "";
        }
        int split = kind.lastIndexOf('_');
        return split >= 0 && split < kind.length() - 1 ? kind.substring(split + 1) : kind;
    }

    private static String definitionStatus(ProjectFileMetadata file) {
        if (file.selectedDefinition()) {
            return "Selected";
        }
        return file.validationStatus() == null || file.validationStatus().isBlank()
                ? "Attached" : file.validationStatus();
    }

    private static String sizeLabel(long bytes) {
        if (bytes <= 0) {
            return "—";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static String measurementOutcome(
            ProjectFileMetadata file,
            Map<String, JobExecutionStatus> jobBySource,
            List<ProcessingArtifactMetadata> artifacts
    ) {
        JobExecutionStatus status = jobBySource == null ? null : jobBySource.get(file.fileId());
        if (status != null) {
            return jobLabel(status);
        }
        ProcessingArtifactMetadata artifact = newestArtifact(artifacts, file.fileId());
        if (artifact != null) {
            String execution = artifact.jobExecutionStatus() == null
                    ? "" : artifact.jobExecutionStatus().toUpperCase(Locale.ROOT);
            if (execution.contains("CANCEL")) {
                return ProcessingStatusLabels.CANCELLED;
            }
            if (execution.contains("FAIL") || execution.contains("BLOCK") || execution.contains("ERROR")) {
                return ProcessingStatusLabels.ERROR;
            }
            if (execution.contains("WARN") || execution.contains("SUCCESS") || execution.contains("COMPLETE")) {
                return ProcessingStatusLabels.COMPLETED;
            }
            boolean valid = ProcessingArtifactMetadata.STATUS_VALID.equalsIgnoreCase(artifact.status());
            if (valid && (execution.isBlank() || execution.contains("SUCCESS"))) {
                return "Processed";
            }
        }
        if (file.validationStatus() == null || file.validationStatus().isBlank()) {
            return "—";
        }
        return file.validationStatus();
    }

    static JobCounts countMeasurementOutcomes(SavedProjectSnapshot snap, List<ProcessingJobMetadata> jobs) {
        if (snap == null) {
            return new JobCounts(0, 0, 0);
        }
        Map<String, JobExecutionStatus> bySource = latestJobBySource(jobs);
        List<ProcessingArtifactMetadata> artifacts = snap.processingArtifacts() == null
                ? List.of() : snap.processingArtifacts();
        int successful = 0;
        int failed = 0;
        int warned = 0;
        for (ProjectFileMetadata file : snap.files()) {
            if (file.kind() == null || !file.kind().contains("MEASUREMENT")) {
                continue;
            }
            String outcome = measurementOutcome(file, bySource, artifacts);
            if (ProcessingStatusLabels.COMPLETED.equals(outcome)
                    || "Processed".equals(outcome)
                    || "Processed with warnings".equals(outcome)) {
                successful++;
                if ("Processed with warnings".equals(outcome)) {
                    warned++;
                }
            } else if (ProcessingStatusLabels.ERROR.equals(outcome) || "Failed".equals(outcome)) {
                failed++;
            }
        }
        return new JobCounts(successful, failed, warned);
    }

    private static ProcessingArtifactMetadata newestArtifact(
            List<ProcessingArtifactMetadata> artifacts, String sourceId
    ) {
        ProcessingArtifactMetadata newest = null;
        if (artifacts == null || sourceId == null) {
            return null;
        }
        for (ProcessingArtifactMetadata artifact : artifacts) {
            if (artifact == null || !sourceId.equals(artifact.sourceId())) {
                continue;
            }
            if (newest == null || newer(artifact, newest)) {
                newest = artifact;
            }
        }
        return newest;
    }

    private static boolean newer(ProcessingArtifactMetadata candidate, ProcessingArtifactMetadata current) {
        if (candidate.createdAt() == null) {
            return false;
        }
        if (current.createdAt() == null) {
            return true;
        }
        return candidate.createdAt().isAfter(current.createdAt());
    }

    private static String jobLabel(JobExecutionStatus status) {
        if (status == null) {
            return "";
        }
        return ProcessingStatusLabels.forJob(status);
    }

    private record JobCounts(int successful, int failed, int warned) {
    }

    private static String processingLabel(SavedProjectSnapshot snap, JobCounts counts, int warningCount) {
        if (counts.failed > 0) {
            return ProcessingStatusLabels.ERROR;
        }
        if (counts.successful > 0 || counts.warned > 0 || warningCount > 0) {
            return ProcessingStatusLabels.COMPLETED;
        }
        return processingStatus(snap);
    }

    private static String processingFinished(SavedProjectSnapshot snap) {
        if (snap == null || snap.processingRuns().isEmpty() || snap.processingRuns().getFirst().finishedAt() == null) {
            return "";
        }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(snap.processingRuns().getFirst().finishedAt());
    }

    private static String processingStatus(SavedProjectSnapshot snap) {
        if (snap == null || snap.processingRuns().isEmpty() || snap.processingRuns().getFirst().status() == null) {
            return "";
        }
        return ProcessingStatusLabels.forStored(snap.processingRuns().getFirst().status());
    }

    private static EngineeringEvidenceItem routeEvidence(EngineeringInvestigation investigation) {
        if (investigation == null) {
            return null;
        }
        for (EngineeringEvidenceItem item : investigation.evidence()) {
            if (item.kind() == EvidenceKind.ROUTE_MAP) {
                return item;
            }
        }
        return null;
    }

    private EngineeringReportModel.RouteMapSection routeSectionForReport(
            EngineeringInvestigation investigation,
            Path artifacts,
            Path assetsDir,
            RouteMapReportAsset automaticMap
    ) throws IOException {
        EngineeringReportModel.RouteMapSection automatic = placeAutomaticMap(automaticMap, assetsDir, null);
        if (automatic != null) {
            return automatic;
        }
        String routeRef = copyRouteMapAsset(investigation, artifacts, assetsDir);
        return routeMapSection(investigation, artifacts, routeRef);
    }

    /**
     * Writes the generated PNG beside the HTML and, when Analysis Storage is configured,
     * under the investigation report directory. A failed render keeps the metrics and
     * writes no image path.
     */
    private EngineeringReportModel.RouteMapSection placeAutomaticMap(
            RouteMapReportAsset automatic, Path assetsDir, Path mirrorAssets
    ) throws IOException {
        if (automatic == null || automatic.context() == null || !automatic.context().hasRoute()) {
            return null;
        }
        if (!automatic.hasImage()) {
            return automatic.section("");
        }
        try {
            Files.createDirectories(assetsDir);
            Files.write(assetsDir.resolve(InvestigationStoragePaths.ROUTE_MAP_ASSET), automatic.png());
            ReportGeneratedAssetCleanup.removeLegacyDetailMaps(assetsDir);
        } catch (IOException ex) {
            LOG.warning("Route map image was not stored: " + ex.getMessage());
            return automatic.section("");
        }
        if (mirrorAssets != null
                && !mirrorAssets.toAbsolutePath().normalize().equals(assetsDir.toAbsolutePath().normalize())) {
            try {
                Files.createDirectories(mirrorAssets);
                Files.write(mirrorAssets.resolve(InvestigationStoragePaths.ROUTE_MAP_ASSET), automatic.png());
                ReportGeneratedAssetCleanup.removeLegacyDetailMaps(mirrorAssets);
            } catch (IOException ex) {
                LOG.warning("Route map image was not mirrored into Analysis Storage: " + ex.getMessage());
            }
        }
        return automatic.section(RouteMapReportRenderer.relativeAsset());
    }

    private static Path mirrorRouteAsset(EngineeringInvestigation investigation, String reportId) {
        if (investigation == null) {
            return null;
        }
        try {
            Path artifacts = AnalysisStorageRoots.projectArtifactsRoot(investigation.projectId());
            String id = reportId == null || reportId.isBlank()
                    ? "report-map"
                    : reportId;
            return InvestigationStoragePaths.reportDir(artifacts, investigation.investigationId(), id)
                    .resolve("assets");
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static EngineeringReportModel.RouteMapSection routeMapSection(
            EngineeringInvestigation investigation, Path artifacts, String imageReference
    ) {
        EngineeringEvidenceItem item = routeEvidence(investigation);
        if (item == null) {
            return null;
        }
        RouteMapSnapshot snapshot = readRouteSnapshot(artifacts, investigation.investigationId(), item.referenceId());
        boolean image = imageReference != null && !imageReference.isBlank();
        if (!image) {
            return EngineeringReportModel.RouteMapSection.unavailable(snapshot);
        }
        return EngineeringReportModel.RouteMapSection.image(snapshot, imageReference);
    }

    private static EngineeringReportModel.RouteMapSection embeddedRouteMap(EngineeringInvestigation investigation) {
        EngineeringEvidenceItem item = routeEvidence(investigation);
        if (item == null) {
            return null;
        }
        Path artifacts = AnalysisStorageRoots.projectArtifactsRoot(investigation.projectId());
        RouteMapSnapshot snapshot = readRouteSnapshot(artifacts, investigation.investigationId(), item.referenceId());
        Path png = InvestigationStoragePaths.snapshot(artifacts, investigation.investigationId(), item.referenceId());
        if (!Files.isRegularFile(png)) {
            return EngineeringReportModel.RouteMapSection.unavailable(snapshot);
        }
        try {
            String uri = GraphImageEmbed.pngDataUri(Files.readAllBytes(png));
            if (uri.isBlank()) {
                return EngineeringReportModel.RouteMapSection.unavailable(snapshot);
            }
            return EngineeringReportModel.RouteMapSection.image(snapshot, uri);
        } catch (IOException ex) {
            java.util.logging.Logger.getLogger(DesktopInvestigationService.class.getName())
                    .warning("Route map image was not embedded: " + ex.getMessage());
            return EngineeringReportModel.RouteMapSection.unavailable(snapshot);
        }
    }

    private static String copyRouteMapAsset(
            EngineeringInvestigation investigation, Path artifacts, Path assetsDir
    ) {
        EngineeringEvidenceItem item = routeEvidence(investigation);
        if (item == null) {
            return "";
        }
        Path png = InvestigationStoragePaths.snapshot(artifacts, investigation.investigationId(), item.referenceId());
        if (!Files.isRegularFile(png)) {
            return "";
        }
        try {
            Files.createDirectories(assetsDir);
            Path dest = assetsDir.resolve(InvestigationStoragePaths.ROUTE_MAP_ASSET);
            Files.copy(png, dest, StandardCopyOption.REPLACE_EXISTING);
            return "assets/" + InvestigationStoragePaths.ROUTE_MAP_ASSET;
        } catch (IOException ex) {
            java.util.logging.Logger.getLogger(DesktopInvestigationService.class.getName())
                    .warning("Route map asset was not copied into the report: " + ex.getMessage());
            return "";
        }
    }

    private static RouteMapSnapshot readRouteSnapshot(Path artifacts, String investigationId, String snapshotId) {
        Path json = InvestigationStoragePaths.routeMapMetadata(artifacts, investigationId, snapshotId);
        if (!Files.isRegularFile(json)) {
            return null;
        }
        try {
            return RouteMapSnapshot.parse(Files.readString(json));
        } catch (IOException ex) {
            java.util.logging.Logger.getLogger(DesktopInvestigationService.class.getName())
                    .warning("Route map summary was not read: " + ex.getMessage());
            return null;
        }
    }

    private static void deleteSiblingRouteMap(InvestigationReportRow report) {
        if (report == null || report.destination() == null || report.destination().isBlank()) {
            return;
        }
        if (!AnalysisStorageRoots.isConfigured()) {
            return;
        }
        Path html = Path.of(report.destination());
        Path parent = html.getParent();
        if (parent == null) {
            return;
        }
        Path asset = parent.resolve("assets").resolve(InvestigationStoragePaths.ROUTE_MAP_ASSET);
        Path root = AnalysisStorageRoots.projectArtifactsRoot(report.projectId()).toAbsolutePath().normalize();
        Path normalized = asset.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || !Files.isRegularFile(normalized)) {
            return;
        }
        try {
            Files.deleteIfExists(normalized);
        } catch (IOException ex) {
            java.util.logging.Logger.getLogger(DesktopInvestigationService.class.getName())
                    .warning("Route map report asset was not deleted: " + ex.getMessage());
        }
    }

    private static List<Path> snapshotFiles(EngineeringInvestigation investigation, Path artifacts) {
        List<Path> out = new ArrayList<>();
        for (EngineeringEvidenceItem item : investigation.evidence()) {
            if (item.kind() != EvidenceKind.GRAPH_SNAPSHOT) {
                continue;
            }
            Path png = InvestigationStoragePaths.snapshot(artifacts, investigation.investigationId(), item.referenceId());
            if (Files.isRegularFile(png)) {
                out.add(png);
            }
        }
        return out;
    }

    private static List<Path> copySnapshotAssets(
            EngineeringInvestigation investigation, Path artifacts, Path assetsDir, CancellationToken token
    ) throws IOException {
        List<Path> copied = new ArrayList<>();
        int i = 1;
        for (Path png : snapshotFiles(investigation, artifacts)) {
            throwIfCancelled(token);
            Path dest = assetsDir.resolve(String.format(Locale.ROOT, "graph-%03d.png", i++));
            Files.copy(png, dest, StandardCopyOption.REPLACE_EXISTING);
            copied.add(dest);
        }
        return copied;
    }

    private static List<EngineeringReportModel.Figure> snapshotFigures(
            EngineeringInvestigation investigation, List<Path> copied
    ) {
        List<EngineeringReportModel.Figure> figures = new ArrayList<>();
        int n = 1;
        int fileIndex = 0;
        Path artifacts = AnalysisStorageRoots.projectArtifactsRoot(investigation.projectId());
        for (EngineeringEvidenceItem item : investigation.evidence()) {
            if (item.kind() != EvidenceKind.GRAPH_SNAPSHOT) {
                continue;
            }
            Path png = InvestigationStoragePaths.snapshot(
                    artifacts, investigation.investigationId(), item.referenceId());
            String rel = "";
            if (Files.isRegularFile(png) && fileIndex < copied.size()) {
                rel = "assets/" + copied.get(fileIndex).getFileName();
                fileIndex++;
            }
            String caption = item.caption().isBlank() ? item.summary() : item.caption();
            if (item.stale()) {
                caption = "STALE — " + caption
                        + (item.staleReason().isBlank() ? "" : " — " + item.staleReason());
            }
            if (rel.isBlank()) {
                caption = caption + " Snapshot file is missing. It was not regenerated.";
            }
            figures.add(new EngineeringReportModel.Figure(
                    n, caption, rel, readSnapshotRange(png, item.referenceId())));
            n++;
        }
        return figures;
    }

    private static void rewriteFigurePaths(EngineeringReportModel model, List<Path> snapshots) {
        // Figures are rebuilt by buildReportModel; this keeps the staging copy side-effect used.
        if (model != null && snapshots != null) {
            snapshots.size();
        }
    }

    private static void cleanupManagedReportDirectory(InvestigationReportRow report) {
        if (report.destination() == null || report.destination().isBlank()) {
            return;
        }
        Path dest = Path.of(report.destination());
        if (dest.getFileName() == null || !"report.html".equalsIgnoreCase(dest.getFileName().toString())) {
            return;
        }
        Path dir = dest.getParent();
        if (dir == null) {
            return;
        }
        Path expected = InvestigationStoragePaths.reportDir(
                AnalysisStorageRoots.projectArtifactsRoot(report.projectId()),
                report.investigationId(),
                report.reportId());
        if (dir.toAbsolutePath().normalize().toString()
                .equalsIgnoreCase(expected.toAbsolutePath().normalize().toString())) {
            InvestigationPackageExporter.deleteTreeQuietly(dir);
        }
    }

    private static void deleteReportArtifactSafely(Path artifacts, String investigationId, InvestigationReportRow report) {
        if (report == null || report.destination().isBlank()) {
            return;
        }
        Path dest = Path.of(report.destination());
        Path root = InvestigationStoragePaths.root(artifacts, investigationId).toAbsolutePath().normalize();
        Path normalized = dest.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            return;
        }
        Path dir = Files.isDirectory(normalized) ? normalized : normalized.getParent();
        if (dir != null && dir.startsWith(root)) {
            InvestigationPackageExporter.deleteTreeQuietly(dir);
        }
    }

    private static void throwIfCancelled(CancellationToken token) {
        if (token != null && token.isCancellationRequested()) {
            throw new AnalysisCancelledException();
        }
    }

    private static void checkpoint(String stage, CancellationToken token) {
        throwIfCancelled(token);
        java.util.function.Consumer<String> hook = reportCheckpoint;
        if (hook != null) {
            hook.accept(stage);
        }
        throwIfCancelled(token);
    }

    private static List<Path> writeSignalImages(List<SignalReportImage> images, Path assetsDir) throws IOException {
        List<Path> written = new ArrayList<>();
        if (images == null || images.isEmpty()) {
            return written;
        }
        Files.createDirectories(assetsDir);
        for (SignalReportImage image : images) {
            if (image == null || !image.hasImage()) {
                continue;
            }
            Path dest = assetsDir.resolve(image.fileName());
            Files.write(dest, image.png());
            written.add(dest);
        }
        return written;
    }

    private static Set<String> assetHashes(
            Path assetsDir, List<SignalReportImage> images, RouteMapReportAsset map
    ) {
        Set<String> hashes = new HashSet<>();
        if (images != null) {
            for (SignalReportImage image : images) {
                if (image != null && image.hasImage()) {
                    String hash = ReportEvidenceAssets.sha256(image.png());
                    if (hash != null) {
                        hashes.add(hash);
                    }
                }
            }
        }
        if (map != null && map.hasImage()) {
            String hash = ReportEvidenceAssets.sha256(map.png());
            if (hash != null) {
                hashes.add(hash);
            }
        }
        if (assetsDir != null && Files.isDirectory(assetsDir)) {
            try (var walk = Files.list(assetsDir)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    String hash = ReportEvidenceAssets.sha256(file);
                    if (hash != null) {
                        hashes.add(hash);
                    }
                }
            } catch (IOException ignored) {
                // A missing asset directory means there is nothing to de-duplicate.
            }
        }
        return hashes;
    }

    private EngineeringInvestigation hydrate(ProjectMetadataStore metadata, EngineeringInvestigationRow row) {
        List<EngineeringEvidenceItem> evidence = new ArrayList<>();
        for (InvestigationEvidenceRow e : metadata.listInvestigationEvidence(row.investigationId())) {
            evidence.add(new EngineeringEvidenceItem(
                    e.evidenceId(), e.investigationId(), parseKind(e.kind()), e.referenceId(), e.sortOrder(),
                    e.stale(), e.staleReason(), e.sourceFingerprint(), e.artifactGeneration(),
                    e.caption(), e.summary(), e.createdAt()));
        }
        evidence.sort(Comparator.comparingInt(EngineeringEvidenceItem::sortOrder));
        List<EngineeringObservation> observations = new ArrayList<>();
        for (InvestigationObservationRow o : metadata.listInvestigationObservations(row.investigationId())) {
            observations.add(new EngineeringObservation(
                    o.observationId(), o.investigationId(), o.startNanos(), o.endNanos(),
                    o.title(), o.note(), o.createdAt(), o.updatedAt()));
        }
        return EngineeringInvestigation.builder(row.projectId())
                .investigationId(row.investigationId())
                .name(row.name())
                .description(row.description())
                .status(parseStatus(row.status()))
                .createdAt(row.createdAt())
                .updatedAt(row.updatedAt())
                .range(row.rangeStartNanos(), row.rangeEndNanos())
                .graphState(row.graphTracesBlob(), row.layoutMode(), row.viewportStartNanos(), row.viewportEndNanos(),
                        row.cursorANanos(), row.cursorBNanos(), row.focusKeyBlob())
                .conclusion(row.conclusion())
                .reportConfig(InvestigationReportConfig.decode(row.reportConfig()))
                .signalKeyBlobs(metadata.listInvestigationSignals(row.investigationId()))
                .evidence(evidence)
                .observations(observations)
                .build();
    }

    private static EngineeringInvestigationRow toRow(EngineeringInvestigation inv) {
        return new EngineeringInvestigationRow(
                inv.investigationId(), inv.projectId(), inv.name(), inv.description(), inv.status().name(),
                inv.rangeStartNanos(), inv.rangeEndNanos(), inv.graphTracesBlob(), inv.layoutMode(),
                inv.viewportStartNanos(), inv.viewportEndNanos(), inv.cursorANanos(), inv.cursorBNanos(),
                inv.focusKeyBlob(), inv.conclusion(), inv.reportConfig().encode(),
                inv.createdAt(), inv.updatedAt());
    }

    private static List<InvestigationEvidenceRow> toEvidenceRows(EngineeringInvestigation inv) {
        List<InvestigationEvidenceRow> out = new ArrayList<>();
        for (EngineeringEvidenceItem e : inv.evidence()) {
            out.add(new InvestigationEvidenceRow(
                    e.evidenceId(), inv.investigationId(), e.kind().name(), e.referenceId(), e.sortOrder(),
                    e.stale(), e.staleReason(), e.sourceFingerprint(), e.artifactGeneration(),
                    e.caption(), e.summary(), e.createdAt()));
        }
        return out;
    }

    private static List<InvestigationObservationRow> toObservationRows(EngineeringInvestigation inv) {
        List<InvestigationObservationRow> out = new ArrayList<>();
        for (EngineeringObservation o : inv.observations()) {
            out.add(new InvestigationObservationRow(
                    o.observationId(), inv.investigationId(), o.startNanos(), o.endNanos(),
                    o.title(), o.note(), o.createdAt(), o.updatedAt()));
        }
        return out;
    }

    private static EvidenceKind parseKind(String kind) {
        try {
            return EvidenceKind.valueOf(kind);
        } catch (RuntimeException ex) {
            return EvidenceKind.ENGINEERING_NOTE;
        }
    }

    private static InvestigationStatus parseStatus(String status) {
        try {
            return InvestigationStatus.valueOf(status);
        } catch (RuntimeException ex) {
            return InvestigationStatus.DRAFT;
        }
    }

    private static EngineeringReportModel withLinkedSnapshots(
            EngineeringReportModel model,
            EngineeringInvestigation investigation,
            ProjectMetadataStore metadata
    ) {
        return EventSnapshotReport.place(model, investigation, metadata);
    }

    public InvestigationHealth health(EngineeringInvestigation inv) {
        return inv == null ? InvestigationHealth.CURRENT : inv.health();
    }

    private static List<EngineeringReportModel.EvaluationRow> evaluationRows(
            ProjectMetadataStore metadata, String projectId
    ) {
        List<EngineeringReportModel.EvaluationRow> rows = new ArrayList<>();
        if (metadata == null || projectId == null || projectId.isBlank()) {
            return rows;
        }
        for (var record : metadata.listEvaluationResults(projectId)) {
            if (!record.included()) {
                continue;
            }
            var result = com.stellar.platform.analysis.investigation.eval.EvaluationDocuments.decodeResult(record.document());
            if (result != null) {
                rows.add(com.stellar.platform.analysis.investigation.report.EvaluationReportRows.row(result));
            }
        }
        return rows;
    }

    private static List<EngineeringReportModel.EventRow> eventRows(
            ProjectMetadataStore metadata,
            EngineeringInvestigation investigation,
            Map<String, String> fileNameById
    ) {
        List<EngineeringReportModel.EventRow> rows = new ArrayList<>();
        if (investigation == null) {
            return rows;
        }
        List<com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata> bookmarks = metadata == null
                ? List.of() : metadata.listBookmarks(investigation.projectId());
        for (EngineeringEvidenceItem item : investigation.evidence()) {
            if (item.kind() != EvidenceKind.ANNOTATION) {
                continue;
            }
            com.stellar.platform.storage.metadata.GraphAnnotationMetadata annotation = null;
            if (metadata != null) {
                for (var candidate : metadata.listGraphAnnotations(investigation.projectId())) {
                    if (candidate.annotationId().equals(item.referenceId())) {
                        annotation = candidate;
                        break;
                    }
                }
            }
            if (annotation == null) {
                com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata bookmark = null;
                for (var candidate : bookmarks) {
                    if (candidate.bookmarkId().equals(item.referenceId())) {
                        bookmark = candidate;
                        break;
                    }
                }
                if (bookmark != null) {
                    var defined = EngineerEventDocuments.decode(bookmark);
                    if (defined != null) {
                        rows.add(com.stellar.platform.analysis.investigation.report.EngineerEventReportRows.row(defined));
                    } else {
                        rows.add(legacyBookmarkRow(bookmark, item, fileNameById));
                    }
                    continue;
                }
                rows.add(new EngineeringReportModel.EventRow(
                        item.caption().isBlank() ? item.referenceId() : item.caption(),
                        "Missing", "", "", "", "",
                        "The annotation record was not found.",
                        "No graph snapshot is linked.",
                        "Not correlated with a route.",
                        "Evidence " + item.evidenceId() + " points at a missing annotation. Nothing was inferred."));
                continue;
            }
            boolean point = annotation.endTimestampNanos() == null
                    || annotation.annotationType() != com.stellar.platform.storage.metadata.GraphAnnotationType.TIME_RANGE;
            String end = point
                    ? ""
                    : com.stellar.platform.analysis.investigation.EngineeringFormats.seconds(
                            annotation.endTimestampNanos());
            String provenance = "Engineer annotation " + annotation.annotationId()
                    + ". The original record was not modified.";
            if (item.stale()) {
                provenance = provenance + " Evidence is stale"
                        + (item.staleReason().isBlank() ? "." : ": " + item.staleReason());
            }
            rows.add(new EngineeringReportModel.EventRow(
                    annotation.label().isBlank() ? item.caption() : annotation.label(),
                    annotation.annotationType().name(),
                    com.stellar.platform.analysis.investigation.EngineeringFormats.seconds(
                            annotation.startTimestampNanos()),
                    end,
                    "",
                    annotation.signalKey(),
                    annotation.note(),
                    "No graph snapshot is linked.",
                    "Not correlated with a route.",
                    provenance));
        }
        return rows;
    }

    private static EngineeringReportModel.EventRow legacyBookmarkRow(
            com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata bookmark,
            EngineeringEvidenceItem item,
            Map<String, String> fileNameById
    ) {
        String name = com.stellar.platform.desktop.presentation.InvestigationEventCodec.titleOf(bookmark.observation());
        if (name.isBlank()) {
            name = item.caption().isBlank() ? bookmark.bookmarkId() : item.caption();
        }
        String source = bookmark.sourceId() == null ? "" : bookmark.sourceId();
        if (fileNameById != null && !source.isBlank()) {
            source = fileNameById.getOrDefault(source, source);
        }
        return new EngineeringReportModel.EventRow(
                name,
                bookmark.eventType(),
                com.stellar.platform.analysis.investigation.EngineeringFormats.seconds(bookmark.timestampNanos()),
                "",
                source,
                bookmark.signalName(),
                com.stellar.platform.desktop.presentation.InvestigationEventCodec.noteOf(bookmark.observation()),
                "No graph snapshot is linked.",
                "GPS evidence was not attached. No coordinate was invented.",
                "Engineer bookmark " + bookmark.bookmarkId() + ". The original record was not modified.");
    }

    private static String readSnapshotRange(Path png, String snapshotId) {
        if (png == null || snapshotId == null || snapshotId.isBlank()) {
            return "";
        }
        Path json = png.resolveSibling(snapshotId + ".json");
        if (!Files.isRegularFile(json)) {
            return "";
        }
        try {
            String text = Files.readString(json);
            Long start = jsonLong(text, "rangeStartNanos");
            Long end = jsonLong(text, "rangeEndNanos");
            if (start == null || end == null) {
                return "";
            }
            return com.stellar.platform.analysis.investigation.EngineeringFormats.range(start, end);
        } catch (IOException ex) {
            return "";
        }
    }

    private static Long jsonLong(String text, String key) {
        String token = "\"" + key + "\":";
        int at = text.indexOf(token);
        if (at < 0) {
            return null;
        }
        int from = at + token.length();
        while (from < text.length() && Character.isWhitespace(text.charAt(from))) {
            from++;
        }
        if (text.startsWith("null", from)) {
            return null;
        }
        int end = from;
        if (end < text.length() && text.charAt(end) == '-') {
            end++;
        }
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        if (end == from) {
            return null;
        }
        try {
            return Long.parseLong(text.substring(from, end));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public Optional<GraphAnnotationMetadata> findAnnotation(ProjectMetadataStore metadata, String projectId, String id) {
        if (metadata == null) {
            return Optional.empty();
        }
        return metadata.listGraphAnnotations(projectId).stream()
                .filter(a -> a.annotationId().equals(id))
                .findFirst();
    }

    static Optional<DiagnosticFindingRow> findingForTest(ProjectMetadataStore metadata, String projectId, String id) {
        return lookupFinding(metadata, projectId, id);
    }
}
