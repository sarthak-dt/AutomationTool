package com.stellar.platform.desktop.processing;

import com.stellar.platform.api.StellarPlatformClient;
import com.stellar.platform.api.assets.FileAssetUploader;
import com.stellar.platform.api.dto.FileAssetDto;
import com.stellar.platform.api.dto.JobDto;
import com.stellar.platform.api.dto.JobStatusDto;
import com.stellar.platform.api.dto.ProcessJobRequest;
import com.stellar.platform.api.dto.ProcessResultDto;
import com.stellar.platform.api.dto.SignalBucketDto;
import com.stellar.platform.api.dto.SignalCatalogEntryDto;
import com.stellar.platform.api.dto.SignalCatalogPageDto;
import com.stellar.platform.api.dto.SignalCatalogQuery;
import com.stellar.platform.api.dto.SignalSampleDto;
import com.stellar.platform.api.dto.SignalSamplePageDto;
import com.stellar.platform.api.dto.SignalViewportRequestDto;
import com.stellar.platform.api.dto.SignalViewportResultDto;
import com.stellar.platform.api.local.LocalSignalQueryApi;
import com.stellar.platform.api.support.SignalKeyCodec;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.FileKindDetector;
import com.stellar.platform.desktop.project.ProcessingSummary;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.signals.SignalPreviewBuffer;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.processing.ProcessingSelection;
import com.stellar.platform.processing.ProcessingOutcome;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.ProcessingResult;
import com.stellar.platform.processing.ProcessingStage;
import com.stellar.platform.processing.ProcessingStatistics;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphQueryProvider;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.graph.LodGraphSeriesQuery;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * REMOTE processing gateway: submits decode jobs to stellar-server and pages signal samples.
 */
public final class RemoteProcessingGateway implements ProcessingGateway {

    private final StellarPlatformClient client;
    private final Supplier<String> projectIdSupplier;
    private final AtomicReference<String> activeJobId = new AtomicReference<>();

    public RemoteProcessingGateway(StellarPlatformClient client, Supplier<String> projectIdSupplier) {
        this.client = Objects.requireNonNull(client, "client");
        this.projectIdSupplier = Objects.requireNonNull(projectIdSupplier, "projectIdSupplier");
    }

    @Override
    public void cancel() {
        String projectId = projectIdSupplier.get();
        String jobId = activeJobId.get();
        if (projectId != null && jobId != null) {
            client.processing().cancelProcessJob(projectId, jobId);
        }
    }

    @Override
    public LocalProcessingGateway.GatewayResult process(
            WorkspaceFile measurement,
            WorkspaceFile definition,
            int batchSize,
            SignalPreviewBuffer previewBuffer,
            ProcessingProgressListener progressListener,
            Consumer<List<CanonicalSignal>> previewBatchListener,
            ProcessingSelection selection
    ) {
        Objects.requireNonNull(measurement, "measurement");
        Objects.requireNonNull(definition, "definition");
        SignalPreviewBuffer preview = previewBuffer == null
                ? new SignalPreviewBuffer(SignalPreviewBuffer.DEFAULT_CAPACITY)
                : previewBuffer;
        ProcessingProgressListener progress = progressListener == null
                ? ProcessingProgressListener.NOOP
                : progressListener;
        Instant started = Instant.now();
        progress.onStage(ProcessingStage.VALIDATING);

        String projectId = projectIdSupplier.get();
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalStateException("REMOTE processing requires a project id");
        }

        Path measurementPath = measurement.path();
        Path dbcPath = definition.path();
        FileKind kind = measurement.kind() == null ? FileKindDetector.detect(measurementPath) : measurement.kind();
        String format = kind.displayName();

        try {
            progress.onStage(ProcessingStage.DBC_DECODING);
            String sourceId = measurement.id();
            FileAssetDto measurementAsset = FileAssetUploader.ensureMeasurementAsset(
                    client.assets(), projectId, sourceId, measurementPath, false);
            FileAssetDto definitionAsset = FileAssetUploader.ensureDefinitionAsset(
                    client.assets(), projectId, definition.id(), dbcPath, false);
            JobDto job = client.processing().startProcessJob(
                    projectId,
                    new ProcessJobRequest(
                            sourceId,
                            measurementAsset.fileAssetId(),
                            definitionAsset.fileAssetId(),
                            batchSize,
                            50_000)
            );
            activeJobId.set(job.jobId());
            JobDto status = poll(projectId, job.jobId(), progress);
            ProcessResultDto result = client.processing().getProcessResult(projectId, job.jobId())
                    .orElse(null);

            RemoteSignalSeriesStore catalog = loadCatalog(projectId);
            Duration elapsed = Duration.between(started, Instant.now());
            boolean cancelled = status.status() == JobStatusDto.CANCELLED
                    || (result != null && result.cancelled());
            boolean success = status.status() == JobStatusDto.SUCCEEDED
                    && result != null
                    && result.completedSuccessfully();

            ProcessingStatistics stats = new ProcessingStatistics();
            if (result != null) {
                for (long i = 0; i < result.framesProcessed(); i++) {
                    stats.recordValidFrame();
                }
                if (result.signalsProduced() > 0) {
                    stats.recordSignals((int) Math.min(Integer.MAX_VALUE, result.signalsProduced()));
                }
            }

            ProjectProcessingStatus projectStatus = cancelled
                    ? ProjectProcessingStatus.CANCELLED
                    : (success ? ProjectProcessingStatus.COMPLETED : ProjectProcessingStatus.FAILED);
            String message = result == null ? status.message() : result.message();
            if (cancelled) {
                progress.onStage(ProcessingStage.CANCELLED);
            } else if (!success) {
                progress.onStage(ProcessingStage.FAILED);
            } else {
                progress.onStage(ProcessingStage.COMPLETED);
            }

            ProcessingSummary summary = new ProcessingSummary(
                    format,
                    measurementPath,
                    dbcPath,
                    stats,
                    elapsed,
                    projectStatus,
                    List.of(),
                    message,
                    result == null ? "" : String.join("\n", result.diagnostics()),
                    measurement.recoveryCandidate(),
                    measurement.recoveryCandidate() ? "ACTIVE" : "NOT_REQUIRED",
                    measurement.mf4FinalizationLabel(),
                    selection == null || selection.isEmpty() ? "All channels" : selection.summaryLabel(),
                    0L
            );

            ProcessingOutcome outcome = cancelled
                    ? ProcessingOutcome.CANCELLED
                    : (success ? ProcessingOutcome.COMPLETED : ProcessingOutcome.FAILED);
            ProcessingResult processingResult = result == null
                    ? null
                    : new ProcessingResult(stats, List.of(), cancelled, success, outcome);

            return new LocalProcessingGateway.GatewayResult(summary, preview, processingResult, null, catalog);
        } catch (RuntimeException e) {
            progress.onStage(ProcessingStage.FAILED);
            String tech = e.getMessage() == null ? e.toString() : e.getMessage();
            ProcessingSummary summary = new ProcessingSummary(
                    format,
                    measurementPath,
                    dbcPath,
                    new ProcessingStatistics(),
                    Duration.between(started, Instant.now()),
                    ProjectProcessingStatus.FAILED,
                    List.of(),
                    "REMOTE processing failed.",
                    tech,
                    measurement.recoveryCandidate(),
                    measurement.recoveryCandidate() ? "ACTIVE" : "",
                    measurement.mf4FinalizationLabel()
            );
            return new LocalProcessingGateway.GatewayResult(summary, preview, null, tech, null);
        } finally {
            activeJobId.set(null);
        }
    }

    private JobDto poll(String projectId, String jobId, ProcessingProgressListener progress) {
        while (true) {
            JobDto status = client.processing().getProcessJob(projectId, jobId);
            if (status.status() == JobStatusDto.RUNNING || status.status() == JobStatusDto.QUEUED) {
                progress.onStage(ProcessingStage.DBC_DECODING);
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    client.processing().cancelProcessJob(projectId, jobId);
                    return client.processing().getProcessJob(projectId, jobId);
                }
                continue;
            }
            return status;
        }
    }

    private RemoteSignalSeriesStore loadCatalog(String projectId) {
        List<SignalCatalogEntry> entries = new ArrayList<>();
        int offset = 0;
        int pageSize = SignalCatalogQuery.MAX_PAGE_SIZE;
        int total = Integer.MAX_VALUE;
        while (offset < total) {
            SignalCatalogPageDto page = client.signals().catalog(
                    projectId, new SignalCatalogQuery("", offset, pageSize));
            total = page.total();
            for (SignalCatalogEntryDto dto : page.entries()) {
                SignalCatalogKey key = SignalKeyCodec.decode(dto.key());
                entries.add(new SignalCatalogEntry(
                        key,
                        dto.unit(),
                        dto.sampleCount(),
                        dto.firstTimestampNanos(),
                        dto.lastTimestampNanos(),
                        dto.minPhysical(),
                        dto.maxPhysical(),
                        dto.sourceFile().isBlank() ? key.sourceFile() : dto.sourceFile(),
                        dto.sourceDatabase().isBlank() ? key.sourceDatabase() : dto.sourceDatabase()
                ));
            }
            if (page.entries().isEmpty()) {
                break;
            }
            offset += page.entries().size();
            if (offset >= total || page.entries().size() < page.limit()) {
                break;
            }
        }
        return new RemoteSignalSeriesStore(projectId, client, new SignalCatalog(entries, "", ""));
    }

    private static final class RemoteSignalSeriesStore implements SignalSeriesStore, GraphQueryProvider {
        private final String projectId;
        private final StellarPlatformClient client;
        private final SignalCatalog catalog;

        private RemoteSignalSeriesStore(String projectId, StellarPlatformClient client, SignalCatalog catalog) {
            this.projectId = projectId;
            this.client = client;
            this.catalog = catalog;
        }

        @Override
        public SignalCatalog snapshot() {
            return catalog;
        }

        @Override
        public List<SignalSamplePoint> querySamples(SignalCatalogKey key, int offset, int limit) {
            SignalSamplePageDto page = client.signals().samples(
                    projectId,
                    SignalKeyCodec.encode(key),
                    offset,
                    limit
            );
            List<SignalSamplePoint> out = new ArrayList<>(page.samples().size());
            for (SignalSampleDto s : page.samples()) {
                out.add(new SignalSamplePoint(s.timestampNanos(), s.physicalValue(), s.rawValue(), s.textualValue()));
            }
            return out;
        }

        @Override
        public int retainedSampleCount(SignalCatalogKey key) {
            return client.signals().samples(projectId, SignalKeyCodec.encode(key), 0, 1).total();
        }

        @Override
        public long retainedWindowStartIndex(SignalCatalogKey key) {
            return 0L;
        }

        @Override
        public GraphSeriesQuery graphQuery() {
            return new LodGraphSeriesQuery() {
                @Override
                public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
                    return queryRange(key, startNanos, endNanos, LocalSignalQueryApi.MAX_SAMPLE_PAGE);
                }

                @Override
                public List<SignalSamplePoint> queryRange(
                        SignalCatalogKey key, long startNanos, long endNanos, int maxSamples) {
                    SignalSamplePageDto page = client.signals().range(
                            projectId,
                            SignalKeyCodec.encode(key),
                            startNanos,
                            endNanos,
                            maxSamples
                    );
                    List<SignalSamplePoint> out = new ArrayList<>(page.samples().size());
                    for (SignalSampleDto s : page.samples()) {
                        out.add(new SignalSamplePoint(
                                s.timestampNanos(), s.physicalValue(), s.rawValue(), s.textualValue()));
                    }
                    return out;
                }

                @Override
                public List<GraphBucket> queryBuckets(
                        SignalCatalogKey key, long startNanos, long endNanos, int targetBuckets) {
                    SignalViewportResultDto viewport = client.signals().viewport(
                            projectId,
                            new SignalViewportRequestDto(
                                    List.of(SignalKeyCodec.encode(key)),
                                    startNanos,
                                    endNanos,
                                    Math.max(1, targetBuckets / 2)
                            )
                    );
                    if (viewport.series().isEmpty()) {
                        return List.of();
                    }
                    List<GraphBucket> out = new ArrayList<>();
                    for (SignalBucketDto b : viewport.series().getFirst().buckets()) {
                        out.add(new GraphBucket(
                                b.firstTimestampNanos(), b.firstValue(),
                                b.minTimestampNanos(), b.minValue(),
                                b.maxTimestampNanos(), b.maxValue(),
                                b.lastTimestampNanos(), b.lastValue(),
                                b.sampleCount()
                        ));
                    }
                    return out;
                }

                @Override
                public String unit(SignalCatalogKey key) {
                    return catalog.find(key).map(SignalCatalogEntry::unit).orElse("");
                }

                @Override
                public long sampleCount(SignalCatalogKey key) {
                    return catalog.find(key).map(SignalCatalogEntry::sampleCount).orElse(0L);
                }
            };
        }
    }
}
