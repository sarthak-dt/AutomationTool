package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.processing.LocalProcessingGateway;
import com.stellar.platform.desktop.processing.ProcessingGateway;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.FileKindDetector;
import com.stellar.platform.desktop.project.MeasurementSourceCapability;
import com.stellar.platform.desktop.project.FileValidationStatus;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectInventoryJobState;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.ProjectWorkspace;
import com.stellar.platform.desktop.project.ProjectFilesAddResult;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.signals.SignalPreviewBuffer;
import com.stellar.platform.desktop.validation.InputValidationResult;
import com.stellar.platform.desktop.validation.ValidationIssue;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParseException;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.inventory.CanDefinitionMatchSummary;
import com.stellar.platform.domain.inventory.CanInventoryDiagnostic;
import com.stellar.platform.domain.inventory.CanInventoryDiagnosticCode;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.ProcessingSelection;
import java.util.HashMap;
import java.util.ArrayList;
import com.stellar.platform.processing.plan.ProcessingPlanInputs;
import com.stellar.platform.processing.plan.ProcessingPlanBuilder;
import com.stellar.platform.processing.execution.ProjectPlanExecutor;
import com.stellar.platform.processing.execution.PlanJobLifecycleListener;
import com.stellar.platform.processing.execution.PlanExecutionContext;
import com.stellar.platform.processing.execution.PlanExecutionPolicy;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.parser.spi.SimpleCancellationToken;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.domain.processing.plan.ProjectChannelSelection;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnostic;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnosticCode;
import com.stellar.platform.domain.processing.plan.ProjectExecutionStatus;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.ProcessingRequest;
import com.stellar.platform.processing.ProcessingStage;
import com.stellar.platform.processing.ProcessingStatistics;
import com.stellar.platform.processing.inventory.CanDefinitionMatchingService;
import com.stellar.platform.processing.inventory.SourceInventoryService;
import com.stellar.platform.storage.ArtifactStorageRefs;
import com.stellar.platform.storage.ManagedProjectArtifactStore;
import com.stellar.platform.storage.ProjectDeletionService;
import com.stellar.platform.storage.ProjectRootIndex;
import com.stellar.platform.storage.ProjectStoragePaths;
import com.stellar.platform.storage.ProjectWorkspaceCatalog;
import com.stellar.platform.storage.StellarAiPaths;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.analysis.AnalysisStorageValidator;
import com.stellar.platform.storage.analysis.CentralSqlitePaths;
import com.stellar.platform.analysis.model.AlignmentPolicy;
import com.stellar.platform.analysis.model.AnalysisRequest;
import com.stellar.platform.analysis.model.AnalysisResult;
import com.stellar.platform.analysis.model.AnalysisSeverity;
import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.EngineeringFinding;
import com.stellar.platform.analysis.model.FindingType;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.storage.metadata.AnalysisWorkspaceMetadata;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.processing.graph.LosslessGraphSignalStore;
import com.stellar.platform.processing.time.SharedTimeSelectionModel;
import com.stellar.platform.storage.metadata.ChannelMappingMetadata;
import com.stellar.platform.storage.metadata.DuplicateProjectNameException;
import com.stellar.platform.storage.metadata.FileFingerprint;
import com.stellar.platform.storage.metadata.NoOpProjectMetadataStore;
import com.stellar.platform.storage.metadata.ProcessingArtifactMetadata;
import com.stellar.platform.storage.metadata.ProcessingPlanFingerprint;
import com.stellar.platform.storage.metadata.ProcessingRunMetadata;
import com.stellar.platform.storage.metadata.ProjectDashboardSummary;
import com.stellar.platform.storage.metadata.ProjectFileMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadataStore;
import com.stellar.platform.storage.metadata.ProjectNameNormalizer;
import com.stellar.platform.storage.metadata.SavedProjectSnapshot;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import java.util.Optional;
import com.stellar.platform.api.support.BoundedExecutors;
import com.stellar.platform.api.support.ProcessingPlanDtoMapper;
import com.stellar.platform.api.support.WorkerResourceLimits;
import com.stellar.platform.api.worker.LocalPlanWorkerEngine;
import com.stellar.platform.api.worker.InProcessLocalWorker;
import com.stellar.platform.api.worker.LocalWorkerBoundary;
import com.stellar.platform.api.worker.LocalWorkerSupervisor;
import com.stellar.platform.api.worker.ProcessIsolatedLocalWorker;
import com.stellar.platform.api.worker.ProcessingTaskDescriptor;
import com.stellar.platform.api.worker.WorkerDiedException;
import com.stellar.platform.api.worker.WorkerMessage;
import com.stellar.platform.api.worker.WorkerProtocol;
import com.stellar.platform.domain.processing.ProcessingFailureClass;
import com.stellar.platform.processing.runtime.LocalResourceManager;
import com.stellar.platform.processing.runtime.RetryRecoveryManager;
import com.stellar.platform.storage.metadata.AuditEventMetadata;
import com.stellar.platform.storage.metadata.AuditEventType;
import com.stellar.platform.storage.metadata.ProcessingJobMetadata;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Presentation service coordinating workspace, validation, and local processing.
 * Controllers stay thin by delegating here.
 */
public final class DesktopAppService {

    private static final System.Logger LOG = System.getLogger(DesktopAppService.class.getName());
    private volatile ProjectMetadataStore metadataStore;
    private final boolean injectedPersistentStore;
    private volatile Path activeProjectRoot;
    private final AtomicReference<String> metadataProjectId = new AtomicReference<>(UUID.randomUUID().toString());
    private final AtomicReference<Instant> metadataProjectCreatedAt = new AtomicReference<>(Instant.now());
    private final AtomicReference<List<ProcessingRunMetadata>> persistedProcessingRuns = new AtomicReference<>(List.of());
    private final ProjectSaveCoordinator saveCoordinator = new ProjectSaveCoordinator();
    private final AtomicLong persistenceGeneration = new AtomicLong();
    private final AtomicReference<Instant> lastSavedAt = new AtomicReference<>();
    private final AtomicReference<Boolean> durableNamedProject = new AtomicReference<>(false);
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();
    /** Test-only: thrown after a saved snapshot is loaded, before the target is activated. */
    volatile RuntimeException openHydrationFailureForTest;
    /** Test-only: runs on the project-switch thread inside hydration so the FX pulse can be observed. */
    volatile Runnable projectOpenPauseForTest;
    /**
     * Test-only: runs on the project-switch thread after hydration returns and before the shell
     * publishes the project. Production leaves this null.
     */
    volatile Runnable projectOpenBeforePublishPauseForTest;

    public void setProjectOpenPauseForTest(Runnable pause) {
        projectOpenPauseForTest = pause;
    }

    public void setProjectOpenBeforePublishPauseForTest(Runnable pause) {
        projectOpenBeforePublishPauseForTest = pause;
    }

    public void setOpenHydrationFailureForTest(RuntimeException failure) {
        openHydrationFailureForTest = failure;
    }
    private final ThreadLocal<Long> openSwitchGeneration = ThreadLocal.withInitial(() -> 0L);
    private final ThreadLocal<BooleanSupplier> openSwitchCancelled = ThreadLocal.withInitial(() -> () -> false);
    private final ExecutorService projectSwitchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "stellar-project-switch");
        thread.setDaemon(true);
        return thread;
    });
    /** Live navigation memory — updated on every route change; not the Save checkpoint. */
    private final AtomicReference<WorkspaceRoute> lastMeaningfulWorkspace = new AtomicReference<>();
    /** Explicit Save checkpoint resume route — persisted in {@code projects.last_workspace}. */
    private final AtomicReference<WorkspaceRoute> checkpointResumeRoute = new AtomicReference<>();
    private final AtomicReference<Runnable> persistenceFlushHook = new AtomicReference<>();
    private volatile ProjectDeletionService projectDeletionService;
    private static final int DEFAULT_INVENTORY_PARALLELISM = Math.min(4,
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
    private static final int INVENTORY_PARALLELISM = Math.max(1, Math.min(8,
            Integer.getInteger("stellar.inventory.parallelism", DEFAULT_INVENTORY_PARALLELISM)));
    private static final AtomicInteger INVENTORY_THREAD_SEQUENCE = new AtomicInteger();


    private final DesktopSession session;
    private final ProcessingScreenModel processingModel = new ProcessingScreenModel();
    private final SignalsScreenModel signalsModel = new SignalsScreenModel();
    private final java.util.concurrent.atomic.AtomicInteger gpsMappingRevision =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Installation-wide configuration defaults; opened on first use, see {@link #configDefaults()}. */
    private com.stellar.platform.storage.api.ConfigMappingDefaultsStore configDefaultsStore;
    private final GraphInvestigationState graphInvestigation = new GraphInvestigationState();
    private final AtomicReference<AnalysisScope> analysisScopeRef = new AtomicReference<>();
    private final SharedTimeSelectionModel analysisSelection = new SharedTimeSelectionModel();
    private final SourceInventoryModel sourceInventoryModel = new SourceInventoryModel();
    private final ChannelSelectionModel channelSelectionModel = new ChannelSelectionModel();
    private final ProjectProcessingContext projectProcessingContext = new ProjectProcessingContext();
    private final ProcessingSelectionWorkspaceModel processingSelectionModel = new ProcessingSelectionWorkspaceModel();
    private final com.stellar.platform.processing.execution.ProcessingResultRegistry processingResultRegistry =
            new com.stellar.platform.processing.execution.ProcessingResultRegistry();
    private final SourceInventoryService sourceInventoryService;
    private final CanDefinitionMatchingService definitionMatchingService = new CanDefinitionMatchingService();
    private final DbcParser dbcParser = new DbcParser();
    private final AtomicReference<Path> cachedDbcPath = new AtomicReference<>();
    private final AtomicReference<DbcDatabase> cachedDbcDatabase = new AtomicReference<>();
    private final AtomicReference<NavigationPage> currentPage =
            new AtomicReference<>(NavigationPage.HOME);
    private final AtomicReference<List<ValidationIssue>> lastValidationIssues =
            new AtomicReference<>(List.of());
    private volatile LocalWorkerBoundary localProcessingWorker;
    private final boolean isolateProductionProcessing;
    private final ExecutorService processingExecutor;
    private final LocalResourceManager resourceManager = new LocalResourceManager();
    private final RetryRecoveryManager retryManager = new RetryRecoveryManager();
    private final ExecutorService inventoryExecutor = Executors.newFixedThreadPool(INVENTORY_PARALLELISM, r -> {
        Thread t = new Thread(r, "stellar-source-inventory-" + INVENTORY_THREAD_SEQUENCE.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    /** One background reader for status-bar and catalog counts. Never runs on the JavaFX thread. */
    private final ExecutorService metadataReadExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "stellar-metadata-read");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger cachedProjectCount = new AtomicInteger(-1);
    private final AtomicInteger cachedSignalCount = new AtomicInteger(0);
    private final AtomicReference<String> cachedSignalProjectId = new AtomicReference<>("");
    private final AtomicLong metadataCacheGeneration = new AtomicLong();
    private final AtomicLong dashboardLoadGeneration = new AtomicLong();
    private final AtomicBoolean metadataRefreshQueued = new AtomicBoolean(false);
    private final AtomicReference<Runnable> metadataCacheListener = new AtomicReference<>();
    private final AtomicReference<Future<?>> runningJob = new AtomicReference<>();
    private final AtomicReference<SimpleCancellationToken> planExecutionToken = new AtomicReference<>();
    private final AtomicReference<String> currentProcessingRunId = new AtomicReference<>();
    private final AtomicReference<String> isolatedTaskId = new AtomicReference<>();
    /** Last claimed terminal outcome for the in-flight run; protects COMPLETED from late Cancel. */
    private final AtomicReference<String> finalizedProcessingOutcome = new AtomicReference<>();
    /**
     * Claimed before any Start Processing work happens so a second click cannot slip between the
     * activity check and {@code beginExecution}, regardless of which thread it arrives on.
     */
    private final AtomicBoolean planExecutionStarting = new AtomicBoolean(false);
    private final AtomicBoolean analyzeHandoffWaveforms = new AtomicBoolean(false);
    /** Package-visible probe invoked at the start of background plan execution (tests). */
    volatile Runnable planExecutionThreadProbeForTest;
    /** Package-visible probe invoked when the first job is reported as starting (tests). */
    volatile Runnable planJobStartingProbeForTest;
    /** When set, isolated Start Processing throws this message after the run is claimed (tests). */
    volatile String isolatedExecutionFailureForTest;
    /** Counts durable staging-store creations; Explore Signals must not increment this. */
    final AtomicInteger signalStoreCreateCountForTest = new AtomicInteger();

    private final ProjectInventoryCoordinator inventoryCoordinator;
    private final ProjectDefinitionMatchingModel definitionMatchingModel = new ProjectDefinitionMatchingModel();
    private final ProjectDefinitionMatchingCoordinator definitionMatchingCoordinator;
    private final SourceAliasRegistry sourceAliasRegistry = new SourceAliasRegistry();
    private final SourceAvailabilityModel sourceAvailabilityModel = new SourceAvailabilityModel();
    /**
     * Source fingerprint recorded by the last processing run, keyed by project source id. Resident
     * so Review &amp; Process can report externally modified sources without re-reading storage.
     */
    private final Map<String, String> lastProcessedSourceFingerprints = new ConcurrentHashMap<>();
    /** Latest persisted/resident processing-job outcome keyed by project source id. */
    private final Map<String, JobExecutionStatus> lastSourceProcessingStatus = new ConcurrentHashMap<>();
    private final WorkingDbcSelectionStore workingDbcSelections = new WorkingDbcSelectionStore();
    private final QuickDecodeSelectionModel quickDecodeSelectionModel = new QuickDecodeSelectionModel();
    private final QuickDecodeDecisionResolver quickDecodeDecisionResolver =
            new QuickDecodeDecisionResolver(definitionMatchingModel, quickDecodeSelectionModel);
    /**
     * Product default is Auto Decode ({@link DecisionWorkspaceMode#QUICK_DECODE}).
     * Custom Decode ({@link DecisionWorkspaceMode#DETAILED_MAPPING}) is entered only when the
     * user explicitly clicks Custom Decode in the current project session.
     */
    private final AtomicReference<DecisionWorkspaceMode> decisionWorkspaceMode =
            new AtomicReference<>(DecisionWorkspaceMode.QUICK_DECODE);
    private final AnalysisStorageService analysisStorageService;
    private final DesktopAnalysisService desktopAnalysisService = new DesktopAnalysisService();
    private final DesktopDiagnosticService desktopDiagnosticService = new DesktopDiagnosticService();
    private final DesktopInvestigationService desktopInvestigationService = new DesktopInvestigationService();
    private final java.util.concurrent.atomic.AtomicLong diagnosticArtifactGeneration =
            new java.util.concurrent.atomic.AtomicLong(1);

    public DesktopAppService(DesktopSession session) {
        this(session, null, NoOpProjectMetadataStore.INSTANCE, null, false);
    }

    /** Production LOCAL desktop: heavy decode runs in a separate worker JVM. */
    public static DesktopAppService forLocalProduction(DesktopSession session) {
        return new DesktopAppService(session, null, NoOpProjectMetadataStore.INSTANCE, null, true);
    }

    /** Production constructor with lightweight project metadata persistence. */
    public DesktopAppService(DesktopSession session, ProjectMetadataStore metadataStore) {
        this(session, null, metadataStore, null, false);
    }

    /** Test constructor with injectable Analysis Storage (project-creation guard tests). */
    public DesktopAppService(DesktopSession session, AnalysisStorageService analysisStorageService) {
        this(session, null, NoOpProjectMetadataStore.INSTANCE, analysisStorageService, false);
    }

    /**
     * Test/package constructor allowing a synthetic {@link SourceInventoryRunner}.
     */
    DesktopAppService(DesktopSession session, SourceInventoryRunner inventoryRunner) {
        this(session, inventoryRunner, NoOpProjectMetadataStore.INSTANCE, null);
    }

    DesktopAppService(
            DesktopSession session,
            SourceInventoryRunner inventoryRunner,
            ProjectMetadataStore metadataStore
    ) {
        this(session, inventoryRunner, metadataStore, null);
    }

    DesktopAppService(
            DesktopSession session,
            SourceInventoryRunner inventoryRunner,
            ProjectMetadataStore metadataStore,
            AnalysisStorageService analysisStorageService
    ) {
        this(session, inventoryRunner, metadataStore, analysisStorageService, false);
    }

    DesktopAppService(
            DesktopSession session,
            SourceInventoryRunner inventoryRunner,
            ProjectMetadataStore metadataStore,
            AnalysisStorageService analysisStorageService,
            boolean isolateProductionProcessing
    ) {
        this.session = Objects.requireNonNull(session, "session");
        this.desktopInvestigationService.samplesFrom(() -> signalsModel.catalogStore());
        this.isolateProductionProcessing = isolateProductionProcessing;
        // Coordinator pool is independent of InProcessLocalWorker. Production Process
        // starts an isolated JVM from this same thread; shutting down the in-process
        // pool must never interrupt or reject the coordinator.
        this.processingExecutor = BoundedExecutors.newFixed("stellar-local-processing", 1, 8);
        this.localProcessingWorker = new InProcessLocalWorker(
                "stellar-local-processing-pool",
                new WorkerResourceLimits(1, 1, 8, 1)
        );
        this.metadataStore = metadataStore == null ? NoOpProjectMetadataStore.INSTANCE : metadataStore;
        this.injectedPersistentStore = this.metadataStore != NoOpProjectMetadataStore.INSTANCE;
        this.analysisStorageService = analysisStorageService == null
                ? new AnalysisStorageService()
                : analysisStorageService;
        this.projectDeletionService = new ProjectDeletionService(this.metadataStore, new ManagedProjectArtifactStore());
        this.sourceInventoryService = new SourceInventoryService(session.parserRegistry());
        SourceInventoryRunner runner = inventoryRunner == null
                ? (session.executionMode() == com.stellar.platform.processing.ExecutionMode.REMOTE
                ? SourceInventoryRunner.remote(
                session.platformClient(),
                session::ensureRemoteProjectId)
                : SourceInventoryRunner.of(this.sourceInventoryService))
                : inventoryRunner;
        this.inventoryCoordinator = new ProjectInventoryCoordinator(
                runner,
                inventoryExecutor,
                this::applyInventoryProgress,
                INVENTORY_PARALLELISM
        );
        this.inventoryCoordinator.setTerminalCommit(this::commitInventoryCoverage);
        this.definitionMatchingCoordinator = new ProjectDefinitionMatchingCoordinator(
                definitionMatchingService,
                definitionMatchingModel
        );
        this.session.bindPreferredProjectId(this::currentProjectId);
    }

    public DesktopSession session() {
        return session;
    }

    public ProjectWorkspace workspace() {
        return session.workspace();
    }

    public ProcessingScreenModel processingModel() {
        return processingModel;
    }

    public GraphInvestigationState graphInvestigation() {
        return graphInvestigation;
    }

    public SignalsScreenModel signalsModel() {
        return signalsModel;
    }

    public AnalysisStorageService analysisStorage() {
        return analysisStorageService;
    }

    public SourceInventoryModel sourceInventoryModel() {
        return sourceInventoryModel;
    }

    public ChannelSelectionModel channelSelectionModel() {
        return channelSelectionModel;
    }

    public ProjectProcessingContext projectProcessingContext() {
        return projectProcessingContext;
    }

    public ProcessingSelectionWorkspaceModel processingSelectionModel() {
        return processingSelectionModel;
    }

    public com.stellar.platform.processing.execution.ProcessingResultRegistry processingResultRegistry() {
        return processingResultRegistry;
    }

    public NavigationPage currentPage() {
        return currentPage.get();
    }

    public void navigate(NavigationPage page) {
        if (page != null) {
            currentPage.set(page);
        }
    }

    public void newProject(String name) {
        newProject(name, null);
    }

    /**
     * Creates a named project in the central SQLite database.
     * New projects do not create {@code projectRoot/.stellar/stellar.db}.
     * Untitled/unsaved work stays in-memory until this method runs.
     */
    public void newProject(String name, Path projectRoot) {
        analysisStorageService.requireValidForProjectCreation();
        String display = ProjectNameNormalizer.requireDisplayName(name);
        if (projectNameTaken(display)) {
            throw new DuplicateProjectNameException(display, metadataStore.findByNormalizedName(
                    ProjectNameNormalizer.normalized(display)).map(ProjectMetadata::projectId).orElse(""));
        }
        abandonCurrentSession();
        resetRuntimeProject(display);
        if (!injectedPersistentStore) {
            ensureCentralMetadataStore();
        }
        String newId = UUID.randomUUID().toString();
        if (metadataStore instanceof SqliteProjectMetadataStore sqlite) {
            newId = Long.toString(sqlite.allocateNumericId("projects"));
        }
        metadataProjectId.set(newId);
        metadataProjectCreatedAt.set(Instant.now());
        persistedProcessingRuns.set(List.of());
        if (!injectedPersistentStore) {
            Path root = projectRoot != null
                    ? projectRoot
                    : AnalysisStorageRoots.projectArtifactsRoot(newId);
            try {
                Files.createDirectories(root);
            } catch (IOException ex) {
                throw new IllegalStateException("Cannot create project artifact directory " + root, ex);
            }
            activeProjectRoot = root.toAbsolutePath().normalize();
            ProjectRootIndex.register(newId, activeProjectRoot);
        }
        boolean inserted = false;
        try {
            persistProjectCritical();
            inserted = true;
            appendAudit(AuditEventType.PROJECT_CREATED, newId, display);
            durableNamedProject.set(true);
            noteProjectMetadataChanged(newId, 0);
            lastSavedAt.set(null);
            checkpointResumeRoute.set(null);
            saveCoordinator.attach(newId);
        } catch (DuplicateProjectNameException | IllegalArgumentException ex) {
            enterCleanEmptyWorkspace();
            throw ex;
        } catch (RuntimeException ex) {
            if (!inserted) {
                enterCleanEmptyWorkspace();
            }
            throw ex;
        }
    }

    /** True when a trimmed name already belongs to another saved project. */
    public boolean projectNameTaken(String name) {
        try {
            String display = ProjectNameNormalizer.requireDisplayName(name);
            if (!injectedPersistentStore) {
                ensureCentralMetadataStore();
            }
            return metadataStore.existsProjectName(display, null);
        } catch (IllegalArgumentException ex) {
            return false;
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Cannot check project name: " + ex.getMessage());
            return false;
        }
    }

    /** Saved project summaries ordered by last update. */
    public List<ProjectMetadata> savedProjects() {
        try {
            if (!injectedPersistentStore) {
                ensureCentralMetadataStore();
            }
            return metadataStore.listProjects();
        }
        catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Cannot list saved projects: " + ex.getMessage());
            return List.of();
        }
    }

    /**
     * Cheap Dashboard Recent Projects rows (metadata counts only — no MF4/waveform load).
     */
    public List<ProjectDashboardSummary> projectDashboardSummaries() {
        try {
            if (!injectedPersistentStore) {
                ensureCentralMetadataStore();
            }
            return metadataStore.listProjectDashboardSummaries().stream()
                    .map(this::locateDashboardSummary)
                    .toList();
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Cannot list project dashboard summaries: " + ex.getMessage());
            return List.of();
        }
    }

    /**
     * Most recently updated saved project. Uses persisted {@code updatedAt} only —
     * not name order and not an in-memory list position.
     */
    public java.util.Optional<String> mostRecentlyUpdatedProjectId() {
        try {
            if (!injectedPersistentStore) {
                ensureCentralMetadataStore();
            }
            return metadataStore.latestUpdatedProjectId();
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Cannot read the latest saved project: " + ex.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * Saved-project count for the status bar. Reads a cache. A miss schedules one
     * background count and returns 0 until that count arrives.
     */
    public int savedProjectCount() {
        int cached = cachedProjectCount.get();
        if (cached < 0) {
            scheduleMetadataRefresh();
            return 0;
        }
        return cached;
    }

    /**
     * Refreshes the status-bar project count and the active project's catalog count
     * off the JavaFX thread. Listener runs on the caller of this method's background task;
     * the shell posts it back to JavaFX.
     */
    public void setProjectMetadataCacheListener(Runnable listener) {
        metadataCacheListener.set(listener);
        scheduleMetadataRefresh();
    }

    /**
     * Loads dashboard rows off the JavaFX thread and delivers them on the JavaFX thread.
     * One load at a time; a newer request replaces a queued one.
     */
    public void loadProjectDashboardSummaries(java.util.function.Consumer<List<ProjectDashboardSummary>> onLoaded) {
        if (onLoaded == null) {
            return;
        }
        long generation = dashboardLoadGeneration.incrementAndGet();
        metadataReadExecutor.execute(() -> {
            if (generation != dashboardLoadGeneration.get()) {
                return;
            }
            List<ProjectDashboardSummary> rows = projectDashboardSummaries();
            if (generation != dashboardLoadGeneration.get()) {
                return;
            }
            javafx.application.Platform.runLater(() -> {
                if (generation == dashboardLoadGeneration.get()) {
                    onLoaded.accept(rows);
                }
            });
        });
    }

    /**
     * Cheap journey facts for the active workspace. Uses only resident presentation state:
     * no measurement parsing, no inventory run and no large signal query.
     */
    public ProjectJourneyFacts projectJourneyFacts() {
        if (!isDurableNamedProject()) {
            return ProjectJourneyFacts.noProject();
        }
        List<WorkspaceFile> measurements = workspace().measurementFiles();
        int usable = usableDecodeSources().size();
        String lastRunStatus = latestPersistedProcessingRun()
                .map(ProcessingRunMetadata::status)
                .orElse("");
        boolean running = planExecutionStarting.get()
                || processingSelectionModel.mode() == ProcessingPageMode.EXECUTING;
        boolean interrupted = !running && ProcessingRunStatuses.isInterrupted(lastRunStatus);
        boolean failed = ProcessingRunStatuses.isFailed(lastRunStatus)
                || workspace().processingStatus() == ProjectProcessingStatus.FAILED;
        boolean memoryCatalog = signalsModel.catalog() != null
                && !signalsModel.catalog().entries().isEmpty();
        boolean seededCatalog = processingResultRegistry.all().stream()
                .anyMatch(IndependentJobResult::successful);
        // Persisted rows alone are not analysis-ready. A missing or stale source must not
        // enable Signals from catalog rows whose artifact could not be reopened.
        boolean catalogSignals = memoryCatalog
                || (persistedSignalCatalogCount() > 0 && seededCatalog);
        SourceWorkflowProfile workflow = SourceWorkflowProfile.fromMeasurements(measurements);
        boolean directPending = false;
        boolean anyDirectProcessed = false;
        for (WorkspaceFile file : measurements) {
            if (!MeasurementSourceCapability.supportsDirectProcessing(file.kind())
                    || !file.status().processingAllowed()) {
                continue;
            }
            if (sourceProcessed(file.projectSourceId())) {
                anyDirectProcessed = true;
            } else {
                directPending = true;
            }
        }
        boolean successfulJobs = processingResultRegistry.all().stream()
                .anyMatch(IndependentJobResult::successful);
        // A successful direct run that produced no catalog entries is not analysis-ready.
        // Counting it as ready would offer Signals with nothing to explore, or hide the result.
        boolean analysisReady = catalogSignals || (!workflow.directOnly() && successfulJobs);
        boolean completed = ProcessingRunStatuses.isCompleted(lastRunStatus) && !failed;
        boolean settledWithoutSignals = workflow.directOnly()
                && !catalogSignals
                && !directPending
                && (completed || anyDirectProcessed);
        List<SignalCatalogKey> graphWorkspace = signalsModel.workspaceKeys();
        boolean graphContextValid = analysisReady
                && !graphWorkspace.isEmpty()
                && signalsModel.missingWorkspaceKeys(graphWorkspace).isEmpty();
        boolean warned = !failed && (ProcessingRunStatuses.isCompletedWithWarnings(lastRunStatus)
                || processingResultRegistry.all().stream()
                .anyMatch(job -> job.status() == JobExecutionStatus.SUCCESS_WITH_WARNINGS));
        return new ProjectJourneyFacts(
                true,
                measurements.size(),
                usable,
                canProceedFromDecisionWorkspace(),
                running,
                interrupted,
                failed,
                analysisReady,
                graphContextValid,
                warned,
                workflow.directOnly(),
                directPending,
                settledWithoutSignals);
    }

    /**
     * Single investigation cursor shared by Waveforms, Map Route, Inspector and events.
     * Views observe this model instead of wiring JavaFX controls to each other.
     */
    public SharedTimeSelectionModel analysisSelection() {
        return analysisSelection;
    }

    /** User-facing project health, including warning count when processing finished with diagnostics. */
    public String projectHealthDisplay() {
        ProjectJourneyState state = projectJourneyState();
        if (state.readyWithWarnings()) {
            int n = processingWarningReport().count();
            if (n <= 0) {
                return state.statusLabel().display();
            }
            return n == 1 ? "Ready with 1 warning" : "Ready with " + n + " warnings";
        }
        return state.statusLabel().display();
    }

    public ProcessingWarningReport processingWarningReport() {
        return ProcessingWarningReport.fromJobs(processingResultRegistry.all(), this::sourceDisplayName);
    }

    public String sourceDisplayName(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return "";
        }
        for (WorkspaceFile file : workspace().measurementFiles()) {
            if (sourceId.equals(file.id())) {
                return file.fileName();
            }
        }
        return sourceId;
    }

    public String activeMeasurementFileName() {
        return activeAnalysisMeasurement().fileName();
    }

    /** Stable project source id of the selected measurement; empty when none is selected. */
    public String activeMeasurementSourceId() {
        return activeAnalysisMeasurement().sourceId();
    }

    /**
     * Authoritative analysis measurement. Controllers must read this instead of reconstructing
     * identity from filename, catalog display, or sticky configuration state.
     *
     * <p>A workspace selection always wins. When nothing is selected, a catalog that contains
     * exactly one measurement resolves deterministically to that measurement. Two or more
     * identities without a selection fail closed — never the first file.
     */
    public AnalysisMeasurementContext activeAnalysisMeasurement() {
        return workspace().selectedMeasurement()
                .map(file -> AnalysisMeasurementContext.of(file.id(), file.fileName()))
                .orElseGet(this::soleCatalogMeasurement);
    }

    /** Analyze Selected → Graphs must land on Waveforms, not a restored analysis tab. */
    public void markAnalyzeHandoff() {
        analyzeHandoffWaveforms.set(true);
    }

    public boolean consumeAnalyzeHandoff() {
        return analyzeHandoffWaveforms.getAndSet(false);
    }

    private AnalysisMeasurementContext soleCatalogMeasurement() {
        List<MeasurementCatalogScope.Ref> refs = MeasurementCatalogScope.measurements(
                signalsModel.catalog().entries());
        if (refs.size() != 1) {
            return AnalysisMeasurementContext.none();
        }
        MeasurementCatalogScope.Ref ref = refs.getFirst();
        return AnalysisMeasurementContext.of(ref.identity(), ref.displayName());
    }

    /**
     * Complete unprojected catalog for the active measurement. Empty when no measurement is
     * selected or the catalog has no rows for that identity (fail closed).
     */
    public List<SignalCatalogEntry> engineeringCatalog() {
        if (isProjectWideAnalysis()) {
            return signalsModel.engineeringCatalogAll();
        }
        return engineeringCatalog(activeAnalysisMeasurement());
    }

    /**
     * BD-25: a checkpoint key is restored by exact identity. Physical per-source keys and
     * project-logical {@code PROJECT} keys are different rows. Looking only at the physical
     * catalog drops a logical key as soon as two measurements share its name, because a
     * unique-name remap then has more than one owner. The two catalogs are not combined for
     * that remap: one signal would be counted twice and a unique owner would be rejected.
     */
    private SignalCatalogEntry resolveRestoredGraphEntry(SignalCatalogKey requested) {
        if (requested == null) {
            return null;
        }
        List<SignalCatalogEntry> physical = physicalGraphRestoreCatalog();
        SignalCatalogEntry exact = exactCatalogKey(requested, physical);
        if (exact != null) {
            return exact;
        }
        List<SignalCatalogEntry> logical = signalsModel.engineeringCatalogAll();
        exact = exactCatalogKey(requested, logical);
        if (exact != null) {
            return exact;
        }
        // The checkpoint codec uses the same separator that a logical key stores inside its
        // database field, so a saved project key may not byte-match that row. A project key
        // then resolves to the one logical catalog row. It is not assigned to one of several
        // physical files. A physical key resolves only to its own measurement.
        String source = requested.sourceFile() == null ? "" : requested.sourceFile().trim();
        boolean projectLogical = source.isEmpty()
                || ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE.equalsIgnoreCase(source);
        if (projectLogical) {
            SignalCatalogEntry logicalMatch = MeasurementCatalogScope.remapToMeasurement(requested, logical);
            if (logicalMatch != null) {
                return logicalMatch;
            }
            return MeasurementCatalogScope.remapToMeasurement(requested, physical);
        }
        List<SignalCatalogEntry> uniquePool = physical.isEmpty() ? logical : physical;
        return MeasurementCatalogScope.remapToMeasurement(requested, uniquePool);
    }

    private List<SignalCatalogEntry> physicalGraphRestoreCatalog() {
        if (signalsModel.catalogStore() instanceof ProjectMergedSignalStore merged) {
            return merged.physicalCatalogAll();
        }
        return List.of();
    }

    private static SignalCatalogEntry exactCatalogKey(
            SignalCatalogKey requested,
            List<SignalCatalogEntry> catalog
    ) {
        if (requested == null || catalog == null) {
            return null;
        }
        for (SignalCatalogEntry entry : catalog) {
            if (entry != null && requested.equals(entry.key())) {
                return entry;
            }
        }
        return null;
    }

    private boolean graphRestoreCatalogReady() {
        return !physicalGraphRestoreCatalog().isEmpty()
                || !signalsModel.engineeringCatalogAll().isEmpty();
    }

    public List<SignalCatalogEntry> engineeringCatalog(AnalysisMeasurementContext measurement) {
        return signalsModel.engineeringCatalog(measurement);
    }

    /** Explicit Graphs scope. {@code ALL} is never encoded as null, {@code "*"}, or a fake id. */
    public AnalysisScope analysisScope() {
        if (isProjectWideAnalysis()) {
            return AnalysisScope.allMeasurements(workspaceMeasurementCount());
        }
        AnalysisMeasurementContext ctx = activeAnalysisMeasurement();
        if (ctx.isPresent()) {
            return AnalysisScope.single(ctx);
        }
        return AnalysisScope.allMeasurements(workspaceMeasurementCount());
    }

    public boolean isProjectWideAnalysis() {
        AnalysisScope stored = analysisScopeRef.get();
        return stored != null && stored.isAll();
    }

    public String analysisScopeToken() {
        if (isProjectWideAnalysis()) {
            return AnalysisScope.allMeasurements(workspaceMeasurementCount()).token();
        }
        return "SINGLE|" + activeAnalysisMeasurement().token();
    }

    public List<AnalysisScope> analysisScopeChoices() {
        List<WorkspaceFile> files = workspace().measurementFiles();
        List<AnalysisScope> choices = new ArrayList<>();
        choices.add(AnalysisScope.allMeasurements(files.size()));
        for (WorkspaceFile file : files) {
            if (file != null) {
                choices.add(AnalysisScope.single(file.id(), file.fileName()));
            }
        }
        return List.copyOf(choices);
    }

    public void selectAnalysisScope(AnalysisScope scope) {
        if (scope == null) {
            return;
        }
        if (scope.isAll()) {
            analysisScopeRef.set(AnalysisScope.allMeasurements(workspaceMeasurementCount()));
            return;
        }
        scope.measurement().ifPresent(ctx -> selectMeasurement(ctx.sourceId()));
    }

    private int workspaceMeasurementCount() {
        return workspace().measurementFiles().size();
    }

    public AnalysisMeasurementContext resolveAnalysisMeasurement(String identityOrName) {
        if (identityOrName == null || identityOrName.isBlank()) {
            return AnalysisMeasurementContext.none();
        }
        for (WorkspaceFile file : workspace().measurementFiles()) {
            if (identityOrName.equalsIgnoreCase(file.id())
                    || identityOrName.equalsIgnoreCase(file.fileName())) {
                return AnalysisMeasurementContext.of(file.id(), file.fileName());
            }
        }
        for (SignalCatalogEntry entry : signalsModel.mapRouteCatalog()) {
            if (MeasurementCatalogScope.belongs(entry, identityOrName, identityOrName)) {
                String sourceId = MeasurementCatalogScope.identityOf(entry);
                String fileName = MeasurementCatalogScope.displayOf(entry);
                for (WorkspaceFile file : workspace().measurementFiles()) {
                    if (file.id().equalsIgnoreCase(sourceId) || file.fileName().equalsIgnoreCase(fileName)) {
                        return AnalysisMeasurementContext.of(file.id(), file.fileName());
                    }
                }
                return AnalysisMeasurementContext.of(sourceId, fileName);
            }
        }
        return AnalysisMeasurementContext.none();
    }

    public String activeMeasurementTooltip() {
        return workspace().selectedMeasurement()
                .map(file -> file.fileName() + "\n" + file.displayPath())
                .orElse("");
    }

    /** Authoritative journey state for the active project. */
    public ProjectJourneyState projectJourneyState() {
        return ProjectJourneyStateResolver.resolve(projectJourneyFacts());
    }

    /** Cheap persisted catalog size for the open project — no sample load. */
    private int persistedSignalCatalogCount() {
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank() || metadataStore == null) {
            return 0;
        }
        if (projectId.equals(cachedSignalProjectId.get())) {
            return cachedSignalCount.get();
        }
        scheduleMetadataRefresh();
        return 0;
    }

    private void noteProjectMetadataChanged(String projectId, int signalCount) {
        metadataCacheGeneration.incrementAndGet();
        cachedSignalProjectId.set(projectId == null ? "" : projectId);
        cachedSignalCount.set(Math.max(0, signalCount));
        cachedProjectCount.updateAndGet(current -> current < 0 ? current : current + 1);
        if (cachedProjectCount.get() < 0) {
            scheduleMetadataRefresh();
        }
    }

    private void noteProjectCountChanged(int delta) {
        metadataCacheGeneration.incrementAndGet();
        cachedProjectCount.updateAndGet(current -> current < 0 ? current : Math.max(0, current + delta));
        if (cachedProjectCount.get() < 0) {
            scheduleMetadataRefresh();
        }
    }

    private void refreshSignalCacheFromStore() {
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank() || !isDurableNamedProject() || metadataStore == null) {
            return;
        }
        metadataCacheGeneration.incrementAndGet();
        try {
            cachedSignalProjectId.set(projectId);
            cachedSignalCount.set(Math.max(0, metadataStore.signalCatalogCount(projectId)));
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Cannot refresh signal catalog count: " + ex.getMessage());
        }
    }

    private void scheduleMetadataRefresh() {
        if (!metadataRefreshQueued.compareAndSet(false, true)) {
            return;
        }
        long generation = metadataCacheGeneration.get();
        String projectId = metadataProjectId.get();
        boolean durable = isDurableNamedProject();
        metadataReadExecutor.execute(() -> {
            try {
                int projects = 0;
                int signals = 0;
                boolean counted = false;
                try {
                    if (!injectedPersistentStore) {
                        ensureCentralMetadataStore();
                    }
                    projects = metadataStore.countProjects();
                    if (durable && projectId != null && !projectId.isBlank()) {
                        signals = metadataStore.signalCatalogCount(projectId);
                    }
                    counted = true;
                } catch (RuntimeException ex) {
                    LOG.log(System.Logger.Level.WARNING, "Cannot refresh project metadata cache: " + ex.getMessage());
                }
                if (counted && generation == metadataCacheGeneration.get()) {
                    cachedProjectCount.set(projects);
                    if (projectId != null && projectId.equals(metadataProjectId.get())) {
                        cachedSignalProjectId.set(projectId);
                        cachedSignalCount.set(Math.max(0, signals));
                    }
                    Runnable listener = metadataCacheListener.get();
                    if (listener != null) {
                        listener.run();
                    }
                }
            } finally {
                metadataRefreshQueued.set(false);
                if (generation != metadataCacheGeneration.get()) {
                    scheduleMetadataRefresh();
                }
            }
        });
    }

    /**
     * The last persisted run for this project never reached a terminal status, so work it had
     * scheduled is still outstanding. False while execution is live in this session.
     */
    public boolean processingRunInterrupted() {
        if (isPlanExecutionActive()) {
            return false;
        }
        return latestPersistedProcessingRun()
                .map(run -> ProcessingRunStatuses.isInterrupted(run.status()))
                .orElse(false);
    }

    private void rememberProcessedSourceFingerprints(List<ProcessingArtifactMetadata> artifacts) {
        if (artifacts == null) {
            return;
        }
        for (ProcessingArtifactMetadata artifact : artifacts) {
            if (artifact == null
                    || artifact.sourceId() == null
                    || artifact.sourceFingerprint() == null
                    || artifact.sourceFingerprint().isBlank()) {
                continue;
            }
            lastProcessedSourceFingerprints.put(artifact.sourceId(), artifact.sourceFingerprint());
        }
    }

    /**
     * True when this source has been processed before and its bytes changed since. Uses the same
     * size/mtime fingerprint the artifact reuse path uses, so review and execution agree; the file
     * is never opened or parsed to answer this.
     */
    public boolean sourceChangedSinceLastProcessing(ProjectSourceId sourceId) {
        if (sourceId == null) {
            return false;
        }
        String fingerprint = lastProcessedSourceFingerprints.get(sourceId.value());
        if (fingerprint == null || fingerprint.isBlank()) {
            return false;
        }
        return workspace().findFile(sourceId.value())
                .map(file -> !FileFingerprint.matches(fingerprint, file.path()))
                .orElse(false);
    }

    /** The last persisted run finished but not every job produced usable output. */
    public boolean processingRunFailed() {
        if (isPlanExecutionActive()) {
            return false;
        }
        return latestPersistedProcessingRun()
                .map(run -> ProcessingRunStatuses.isFailed(run.status()))
                .orElse(false);
    }

    /**
     * What the last persisted run of this project actually did. Presentation reads this instead of
     * re-deriving outcomes from status strings.
     */
    public record ProcessingRunOutcome(
            boolean present,
            boolean interrupted,
            boolean failed,
            boolean completed,
            int jobCount,
            String message
    ) {
        private static final ProcessingRunOutcome NONE =
                new ProcessingRunOutcome(false, false, false, false, 0, "");

        public static ProcessingRunOutcome none() {
            return NONE;
        }

        public ProcessingRunOutcome {
            message = message == null ? "" : message;
        }
    }

    public ProcessingRunOutcome lastProcessingRunOutcome() {
        if (planExecutionStarting.get()
                || processingSelectionModel.mode() == ProcessingPageMode.EXECUTING) {
            return ProcessingRunOutcome.none();
        }
        return latestPersistedProcessingRun()
                .map(run -> new ProcessingRunOutcome(
                        true,
                        ProcessingRunStatuses.isInterrupted(run.status()),
                        ProcessingRunStatuses.isFailed(run.status()),
                        ProcessingRunStatuses.isCompleted(run.status()),
                        run.jobCount(),
                        run.message()))
                .orElseGet(ProcessingRunOutcome::none);
    }

    /**
     * The one authoritative resume destination for the active project. Callers must not
     * re-implement resume rules.
     */
    public WorkspaceRoute resolveResumeRoute() {
        return resolveResumeRoute(checkpointResumeRoute.get() != null
                ? checkpointResumeRoute.get()
                : lastMeaningfulWorkspace.get());
    }

    /**
     * @param savedWorkspace last visible workspace, when one is known; it is only honoured when
     *                       the current project state can actually use it
     */
    public WorkspaceRoute resolveResumeRoute(WorkspaceRoute savedWorkspace) {
        if (!isDurableNamedProject()) {
            LOG.log(System.Logger.Level.WARNING,
                    "Resume destination requested with no active project");
            return WorkspaceRoute.HOME;
        }
        ProjectJourneyFacts facts = projectJourneyFacts();
        if (!facts.projectOpen()) {
            LOG.log(System.Logger.Level.WARNING,
                    "Resume destination requested before the active project was hydrated");
            return WorkspaceRoute.HOME;
        }
        return ResumeDestinationResolver.resolve(
                ProjectJourneyStateResolver.resolve(facts), savedWorkspace, facts);
    }

    public String currentProjectId() {
        return metadataProjectId.get();
    }

    public ProjectSaveState projectSaveState() {
        return saveCoordinator.state();
    }

    public boolean hasUnsavedChanges() {
        return saveCoordinator.hasUnsavedChanges();
    }

    public String saveStatusLabel() {
        ProjectSaveState state = saveCoordinator.state();
        return switch (state) {
            case SAVING -> "Saving…";
            case SAVE_FAILED -> "Save failed";
            case DIRTY -> "Unsaved changes";
            case CLEAN -> SaveStatusFormatter.compactStatus(lastSavedAt.get(), ZoneId.systemDefault());
        };
    }

    public String saveStatusTooltip() {
        if (saveCoordinator.state() == ProjectSaveState.SAVE_FAILED) {
            return "Save failed. Retry to persist again.";
        }
        if (saveCoordinator.state() == ProjectSaveState.DIRTY) {
            return "Project has unsaved changes.";
        }
        return SaveStatusFormatter.tooltip(lastSavedAt.get(), ZoneId.systemDefault());
    }

    public String lastSaveError() {
        return saveCoordinator.lastError();
    }

    public void addSaveStateListener(Runnable listener) {
        saveCoordinator.addListener(listener);
    }

    public void removeSaveStateListener(Runnable listener) {
        saveCoordinator.removeListener(listener);
    }

    public void setPersistenceFlushHook(Runnable hook) {
        persistenceFlushHook.set(hook);
    }

    /**
     * Durable logical change. Hover, LOD, generation and selection highlight must not call this.
     */
    public void noteLogicalChange() {
        if (!Boolean.TRUE.equals(durableNamedProject.get())) {
            return;
        }
        saveCoordinator.markLogicalDirty();
        scheduleProjectScopedAutosave();
    }

    public void noteGraphLogicalChange() {
        if (!Boolean.TRUE.equals(durableNamedProject.get())) {
            return;
        }
        // Graph session checkpoint advances only on explicit Save — mark dirty, do not autosave.
        saveCoordinator.noteGraphLogicalChange();
    }

    private void scheduleProjectScopedAutosave() {
        if (!Boolean.TRUE.equals(durableNamedProject.get()) || saveCoordinator.autosaveSuspended()) {
            return;
        }
        final long generation = persistenceGeneration.get();
        final String projectId = metadataProjectId.get();
        saveCoordinator.scheduleAutosave(
                () -> autosaveCompleteState(generation, projectId),
                ProjectSaveCoordinator.AUTOSAVE_DEBOUNCE_MS);
    }

    public void noteTransientGraphHover() {
        // Hover / LOD / generation are runtime only.
    }

    public void rememberMeaningfulWorkspace(WorkspaceRoute route) {
        if (route == WorkspaceRoute.SOURCES
                || route == WorkspaceRoute.DECODE
                || route == WorkspaceRoute.SIGNALS
                || route == WorkspaceRoute.GRAPHS
                || route == WorkspaceRoute.INVESTIGATIONS) {
            lastMeaningfulWorkspace.set(route);
        }
    }

    public Optional<WorkspaceRoute> lastMeaningfulWorkspace() {
        return Optional.ofNullable(lastMeaningfulWorkspace.get());
    }

    public RelocateSourceResult relocateSourceDetailed(ProjectSourceId sourceId, Path newPath) {
        if (sourceId == null || newPath == null) {
            return RelocateSourceResult.REJECTED;
        }
        Path normalized = newPath.toAbsolutePath().normalize();
        Optional<WorkspaceFile> target = workspace().findFile(sourceId.value());
        if (target.isEmpty() || !target.get().kind().measurement()) {
            return RelocateSourceResult.REJECTED;
        }
        if (!Files.isRegularFile(normalized)) {
            return RelocateSourceResult.REJECTED;
        }
        FileKind kind = FileKindDetector.detect(normalized);
        if (!kind.measurement() || kind != target.get().kind()) {
            return RelocateSourceResult.REJECTED;
        }
        Optional<WorkspaceFile> clash = workspace().findMeasurementByPath(normalized);
        if (clash.isPresent() && !clash.get().id().equals(sourceId.value())) {
            return RelocateSourceResult.REJECTED;
        }
        String expected = lastProcessedSourceFingerprints.get(sourceId.value());
        if (expected != null && !expected.isBlank() && !FileFingerprint.matches(expected, normalized)) {
            return RelocateSourceResult.DIFFERENT_CONTENT;
        }
        long size;
        try {
            size = Files.size(normalized);
        } catch (Exception ex) {
            size = 0L;
        }
        WorkspaceFile file = target.get();
        file.relocate(normalized, size);
        file.setStatus(FileValidationStatus.READY, "");
        sourceAvailabilityModel.markAvailable(sourceId);
        persistAllFiles();
        return RelocateSourceResult.REATTACHED;
    }

    /**
     * Releases project runtime without deleting durable StellarAI state.
     */
    public void closeProject() {
        // Close session handles only. Analysis Storage artifacts under the configured root are retained.
        abandonCurrentSession();
        resetRuntimeProject("Untitled Project");
        metadataProjectId.set(UUID.randomUUID().toString());
        metadataProjectCreatedAt.set(Instant.now());
        persistedProcessingRuns.set(List.of());
        durableNamedProject.set(false);
        lastMeaningfulWorkspace.set(null);
        checkpointResumeRoute.set(null);
        lastSavedAt.set(null);
        saveCoordinator.detach();
        activeProjectRoot = null;
    }

    void rememberProcessedSourceFingerprintForTest(String sourceId, String fingerprint) {
        if (sourceId != null && fingerprint != null) {
            lastProcessedSourceFingerprints.put(sourceId, fingerprint);
        }
    }

    public Optional<Instant> lastSavedAt() {
        return Optional.ofNullable(lastSavedAt.get());
    }

    /** True when the open project is a named row in the metadata store. */
    public boolean isDurableNamedProject() {
        return Boolean.TRUE.equals(durableNamedProject.get());
    }

    public record ProjectSaveResult(boolean saved, String message, List<String> warnings) {
        public ProjectSaveResult {
            message = message == null ? "" : message;
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
        }
    }

    public record ProjectDeleteResult(boolean deleted, String projectName, List<String> warnings) {
        public ProjectDeleteResult {
            projectName = projectName == null ? "" : projectName;
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
        }
    }

    /**
     * Persists the complete durable project state for the current {@code project_id}.
     * Does not reprocess sources or store waveform samples in SQLite.
     * Updates the explicit Save checkpoint (resume location + lastSavedAt).
     */
    public ProjectSaveResult saveProject() {
        return saveProject(null, true);
    }

    /**
     * @param overrideName when non-null, renames then saves under the same project_id
     */
    public ProjectSaveResult saveProject(String overrideName) {
        return saveProject(overrideName, true);
    }

    /**
     * Persist durable project data without advancing the explicit user checkpoint.
     * Used for application-exit integrity saves so unsaved navigation cannot replace
     * the last explicit Save resume location / lastSavedAt.
     */
    public ProjectSaveResult saveProjectDataOnly() {
        return saveProject(null, false);
    }

    private ProjectSaveResult saveProject(String overrideName, boolean updateCheckpoint) {
        synchronized (saveCoordinator.saveLock()) {
            flushPendingLogicalPersistence();
            saveCoordinator.beginSave();
            try {
                if (overrideName != null) {
                    String display = ProjectNameNormalizer.requireDisplayName(overrideName);
                    if (metadataStore.existsProjectName(display, metadataProjectId.get())) {
                        throw new DuplicateProjectNameException(display, "");
                    }
                    workspace().setProjectName(display);
                } else {
                    String current = workspace().projectName();
                    if (current == null || current.isBlank()
                            || "Untitled Project".equalsIgnoreCase(current.trim())) {
                        saveCoordinator.markLogicalDirty();
                        return new ProjectSaveResult(false, "NAME_REQUIRED", List.of(
                                "Enter a unique project name before saving."));
                    }
                    ProjectNameNormalizer.requireDisplayName(current);
                }
                Instant savedAt = lastSavedAt.get();
                if (updateCheckpoint) {
                    captureExplicitCheckpoint();
                    savedAt = Instant.now();
                }
                persistCompleteProjectState(updateCheckpoint, savedAt);
                durableNamedProject.set(true);
                if (updateCheckpoint) {
                    lastSavedAt.set(savedAt);
                    final Instant checkpointAt = savedAt;
                    LOG.log(System.Logger.Level.INFO, () ->
                            "Project checkpoint saved resume="
                                    + checkpointResumeRoute.get()
                                    + " graphKeys=" + signalsModel.workspaceKeys().size()
                                    + " viewport=" + graphInvestigation.viewportStart()
                                    + ".." + graphInvestigation.viewportEnd()
                                    + " lastSavedAt=" + checkpointAt);
                }
                markClean();
                if (updateCheckpoint) {
                    appendAudit(AuditEventType.PROJECT_SAVED, metadataProjectId.get(), "Project saved");
                }
                return new ProjectSaveResult(true, "Project saved", List.of());
            } catch (DuplicateProjectNameException | IllegalArgumentException ex) {
                saveCoordinator.markFailed(ex);
                throw ex;
            } catch (RuntimeException ex) {
                saveCoordinator.markFailed(ex);
                LOG.log(System.Logger.Level.ERROR, "Project save failed: "
                        + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
                return new ProjectSaveResult(false,
                        "Project could not be saved.\nYour source files were not modified.",
                        List.of(ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            }
        }
    }

    /** Snapshot live UI into the explicit checkpoint fields before durable write. */
    private void captureExplicitCheckpoint() {
        WorkspaceRoute live = lastMeaningfulWorkspace.get();
        if (live == null) {
            live = WorkspaceRoute.forPage(currentPage.get());
        }
        if (live == WorkspaceRoute.SOURCES
                || live == WorkspaceRoute.DECODE
                || live == WorkspaceRoute.SIGNALS
                || live == WorkspaceRoute.GRAPHS) {
            checkpointResumeRoute.set(live);
        }
        graphInvestigation.setSignalsDisplayMode(
                signalsModel.displayMode() == null ? "" : signalsModel.displayMode().name());
        graphInvestigation.setSignalsPrecision(
                signalsModel.precision() == null ? "" : signalsModel.precision().name());
    }

    public void renameProject(String newName) {
        String display = ProjectNameNormalizer.requireDisplayName(newName);
        if (metadataStore.existsProjectName(display, metadataProjectId.get())) {
            throw new DuplicateProjectNameException(display, "");
        }
        workspace().setProjectName(display);
        persistProjectCritical();
        durableNamedProject.set(true);
        markClean();
    }

    /**
     * Deletes a saved project by {@code project_id}. Never deletes original source files.
     * Production uses the target project's own SQLite (not the currently open store).
     */
    public ProjectDeleteResult deleteProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return new ProjectDeleteResult(false, "", List.of("Invalid project id."));
        }
        boolean deletingCurrent = isDurableNamedProject() && projectId.equals(metadataProjectId.get());
        if (deletingCurrent) {
            cancelProcessing();
            cancelAllInventories();
            abandonCurrentSession();
            processingResultRegistry.clear();
            signalsModel.clear();
            processingSelectionModel.clear();
            projectProcessingContext.clear();
            if (!injectedPersistentStore) {
                ensureCentralMetadataStore();
            }
        }
        if (!injectedPersistentStore) {
            ensureCentralMetadataStore();
        }
        ProjectDeletionService.Result result = projectDeletionService.deleteByProjectId(projectId);
        if (!injectedPersistentStore) {
            ProjectRootIndex.unregister(projectId);
        }
        boolean gone = savedProjects().stream().noneMatch(p -> projectId.equals(p.projectId()));
        if (deletingCurrent && (result.metadataDeleted() || gone)) {
            enterCleanEmptyWorkspace();
        }
        if (result.metadataDeleted() || gone) {
            noteProjectCountChanged(-1);
        }
        return new ProjectDeleteResult(
                result.metadataDeleted() || gone,
                result.projectName(),
                result.warnings());
    }

    private void enterCleanEmptyWorkspace() {
        abandonCurrentSession();
        resetRuntimeProject("Untitled Project");
        metadataProjectId.set(UUID.randomUUID().toString());
        metadataProjectCreatedAt.set(Instant.now());
        persistedProcessingRuns.set(List.of());
        durableNamedProject.set(false);
        lastMeaningfulWorkspace.set(null);
        checkpointResumeRoute.set(null);
        lastSavedAt.set(null);
        saveCoordinator.detach();
        activeProjectRoot = null;
        // Keep the central metadata store open so dashboard listing still works.
        // Do not persist untitled placeholder rows.
    }

    /** Cancel previous-project debounce and isolate later persist tasks from this session. */
    private void abandonCurrentSession() {
        saveCoordinator.cancelPendingAutosave();
        persistenceGeneration.incrementAndGet();
    }

    /**
     * Removes leftover decoded caches under the application home
     * ({@code <stellarai.home>/projects/<id>/signal-store}).
     * The Analysis Storage signal store is the durable catalog and is never deleted here.
     * Original source files are never touched.
     */
    private void discardDurableSignalCache(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        Path legacy = StellarAiPaths.signalStoreRoot(projectId).toAbsolutePath().normalize();
        if (isDurableAnalysisSignalStore(projectId, legacy)) {
            return;
        }
        deleteTreeBestEffort(legacy);
    }

    /** True when {@code candidate} is, or lies inside, the configured Analysis Storage artifact tree. */
    private static boolean isDurableAnalysisSignalStore(String projectId, Path candidate) {
        if (!AnalysisStorageRoots.isConfigured() || candidate == null) {
            return false;
        }
        Path durable = AnalysisStorageRoots.signalStoreRoot(projectId).toAbsolutePath().normalize();
        if (candidate.equals(durable)) {
            return true;
        }
        Path controlled = AnalysisStorageRoots.controlledRoot().orElse(null);
        return controlled != null && candidate.startsWith(controlled.toAbsolutePath().normalize());
    }

    private void markDirty() {
        saveCoordinator.markLogicalDirty();
    }

    private void markClean() {
        saveCoordinator.markClean();
    }

    public void flushPendingLogicalPersistence() {
        saveCoordinator.flushPendingAutosave();
        Runnable hook = persistenceFlushHook.get();
        if (hook != null) {
            hook.run();
        }
    }

    public void discardUnsavedLogicalChanges() {
        saveCoordinator.cancelPendingAutosave();
    }

    private void autosaveCompleteState(long generation, String projectId) {
        if (persistenceGeneration.get() != generation
                || projectId == null
                || !projectId.equals(metadataProjectId.get())
                || !Boolean.TRUE.equals(durableNamedProject.get())
                || saveCoordinator.autosaveSuspended()) {
            return;
        }
        synchronized (saveCoordinator.saveLock()) {
            if (persistenceGeneration.get() != generation
                    || !projectId.equals(metadataProjectId.get())
                    || !Boolean.TRUE.equals(durableNamedProject.get())
                    || saveCoordinator.autosaveSuspended()) {
                return;
            }
            saveCoordinator.beginSave();
            try {
                // Autosave / background persist must not overwrite the explicit Save checkpoint.
                persistCompleteProjectState(false, lastSavedAt.get());
                markClean();
            } catch (RuntimeException ex) {
                saveCoordinator.markFailed(ex);
                LOG.log(System.Logger.Level.ERROR, "Autosave failed: "
                        + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            }
        }
    }

    private void persistCompleteProjectState() {
        persistCompleteProjectState(false, lastSavedAt.get());
    }

    private void persistCompleteProjectState(boolean updateCheckpoint, Instant checkpointSavedAt) {
        metadataStore.runInTransaction(() -> {
            persistProjectCritical(updateCheckpoint, checkpointSavedAt);
            persistAllFilesCritical();
            for (SourceChannelMappingEntry mapping : definitionMatchingModel.allMappings()) {
                persistChannelMappingCritical(mapping.sourceChannelKey());
            }
            // Workspace/graph resume checkpoint is written only on explicit Save.
            if (updateCheckpoint) {
                persistGraphInvestigationCritical();
                persistAnalysisWorkspaceCritical();
                persistInvestigationWorkspaceCritical();
            }
        });
    }

    public List<ProcessingRunMetadata> persistedProcessingRuns() {
        return persistedProcessingRuns.get();
    }

    public Optional<ProcessingRunMetadata> latestPersistedProcessingRun() {
        return persistedProcessingRuns.get().stream().findFirst();
    }

    /**
     * Per-source wall clock for Processing Complete.
     * Newest run that touched a source wins. Sources absent from that run keep their last
     * valid window (Add More Measurement). A source present in a newer run without a valid
     * window stays unavailable — historical SUCCESS is never mixed into an untimed reprocess.
     */
    public Map<String, ProcessingJobTiming.SourceWindow> persistedSourceTiming() {
        ProjectMetadataStore store = metadataStore;
        String projectId = currentProjectId();
        if (store == null || projectId == null || projectId.isBlank()) {
            return Map.of();
        }
        List<ProcessingRunMetadata> runs = persistedProcessingRuns.get();
        if (runs == null || runs.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, ProcessingJobTiming.SourceWindow> windows = ProcessingJobTiming.sourceWindows(
                    store.listProcessingJobs(projectId), runs);
            java.util.Set<String> living = workspace().measurementFiles().stream()
                    .map(WorkspaceFile::id)
                    .collect(java.util.stream.Collectors.toSet());
            windows.entrySet().removeIf(entry -> !living.contains(entry.getKey()));
            return windows;
        } catch (RuntimeException unavailable) {
            return Map.of();
        }
    }

    /** Source ids that have a {@code processing_jobs} row in the latest persisted run. */
    public Set<String> latestProcessingRunSourceIds() {
        ProjectMetadataStore store = metadataStore;
        String projectId = currentProjectId();
        Optional<ProcessingRunMetadata> run = latestPersistedProcessingRun();
        if (store == null || projectId == null || projectId.isBlank()
                || run.isEmpty() || run.get().runId() == null || run.get().runId().isBlank()) {
            return Set.of();
        }
        String runId = run.get().runId();
        try {
            Set<String> ids = new java.util.LinkedHashSet<>();
            for (ProcessingJobMetadata job : store.listProcessingJobs(projectId)) {
                if (job != null && runId.equals(job.runId())
                        && job.sourceId() != null && !job.sourceId().isBlank()) {
                    ids.add(job.sourceId());
                }
            }
            return ids;
        } catch (RuntimeException unavailable) {
            return Set.of();
        }
    }

    public record ProjectOpenResult(boolean opened, String projectName, List<String> warnings) {
        public ProjectOpenResult {
            projectName = projectName == null ? "" : projectName;
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
        }
    }

    /**
     * Reopen a metadata-backed project without reparsing unchanged measurement files.
     *
     * <p><b>Root cause of empty Signals/Processing after restart:</b> SQLite restored files,
     * inventories and mappings, but {@link #resetRuntimeProject} cleared the processing registry
     * and signal catalog, while lossless samples lived only in JVM temp dirs. Hydration now
     * reopens project-scoped signal stores from artifact manifests and rebuilds Processing via
     * the existing {@link #hydrateProcessingWorkspace()} path (no duplicate plan generator).</p>
     */
    public ProjectOpenResult openSavedProject(String projectId) {
        return openSavedProject(projectId, 0L, () -> false);
    }

    /**
     * Reopen a saved project. {@code cancelled} is polled between artifact restores so a newer
     * switch does not keep publishing the project the user already left.
     */
    public ProjectOpenResult openSavedProject(String projectId, long generation, BooleanSupplier cancelled) {
        openSwitchGeneration.set(generation);
        openSwitchCancelled.set(cancelled == null ? () -> false : cancelled);
        long started = ProjectSwitchTrace.now();
        try {
            ProjectOpenResult result = openSavedProjectBody(projectId);
            ProjectSwitchTrace.stage(generation, "open-complete", started, openArtifactCount(), false);
            Runnable beforePublish = projectOpenBeforePublishPauseForTest;
            if (beforePublish != null) {
                beforePublish.run();
            }
            return result;
        } catch (SwitchSuperseded superseded) {
            ProjectSwitchTrace.stage(generation, "open-cancelled", started, openArtifactCount(), true);
            return new ProjectOpenResult(false, "", List.of("SWITCH_SUPERSEDED"));
        } finally {
            openSwitchGeneration.remove();
            openSwitchCancelled.remove();
        }
    }

    public void submitProjectSwitch(Runnable task) {
        if (task != null) {
            projectSwitchExecutor.execute(task);
        }
    }

    private void throwIfSwitchSuperseded() {
        BooleanSupplier cancelled = openSwitchCancelled.get();
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new SwitchSuperseded();
        }
    }

    private int openArtifactCount() {
        return processingResultRegistry.all().size();
    }

    private static final class SwitchSuperseded extends RuntimeException {
        private SwitchSuperseded() {
        }
    }

    private ProjectOpenResult openSavedProjectBody(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return new ProjectOpenResult(false, "", List.of("Invalid project id."));
        }
        SavedProjectSnapshot snapshot;
        boolean copiedFromLegacy = false;
        try {
            LoadOutcome loaded = loadProjectSnapshot(projectId);
            if (loaded.snapshot() == null) {
                return new ProjectOpenResult(false, "", List.of("Saved project was not found."));
            }
            snapshot = loaded.snapshot();
            copiedFromLegacy = loaded.copiedFromLegacy();
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Cannot open saved project " + projectId + ": " + ex.getMessage());
            return new ProjectOpenResult(false, "", List.of("Could not read the saved project: " + ex.getMessage()));
        }

        if (openHydrationFailureForTest != null) {
            RuntimeException injected = openHydrationFailureForTest;
            openHydrationFailureForTest = null;
            LOG.log(System.Logger.Level.ERROR, "Open hydration failed for " + projectId
                    + "; saved metadata was not deleted: " + injected.getMessage());
            return new ProjectOpenResult(false, snapshot.project().name(),
                    List.of("Could not open project: " + injected.getMessage()));
        }

        throwIfSwitchSuperseded();
        abandonCurrentSession();
        List<String> warnings;
        try {
            warnings = hydrateOpenedProject(snapshot);
        } catch (SwitchSuperseded superseded) {
            throw superseded;
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.ERROR, "Open failed for " + projectId
                    + "; saved metadata was not deleted: "
                    + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            enterCleanEmptyWorkspace();
            return new ProjectOpenResult(false, snapshot.project().name(),
                    List.of("Could not open project: "
                            + (ex.getMessage() == null ? ex.toString() : ex.getMessage())));
        }
        if (!projectId.equals(metadataProjectId.get()) || !isDurableNamedProject()) {
            LOG.log(System.Logger.Level.ERROR,
                    "Open completed without activating requested project " + projectId);
            enterCleanEmptyWorkspace();
            return new ProjectOpenResult(false, snapshot.project().name(),
                    List.of("Could not open project: active project did not match the requested id."));
        }
        if (copiedFromLegacy) {
            persistCompleteProjectState();
        }
        appendAudit(AuditEventType.PROJECT_OPENED, projectId, snapshot.project().name());
        return new ProjectOpenResult(true, snapshot.project().name(), warnings);
    }

    private List<String> hydrateOpenedProject(SavedProjectSnapshot snapshot) {
        long stage = ProjectSwitchTrace.now();
        throwIfSwitchSuperseded();
        Runnable pause = projectOpenPauseForTest;
        if (pause != null) {
            pause.run();
        }
        throwIfSwitchSuperseded();
        resetRuntimeProject(snapshot.project().name());
        metadataProjectId.set(snapshot.project().projectId());
        refreshSignalCacheFromStore();
        metadataProjectCreatedAt.set(snapshot.project().createdAt());
        persistedProcessingRuns.set(snapshot.processingRuns());
        durableNamedProject.set(true);
        WorkspaceRoute checkpoint = parseLastWorkspace(snapshot.project().lastWorkspace());
        checkpointResumeRoute.set(checkpoint);
        lastMeaningfulWorkspace.set(checkpoint);
        Instant savedAt = snapshot.project().lastSavedAt() != null
                ? snapshot.project().lastSavedAt()
                : snapshot.project().updatedAt();
        lastSavedAt.set(savedAt);
        saveCoordinator.attach(snapshot.project().projectId());

        List<String> warnings = new java.util.ArrayList<>();
        Map<String, WorkspaceFile> restored = new java.util.LinkedHashMap<>();
        for (ProjectFileMetadata stored : snapshot.files()) {
            FileKind kind = enumValue(FileKind.class, stored.kind(), FileKind.UNKNOWN);
            Path path;
            try { path = Path.of(stored.path()).toAbsolutePath().normalize(); }
            catch (RuntimeException invalidPath) {
                warnings.add("Invalid saved path: " + stored.path());
                continue;
            }
            FileKind productKind = FileKindDetector.productSourceKind(path);
            if (productKind != null) {
                kind = productKind;
            }
            WorkspaceFile file = WorkspaceFile.restore(stored.fileId(), path, kind, stored.sizeBytes());
            FileValidationStatus savedStatus = enumValue(
                    FileValidationStatus.class, stored.validationStatus(),
                    kind.implemented() ? FileValidationStatus.READY : FileValidationStatus.UNSUPPORTED);
            String validationMessage = stored.validationMessage();
            if (!Files.isRegularFile(path)) {
                savedStatus = FileValidationStatus.INVALID;
                validationMessage = "Saved source is no longer available at " + path;
                warnings.add("Missing file: " + path);
                if (kind.measurement()) {
                    sourceAvailabilityModel.markUnavailable(ProjectSourceId.of(stored.fileId()));
                }
            }
            file.setStatus(savedStatus, validationMessage);
            if (Files.isRegularFile(path) && kind.measurement()) {
                applyDetectedFormat(file, FileKindDetector.inspect(path));
            }
            file.setAssociatedDefinitionId(stored.associatedDefinitionId());
            file.setMf4FinalizationLabel(stored.finalizationLabel());
            file.setRecoveryCandidate(stored.recoveryCandidate());
            workspace().addFile(file);
            restored.put(file.id(), file);
        }

        // Restore selected source/definition identities only after every file exists in the workspace.
        for (ProjectFileMetadata stored : snapshot.files()) {
            if (!restored.containsKey(stored.fileId())) continue;
            if (stored.selectedMeasurement()) workspace().selectMeasurement(stored.fileId());
            if (stored.selectedDefinition()) workspace().selectDefinition(stored.fileId());
        }

        // Restore persisted inventory metadata. Schema v2 also restores unique CAN message IDs,
        // allowing DBC recommendations to be rebuilt without re-reading MF4/ASC files.
        for (WorkspaceFile file : workspace().measurementFiles()) {
            CanSourceInventory inventory = snapshot.inventories().get(file.id());
            if (inventory != null) {
                sourceInventoryModel.put(file.id(), inventory);
                inventoryCoordinator.registerSource(file.projectSourceId(), inventoryJobStateFor(inventory));
                if (inventory.status() == CanInventoryStatus.INVENTORY_READY && inventory.channelDiscoveryComplete()) {
                    definitionMatchingCoordinator.ensureReadyInventory(file.projectSourceId(), inventory);
                }
                if (inventory.totalUniqueMessageIds() > 0L
                        && inventory.channels().stream().allMatch(ch -> ch.messages().isEmpty())) {
                    warnings.add(file.fileName() + " inventory was saved by the older DB schema; re-inspect it to rebuild detailed CAN-ID metadata.");
                }
            } else {
                inventoryCoordinator.registerSource(file.projectSourceId(),
                        file.kind().implemented() && file.status() != FileValidationStatus.UNSUPPORTED
                                ? ProjectInventoryJobState.ADDED
                                : ProjectInventoryJobState.UNSUPPORTED);
            }
        }

        // Reparse only the small DBC definition files so candidates/comparisons are available.
        // Measurement sources are NOT reparsed here.
        ProjectSwitchTrace.stage(openSwitchGeneration.get(), "metadata", stage, 0, false);
        stage = ProjectSwitchTrace.now();
        for (WorkspaceFile definition : workspace().definitionFiles()) {
            throwIfSwitchSuperseded();
            if (definition.kind() == FileKind.DEFINITION_DBC && Files.isRegularFile(definition.path())) {
                registerDefinitionForMatching(definition);
            }
        }
        ProjectSwitchTrace.stage(openSwitchGeneration.get(), "dbc", stage, 0, false);
        stage = ProjectSwitchTrace.now();

        // Persisted user decisions win over any recomputed recommendation state.
        List<SourceChannelMappingEntry> persistedMappings = snapshot.channelMappings().stream()
                .map(this::restoreMappingEntry)
                .filter(Objects::nonNull)
                .toList();
        definitionMatchingModel.putMappings(persistedMappings);
        for (SourceChannelMappingEntry mapping : persistedMappings) {
            mapping.mappedDefinitionId().ifPresent(def -> workingDbcSelections.put(mapping.sourceChannelKey(), def));
        }

        workspace().selectedMeasurement().ifPresent(file -> sourceInventoryModel.focus(file.id()));
        refreshDefinitionMatching();
        // refreshDefinitionMatching can recompute focused presentation; overlay saved decisions again.
        definitionMatchingModel.putMappings(persistedMappings);
        restoreProcessingStatus(snapshot.processingRuns());

        rememberProcessedSourceFingerprints(snapshot.processingArtifacts());
        rememberSourceProcessingJobs(metadataStore.listProcessingJobs(snapshot.project().projectId()));
        // Legacy project-local signal-store under ~/.stellarai/projects/... only — never Analysis Storage.
        discardDurableSignalCache(snapshot.project().projectId());
        ProcessedProjectHydrator.HydrationResult hydration = new ProcessedProjectHydrator(this)
                .verifySnapshot(snapshot);
        warnings.addAll(hydration.warnings());

        // Rebuild Processing from restored Decision state using the ONE plan assembler path.
        // Prefer durable Analysis Storage artifacts over reparsing MF4 when valid.
        if (canProceedFromDecisionWorkspace()) {
            prepareProcessingFromDecisionWorkspace();
            ProjectProcessingPlan plan = processingSelectionModel.reviewedPlan();
            if (plan != null && !plan.isEmpty()) {
                warnings.addAll(seedReusableArtifactsFromMetadata(plan));
                restoreUnpersistedJobFailures(plan);
            }
            hydrateProcessingWorkspace();
        }
        // BD-24: DAT/LOG artifacts are stored under a blank definition id and are not CAN plan
        // jobs. Seeding them only when the catalog is still empty drops them as soon as an
        // ASC/BLF/MF4 plan has published signals. Re-read manifests so a plan seed that marked
        // an artifact stale is not opened again from the snapshot captured at the start of open.
        SavedProjectSnapshot refreshedArtifacts = metadataStore == null
                ? snapshot
                : metadataStore.loadProject(snapshot.project().projectId()).orElse(snapshot);
        warnings.addAll(seedReusableArtifactsFromPersistedManifests(refreshedArtifacts.processingArtifacts()));
        ProjectSwitchTrace.stage(openSwitchGeneration.get(), "artifacts", stage, openArtifactCount(), false);
        throwIfSwitchSuperseded();
        stage = ProjectSwitchTrace.now();
        republishSignalsFromRegistry();

        // republishSignalsFromRegistry already applies the checkpoint once the catalog exists.
        // A second full restore rewrote the same keys and blocked the UI thread again.
        if (!signalsModel.workspaceKeys().isEmpty() && graphInvestigation.viewportInitialized()) {
            ProjectSwitchTrace.stage(openSwitchGeneration.get(), "graph-restore-skipped", stage, openArtifactCount(), false);
        } else {
            restoreGraphInvestigation();
        }
        ProjectSwitchTrace.stage(openSwitchGeneration.get(), "graph", stage, openArtifactCount(), false);
        restoreAnalysisWorkspace();
        restoreDiagnosticWorkspace();
        restoreInvestigationWorkspace();
        recoverInterruptedJobs(snapshot.project().projectId());

        return List.copyOf(warnings);
    }

    /**
     * Jobs left RUNNING were not cancelled by the user. Mark them INTERRUPTED so reopen can
     * offer checkpoint resume instead of pretending the run is still live.
     */
    private void recoverInterruptedJobs(String projectId) {
        if (metadataStore == null || projectId == null || projectId.isBlank()) {
            return;
        }
        int marked = new LocalWorkerSupervisor(metadataStore).markRunningJobsInterrupted(
                projectId, "Processing interrupted");
        finalizeOrphanRunningRuns(null, "Processing interrupted");
        if (marked > 0) {
            warningsSafe("Processing interrupted — Worker stopped unexpectedly. Recovery available from checkpoint.");
        }
    }

    private void warningsSafe(String message) {
        LOG.log(System.Logger.Level.WARNING, message);
    }

    /** Publishes catalog/store handles into Signals after durable reopen (lazy sample queries). */
    private void republishSignalsFromRegistry() {
        List<IndependentJobResult> successful = processingResultRegistry.all().stream()
                .filter(IndependentJobResult::successful)
                .filter(result -> result.catalogHandle().isPresent())
                .toList();
        if (successful.isEmpty()) {
            if (!analysisStorageService.isValid() && !persistedProcessingRuns.get().isEmpty()) {
                LOG.log(System.Logger.Level.INFO, "Analysis storage unavailable — processed signals not loaded");
            }
            return;
        }
        if (isDirectProcessingBatch(successful) && successful.size() > 1) {
            publishDirectMeasurementCatalog(successful);
        } else {
            new QuickDecodeSignalsViewAssembler()
                    .assembleResults(successful, id -> workspace().findFile(id).map(WorkspaceFile::fileName).orElse(id))
                    .ifPresent(view -> signalsModel.replaceCatalog(view.store(), view.hint(), view.context(), true));
        }
        // Catalog may arrive after the first restore attempt (empty catalog → 0 resolved keys).
        restoreGraphInvestigationIfWorkspaceEmpty();
        restoreAnalysisWorkspace();
        restoreDiagnosticWorkspace();
        refreshSignalCacheFromStore();
    }

    /**
     * Re-applies the saved Graph checkpoint once the catalog exists. Skipped when the user
     * already has workspace membership (live session) or when Clear View saved an empty set
     * after a successful restore (workspace stays empty by design).
     */
    void restoreGraphInvestigationIfWorkspaceEmpty() {
        if (!signalsModel.workspaceKeys().isEmpty()) {
            return;
        }
        if (signalsModel.catalog() == null || signalsModel.catalog().entries().isEmpty()) {
            return;
        }
        if (!hasPersistedGraphTraces()) {
            return;
        }
        restoreGraphInvestigation();
    }

    private boolean hasPersistedGraphTraces() {
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        try {
            return metadataStore.findGraphInvestigation(projectId)
                    .map(meta -> !GraphInvestigationCodec.decodeTraces(meta.tracesBlob()).isEmpty())
                    .orElse(false);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void resetRuntimeProject(String name) {
        cancelProcessing();
        cancelAllInventories();
        session.newProject(name);
        processingModel.clear();
        signalsModel.clear();
        sourceInventoryModel.clear();
        inventoryCoordinator.clear();
        definitionMatchingCoordinator.clear();
        channelSelectionModel.clear();
        projectProcessingContext.clear();
        processingSelectionModel.clear();
        processingResultRegistry.clear();
        sourceAliasRegistry.clear();
        sourceAvailabilityModel.clear();
        lastProcessedSourceFingerprints.clear();
        lastSourceProcessingStatus.clear();
        lastMeaningfulWorkspace.set(null);
        checkpointResumeRoute.set(null);
        graphInvestigation.beginSession();
        analysisScopeRef.set(null);
        analysisSelection.reset();
        workingDbcSelections.clearAll();
        quickDecodeSelectionModel.reset();
        decisionWorkspaceMode.set(DecisionWorkspaceMode.QUICK_DECODE);
        cachedDbcPath.set(null);
        cachedDbcDatabase.set(null);
        lastValidationIssues.set(List.of());
        currentPage.set(NavigationPage.HOME);
    }

    private SourceChannelMappingEntry restoreMappingEntry(ChannelMappingMetadata stored) {
        try {
            SourceChannelKey key = SourceChannelKey.of(ProjectSourceId.of(stored.sourceId()), stored.channelId());
            ChannelMappingStatus status = enumValue(ChannelMappingStatus.class, stored.status(), ChannelMappingStatus.UNMAPPED);
            ChannelRecommendationKind kind = enumValue(
                    ChannelRecommendationKind.class, stored.recommendationKind(), ChannelRecommendationKind.NO_MATCH);
            DefinitionSourceId recommended = optionalDefinitionId(stored.recommendedDefinitionId());
            DefinitionSourceId mapped = optionalDefinitionId(stored.mappedDefinitionId());
            return new SourceChannelMappingEntry(
                    key, status, kind, recommended, definitionDisplayName(recommended),
                    mapped, definitionDisplayName(mapped), stored.observedUniqueMessageCount(),
                    stored.matchedUniqueMessageCount(), stored.coverageRatio());
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Skipping invalid persisted mapping: " + ex.getMessage());
            return null;
        }
    }

    private DefinitionSourceId optionalDefinitionId(String value) {
        return value == null || value.isBlank() ? null : DefinitionSourceId.of(value);
    }

    private String definitionDisplayName(DefinitionSourceId id) {
        if (id == null) return "";
        return workspace().findFile(id.value()).map(WorkspaceFile::fileName).orElse(id.value());
    }

    private static ProjectInventoryJobState inventoryJobStateFor(CanSourceInventory inventory) {
        if (inventory == null) return ProjectInventoryJobState.ADDED;
        return switch (inventory.status()) {
            case INVENTORY_READY -> ProjectInventoryJobState.READY;
            case FAILED -> ProjectInventoryJobState.FAILED;
            case CANCELLED -> ProjectInventoryJobState.CANCELLED;
            case FILE_ACCEPTED -> ProjectInventoryJobState.ADDED;
            case DISCOVERING_CHANNELS, CHANNELS_DISCOVERED, INDEXING_MESSAGE_IDS -> ProjectInventoryJobState.REQUIRES_ATTENTION;
        };
    }

    private void restoreProcessingStatus(List<ProcessingRunMetadata> runs) {
        ProcessingRunMetadata latest = runs == null || runs.isEmpty() ? null : runs.get(0);
        if (latest == null) {
            workspace().setProcessingStatus(ProjectProcessingStatus.IDLE);
            return;
        }
        ProjectProcessingStatus status = switch (latest.status().toUpperCase(java.util.Locale.ROOT)) {
            case "COMPLETED", "SUCCESS", "SUCCEEDED" -> ProjectProcessingStatus.COMPLETED;
            case "COMPLETED_WITH_WARNINGS" -> ProjectProcessingStatus.COMPLETED_WITH_WARNINGS;
            case "COMPLETED_WITH_FAILURES", "FAILED" -> ProjectProcessingStatus.FAILED;
            case "CANCELLED", "CANCELED" -> ProjectProcessingStatus.CANCELLED;
            case "RUNNING" -> ProjectProcessingStatus.IDLE; // prior process cannot still be running
            default -> ProjectProcessingStatus.IDLE;
        };
        workspace().setProcessingStatus(status);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException ex) { return fallback; }
    }

    /** Stable Source A/B/C… presentation aliases for the current project session. */
    public SourceAliasRegistry sourceAliasRegistry() {
        return sourceAliasRegistry;
    }

    public QuickDecodeSelectionModel quickDecodeSelectionModel() {
        return quickDecodeSelectionModel;
    }

    public QuickDecodeDecisionResolver quickDecodeDecisionResolver() {
        return quickDecodeDecisionResolver;
    }

    public DecisionWorkspaceMode decisionWorkspaceMode() {
        return decisionWorkspaceMode.get();
    }

    public void setDecisionWorkspaceMode(DecisionWorkspaceMode mode) {
        if (mode != null) {
            decisionWorkspaceMode.set(mode);
        }
    }

    /**
     * Mode captured when the current processing plan was prepared. The processing workspace model
     * owns this provenance so a later Decision Workspace toggle cannot re-interpret old results.
     */
    public DecisionWorkspaceMode processingDecisionMode() {
        return processingSelectionModel.decisionOrigin();
    }

    public WorkspaceFile addPath(Path path) {
        return addPath(path, PlatformUi.NOOP);
    }

    /**
     * @param uiUpdater marshals inventory progress onto the JavaFX thread
     */
    public WorkspaceFile addPath(Path path, Consumer<Runnable> uiUpdater) {
        Objects.requireNonNull(path, "path");
        Consumer<Runnable> ui = uiUpdater == null ? PlatformUi.NOOP : uiUpdater;
        AddPathOutcome outcome = addPathInternal(path);
        if (outcome.added() != null) {
            autoAssociateSingletonPair();
            if (outcome.added().kind().definition()) {
                registerDefinitionForMatching(outcome.added());
            }
            maybeStartInventoryFor(outcome.added(), ui);
        }
        return outcome.file();
    }

    /**
     * MF-2: add multiple measurement sources without launching N inventory tasks.
     * Inventory runs only for the single active/selected measurement (compat path).
     */
    public ProjectFilesAddResult addMeasurementPaths(List<Path> paths, Consumer<Runnable> uiUpdater) {
        Objects.requireNonNull(paths, "paths");
        Consumer<Runnable> ui = uiUpdater == null ? PlatformUi.NOOP : uiUpdater;
        ProjectFilesAddResult.Builder result = ProjectFilesAddResult.builder();
        for (Path path : paths) {
            if (path == null) {
                continue;
            }
            Optional<WorkspaceFile> existing = workspace().findMeasurementByPath(path);
            if (existing.isPresent()) {
                result.duplicate(existing.get().fileName() + " is already added to this project.");
                continue;
            }
            FileKindDetector.Inspection inspection = FileKindDetector.inspect(path);
            FileKind kind = inspection.kind();
            if (kind == FileKind.UNKNOWN || !kind.measurement()) {
                String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
                String reason = inspection.detected() == null
                        ? "unsupported measurement format"
                        : inspection.detected().unknownMessage();
                result.skipped(name + " — " + reason);
                continue;
            }
            AddPathOutcome outcome = addPathInternal(path);
            if (outcome.added() != null) {
                result.added(outcome.added());
                appendAudit(AuditEventType.SOURCE_ADDED, outcome.added().id(), outcome.added().fileName());
            } else {
                result.skipped(outcome.file().fileName() + " — " + outcome.file().validationMessage());
            }
        }
        autoAssociateSingletonPair();
        ProjectFilesAddResult built = result.build();
        for (WorkspaceFile added : built.added()) {
            maybeStartInventoryFor(added, ui);
        }
        return built;
    }

    /**
     * MF-2: add multiple definition sources. Does not invent Source-to-DBC mappings.
     */
    public ProjectFilesAddResult addDefinitionPaths(List<Path> paths) {
        Objects.requireNonNull(paths, "paths");
        ProjectFilesAddResult.Builder result = ProjectFilesAddResult.builder();
        for (Path path : paths) {
            if (path == null) {
                continue;
            }
            Optional<WorkspaceFile> existing = workspace().findDefinitionByPath(path);
            if (existing.isPresent()) {
                result.duplicate(existing.get().fileName() + " is already added to this project.");
                continue;
            }
            FileKind kind = FileKindDetector.detect(path);
            if (kind == FileKind.UNKNOWN || !kind.definition()) {
                String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
                result.skipped(name + " — unsupported definition format");
                continue;
            }
            if (kind != FileKind.DEFINITION_DBC) {
                String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
                result.skipped(name + " — " + kind.displayName() + " is not yet implemented");
                continue;
            }
            String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
            DbcDatabase parsed;
            try {
                parsed = dbcParser.parse(path);
            } catch (DbcParseException ex) {
                String detail = ex.getMessage() == null || ex.getMessage().isBlank()
                        ? "This file is not a valid DBC."
                        : ex.getMessage();
                result.skipped(name + " — " + detail);
                continue;
            }
            AddPathOutcome outcome = addPathInternal(path);
            if (outcome.added() != null) {
                List<String> warnings = DbcParser.importWarnings(parsed);
                if (!warnings.isEmpty()) {
                    outcome.added().setStatus(
                            FileValidationStatus.READY_WITH_WARNINGS,
                            String.join(System.lineSeparator(), warnings));
                    for (String warning : warnings) {
                        result.warning(name + " — " + warning);
                    }
                }
                result.added(outcome.added());
            }
        }
        autoAssociateSingletonPair();
        ProjectFilesAddResult built = result.build();
        for (WorkspaceFile added : built.added()) {
            registerDefinitionForMatching(added);
        }
        return built;
    }

    private static void applyDetectedFormat(WorkspaceFile file, FileKindDetector.Inspection inspection) {
        if (file == null || inspection == null) {
            return;
        }
        file.setDetectedFormatLabel(inspection.uiLabel());
        if (MeasurementSourceCapability.supportsDirectProcessing(file.kind())) {
            if (inspection.recognized() && inspection.parserSupported()) {
                file.setStatus(FileValidationStatus.READY, "Direct processing. DBC is not required.");
            } else if (inspection.recognized() && inspection.detected().content() != null) {
                file.setStatus(
                        FileValidationStatus.UNSUPPORTED,
                        inspection.detected().content().unsupportedVersionMessage());
            } else {
                String message = inspection.detected() == null
                        ? "StellarAI could not determine the measurement structure automatically."
                        : inspection.detected().unknownMessage();
                file.setStatus(FileValidationStatus.UNSUPPORTED, message);
            }
            return;
        }
        if (inspection.recognized()
                && !inspection.parserSupported()
                && inspection.detected().content() != null) {
            file.setStatus(
                    FileValidationStatus.UNSUPPORTED,
                    inspection.detected().content().unsupportedVersionMessage());
        }
    }

    private AddPathOutcome addPathInternal(Path path) {
        FileKindDetector.Inspection inspection = FileKindDetector.inspect(path);
        FileKind kind = inspection.kind();
        long size = 0L;
        try {
            if (Files.isRegularFile(path)) {
                size = Files.size(path);
            }
        } catch (Exception ignored) {
            // size remains 0
        }
        WorkspaceFile file = new WorkspaceFile(path, kind, size);
        if (!kind.implemented()) {
            file.setStatus(FileValidationStatus.UNSUPPORTED, "Not yet implemented");
        }
        if (kind == FileKind.UNKNOWN) {
            String message = inspection.detected() == null
                    ? "Unknown file type"
                    : inspection.detected().unknownMessage();
            file.setStatus(FileValidationStatus.UNSUPPORTED, message);
            return new AddPathOutcome(file, null);
        }
        applyDetectedFormat(file, inspection);
        if (!kind.measurement() && !kind.definition()) {
            return new AddPathOutcome(file, null);
        }
        if (kind.measurement()) {
            Optional<WorkspaceFile> existing = workspace().findMeasurementByPath(path);
            if (existing.isPresent()) {
                return new AddPathOutcome(existing.get(), null);
            }
        }
        if (kind.definition()) {
            Optional<WorkspaceFile> existing = workspace().findDefinitionByPath(path);
            if (existing.isPresent()) {
                return new AddPathOutcome(existing.get(), null);
            }
        }
        WorkspaceFile added = workspace().addFile(file);
        return new AddPathOutcome(added, added);
    }

    private void maybeStartInventoryFor(WorkspaceFile file, Consumer<Runnable> ui) {
        if (file == null || !file.kind().measurement()) {
            return;
        }
        if (!file.kind().implemented() || file.status() == FileValidationStatus.UNSUPPORTED) {
            inventoryCoordinator.registerSource(file.projectSourceId(), ProjectInventoryJobState.UNSUPPORTED);
            return;
        }
        if (MeasurementSourceCapability.supportsDirectProcessing(file.kind())) {
            inventoryCoordinator.registerSource(file.projectSourceId(), ProjectInventoryJobState.READY);
            return;
        }
        inventoryCoordinator.registerSource(file.projectSourceId(), ProjectInventoryJobState.ADDED);
        // Coordinator skips READY / WAITING / INDEXING — no re-index of healthy sources.
        startSourceInventory(file, ui);
    }

    private record AddPathOutcome(WorkspaceFile file, WorkspaceFile added) {
    }

    /**
     * Command: when exactly one measurement and one DBC exist, select and associate them.
     * Does not guess when multiple DBCs or measurements are present.
     * Invoke only from project state-change paths (add/remove/select), never from UI queries.
     */
    public void autoAssociateSingletonPair() {
        List<WorkspaceFile> directOnly = workspace().measurementFiles().stream()
                .filter(f -> MeasurementSourceCapability.supportsDirectProcessing(f.kind()))
                .toList();
        if (directOnly.size() == 1 && workspace().measurementFiles().size() == 1) {
            workspace().selectMeasurement(directOnly.get(0).id());
        }
        List<WorkspaceFile> measurements = workspace().measurementFiles().stream()
                .filter(f -> f.kind() == FileKind.MEASUREMENT_ASC || f.kind() == FileKind.MEASUREMENT_MF4)
                .toList();
        List<WorkspaceFile> dbcs = workspace().definitionFiles().stream()
                .filter(f -> f.kind() == FileKind.DEFINITION_DBC)
                .toList();
        if (measurements.size() == 1) {
            workspace().selectMeasurement(measurements.get(0).id());
        }
        if (dbcs.size() == 1) {
            workspace().selectDefinition(dbcs.get(0).id());
        }
        if (measurements.size() == 1 && dbcs.size() == 1) {
            measurements.get(0).setAssociatedDefinitionId(dbcs.get(0).id());
        }
        refreshDefinitionMatching();
        persistAllFiles();
    }

    public boolean removeFile(String fileId) {
        Optional<WorkspaceFile> existing = fileId == null ? Optional.empty() : workspace().findFile(fileId);
        if (fileId != null) {
            inventoryCoordinator.cancelAndForget(ProjectSourceId.of(fileId));
            if (existing.isPresent() && existing.get().kind().measurement()) {
                definitionMatchingCoordinator.onInventoryRemoved(ProjectSourceId.of(fileId));
            }
            if (existing.isPresent() && existing.get().kind().definition()) {
                definitionMatchingCoordinator.unregisterDefinition(DefinitionSourceId.of(fileId));
            }
        }
        sourceInventoryModel.remove(fileId);
        boolean removed = workspace().removeFile(fileId);
        if (removed && existing.isPresent() && existing.get().kind().measurement()) {
            java.util.List<String> living = workspace().measurementFiles().stream()
                    .map(WorkspaceFile::id)
                    .toList();
            processingSelectionModel.retainLivingSources(living);
            forgetRemovedMeasurement(fileId, existing.get());
        }
        // Removal is a state-change command: re-evaluate singleton association + focused matching.
        autoAssociateSingletonPair();
        if (removed && fileId != null) metadataWrite(() -> metadataStore.deleteFile(metadataProjectId.get(), fileId));
        return removed;
    }

    /**
     * Removes one measurement from the active project view without reprocessing remaining
     * files or opening the dropped MF4. Historical ledger rows may remain; they are not
     * shown as current sources/results.
     */
    private void forgetRemovedMeasurement(String fileId, WorkspaceFile removed) {
        processingResultRegistry.removeSource(fileId);
        processingSelectionModel.forgetRemovedSource(fileId);
        lastSourceProcessingStatus.remove(fileId);
        lastProcessedSourceFingerprints.remove(fileId);
        graphInvestigation.forgetSource(fileId);
        signalsModel.resetMeasurementFilterIfGone(fileId);
        if (channelSelectionModel.sourceFileId().orElse("").equals(fileId)) {
            channelSelectionModel.clear();
        }
        AnalysisScope scope = analysisScopeRef.get();
        if (scope != null && scope.isSingle()
                && scope.measurement().map(ctx -> fileId.equals(ctx.sourceId())).orElse(false)) {
            List<WorkspaceFile> remaining = workspace().measurementFiles();
            if (remaining.size() == 1) {
                WorkspaceFile next = remaining.getFirst();
                workspace().selectMeasurement(next.id());
                analysisScopeRef.set(AnalysisScope.single(next.id(), next.fileName()));
            } else {
                workspace().selectMeasurement(null);
                analysisScopeRef.set(AnalysisScope.allMeasurements(remaining.size()));
            }
        } else if (analysisScopeRef.get() != null && analysisScopeRef.get().isAll()) {
            analysisScopeRef.set(AnalysisScope.allMeasurements(workspaceMeasurementCount()));
        }
        if (processingSelectionModel.canNavigateAwayFromExecution()) {
            ProcessingPlanInputs inputs = new ProcessingPlanInputAssembler().assemble(this);
            processingSelectionModel.rebuildReviewedPlanForSources(inputs, activeMeasurementSourceIds());
            processingSelectionModel.hydrateFromRegistry(inputs, processingResultRegistry);
        }
        List<IndependentJobResult> remainingSuccessful = processingResultRegistry.all().stream()
                .filter(IndependentJobResult::successful)
                .filter(result -> result.catalogHandle().isPresent())
                .toList();
        if (remainingSuccessful.isEmpty()) {
            signalsModel.replaceCatalog(null, null, null, true);
        } else {
            republishSignalsFromRegistry();
        }
        LOG.log(System.Logger.Level.INFO, () -> "Removed measurement from active project source="
                + fileId
                + " remainingSources=" + workspace().measurementFiles().size()
                + " remainingJobs=" + processingResultRegistry.all().size()
                + (removed == null ? "" : " file=" + removed.fileName()));
    }

    public void selectMeasurement(String fileId) {
        workspace().selectMeasurement(fileId);
        if (fileId != null) {
            WorkspaceFile selected = workspace().selectedMeasurement().orElse(null);
            if (selected != null) {
                analysisScopeRef.set(AnalysisScope.single(selected.id(), selected.fileName()));
            } else {
                analysisScopeRef.set(AnalysisScope.single(fileId, fileId));
            }
            sourceInventoryModel.focus(fileId);
        } else {
            analysisScopeRef.set(null);
        }
        refreshDefinitionMatching();
        persistAllFiles();
    }

    public void selectDefinition(String fileId) {
        workspace().selectDefinition(fileId);
        associateDefinitionWithSelectedMeasurement(fileId);
        if (fileId == null) {
            cachedDbcPath.set(null);
            cachedDbcDatabase.set(null);
        }
        refreshDefinitionMatching();
        persistAllFiles();
    }

    /**
     * BD-24: choosing a DBC applies it to the selected CAN measurement only.
     * DAT and LOG stay direct-processing sources. Project reopen selects the saved
     * definition through {@link ProjectWorkspace#selectDefinition} and does not call this.
     */
    private void associateDefinitionWithSelectedMeasurement(String definitionFileId) {
        if (definitionFileId == null) {
            return;
        }
        workspace().selectedMeasurement().ifPresent(measurement -> {
            if (!MeasurementSourceCapability.supportsDirectProcessing(measurement.kind())) {
                measurement.setAssociatedDefinitionId(definitionFileId);
            }
        });
    }

    private void registerDefinitionForMatching(WorkspaceFile definition) {
        if (definition == null || definition.kind() != FileKind.DEFINITION_DBC) {
            return;
        }
        try {
            DbcDatabase database = loadDbcDatabase(definition.path());
            // Ensure READY inventories from SourceInventoryModel are visible to matching
            // (covers upload-order races and any missed READY notifications).
            syncReadyInventoriesIntoMatchingCoordinator();
            definitionMatchingCoordinator.registerDefinition(
                    definition.definitionSourceId(),
                    definition.fileName(),
                    database
            );
            int messageCount = database.messages().size();
            if (messageCount <= 0) {
                LOG.log(System.Logger.Level.WARNING,
                        "Registered DBC for matching with ZERO messages: {0} (DefinitionSourceId={1}). "
                                + "Comparisons will report EMPTY_DEFINITION, not silent 0% overlap.",
                        definition.fileName(),
                        definition.definitionSourceId().value());
            } else {
                LOG.log(System.Logger.Level.INFO,
                        "Registered DBC for matching: {0} DefinitionSourceId={1} messages={2}",
                        definition.fileName(),
                        definition.definitionSourceId().value(),
                        messageCount);
            }
        } catch (DbcParseException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Failed to parse DBC for cross-matching (file listed but NOT registered): "
                            + definition.fileName()
                            + " — " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Failed to register DBC for cross-matching (file listed but NOT registered): "
                            + definition.fileName()
                            + " — " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /**
     * Pull every READY CanSourceInventory from the shared SourceInventoryModel into the
     * matching coordinator. SourceInventoryModel remains the source of truth for inventory.
     */
    private void syncReadyInventoriesIntoMatchingCoordinator() {
        for (WorkspaceFile measurement : workspace().measurementFiles()) {
            if (!measurement.kind().measurement()) {
                continue;
            }
            sourceInventoryModel.inventoryFor(measurement.id()).ifPresent(inventory -> {
                if (isInventoryReadyForMatching(inventory)) {
                    definitionMatchingCoordinator.ensureReadyInventory(
                            measurement.projectSourceId(), inventory);
                }
            });
        }
    }

    /**
     * Command: recompute DBC Message-ID match presentation for the focused inventory.
     * Must not be invoked from side-effect-free query paths (canStart / validate).
     */
    public void refreshDefinitionMatching() {
        Optional<WorkspaceFile> definition = workspace().selectedDefinition();
        if (definition.isEmpty() || definition.get().kind() != FileKind.DEFINITION_DBC) {
            cachedDbcPath.set(null);
            cachedDbcDatabase.set(null);
            sourceInventoryModel.setMatchState(SourceInventoryMatchState.noDbcSelected());
            return;
        }

        WorkspaceFile dbcFile = definition.get();
        String dbcName = dbcFile.fileName();
        Optional<CanSourceInventory> inventoryOpt = sourceInventoryModel.focusedInventory();
        if (inventoryOpt.isEmpty() || !isInventoryReadyForMatching(inventoryOpt.get())) {
            sourceInventoryModel.setMatchState(SourceInventoryMatchState.waiting(dbcName, 0));
            return;
        }

        CanSourceInventory inventory = inventoryOpt.get();
        try {
            DbcDatabase database = loadDbcDatabase(dbcFile.path());
            CanDefinitionMatchSummary summary = definitionMatchingService.match(inventory, database);
            sourceInventoryModel.setMatchState(
                    SourceInventoryMatchState.ofSummary(dbcName, summary, database));
            syncChannelSelection();
        } catch (DbcParseException e) {
            String reason = e.getMessage() == null ? "Failed to parse DBC." : e.getMessage();
            sourceInventoryModel.setMatchState(SourceInventoryMatchState.failed(dbcName, reason));
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            sourceInventoryModel.setMatchState(SourceInventoryMatchState.failed(dbcName, reason));
        }
    }

    private static boolean isInventoryReadyForMatching(CanSourceInventory inventory) {
        return inventory != null
                && inventory.channelDiscoveryComplete()
                && inventory.status() == CanInventoryStatus.INVENTORY_READY;
    }

    private DbcDatabase loadDbcDatabase(Path dbcPath) throws DbcParseException {
        Path cachedPath = cachedDbcPath.get();
        DbcDatabase cached = cachedDbcDatabase.get();
        if (cached != null && Objects.equals(cachedPath, dbcPath)) {
            return cached;
        }
        DbcDatabase parsed = dbcParser.parse(dbcPath);
        cachedDbcPath.set(dbcPath);
        cachedDbcDatabase.set(parsed);
        return parsed;
    }

    /**
     * Starts format-agnostic CAN channel / Message-ID inventory for one measurement.
     * Does not require a DBC and does not run DBC decoding.
     * Scheduling is owned by {@link ProjectInventoryCoordinator} (sequential, cancellable).
     */
    public void startSourceInventory(WorkspaceFile measurement, Consumer<Runnable> uiUpdater) {
        Objects.requireNonNull(measurement, "measurement");
        Consumer<Runnable> ui = uiUpdater == null ? PlatformUi.NOOP : uiUpdater;
        if (!measurement.kind().measurement()) {
            return;
        }
        if (MeasurementSourceCapability.supportsDirectProcessing(measurement.kind())) {
            if (measurement.status().processingAllowed()) {
                inventoryCoordinator.registerSource(measurement.projectSourceId(), ProjectInventoryJobState.READY);
            }
            return;
        }
        inventoryCoordinator.enqueue(measurement, ui);
    }

    /**
     * MF-3: ensure every implemented measurement source that is not READY is inventoried.
     */
    public void inspectPendingSources(Consumer<Runnable> uiUpdater) {
        Consumer<Runnable> ui = uiUpdater == null ? PlatformUi.NOOP : uiUpdater;
        for (WorkspaceFile file : workspace().measurementFiles()) {
            if (!file.kind().measurement() || !file.kind().implemented()) {
                continue;
            }
            if (MeasurementSourceCapability.supportsDirectProcessing(file.kind())) {
                if (file.status().processingAllowed()) {
                    inventoryCoordinator.registerSource(file.projectSourceId(), ProjectInventoryJobState.READY);
                }
                continue;
            }
            ProjectInventoryJobState state = inventoryCoordinator.state(file.projectSourceId());
            if (state == ProjectInventoryJobState.READY
                    || state == ProjectInventoryJobState.WAITING
                    || state == ProjectInventoryJobState.INDEXING) {
                continue;
            }
            startSourceInventory(file, ui);
        }
    }

    public ProjectInventoryJobState inventoryJobState(ProjectSourceId sourceId) {
        return inventoryCoordinator.state(sourceId);
    }

    /** Sparse record of sources whose file is missing at its recorded location. */
    public SourceAvailabilityModel sourceAvailabilityModel() {
        return sourceAvailabilityModel;
    }

    /**
     * User-facing status for one measurement source. Reads resident state only — no file system
     * access, no parsing, no inventory scheduling.
     */
    public SourceStatus sourceStatus(WorkspaceFile file) {
        if (file == null || !file.kind().measurement()) {
            return SourceStatus.NEEDS_ATTENTION;
        }
        ProjectSourceId sourceId = file.projectSourceId();
        boolean inventoryReady = sourceInventoryModel.inventoryFor(file.id())
                .map(inventory -> inventory.status() == CanInventoryStatus.INVENTORY_READY)
                .orElse(false);
        return SourceStatusResolver.resolve(
                file,
                inventoryCoordinator.state(sourceId),
                inventoryReady,
                sourceAvailabilityModel.available(sourceId),
                sourceProcessed(sourceId));
    }

    /** True when this source already produced at least one successful processing artifact. */
    public boolean sourceProcessed(ProjectSourceId sourceId) {
        if (sourceId == null) {
            return false;
        }
        JobExecutionStatus job = latestSourceProcessingStatus(sourceId);
        if (job != null && job.isSuccessful()) {
            return true;
        }
        // Restored projects keep processed identity in persisted artifact fingerprints
        // even when the in-session registry has not been rehydrated yet.
        return lastProcessedSourceFingerprints.containsKey(sourceId.value());
    }

    /**
     * Single UI mapping for Sources row badges, selected-source details and footer counts.
     */
    public SourceStatusPresenter.View sourceStatusView(WorkspaceFile file) {
        SourceStatus status = sourceStatus(file);
        JobExecutionStatus job = file == null ? null : latestSourceProcessingStatus(file.projectSourceId());
        return SourceStatusPresenter.present(status, job);
    }

    JobExecutionStatus latestSourceProcessingStatus(ProjectSourceId sourceId) {
        if (sourceId == null) {
            return null;
        }
        JobExecutionStatus best = lastSourceProcessingStatus.get(sourceId.value());
        for (IndependentJobResult result : processingResultRegistry.all()) {
            if (result != null && sourceId.value().equals(result.projectSourceId())) {
                best = SourceStatusPresenter.prefer(best, result.status());
            }
        }
        return best;
    }

    private void rememberSourceProcessingJobs(Iterable<ProcessingJobMetadata> jobs) {
        if (jobs == null) {
            return;
        }
        for (ProcessingJobMetadata job : jobs) {
            if (job == null || job.sourceId() == null || job.sourceId().isBlank()) {
                continue;
            }
            lastSourceProcessingStatus.merge(job.sourceId(), job.status(), SourceStatusPresenter::prefer);
        }
    }

    void rememberSourceProcessingStatusForTest(String sourceId, JobExecutionStatus status) {
        if (sourceId != null && status != null) {
            lastSourceProcessingStatus.merge(sourceId, status, SourceStatusPresenter::prefer);
        }
    }

    /**
     * Sources that can take part in the Decode stage. Drives the single Sources primary action,
     * so one broken or missing source never blocks the rest of the project.
     */
    public List<WorkspaceFile> usableDecodeSources() {
        return workspace().measurementFiles().stream()
                .filter(file -> sourceStatus(file).usableForDecode())
                .toList();
    }

    /** True when at least one usable measurement source exists, enabling Continue to Decode. */
    public boolean canContinueToDecode() {
        return !usableDecodeSources().isEmpty();
    }

    /**
     * Explicit, bounded availability sweep for the Sources workspace. Runs one
     * {@code isRegularFile} probe per source off the JavaFX thread and publishes a single
     * update. Resume routing never calls this.
     */
    public void refreshSourceAvailability(Consumer<Runnable> uiUpdater) {
        Consumer<Runnable> ui = uiUpdater == null ? PlatformUi.NOOP : uiUpdater;
        List<WorkspaceFile> measurements = workspace().measurementFiles();
        if (measurements.isEmpty()) {
            ui.accept(() -> sourceAvailabilityModel.replaceUnavailable(List.of()));
            return;
        }
        Map<ProjectSourceId, Path> probes = new java.util.LinkedHashMap<>();
        for (WorkspaceFile file : measurements) {
            probes.put(file.projectSourceId(), file.path());
        }
        inventoryExecutor.execute(() -> {
            List<ProjectSourceId> missing = new java.util.ArrayList<>();
            for (Map.Entry<ProjectSourceId, Path> probe : probes.entrySet()) {
                try {
                    if (!Files.isRegularFile(probe.getValue())) {
                        missing.add(probe.getKey());
                    }
                } catch (RuntimeException ex) {
                    missing.add(probe.getKey());
                }
            }
            ui.accept(() -> sourceAvailabilityModel.replaceUnavailable(missing));
        });
    }

    /**
     * Repoints an unavailable source at the file the engineer located, keeping the same
     * {@link ProjectSourceId}. Source identity is preserved deliberately: mappings, inventory and
     * processed artifacts belonging to this source stay valid.
     *
     * @return true when the source was repointed
     */
    public boolean relocateSource(ProjectSourceId sourceId, Path newPath) {
        return relocateSourceDetailed(sourceId, newPath).accepted();
    }

    public ProjectInventoryCoordinator inventoryCoordinator() {
        return inventoryCoordinator;
    }

    public ProjectDefinitionMatchingModel definitionMatchingModel() {
        return definitionMatchingModel;
    }

    public ProjectDefinitionMatchingCoordinator definitionMatchingCoordinator() {
        return definitionMatchingCoordinator;
    }

    /** Query: candidates for one source channel. Side-effect free. */
    public List<com.stellar.platform.desktop.project.SourceChannelDefinitionCandidate> candidatesFor(
            SourceChannelKey key
    ) {
        return definitionMatchingModel.candidatesFor(key);
    }

    /** Read-only ID-level comparisons; does not recompute matching. */
    public List<com.stellar.platform.desktop.project.SourceChannelDefinitionComparison> comparisonsFor(
            SourceChannelKey key
    ) {
        return definitionMatchingModel.comparisonsFor(key);
    }

    /** Query: mapping entry for one source channel. Side-effect free. */
    public Optional<com.stellar.platform.desktop.project.SourceChannelMappingEntry> channelMapping(
            SourceChannelKey key
    ) {
        return definitionMatchingModel.mapping(key);
    }

    /** Command: confirm the current recommendation for a channel. */
    public void confirmChannelRecommendation(SourceChannelKey key) {
        definitionMatchingCoordinator.confirmRecommendation(key);
        persistChannelMapping(key);
    }

    /** Command: confirm multiple clear recommendations with one model notification. */
    public void confirmChannelRecommendations(List<SourceChannelKey> keys) {
        definitionMatchingCoordinator.confirmRecommendations(keys);
        if (keys != null) keys.forEach(this::persistChannelMapping);
    }

    /** Command: manually choose a DBC for a channel. */
    public void chooseChannelDefinition(SourceChannelKey key, DefinitionSourceId definitionId) {
        definitionMatchingCoordinator.chooseDefinition(key, definitionId);
        if (key != null && definitionId != null) {
            workingDbcSelections.put(key, definitionId);
        }
        persistChannelMapping(key);
    }

    public WorkingDbcSelectionStore workingDbcSelections() {
        return workingDbcSelections;
    }

    public void setWorkingDbcSelection(SourceChannelKey key, DefinitionSourceId definitionId) {
        workingDbcSelections.put(key, definitionId);
    }

    public Optional<DefinitionSourceId> workingDbcSelection(SourceChannelKey key) {
        return workingDbcSelections.get(key);
    }

    /** Command: clear confirmed/manual mapping and restore recommendation state. */
    public void clearChannelMapping(SourceChannelKey key) {
        definitionMatchingCoordinator.clearMapping(key);
        if (key != null) metadataWrite(() -> metadataStore.deleteChannelMapping(
                metadataProjectId.get(), key.sourceId().value(), key.channelId()));
    }

    private synchronized void commitInventoryCoverage(ProjectInventoryCoordinator.InventoryProgress progress) {
        if (progress == null || progress.sourceId() == null || !progress.jobState().terminal()) {
            return;
        }
        if (progress.generation() != inventoryCoordinator.generationOf(progress.sourceId().value())) {
            return;
        }
        String projectId = metadataProjectId.get();
        if (workspace().findFile(progress.sourceId().value()).isEmpty()) {
            return;
        }
        if (isInventoryReadyForMatching(progress.inventory())) {
            definitionMatchingCoordinator.onInventoryReady(progress.sourceId(), progress.inventory());
        } else {
            definitionMatchingCoordinator.onInventoryRemoved(progress.sourceId());
        }
        persistSourceChannelMappings(projectId, progress.sourceId());
    }

    private synchronized void applyInventoryProgress(ProjectInventoryCoordinator.InventoryProgress progress) {
        if (progress == null || progress.sourceId() == null) {
            return;
        }
        final String projectId = metadataProjectId.get();
        String fileId = progress.sourceId().value();
        Optional<WorkspaceFile> fileOpt = workspace().findFile(fileId);
        if (fileOpt.isEmpty()) {
            // Stale completion after removal — must not resurrect source state.
            return;
        }
        if (progress.generation() != inventoryCoordinator.generationOf(fileId)) {
            return;
        }
        CanSourceInventory inventory = progress.inventory();
        if (inventory == null && progress.jobState() == ProjectInventoryJobState.FAILED) {
            String detail = progress.detail();
            inventory = inventoryFailure(fileOpt.get().path(), new IllegalStateException(
                    detail == null || detail.isBlank() ? "Inventory failed" : detail));
        }
        if (inventory != null) {
            sourceInventoryModel.put(fileId, inventory);
            CanSourceInventory persistedInventory = inventory;
            metadataWrite(() -> {
                metadataStore.saveInventory(metadataProjectId.get(), fileId, persistedInventory);
                persistInventoryDataQualityFindings(fileId, persistedInventory);
            });
            // Matching uses the same CanSourceInventory instance as Source Inventory UI.
            if (isInventoryReadyForMatching(inventory)) {
                definitionMatchingCoordinator.onInventoryReady(progress.sourceId(), inventory);
                persistSourceChannelMappings(projectId, progress.sourceId());
            } else if (progress.jobState().terminal()) {
                definitionMatchingCoordinator.onInventoryRemoved(progress.sourceId());
                persistSourceChannelMappings(projectId, progress.sourceId());
            }
        } else if (progress.jobState().terminal()
                && progress.jobState() != ProjectInventoryJobState.READY) {
            definitionMatchingCoordinator.onInventoryRemoved(progress.sourceId());
            persistSourceChannelMappings(projectId, progress.sourceId());
        }
        String focusedId = sourceInventoryModel.focusedFileId().orElse(null);
        String selectedId = workspace().selectedMeasurement().map(WorkspaceFile::id).orElse(null);
        if (Objects.equals(selectedId, fileId) || focusedId == null || Objects.equals(focusedId, fileId)) {
            sourceInventoryModel.focus(fileId);
            syncChannelSelection();
        }
        if (progress.jobState().terminal()) {
            if (Objects.equals(selectedId, fileId) || Objects.equals(focusedId, fileId)) {
                refreshDefinitionMatching();
            }
        }
    }

    private static boolean isTerminalInventoryStatus(CanInventoryStatus status) {
        return status == CanInventoryStatus.INVENTORY_READY
                || status == CanInventoryStatus.FAILED
                || status == CanInventoryStatus.CANCELLED;
    }

    private static CanSourceInventory inventoryFailure(Path path, RuntimeException error) {
        String message = error.getMessage() == null ? error.toString() : error.getMessage();
        return new CanSourceInventory(
                path,
                path.getFileName() == null ? path.toString() : path.getFileName().toString(),
                MeasurementSourceFormat.fromFormatKey(extensionOf(path)),
                CanInventoryStatus.FAILED,
                0,
                0,
                0,
                true,
                List.of(),
                List.of(new CanInventoryDiagnostic(
                        CanInventoryDiagnosticCode.SOURCE_READ_FAILED,
                        message
                ))
        );
    }

    private static String extensionOf(Path path) {
        if (path == null || path.getFileName() == null) {
            return "";
        }
        String name = path.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private void cancelInventory(String fileId) {
        if (fileId == null) {
            return;
        }
        inventoryCoordinator.cancelAndForget(ProjectSourceId.of(fileId));
    }

    private void cancelAllInventories() {
        inventoryCoordinator.clear();
    }

    public void syncChannelSelection() {
        Optional<WorkspaceFile> measurement = workspace().selectedMeasurement();
        Optional<CanSourceInventory> inventory = sourceInventoryModel.focusedInventory();
        if (measurement.isEmpty() || inventory.isEmpty()) {
            return;
        }
        channelSelectionModel.bindSource(
                measurement.get().id(),
                measurement.get().path(),
                inventory.get()
        );
    }

    /**
     * True when the Sources primary action should run the existing direct-processing path
     * instead of opening Decode. A selected DAT or LOG qualifies. So does the approved
     * Direct Processing journey, including a mixed project whose DAT or LOG files are
     * still unprocessed. The run still decodes only DAT and LOG files.
     */
    public boolean directProcessingActionAvailable() {
        if (selectedDirectMeasurement().isPresent()) {
            return true;
        }
        return projectJourneyState() == ProjectJourneyState.DIRECT_PROCESS
                && firstProcessableDirectMeasurement().isPresent();
    }

    /**
     * Ensures a processable DAT/LOG source is selected so the project-level Direct Processing
     * action can start. The run itself includes every active compatible measurement, not only
     * this selection. Returns false when Decode remains the correct next step.
     */
    public boolean prepareDirectProcessingSelection() {
        if (selectedDirectMeasurement().isPresent()) {
            return true;
        }
        if (!directProcessingActionAvailable()) {
            return false;
        }
        WorkspaceFile target = firstProcessableDirectMeasurement().orElse(null);
        if (target == null) {
            return false;
        }
        selectMeasurement(target.id());
        return true;
    }

    /** DAT/LOG sources currently in the project, including files that cannot be processed. */
    public List<WorkspaceFile> directMeasurementFiles() {
        return workspace().measurementFiles().stream()
                .filter(file -> MeasurementSourceCapability.supportsDirectProcessing(file.kind()))
                .toList();
    }

    /** Active DAT/LOG sources the project-level Direct Processing action will run. */
    public List<WorkspaceFile> directProcessingTargets() {
        return directMeasurementFiles().stream()
                .filter(file -> file.status().processingAllowed())
                .toList();
    }

    private java.util.Optional<WorkspaceFile> selectedDirectMeasurement() {
        return workspace().selectedMeasurement()
                .filter(file -> MeasurementSourceCapability.supportsDirectProcessing(file.kind()))
                .filter(file -> file.status().processingAllowed());
    }

    private java.util.Optional<WorkspaceFile> firstProcessableDirectMeasurement() {
        return workspace().measurementFiles().stream()
                .filter(file -> MeasurementSourceCapability.supportsDirectProcessing(file.kind()))
                .filter(file -> file.status().processingAllowed())
                .filter(file -> !sourceProcessed(file.projectSourceId()))
                .findFirst();
    }

    public boolean canStartLocalProcessing() {
        Optional<WorkspaceFile> direct = workspace().selectedMeasurement()
                .filter(file -> MeasurementSourceCapability.supportsDirectProcessing(file.kind()));
        if (direct.isPresent()) {
            return direct.get().status().processingAllowed();
        }
        InputValidationResult validation = validateCurrentSelection();
        if (!validation.valid()) {
            return false;
        }
        if (!SourceInventoryFilterEngine.isDbcMatchReady(sourceInventoryModel.matchState())) {
            return false;
        }
        return channelSelectionModel.hasSelection();
    }

    public String processingSelectionHint() {
        Optional<WorkspaceFile> direct = workspace().selectedMeasurement()
                .filter(file -> MeasurementSourceCapability.supportsDirectProcessing(file.kind()));
        if (direct.isPresent()) {
            if (!direct.get().status().processingAllowed()) {
                String message = direct.get().validationMessage();
                return message == null || message.isBlank()
                        ? "StellarAI could not determine the measurement structure automatically."
                        : message;
            }
            return "Direct processing. DBC is not required.";
        }
        if (workspace().selectedDefinition().isEmpty()) {
            return "Select a DBC before generating DBC-decoded signals.";
        }
        if (!SourceInventoryFilterEngine.isDbcMatchReady(sourceInventoryModel.matchState())) {
            return "Waiting for DBC matching before channel selection can start processing.";
        }
        if (!channelSelectionModel.hasSelection()) {
            return "Select at least one CAN channel to continue.";
        }
        if (channelSelectionModel.resultStale()) {
            return "Selection changed. Run processing to generate signals for the new channel selection.";
        }
        return "";
    }

    /** Side-effect free effective DBC for one channel. Does not confirm mappings. */
    public ProcessingPlanInputAssembler.EffectiveResolution effectiveDbc(SourceChannelKey key) {
        if (key == null) {
            return ProcessingPlanInputAssembler.resolveEffective(null);
        }
        ProcessingPlanInputAssembler.EffectiveResolution resolution = ProcessingPlanInputAssembler.resolveEffective(
                definitionMatchingModel.mapping(key).orElse(null));
        if (!resolution.resolved() || !definitionAvailable(resolution.definitionId())) {
            return ProcessingPlanInputAssembler.resolveEffective(null);
        }
        return resolution;
    }

    /**
     * Decision Workspace Proceed readiness.
     *
     * <p>Auto Decode: every included required channel is either DBC-decodable (clear best match
     * or a documented Auto exact-tie selection) or processable native-only (NO_MATCH). A missing
     * DBC is a warning, not a blocker. Auto Decode does not require a manual DBC pick.
     *
     * <p>Custom Decode: an explicit DBC (MANUAL, CONFIRMED, or a clear recommendation) is
     * required wherever one can be chosen. A true No Match channel uses the same native-only
     * plan path as Auto Decode and is a warning, not a blocker. An ambiguous Review channel
     * stays blocked until the engineer chooses a DBC.
     */
    public boolean canProceedFromDecisionWorkspace() {
        return decisionProcessingReadiness().canProceed();
    }

    public String decisionProcessingHint() {
        return decisionProcessingReadiness().hint();
    }

    public String decisionProcessingReadyTooltip() {
        return decisionProcessingReadiness().readyTooltip();
    }

    public DecisionProcessingReadiness decisionProcessingReadiness() {
        boolean auto = decisionWorkspaceMode.get() == DecisionWorkspaceMode.QUICK_DECODE;
        int required = 0;
        int unresolved = 0;
        int recommendedFallback = 0;
        int nativeOnly = 0;
        int ambiguous = 0;
        boolean anyInventoryReady = false;
        for (WorkspaceFile measurement : workspace().measurementFiles()) {
            Optional<CanSourceInventory> inventory = sourceInventoryModel.inventoryFor(measurement.id());
            if (inventory.isEmpty() || inventory.get().status() != CanInventoryStatus.INVENTORY_READY) {
                continue;
            }
            anyInventoryReady = true;
            for (var channel : inventory.get().channels()) {
                SourceChannelKey key = SourceChannelKey.of(measurement.projectSourceId(), channel.channelId());
                Optional<com.stellar.platform.desktop.project.SourceChannelMappingEntry> mapping =
                        definitionMatchingModel.mapping(key);
                if (mapping.isPresent()
                        && mapping.get().status()
                        == com.stellar.platform.desktop.project.ChannelMappingStatus.NOT_APPLICABLE) {
                    continue;
                }
                if (auto && !quickDecodeSelectionModel.included(key)) {
                    continue;
                }
                required++;
                if (auto) {
                    QuickDecodeDecisionResolver.Decision decision = quickDecodeDecisionResolver.resolve(key);
                    if (decision.decodable()) {
                        if (decision.planLifecycle() == MappingLifecycle.RECOMMENDED_FALLBACK) {
                            recommendedFallback++;
                        }
                    } else if (decision.processableWithoutDbc()) {
                        nativeOnly++;
                    } else {
                        unresolved++;
                        if (decision.status() == QuickDecodeDecisionResolver.Status.REVIEW) {
                            ambiguous++;
                        }
                    }
                } else if (isNativeOnlyChannel(mapping.orElse(null))) {
                    nativeOnly++;
                } else {
                    ProcessingPlanInputAssembler.EffectiveResolution resolution =
                            ProcessingPlanInputAssembler.resolveEffective(mapping.orElse(null));
                    if (!resolution.resolved() || !definitionAvailable(resolution.definitionId())) {
                        unresolved++;
                        if (mapping.isPresent() && mapping.get().recommendationKind()
                                == ChannelRecommendationKind.AMBIGUOUS) {
                            ambiguous++;
                        }
                    } else if (resolution.origin() == ProcessingPlanInputAssembler.EffectiveOrigin.RECOMMENDED_FALLBACK) {
                        recommendedFallback++;
                    }
                }
            }
        }
        if (!anyInventoryReady) {
            return new DecisionProcessingReadiness(false, 0, 0, 0,
                    "Waiting for CAN inventory before processing can start.", "", 0);
        }
        if (required == 0) {
            return new DecisionProcessingReadiness(false, 0, 0, 0,
                    auto
                            ? "Select at least one CAN channel to continue."
                            : "No CAN channels are available for processing.",
                    "", 0);
        }
        if (unresolved > 0) {
            String hint;
            if (!auto) {
                hint = unresolved + (unresolved == 1 ? " channel still needs" : " channels still need")
                        + " a DBC choice before processing.";
                if (ambiguous > 0) {
                    hint += " " + ambiguous + (ambiguous == 1 ? " has" : " have")
                            + " several equally matching DBCs. Choose one, then Confirm Selected DBC"
                            + " or Apply it to every compatible Review channel.";
                }
                if (nativeOnly > 0) {
                    hint += " " + nativeOnly + " No Match "
                            + (nativeOnly == 1 ? "channel stays" : "channels stay")
                            + " native-only and " + (nativeOnly == 1 ? "does" : "do") + " not need a DBC.";
                }
            } else {
                hint = unresolved + (unresolved == 1 ? " channel still needs" : " channels still need")
                        + " a DBC that Auto Decode can apply safely.";
                if (ambiguous > 0) {
                    hint += " A matching definition is missing, corrupt, or no longer compatible."
                            + " Use Custom Decode to inspect the channel.";
                }
            }
            return new DecisionProcessingReadiness(false, required, unresolved, recommendedFallback,
                    hint, "", nativeOnly);
        }
        String readyTip;
        if (nativeOnly > 0) {
            readyTip = nativeOnly == required
                    ? "No matching DBC is available for this channel. Processing can continue, but DBC-decoded CAN signals will not be generated."
                    : nativeOnly + " channel(s) have no matching DBC. Processing can continue, but those channels will not generate DBC-decoded CAN signals.";
        } else if (auto && recommendedFallback > 0) {
            readyTip = "Auto Decode applied a deterministic DBC. Use Custom Decode to review or override.";
        } else if (!auto && recommendedFallback > 0) {
            readyTip = "Only confirmed or manually chosen DBC mappings will be processed. Recommended channels stay in Custom Decode until accepted.";
        } else {
            readyTip = "Continue to Processing with the current mapping selection.";
        }
        return new DecisionProcessingReadiness(true, required, 0, recommendedFallback, "", readyTip, nativeOnly);
    }

    /**
     * Channels whose Detailed Mapping decision was explicitly accepted (CONFIRMED or MANUAL)
     * and whose effective DBC is available. Recommendations are not included.
     */
    public List<SourceChannelRef> acceptedDecisionProcessingChannels() {
        List<SourceChannelRef> accepted = new java.util.ArrayList<>();
        for (WorkspaceFile measurement : workspace().measurementFiles()) {
            Optional<CanSourceInventory> inventory = sourceInventoryModel.inventoryFor(measurement.id());
            if (inventory.isEmpty() || inventory.get().status() != CanInventoryStatus.INVENTORY_READY) {
                continue;
            }
            for (var channel : inventory.get().channels()) {
                SourceChannelKey key = SourceChannelKey.of(measurement.projectSourceId(), channel.channelId());
                if (effectiveDbc(key).acceptedForProcessing()) {
                    accepted.add(SourceChannelRef.of(measurement.id(), channel.channelId()));
                }
            }
        }
        return List.copyOf(accepted);
    }

    /**
     * Custom Decode channels that may enter the processing plan: an explicitly accepted DBC,
     * plus true No Match channels as native-only jobs. Ambiguous Review channels are omitted.
     */
    public List<SourceChannelRef> customDecodeProcessableChannels() {
        List<SourceChannelRef> selected = new ArrayList<>(acceptedDecisionProcessingChannels());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (SourceChannelRef ref : selected) {
            seen.add(ref.projectSourceId() + "#" + ref.channelId());
        }
        for (WorkspaceFile measurement : workspace().measurementFiles()) {
            Optional<CanSourceInventory> inventory = sourceInventoryModel.inventoryFor(measurement.id());
            if (inventory.isEmpty() || inventory.get().status() != CanInventoryStatus.INVENTORY_READY) {
                continue;
            }
            for (var channel : inventory.get().channels()) {
                SourceChannelKey key = SourceChannelKey.of(measurement.projectSourceId(), channel.channelId());
                if (!isNativeOnlyChannel(definitionMatchingModel.mapping(key).orElse(null))) {
                    continue;
                }
                SourceChannelRef ref = SourceChannelRef.of(measurement.id(), channel.channelId());
                if (seen.add(ref.projectSourceId() + "#" + ref.channelId())) {
                    selected.add(ref);
                }
            }
        }
        return List.copyOf(selected);
    }

    /**
     * Review channels where {@code definitionId} matches at least one CAN ID.
     * No Match channels, clear recommendations, and channels already given a different
     * explicit DBC are excluded. Nothing is selected automatically.
     */
    public List<SourceChannelKey> compatibleReviewChannels(DefinitionSourceId definitionId) {
        if (definitionId == null) {
            return List.of();
        }
        boolean auto = decisionWorkspaceMode.get() == DecisionWorkspaceMode.QUICK_DECODE;
        List<SourceChannelKey> compatible = new ArrayList<>();
        for (WorkspaceFile measurement : workspace().measurementFiles()) {
            Optional<CanSourceInventory> inventory = sourceInventoryModel.inventoryFor(measurement.id());
            if (inventory.isEmpty() || inventory.get().status() != CanInventoryStatus.INVENTORY_READY) {
                continue;
            }
            for (var channel : inventory.get().channels()) {
                SourceChannelKey key = SourceChannelKey.of(measurement.projectSourceId(), channel.channelId());
                if (auto && !quickDecodeSelectionModel.included(key)) {
                    continue;
                }
                if (!isOpenReviewChannel(key)) {
                    continue;
                }
                boolean overlaps = candidatesFor(key).stream().anyMatch(candidate ->
                        definitionId.equals(candidate.definitionSourceId())
                                && candidate.matchedUniqueMessageCount() > 0L);
                if (overlaps) {
                    compatible.add(key);
                }
            }
        }
        return List.copyOf(compatible);
    }

    public int compatibleReviewChannelCount(DefinitionSourceId definitionId) {
        return compatibleReviewChannels(definitionId).size();
    }

    /**
     * Explicit bulk acceptance of one DBC for every still-open compatible Review channel.
     * Auto Decode stores a per-channel override. Custom Decode stores a manual mapping.
     * @return how many channels were updated
     */
    public int applySelectedDbcToCompatibleReviewChannels(DefinitionSourceId definitionId) {
        List<SourceChannelKey> keys = compatibleReviewChannels(definitionId);
        if (keys.isEmpty() || definitionId == null) {
            return 0;
        }
        if (decisionWorkspaceMode.get() == DecisionWorkspaceMode.QUICK_DECODE) {
            quickDecodeSelectionModel.setOverrides(keys, definitionId);
            return keys.size();
        }
        for (SourceChannelKey key : keys) {
            chooseChannelDefinition(key, definitionId);
        }
        return keys.size();
    }

    /**
     * An explicit Auto Decode DBC choice that can still be applied to other Review channels.
     */
    public Optional<DefinitionSourceId> explicitDbcPendingReviewApply() {
        DefinitionSourceId pending = null;
        for (DefinitionSourceId id : quickDecodeSelectionModel.overrides().values()) {
            if (id != null && compatibleReviewChannelCount(id) > 0) {
                pending = id;
            }
        }
        return Optional.ofNullable(pending);
    }

    private boolean isOpenReviewChannel(SourceChannelKey key) {
        com.stellar.platform.desktop.project.SourceChannelMappingEntry mapping =
                definitionMatchingModel.mapping(key).orElse(null);
        if (mapping == null) {
            return false;
        }
        if (mapping.status() == ChannelMappingStatus.MANUAL
                || mapping.status() == ChannelMappingStatus.CONFIRMED
                || mapping.status() == ChannelMappingStatus.NO_MATCH
                || mapping.status() == ChannelMappingStatus.NOT_APPLICABLE
                || mapping.recommendationKind() == ChannelRecommendationKind.NO_MATCH
                || mapping.recommendationKind() == ChannelRecommendationKind.NOT_APPLICABLE
                || mapping.recommendationKind() != ChannelRecommendationKind.AMBIGUOUS) {
            return false;
        }
        if (decisionWorkspaceMode.get() != DecisionWorkspaceMode.QUICK_DECODE) {
            return true;
        }
        return quickDecodeDecisionResolver.resolve(key).status() == QuickDecodeDecisionResolver.Status.REVIEW;
    }

    /** True when the channel has no usable DBC and the plan may run it native-only. */
    private static boolean isNativeOnlyChannel(
            com.stellar.platform.desktop.project.SourceChannelMappingEntry mapping
    ) {
        if (mapping == null) {
            return false;
        }
        if (mapping.status() == ChannelMappingStatus.MANUAL
                || mapping.status() == ChannelMappingStatus.CONFIRMED) {
            return false;
        }
        if (ProcessingPlanInputAssembler.resolveEffective(mapping).acceptedForProcessing()) {
            return false;
        }
        return mapping.status() == ChannelMappingStatus.NO_MATCH
                || mapping.recommendationKind() == ChannelRecommendationKind.NO_MATCH;
    }

    /**
     * Quick Decode channels that can produce DBC-decoded signals now.
     */
    public List<SourceChannelRef> quickDecodeReadyChannels() {
        return quickDecodeChannels(true);
    }

    /**
     * Quick Decode channels that may enter the processing plan: DBC-decodable (including
     * Auto-selected exact ties) or native-only (included NO_MATCH). Only a true Auto REVIEW
     * blocker stays out of the plan.
     */
    public List<SourceChannelRef> quickDecodeProcessableChannels() {
        return quickDecodeChannels(false);
    }

    private List<SourceChannelRef> quickDecodeChannels(boolean decodableOnly) {
        List<SourceChannelRef> selected = new java.util.ArrayList<>();
        for (WorkspaceFile measurement : workspace().measurementFiles()) {
            Optional<CanSourceInventory> inventory = sourceInventoryModel.inventoryFor(measurement.id());
            if (inventory.isEmpty() || inventory.get().status() != CanInventoryStatus.INVENTORY_READY) {
                continue;
            }
            for (var channel : inventory.get().channels()) {
                SourceChannelKey key = SourceChannelKey.of(measurement.projectSourceId(), channel.channelId());
                QuickDecodeDecisionResolver.Decision decision = quickDecodeDecisionResolver.resolve(key);
                if (decodableOnly ? decision.decodable() : decision.processable()) {
                    selected.add(SourceChannelRef.of(measurement.id(), channel.channelId()));
                }
            }
        }
        return List.copyOf(selected);
    }

    /**
     * Selects the current Decision Workspace mode's executable channels for processing.
     * Detailed mode preserves the existing explicit-confirmation behaviour; Quick Decode applies
     * a deterministic Auto DBC (clear best match or documented exact-tie rule) and keeps manual
     * overrides isolated from Detailed Mapping state. Included NO_MATCH channels enter as
     * native-only jobs.
     */
    public void prepareProcessingFromDecisionWorkspace() {
        DecisionWorkspaceMode mode = decisionWorkspaceMode.get();
        processingSelectionModel.captureDecisionOrigin(mode);
        List<SourceChannelRef> selected = mode == DecisionWorkspaceMode.QUICK_DECODE
                ? quickDecodeProcessableChannels()
                : customDecodeProcessableChannels();
        processingSelectionModel.clearSelection();
        processingSelectionModel.selectAll(selected);

        // Legacy single-source model remains synchronized only for old processing surfaces.
        // Never let it expand the project-scoped Quick Decode selection.
        if (mode == DecisionWorkspaceMode.DETAILED_MAPPING) {
            syncChannelSelection();
            Optional<CanSourceInventory> focused = sourceInventoryModel.focusedInventory();
            if (focused.isPresent() && !channelSelectionModel.hasSelection()) {
                channelSelectionModel.selectAll(focused.get());
            }
        }
    }

    /**
     * Decision Workspace proceed: build an immutable plan from accepted mappings only
     * and open the unified Processing Workspace. Does not confirm recommendations.
     */
    public void enterProcessingWorkspaceFromDecision() {
        if (!processingSelectionModel.canNavigateAwayFromExecution()) {
            return;
        }
        prepareProcessingFromDecisionWorkspace();
        hydrateProcessingWorkspace();
    }

    /**
     * Rebuilds the current immutable plan from Decision-accepted channels and restores
     * matching results from durable artifacts + the session registry. No reparse.
     */
    public void hydrateProcessingWorkspace() {
        if (!processingSelectionModel.canNavigateAwayFromExecution()) {
            return;
        }
        if (processingSelectionModel.selection().selectedChannels().isEmpty()) {
            prepareProcessingFromDecisionWorkspace();
        }
        ProcessingPlanInputs inputs = new ProcessingPlanInputAssembler().assemble(this);
        ProjectProcessingPlan seedPlan = new ProcessingPlanBuilder()
                .build(processingSelectionModel.selection(), inputs);
        if (seedPlan.isEmpty()) {
            ProjectProcessingPlan snapshot = processingSelectionModel.executionPlanSnapshot();
            Set<String> living = activeMeasurementSourceIds();
            if (snapshot != null && !snapshot.isEmpty()
                    && !ProcessingSelectionWorkspaceModel.planContainsInactiveSources(snapshot, living)) {
                seedPlan = snapshot;
            }
        }
        seedReusableArtifactsFromMetadata(seedPlan);
        restoreUnpersistedJobFailures(seedPlan);
        boolean materializedExecutionResult =
                processingSelectionModel.mode() == ProcessingPageMode.EXECUTION_RESULT
                        && processingSelectionModel.executionJobs().stream()
                        .anyMatch(job -> job.result().isPresent());
        if (!(materializedExecutionResult && processingResultRegistry.all().isEmpty())) {
            processingSelectionModel.hydrateFromRegistry(inputs, processingResultRegistry);
        }
        if (signalsModel.catalog() == null || signalsModel.catalog().entries().isEmpty()) {
            republishSignalsFromRegistry();
        }
    }

    public boolean isPlanExecutionActive() {
        if (planExecutionStarting.get() || processingSelectionModel.mode() == ProcessingPageMode.EXECUTING) {
            return true;
        }
        Future<?> future = runningJob.get();
        return future != null && !future.isDone();
    }

    public record DecisionProcessingReadiness(
            boolean canProceed,
            int requiredChannels,
            int unresolvedChannels,
            int recommendedFallbackChannels,
            String hint,
            String readyTooltip,
            int nativeOnlyChannels
    ) {
        public DecisionProcessingReadiness {
            hint = hint == null ? "" : hint;
            readyTooltip = readyTooltip == null ? "" : readyTooltip;
            nativeOnlyChannels = Math.max(0, nativeOnlyChannels);
        }

        /** Channels with a usable DBC mapping. Native-only Auto Decode channels are not counted. */
        public int dbcConfiguredChannels() {
            return Math.max(0, requiredChannels - unresolvedChannels - nativeOnlyChannels);
        }
    }

    private boolean definitionAvailable(DefinitionSourceId id) {
        if (id == null) {
            return false;
        }
        if (definitionMatchingCoordinator.parsedDefinition(id).isPresent()) {
            return true;
        }
        return workspace().definitionFiles().stream()
                .anyMatch(file -> id.equals(file.definitionSourceId()));
    }

    /**
     * Query: validate the currently selected measurement/definition pair.
     * Side-effect free — does not auto-associate, refresh matching, or mutate processing status.
     */
    public InputValidationResult validateCurrentSelection() {
        Optional<WorkspaceFile> measurement = workspace().selectedMeasurement();
        Optional<WorkspaceFile> definition = workspace().selectedDefinition();
        InputValidationResult result = session.validationService().validateSelection(
                measurement.orElse(null),
                definition.orElse(null)
        );
        lastValidationIssues.set(result.issues());
        return result;
    }

    public List<ValidationIssue> lastValidationIssues() {
        return lastValidationIssues.get();
    }

    /**
     * Project-level Direct Processing. Each active DAT/LOG source is parsed, persisted, and
     * published on its own. One failure does not drop the remaining files or copy another
     * file's catalog.
     */
    private void startAllDirectProcessing(
            Consumer<Runnable> uiUpdater,
            Runnable onUiRefresh,
            ProcessingStartOptions opts
    ) {
        List<WorkspaceFile> targets = directProcessingTargets();
        List<WorkspaceFile> rejected = directMeasurementFiles().stream()
                .filter(file -> !file.status().processingAllowed())
                .toList();
        if (targets.isEmpty() && rejected.isEmpty()) {
            workspace().setProcessingStatus(ProjectProcessingStatus.FAILED);
            processingModel.resetForRun("", "");
            processingModel.applyProgress(ProcessingStage.FAILED, new ProcessingStatistics());
            uiUpdater.accept(onUiRefresh);
            return;
        }
        long generation = projectProcessingContext.beginRequest();
        cancelProcessing();
        boolean hadResult = signalsModel.processingContext().hasResult();
        boolean preserve = opts.preserveExistingResultUntilSuccess() && hadResult;
        projectProcessingContext.setPreserveDisplayedResult(preserve);
        projectProcessingContext.clearFailure();
        int requested = targets.size() + rejected.size();
        String batchLabel = requested + " measurements";
        processingModel.resetForRun(batchLabel, "", "Direct", 0L);
        workspace().setProcessingStatus(ProjectProcessingStatus.RUNNING);
        if (!preserve) {
            signalsModel.clear();
        }
        if (opts.openProcessingPageOnStart()) {
            currentPage.set(NavigationPage.PROCESSING);
        }
        uiUpdater.accept(onUiRefresh);

        ProcessingGateway gateway = session.processingGateway();
        CoalescingUiDispatcher dispatcher = new CoalescingUiDispatcher(uiUpdater);
        Future<?> future = processingExecutor.submit(() -> {
            int succeeded = 0;
            int failed = rejected.size();
            List<String> failures = new ArrayList<>();
            for (WorkspaceFile measurement : rejected) {
                String detail = measurement.validationMessage();
                failures.add(measurement.fileName()
                        + " — " + (detail == null || detail.isBlank() ? "Processing unavailable" : detail));
                lastSourceProcessingStatus.merge(
                        measurement.id(), JobExecutionStatus.FAILED, SourceStatusPresenter::prefer);
            }
            Instant started = Instant.now();
            boolean cancelled = false;
            for (int index = 0; index < targets.size(); index++) {
                if (!projectProcessingContext.isCurrentGeneration(generation)) {
                    cancelled = true;
                    break;
                }
                WorkspaceFile measurement = targets.get(index);
                processingModel.resetForRun(
                        measurement.fileName(),
                        "",
                        (index + 1) + " of " + targets.size(),
                        0L);
                dispatcher.submit(onUiRefresh);
                DirectFileOutcome outcome = processOneDirectMeasurement(measurement, gateway);
                if (outcome.cancelled()) {
                    cancelled = true;
                    failed++;
                    failures.add(measurement.fileName() + " — cancelled");
                    break;
                }
                if (outcome.succeeded()) {
                    succeeded++;
                } else {
                    failed++;
                    failures.add(measurement.fileName()
                            + " — " + (outcome.detail().isBlank() ? "failed" : outcome.detail()));
                    lastSourceProcessingStatus.merge(
                            measurement.id(), JobExecutionStatus.FAILED, SourceStatusPresenter::prefer);
                }
            }
            if (!projectProcessingContext.isCurrentGeneration(generation)) {
                return;
            }
            ProjectProcessingStatus completion;
            if (cancelled) {
                completion = ProjectProcessingStatus.CANCELLED;
            } else if (succeeded == 0 || failed > 0) {
                completion = ProjectProcessingStatus.FAILED;
            } else {
                completion = ProjectProcessingStatus.COMPLETED;
            }
            String message = directBatchMessage(requested, succeeded, failed, cancelled);
            String technical = String.join("\n", failures);
            if (succeeded > 0) {
                publishDirectMeasurementCatalog(directSuccessfulJobs());
            }
            persistDirectBatchRun(completion, requested, message);
            processingModel.resetForRun(batchLabel, "", requested + " measurements", succeeded);
            Path summaryFile = targets.isEmpty() ? rejected.getFirst().path() : targets.getFirst().path();
            com.stellar.platform.desktop.project.ProcessingSummary summary =
                    new com.stellar.platform.desktop.project.ProcessingSummary(
                            "DAT/LOG",
                            summaryFile,
                            Path.of(""),
                            new ProcessingStatistics(),
                            Duration.between(started, Instant.now()),
                            completion,
                            List.of(),
                            message,
                            technical,
                            false,
                            "",
                            "",
                            requested + " measurements",
                            succeeded
                    );
            if (succeeded > 0) {
                processingModel.applySummary(summary);
                workspace().setLatestSummary(summary);
                workspace().setProcessingStatus(completion);
                projectProcessingContext.clearFailure();
                projectProcessingContext.setPreserveDisplayedResult(false);
                if (opts.navigateToSignalsOnSuccess()) {
                    projectProcessingContext.markAutoNavigateToSignals();
                    currentPage.set(NavigationPage.SIGNALS);
                }
            } else {
                applyFailedOrCancelledResult(
                        summary,
                        new ChannelSelectionModel.SelectionSummary(0, 0, 0, "Direct", false),
                        preserve,
                        completion
                );
            }
            dispatcher.submitImmediate(onUiRefresh);
        });
        runningJob.set(future);
    }

    private record DirectFileOutcome(boolean succeeded, boolean warnings, boolean cancelled, String detail) {
    }

    /**
     * Parses and commits one DAT/LOG source. Samples stay in that source's artifact.
     * A failure here does not roll back sources that already committed.
     */
    private DirectFileOutcome processOneDirectMeasurement(WorkspaceFile measurement, ProcessingGateway gateway) {
        SignalPreviewBuffer preview = new SignalPreviewBuffer(1);
        Path directRoot = null;
        if (isDurableNamedProject()) {
            try {
                directRoot = prepareDirectArtifactStaging(measurement);
            } catch (RuntimeException ex) {
                return new DirectFileOutcome(false, false, false,
                        ex.getMessage() == null ? ex.toString() : ex.getMessage());
            }
        }
        LocalProcessingGateway.GatewayResult result;
        try {
            if (directRoot != null) {
                if (!(gateway instanceof LocalProcessingGateway localGateway)) {
                    return new DirectFileOutcome(false, false, false, "Direct processing gateway is unavailable.");
                }
                result = localGateway.process(
                        measurement,
                        null,
                        ProcessingRequest.DEFAULT_BATCH_SIZE,
                        preview,
                        ProcessingProgressListener.NOOP,
                        null,
                        null,
                        directRoot
                );
            } else {
                result = gateway.process(
                        measurement,
                        null,
                        ProcessingRequest.DEFAULT_BATCH_SIZE,
                        preview,
                        ProcessingProgressListener.NOOP,
                        null,
                        null
                );
            }
        } catch (RuntimeException ex) {
            return new DirectFileOutcome(false, false, false,
                    ex.getMessage() == null ? ex.toString() : ex.getMessage());
        }
        var fileSummary = result.summary();
        ProjectProcessingStatus status = fileSummary == null
                ? ProjectProcessingStatus.FAILED
                : fileSummary.completionStatus();
        if (status == ProjectProcessingStatus.CANCELLED) {
            return new DirectFileOutcome(false, false, true, "Processing cancelled.");
        }
        boolean success = status == ProjectProcessingStatus.COMPLETED
                || status == ProjectProcessingStatus.COMPLETED_WITH_WARNINGS;
        if (!success) {
            String detail = fileSummary == null ? "Processing failed." : fileSummary.userMessage();
            return new DirectFileOutcome(false, false, false, detail == null ? "" : detail);
        }
        if (directRoot != null) {
            Optional<SignalSeriesStore> committed = commitDirectProcessingResult(measurement, result);
            if (committed.isEmpty()) {
                return new DirectFileOutcome(false, false, false, "Processed signals could not be saved.");
            }
        }
        boolean warnings = status == ProjectProcessingStatus.COMPLETED_WITH_WARNINGS;
        if (warnings) {
            lastSourceProcessingStatus.merge(
                    measurement.id(),
                    JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                    SourceStatusPresenter::prefer);
        }
        return new DirectFileOutcome(true, warnings, false, "");
    }

    private static String directBatchMessage(int requested, int succeeded, int failed, boolean cancelled) {
        StringBuilder message = new StringBuilder();
        if (cancelled) {
            message.append("Direct processing cancelled. ");
        } else if (failed > 0) {
            message.append("Direct processing did not complete for every measurement. ");
        } else {
            message.append("Direct processing completed. ");
        }
        message.append(succeeded).append(" of ").append(requested).append(" measurements succeeded");
        if (failed > 0) {
            message.append(", ").append(failed).append(" failed");
        }
        message.append('.');
        return message.toString();
    }

    private List<IndependentJobResult> directSuccessfulJobs() {
        return processingResultRegistry.all().stream()
                .filter(IndependentJobResult::successful)
                .filter(result -> result.catalogHandle().isPresent())
                .filter(result -> "direct-processing".equals(result.technicalDetail()))
                .toList();
    }

    private static boolean isDirectProcessingBatch(List<IndependentJobResult> successful) {
        return successful.stream().allMatch(result -> "direct-processing".equals(result.technicalDetail()));
    }

    /**
     * Publishes one logical signal per compatible series. Each measurement keeps its own
     * artifact; All Measurements reads those series through a timestamp merge.
     */
    private void publishDirectMeasurementCatalog(List<IndependentJobResult> successful) {
        List<ProjectMergedSignalStore.Member> members = new ArrayList<>();
        for (IndependentJobResult result : successful) {
            Object handle = result.catalogHandle().orElse(null);
            if (!(handle instanceof SignalSeriesStore store)) {
                continue;
            }
            String sourceId = result.projectSourceId();
            String name = workspace().findFile(sourceId).map(WorkspaceFile::fileName).orElse(sourceId);
            members.add(new ProjectMergedSignalStore.Member(
                    store, sourceId, "_none_", name, "Direct"));
        }
        if (members.isEmpty()) {
            return;
        }
        String summary = members.size() == 1
                ? members.getFirst().measurementDisplayName()
                : members.size() + " measurements";
        ProjectMergedSignalStore store = ProjectMergedSignalStore.fromMembers(members, summary, "Direct");
        signalsModel.replaceCatalog(
                store,
                "Direct processing · " + summary,
                SignalsProcessingContext.of(summary, "", "Direct", SignalViewScope.PROJECT),
                true);
        if (members.size() > 1) {
            signalsModel.requestAllMeasurementsNavigation();
            analysisScopeRef.set(AnalysisScope.allMeasurements(workspaceMeasurementCount()));
        }
        refreshSignalCacheFromStore();
    }

    private void persistDirectBatchRun(ProjectProcessingStatus completion, int jobCount, String message) {
        if (!isDurableNamedProject()) {
            return;
        }
        String status = switch (completion) {
            case COMPLETED -> ProcessingRunStatuses.COMPLETED;
            case COMPLETED_WITH_WARNINGS -> ProcessingRunStatuses.COMPLETED_WITH_WARNINGS;
            case CANCELLED -> ProcessingRunStatuses.CANCELLED;
            default -> ProcessingRunStatuses.FAILED;
        };
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        ProcessingRunMetadata run = new ProcessingRunMetadata(
                runId,
                metadataProjectId.get(),
                status,
                false,
                jobCount,
                now,
                now,
                message);
        persistWrite(() -> metadataStore.upsertProcessingRun(run));
        recordResidentProcessingRun(run);
    }

    /**
     * Starts LOCAL processing on a background executor using the primary workflow options
     * (auto-navigate to Signals on success; keep prior result until success).
     */
    public void startLocalProcessing(Consumer<Runnable> uiUpdater, Runnable onUiRefresh) {
        startLocalProcessing(uiUpdater, onUiRefresh, ProcessingStartOptions.primaryWorkflow());
    }

    /**
     * Starts LOCAL processing on a background executor.
     *
     * @param uiUpdater marshals UI refresh onto the JavaFX thread (may be identity in tests)
     * @param options   navigation / result-preservation policy
     */
    public void startLocalProcessing(
            Consumer<Runnable> uiUpdater,
            Runnable onUiRefresh,
            ProcessingStartOptions options
    ) {
        Objects.requireNonNull(uiUpdater, "uiUpdater");
        ProcessingStartOptions opts = options == null ? ProcessingStartOptions.primaryWorkflow() : options;
        if (selectedDirectMeasurement().isPresent() && directMeasurementFiles().size() > 1) {
            startAllDirectProcessing(uiUpdater, onUiRefresh, opts);
            return;
        }
        InputValidationResult validation = validateCurrentSelection();
        if (!validation.valid()) {
            workspace().setProcessingStatus(ProjectProcessingStatus.FAILED);
            processingModel.resetForRun(
                    workspace().selectedMeasurement().map(WorkspaceFile::fileName).orElse(""),
                    workspace().selectedDefinition().map(WorkspaceFile::fileName).orElse("")
            );
            processingModel.applyProgress(ProcessingStage.FAILED, new ProcessingStatistics());
            if (!opts.preserveExistingResultUntilSuccess() || !signalsModel.processingContext().hasResult()) {
                projectProcessingContext.markReprocessFailure("Cannot start processing: selection invalid.");
            }
            uiUpdater.accept(onUiRefresh);
            return;
        }

        WorkspaceFile measurement = workspace().selectedMeasurement().orElseThrow();
        boolean direct = MeasurementSourceCapability.supportsDirectProcessing(measurement.kind());
        WorkspaceFile definition = direct ? null : workspace().selectedDefinition().orElseThrow();
        ChannelSelectionModel.SelectionSummary selectionSummary;
        ProcessingSelection selection;
        if (direct) {
            selectionSummary = new ChannelSelectionModel.SelectionSummary(0, 0, 0, "Direct", false);
            selection = null;
        } else {
            syncChannelSelection();
            if (!channelSelectionModel.hasSelection()) {
                workspace().setProcessingStatus(ProjectProcessingStatus.FAILED);
                processingModel.resetForRun(measurement.fileName(), definition.fileName());
                processingModel.applyProgress(ProcessingStage.FAILED, new ProcessingStatistics());
                uiUpdater.accept(onUiRefresh);
                return;
            }
            selection = channelSelectionModel.toProcessingSelection();
            selectionSummary = channelSelectionModel.summarize(
                    sourceInventoryModel.focusedInventory().orElse(null),
                    sourceInventoryModel.matchState());
        }

        // Bump generation first so a finishing prior job cannot swap results, then cancel it.
        long generation = projectProcessingContext.beginRequest();
        cancelProcessing();

        boolean hadResult = signalsModel.processingContext().hasResult();
        boolean preserve = opts.preserveExistingResultUntilSuccess() && hadResult;
        projectProcessingContext.setPreserveDisplayedResult(preserve);
        projectProcessingContext.clearFailure();
        if (hadResult) {
            projectProcessingContext.setViewingResultChannels(
                    signalsModel.processingContext().channelsSummary());
            projectProcessingContext.setUpdatingToChannels(selectionSummary.channelLabel());
        } else {
            projectProcessingContext.setViewingResultChannels("");
            projectProcessingContext.setUpdatingToChannels(selectionSummary.channelLabel());
        }

        processingModel.resetForRun(
                measurement.fileName(),
                definition == null ? "" : definition.fileName(),
                selectionSummary.channelLabel(),
                selectionSummary.selectedMatchedIds());
        processingModel.setMf4Hints(measurement.mf4FinalizationLabel(), measurement.recoveryCandidate());
        workspace().setProcessingStatus(ProjectProcessingStatus.RUNNING);

        SignalPreviewBuffer preview = new SignalPreviewBuffer(previewCapacity());
        if (!preserve) {
            // First result (or explicit non-preserve): clear before decode so Signals isn't stale.
            signalsModel.clear();
            signalsModel.replaceBuffer(preview);
        }

        if (opts.openProcessingPageOnStart()) {
            currentPage.set(NavigationPage.PROCESSING);
        }
        uiUpdater.accept(onUiRefresh);

        ProcessingGateway gateway = session.processingGateway();
        CoalescingUiDispatcher dispatcher = new CoalescingUiDispatcher(uiUpdater);
        Future<?> future = processingExecutor.submit(() -> {
            ProcessingProgressListener progress = new ProcessingProgressListener() {
                @Override
                public void onStage(ProcessingStage stage) {
                    if (!projectProcessingContext.isCurrentGeneration(generation)) {
                        return;
                    }
                    processingModel.applyProgress(stage, null);
                    if (stage == ProcessingStage.COMPLETED
                            || stage == ProcessingStage.FAILED
                            || stage == ProcessingStage.CANCELLED) {
                        dispatcher.submitImmediate(onUiRefresh);
                    } else {
                        dispatcher.submit(onUiRefresh);
                    }
                }

                @Override
                public void onStatistics(ProcessingStatistics statistics) {
                    if (!projectProcessingContext.isCurrentGeneration(generation)) {
                        return;
                    }
                    processingModel.applyProgress(null, statistics);
                    dispatcher.submit(onUiRefresh);
                }
            };

            Path directRoot = null;
            if (direct && isDurableNamedProject()) {
                try {
                    directRoot = prepareDirectArtifactStaging(measurement);
                } catch (RuntimeException ex) {
                    LOG.log(System.Logger.Level.ERROR, "Direct processing storage is not writable: "
                            + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
                    workspace().setProcessingStatus(ProjectProcessingStatus.FAILED);
                    processingModel.applyProgress(ProcessingStage.FAILED, new ProcessingStatistics());
                    dispatcher.submitImmediate(onUiRefresh);
                    return;
                }
            }
            Consumer<List<com.stellar.platform.domain.CanonicalSignal>> previewListener = batch -> {
                if (projectProcessingContext.isCurrentGeneration(generation)) {
                    dispatcher.submit(onUiRefresh);
                }
            };
            LocalProcessingGateway.GatewayResult result;
            if (directRoot != null) {
                if (!(gateway instanceof LocalProcessingGateway localGateway)) {
                    workspace().setProcessingStatus(ProjectProcessingStatus.FAILED);
                    processingModel.applyProgress(ProcessingStage.FAILED, new ProcessingStatistics());
                    dispatcher.submitImmediate(onUiRefresh);
                    return;
                }
                result = localGateway.process(
                        measurement,
                        definition,
                        ProcessingRequest.DEFAULT_BATCH_SIZE,
                        preview,
                        progress,
                        previewListener,
                        selection,
                        directRoot
                );
            } else {
                result = gateway.process(
                        measurement,
                        definition,
                        ProcessingRequest.DEFAULT_BATCH_SIZE,
                        preview,
                        progress,
                        previewListener,
                        selection
                );
            }

            if (!projectProcessingContext.isCurrentGeneration(generation)) {
                return;
            }

            var summary = result.summary();
            ProjectProcessingStatus completion = summary == null
                    ? ProjectProcessingStatus.FAILED
                    : summary.completionStatus();
            boolean success = completion == ProjectProcessingStatus.COMPLETED
                    || completion == ProjectProcessingStatus.COMPLETED_WITH_WARNINGS;

            if (success && directRoot != null) {
                Optional<SignalSeriesStore> committed = commitDirectProcessingResult(measurement, result);
                if (committed.isEmpty()) {
                    applyFailedOrCancelledResult(
                            persistenceFailedSummary(summary),
                            selectionSummary,
                            false,
                            ProjectProcessingStatus.FAILED
                    );
                } else {
                    applySuccessfulResult(
                            new LocalProcessingGateway.GatewayResult(
                                    summary,
                                    result.preview(),
                                    result.processingResult(),
                                    result.failureTechnicalDetail(),
                                    committed.get()
                            ),
                            measurement,
                            definition,
                            selectionSummary,
                            preserve,
                            opts.navigateToSignalsOnSuccess()
                    );
                }
            } else if (success) {
                applySuccessfulResult(
                        result,
                        measurement,
                        definition,
                        selectionSummary,
                        preserve,
                        opts.navigateToSignalsOnSuccess()
                );
            } else {
                applyFailedOrCancelledResult(summary, selectionSummary, preserve, completion);
            }
            dispatcher.submitImmediate(onUiRefresh);
        });
        runningJob.set(future);
    }

    private void applySuccessfulResult(
            LocalProcessingGateway.GatewayResult result,
            WorkspaceFile measurement,
            WorkspaceFile definition,
            ChannelSelectionModel.SelectionSummary selectionSummary,
            boolean preserveWorkspace,
            boolean navigateToSignals
    ) {
        signalsModel.replaceBuffer(result.preview());
        String hint = result.summary() == null ? null : result.summary().userMessage();
        SignalsProcessingContext signalsContext = SignalsProcessingContext.of(
                measurement.fileName(),
                definition == null ? "" : definition.fileName(),
                selectionSummary.channelLabel()
        );
        signalsModel.replaceCatalog(result.catalogStore(), hint, signalsContext, preserveWorkspace);
        var summary = result.summary();
        if (summary != null) {
            summary = new com.stellar.platform.desktop.project.ProcessingSummary(
                    summary.sourceFormat(),
                    summary.sourceFile(),
                    summary.dbcFile(),
                    summary.statistics(),
                    summary.elapsed(),
                    summary.completionStatus(),
                    summary.diagnostics(),
                    summary.userMessage(),
                    summary.technicalDetail(),
                    summary.recoveryUsed(),
                    summary.recoveryState(),
                    summary.mf4FileStatus(),
                    selectionSummary.channelLabel(),
                    selectionSummary.selectedMatchedIds()
            );
        }
        processingModel.applySummary(summary);
        workspace().setLatestSummary(summary);
        workspace().setProcessingStatus(summary.completionStatus());
        channelSelectionModel.markProcessed();
        projectProcessingContext.setViewingResultChannels(selectionSummary.channelLabel());
        projectProcessingContext.clearUpdatingBanner();
        projectProcessingContext.clearFailure();
        projectProcessingContext.setPreserveDisplayedResult(false);
        if (navigateToSignals) {
            projectProcessingContext.markAutoNavigateToSignals();
            currentPage.set(NavigationPage.SIGNALS);
        }
    }

    private void applyFailedOrCancelledResult(
            com.stellar.platform.desktop.project.ProcessingSummary summary,
            ChannelSelectionModel.SelectionSummary selectionSummary,
            boolean preserve,
            ProjectProcessingStatus completion
    ) {
        if (summary != null) {
            summary = new com.stellar.platform.desktop.project.ProcessingSummary(
                    summary.sourceFormat(),
                    summary.sourceFile(),
                    summary.dbcFile(),
                    summary.statistics(),
                    summary.elapsed(),
                    summary.completionStatus(),
                    summary.diagnostics(),
                    summary.userMessage(),
                    summary.technicalDetail(),
                    summary.recoveryUsed(),
                    summary.recoveryState(),
                    summary.mf4FileStatus(),
                    selectionSummary.channelLabel(),
                    selectionSummary.selectedMatchedIds()
            );
            processingModel.applySummary(summary);
        } else {
            processingModel.applyProgress(ProcessingStage.FAILED, new ProcessingStatistics());
            workspace().setProcessingStatus(completion);
        }
        // Keep prior completed summary/result when preserving; do not blank Signals.
        if (!preserve) {
            workspace().setLatestSummary(summary);
            workspace().setProcessingStatus(completion);
        } else {
            // Restore workspace status to previous completed result if still present.
            workspace().latestSummary().ifPresentOrElse(
                    prev -> workspace().setProcessingStatus(prev.completionStatus()),
                    () -> workspace().setProcessingStatus(completion)
            );
            String previous = projectProcessingContext.viewingResultChannels();
            String msg = completion == ProjectProcessingStatus.CANCELLED
                    ? "Reprocessing cancelled. Previous " + previous + " result is still displayed."
                    : "Reprocessing failed. Previous " + previous + " result is still displayed.";
            projectProcessingContext.markReprocessFailure(msg);
        }
        projectProcessingContext.clearUpdatingBanner();
        projectProcessingContext.setPreserveDisplayedResult(false);
    }


    /**
     * Executes the immutable reviewed plan snapshot on the processing background thread.
     * Does not rebuild from checkboxes; stale mapping/inventory fails closed to PLAN_REVIEW.
     */
    public void executeReviewedPlan(Consumer<Runnable> uiUpdater, Runnable onUiRefresh) {
        executeReviewedPlan(uiUpdater, onUiRefresh, false);
    }

    /** Explicit user action that bypasses incremental reuse and decodes every current job again. */
    public void forceReprocessReviewedPlan(Consumer<Runnable> uiUpdater, Runnable onUiRefresh) {
        executeReviewedPlan(uiUpdater, onUiRefresh, true);
    }

    private void executeReviewedPlan(
            Consumer<Runnable> uiUpdater,
            Runnable onUiRefresh,
            boolean forceReprocess
    ) {
        Consumer<Runnable> ui = uiUpdater == null ? PlatformUi.NOOP : uiUpdater;
        Runnable refresh = onUiRefresh == null ? () -> {
        } : onUiRefresh;
        ProcessingSelectionWorkspaceModel model = processingSelectionModel;
        if (isPlanExecutionActive() || !planExecutionStarting.compareAndSet(false, true)) {
            ui.accept(refresh);
            return;
        }
        boolean started = false;
        ProjectProcessingPlan executionPlan;
        try {
            ProcessingPlanInputAssembler assembler = new ProcessingPlanInputAssembler();
            ProcessingPlanInputs currentInputs = assembler.assemble(this);
            ProjectProcessingPlan reviewed = prepareActiveExecutionPlan(model, currentInputs, forceReprocess);
            if (reviewed == null || reviewed.isEmpty()) {
                model.markPlanRequiresReview("PLAN REQUIRES REVIEW — no reviewed plan");
                ui.accept(refresh);
                return;
            }
            if (!isReviewedPlanStillExecutable(reviewed, currentInputs)) {
                model.markPlanRequiresReview("PLAN REQUIRES REVIEW");
                ui.accept(refresh);
                return;
            }

            executionPlan = reviewed;
            if (!forceReprocess) {
                // Seed registry from VALID SQLite artifacts so reuse works across restarts
                // without a second processing engine.
                seedReusableArtifactsFromMetadata(executionPlan);
            }
            if (forceReprocess) {
                model.beginExecution(executionPlan);
            } else {
                model.beginIncrementalExecution(executionPlan, processingResultRegistry);
            }
            started = true;
        } finally {
            if (!started) {
                planExecutionStarting.set(false);
            }
        }
        ui.accept(refresh);

        SimpleCancellationToken token = new SimpleCancellationToken();
        planExecutionToken.set(token);
        CoalescingUiDispatcher dispatcher = new CoalescingUiDispatcher(ui);

        MeasurementDecodePipeline pipeline = session.decodePipeline();
        Map<String, DbcDatabase> definitionCache = new HashMap<>();

        PlanExecutionContext context = PlanExecutionContext.builder(pipeline)
                .measurementPathResolver(sourceId -> workspace().findFile(sourceId)
                        .map(WorkspaceFile::path)
                        .orElse(null))
                .definitionDatabaseResolver(definitionId -> loadDefinitionDatabaseCached(definitionId, definitionCache))
                .definitionPathResolver(definitionId -> workspace().findFile(definitionId)
                        .filter(f -> f.kind().definition())
                        .map(WorkspaceFile::path)
                        .orElse(null))
                .cancellationToken(token)
                .signalStoreFactory(this::createPersistentSignalStore)
                .build();

        ProjectPlanExecutor executor = new ProjectPlanExecutor(context, processingResultRegistry);
        String metadataRunId = UUID.randomUUID().toString();
        Instant metadataRunStarted = Instant.now();
        currentProcessingRunId.set(metadataRunId);
        finalizeOrphanRunningRuns(metadataRunId, "Superseded by a newer processing run");
        persistProcessingRun(metadataRunId, ProcessingRunStatuses.RUNNING, forceReprocess,
                executionPlan.jobs().size(), metadataRunStarted, null, "");
        finalizedProcessingOutcome.set(null);
        if (forceReprocess) {
            releaseForcedReprocessHandles(executionPlan);
        }
        LOG.log(System.Logger.Level.INFO, () -> "Processing run created runId=" + metadataRunId
                + " jobs=" + executionPlan.jobs().size()
                + " force=" + forceReprocess
                + " readiness=" + executionPlan.readiness()
                + " isolation=" + (isolateProductionProcessing ? "PROCESS_ISOLATED" : "IN_PROCESS"));
        for (ProcessingJob job : executionPlan.jobs()) {
            LOG.log(System.Logger.Level.INFO, () -> "Processing run job=" + job.key()
                    + " runId=" + metadataRunId
                    + " source=" + job.projectSourceId()
                    + " channel=" + job.channelId()
                    + " mapping=" + job.mappingLifecycle()
                    + " definitionId=" + (job.definitionSourceId().isPresent() ? "present" : "absent")
                    + " executable=" + job.executable()
                    + " readiness=" + executionPlan.readiness());
        }
        PlanJobLifecycleListener life = new PlanJobLifecycleListener() {
            @Override
            public void onJobStarting(ProcessingJob job, int index, int total) {
                model.updateJobStatus(job.key(), JobExecutionStatus.RUNNING);
                persistJobLifecycle(metadataRunId, job, JobExecutionStatus.RUNNING, null, index, total);
                LOG.log(System.Logger.Level.INFO, () -> "Scheduler admitted job=" + job.key()
                        + " index=" + index + "/" + total);
                Runnable startingProbe = planJobStartingProbeForTest;
                if (startingProbe != null) {
                    startingProbe.run();
                }
                dispatcher.submit(refresh);
            }

            @Override
            public void onJobFinished(IndependentJobResult result, int index, int total) {
                model.updateJobStatus(result.jobKey(), result);
                ProcessingJob job = executionPlan.jobs().stream()
                        .filter(item -> item.key().equals(result.jobKey()))
                        .findFirst()
                        .orElse(null);
                persistJobLifecycle(metadataRunId, job, result.status(), result, index, total);
                dispatcher.submit(refresh);
            }

            @Override
            public void onJobReused(ProcessingJob job, IndependentJobResult result, int index, int total) {
                model.updateJobStatus(job.key(), result);
                dispatcher.submit(refresh);
            }
        };

        Future<?> future = processingExecutor.submit(() -> {
            try {
                Runnable probe = planExecutionThreadProbeForTest;
                if (probe != null) {
                    probe.run();
                }
                if (token.isCancellationRequested()) {
                    discardCancelledStaging(executionPlan, null);
                    finalizeCancelledRun(metadataRunId, executionPlan, forceReprocess, metadataRunStarted,
                            model, dispatcher, refresh, "Cancelled");
                    return;
                }
                MultiSourceProcessingResult batch;
                if (session.executionMode() == com.stellar.platform.processing.ExecutionMode.REMOTE) {
                    batch = executePlanViaPlatformClient(
                            executionPlan,
                            forceReprocess,
                            token,
                            life,
                            dispatcher,
                            refresh,
                            model);
                } else if (isolateProductionProcessing) {
                    batch = executeReviewedPlanInIsolatedWorker(
                            executionPlan, forceReprocess, metadataRunId, token, life, dispatcher, refresh, model);
                } else {
                    batch = executor.execute(
                            executionPlan,
                            life,
                            forceReprocess ? PlanExecutionPolicy.FORCE_REPROCESS : PlanExecutionPolicy.REUSE_COMPLETED);
                }
                if (shouldTreatRunAsCancelled(token, batch)) {
                    LOG.log(System.Logger.Level.INFO, () -> "Skip artifact commit — run cancelled; "
                            + "last valid generation retained runId=" + metadataRunId);
                    discardCancelledStaging(executionPlan, batch);
                    finalizeCancelledRun(metadataRunId, executionPlan, forceReprocess, metadataRunStarted,
                            model, dispatcher, refresh, "Cancelled");
                    return;
                }
                String outcome = ProcessingRunStatuses.forJobs(batch.status(), processingResultRegistry.all());
                finalizeProcessingRun(
                        metadataRunId,
                        outcome,
                        forceReprocess,
                        executionPlan.jobs().size(),
                        metadataRunStarted,
                        batchOutcomeMessage(batch),
                        batch,
                        model,
                        dispatcher,
                        refresh,
                        session.executionMode() != com.stellar.platform.processing.ExecutionMode.REMOTE);
            } catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING,
                        "Plan execution failed: " + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
                if (token.isCancellationRequested() || isUserCancelException(ex)) {
                    if (!adoptDurableSuccessfulCompletion(metadataRunId, executionPlan, forceReprocess,
                            metadataRunStarted, model, dispatcher, refresh)) {
                        discardCancelledStaging(executionPlan, null);
                        finalizeCancelledRun(metadataRunId, executionPlan, forceReprocess, metadataRunStarted,
                                model, dispatcher, refresh, "Cancelled");
                    }
                } else {
                    String failureMessage = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                    logPlanExecutionRejection(metadataRunId, executionPlan, "EXECUTION_FAILED", failureMessage, ex);
                    persistPlanJobs(metadataRunId, executionPlan, JobExecutionStatus.FAILED, failureMessage,
                            forceReprocess);
                    model.markExecutionFailed(
                            "PLAN REQUIRES REVIEW — execution failed",
                            failedBatchFromException(executionPlan, failureMessage));
                    finalizeProcessingRun(
                            metadataRunId,
                            ProcessingRunStatuses.FAILED,
                            forceReprocess,
                            executionPlan.jobs().size(),
                            metadataRunStarted,
                            failureMessage,
                            null,
                            model,
                            dispatcher,
                            refresh,
                            false);
                }
            } finally {
                planExecutionToken.compareAndSet(token, null);
                currentProcessingRunId.compareAndSet(metadataRunId, null);
                isolatedTaskId.compareAndSet(metadataRunId, null);
            }
        });
        runningJob.set(future);
        // From here the running future and EXECUTING mode are the authoritative guard.
        planExecutionStarting.set(false);
    }

    /**
     * Applies one worker PROGRESS event to the live job rows. Job events name their job by key;
     * in-job events only carry the frame count. Full results still arrive with the terminal event.
     */
    static void applyWorkerJobProgress(
            WorkerMessage incoming,
            ProjectProcessingPlan plan,
            PlanJobLifecycleListener life,
            ProcessingSelectionWorkspaceModel model
    ) {
        String stage = incoming.stage();
        if (stage == null) {
            return;
        }
        if (WorkerProtocol.STAGE_JOB_PROGRESS.equals(stage)) {
            if (incoming.batchSequence() != null) {
                model.updateLiveSourceFramesRead(incoming.batchSequence());
            }
            return;
        }
        String key = incoming.message();
        if (key == null || key.isBlank()) {
            return;
        }
        List<ProcessingJob> jobs = plan.jobs();
        for (int index = 0; index < jobs.size(); index++) {
            ProcessingJob job = jobs.get(index);
            if (!job.key().toString().equals(key)) {
                continue;
            }
            if (WorkerProtocol.STAGE_JOB_STARTED.equals(stage)) {
                model.updateLiveSourceFramesRead(0L);
                life.onJobStarting(job, index, jobs.size());
                return;
            }
            JobExecutionStatus status;
            try {
                status = JobExecutionStatus.valueOf(stage);
            } catch (IllegalArgumentException notAStatus) {
                return;
            }
            if (status.isTerminal()) {
                model.updateJobStatus(job.key(), status);
            }
            return;
        }
    }

    private MultiSourceProcessingResult executeReviewedPlanInIsolatedWorker(
            ProjectProcessingPlan executionPlan,
            boolean forceReprocess,
            String metadataRunId,
            SimpleCancellationToken token,
            PlanJobLifecycleListener life,
            CoalescingUiDispatcher dispatcher,
            Runnable refresh,
            ProcessingSelectionWorkspaceModel model
    ) {
        if (isolatedExecutionFailureForTest != null && !isolatedExecutionFailureForTest.isBlank()) {
            throw new IllegalStateException(isolatedExecutionFailureForTest);
        }
        ensureIsolatedProcessingPrerequisites();
        LocalResourceManager.Admission admission = admitLocalProcessing(executionPlan);
        if (!admission.allowed()) {
            persistPlanJobs(metadataRunId, executionPlan, JobExecutionStatus.BLOCKED, admission.reason(),
                    forceReprocess);
            appendAudit(AuditEventType.PROCESSING_FAILED, metadataRunId, admission.reason());
            throw new IllegalStateException(admission.reason());
        }
        Path projectDb = activeDatabasePath().orElse(null);
        Path analysisRoot = analysisStorageService.userSelectedRoot().orElse(null);
        if (projectDb == null || !Files.isRegularFile(projectDb) || analysisRoot == null) {
            throw new IllegalStateException(
                    "Isolated processing requires the central metadata database and configured Analysis Storage");
        }
        isolatedTaskId.set(metadataRunId);
        persistPlanJobs(metadataRunId, executionPlan, JobExecutionStatus.QUEUED, "", forceReprocess);
        appendAudit(AuditEventType.PROCESSING_STARTED, metadataRunId, "Isolated worker PLAN");
        Map<String, String> sourcePaths = new HashMap<>();
        Map<String, String> definitionPaths = new HashMap<>();
        for (ProcessingJob job : executionPlan.jobs()) {
            workspace().findFile(job.projectSourceId())
                    .ifPresent(f -> sourcePaths.put(job.projectSourceId(), f.path().toString()));
            job.definitionSourceId().flatMap(id -> workspace().findFile(id))
                    .ifPresent(f -> definitionPaths.put(f.id(), f.path().toString()));
        }
        ProcessingTaskDescriptor task = ProcessingTaskDescriptor.plan(
                metadataRunId,
                metadataProjectId.get(),
                metadataRunId,
                projectDb.toAbsolutePath().toString(),
                analysisRoot.toAbsolutePath().toString(),
                ProcessingPlanDtoMapper.toJson(executionPlan),
                sourcePaths,
                definitionPaths,
                forceReprocess
        );
        int attempt = 1;
        while (true) {
            if (token.isCancellationRequested()) {
                persistPlanJobs(metadataRunId, executionPlan, JobExecutionStatus.CANCELLED, "Cancelled",
                        forceReprocess);
                throw new IllegalStateException("Cancelled");
            }
            ProcessIsolatedLocalWorker worker = ensureIsolatedWorker();
            try {
                    WorkerMessage done = worker.submitTask(task, incoming -> {
                    String type = incoming.type() == null ? "" : incoming.type();
                    if (incoming.task() != null && !WorkerProtocol.START_JOB.equals(type)) {
                        LOG.log(System.Logger.Level.WARNING,
                                "Worker event carried a task descriptor type=" + type);
                    }
                    if (WorkerProtocol.JOB_ACCEPTED.equals(type)) {
                        LOG.log(System.Logger.Level.INFO, () -> "Worker accepted task=" + metadataRunId);
                    }
                    if (WorkerProtocol.isTerminal(type)) {
                        LOG.log(System.Logger.Level.INFO, () -> "Worker terminal event received type=" + type
                                + " runId=" + metadataRunId + " jobId=" + incoming.taskId());
                    }
                    if (WorkerProtocol.PROGRESS.equals(type)) {
                        applyWorkerJobProgress(incoming, executionPlan, life, model);
                    }
                    dispatcher.submit(refresh);
                }).get();
                if (WorkerProtocol.isSuccessfulTerminal(done.type()) && !outcomeCancelled(done)) {
                    LOG.log(System.Logger.Level.INFO, () -> "Worker successful terminal type=" + done.type()
                            + " runId=" + metadataRunId);
                    checkpointMetadataWal();
                    seedReusableArtifactsFromMetadata(executionPlan);
                    republishSignalsFromRegistry();
                    LOG.log(System.Logger.Level.INFO, () -> "Result hydrated after worker terminal runId="
                            + metadataRunId + " jobs=" + processingResultRegistry.all().size());
                    MultiSourceProcessingResult batch = processingResultRegistry.latestBatch()
                            .orElseGet(() -> executorBatchFromRegistry(executionPlan));
                    for (IndependentJobResult result : batch.jobResults()) {
                        if (result.successful() && result.technicalDetail() != null
                                && result.technicalDetail().contains("reopened-from-durable-job")) {
                            ProcessingJob job = executionPlan.jobs().stream()
                                    .filter(j -> j.key().equals(result.jobKey()))
                                    .findFirst()
                                    .orElse(null);
                            if (job != null) {
                                life.onJobReused(job, result, 0, executionPlan.jobs().size());
                            }
                        } else {
                            life.onJobFinished(result, 0, executionPlan.jobs().size());
                        }
                    }
                    appendAudit(AuditEventType.PROCESSING_COMPLETED, metadataRunId, batch.status().name());
                    return batch;
                }
                if (token.isCancellationRequested()
                        || WorkerProtocol.JOB_CANCELLED.equals(done.type())
                        || outcomeCancelled(done)) {
                    persistIncompleteJobsCancelled(metadataRunId, executionPlan, model);
                    seedReusableArtifactsFromMetadata(executionPlan);
                    republishSignalsFromRegistry();
                    return cancelledBatchPreservingValid(executionPlan);
                }
                if (WorkerProtocol.FAILED.equals(done.type()) || WorkerProtocol.JOB_FAILED.equals(done.type())) {
                    ProcessingFailureClass failure = ProcessingFailureClass.parse(
                            done.classification(), ProcessingFailureClass.INTERNAL_ERROR);
                    if (retryManager.shouldRetry(failure, attempt, RetryRecoveryManager.DEFAULT_MAX_ATTEMPTS)) {
                        attempt++;
                        sleepBackoff(attempt);
                        continue;
                    }
                    String workerMessage = done.message() == null ? "Worker failed" : done.message();
                    persistPlanJobs(metadataRunId, executionPlan, JobExecutionStatus.FAILED, workerMessage,
                            forceReprocess);
                    seedReusableArtifactsFromMetadata(executionPlan);
                    appendAudit(AuditEventType.PROCESSING_FAILED, metadataRunId, done.message());
                    logPlanExecutionRejection(metadataRunId, executionPlan,
                            done.classification() == null ? "WORKER_FAILED" : done.classification(),
                            workerMessage, null);
                    throw new IllegalStateException(workerMessage);
                }
                checkpointMetadataWal();
                seedReusableArtifactsFromMetadata(executionPlan);
                republishSignalsFromRegistry();
                MultiSourceProcessingResult batch = processingResultRegistry.latestBatch()
                        .orElseGet(() -> executorBatchFromRegistry(executionPlan));
                for (IndependentJobResult result : batch.jobResults()) {
                    if (result.successful() && result.technicalDetail() != null
                            && result.technicalDetail().contains("reopened-from-durable-job")) {
                        ProcessingJob job = executionPlan.jobs().stream()
                                .filter(j -> j.key().equals(result.jobKey()))
                                .findFirst()
                                .orElse(null);
                        if (job != null) {
                            life.onJobReused(job, result, 0, executionPlan.jobs().size());
                        }
                    } else {
                        life.onJobFinished(result, 0, executionPlan.jobs().size());
                    }
                }
                appendAudit(AuditEventType.PROCESSING_COMPLETED, metadataRunId, batch.status().name());
                return batch;
            } catch (WorkerDiedException died) {
                new LocalWorkerSupervisor(metadataStore)
                        .markRunningJobsInterrupted(metadataProjectId.get(), died.getMessage());
                appendAudit(AuditEventType.WORKER_CRASHED, metadataRunId, died.getMessage());
                if (retryManager.shouldRetry(
                        ProcessingFailureClass.WORKER_CRASH, attempt, RetryRecoveryManager.DEFAULT_MAX_ATTEMPTS)) {
                    attempt++;
                    restartIsolatedWorker();
                    appendAudit(AuditEventType.WORKER_RESTARTED, metadataRunId, "Retry after worker crash");
                    sleepBackoff(attempt);
                    continue;
                }
                throw died;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted", ie);
            } catch (java.util.concurrent.ExecutionException ee) {
                Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                if (cause instanceof WorkerDiedException died) {
                    throw died;
                }
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new IllegalStateException(cause);
            }
        }
    }

    private boolean shouldTreatRunAsCancelled(SimpleCancellationToken token, MultiSourceProcessingResult batch) {
        if (token == null || !token.isCancellationRequested()) {
            return false;
        }
        if (batch != null && batch.status() != com.stellar.platform.domain.processing.plan.ProjectExecutionStatus.CANCELLED
                && batch.jobResults().stream().anyMatch(IndependentJobResult::successful)) {
            LOG.log(System.Logger.Level.INFO, () -> "Late cancel ignored — durable jobs already successful");
            return false;
        }
        if (ProcessingRunStatuses.isProtectedTerminal(finalizedProcessingOutcome.get())) {
            LOG.log(System.Logger.Level.INFO, () -> "Late cancel ignored — run already "
                    + finalizedProcessingOutcome.get());
            return false;
        }
        return true;
    }

    /**
     * Drops the in-progress {@code *.staging} store for a cancelled run.
     * The last committed generation is left in place.
     */
    private void discardCancelledStaging(ProjectProcessingPlan plan, MultiSourceProcessingResult batch) {
        if (batch != null) {
            for (IndependentJobResult result : batch.jobResults()) {
                Object handle = result.catalogHandle().orElse(null);
                if (handle instanceof GraphSignalSessionStore store) {
                    try {
                        store.close();
                    } catch (RuntimeException ignored) {
                        // The directory delete below is the cleanup that matters.
                    }
                }
            }
        }
        if (plan == null) {
            return;
        }
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        for (ProcessingJob job : plan.jobs()) {
            if (job == null) {
                continue;
            }
            String definitionId = job.definitionSourceId().orElse("");
            Path finalRoot = ArtifactStorageRefs.jobRoot(
                    projectId, job.projectSourceId(), job.channelId(), definitionId);
            deleteTreeBestEffort(finalRoot.resolveSibling(finalRoot.getFileName().toString() + ".staging"));
        }
    }

    private void finalizeCancelledRun(
            String runId,
            ProjectProcessingPlan executionPlan,
            boolean forceReprocess,
            Instant startedAt,
            ProcessingSelectionWorkspaceModel model,
            CoalescingUiDispatcher dispatcher,
            Runnable refresh,
            String message
    ) {
        persistIncompleteJobsCancelled(runId, executionPlan, model);
        republishSignalsFromRegistry();
        finalizeProcessingRun(
                runId,
                ProcessingRunStatuses.CANCELLED,
                forceReprocess,
                executionPlan == null ? 0 : executionPlan.jobs().size(),
                startedAt,
                message,
                null,
                model,
                dispatcher,
                refresh,
                false);
    }

    /**
     * Single idempotent terminal path: persist run, hydrate result, notify UI.
     * Safe if worker COMPLETED and a delayed Cancel both arrive.
     */
    private void finalizeProcessingRun(
            String runId,
            String terminalOutcome,
            boolean forceReprocess,
            int jobCount,
            Instant startedAt,
            String message,
            MultiSourceProcessingResult batch,
            ProcessingSelectionWorkspaceModel model,
            CoalescingUiDispatcher dispatcher,
            Runnable refresh,
            boolean commitArtifacts
    ) {
        final String requested = terminalOutcome == null ? ProcessingRunStatuses.FAILED : terminalOutcome;
        LOG.log(System.Logger.Level.INFO, () -> "finalizeProcessingRun invoked runId="
                + (runId == null ? "-" : runId) + " outcome=" + requested);
        if (!claimTerminalOutcome(runId, requested)) {
            dispatcher.submitImmediate(refresh);
            return;
        }
        String outcome = requested;
        if (ProcessingRunStatuses.isCancelled(outcome)) {
            model.cancelActiveExecution();
        } else if (batch != null) {
            model.completeExecution(batch);
            if (commitArtifacts) {
                persistProcessingArtifacts(runId, batch);
                for (IndependentJobResult current : processingResultRegistry.all()) {
                    if (current.status() == JobExecutionStatus.FAILED) {
                        model.updateJobStatus(current.jobKey(), current);
                    }
                }
                String reconciled = ProcessingRunStatuses.forJobs(
                        batch.status(), processingResultRegistry.all());
                if (!reconciled.equals(outcome)) {
                    finalizedProcessingOutcome.compareAndSet(outcome, reconciled);
                    outcome = reconciled;
                }
                refreshExecutionJobHandlesFromRegistry(model);
                republishSignalsFromRegistry();
            }
            LOG.log(System.Logger.Level.INFO, () -> "Result hydrated runId="
                    + (runId == null ? "-" : runId)
                    + " jobs=" + model.executionJobs().size());
        } else if (model.mode() == ProcessingPageMode.EXECUTING) {
            model.cancelActiveExecution();
        }
        persistJobWindowsFromResults(runId, batch);
        final String durable = outcome;
        persistProcessingRun(runId, durable, forceReprocess, jobCount, startedAt, Instant.now(), message);
        LOG.log(System.Logger.Level.INFO, () -> "durable terminal state runId="
                + (runId == null ? "-" : runId) + " status=" + durable
                + " journey=" + projectJourneyState());
        if (Boolean.TRUE.equals(durableNamedProject.get())
                && saveCoordinator.state() != ProjectSaveState.SAVE_FAILED) {
            markClean();
        } else if (saveCoordinator.state() != ProjectSaveState.SAVE_FAILED) {
            markDirty();
        }
        dispatcher.submitImmediate(refresh);
    }

    private boolean claimTerminalOutcome(String runId, String outcome) {
        while (true) {
            String previous = finalizedProcessingOutcome.get();
            if (ProcessingRunStatuses.isProtectedTerminal(previous)
                    && ProcessingRunStatuses.isCancelled(outcome)) {
                LOG.log(System.Logger.Level.INFO, () -> "Late cancel ignored runId="
                        + (runId == null ? "-" : runId) + " kept=" + previous);
                return false;
            }
            if (previous != null && previous.equals(outcome)) {
                return true;
            }
            if (ProcessingRunStatuses.isProtectedTerminal(previous)
                    && ProcessingRunStatuses.isProtectedTerminal(outcome)) {
                return true;
            }
            if (finalizedProcessingOutcome.compareAndSet(previous, outcome)) {
                return true;
            }
        }
    }

    private boolean adoptDurableSuccessfulCompletion(
            String runId,
            ProjectProcessingPlan plan,
            boolean forceReprocess,
            Instant startedAt,
            ProcessingSelectionWorkspaceModel model,
            CoalescingUiDispatcher dispatcher,
            Runnable refresh
    ) {
        checkpointMetadataWal();
        seedReusableArtifactsFromMetadata(plan);
        republishSignalsFromRegistry();
        if (processingResultRegistry.all().stream().noneMatch(IndependentJobResult::successful)) {
            return false;
        }
        MultiSourceProcessingResult batch = processingResultRegistry.latestBatch()
                .orElseGet(() -> executorBatchFromRegistry(plan));
        String outcome = ProcessingRunStatuses.forJobs(batch.status(), processingResultRegistry.all());
        if (!ProcessingRunStatuses.isProtectedTerminal(outcome)) {
            return false;
        }
        LOG.log(System.Logger.Level.INFO, () -> "Adopting durable terminal completion runId="
                + (runId == null ? "-" : runId) + " outcome=" + outcome);
        finalizeProcessingRun(
                runId, outcome, forceReprocess,
                plan == null ? 0 : plan.jobs().size(),
                startedAt, batchOutcomeMessage(batch),
                batch, model, dispatcher, refresh, false);
        return true;
    }

    /**
     * Production Start Processing uses an isolated worker JVM. Untitled/transient work and a
     * missing Analysis Storage setting must not convert an already-reviewed executable plan into
     * {@code PLAN REQUIRES REVIEW — execution failed}. Persist a managed project ledger when
     * Analysis Storage is already configured. A missing or unavailable root fails clearly.
     */
    private void ensureIsolatedProcessingPrerequisites() {
        if (analysisStorageService.userSelectedRoot().isEmpty()) {
            analysisStorageService.reloadFromDisk();
        }
        if (analysisStorageService.userSelectedRoot().isEmpty() || !analysisStorageService.isValid()) {
            throw new IllegalStateException(
                    "Analysis Storage is unavailable. Choose a writable folder in Configuration.");
        }
        if (!injectedPersistentStore) {
            ensureCentralMetadataStore();
            promoteUntitledWorkspaceToManagedProject();
        }
        Path projectDb = activeDatabasePath().orElse(null);
        if (projectDb != null && Files.isRegularFile(projectDb)) {
            persistAllFilesCritical();
            return;
        }
        throw new IllegalStateException("Isolated processing requires the central metadata database");
    }

    /**
     * Isolated Start Processing on untitled work must persist a named project ledger
     * in the central database. It must not create {@code .stellar/stellar.db}.
     */
    private void promoteUntitledWorkspaceToManagedProject() {
        if (injectedPersistentStore || isDurableNamedProject()) {
            return;
        }
        if (metadataStore instanceof SqliteProjectMetadataStore sqlite) {
            metadataProjectId.set(Long.toString(sqlite.allocateNumericId("projects")));
        }
        if (metadataProjectCreatedAt.get() == null) {
            metadataProjectCreatedAt.set(Instant.now());
        }
        Path root = AnalysisStorageRoots.projectArtifactsRoot(metadataProjectId.get());
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot create project artifact directory " + root, ex);
        }
        activeProjectRoot = root.toAbsolutePath().normalize();
        ProjectRootIndex.register(metadataProjectId.get(), activeProjectRoot);
    }

    private LocalResourceManager.Admission admitLocalProcessing(ProjectProcessingPlan plan) {
        long sourceBytes = 0L;
        if (plan != null) {
            for (ProcessingJob job : plan.jobs()) {
                Path path = workspace().findFile(job.projectSourceId()).map(WorkspaceFile::path).orElse(null);
                if (path != null && Files.isRegularFile(path)) {
                    try {
                        sourceBytes += Files.size(path);
                    } catch (java.io.IOException ignored) {
                    }
                }
            }
        }
        Path root = analysisStorageService.userSelectedRoot().orElse(null);
        return resourceManager.admit(
                root,
                sourceBytes,
                localProcessingWorker.activeApproximate(),
                8);
    }

    private ProcessIsolatedLocalWorker ensureIsolatedWorker() {
        if (localProcessingWorker instanceof ProcessIsolatedLocalWorker isolated && isolated.isAlive()) {
            return isolated;
        }
        ProcessIsolatedLocalWorker started;
        try {
            started = ProcessIsolatedLocalWorker.create();
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "Cannot start the isolated local processing worker. "
                            + "Processing did not fall back to in-process decode. "
                            + (ex.getMessage() == null ? ex.toString() : ex.getMessage()),
                    ex);
        }
        LocalWorkerBoundary previous = localProcessingWorker;
        localProcessingWorker = started;
        if (previous != null && previous != started) {
            try {
                previous.shutdown();
            } catch (RuntimeException ignored) {
            }
        }
        appendAudit(AuditEventType.WORKER_STARTED, "", "pid=" + started.workerPid());
        return started;
    }

    private void restartIsolatedWorker() {
        ProcessIsolatedLocalWorker previous = localProcessingWorker instanceof ProcessIsolatedLocalWorker iso
                ? iso : null;
        localProcessingWorker = new LocalWorkerSupervisor(metadataStore).startOrRestart(previous);
        appendAudit(AuditEventType.WORKER_RESTARTED, "", "worker restarted");
    }

    /**
     * Writes per-job start/finish from the executor's elapsed so Processing Complete can
     * rebuild source times after reopen. No new column: processing_jobs already has those instants.
     */
    private void persistJobWindowsFromResults(String runId, MultiSourceProcessingResult batch) {
        if (metadataStore == null || batch == null || runId == null || runId.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        Instant runStarted = latestPersistedProcessingRun()
                .filter(run -> runId.equals(run.runId()))
                .map(ProcessingRunMetadata::startedAt)
                .orElse(null);
        Optional<Duration> runElapsed = ProcessingElapsed.totalElapsed(
                batch.elapsed(), runStarted, now);
        for (IndependentJobResult result : batch.jobResults()) {
            if (result == null || result.jobKey() == null) {
                continue;
            }
            IndependentJobResult persisted = processingResultRegistry.get(result.jobKey()).orElse(result);
            ProcessingJobMetadata prior = metadataStore.findProcessingJob(persisted.jobKey().toString()).orElse(null);
            boolean reused = reusedResult(persisted);
            if (reused && prior != null && !runId.equals(prior.runId())) {
                continue;
            }
            Duration elapsed = persisted.elapsed();
            if (elapsed != null && ProcessingElapsed.materiallyExceedsRun(elapsed, runElapsed)) {
                elapsed = null;
            }
            Instant[] window = LocalPlanWorkerEngine.executionWindow(
                    prior, persisted.status(), reused, now, elapsed, runId, runStarted);
            boolean degenerate = window[0] == null || window[1] == null || !window[1].isAfter(window[0]);
            boolean priorValidSameRun = prior != null && runId.equals(prior.runId())
                    && prior.startedAt() != null && prior.finishedAt() != null
                    && prior.finishedAt().isAfter(prior.startedAt())
                    && (runStarted == null || !prior.startedAt().isBefore(runStarted));
            if (degenerate && priorValidSameRun) {
                continue;
            }
            if (!degenerate && runStarted != null && window[0].isBefore(runStarted)) {
                continue;
            }
            persistWriteQuiet(() -> metadataStore.upsertProcessingJob(new ProcessingJobMetadata(
                    persisted.jobKey().toString(),
                    metadataProjectId.get(),
                    runId,
                    persisted.projectSourceId(),
                    persisted.channelId(),
                    persisted.definitionSourceId().orElse(""),
                    persisted.status(),
                    1,
                    RetryRecoveryManager.DEFAULT_MAX_ATTEMPTS,
                    1.0d,
                    persisted.status().name(),
                    isolateProductionProcessing ? "isolated-worker" : "in-process",
                    prior == null ? now : prior.createdAt(),
                    window[0],
                    window[1],
                    null,
                    persisted.technicalDetail()
            )));
        }
    }

    private IndependentJobResult withPersistedElapsed(IndependentJobResult result) {
        if (result == null) {
            return null;
        }
        Duration current = result.elapsed();
        if (current != null && !current.isZero() && !current.isNegative()) {
            return result;
        }
        ProcessingJobTiming.SourceWindow window = currentRunSourceWindow(result.projectSourceId());
        if (window == null || window.elapsed().isEmpty()) {
            return result;
        }
        return new IndependentJobResult(
                result.jobKey(),
                result.definitionSourceId().orElse(null),
                result.definitionDisplayName(),
                result.mappingLifecycle(),
                result.status(),
                result.expectedSelectedChannelFrames(),
                result.sourceFramesRead(),
                result.selectedChannelFrames(),
                result.dbcMatchedFrames(),
                result.dbcUnmatchedFrames(),
                result.signalsProduced(),
                result.firstSampleTimestampNanos().orElse(null),
                result.lastSampleTimestampNanos().orElse(null),
                result.observedSourceTiming().orElse(null),
                window.elapsed().orElse(Duration.ZERO),
                result.diagnostics(),
                result.technicalDetail(),
                result.catalogHandle().orElse(null)
        );
    }

    private static boolean reusedResult(IndependentJobResult result) {
        if (result == null) {
            return false;
        }
        String detail = result.technicalDetail();
        if (detail == null || detail.isBlank()) {
            return false;
        }
        String lower = detail.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("reused") || lower.contains("reopened-from-durable-job");
    }

    private void persistPlanJobs(
            String runId,
            ProjectProcessingPlan plan,
            JobExecutionStatus status,
            String message,
            boolean forceReprocess
    ) {
        if (metadataStore == null || plan == null) {
            return;
        }
        Instant now = Instant.now();
        for (ProcessingJob job : plan.jobs()) {
            ProcessingJobMetadata prior = metadataStore.findProcessingJob(job.key().toString()).orElse(null);
            if (ProcessingJobTiming.retainExistingSuccessfulWindow(prior, status, forceReprocess)) {
                continue;
            }
            Instant runStarted = latestPersistedProcessingRun()
                    .filter(run -> runId.equals(run.runId()))
                    .map(ProcessingRunMetadata::startedAt)
                    .orElse(null);
            Instant[] window = LocalPlanWorkerEngine.executionWindow(
                    prior, status, false, now, null, runId, runStarted);
            persistWriteQuiet(() -> metadataStore.upsertProcessingJob(new ProcessingJobMetadata(
                    job.key().toString(),
                    metadataProjectId.get(),
                    runId,
                    job.projectSourceId(),
                    job.channelId(),
                    job.definitionSourceId().orElse(""),
                    status,
                    1,
                    RetryRecoveryManager.DEFAULT_MAX_ATTEMPTS,
                    status == JobExecutionStatus.RUNNING ? 0.0d : 1.0d,
                    status.name(),
                    isolateProductionProcessing ? "isolated-worker" : "in-process",
                    prior == null ? now : prior.createdAt(),
                    window[0],
                    window[1],
                    status == JobExecutionStatus.BLOCKED ? ProcessingFailureClass.INSUFFICIENT_STORAGE : null,
                    message
            )));
            lastSourceProcessingStatus.merge(job.projectSourceId(), status, SourceStatusPresenter::prefer);
        }
    }

    /**
     * Writes one job's current-run execution window. {@code RUNNING} captures the real worker
     * start; a later hydrate with {@code Duration.ZERO} must not replace that start.
     */
    private void persistJobLifecycle(
            String runId,
            ProcessingJob job,
            JobExecutionStatus status,
            IndependentJobResult result,
            int index,
            int total
    ) {
        if (metadataStore == null || job == null || runId == null || runId.isBlank() || status == null) {
            return;
        }
        Instant now = Instant.now();
        ProcessingJobMetadata prior = metadataStore.findProcessingJob(job.key().toString()).orElse(null);
        Instant runStarted = latestPersistedProcessingRun()
                .filter(run -> runId.equals(run.runId()))
                .map(ProcessingRunMetadata::startedAt)
                .orElse(null);
        Duration elapsed = result == null ? null : result.elapsed();
        Instant[] window = LocalPlanWorkerEngine.executionWindow(
                prior, status, reusedResult(result), now, elapsed, runId, runStarted);
        persistWriteQuiet(() -> metadataStore.upsertProcessingJob(new ProcessingJobMetadata(
                job.key().toString(),
                metadataProjectId.get(),
                runId,
                job.projectSourceId(),
                job.channelId(),
                job.definitionSourceId().orElse(""),
                status,
                1,
                RetryRecoveryManager.DEFAULT_MAX_ATTEMPTS,
                total <= 0 ? 0d : (double) (index + 1) / total,
                status.name(),
                isolateProductionProcessing ? "isolated-worker" : "in-process",
                prior == null ? now : prior.createdAt(),
                window[0],
                window[1],
                null,
                result == null ? "" : result.technicalDetail()
        )));
    }

    private ProcessingJobTiming.SourceWindow currentRunSourceWindow(String sourceId) {
        if (sourceId == null || sourceId.isBlank() || metadataStore == null) {
            return null;
        }
        String projectId = currentProjectId();
        Optional<ProcessingRunMetadata> run = latestPersistedProcessingRun();
        if (projectId == null || projectId.isBlank() || run.isEmpty()
                || run.get().runId() == null || run.get().runId().isBlank()) {
            return null;
        }
        try {
            return ProcessingJobTiming.sourceWindows(
                    metadataStore.listProcessingJobs(projectId),
                    run.get().runId(),
                    run.get().startedAt()).get(sourceId);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private void logPlanExecutionRejection(
            String runId,
            ProjectProcessingPlan plan,
            String failureCode,
            String failureMessage,
            Throwable ex
    ) {
        String rid = runId == null || runId.isBlank() ? "-" : runId;
        String readiness = plan == null ? "-" : String.valueOf(plan.readiness());
        LOG.log(System.Logger.Level.WARNING, "Plan execution rejected runId=" + rid
                + " failureCode=" + failureCode
                + " readiness=" + readiness
                + " jobs=" + (plan == null ? 0 : plan.jobs().size())
                + " message=" + (failureMessage == null ? "" : failureMessage), ex);
        if (plan == null) {
            return;
        }
        for (ProcessingJob job : plan.jobs()) {
            LOG.log(System.Logger.Level.WARNING, () -> "Plan execution rejected job=" + job.key()
                    + " runId=" + rid
                    + " source=" + job.projectSourceId()
                    + " channel=" + job.channelId()
                    + " mapping=" + job.mappingLifecycle()
                    + " definitionId=" + (job.definitionSourceId().isPresent() ? "present" : "absent")
                    + " executable=" + job.executable()
                    + " readiness=" + readiness
                    + " failureCode=" + failureCode);
        }
    }

    private static MultiSourceProcessingResult failedBatchFromException(
            ProjectProcessingPlan plan,
            String failureMessage
    ) {
        List<IndependentJobResult> results = new ArrayList<>();
        if (plan != null) {
            String detail = failureMessage == null || failureMessage.isBlank()
                    ? "execution failed" : failureMessage;
            for (ProcessingJob job : plan.jobs()) {
                results.add(new IndependentJobResult(
                        job.key(),
                        job.definitionSourceId().orElse(null),
                        job.definitionDisplayName(),
                        job.mappingLifecycle(),
                        JobExecutionStatus.FAILED,
                        job.frameCount(),
                        0, 0, 0, 0, 0,
                        null, null,
                        job.observedTiming().orElse(null),
                        java.time.Duration.ZERO,
                        List.of(ProcessingPlanDiagnostic.of(
                                ProcessingPlanDiagnosticCode.SOURCE_NOT_READY,
                                job.channel(),
                                detail)),
                        detail,
                        null
                ));
            }
        }
        return new MultiSourceProcessingResult(
                plan == null ? "" : plan.planId(),
                Instant.now(),
                ProjectExecutionStatus.FAILED,
                results,
                List.of(),
                java.time.Duration.ZERO
        );
    }

    private void persistIncompleteJobsCancelled(
            String runId,
            ProjectProcessingPlan plan,
            ProcessingSelectionWorkspaceModel model
    ) {
        if (plan == null || runId == null) {
            return;
        }
        Instant now = Instant.now();
        for (ProcessingJob job : plan.jobs()) {
            JobExecutionStatus ui = JobExecutionStatus.QUEUED;
            for (ExecutionJobPresentation row : model.executionJobs()) {
                if (row.jobKey().equals(job.key())) {
                    ui = row.status();
                    break;
                }
            }
            if (ui.isSuccessful()) {
                continue;
            }
            if (processingResultRegistry.get(job.key()).map(IndependentJobResult::successful).orElse(false)) {
                continue;
            }
            Optional<ProcessingJobMetadata> durable = metadataStore.findProcessingJob(job.key().toString());
            if (durable.isPresent() && durable.get().status() != null && durable.get().status().isSuccessful()) {
                continue;
            }
            persistWriteQuiet(() -> metadataStore.upsertProcessingJob(new ProcessingJobMetadata(
                    job.key().toString(),
                    metadataProjectId.get(),
                    runId,
                    job.projectSourceId(),
                    job.channelId(),
                    job.definitionSourceId().orElse(""),
                    JobExecutionStatus.CANCELLED,
                    1,
                    RetryRecoveryManager.DEFAULT_MAX_ATTEMPTS,
                    1.0d,
                    JobExecutionStatus.CANCELLED.name(),
                    isolateProductionProcessing ? "isolated-worker" : "in-process",
                    now,
                    now,
                    now,
                    null,
                    "Cancelled"
            )));
            LOG.log(System.Logger.Level.INFO, () -> "Job cancelled job=" + job.key() + " runId=" + runId);
        }
    }

    private void refreshExecutionJobHandlesFromRegistry(ProcessingSelectionWorkspaceModel model) {
        for (IndependentJobResult result : processingResultRegistry.all()) {
            if (result.successful()) {
                model.updateJobStatus(result.jobKey(), result);
            }
        }
    }

    private void requestIsolatedCancel() {
        String taskId = isolatedTaskId.get();
        if (localProcessingWorker instanceof ProcessIsolatedLocalWorker isolated) {
            isolated.requestCancel(taskId == null ? "" : taskId);
        }
    }

    private static boolean isUserCancelException(RuntimeException ex) {
        if (ex == null) {
            return false;
        }
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        return message.equalsIgnoreCase("Cancelled") || message.toUpperCase(java.util.Locale.ROOT).contains("CANCEL");
    }

    private static boolean outcomeCancelled(WorkerMessage done) {
        if (done == null) {
            return false;
        }
        String token = done.token();
        if (token != null && token.toUpperCase(java.util.Locale.ROOT).contains("CANCELLED")) {
            return true;
        }
        String message = done.message();
        return message != null && message.toUpperCase(java.util.Locale.ROOT).contains("CANCELLED");
    }

    private MultiSourceProcessingResult cancelledBatchPreservingValid(ProjectProcessingPlan plan) {
        List<IndependentJobResult> results = new ArrayList<>();
        for (ProcessingJob job : plan.jobs()) {
            Optional<IndependentJobResult> existing = processingResultRegistry.get(job.key());
            if (existing.isPresent() && existing.get().successful()) {
                results.add(existing.get());
            } else {
                results.add(new IndependentJobResult(
                        job.key(),
                        job.definitionSourceId().orElse(null),
                        job.definitionDisplayName(),
                        job.mappingLifecycle(),
                        JobExecutionStatus.CANCELLED,
                        job.frameCount(),
                        0, 0, 0, 0, 0,
                        null, null,
                        job.observedTiming().orElse(null),
                        java.time.Duration.ZERO,
                        List.of(),
                        "Cancelled",
                        null
                ));
            }
        }
        return new MultiSourceProcessingResult(
                plan.planId(),
                Instant.now(),
                com.stellar.platform.domain.processing.plan.ProjectExecutionStatus.CANCELLED,
                results,
                List.of(),
                java.time.Duration.ZERO
        );
    }

    private static com.stellar.platform.processing.catalog.SignalSeriesStore usableExplorationStore(Object handle) {
        if (!(handle instanceof com.stellar.platform.processing.catalog.SignalSeriesStore store)) {
            return null;
        }
        if (handle instanceof GraphSignalSessionStore graph) {
            Path root = graph.root();
            if (root == null || !Files.isDirectory(root)) {
                return null;
            }
            if (root.getFileName().toString().endsWith(".staging")) {
                return null;
            }
            if (!GraphSignalSessionStore.hasCurrentDecoderGeneration(root)
                    || !LosslessGraphSignalStore.hasValidIndex(root)
                    || !LosslessGraphSignalStore.segmentsReadable(root)) {
                return null;
            }
            return graph;
        }
        try {
            return store.snapshot() == null || store.snapshot().entries().isEmpty() ? null : store;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private GraphSignalSessionStore reopenDurableJobStore(IndependentJobResult result) {
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank()) {
            return null;
        }
        Path finalRoot = ArtifactStorageRefs.jobRoot(
                projectId, result.projectSourceId(), result.channelId(), result.definitionSourceId().orElse(""));
        if (GraphSignalSessionStore.hasIncompleteFlag(finalRoot)) {
            return null;
        }
        if (!GraphSignalSessionStore.hasCurrentDecoderGeneration(finalRoot)
                || !LosslessGraphSignalStore.hasValidIndex(finalRoot)
                || !LosslessGraphSignalStore.segmentsReadable(finalRoot)) {
            return null;
        }
        try {
            GraphSignalSessionStore opened = GraphSignalSessionStore.openExisting(finalRoot);
            processingResultRegistry.publishJob(rebuildResultWithHandle(result, opened));
            LOG.log(System.Logger.Level.INFO, () -> "Artifact generation reused path=" + finalRoot
                    + " samples=" + opened.losslessStore().totalSampleCount());
            return opened;
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Cannot reopen durable artifact " + finalRoot + ": "
                    + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            return null;
        }
    }

    private MultiSourceProcessingResult executorBatchFromRegistry(ProjectProcessingPlan plan) {
        List<IndependentJobResult> results = new ArrayList<>();
        for (ProcessingJob job : plan.jobs()) {
            processingResultRegistry.get(job.key()).ifPresent(results::add);
        }
        com.stellar.platform.domain.processing.plan.ProjectExecutionStatus status =
                results.isEmpty()
                        ? com.stellar.platform.domain.processing.plan.ProjectExecutionStatus.FAILED
                        : com.stellar.platform.domain.processing.plan.ProjectExecutionStatus.SUCCESS;
        return new MultiSourceProcessingResult(
                plan.planId(), Instant.now(), status, results, List.of(), java.time.Duration.ZERO);
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(retryManager.backoff(attempt).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void appendAudit(AuditEventType type, String subjectId, String detail) {
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank() || metadataStore == null) {
            return;
        }
        persistWriteQuiet(() -> metadataStore.appendAuditEvent(
                AuditEventMetadata.of(projectId, type, subjectId == null ? "" : subjectId, detail)));
    }

    /**
     * REMOTE backend: same {@link ProjectProcessingPlan} via {@code ProcessingApi.startProcessingRun}
     * (asset ids only — no desktop filesystem paths on the wire).
     */
    private MultiSourceProcessingResult executePlanViaPlatformClient(
            ProjectProcessingPlan executionPlan,
            boolean forceReprocess,
            SimpleCancellationToken token,
            PlanJobLifecycleListener life,
            CoalescingUiDispatcher dispatcher,
            Runnable refresh,
            ProcessingSelectionWorkspaceModel model
    ) {
        String projectId = session.ensureRemoteProjectId();
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalStateException("REMOTE processing requires a stable project id");
        }
        Map<String, String> measurementAssets = new java.util.LinkedHashMap<>();
        Map<String, String> definitionAssets = new java.util.LinkedHashMap<>();
        for (ProcessingJob job : executionPlan.jobs()) {
            if (!measurementAssets.containsKey(job.projectSourceId())) {
                WorkspaceFile measurement = workspace().findFile(job.projectSourceId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Missing measurement for source " + job.projectSourceId()));
                var asset = com.stellar.platform.api.assets.FileAssetUploader.ensureMeasurementAsset(
                        session.platformClient().assets(),
                        projectId,
                        job.projectSourceId(),
                        measurement.path(),
                        false);
                measurementAssets.put(job.projectSourceId(), asset.fileAssetId());
            }
            job.definitionSourceId().ifPresent(defId -> {
                if (definitionAssets.containsKey(defId)) {
                    return;
                }
                WorkspaceFile definition = workspace().findFile(defId)
                        .filter(f -> f.kind().definition())
                        .orElseThrow(() -> new IllegalStateException("Missing definition " + defId));
                var asset = com.stellar.platform.api.assets.FileAssetUploader.ensureDefinitionAsset(
                        session.platformClient().assets(),
                        projectId,
                        defId,
                        definition.path(),
                        false);
                definitionAssets.put(defId, asset.fileAssetId());
            });
        }

        var runRequest = new com.stellar.platform.api.dto.ProcessingRunRequest(
                com.stellar.platform.api.support.ProcessingPlanDtoMapper.toDto(executionPlan),
                forceReprocess ? "FORCE_REPROCESS" : "REUSE_COMPLETED",
                measurementAssets,
                definitionAssets,
                com.stellar.platform.processing.ProcessingRequest.DEFAULT_BATCH_SIZE
        );
        var run = session.platformClient().processing().startProcessingRun(projectId, runRequest);
        String runId = run.jobId();
        while (true) {
            if (token.isCancellationRequested()) {
                session.platformClient().processing().cancelProcessJob(projectId, runId);
            }
            var status = session.platformClient().processing().getProcessJob(projectId, runId);
            if (status.status() != com.stellar.platform.api.dto.JobStatusDto.RUNNING
                    && status.status() != com.stellar.platform.api.dto.JobStatusDto.QUEUED) {
                break;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                session.platformClient().processing().cancelProcessJob(projectId, runId);
                break;
            }
        }
        var dto = session.platformClient().processing().getProcessingRunResult(projectId, runId)
                .orElseThrow(() -> new IllegalStateException("REMOTE processing run result missing"));
        MultiSourceProcessingResult batch =
                com.stellar.platform.api.support.ProcessingRunResultDomainMapper.toDomain(dto);
        int index = 0;
        int total = batch.jobResults().size();
        for (IndependentJobResult result : batch.jobResults()) {
            life.onJobFinished(result, index++, total);
            processingResultRegistry.publishJob(result);
        }
        processingResultRegistry.attachLatestBatch(batch);
        dispatcher.submit(refresh);
        return batch;
    }

    private static String batchOutcomeMessage(MultiSourceProcessingResult batch) {
        if (batch == null) {
            return "";
        }
        long succeeded = batch.jobResults().stream().filter(IndependentJobResult::successful).count();
        return switch (batch.status()) {
            case SUCCESS -> "";
            case PARTIAL_FAILURE -> succeeded + " of " + batch.jobResults().size() + " jobs completed.";
            case CANCELLED -> "Cancelled after " + succeeded + " completed jobs.";
            case BLOCKED -> "No job could be executed from this plan.";
            case FAILED -> "No job completed successfully.";
        };
    }

    private static final int DIRECT_CHANNEL_ID = 0;

    /** Test seam: the next direct catalog commit throws after the artifact files are written. */
    boolean failNextDirectPersistenceForTest;

    /**
     * Staging directory for one direct DAT/LOG source. Matches the plan executor's
     * {@code *.staging} sibling so {@link #commitArtifactDirectory} can promote it.
     */
    private Path prepareDirectArtifactStaging(WorkspaceFile measurement) {
        analysisStorageService.requireValidForProjectCreation();
        AnalysisStorageRoots.ensureControlledDirectory();
        Path finalRoot = directArtifactFinalRoot(measurement.id());
        Path staging = finalRoot.resolveSibling(finalRoot.getFileName().toString() + ".staging");
        deleteTreeBestEffort(staging);
        return staging;
    }

    private Path directArtifactFinalRoot(String sourceId) {
        return ArtifactStorageRefs.jobRoot(metadataProjectId.get(), sourceId, DIRECT_CHANNEL_ID, "");
    }

    /**
     * Commits a successful direct-processing store with the same artifact and signal-catalog
     * records used by MF4/DBC jobs. Failure deletes the new files and does not leave a VALID
     * artifact without catalog rows. Graph trace selection stays on the existing checkpoint.
     */
    private Optional<SignalSeriesStore> commitDirectProcessingResult(
            WorkspaceFile measurement,
            LocalProcessingGateway.GatewayResult result
    ) {
        if (!(result.catalogStore() instanceof GraphSignalSessionStore store)) {
            LOG.log(System.Logger.Level.ERROR, "Direct processing produced no durable signal store");
            return Optional.empty();
        }
        String projectId = metadataProjectId.get();
        String sourceId = measurement.id();
        String definitionId = "";
        Path finalRoot = directArtifactFinalRoot(sourceId);
        if (!isStagingArtifactRoot(store.root(), finalRoot)) {
            LOG.log(System.Logger.Level.ERROR,
                    "Refusing to persist a temporary direct-processing store at " + store.root());
            return Optional.empty();
        }
        String artifactId = projectId + "|" + sourceId + "|" + DIRECT_CHANNEL_ID + "|" + definitionId;
        String storageRef = ArtifactStorageRefs.forJob(projectId, sourceId, DIRECT_CHANNEL_ID, definitionId);
        String runId = UUID.randomUUID().toString();
        GraphSignalSessionStore committed = null;
        try {
            store.finishWriteSession();
            commitArtifactDirectory(store.root(), finalRoot);
            committed = GraphSignalSessionStore.openExisting(finalRoot);
            try {
                store.close();
            } catch (RuntimeException ignored) {
                // The staging handle is unused once the directory has moved.
            }
            if (failNextDirectPersistenceForTest) {
                failNextDirectPersistenceForTest = false;
                throw new IllegalStateException("simulated direct catalog persistence failure");
            }
            IndependentJobResult job = directJobResult(committed, sourceId);
            ProcessingArtifactMetadata artifact = directArtifactMetadata(
                    measurement, job, committed, artifactId, storageRef, runId, definitionId);
            List<com.stellar.platform.storage.metadata.SignalCatalogMetadata> rows =
                    directCatalogRows(artifactId, job, committed);
            Instant finished = Instant.now();
            ProcessingRunMetadata run = new ProcessingRunMetadata(
                    runId,
                    projectId,
                    ProcessingRunStatuses.COMPLETED,
                    false,
                    1,
                    finished,
                    finished,
                    "Direct processing completed.");
            persistWrite(() -> {
                metadataStore.replaceSignalCatalog(projectId, artifactId, rows);
                metadataStore.upsertProcessingArtifact(artifact);
                metadataStore.upsertProcessingRun(run);
            });
            recordResidentProcessingRun(run);
            rememberProcessedSourceFingerprints(List.of(artifact));
            processingResultRegistry.publishJob(job);
            lastSourceProcessingStatus.merge(sourceId, JobExecutionStatus.SUCCESS, SourceStatusPresenter::prefer);
            bumpDiagnosticArtifactGeneration();
            return Optional.of(committed);
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.ERROR, "Direct processing persistence failed: "
                    + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            if (committed != null) {
                try {
                    committed.close();
                } catch (RuntimeException ignored) {
                    // The committed directory is about to be removed.
                }
            }
            try {
                store.close();
            } catch (RuntimeException ignored) {
                // Staging may already have been closed or moved.
            }
            deleteTreeBestEffort(finalRoot);
            deleteTreeBestEffort(store.root());
            rollbackDirectCatalog(projectId, sourceId, artifactId, runId);
            return Optional.empty();
        }
    }

    private IndependentJobResult directJobResult(GraphSignalSessionStore store, String sourceId) {
        Long first = null;
        Long last = null;
        for (SignalCatalogEntry entry : store.snapshot().entries()) {
            if (entry.firstTimestampNanos() != null) {
                first = first == null ? entry.firstTimestampNanos() : Math.min(first, entry.firstTimestampNanos());
            }
            if (entry.lastTimestampNanos() != null) {
                last = last == null ? entry.lastTimestampNanos() : Math.max(last, entry.lastTimestampNanos());
            }
        }
        long samples = store.losslessStore().totalSampleCount();
        return new IndependentJobResult(
                ProcessingJobKey.of(sourceId, DIRECT_CHANNEL_ID),
                null,
                "",
                MappingLifecycle.NOT_APPLICABLE,
                JobExecutionStatus.SUCCESS,
                samples,
                samples,
                samples,
                0L,
                0L,
                store.snapshot().entries().size(),
                first,
                last,
                null,
                Duration.ZERO,
                List.of(),
                "direct-processing",
                store
        );
    }

    private ProcessingArtifactMetadata directArtifactMetadata(
            WorkspaceFile measurement,
            IndependentJobResult job,
            GraphSignalSessionStore store,
            String artifactId,
            String storageRef,
            String runId,
            String definitionId
    ) {
        String sourceFormat = MeasurementSourceFormat.fromFormatKey(measurement.kind().displayName()).name();
        String planFingerprint = ProcessingPlanFingerprint.forJob(
                job.projectSourceId(),
                job.channelId(),
                definitionId,
                job.mappingLifecycle().name(),
                sourceFormat);
        return new ProcessingArtifactMetadata(
                artifactId,
                metadataProjectId.get(),
                job.projectSourceId(),
                job.channelId(),
                definitionId,
                runId,
                ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                "",
                storageRef,
                ProcessingArtifactMetadata.ARTIFACT_VERSION_CURRENT,
                FileFingerprint.of(measurement.path()),
                "",
                planFingerprint,
                store.snapshot().entries().size(),
                store.losslessStore().totalSampleCount(),
                job.firstSampleTimestampNanos().orElse(null),
                job.lastSampleTimestampNanos().orElse(null),
                ProcessingArtifactMetadata.STATUS_VALID,
                Instant.now(),
                job.mappingLifecycle().name(),
                job.status().name(),
                ProcessingArtifactDiagnosticsCodec.encode(job.diagnostics()),
                job.technicalDetail()
        );
    }

    private List<com.stellar.platform.storage.metadata.SignalCatalogMetadata> directCatalogRows(
            String artifactId,
            IndependentJobResult job,
            GraphSignalSessionStore store
    ) {
        Instant now = Instant.now();
        List<com.stellar.platform.storage.metadata.SignalCatalogMetadata> rows = new java.util.ArrayList<>();
        for (SignalCatalogEntry entry : store.snapshot().entries()) {
            SignalCatalogKey key = entry.key();
            String signalKey = key.signalName() + "|" + key.sourceType() + "|" + key.messageName()
                    + "|" + key.canId() + "|" + key.channel();
            rows.add(new com.stellar.platform.storage.metadata.SignalCatalogMetadata(
                    UUID.randomUUID().toString(),
                    metadataProjectId.get(),
                    job.projectSourceId(),
                    job.channelId(),
                    "",
                    artifactId,
                    signalKey,
                    key.signalName(),
                    key.messageName(),
                    key.canId(),
                    key.channel(),
                    key.sourceType().name(),
                    entry.unit(),
                    entry.sampleCount(),
                    entry.firstTimestampNanos(),
                    entry.lastTimestampNanos(),
                    entry.minPhysical(),
                    entry.maxPhysical(),
                    now,
                    now
            ));
        }
        return rows;
    }

    private void rollbackDirectCatalog(String projectId, String sourceId, String artifactId, String runId) {
        try {
            metadataStore.replaceSignalCatalog(projectId, artifactId, List.of());
        } catch (RuntimeException ignored) {
            // The failed write may already have rolled its own catalog transaction back.
        }
        try {
            metadataStore.findValidArtifact(projectId, sourceId, DIRECT_CHANNEL_ID, "")
                    .ifPresent(artifact -> metadataStore.upsertProcessingArtifact(
                            artifact.withStatus(ProcessingArtifactMetadata.STATUS_STALE)));
        } catch (RuntimeException ignored) {
            // No VALID artifact is preferable to a processed source with an empty catalog.
        }
        Instant now = Instant.now();
        ProcessingRunMetadata failed = new ProcessingRunMetadata(
                runId,
                projectId,
                ProcessingRunStatuses.FAILED,
                false,
                1,
                now,
                now,
                "Direct processing results could not be saved.");
        if (persistWriteQuiet(() -> metadataStore.upsertProcessingRun(failed))) {
            recordResidentProcessingRun(failed);
        }
    }

    private static com.stellar.platform.desktop.project.ProcessingSummary persistenceFailedSummary(
            com.stellar.platform.desktop.project.ProcessingSummary summary
    ) {
        String message = "Processed signals could not be saved. The project was not marked processed.";
        if (summary == null) {
            return new com.stellar.platform.desktop.project.ProcessingSummary(
                    "",
                    Path.of(""),
                    Path.of(""),
                    new ProcessingStatistics(),
                    Duration.ZERO,
                    ProjectProcessingStatus.FAILED,
                    List.of(),
                    message,
                    message,
                    false,
                    "",
                    ""
            );
        }
        return new com.stellar.platform.desktop.project.ProcessingSummary(
                summary.sourceFormat(),
                summary.sourceFile(),
                summary.dbcFile(),
                summary.statistics(),
                summary.elapsed(),
                ProjectProcessingStatus.FAILED,
                summary.diagnostics(),
                message,
                message,
                summary.recoveryUsed(),
                summary.recoveryState(),
                summary.mf4FileStatus(),
                summary.selectedChannelsSummary(),
                summary.selectedMatchedIds()
        );
    }

    private GraphSignalSessionStore createPersistentSignalStore(
            ProcessingJob job,
            String measurementDisplayName,
            String definitionDisplayName
    ) {
        analysisStorageService.requireValidForProjectCreation();
        AnalysisStorageRoots.ensureControlledDirectory();
        String projectId = metadataProjectId.get();
        String definitionId = job.definitionSourceId().orElse("");
        Path finalRoot = ArtifactStorageRefs.jobRoot(
                projectId, job.projectSourceId(), job.channelId(), definitionId);
        Path stagingRoot = finalRoot.resolveSibling(finalRoot.getFileName().toString() + ".staging");
        signalStoreCreateCountForTest.incrementAndGet();
        LOG.log(System.Logger.Level.INFO, () -> "Artifact write start staging=" + stagingRoot);
        return GraphSignalSessionStore.createPersistent(stagingRoot, measurementDisplayName, definitionDisplayName);
    }

    private void persistProcessingArtifacts(String runId, MultiSourceProcessingResult batch) {
        if (batch == null) return;
        for (IndependentJobResult result : batch.jobResults()) {
            if (!result.successful()) continue;
            Object handle = result.catalogHandle().orElse(null);
            if (!(handle instanceof GraphSignalSessionStore store)) continue;
            Path measurement = workspace().findFile(result.projectSourceId()).map(WorkspaceFile::path).orElse(null);
            Path definition = result.definitionSourceId()
                    .flatMap(id -> workspace().findFile(id).map(WorkspaceFile::path))
                    .orElse(null);
            String definitionId = result.definitionSourceId().orElse("");
            String artifactId = metadataProjectId.get() + "|" + result.projectSourceId()
                    + "|" + result.channelId() + "|" + definitionId;
            String storageRef = ArtifactStorageRefs.forJob(
                    metadataProjectId.get(), result.projectSourceId(), result.channelId(), definitionId);
            Path finalRoot = ArtifactStorageRefs.resolveLocal(storageRef);
            Path storeRoot = store.root();

            // Reused/seeded jobs already live at the durable final root. Committing them again
            // deletes that tree (delete-then-move) and leaves an in-memory index pointing at
            // missing segment .bin files — Explore Signals then shows columns with 0 rows.
            // A session handle that is not that final root must be promoted before metadata
            // is marked valid. In-memory success is not a completed artifact.
            if (!isStagingArtifactRoot(storeRoot, finalRoot)) {
                if (!publishReusableOrPromote(runId, result, store, artifactId, storageRef,
                        measurement, definition, definitionId, storeRoot, finalRoot)) {
                    LOG.log(System.Logger.Level.WARNING, () ->
                            "Artifact not marked complete; durable root is not restart-safe: " + finalRoot);
                }
                continue;
            }

            try {
                store.finishWriteSession();
                commitArtifactDirectory(storeRoot, finalRoot);
                LOG.log(System.Logger.Level.INFO, () -> "Artifact generation replaced path=" + finalRoot
                        + " samples=" + store.losslessStore().totalSampleCount());
            } catch (RuntimeException ex) {
                String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                LOG.log(System.Logger.Level.WARNING,
                        "Artifact commit failed for " + artifactId + ": " + message);
                IndependentJobResult failed = result.rejectedPersistence(message);
                processingResultRegistry.publishJob(failed);
                continue;
            }
            // Re-open committed root so the live handle matches the durable path.
            GraphSignalSessionStore committed;
            try {
                committed = GraphSignalSessionStore.openExisting(finalRoot);
            } catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING,
                        "Cannot reopen committed artifact " + finalRoot + ": " + ex.getMessage());
                continue;
            }
            processingResultRegistry.publishJob(rebuildResultWithHandle(result, committed));
            upsertDurableArtifactMetadata(runId, result, committed, artifactId, storageRef,
                    measurement, definition, definitionId);
        }
    }

    /**
     * Writes artifact metadata only after the durable final root is restart-safe.
     * A complete store that still lives somewhere else is moved to that root first.
     * An already-durable root is never delete-then-moved.
     *
     * @return true when durable metadata was written
     */
    private boolean publishReusableOrPromote(
            String runId,
            IndependentJobResult result,
            GraphSignalSessionStore store,
            String artifactId,
            String storageRef,
            Path measurement,
            Path definition,
            String definitionId,
            Path storeRoot,
            Path finalRoot
    ) {
        try {
            store.finishWriteSession();
        } catch (RuntimeException ignored) {
            // Read-only reopen must not fail the batch when flush is a no-op.
        }
        Path storeAbs = storeRoot == null ? null : storeRoot.toAbsolutePath().normalize();
        Path finalAbs = finalRoot.toAbsolutePath().normalize();
        boolean openedAtFinal = storeAbs != null && storeAbs.equals(finalAbs);
        if (openedAtFinal && durableArtifactReady(finalAbs)) {
            LOG.log(System.Logger.Level.INFO, () -> "Artifact generation reused path=" + finalAbs
                    + " samples=" + store.losslessStore().totalSampleCount());
            upsertDurableArtifactMetadata(runId, result, store, artifactId, storageRef,
                    measurement, definition, definitionId);
            return true;
        }
        if (durableArtifactReady(finalAbs)) {
            try {
                GraphSignalSessionStore committed = GraphSignalSessionStore.openExisting(finalAbs);
                processingResultRegistry.publishJob(rebuildResultWithHandle(result, committed));
                LOG.log(System.Logger.Level.INFO, () -> "Artifact generation reused path=" + finalAbs
                        + " samples=" + committed.losslessStore().totalSampleCount());
                upsertDurableArtifactMetadata(runId, result, committed, artifactId, storageRef,
                        measurement, definition, definitionId);
                return true;
            } catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING,
                        "Cannot reopen durable artifact " + finalAbs + ": " + ex.getMessage());
                return false;
            }
        }
        if (storeAbs != null
                && Files.isDirectory(storeAbs)
                && !storeAbs.equals(finalAbs)
                && durableArtifactReady(storeAbs)) {
            try {
                commitArtifactDirectory(storeAbs, finalAbs);
                GraphSignalSessionStore committed = GraphSignalSessionStore.openExisting(finalAbs);
                processingResultRegistry.publishJob(rebuildResultWithHandle(result, committed));
                LOG.log(System.Logger.Level.INFO, () -> "Artifact generation replaced path=" + finalAbs
                        + " samples=" + committed.losslessStore().totalSampleCount());
                upsertDurableArtifactMetadata(runId, result, committed, artifactId, storageRef,
                        measurement, definition, definitionId);
                return true;
            } catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING,
                        "Artifact promote failed for " + artifactId + ": " + ex.getMessage());
                return false;
            }
        }
        return false;
    }

    private static boolean durableArtifactReady(Path root) {
        return root != null
                && !GraphSignalSessionStore.hasIncompleteFlag(root)
                && GraphSignalSessionStore.hasCurrentDecoderGeneration(root)
                && LosslessGraphSignalStore.payloadMatchesValidityLayout(root);
    }

    /**
     * True only for the write-staging directory created by {@link #createPersistentSignalStore}.
     * Final/durable roots must never enter {@link #commitArtifactDirectory}.
     */
    private static boolean isStagingArtifactRoot(Path storeRoot, Path finalRoot) {
        if (storeRoot == null || finalRoot == null) {
            return false;
        }
        Path storeAbs = storeRoot.toAbsolutePath().normalize();
        Path finalAbs = finalRoot.toAbsolutePath().normalize();
        if (storeAbs.equals(finalAbs)) {
            return false;
        }
        Path expectedStaging = finalAbs.resolveSibling(finalAbs.getFileName().toString() + ".staging");
        return storeAbs.equals(expectedStaging)
                || storeAbs.getFileName().toString().endsWith(".staging");
    }

    private void upsertDurableArtifactMetadata(
            String runId,
            IndependentJobResult result,
            GraphSignalSessionStore store,
            String artifactId,
            String storageRef,
            Path measurement,
            Path definition,
            String definitionId
    ) {
        long signalCount = store.snapshot().entries().size();
        long sampleCount = store.losslessStore().totalSampleCount();
        String sourceFormat = workspace().findFile(result.projectSourceId())
                .map(f -> MeasurementSourceFormat.fromFormatKey(f.kind().displayName()).name())
                .orElse(MeasurementSourceFormat.UNKNOWN.name());
        String planFingerprint = ProcessingPlanFingerprint.forJob(
                result.projectSourceId(),
                result.channelId(),
                definitionId,
                result.mappingLifecycle().name(),
                sourceFormat);
        ProcessingArtifactMetadata artifact = new ProcessingArtifactMetadata(
                artifactId,
                metadataProjectId.get(),
                result.projectSourceId(),
                result.channelId(),
                definitionId,
                runId,
                ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                "",
                storageRef,
                ProcessingArtifactMetadata.ARTIFACT_VERSION_CURRENT,
                FileFingerprint.of(measurement),
                FileFingerprint.of(definition),
                planFingerprint,
                signalCount,
                sampleCount,
                result.firstSampleTimestampNanos().orElse(null),
                result.lastSampleTimestampNanos().orElse(null),
                ProcessingArtifactMetadata.STATUS_VALID,
                Instant.now(),
                result.mappingLifecycle().name(),
                result.status().name(),
                ProcessingArtifactDiagnosticsCodec.encode(result.diagnostics()),
                result.technicalDetail()
        );
        metadataWrite(() -> metadataStore.upsertProcessingArtifact(artifact));
        rememberProcessedSourceFingerprints(List.of(artifact));
        bumpDiagnosticArtifactGeneration();
        persistSignalCatalogEntries(artifactId, result, store);
    }

    private static void commitArtifactDirectory(Path stagingRoot, Path finalRoot) {
        com.stellar.platform.processing.graph.ArtifactStoreCommit.promote(stagingRoot, finalRoot);
    }

    /** Closes in-memory stores for this plan before a forced rewrite so they cannot persist a stale index. */
    private void releaseForcedReprocessHandles(ProjectProcessingPlan plan) {
        if (plan == null) {
            return;
        }
        for (ProcessingJob job : plan.jobs()) {
            processingResultRegistry.removeJob(job.key());
        }
    }

    private static void deleteTreeBestEffort(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException ignored) {
                }
            });
        } catch (java.io.IOException ignored) {
        }
    }

    private static IndependentJobResult rebuildResultWithHandle(IndependentJobResult result, Object handle) {
        return new IndependentJobResult(
                result.jobKey(),
                result.definitionSourceId().orElse(null),
                result.definitionDisplayName(),
                result.mappingLifecycle(),
                result.status(),
                result.expectedSelectedChannelFrames(),
                result.sourceFramesRead(),
                result.selectedChannelFrames(),
                result.dbcMatchedFrames(),
                result.dbcUnmatchedFrames(),
                result.signalsProduced(),
                result.firstSampleTimestampNanos().orElse(null),
                result.lastSampleTimestampNanos().orElse(null),
                result.observedSourceTiming().orElse(null),
                result.elapsed(),
                result.diagnostics(),
                result.technicalDetail(),
                handle
        );
    }

    /**
     * Seeds the session registry from VALID durable LOSSLESS artifacts so reopen and
     * incremental execution reuse disk stores without re-reading MF4/DBC.
     *
     * @return user-visible warnings for rejected artifacts (e.g. reprocessing required)
     */
    /**
     * A job whose staged store was rejected has no VALID artifact. Reopen still shows that
     * channel as failed, using the ledger message, and does not invent catalog rows.
     */
    private void restoreUnpersistedJobFailures(ProjectProcessingPlan plan) {
        if (plan == null || metadataStore == null) {
            return;
        }
        for (ProcessingJob job : plan.jobs()) {
            if (job == null || processingResultRegistry.get(job.key()).isPresent()) {
                continue;
            }
            ProcessingJobMetadata row = metadataStore.findProcessingJob(job.key().toString()).orElse(null);
            if (row == null || row.status() != JobExecutionStatus.FAILED) {
                continue;
            }
            processingResultRegistry.publishJob(new IndependentJobResult(
                    job.key(),
                    job.definitionSourceId().orElse(null),
                    job.definitionDisplayName(),
                    job.mappingLifecycle(),
                    JobExecutionStatus.FAILED,
                    job.frameCount(),
                    0L,
                    job.frameCount(),
                    0L,
                    0L,
                    0L,
                    null,
                    null,
                    job.observedTiming().orElse(null),
                    java.time.Duration.ZERO,
                    List.of(),
                    row.message(),
                    null
            ));
        }
    }

    private List<String> seedReusableArtifactsFromMetadata(ProjectProcessingPlan plan) {
        List<String> warnings = new ArrayList<>();
        if (plan == null || plan.isEmpty()) {
            return warnings;
        }
        if (!analysisStorageService.isValid()) {
            LOG.log(System.Logger.Level.INFO, "Analysis Storage unavailable — cannot seed artifacts");
            return warnings;
        }
        int seeded = 0;
        for (ProcessingJob job : plan.jobs()) {
            throwIfSwitchSuperseded();
            if (processingResultRegistry.get(job.key()).isPresent()) {
                continue;
            }
            String definitionId = job.definitionSourceId().orElse("");
            Optional<ProcessingArtifactMetadata> found = metadataStore.findValidArtifact(
                    metadataProjectId.get(), job.projectSourceId(), job.channelId(), definitionId);
            if (found.isPresent()) {
                if (openReusableArtifact(found.get(), job.key(), definitionId, warnings)) {
                    seeded++;
                }
                continue;
            }
            if (seedDurableJobRoot(job, warnings)) {
                seeded++;
            }
        }
        if (seeded > 0) {
            int seededCount = seeded;
            LOG.log(System.Logger.Level.INFO, () -> "Seeded " + seededCount + " reusable analysis artifacts");
        }
        return warnings;
    }

    private void checkpointMetadataWal() {
        if (metadataStore instanceof SqliteProjectMetadataStore sqlite) {
            sqlite.checkpointWal();
        }
    }

    /**
     * When the project-local artifact row is not yet visible (WAL) but the committed
     * job root already exists, reopen it without reparsing MF4/DBC.
     */
    private boolean seedDurableJobRoot(ProcessingJob job, List<String> warnings) {
        if (job == null) {
            return false;
        }
        String definitionId = job.definitionSourceId().orElse("");
        Path finalRoot = ArtifactStorageRefs.jobRoot(
                metadataProjectId.get(), job.projectSourceId(), job.channelId(), definitionId);
        if (GraphSignalSessionStore.hasIncompleteFlag(finalRoot)) {
            return false;
        }
        if (!GraphSignalSessionStore.hasCurrentDecoderGeneration(finalRoot)) {
            if (warnings != null) {
                warnings.add("Analysis artifact predates the current decoder generation — reprocessing required ("
                        + job.projectSourceId() + "). Original measurement files were not changed.");
            }
            return false;
        }
        if (!LosslessGraphSignalStore.payloadMatchesValidityLayout(finalRoot)) {
            if (warnings != null) {
                int layout = LosslessGraphSignalStore.indexLayoutVersion(finalRoot);
                warnings.add(layout == LosslessGraphSignalStore.INDEX_LAYOUT_VALIDITY
                        ? "Analysis artifact index and sample records disagree — reprocessing required ("
                        + job.projectSourceId() + "). Original measurement files were not changed."
                        : "Analysis artifact predates signal-validity storage — reprocessing required ("
                        + job.projectSourceId() + "). Original measurement files were not changed.");
            }
            return false;
        }
        try {
            GraphSignalSessionStore opened = GraphSignalSessionStore.openExisting(finalRoot);
            IndependentJobResult result = new IndependentJobResult(
                    job.key(),
                    definitionId.isBlank() ? null : definitionId,
                    job.definitionDisplayName(),
                    job.mappingLifecycle(),
                    JobExecutionStatus.SUCCESS,
                    opened.losslessStore().totalSampleCount(),
                    opened.losslessStore().totalSampleCount(),
                    opened.losslessStore().totalSampleCount(),
                    opened.losslessStore().totalSampleCount(),
                    0L,
                    opened.snapshot().entries().size(),
                    null,
                    null,
                    job.observedTiming().orElse(null),
                    java.time.Duration.ZERO,
                    List.of(),
                    "reopened-from-durable-job",
                    opened
            );
            processingResultRegistry.publishJob(withPersistedElapsed(result));
            LOG.log(System.Logger.Level.INFO, () -> "Artifact generation reused path=" + finalRoot
                    + " samples=" + opened.losslessStore().totalSampleCount());
            return true;
        } catch (RuntimeException ex) {
            if (warnings != null) {
                warnings.add("Analysis artifact unreadable for " + job.projectSourceId()
                        + " — reprocessing required.");
            }
            return false;
        }
    }

    /**
     * Reopen durable artifacts from persisted manifests when Decision Proceed is not yet ready.
     * Required so Graph/Signals checkpoints can resolve catalog keys on project open.
     */
    private List<String> seedReusableArtifactsFromPersistedManifests(
            List<ProcessingArtifactMetadata> artifacts
    ) {
        List<String> warnings = new ArrayList<>();
        if (artifacts == null || artifacts.isEmpty()) {
            return warnings;
        }
        if (!analysisStorageService.isValid()) {
            LOG.log(System.Logger.Level.INFO, "Analysis Storage unavailable — cannot seed artifacts");
            return warnings;
        }
        int seeded = 0;
        for (ProcessingArtifactMetadata artifact : artifacts) {
            throwIfSwitchSuperseded();
            if (artifact == null) {
                continue;
            }
            if (!ProcessingArtifactMetadata.STATUS_VALID.equals(artifact.status())) {
                continue;
            }
            if (!ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE.equals(artifact.artifactType())) {
                continue;
            }
            if (workspace().findFile(artifact.sourceId()).isEmpty()) {
                continue;
            }
            ProcessingJobKey jobKey = ProcessingJobKey.of(artifact.sourceId(), artifact.channelId());
            if (processingResultRegistry.get(jobKey).isPresent()) {
                continue;
            }
            if (openReusableArtifact(artifact, jobKey, artifact.definitionId(), warnings)) {
                seeded++;
            }
        }
        if (seeded > 0) {
            int seededCount = seeded;
            LOG.log(System.Logger.Level.INFO, () -> "Seeded " + seededCount + " reusable analysis artifacts");
        }
        return warnings;
    }

    private boolean openReusableArtifact(
            ProcessingArtifactMetadata artifact,
            ProcessingJobKey jobKey,
            String definitionId,
            List<String> warnings
    ) {
        if (artifact == null || jobKey == null) {
            return false;
        }
        String defId = definitionId == null ? "" : definitionId;
        Path measurement = workspace().findFile(jobKey.projectSourceId()).map(WorkspaceFile::path).orElse(null);
        Path definition = defId.isBlank()
                ? null
                : workspace().findFile(defId).map(WorkspaceFile::path).orElse(null);
        Path root;
        try {
            root = ArtifactStorageRefs.resolveLocal(artifact.resolutionKey());
        } catch (RuntimeException ex) {
            warnings.add("Analysis artifact reference unreadable for " + artifact.sourceId()
                    + " — reprocessing required.");
            markArtifactStale(artifact);
            return false;
        }
        String expectedPlanFp = artifact.planFingerprint() == null ? "" : artifact.planFingerprint();
        DurableArtifactLifecycle.Check check = DurableArtifactLifecycle.validateOnDisk(
                artifact, root, measurement, definition, expectedPlanFp);
        if (!check.reusable()) {
            if (!check.warning().isBlank()) {
                warnings.add(check.warning());
            }
            if (check.outcome() == DurableArtifactLifecycle.Outcome.STALE
                    || check.outcome() == DurableArtifactLifecycle.Outcome.MISSING) {
                markArtifactStale(artifact);
            }
            LOG.log(System.Logger.Level.INFO, () -> "Artifact rejected for reopen: "
                    + artifact.artifactId() + " — " + check.warning());
            return false;
        }
        try {
            GraphSignalSessionStore store = GraphSignalSessionStore.openExisting(root);
            MappingLifecycle lifecycle;
            try {
                lifecycle = MappingLifecycle.valueOf(artifact.mappingLifecycle());
            } catch (RuntimeException ex) {
                lifecycle = MappingLifecycle.MANUAL;
            }
            JobExecutionStatus seededStatus =
                    ProcessingArtifactDiagnosticsCodec.parseStatus(artifact.jobExecutionStatus());
            IndependentJobResult result = new IndependentJobResult(
                    jobKey,
                    defId.isBlank() ? null : defId,
                    definition == null ? "" : definition.getFileName().toString(),
                    lifecycle,
                    seededStatus,
                    artifact.sampleCount(),
                    artifact.sampleCount(),
                    artifact.sampleCount(),
                    artifact.sampleCount(),
                    0L,
                    artifact.signalCount(),
                    artifact.startTimestampNanos(),
                    artifact.endTimestampNanos(),
                    null,
                    java.time.Duration.ZERO,
                    ProcessingArtifactDiagnosticsCodec.decode(artifact.diagnosticsSummary()),
                    artifact.technicalDetail().isBlank() ? "reopened-from-artifact" : artifact.technicalDetail(),
                    store
            );
            processingResultRegistry.publishJob(withPersistedElapsed(result));
            LOG.log(System.Logger.Level.INFO, () -> "Artifact reopen " + artifact.artifactId()
                    + " signals=" + artifact.signalCount()
                    + " status=" + seededStatus);
            return true;
        } catch (RuntimeException ex) {
            warnings.add("Analysis artifact corrupt for " + artifact.sourceId()
                    + " — reprocessing required.");
            markArtifactStale(artifact);
            LOG.log(System.Logger.Level.WARNING,
                    "Artifact rejected " + artifact.artifactId() + ": " + ex.getMessage());
            return false;
        }
    }

    private void markArtifactStale(ProcessingArtifactMetadata artifact) {
        if (artifact == null) {
            return;
        }
        metadataWrite(() -> metadataStore.upsertProcessingArtifact(
                artifact.withStatus(ProcessingArtifactMetadata.STATUS_STALE)));
    }

    private void persistSignalCatalogEntries(
            String artifactId,
            IndependentJobResult result,
            GraphSignalSessionStore store
    ) {
        Instant now = Instant.now();
        List<com.stellar.platform.storage.metadata.SignalCatalogMetadata> rows = new java.util.ArrayList<>();
        for (var entry : store.snapshot().entries()) {
            var key = entry.key();
            String signalKey = key.signalName() + "|" + key.sourceType() + "|" + key.messageName()
                    + "|" + key.canId() + "|" + key.channel();
            rows.add(new com.stellar.platform.storage.metadata.SignalCatalogMetadata(
                    java.util.UUID.randomUUID().toString(),
                    metadataProjectId.get(),
                    result.projectSourceId(),
                    result.channelId(),
                    result.definitionSourceId().orElse(""),
                    artifactId,
                    signalKey,
                    key.signalName(),
                    key.messageName(),
                    key.canId(),
                    key.channel(),
                    key.sourceType().name(),
                    entry.unit(),
                    entry.sampleCount(),
                    entry.firstTimestampNanos(),
                    entry.lastTimestampNanos(),
                    entry.minPhysical(),
                    entry.maxPhysical(),
                    now,
                    now
            ));
        }
        metadataWrite(() -> metadataStore.replaceSignalCatalog(
                metadataProjectId.get(), artifactId, rows));
    }

    private void persistInventoryDataQualityFindings(String sourceId, CanSourceInventory inventory) {
        if (sourceId == null || inventory == null) {
            return;
        }
        String projectId = metadataProjectId.get();
        metadataStore.deleteDataQualityFindingsForSource(projectId, sourceId);
        Instant now = Instant.now();
        for (var diagnostic : inventory.diagnostics()) {
            if (diagnostic == null || diagnostic.code() == null) {
                continue;
            }
            String type = diagnostic.code().name();
            String severity = switch (diagnostic.code()) {
                case SOURCE_READ_FAILED, CHANNEL_DISCOVERY_FAILED, CAN_PAYLOAD_UNREADABLE -> "WARNING";
                case FORMAT_UNSUPPORTED, UNSUPPORTED_CAN_STRUCTURE, NO_CAN_DATA, INVENTORY_CANCELLED,
                        MALFORMED_RECORD -> "INFO";
            };
            String summary = switch (diagnostic.code()) {
                case NO_CAN_DATA -> "No CAN data observed in recording inventory";
                case CHANNEL_DISCOVERY_FAILED -> "Observed channel discovery issue";
                case UNSUPPORTED_CAN_STRUCTURE -> "Unsupported CAN structure observed";
                case CAN_PAYLOAD_UNREADABLE -> "Observed discontinuity: CAN payload unreadable";
                case INVENTORY_CANCELLED -> "Inventory cancelled before completion";
                case SOURCE_READ_FAILED -> "Source read failed during inventory";
                case FORMAT_UNSUPPORTED -> "Measurement format unsupported for inventory";
                case MALFORMED_RECORD -> "Malformed source line was not counted as a frame";
            };
            metadataStore.upsertDataQualityFinding(new com.stellar.platform.storage.metadata.DataQualityFindingMetadata(
                    java.util.UUID.randomUUID().toString(),
                    projectId,
                    sourceId,
                    null,
                    type,
                    severity,
                    null,
                    null,
                    summary,
                    diagnostic.message() == null ? "" : diagnostic.message(),
                    "",
                    now
            ));
        }
    }

    public DesktopAnalysisService engineeringAnalysis() {
        return desktopAnalysisService;
    }

    public AnalysisResult runEngineeringAnalysis(AnalysisRequest request) {
        SignalSeriesStore store = signalsModel.catalogStore();
        if (store == null) {
            throw new IllegalStateException("No processed signals are loaded");
        }
        return desktopAnalysisService.run(request, store, metadataStore);
    }

    public com.stellar.platform.analysis.advanced.AdvancedAnalyticsResult runAdvancedAnalytics(
            com.stellar.platform.analysis.advanced.AdvancedAnalyticsRequest request
    ) {
        SignalSeriesStore store = signalsModel.catalogStore();
        if (store == null) {
            throw new IllegalStateException("No processed signals are loaded");
        }
        return desktopAnalysisService.runAdvanced(request, store, metadataStore);
    }

    public com.stellar.platform.analysis.advanced.AdvancedAnalyticsResult lastAdvancedAnalytics() {
        return desktopAnalysisService.lastAdvanced();
    }

    public com.stellar.platform.analysis.advanced.AdvancedAnalyticsResult saveAdvancedDerived() {
        return desktopAnalysisService.saveLastDerived(currentProjectId(), metadataStore);
    }

    public AnalysisResult lastEngineeringAnalysis() {
        return desktopAnalysisService.lastResult();
    }

    public String lastEngineeringAnalysisReport() {
        return desktopAnalysisService.lastReport();
    }

    public String lastEngineeringAnalysisType() {
        return desktopAnalysisService.lastAnalysisType();
    }

    public DesktopDiagnosticService engineeringDiagnostics() {
        return desktopDiagnosticService;
    }

    public DesktopInvestigationService engineeringInvestigations() {
        return desktopInvestigationService;
    }

    public ProjectMetadataStore metadataStore() {
        return metadataStore;
    }

    public java.util.Set<String> currentSourceFingerprints() {
        return java.util.Set.copyOf(lastProcessedSourceFingerprints.values());
    }

    public String fingerprintsForWorkspace() {
        return com.stellar.platform.analysis.investigation.InvestigationStaleness.join(
                lastProcessedSourceFingerprints.values());
    }

    public com.stellar.platform.analysis.investigation.EngineeringInvestigation createInvestigation(
            String name, String description, String scope
    ) {
        return createInvestigation(name, description, scope, true);
    }

    public com.stellar.platform.analysis.investigation.EngineeringInvestigation createInvestigation(
            String name, String description, String scope, boolean useCurrentSignals
    ) {
        String projectId = currentProjectId();
        GraphInvestigationSnapshot snap = graphInvestigation.snapshot(projectId, signalsModel.workspaceKeys());
        Long start = snap.viewportStartNanos();
        Long end = snap.viewportEndNanos();
        String range = scope == null ? "CURRENT" : scope.trim().toUpperCase();
        if ("CURSOR_A_TO_B".equals(range) || "CURSOR".equals(range)) {
            start = snap.cursorANanos();
            end = snap.cursorBNanos();
        } else if ("FULL".equals(range)) {
            start = snap.defaultStartNanos() != null ? snap.defaultStartNanos() : start;
            end = snap.defaultEndNanos() != null ? snap.defaultEndNanos() : end;
        }
        java.util.List<String> blobs = new java.util.ArrayList<>();
        if (useCurrentSignals) {
            for (GraphInvestigationSnapshot.Trace t : snap.traces()) {
                blobs.add(GraphInvestigationCodec.encodeKey(t.key()));
            }
        }
        com.stellar.platform.analysis.investigation.EngineeringInvestigation inv =
                com.stellar.platform.analysis.investigation.EngineeringInvestigation.builder(projectId)
                        .name(name)
                        .description(description)
                        .range(start, end)
                        .graphState(GraphInvestigationCodec.encodeTraces(snap.traces()), snap.layoutMode(),
                                snap.viewportStartNanos(), snap.viewportEndNanos(),
                                snap.cursorANanos(), snap.cursorBNanos(),
                                snap.focusKey() == null ? "" : GraphInvestigationCodec.encodeKey(snap.focusKey()))
                        .signalKeyBlobs(blobs)
                        .build();
        com.stellar.platform.analysis.investigation.EngineeringInvestigation saved =
                desktopInvestigationService.save(metadataStore, inv);
        desktopInvestigationService.rememberWorkspace(metadataStore, projectId, saved.investigationId());
        noteLogicalChange();
        appendAudit(AuditEventType.INVESTIGATION_CREATED, saved.investigationId(), saved.name());
        return saved;
    }

    public com.stellar.platform.analysis.investigation.EngineeringInvestigation createInvestigationFromCurrentGraph() {
        return createInvestigation("Investigation from Graph", "", "CURRENT", true);
    }

    public com.stellar.platform.analysis.investigation.EngineeringInvestigation saveInvestigation(
            com.stellar.platform.analysis.investigation.EngineeringInvestigation investigation
    ) {
        com.stellar.platform.analysis.investigation.EngineeringInvestigation saved =
                desktopInvestigationService.save(metadataStore, investigation);
        desktopInvestigationService.rememberWorkspace(metadataStore, currentProjectId(), saved.investigationId());
        noteLogicalChange();
        if (saved.status() == com.stellar.platform.analysis.investigation.InvestigationStatus.COMPLETED) {
            appendAudit(AuditEventType.INVESTIGATION_COMPLETED, saved.investigationId(), saved.name());
        } else {
            appendAudit(AuditEventType.INVESTIGATION_UPDATED, saved.investigationId(), saved.name());
        }
        return saved;
    }

    public com.stellar.platform.analysis.investigation.EngineeringInvestigation duplicateInvestigation(
            com.stellar.platform.analysis.investigation.EngineeringInvestigation source
    ) {
        var copy = desktopInvestigationService.duplicate(metadataStore, source);
        noteLogicalChange();
        appendAudit(AuditEventType.INVESTIGATION_CREATED, copy.investigationId(), "Duplicated " + source.name());
        return copy;
    }

    public void deleteInvestigation(com.stellar.platform.analysis.investigation.EngineeringInvestigation investigation) {
        desktopInvestigationService.delete(metadataStore, investigation);
        noteLogicalChange();
        appendAudit(AuditEventType.INVESTIGATION_UPDATED, investigation.investigationId(), "Deleted");
    }

    public com.stellar.platform.analysis.investigation.EngineeringInvestigation addInvestigationEvidence(
            com.stellar.platform.analysis.investigation.EngineeringInvestigation investigation,
            com.stellar.platform.analysis.investigation.EngineeringEvidenceItem item
    ) {
        var saved = desktopInvestigationService.addEvidence(metadataStore, investigation, item);
        noteLogicalChange();
        appendAudit(AuditEventType.INVESTIGATION_UPDATED, saved.investigationId(), "Evidence added");
        return saved;
    }

    public void reorderInvestigationEvidence(
            com.stellar.platform.analysis.investigation.EngineeringInvestigation investigation,
            java.util.List<String> evidenceIds
    ) {
        desktopInvestigationService.reorderEvidence(metadataStore, investigation, evidenceIds);
        noteLogicalChange();
    }

    public void setInvestigationRangeFromGraph(
            com.stellar.platform.analysis.investigation.EngineeringInvestigation investigation
    ) {
        GraphInvestigationSnapshot snap = graphInvestigation.snapshot(currentProjectId(), signalsModel.workspaceKeys());
        saveInvestigation(investigation.toBuilder()
                .range(snap.viewportStartNanos(), snap.viewportEndNanos())
                .build());
    }

    public void applyInvestigationGraph(
            com.stellar.platform.analysis.investigation.EngineeringInvestigation investigation
    ) {
        if (investigation == null) {
            return;
        }
        desktopInvestigationService.rememberWorkspace(
                metadataStore, currentProjectId(), investigation.investigationId());
        java.util.List<GraphInvestigationSnapshot.Trace> traces =
                GraphInvestigationCodec.decodeTraces(investigation.graphTracesBlob());
        java.util.List<SignalCatalogKey> resolved = new java.util.ArrayList<>();
        java.util.List<GraphInvestigationSnapshot.Trace> remapped = new java.util.ArrayList<>();
        for (GraphInvestigationSnapshot.Trace trace : traces) {
            if (trace == null || trace.key() == null) {
                continue;
            }
            Optional<SignalCatalogKey> key = signalsModel.resolveCatalogKey(trace.key());
            if (key.isPresent()) {
                resolved.add(key.get());
                remapped.add(new GraphInvestigationSnapshot.Trace(key.get(), trace.hidden(), trace.colorIndex()));
            }
        }
        SignalCatalogKey focus = GraphInvestigationCodec.decodeKey(investigation.focusKeyBlob());
        SignalCatalogKey remappedFocus = focus == null ? null : signalsModel.resolveCatalogKey(focus).orElse(null);
        signalsModel.clearWorkspace();
        if (!resolved.isEmpty()) {
            signalsModel.addToWorkspace(resolved);
        }
        graphInvestigation.restore(new GraphInvestigationSnapshot(
                currentProjectId(),
                investigation.layoutMode(),
                investigation.viewportStartNanos(),
                investigation.viewportEndNanos(),
                investigation.rangeStartNanos(),
                investigation.rangeEndNanos(),
                investigation.cursorANanos(),
                investigation.cursorBNanos(),
                "",
                remapped,
                remappedFocus,
                0, true, false, "", ""), resolved);
        noteGraphLogicalChange();
    }

    public void focusInvestigationEvidence(
            com.stellar.platform.analysis.investigation.EngineeringInvestigation investigation,
            com.stellar.platform.analysis.investigation.EngineeringEvidenceItem item
    ) {
        if (investigation != null) {
            applyInvestigationGraph(investigation);
        }
        if (item == null) {
            return;
        }
        switch (item.kind()) {
            case ANNOTATION -> desktopInvestigationService.findAnnotation(
                    metadataStore, currentProjectId(), item.referenceId()).ifPresent(ann -> {
                graphInvestigation.setViewport(Math.max(0, ann.startTimestampNanos() - 1_000_000_000L),
                        ann.effectiveEndTimestampNanos() + 1_000_000_000L);
                graphInvestigation.setCursorA(ann.startTimestampNanos());
                graphInvestigation.setCursorB(ann.effectiveEndTimestampNanos());
            });
            case DIAGNOSTIC_FINDING -> DesktopInvestigationService.findingForTest(
                    metadataStore, currentProjectId(), item.referenceId()).ifPresent(f -> {
                long end = f.endTimestampNanos() == null ? f.startTimestampNanos() : f.endTimestampNanos();
                graphInvestigation.setViewport(Math.max(0, f.startTimestampNanos() - 1_000_000_000L),
                        end + 1_000_000_000L);
            });
            case ANALYSIS_RESULT, SPECTRAL_RESULT -> metadataStore.findAnalysisRun(item.referenceId())
                    .ifPresent(run -> {
                        if (run.startTimestampNanos() != null && run.endTimestampNanos() != null) {
                            graphInvestigation.setViewport(run.startTimestampNanos(), run.endTimestampNanos());
                        }
                        graphInvestigation.setAnalysisTab(item.kind()
                                == com.stellar.platform.analysis.investigation.EvidenceKind.SPECTRAL_RESULT ? 1 : 0);
                    });
            default -> {
            }
        }
        noteGraphLogicalChange();
    }

    public Path generateInvestigationReport(
            com.stellar.platform.analysis.investigation.EngineeringInvestigation investigation, String author
    ) throws java.io.IOException {
        return generateInvestigationReport(investigation, author, null);
    }

    public Path generateInvestigationReport(
            com.stellar.platform.analysis.investigation.EngineeringInvestigation investigation,
            String author,
            Path destination
    ) throws java.io.IOException {
        awaitCommittedChannelMappings(investigation);
        appendAudit(AuditEventType.REPORT_GENERATION_STARTED, investigation.investigationId(), investigation.name());
        var job = desktopInvestigationService.startReportJob();
        try {
            RouteMapReportAsset automaticMap = automaticRouteMap(investigation, job.token(), false);
            java.util.List<SignalReportImage> signalImages = new SignalReportLoader()
                    .load(this, investigation, false, job.token());
            Path html = destination == null
                    ? desktopInvestigationService.generateHtmlReport(
                    metadataStore, investigation, author, job.token(), automaticMap, signalImages)
                    : desktopInvestigationService.writeHtmlReport(
                    metadataStore, investigation, author, projectHealthDisplay(), destination, job.token(),
                    automaticMap, signalImages);
            appendAudit(AuditEventType.REPORT_GENERATED, investigation.investigationId(), html.toString());
            return html;
        } catch (com.stellar.platform.analysis.source.AnalysisCancelledException cancelled) {
            throw cancelled;
        } catch (RuntimeException | java.io.IOException e) {
            appendAudit(AuditEventType.REPORT_FAILED, investigation.investigationId(),
                    e.getMessage() == null ? e.toString() : e.getMessage());
            throw e;
        }
    }

    public Path previewInvestigationHtml(
            com.stellar.platform.analysis.investigation.EngineeringInvestigation investigation,
            String author,
            Path destination
    ) throws java.io.IOException {
        awaitCommittedChannelMappings(investigation);
        var job = desktopInvestigationService.startReportJob();
        RouteMapReportAsset automaticMap = automaticRouteMap(investigation, job.token(), false);
        java.util.List<SignalReportImage> signalImages = new SignalReportLoader()
                .load(this, investigation, false, job.token());
        return desktopInvestigationService.writeHtmlReport(
                metadataStore, investigation, author, projectHealthDisplay(), destination, job.token(),
                automaticMap, signalImages, false);
    }

    public Path generateInvestigationPdfReport(
            com.stellar.platform.analysis.investigation.EngineeringInvestigation investigation,
            String author,
            Path destination
    ) throws java.io.IOException {
        if (destination == null) {
            throw new java.io.IOException("Choose a destination for the report.");
        }
        awaitCommittedChannelMappings(investigation);
        appendAudit(AuditEventType.REPORT_GENERATION_STARTED, investigation.investigationId(), investigation.name());
        var job = desktopInvestigationService.startReportJob();
        try {
            RouteMapReportAsset automaticMap = automaticRouteMap(investigation, job.token(), false);
            java.util.List<SignalReportImage> signalImages = new SignalReportLoader()
                    .load(this, investigation, false, job.token());
            Path pdf = desktopInvestigationService.writePdfReport(
                    metadataStore, investigation, author, projectHealthDisplay(), destination, job.token(),
                    automaticMap, signalImages);
            appendAudit(AuditEventType.REPORT_GENERATED, investigation.investigationId(), pdf.toString());
            return pdf;
        } catch (com.stellar.platform.analysis.source.AnalysisCancelledException cancelled) {
            throw cancelled;
        } catch (RuntimeException | java.io.IOException e) {
            appendAudit(AuditEventType.REPORT_FAILED, investigation.investigationId(),
                    e.getMessage() == null ? e.toString() : e.getMessage());
            throw e;
        }
    }

    /**
     * Builds a static route image from processed GPS data for the current analysis scope.
     * Returns null when this project has no route, so the report omits the map section.
     */
    private RouteMapReportAsset automaticRouteMap(
            com.stellar.platform.analysis.investigation.EngineeringInvestigation investigation,
            com.stellar.platform.parser.spi.CancellationToken token,
            boolean persistedOnly) {
        if (investigation == null || !investigation.projectId().equals(currentProjectId())) {
            return null;
        }
        try {
            return new RouteMapReportRenderer().prepare(this, investigation, persistedOnly, token);
        } catch (com.stellar.platform.analysis.source.AnalysisCancelledException cancelled) {
            throw cancelled;
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Report map was not prepared: " + ex.getMessage());
            return null;
        }
    }

    public Path exportInvestigationPackage(
            com.stellar.platform.analysis.investigation.EngineeringInvestigation investigation,
            Path destination,
            String author
    ) throws java.io.IOException {
        var job = desktopInvestigationService.startReportJob();
        RouteMapReportAsset automaticMap = automaticRouteMap(investigation, job.token(), true);
        java.util.List<SignalReportImage> signalImages = new SignalReportLoader()
                .load(this, investigation, true, job.token());
        Path zip = desktopInvestigationService.exportPackage(
                metadataStore, investigation, destination, author, job.token(), automaticMap, signalImages);
        appendAudit(AuditEventType.INVESTIGATION_EXPORTED, investigation.investigationId(), zip.toString());
        return zip;
    }

    public Path exportDiagnosticsSupportBundle(Path destination) throws java.io.IOException {
        java.util.List<String> errors = new java.util.ArrayList<>();
        java.util.List<String> audit = new java.util.ArrayList<>();
        String projectId = currentProjectId();
        if (metadataStore != null && projectId != null && !projectId.isBlank()) {
            for (var err : metadataStore.listProcessingErrors(projectId)) {
                errors.add(err.severity() + ": " + err.summary());
            }
            for (var event : metadataStore.listRecentAuditEvents(projectId, 40)) {
                audit.add(event.eventType().name() + " " + event.summary());
            }
        }
        var storage = analysisStorage().currentStatus();
        String storageStatus = storage == null ? "unknown" : storage.status() + " " + storage.message();
        var health = workerHealth();
        String worker = "isolation=" + (isolateProductionProcessing ? "PROCESS_ISOLATED" : "IN_PROCESS")
                + " pid=" + health.pid() + " alive=" + health.alive()
                + " heapUsed=" + health.heapUsedBytes() + " activeJobs=" + health.activeJobs();
        Path log = AnalysisStorageRoots.isConfigured()
                ? AnalysisStorageRoots.logsRoot().resolve("stellar-desktop.log")
                : null;
        return com.stellar.platform.desktop.support.DiagnosticsSupportBundle.write(
                destination,
                new com.stellar.platform.desktop.support.DiagnosticsSupportBundle.Snapshot(
                        com.stellar.platform.desktop.DesktopVersion.displayVersion(),
                        com.stellar.platform.storage.metadata.SqliteProjectMetadataStore.currentSchemaVersion(),
                        System.getProperty("java.version"),
                        System.getProperty("os.name"),
                        System.getProperty("os.arch"),
                        storageStatus,
                        worker,
                        errors,
                        audit,
                        log != null && Files.isRegularFile(log) ? log : null),
                null);
    }

    public void deleteInvestigationReport(com.stellar.platform.storage.metadata.InvestigationReportRow row)
            throws java.io.IOException {
        desktopInvestigationService.deleteReport(metadataStore, row);
    }

    public com.stellar.platform.storage.metadata.InvestigationReportRow renameInvestigationReport(
            com.stellar.platform.storage.metadata.InvestigationReportRow row,
            String requestedName,
            java.util.Collection<String> namesInDirectory
    ) throws java.io.IOException {
        return new ReportRenameService().rename(row, requestedName, namesInDirectory,
                metadataStore::upsertInvestigationReport);
    }

    public com.stellar.platform.analysis.investigation.EngineeringInvestigation attachLastAnalysisToInvestigation() {
        var inv = requireActiveInvestigation();
        AnalysisResult last = desktopAnalysisService.lastResult();
        if (inv == null || last == null) {
            return inv;
        }
        return addInvestigationEvidence(inv, new com.stellar.platform.analysis.investigation.EngineeringEvidenceItem(
                null, inv.investigationId(),
                com.stellar.platform.analysis.investigation.EvidenceKind.ANALYSIS_RESULT,
                last.analysisId(), 0, false, "", fingerprintsForWorkspace(), 0L,
                last.analysisId(), desktopAnalysisService.lastReport(), Instant.now()));
    }

    public com.stellar.platform.analysis.investigation.EngineeringInvestigation attachLastAdvancedToInvestigation() {
        var inv = requireActiveInvestigation();
        var last = desktopAnalysisService.lastAdvanced();
        if (inv == null || last == null) {
            return inv;
        }
        return addInvestigationEvidence(inv, new com.stellar.platform.analysis.investigation.EngineeringEvidenceItem(
                null, inv.investigationId(),
                com.stellar.platform.analysis.investigation.EvidenceKind.SPECTRAL_RESULT,
                last.analysisId(), 0, false, "", fingerprintsForWorkspace(), 0L,
                last.type() == null ? "FFT" : last.type().name(),
                desktopAnalysisService.lastReport(), Instant.now()));
    }

    public com.stellar.platform.analysis.investigation.EngineeringInvestigation attachFindingToInvestigation(
            com.stellar.platform.analysis.diagnostics.DiagnosticFinding finding
    ) {
        var inv = requireActiveInvestigation();
        if (inv == null || finding == null) {
            return inv;
        }
        return addInvestigationEvidence(inv, new com.stellar.platform.analysis.investigation.EngineeringEvidenceItem(
                null, inv.investigationId(),
                com.stellar.platform.analysis.investigation.EvidenceKind.DIAGNOSTIC_FINDING,
                finding.findingId(), 0, false, "", fingerprintsForWorkspace(), 0L,
                finding.ruleId() + " " + finding.severity(),
                finding.summary(), Instant.now()));
    }

    public com.stellar.platform.analysis.investigation.EngineeringInvestigation attachAnnotationToInvestigation(
            String annotationId, String caption
    ) {
        var inv = requireActiveInvestigation();
        if (inv == null || annotationId == null || annotationId.isBlank()) {
            return inv;
        }
        return addInvestigationEvidence(inv, new com.stellar.platform.analysis.investigation.EngineeringEvidenceItem(
                null, inv.investigationId(),
                com.stellar.platform.analysis.investigation.EvidenceKind.ANNOTATION,
                annotationId, 0, false, "", fingerprintsForWorkspace(), 0L,
                caption == null ? "Annotation" : caption, caption == null ? "" : caption, Instant.now()));
    }

    public com.stellar.platform.analysis.investigation.EngineeringInvestigation attachGraphSnapshot(
            byte[] png, String caption
    ) throws java.io.IOException {
        var inv = requireActiveInvestigation();
        if (inv == null) {
            throw new IllegalStateException("Select or create an investigation first.");
        }
        String snapshotId = java.util.UUID.randomUUID().toString();
        Path file = desktopInvestigationService.writeSnapshotPng(
                currentProjectId(), inv.investigationId(), snapshotId, png);
        GraphInvestigationSnapshot snap = graphInvestigation.snapshot(currentProjectId(), signalsModel.workspaceKeys());
        java.util.List<String> keys = new java.util.ArrayList<>();
        java.util.List<String> visible = new java.util.ArrayList<>();
        java.util.List<String> units = new java.util.ArrayList<>();
        java.util.List<com.stellar.platform.domain.catalog.SignalCatalogEntry> entries = java.util.List.of();
        try {
            if (signalsModel.catalog() != null && signalsModel.catalog().entries() != null) {
                entries = signalsModel.catalog().entries();
            }
        } catch (RuntimeException ignored) {
            entries = java.util.List.of();
        }
        for (GraphInvestigationSnapshot.Trace t : snap.traces()) {
            String encoded = GraphInvestigationCodec.encodeKey(t.key());
            keys.add(encoded);
            if (!t.hidden()) {
                visible.add(t.key().signalName());
            }
            String unit = "";
            for (var entry : entries) {
                if (entry != null && encoded.equals(GraphInvestigationCodec.encodeKey(entry.key()))) {
                    unit = entry.unit() == null ? "" : entry.unit();
                    break;
                }
            }
            units.add(unit);
        }
        String label = caption == null || caption.isBlank() ? "Graph snapshot" : caption.strip();
        String timeBasis = "UNKNOWN_ORDER".equals(graphInvestigation.viewportDomain())
                ? "LAYOUT_SLOT"
                : "VERIFIED_UTC".equals(graphInvestigation.viewportDomain())
                ? "VERIFIED_UTC" : "MEASUREMENT_ELAPSED";
        desktopInvestigationService.writeSnapshotMetadata(new com.stellar.platform.analysis.investigation.GraphSnapshotMetadata(
                snapshotId, inv.investigationId(), Instant.now(),
                snap.viewportStartNanos(), snap.viewportEndNanos(),
                keys, visible, snap.layoutMode(), fingerprintsForWorkspace(), file.toString(),
                units, label, timeBasis));
        return addInvestigationEvidence(inv, new com.stellar.platform.analysis.investigation.EngineeringEvidenceItem(
                null, inv.investigationId(),
                com.stellar.platform.analysis.investigation.EvidenceKind.GRAPH_SNAPSHOT,
                snapshotId, 0, false, "", fingerprintsForWorkspace(), 0L,
                label, label, Instant.now()));
    }

    /**
     * Attaches the route already on screen. The image is the configured basemap snapshot.
     * Credentials are not written into the evidence row or the summary file.
     */
    public com.stellar.platform.analysis.investigation.EngineeringInvestigation attachRouteMap(
            byte[] png,
            com.stellar.platform.analysis.investigation.report.RouteMapSnapshot facts
    ) throws java.io.IOException {
        return attachRouteMap(png, facts, null);
    }

    public com.stellar.platform.analysis.investigation.EngineeringInvestigation attachRouteMap(
            byte[] png,
            com.stellar.platform.analysis.investigation.report.RouteMapSnapshot facts,
            String caption
    ) throws java.io.IOException {
        var inv = requireActiveInvestigation();
        if (inv == null) {
            throw new IllegalStateException("Select or create an investigation first.");
        }
        if (facts == null) {
            throw new IllegalArgumentException("Route map requires an existing route.");
        }
        String snapshotId = java.util.UUID.randomUUID().toString();
        boolean image = png != null && png.length > 0;
        if (image) {
            desktopInvestigationService.writeSnapshotPng(
                    currentProjectId(), inv.investigationId(), snapshotId, png);
        }
        var stored = new com.stellar.platform.analysis.investigation.report.RouteMapSnapshot(
                facts.startNanos(), facts.endNanos(), facts.sampleCount(), facts.distanceMeters(),
                facts.south(), facts.north(), facts.west(), facts.east(), image);
        Path json = com.stellar.platform.analysis.investigation.InvestigationStoragePaths.routeMapMetadata(
                AnalysisStorageRoots.projectArtifactsRoot(currentProjectId()),
                inv.investigationId(), snapshotId);
        java.nio.file.Files.createDirectories(json.getParent());
        java.nio.file.Files.writeString(json, stored.toJson());
        String routeLabel = caption == null || caption.isBlank() ? "Route map" : caption.strip();
        return addInvestigationEvidence(inv, new com.stellar.platform.analysis.investigation.EngineeringEvidenceItem(
                null, inv.investigationId(),
                com.stellar.platform.analysis.investigation.EvidenceKind.ROUTE_MAP,
                snapshotId, 0, false, "", fingerprintsForWorkspace(), 0L,
                routeLabel, image ? routeLabel : com.stellar.platform.analysis.investigation.report.EngineeringReportModel.RouteMapSection.UNAVAILABLE,
                Instant.now()));
    }

    private com.stellar.platform.analysis.investigation.EngineeringInvestigation requireActiveInvestigation() {
        String id = desktopInvestigationService.activeInvestigationId();
        if (id == null || id.isBlank()) {
            var listed = desktopInvestigationService.list(metadataStore, currentProjectId());
            return listed.isEmpty() ? null : listed.getFirst();
        }
        return desktopInvestigationService.find(metadataStore, id).orElse(null);
    }

    private void persistInvestigationWorkspaceCritical() {
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        desktopInvestigationService.rememberWorkspace(
                metadataStore, projectId, desktopInvestigationService.activeInvestigationId());
    }

    private void restoreInvestigationWorkspace() {
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        desktopInvestigationService.lastOpenInvestigationId(metadataStore, projectId)
                .ifPresent(desktopInvestigationService::setActiveInvestigationId);
        java.util.Set<String> fingerprints = currentSourceFingerprints();
        if (!fingerprints.isEmpty()) {
            desktopInvestigationService.refreshAllStaleness(metadataStore, projectId, fingerprints);
        }
    }

    public long diagnosticArtifactGeneration() {
        return diagnosticArtifactGeneration.get();
    }

    public com.stellar.platform.analysis.diagnostics.DiagnosticRunResult runEngineeringDiagnostics(
            com.stellar.platform.analysis.diagnostics.DiagnosticRunRequest request
    ) {
        SignalSeriesStore store = signalsModel.catalogStore();
        if (store == null) {
            throw new IllegalStateException("No processed signals are loaded");
        }
        return desktopDiagnosticService.run(request, store, signalsModel.catalog().entries(), metadataStore);
    }

    public com.stellar.platform.analysis.diagnostics.DiagnosticRunResult lastEngineeringDiagnostics() {
        return desktopDiagnosticService.lastResult();
    }

    public String lastEngineeringDiagnosticsReport() {
        return desktopDiagnosticService.lastReport();
    }

    public Path exportEngineeringDiagnostics(Path destination) {
        Path written = desktopDiagnosticService.exportSummary(destination, metadataStore, currentProjectId());
        Path findings = destination.resolveSibling(
                destination.getFileName().toString().replace(".csv", "-findings.csv"));
        desktopDiagnosticService.exportFindings(findings, metadataStore, currentProjectId());
        Path eval = destination.resolveSibling(
                destination.getFileName().toString().replace(".csv", "-evaluation.csv"));
        desktopDiagnosticService.exportEvaluation(eval, metadataStore, currentProjectId());
        return written;
    }

    public Path exportDiagnosticRulePack(Path destination) {
        return desktopDiagnosticService.exportPack(
                destination, desktopDiagnosticService.projectCustomPack(metadataStore, currentProjectId()),
                metadataStore, currentProjectId());
    }

    public java.util.List<com.stellar.platform.analysis.rules.DiagnosticRulePack> diagnosticPacks() {
        return desktopDiagnosticService.availablePacks(metadataStore, currentProjectId());
    }

    public com.stellar.platform.analysis.rules.DiagnosticRulePack projectCustomRulePack() {
        return desktopDiagnosticService.projectCustomPack(metadataStore, currentProjectId());
    }

    public void saveCustomDiagnosticRule(com.stellar.platform.analysis.rules.DiagnosticRuleDefinition rule) {
        if (rule == null) {
            return;
        }
        var existing = desktopDiagnosticService.projectCustomPack(metadataStore, currentProjectId());
        com.stellar.platform.analysis.rules.DiagnosticRuleDefinition toSave = rule;
        if (existing.byId().containsKey(rule.ruleId())) {
            toSave = com.stellar.platform.analysis.workflow.RuleTemplates.incrementVersion(rule);
        }
        desktopDiagnosticService.saveCustomRule(metadataStore, currentProjectId(), toSave);
        appendAudit(existing.byId().containsKey(rule.ruleId())
                        ? AuditEventType.RULE_UPDATED : AuditEventType.RULE_CREATED,
                toSave.ruleId(), toSave.name() + " v" + toSave.version());
        noteLogicalChange();
    }

    public void deleteCustomDiagnosticRule(String ruleId) {
        desktopDiagnosticService.deleteCustomRule(metadataStore, currentProjectId(), ruleId);
        noteLogicalChange();
    }

    public com.stellar.platform.analysis.workflow.PackImportPreview previewDiagnosticPackImport(String json) {
        return desktopDiagnosticService.previewImport(json, metadataStore, currentProjectId());
    }

    public com.stellar.platform.analysis.rules.DiagnosticRulePack importDiagnosticPack(String json) {
        var pack = desktopDiagnosticService.importPack(json, metadataStore, currentProjectId());
        appendAudit(AuditEventType.RULE_PACK_IMPORTED, pack.packId(),
                pack.name() + " v" + pack.packVersion());
        noteLogicalChange();
        return pack;
    }

    public com.stellar.platform.analysis.diagnostics.DiagnosticRunResult dryRunEngineeringDiagnostics(
            com.stellar.platform.analysis.diagnostics.DiagnosticRunRequest request
    ) {
        SignalSeriesStore store = signalsModel.catalogStore();
        if (store == null) {
            throw new IllegalStateException("No processed signals are loaded");
        }
        return desktopDiagnosticService.dryRun(request, store, signalsModel.catalog().entries());
    }

    public java.util.List<com.stellar.platform.storage.metadata.DiagnosticRunMetadata> diagnosticRunHistory() {
        return desktopDiagnosticService.listRuns(metadataStore, currentProjectId());
    }

    public com.stellar.platform.analysis.diagnostics.DiagnosticRunResult loadDiagnosticRun(String runId) {
        var restored = desktopDiagnosticService.reconstruct(metadataStore, runId);
        if (restored != null) {
            desktopDiagnosticService.restoreLast(restored, restored.runId());
        }
        return restored;
    }

    public void saveDiagnosticFindingReview(
            String findingId,
            com.stellar.platform.analysis.workflow.FindingReviewState state,
            String note
    ) {
        desktopDiagnosticService.saveReview(metadataStore, currentProjectId(), findingId, state, note);
        noteLogicalChange();
    }

    public java.util.Map<String, com.stellar.platform.storage.metadata.DiagnosticFindingReviewRow>
            loadDiagnosticReviews() {
        return desktopDiagnosticService.loadReviews(metadataStore, currentProjectId());
    }

    public void saveDiagnosticRulePreference(String packId, String ruleId, boolean enabled) {
        desktopDiagnosticService.saveRulePreference(metadataStore, currentProjectId(), packId, ruleId, enabled);
        noteLogicalChange();
    }

    public java.util.Map<String, Boolean> loadDiagnosticRulePreferences(String packId) {
        return desktopDiagnosticService.loadRulePreferences(metadataStore, currentProjectId(), packId);
    }

    public Path exportSelectedDiagnosticFindings(
            Path destination,
            java.util.List<com.stellar.platform.analysis.diagnostics.DiagnosticFinding> findings
    ) {
        return desktopDiagnosticService.exportSelectedFindings(
                destination, findings, metadataStore, currentProjectId());
    }

    public void recordDiagnosticActivity(AuditEventType type, String subjectId, String detail) {
        appendAudit(type, subjectId, detail);
    }

    public void persistDiagnosticBindings(String packId, java.util.Map<String, SignalCatalogKey> bindings) {
        desktopDiagnosticService.persistBindings(metadataStore, currentProjectId(), packId, bindings);
        noteLogicalChange();
    }

    public java.util.Map<String, SignalCatalogKey> loadDiagnosticBindings(String packId) {
        return desktopDiagnosticService.loadBindings(metadataStore, currentProjectId(), packId);
    }

    public void createAnnotationFromFinding(
            com.stellar.platform.analysis.diagnostics.DiagnosticFinding finding
    ) {
        if (finding == null || currentProjectId() == null || currentProjectId().isBlank()) {
            return;
        }
        metadataWrite(() -> metadataStore.upsertGraphAnnotation(
                desktopDiagnosticService.annotationFromFinding(currentProjectId(), finding)));
    }

    private void bumpDiagnosticArtifactGeneration() {
        long generation = diagnosticArtifactGeneration.incrementAndGet();
        String projectId = currentProjectId();
        if (projectId != null && !projectId.isBlank()) {
            metadataStore.invalidateDiagnosticRuns(projectId, generation);
            desktopInvestigationService.refreshAllStaleness(
                    metadataStore, projectId, currentSourceFingerprints());
        }
    }

    private void restoreDiagnosticWorkspace() {
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        metadataStore.findDiagnosticWorkspace(projectId).ifPresent(ws -> {
            if (ws.lastRunId().isBlank()) {
                return;
            }
            var restored = desktopDiagnosticService.reconstruct(metadataStore, ws.lastRunId());
            if (restored != null) {
                desktopDiagnosticService.restoreLast(restored, ws.lastPackId());
            }
        });
    }

    public Path exportEngineeringAnalysis(Path destination) {
        Path written = desktopAnalysisService.exportSummary(destination, metadataStore, currentProjectId());
        Path findings = destination.resolveSibling(
                destination.getFileName().toString().replace(".csv", "-findings.csv"));
        desktopAnalysisService.exportFindings(findings, metadataStore, currentProjectId());
        return written;
    }

    public Path exportAdvancedSpectrum(Path destination) {
        return desktopAnalysisService.exportSpectrum(destination, metadataStore, currentProjectId());
    }

    public Path exportAdvancedPsd(Path destination) {
        return desktopAnalysisService.exportPsd(destination, metadataStore, currentProjectId());
    }

    public Path exportAdvancedPeaks(Path destination) {
        return desktopAnalysisService.exportPeaks(destination, metadataStore, currentProjectId());
    }

    public Path exportAdvancedDerived(Path destination) {
        return desktopAnalysisService.exportAdvancedDerived(destination, metadataStore, currentProjectId());
    }

    public Path exportAdvancedLag(Path destination) {
        return desktopAnalysisService.exportLag(destination, metadataStore, currentProjectId());
    }

    public Path exportAdvancedCycles(Path destination) {
        return desktopAnalysisService.exportCycles(destination, metadataStore, currentProjectId());
    }

    private void persistAnalysisWorkspaceCritical() {
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        var advanced = desktopAnalysisService.lastAdvanced();
        String type = desktopAnalysisService.lastAnalysisType();
        if (advanced != null && advanced.status() != null && advanced.status().successful()) {
            try {
                com.stellar.platform.analysis.advanced.AdvancedAnalyticsType.valueOf(type);
                metadataStore.upsertAnalysisWorkspace(new AnalysisWorkspaceMetadata(
                        projectId,
                        type,
                        advanced.analysisId(),
                        true,
                        "",
                        Instant.now()
                ));
                return;
            } catch (IllegalArgumentException ignored) {
                // last type is Phase 4
            }
        }
        AnalysisResult result = desktopAnalysisService.lastResult();
        if (result == null) {
            return;
        }
        if (type.isBlank()) {
            type = result.message() == null ? "" : result.message();
        }
        metadataStore.upsertAnalysisWorkspace(new AnalysisWorkspaceMetadata(
                projectId,
                type,
                result.analysisId(),
                true,
                result.findings().isEmpty() ? "" : result.findings().getFirst().findingId(),
                Instant.now()
        ));
    }

    private void restoreAnalysisWorkspace() {
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        metadataStore.findAnalysisWorkspace(projectId).ifPresent(ws -> {
            if (ws.lastAnalysisId().isBlank()) {
                return;
            }
            metadataStore.findAnalysisRun(ws.lastAnalysisId()).ifPresent(run -> {
                try {
                    AnalysisResult.Builder b = AnalysisResult.builder(run.analysisId())
                            .status(AnalysisStatus.valueOf(run.status()))
                            .startedAt(run.createdAt())
                            .completedAt(run.completedAt())
                            .alignmentPolicy(AlignmentPolicy.valueOf(run.alignmentPolicy()))
                            .analysisVersion(run.analysisVersion())
                            .message(run.message());
                    if (run.startTimestampNanos() != null && run.endTimestampNanos() != null) {
                        b.effectiveRange(new TimeWindow(run.startTimestampNanos(), run.endTimestampNanos()));
                        b.requestedRange(new TimeWindow(run.startTimestampNanos(), run.endTimestampNanos()));
                    }
                    int thresholdEvents = 0;
                    for (var row : metadataStore.listAnalysisFindings(run.analysisId())) {
                        FindingType findingType;
                        try {
                            findingType = FindingType.valueOf(row.findingType());
                        } catch (RuntimeException ex) {
                            continue;
                        }
                        AnalysisSeverity severity;
                        try {
                            severity = AnalysisSeverity.valueOf(row.severity());
                        } catch (RuntimeException ex) {
                            severity = AnalysisSeverity.OBSERVATION;
                        }
                        b.addFinding(new EngineeringFinding(
                                row.findingId(), row.analysisId(), findingType, severity, List.of(),
                                row.startTimestampNanos(), row.endTimestampNanos(),
                                row.summary(), row.details(), java.util.Map.of(), row.createdAt()));
                        if (findingType == FindingType.THRESHOLD_EVENT) {
                            thresholdEvents++;
                        }
                    }
                    if (thresholdEvents > 0) {
                        b.metric("eventCount", thresholdEvents);
                    }
                    desktopAnalysisService.restoreLast(b.build(), ws.lastAnalysisType());
                    restoreAdvancedFromArtifact(run);
                } catch (RuntimeException ignored) {
                    // Corrupt analysis workspace must not block project reopen.
                }
            });
        });
    }

    private void restoreAdvancedFromArtifact(com.stellar.platform.storage.metadata.AnalysisRunMetadata run) {
        if (run == null || run.analysisId() == null || run.analysisId().isBlank()) {
            return;
        }
        try {
            com.stellar.platform.analysis.advanced.AdvancedAnalyticsType type =
                    com.stellar.platform.analysis.advanced.AdvancedAnalyticsType.valueOf(run.analysisType());
            Path root = AnalysisStorageRoots.projectArtifactsRoot(currentProjectId());
            var writer = desktopAnalysisService.advancedArtifacts();
            if (!writer.hasCommitted(root, run.analysisId())) {
                return;
            }
            var restored = com.stellar.platform.analysis.advanced.AdvancedAnalyticsResult
                    .builder(run.analysisId(), type)
                    .status(AnalysisStatus.valueOf(run.status()))
                    .message(run.message())
                    .cacheKey(run.invalidationKey())
                    .reproducibility(writer.loadManifest(root, run.analysisId()))
                    .spectrum(writer.loadSpectrum(root, run.analysisId()))
                    .psd(writer.loadPsd(root, run.analysisId()))
                    .peaks(writer.loadPeaks(root, run.analysisId()))
                    .artifactPath(run.artifactRef())
                    .build();
            var withTime = restored.toBuilder();
            if (run.startTimestampNanos() != null && run.endTimestampNanos() != null) {
                withTime.window(new TimeWindow(run.startTimestampNanos(), run.endTimestampNanos()));
            }
            withTime.completedAt(run.completedAt());
            desktopAnalysisService.restoreAdvanced(withTime.build());
        } catch (IllegalArgumentException ignored) {
            // Phase 4 analysis type — no advanced artifact.
        }
    }

    /** Persists an Engineering Analysis bookmark for the active project. */
    public void persistEngineeringBookmark(
            String signalName,
            String eventType,
            String observation,
            long timestampNanos
    ) {
        Instant now = Instant.now();
        String sourceId = workspace().selectedMeasurement().map(WorkspaceFile::id).orElse("");
        metadataWrite(() -> metadataStore.upsertBookmark(
                new com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata(
                        java.util.UUID.randomUUID().toString(),
                        metadataProjectId.get(),
                        sourceId,
                        "",
                        signalName == null ? "" : signalName,
                        signalName == null ? "" : signalName,
                        timestampNanos,
                        eventType == null ? "" : eventType,
                        observation == null ? "" : observation,
                        now,
                        now
                )));
        noteLogicalChange();
    }

    /**
     * Manual investigation event at the selected timestamp. Reuses engineering_bookmarks.
     */
    public void persistInvestigationEvent(
            String title,
            String category,
            String note,
            long timestampNanos,
            String signalName
    ) {
        persistEngineeringBookmark(
                signalName,
                InvestigationEventCodec.categoryOf(category),
                InvestigationEventCodec.encode(title, note),
                timestampNanos);
    }

    /**
     * Graph Add Event. A point leaves {@code endNanos} null. A window stores the end
     * in the same bookmark. Signal keys are the catalog identities selected on the graph.
     *
     * @return the bookmark id, or blank when the window is invalid or no project is open
     */
    public String saveGraphEvent(
            String existingId,
            String title,
            String category,
            String note,
            long startNanos,
            Long endNanos,
            String sourceId,
            String sourceFile,
            java.util.List<String> signalNames,
            java.util.List<String> signalKeys
    ) {
        return saveGraphEvent(existingId, title, category, note, startNanos, endNanos,
                sourceId, sourceFile, signalNames, signalKeys, "", null);
    }

    /**
     * Graph Add Event. {@code sampleText} and {@code sampleNumber} record the plotted sample
     * for a point. A window leaves both empty.
     */
    public String saveGraphEvent(
            String existingId,
            String title,
            String category,
            String note,
            long startNanos,
            Long endNanos,
            String sourceId,
            String sourceFile,
            java.util.List<String> signalNames,
            java.util.List<String> signalKeys,
            String sampleText,
            Double sampleNumber
    ) {
        if (endNanos != null && endNanos < startNanos) {
            return "";
        }
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank()) {
            return "";
        }
        String id = existingId == null || existingId.isBlank()
                ? java.util.UUID.randomUUID().toString() : existingId;
        java.util.List<String> names = signalNames == null ? java.util.List.of() : signalNames;
        java.util.List<String> keys = signalKeys == null ? java.util.List.of() : signalKeys;
        String signal = names.isEmpty() ? "" : names.getFirst();
        String signalKey = keys.isEmpty() ? signal : keys.getFirst();
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant created = findEngineeringBookmark(id)
                .map(com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata::createdAt)
                .orElse(now);
        String observation = EngineerEventDocuments.graphObservation(
                title, note, endNanos, names, keys, sourceFile, sampleText, sampleNumber);
        if (!persistWriteQuiet(() -> metadataStore.upsertBookmark(
                new com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata(
                        id, projectId, sourceId == null ? "" : sourceId, "", signal, signalKey, startNanos,
                        InvestigationEventCodec.categoryOf(category), observation, created, now)))) {
            return "";
        }
        noteLogicalChange();
        return id;
    }

    /** Snapshot choices for the selected graph event. The image files are not opened into memory here. */
    public java.util.List<GraphSnapshotLinks.Choice> graphSnapshotChoices(String bookmarkId) {
        var defined = findEngineeringBookmark(bookmarkId).map(this::eventForLink).orElse(null);
        java.util.List<String> linked = defined == null ? java.util.List.of() : defined.snapshotIds();
        String projectId = currentProjectId();
        return GraphSnapshotLinks.choices(
                defined,
                desktopInvestigationService.list(metadataStore, projectId),
                com.stellar.platform.storage.analysis.AnalysisStorageRoots.projectArtifactsRoot(projectId),
                linked);
    }

    /**
     * Links one existing snapshot to a graph event. A blank return means the same bookmark was updated.
     * A refusal leaves the bookmark unchanged.
     */
    public String linkGraphEventSnapshot(String bookmarkId, String snapshotId) {
        var found = findEngineeringBookmark(bookmarkId);
        if (found.isEmpty() || snapshotId == null || snapshotId.isBlank()) {
            return "The event was not found.";
        }
        var existing = found.get();
        var defined = eventForLink(existing);
        if (defined.snapshotIds().contains(snapshotId)) {
            return "";
        }
        GraphSnapshotLinks.Choice match = null;
        for (var choice : graphSnapshotChoices(bookmarkId)) {
            if (snapshotId.equals(choice.snapshotId())) {
                match = choice;
            }
        }
        if (match == null) {
            return "That snapshot is not in this project.";
        }
        if (!match.linkAllowed()) {
            return match.detail();
        }
        java.util.List<String> ids = new java.util.ArrayList<>(defined.snapshotIds());
        ids.add(snapshotId);
        String observation = EngineerEventDocuments.withSnapshots(
                existing.observation(), ids, snapshotNote(ids, graphSnapshotChoices(bookmarkId)));
        if (!rewriteBookmarkObservation(existing, observation)) {
            return "The event was not saved.";
        }
        noteLogicalChange();
        return "";
    }

    /** Stores a GPS match or a refusal on the existing event. The event itself stays. */
    public String saveGraphEventGps(String bookmarkId, GraphEventGps.Evidence evidence) {
        var found = findEngineeringBookmark(bookmarkId);
        if (found.isEmpty() || evidence == null) {
            return "The event was not found.";
        }
        String observation = GraphEventGps.write(found.get().observation(), evidence);
        if (!rewriteBookmarkObservation(found.get(), observation)) {
            return "The event was not saved.";
        }
        noteLogicalChange();
        return "";
    }

    /** Drops the GPS link. The event and its snapshot links stay. */
    public String clearGraphEventGps(String bookmarkId) {
        return saveGraphEventGps(bookmarkId, GraphEventGps.notRequested());
    }

    /** Drops one snapshot id from the event. The image file stays. */
    public String unlinkGraphEventSnapshot(String bookmarkId, String snapshotId) {
        var found = findEngineeringBookmark(bookmarkId);
        if (found.isEmpty() || snapshotId == null || snapshotId.isBlank()) {
            return "The event was not found.";
        }
        var existing = found.get();
        var defined = eventForLink(existing);
        if (!defined.snapshotIds().contains(snapshotId)) {
            return "";
        }
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (String id : defined.snapshotIds()) {
            if (!snapshotId.equals(id)) {
                ids.add(id);
            }
        }
        String observation = EngineerEventDocuments.withSnapshots(
                existing.observation(), ids, snapshotNote(ids, graphSnapshotChoices(bookmarkId)));
        if (!rewriteBookmarkObservation(existing, observation)) {
            return "The event was not saved.";
        }
        noteLogicalChange();
        return "";
    }

    /** Removes the report row. The snapshot file and any event link stay. */
    public String excludeGraphSnapshot(String snapshotId) {
        var holder = investigationHolding(snapshotId);
        if (holder == null) {
            return "";
        }
        java.util.List<com.stellar.platform.analysis.investigation.EngineeringEvidenceItem> next = new java.util.ArrayList<>();
        int order = 0;
        for (var item : holder.evidence()) {
            if (item.kind() == com.stellar.platform.analysis.investigation.EvidenceKind.GRAPH_SNAPSHOT
                    && snapshotId.equals(item.referenceId())) {
                continue;
            }
            next.add(item.withOrder(order++));
        }
        saveInvestigation(holder.toBuilder().evidence(next).build());
        return "";
    }

    /** Puts an existing snapshot file back on its investigation. The PNG is not written again. */
    public String includeGraphSnapshot(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            return "That snapshot is not in this project.";
        }
        if (investigationHolding(snapshotId) != null) {
            return "";
        }
        String projectId = currentProjectId();
        Path png = GraphSnapshotLinks.png(
                com.stellar.platform.storage.analysis.AnalysisStorageRoots.projectArtifactsRoot(projectId),
                "", snapshotId);
        var meta = GraphSnapshotLinks.read(png);
        if (png == null || meta == null) {
            return "The snapshot file is missing. It was not regenerated.";
        }
        var owner = desktopInvestigationService.find(metadataStore, meta.investigationId()).orElse(null);
        if (owner == null || projectId == null || !projectId.equals(owner.projectId())) {
            return "That snapshot's investigation is no longer in this project.";
        }
        String label = meta.caption().isBlank() ? "Graph snapshot" : meta.caption();
        addInvestigationEvidence(owner, new com.stellar.platform.analysis.investigation.EngineeringEvidenceItem(
                null, owner.investigationId(),
                com.stellar.platform.analysis.investigation.EvidenceKind.GRAPH_SNAPSHOT,
                snapshotId, 0, false, "", fingerprintsForWorkspace(), 0L,
                label, label, Instant.now()));
        return "";
    }

    /**
     * Deletes the snapshot file only when no event and no other investigation still reference it.
     *
     * @return a blank string when the file was deleted, otherwise the reason it was kept
     */
    public String deleteGraphSnapshot(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            return "That snapshot is not in this project.";
        }
        for (var bookmark : listEngineeringBookmarks()) {
            var defined = EngineerEventDocuments.decode(bookmark);
            if (defined != null && defined.snapshotIds().contains(snapshotId)) {
                return "This snapshot is still linked to an event. The image was not deleted.";
            }
        }
        java.util.List<com.stellar.platform.analysis.investigation.EngineeringInvestigation> holders = new java.util.ArrayList<>();
        for (var investigation : desktopInvestigationService.list(metadataStore, currentProjectId())) {
            if (holdsSnapshot(investigation, snapshotId)) {
                holders.add(investigation);
            }
        }
        if (holders.size() > 1) {
            return "This snapshot is used by more than one investigation. The image was not deleted.";
        }
        String projectId = currentProjectId();
        Path png = GraphSnapshotLinks.png(
                com.stellar.platform.storage.analysis.AnalysisStorageRoots.projectArtifactsRoot(projectId),
                holders.isEmpty() ? "" : holders.getFirst().investigationId(), snapshotId);
        if (png == null && holders.isEmpty()) {
            return "That snapshot is not in this project.";
        }
        for (var holder : holders) {
            excludeGraphSnapshot(snapshotId);
        }
        if (png != null) {
            try {
                java.nio.file.Files.deleteIfExists(png);
                String jsonName = png.getFileName().toString().endsWith(".png")
                        ? png.getFileName().toString().substring(0, png.getFileName().toString().length() - 4) + ".json"
                        : png.getFileName().toString() + ".json";
                java.nio.file.Files.deleteIfExists(png.resolveSibling(jsonName));
            } catch (java.io.IOException ex) {
                return "The snapshot image could not be deleted.";
            }
        }
        return "";
    }

    private com.stellar.platform.analysis.investigation.EngineerEventDefinition eventForLink(
            com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata bookmark
    ) {
        var defined = EngineerEventDocuments.decode(bookmark);
        if (defined != null) {
            return defined;
        }
        String signal = bookmark.signalName() == null ? "" : bookmark.signalName();
        return new com.stellar.platform.analysis.investigation.EngineerEventDefinition(
                bookmark.bookmarkId(),
                InvestigationEventCodec.titleOf(bookmark.observation()),
                bookmark.eventType(),
                bookmark.sourceId() == null ? "" : bookmark.sourceId(),
                "",
                bookmark.timestampNanos(),
                null,
                signal.isBlank() ? java.util.List.of() : java.util.List.of(signal),
                InvestigationEventCodec.noteOf(bookmark.observation()),
                "", "", java.util.List.of(), false, "Draft", "", "", "No graph snapshot is linked.");
    }

    private static String snapshotNote(
            java.util.List<String> ids, java.util.List<GraphSnapshotLinks.Choice> choices
    ) {
        if (ids == null || ids.isEmpty()) {
            return "No graph snapshot is linked.";
        }
        StringBuilder text = new StringBuilder();
        for (String id : ids) {
            String caption = id;
            for (var choice : choices) {
                if (id.equals(choice.snapshotId()) && choice.caption() != null && !choice.caption().isBlank()) {
                    caption = choice.caption();
                }
            }
            if (!text.isEmpty()) {
                text.append("; ");
            }
            text.append(caption);
        }
        return text.toString();
    }

    private boolean rewriteBookmarkObservation(
            com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata existing, String observation
    ) {
        return persistWriteQuiet(() -> metadataStore.upsertBookmark(
                new com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata(
                        existing.bookmarkId(), existing.projectId(), existing.sourceId(), existing.artifactId(),
                        existing.signalName(), existing.signalKey(), existing.timestampNanos(), existing.eventType(),
                        observation, existing.createdAt(), Instant.now())));
    }

    private com.stellar.platform.analysis.investigation.EngineeringInvestigation investigationHolding(String snapshotId) {
        for (var investigation : desktopInvestigationService.list(metadataStore, currentProjectId())) {
            if (holdsSnapshot(investigation, snapshotId)) {
                return investigation;
            }
        }
        return null;
    }

    private static boolean holdsSnapshot(
            com.stellar.platform.analysis.investigation.EngineeringInvestigation investigation, String snapshotId
    ) {
        if (investigation == null || snapshotId == null) {
            return false;
        }
        for (var item : investigation.evidence()) {
            if (item.kind() == com.stellar.platform.analysis.investigation.EvidenceKind.GRAPH_SNAPSHOT
                    && snapshotId.equals(item.referenceId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Saves an engineer event in engineering_bookmarks. Report inclusion is a separate evidence link.
     *
     * @return the bookmark id, or blank when the end is before the start or no project is open
     */
    public String saveEngineerEvent(com.stellar.platform.analysis.investigation.EngineerEventDefinition event) {
        if (event == null) {
            return "";
        }
        if (event.window() && event.endNanos() < event.startNanos()) {
            return "";
        }
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank()) {
            return "";
        }
        String id = event.id().isBlank() ? java.util.UUID.randomUUID().toString() : event.id();
        var stored = event.withId(id);
        String signal = stored.signals().isEmpty() ? "" : stored.signals().getFirst();
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant created = findEngineeringBookmark(id)
                .map(com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata::createdAt)
                .orElse(now);
        if (!persistWriteQuiet(() -> metadataStore.upsertBookmark(
                new com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata(
                        id, projectId, stored.sourceId(), "", signal, signal, stored.startNanos(),
                        InvestigationEventCodec.categoryOf(stored.category()),
                        EngineerEventDocuments.observation(stored), created, now)))) {
            return "";
        }
        noteLogicalChange();
        return id;
    }

    /**
     * Stores a new rule version. An existing version row is left unchanged.
     * Version 1 is created when {@code draft.ruleId()} is blank or unknown.
     */
    public com.stellar.platform.analysis.investigation.eval.EvaluationRule saveEvaluationRule(
            com.stellar.platform.analysis.investigation.eval.EvaluationRule draft
    ) {
        if (draft == null) {
            return null;
        }
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank() || metadataStore == null) {
            return null;
        }
        String id = draft.ruleId().isBlank() ? java.util.UUID.randomUUID().toString() : draft.ruleId();
        int max = 0;
        String created = "";
        for (var record : metadataStore.listEvaluationRules(projectId)) {
            if (!id.equals(record.ruleId())) {
                continue;
            }
            max = Math.max(max, record.version());
            if (record.version() == 1) {
                var previous = com.stellar.platform.analysis.investigation.eval.EvaluationDocuments.decodeRule(record.document());
                if (previous != null) {
                    created = previous.createdAt();
                }
            }
        }
        java.time.Instant now = java.time.Instant.now();
        if (created.isBlank()) {
            created = now.toString();
        }
        var stored = draft.withIdentity(id, max + 1, created, now.toString());
        if (!persistWriteQuiet(() -> metadataStore.insertEvaluationRule(
                new com.stellar.platform.storage.metadata.EvaluationRuleRecord(
                        projectId, id, stored.version(),
                        com.stellar.platform.analysis.investigation.eval.EvaluationDocuments.encodeRule(stored), now)))) {
            return null;
        }
        noteLogicalChange();
        return stored;
    }

    public java.util.List<com.stellar.platform.analysis.investigation.eval.EvaluationRule> listEvaluationRules() {
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank() || metadataStore == null) {
            return java.util.List.of();
        }
        java.util.List<com.stellar.platform.analysis.investigation.eval.EvaluationRule> out = new java.util.ArrayList<>();
        for (var record : metadataStore.listEvaluationRules(projectId)) {
            var rule = com.stellar.platform.analysis.investigation.eval.EvaluationDocuments.decodeRule(record.document());
            if (rule != null) {
                out.add(rule);
            }
        }
        return java.util.List.copyOf(out);
    }

    /**
     * Runs one selected rule and stores the result. {@code series} null reads the open catalog.
     * The stored document is the result for this rule version.
     */
    public com.stellar.platform.analysis.investigation.eval.EvaluationResult evaluateAndStore(
            com.stellar.platform.analysis.investigation.EngineerEventDefinition event,
            com.stellar.platform.analysis.investigation.eval.EvaluationRule rule,
            java.util.List<com.stellar.platform.analysis.investigation.eval.EvaluationSeries> series,
            boolean clocksComparable,
            boolean included
    ) {
        java.util.List<com.stellar.platform.analysis.investigation.eval.EvaluationSeries> used = series != null
                ? series : seriesFromCatalog(rule, event);
        return storeEvaluation(com.stellar.platform.analysis.investigation.eval.EvaluationEngine.evaluate(
                rule, event, used, clocksComparable), included);
    }

    public com.stellar.platform.analysis.investigation.eval.EvaluationResult storeNotEvaluated(
            com.stellar.platform.analysis.investigation.EngineerEventDefinition event,
            com.stellar.platform.analysis.investigation.eval.EvaluationRule rule,
            boolean included
    ) {
        return storeEvaluation(com.stellar.platform.analysis.investigation.eval.EvaluationEngine.notEvaluated(rule, event),
                included);
    }

    public void setEvaluationIncluded(String resultId, boolean included) {
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank() || resultId == null || resultId.isBlank() || metadataStore == null) {
            return;
        }
        persistWriteQuiet(() -> metadataStore.setEvaluationIncluded(projectId, resultId, included));
    }

    private com.stellar.platform.analysis.investigation.eval.EvaluationResult storeEvaluation(
            com.stellar.platform.analysis.investigation.eval.EvaluationResult result, boolean included
    ) {
        if (result == null) {
            return null;
        }
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank() || metadataStore == null) {
            return null;
        }
        String id = java.util.UUID.randomUUID().toString();
        java.time.Instant now = java.time.Instant.now();
        var stored = result.withId(id, now.toString());
        if (!persistWriteQuiet(() -> metadataStore.insertEvaluationResult(
                new com.stellar.platform.storage.metadata.EvaluationResultRecord(
                        projectId, id, stored.eventId(), stored.ruleId(), stored.ruleVersion(), included,
                        com.stellar.platform.analysis.investigation.eval.EvaluationDocuments.encodeResult(stored), now)))) {
            return null;
        }
        noteLogicalChange();
        return stored;
    }

    private java.util.List<com.stellar.platform.analysis.investigation.eval.EvaluationSeries> seriesFromCatalog(
            com.stellar.platform.analysis.investigation.eval.EvaluationRule rule,
            com.stellar.platform.analysis.investigation.EngineerEventDefinition event
    ) {
        com.stellar.platform.processing.catalog.SignalSeriesStore store = signalsModel.catalogStore();
        if (rule == null || store == null || store.snapshot() == null || store.snapshot().entries() == null) {
            return java.util.List.of();
        }
        java.util.List<com.stellar.platform.analysis.investigation.eval.EvaluationSeries> out = new java.util.ArrayList<>();
        for (String required : rule.requiredSignals()) {
            String name = com.stellar.platform.analysis.investigation.EngineerEventValidation.signalName(required);
            com.stellar.platform.domain.catalog.SignalCatalogEntry entry = null;
            for (var candidate : store.snapshot().entries()) {
                if (name.equals(candidate.signalName())) {
                    entry = candidate;
                    break;
                }
            }
            if (entry == null) {
                continue;
            }
            java.util.List<com.stellar.platform.analysis.investigation.eval.EvaluationSample> samples = new java.util.ArrayList<>();
            int retained = store.retainedSampleCount(entry.key());
            int offset = 0;
            while (offset < retained) {
                var page = store.querySamples(entry.key(), offset, 5_000);
                if (page.isEmpty()) {
                    break;
                }
                for (var sample : page) {
                    samples.add(new com.stellar.platform.analysis.investigation.eval.EvaluationSample(
                            sample.timestampNanos(), sample.physicalValue(), sample.usableMeasurement(), ""));
                }
                offset += page.size();
            }
            String unit = entry.unit() == null ? "" : entry.unit();
            String source = event == null ? "" : event.sourceId();
            out.add(new com.stellar.platform.analysis.investigation.eval.EvaluationSeries(name, unit, source, samples));
        }
        return out;
    }

    /** Adds a report pointer to a saved event. A second include does not add another row. */
    public com.stellar.platform.analysis.investigation.EngineeringInvestigation includeEngineerEvent(String eventId) {
        var inv = requireActiveInvestigation();
        if (inv == null || eventId == null || eventId.isBlank()) {
            return inv;
        }
        String caption = findEngineeringBookmark(eventId)
                .map(bookmark -> InvestigationEventCodec.titleOf(bookmark.observation()))
                .orElse(eventId);
        var next = com.stellar.platform.analysis.investigation.EngineerEventInclusion.include(
                inv.evidence(), inv.investigationId(), eventId, caption);
        if (next == inv.evidence()) {
            return inv;
        }
        return saveInvestigation(inv.toBuilder().evidence(next).build());
    }

    /** Drops the report pointer. The bookmark remains in the project. */
    public com.stellar.platform.analysis.investigation.EngineeringInvestigation excludeEngineerEvent(String eventId) {
        var inv = requireActiveInvestigation();
        if (inv == null || eventId == null || eventId.isBlank()) {
            return inv;
        }
        var next = com.stellar.platform.analysis.investigation.EngineerEventInclusion.exclude(inv.evidence(), eventId);
        if (next.size() == inv.evidence().size()) {
            return inv;
        }
        return saveInvestigation(inv.toBuilder().evidence(next).build());
    }

    /** Bookmark in the open project, matched by its stable id. */
    public Optional<com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata> findEngineeringBookmark(
            String bookmarkId) {
        if (bookmarkId == null || bookmarkId.isBlank()) {
            return Optional.empty();
        }
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        for (var bookmark : listEngineeringBookmarks()) {
            if (bookmarkId.equals(bookmark.bookmarkId()) && projectId.equals(bookmark.projectId())) {
                return Optional.of(bookmark);
            }
        }
        return Optional.empty();
    }

    /**
     * Updates title, category, and note of one manual event. The bookmark id, project,
     * measurement source, signal, and timestamp stay on the existing record.
     *
     * @return false when the event is missing, not manual, or the write did not stick
     */
    public boolean updateInvestigationEvent(String bookmarkId, String title, String category, String note) {
        if (!InvestigationEventCodec.isManual(category)) {
            return false;
        }
        var found = findEngineeringBookmark(bookmarkId);
        if (found.isEmpty() || !InvestigationEventCodec.isManual(found.get().eventType())) {
            return false;
        }
        var existing = found.get();
        String heading = title == null || title.isBlank() ? "Event" : title.strip();
        String storedCategory = InvestigationEventCodec.categoryOf(category);
        var updated = new com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata(
                existing.bookmarkId(),
                existing.projectId(),
                existing.sourceId(),
                existing.artifactId(),
                existing.signalName(),
                existing.signalKey(),
                existing.timestampNanos(),
                storedCategory,
                EngineerEventDocuments.withTitleAndNote(existing.observation(), heading, note),
                existing.createdAt(),
                java.time.Instant.now());
        if (!persistWriteQuiet(() -> metadataStore.upsertBookmark(updated))) {
            return false;
        }
        var stored = findEngineeringBookmark(bookmarkId);
        if (stored.isEmpty()
                || !existing.bookmarkId().equals(stored.get().bookmarkId())
                || !existing.projectId().equals(stored.get().projectId())
                || !existing.sourceId().equals(stored.get().sourceId())
                || stored.get().timestampNanos() != existing.timestampNanos()
                || !storedCategory.equals(stored.get().eventType())
                || !heading.equals(InvestigationEventCodec.titleOf(stored.get().observation()))
                || listEngineeringBookmarks().stream().filter(b -> bookmarkId.equals(b.bookmarkId())).count() != 1) {
            return false;
        }
        noteLogicalChange();
        return true;
    }

    /**
     * Deletes one manual event in the open project.
     *
     * @return false when the id is missing, belongs to another project, or is not a manual event
     */
    public boolean deleteInvestigationEvent(String bookmarkId) {
        var found = findEngineeringBookmark(bookmarkId);
        if (found.isEmpty() || !InvestigationEventCodec.isManual(found.get().eventType())) {
            return false;
        }
        String projectId = found.get().projectId();
        excludeEngineerEvent(bookmarkId);
        if (!persistWriteQuiet(() -> metadataStore.deleteBookmark(projectId, bookmarkId))) {
            return false;
        }
        if (findEngineeringBookmark(bookmarkId).isPresent()) {
            return false;
        }
        noteLogicalChange();
        return true;
    }

    /**
     * Persists the Map Route selection for the active measurement. Only mode, timestamps and the
     * sync toggle are stored; the route geometry is rebuilt from processed signals on reload.
     */
    public void persistMapRouteState(
            String mode,
            Long pointTimestampNanos,
            Long rangeStartNanos,
            Long rangeEndNanos,
            boolean autoSync
    ) {
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        String sourceId = workspace().selectedMeasurement().map(WorkspaceFile::id).orElse("");
        metadataWrite(() -> metadataStore.upsertMapRouteState(
                new com.stellar.platform.storage.metadata.MapRouteStateMetadata(
                        projectId,
                        sourceId,
                        mode,
                        pointTimestampNanos,
                        rangeStartNanos,
                        rangeEndNanos,
                        autoSync,
                        Instant.now()
                )));
    }

    /**
     * Source identity used when a project's processed catalog carries no measurement file name.
     * Both Map Route and GPS Configuration must derive the same value or a saved mapping would not
     * be found again.
     */
    public static final String GPS_MAPPING_MERGED_SOURCE =
            com.stellar.platform.processing.map.GpsMappingScope.MERGED_SOURCE;

    /**
     * Measurement sources GPS mapping can be configured for, taken from the processed signal
     * catalog. No measurement file is opened or scanned to answer this.
     */
    public List<String> gpsMappingSourceIds() {
        return com.stellar.platform.processing.map.GpsMappingScope
                .sourceIds(signalsModel.mapRouteCatalog());
    }

    /** The source Map Route configures by default — the first processed measurement in scope. */
    public String defaultGpsMappingSourceId() {
        return com.stellar.platform.processing.map.GpsMappingScope
                .defaultSourceId(signalsModel.mapRouteCatalog());
    }

    /**
     * Catalog entries a GPS mapping may choose from for one source, and the catalog its schema
     * fingerprint is computed over. Shared with Map Route through
     * {@link com.stellar.platform.processing.map.GpsMappingScope} so both sides scope identically.
     */
    public List<SignalCatalogEntry> gpsMappingCatalog(String sourceId) {
        AnalysisMeasurementContext ctx = resolveAnalysisMeasurement(sourceId);
        if (ctx.isPresent()) {
            return engineeringCatalog(ctx);
        }
        return com.stellar.platform.processing.map.GpsMappingScope
                .catalogFor(sourceId, signalsModel.mapRouteCatalog());
    }

    /**
     * How many processed measurements share a source's GPS schema. Configuration reports this so an
     * engineer knows one mapping already covers a set of identical recordings.
     */
    public int gpsMappingCompatibleSourceCount(String sourceId) {
        String fingerprint = com.stellar.platform.processing.map.GpsMappingScope
                .fingerprintFor(sourceId, signalsModel.mapRouteCatalog());
        if (fingerprint.isBlank()) {
            return 0;
        }
        List<SignalCatalogEntry> catalog = signalsModel.mapRouteCatalog();
        int count = 0;
        for (String id : gpsMappingSourceIds()) {
            if (fingerprint.equals(com.stellar.platform.processing.map.GpsMappingScope
                    .fingerprintFor(id, catalog))) {
                count++;
            }
        }
        return count;
    }

    /** True when a saved project actually has processed signals to map GPS roles against. */
    public boolean hasProcessedSignalCatalog() {
        return isDurableNamedProject() && !signalsModel.mapRouteCatalog().isEmpty();
    }

    /**
     * Increments whenever GPS mappings are written. Map Route compares it so a mapping edited in
     * Configuration cannot be missed when the route request signature is otherwise unchanged.
     */
    public int gpsMappingRevision() {
        return gpsMappingRevision.get();
    }

    /**
     * Product-wide route configuration, shared by every project on this installation.
     *
     * <p>Opened on first use and kept open: it is a handful of rows read whenever a project resolves
     * its route mapping, and a project that never touches Map Route never opens the file at all.
     */
    private synchronized com.stellar.platform.storage.api.ConfigMappingDefaultsStore configDefaults() {
        if (configDefaultsStore == null) {
            configDefaultsStore = new com.stellar.platform.storage.metadata.SqliteConfigMappingDefaultsStore();
        }
        return configDefaultsStore;
    }

    /** Product default route mappings, or empty when this installation has none. */
    public java.util.List<com.stellar.platform.processing.map.GpsSignalMapping> loadRouteConfigDefaults() {
        try {
            java.util.List<com.stellar.platform.processing.map.GpsSignalMapping> out = new java.util.ArrayList<>();
            for (com.stellar.platform.storage.metadata.SemanticSignalMappingMetadata row
                    : configDefaults().listDefaultMappings(
                            com.stellar.platform.storage.metadata.SemanticSignalMappingMetadata.GROUP_MAP_ROUTE)) {
                toGpsMapping(row).ifPresent(out::add);
            }
            return java.util.List.copyOf(out);
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING,
                    "Cannot load product route configuration: " + ex.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Replaces the product default route configuration. An empty list removes it, which returns every
     * project without its own configuration to automatic detection.
     */
    public void persistRouteConfigDefaults(
            java.util.List<com.stellar.platform.processing.map.GpsSignalMapping> mappings) {
        gpsMappingRevision.incrementAndGet();
        try {
            com.stellar.platform.storage.api.ConfigMappingDefaultsStore store = configDefaults();
            store.deleteDefaultMappings(
                    com.stellar.platform.storage.metadata.SemanticSignalMappingMetadata.GROUP_MAP_ROUTE);
            if (mappings == null) {
                return;
            }
            Instant now = Instant.now();
            for (com.stellar.platform.processing.map.GpsSignalMapping mapping : mappings) {
                if (mapping == null) {
                    continue;
                }
                store.upsertDefaultMapping(toMetadata(mapping, "", "", "",
                        com.stellar.platform.storage.metadata.SemanticSignalMappingMetadata.SCOPE_DEFAULT,
                        now));
            }
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING,
                    "Cannot store product route configuration: " + ex.getMessage());
        }
    }

    /**
     * User-confirmed GPS role mappings for the open project. Missing or malformed rows are skipped.
     */
    public java.util.List<com.stellar.platform.processing.map.GpsSignalMapping> loadAllGpsSignalMappings() {
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank()) {
            return java.util.List.of();
        }
        try {
            java.util.List<com.stellar.platform.processing.map.GpsSignalMapping> out = new java.util.ArrayList<>();
            for (com.stellar.platform.storage.metadata.SemanticSignalMappingMetadata row
                    : metadataStore.listSemanticSignalMappings(projectId)) {
                toGpsMapping(row).ifPresent(out::add);
            }
            return java.util.List.copyOf(out);
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Cannot load GPS mappings: " + ex.getMessage());
            return java.util.List.of();
        }
    }

    public boolean persistGpsSignalMappings(
            String sourceId,
            String schemaFingerprint,
            java.util.List<com.stellar.platform.processing.map.GpsSignalMapping> mappings
    ) {
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        AnalysisMeasurementContext ctx = resolveAnalysisMeasurement(sourceId);
        String source = ctx.isPresent() ? ctx.sourceId() : (sourceId == null ? "" : sourceId);
        String legacyName = ctx.isPresent() ? ctx.fileName() : "";
        boolean stored = persistWriteQuiet(() -> {
            metadataStore.deleteSemanticSignalMappings(projectId, source,
                    com.stellar.platform.storage.metadata.SemanticSignalMappingMetadata.GROUP_MAP_ROUTE);
            if (!legacyName.isBlank() && !legacyName.equals(source)) {
                metadataStore.deleteSemanticSignalMappings(projectId, legacyName,
                        com.stellar.platform.storage.metadata.SemanticSignalMappingMetadata.GROUP_MAP_ROUTE);
            }
            if (mappings == null || mappings.isEmpty()) {
                return;
            }
            Instant now = Instant.now();
            String fingerprint = schemaFingerprint == null ? "" : schemaFingerprint;
            for (com.stellar.platform.processing.map.GpsSignalMapping mapping : mappings) {
                if (mapping == null || !mapping.userConfirmed()) {
                    continue;
                }
                metadataStore.upsertSemanticSignalMapping(toMetadata(
                        mapping,
                        projectId,
                        source,
                        fingerprint.isBlank() ? mapping.schemaFingerprint() : fingerprint,
                        com.stellar.platform.storage.metadata.SemanticSignalMappingMetadata.SCOPE_PROJECT,
                        now));
            }
        });
        if (stored) {
            gpsMappingRevision.incrementAndGet();
        }
        return stored;
    }

    public com.stellar.platform.processing.drive.speed.SpeedSourceMapping loadVehicleSpeedMapping(String sourceId) {
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank()) {
            return null;
        }
        try {
            for (com.stellar.platform.storage.metadata.SemanticSignalMappingMetadata row
                    : metadataStore.listSemanticSignalMappings(projectId, sourceId)) {
                java.util.Optional<com.stellar.platform.processing.drive.speed.SpeedSourceMapping> mapped =
                        VehicleSpeedMappingCodec.fromMetadata(row);
                if (mapped.isPresent()) {
                    return mapped.get();
                }
            }
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Cannot load vehicle-speed mapping: " + ex.getMessage());
        }
        return null;
    }

    public boolean persistVehicleSpeedMapping(
            com.stellar.platform.processing.drive.speed.SpeedSourceMapping mapping) {
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank() || mapping == null) {
            return false;
        }
        return persistWriteQuiet(() -> metadataStore.upsertSemanticSignalMapping(
                VehicleSpeedMappingCodec.toMetadata(projectId, mapping)));
    }

    /** One conversion for both stores: a mapping is the same row whatever scope it is written at. */
    private static com.stellar.platform.storage.metadata.SemanticSignalMappingMetadata toMetadata(
            com.stellar.platform.processing.map.GpsSignalMapping mapping,
            String projectId,
            String sourceId,
            String schemaFingerprint,
            String scope,
            Instant now) {
        if (mapping.manual()) {
            return new com.stellar.platform.storage.metadata.SemanticSignalMappingMetadata(
                    projectId, sourceId, schemaFingerprint,
                    com.stellar.platform.storage.metadata.SemanticSignalMappingMetadata.GROUP_MAP_ROUTE,
                    scope,
                    mapping.role().name(),
                    com.stellar.platform.storage.metadata.SemanticSignalMappingMetadata.MODE_MANUAL_TEXT,
                    "", "", "", "GPS", 0, mapping.manualSignalName(), true, now);
        }
        com.stellar.platform.domain.catalog.SignalCatalogKey key = mapping.key();
        return new com.stellar.platform.storage.metadata.SemanticSignalMappingMetadata(
                projectId, sourceId, schemaFingerprint,
                com.stellar.platform.storage.metadata.SemanticSignalMappingMetadata.GROUP_MAP_ROUTE,
                scope,
                mapping.role().name(),
                com.stellar.platform.storage.metadata.SemanticSignalMappingMetadata.MODE_SIGNAL_REFERENCE,
                key.signalName(),
                key.sourceType().name(),
                key.sourceFile(),
                key.messageName(),
                key.channel(),
                "", true, now);
    }

    private static java.util.Optional<com.stellar.platform.processing.map.GpsSignalMapping> toGpsMapping(
            com.stellar.platform.storage.metadata.SemanticSignalMappingMetadata row) {
        if (row == null) {
            return java.util.Optional.empty();
        }
        try {
            com.stellar.platform.processing.map.GpsSignalRole role =
                    com.stellar.platform.processing.map.GpsSignalRole.valueOf(row.role());
            com.stellar.platform.processing.map.RouteMappingScope scope =
                    com.stellar.platform.processing.map.RouteMappingScope.parse(row.scope());
            if (row.manual()) {
                return java.util.Optional.of(com.stellar.platform.processing.map.GpsSignalMapping.manual(
                        row.projectId(), row.sourceId(), row.schemaFingerprint(),
                        role, row.manualText(), scope, row.updatedAt()));
            }
            com.stellar.platform.domain.catalog.SignalSourceType type;
            try {
                type = com.stellar.platform.domain.catalog.SignalSourceType.valueOf(row.sourceType());
            } catch (RuntimeException ex) {
                type = com.stellar.platform.domain.catalog.SignalSourceType.MF4_NATIVE;
            }
            com.stellar.platform.domain.catalog.SignalCatalogKey key =
                    new com.stellar.platform.domain.catalog.SignalCatalogKey(
                            row.signalName(), type, row.messageName(), 0, row.channel(),
                            row.sourceFile(), "");
            return java.util.Optional.of(new com.stellar.platform.processing.map.GpsSignalMapping(
                    row.projectId(), row.sourceId(), row.schemaFingerprint(), role,
                    com.stellar.platform.processing.map.RouteMappingMode.SIGNAL_REFERENCE, key, "",
                    scope, row.userConfirmed(), row.updatedAt()));
        } catch (RuntimeException ex) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Persists the current graph investigation. Membership is the Signals workspace;
     * visibility, layout, viewport, cursors and notes are graph-only.
     */
    public void persistGraphInvestigation() {
        boolean ok = persistWriteQuiet(this::persistGraphInvestigationCritical);
        saveCoordinator.graphPersistFinished(ok);
    }

    private void persistGraphInvestigationCritical() {
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        GraphInvestigationSnapshot snap = graphInvestigation.snapshot(projectId, signalsModel.workspaceKeys())
                .withoutTemporaryVisibility();
        String focusBlob = snap.focusKey() == null ? "" : GraphInvestigationCodec.encodeKey(snap.focusKey());
        metadataStore.upsertGraphInvestigation(
                new com.stellar.platform.storage.metadata.GraphInvestigationMetadata(
                        projectId,
                        snap.layoutMode(),
                        snap.viewportStartNanos(),
                        snap.viewportEndNanos(),
                        snap.defaultStartNanos(),
                        snap.defaultEndNanos(),
                        snap.cursorANanos(),
                        snap.cursorBNanos(),
                        snap.notes(),
                        GraphInvestigationCodec.encodeState(snap.traces(),
                                graphInvestigation.timelineGroup(),
                                graphInvestigation.fileFocusMeasurementId(),
                                graphInvestigation.viewportDomain()),
                        Instant.now(),
                        focusBlob,
                        snap.analysisTab(),
                        snap.inspectorVisible(),
                        snap.plotSettingsVisible(),
                        snap.signalsDisplayMode(),
                        snap.signalsPrecision(),
                        com.stellar.platform.storage.metadata.GraphInvestigationMetadata.PRESENTATION_VERSION
                ));
        persistAnalysisScopeCritical(projectId);
    }

    private void persistAnalysisScopeCritical(String projectId) {
        if (metadataStore == null || projectId == null || projectId.isBlank()) {
            return;
        }
        if (isProjectWideAnalysis()) {
            metadataStore.upsertAnalysisScope(new com.stellar.platform.storage.metadata.AnalysisScopeRecord(
                    projectId, "ALL", "", "", Instant.now()));
            return;
        }
        AnalysisMeasurementContext ctx = activeAnalysisMeasurement();
        metadataStore.upsertAnalysisScope(new com.stellar.platform.storage.metadata.AnalysisScopeRecord(
                projectId, "SINGLE", ctx.sourceId(), ctx.fileName(), Instant.now()));
    }

    /**
     * Restores a saved investigation by stable identity. A missing catalog entry is reported
     * unavailable — it is never rebound to another signal that merely shares the name.
     */
    public void restoreGraphInvestigation() {
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        Optional<com.stellar.platform.storage.metadata.GraphInvestigationMetadata> stored;
        try {
            stored = metadataStore.findGraphInvestigation(projectId);
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Cannot load graph investigation: " + ex.getMessage());
            restoreAnalysisScope(projectId);
            return;
        }
        if (stored.isEmpty()) {
            restoreAnalysisScope(projectId);
            return;
        }
        var meta = stored.get();
        if (!GraphInvestigationCodec.compatibleTracesBlob(meta.tracesBlob())) {
            LOG.log(System.Logger.Level.WARNING,
                    "Incompatible graph investigation state for " + projectId + "; opening Signals.");
            restoreAnalysisScope(projectId);
            return;
        }
        SignalCatalogKey focusKey = GraphInvestigationCodec.decodeKey(meta.focusKeyBlob());
        GraphInvestigationSnapshot snap = new GraphInvestigationSnapshot(
                meta.projectId(),
                meta.layoutMode(),
                meta.viewportStartNanos(),
                meta.viewportEndNanos(),
                meta.defaultStartNanos(),
                meta.defaultEndNanos(),
                meta.cursorANanos(),
                meta.cursorBNanos(),
                meta.notes(),
                GraphInvestigationCodec.decodeTraces(meta.tracesBlob()),
                focusKey,
                meta.analysisTab(),
                meta.inspectorVisible(),
                meta.plotSettingsVisible(),
                meta.signalsDisplayMode(),
                meta.signalsPrecision());
        GraphInvestigationCodec.Domain domain = GraphInvestigationCodec.decodeDomain(meta.tracesBlob());
        graphInvestigation.setTimelineContext(
                domain.timelineGroup(), domain.fileFocusMeasurementId(), domain.viewportDomain());
        restoreAnalysisScope(projectId);
        boolean savedSelection = snap.traces().stream().anyMatch(trace -> trace != null && trace.key() != null);
        if (savedSelection && !graphRestoreCatalogReady()) {
            LOG.log(System.Logger.Level.INFO,
                    "Graph restore deferred until the signal catalog is available for " + projectId);
            return;
        }
        List<SignalCatalogKey> resolved = new java.util.ArrayList<>();
        List<SignalCatalogKey> missing = new java.util.ArrayList<>();
        List<GraphInvestigationSnapshot.Trace> remappedTraces = new java.util.ArrayList<>();
        for (GraphInvestigationSnapshot.Trace trace : snap.traces()) {
            if (trace == null || trace.key() == null) {
                continue;
            }
            SignalCatalogEntry remapped = resolveRestoredGraphEntry(trace.key());
            if (remapped != null) {
                resolved.add(remapped.key());
                remappedTraces.add(new GraphInvestigationSnapshot.Trace(
                        remapped.key(), trace.hidden(), trace.colorIndex()));
            } else {
                missing.add(trace.key());
                LOG.log(System.Logger.Level.INFO, () ->
                        "Checkpoint signal missing on reopen key=" + trace.key());
            }
        }
        SignalCatalogKey remappedFocus = snap.focusKey() == null
                ? null
                : Optional.ofNullable(resolveRestoredGraphEntry(snap.focusKey()))
                .map(SignalCatalogEntry::key)
                .orElse(null);
        // Persisted membership is authoritative, including Clear View (empty).
        signalsModel.replacePhysicalWorkspace(resolved);
        GraphInvestigationSnapshot restoredSnap = new GraphInvestigationSnapshot(
                snap.projectId(),
                snap.layoutMode(),
                snap.viewportStartNanos(),
                snap.viewportEndNanos(),
                snap.defaultStartNanos(),
                snap.defaultEndNanos(),
                snap.cursorANanos(),
                snap.cursorBNanos(),
                snap.notes(),
                remappedTraces,
                remappedFocus,
                snap.analysisTab(),
                snap.inspectorVisible(),
                snap.plotSettingsVisible(),
                snap.signalsDisplayMode(),
                snap.signalsPrecision());
        graphInvestigation.restore(restoredSnap, resolved);
        if (SignalsWorkspacePreferences.obsoleteTimeline(snap.signalsDisplayMode())
                || SignalsWorkspacePreferences.obsoleteTimeline(snap.signalsPrecision())) {
            LOG.log(System.Logger.Level.INFO,
                    "Ignoring obsolete Signal Workspace timeline preference; aligned timeline stays active");
        }
        SignalsWorkspacePreferences.displayMode(snap.signalsDisplayMode())
                .ifPresent(signalsModel::setDisplayMode);
        SignalsWorkspacePreferences.precision(snap.signalsPrecision())
                .ifPresent(signalsModel::setPrecision);
        validateRestoredViewport(resolved);
        LOG.log(System.Logger.Level.INFO, () ->
                "Checkpoint restored resume=" + checkpointResumeRoute.get()
                        + " resolvedKeys=" + resolved.size()
                        + " missingKeys=" + missing.size()
                        + " viewportInitialized=" + graphInvestigation.viewportInitialized());
    }

    private void restoreAnalysisScope(String projectId) {
        if (metadataStore == null || projectId == null || projectId.isBlank()) {
            return;
        }
        Optional<com.stellar.platform.storage.metadata.AnalysisScopeRecord> stored;
        try {
            stored = metadataStore.findAnalysisScope(projectId);
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Cannot load analysis scope: " + ex.getMessage());
            return;
        }
        if (stored.isEmpty()) {
            return;
        }
        com.stellar.platform.storage.metadata.AnalysisScopeRecord rec = stored.get();
        if (rec.allMeasurements()) {
            analysisScopeRef.set(AnalysisScope.allMeasurements(workspaceMeasurementCount()));
            return;
        }
        if (!rec.sourceId().isBlank()) {
            selectMeasurement(rec.sourceId());
        }
    }

    private void validateRestoredViewport(List<SignalCatalogKey> resolved) {
        if (!graphInvestigation.viewportInitialized() || resolved == null || resolved.isEmpty()) {
            return;
        }
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        boolean any = false;
        for (SignalCatalogKey key : resolved) {
            if (graphInvestigation.isHidden(key)) {
                continue;
            }
            var entry = signalsModel.findEntry(key);
            if (entry.isEmpty()) {
                continue;
            }
            Optional<Long> first = entry.get().firstTimestampNanosOptional();
            Optional<Long> last = entry.get().lastTimestampNanosOptional();
            if (first.isPresent() && last.isPresent()) {
                any = true;
                min = Math.min(min, first.get());
                max = Math.max(max, last.get());
            }
        }
        if (!any || max <= min) {
            return;
        }
        long start = graphInvestigation.viewportStart();
        long end = graphInvestigation.viewportEnd();
        if (end <= start || end < min || start > max) {
            final long savedStart = start;
            final long savedEnd = end;
            final long dataMin = min;
            final long dataMax = max;
            LOG.log(System.Logger.Level.INFO, () ->
                    "Viewport fallback reason=OUTSIDE_DATA saved=" + savedStart + ".." + savedEnd
                            + " data=" + dataMin + ".." + dataMax);
            graphInvestigation.setViewport(min, max);
            graphInvestigation.captureDefaultViewport(min, max);
        }
    }

    /** Map Route selection saved for the active measurement, if any. */
    public Optional<com.stellar.platform.storage.metadata.MapRouteStateMetadata> loadMapRouteState() {
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        String sourceId = workspace().selectedMeasurement().map(WorkspaceFile::id).orElse("");
        try {
            return metadataStore.findMapRouteState(projectId, sourceId);
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Cannot load map route state: " + ex.getMessage());
            return Optional.empty();
        }
    }

    public List<com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata> listEngineeringBookmarks() {
        try {
            return metadataStore.listBookmarks(metadataProjectId.get());
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Cannot list engineering bookmarks: " + ex.getMessage());
            return List.of();
        }
    }

    public List<com.stellar.platform.storage.metadata.SignalCatalogMetadata> searchPersistedSignalCatalog(String query) {
        try {
            return metadataStore.searchSignalCatalog(metadataProjectId.get(), query);
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Cannot search persisted signal catalog: " + ex.getMessage());
            return List.of();
        }
    }

    public Optional<com.stellar.platform.storage.metadata.SignalProvenance> signalProvenance(String signalCatalogId) {
        try {
            return metadataStore.getSignalProvenance(metadataProjectId.get(), signalCatalogId);
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Cannot load signal provenance: " + ex.getMessage());
            return Optional.empty();
        }
    }

    public List<com.stellar.platform.storage.metadata.DataQualityFindingMetadata> listDataQualityFindings() {
        try {
            return metadataStore.listDataQualityFindings(metadataProjectId.get());
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Cannot list data-quality findings: " + ex.getMessage());
            return List.of();
        }
    }

    public List<com.stellar.platform.storage.metadata.HistoricalDbcMappingSummary> historicalDbcMappings(
            List<Long> observedCanIds
    ) {
        try {
            return metadataStore.findHistoricalDbcMappings(metadataProjectId.get(), observedCanIds);
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Cannot query historical DBC mappings: " + ex.getMessage());
            return List.of();
        }
    }

    Set<String> activeMeasurementSourceIds() {
        Set<String> living = new java.util.LinkedHashSet<>();
        for (WorkspaceFile file : workspace().measurementFiles()) {
            if (file != null && file.id() != null && !file.id().isBlank()) {
                living.add(file.id());
            }
        }
        return Set.copyOf(living);
    }

    /**
     * Force reprocess always rebuilds from the current workspace + living selection.
     * Incremental execute rebuilds only when the cached plan still lists a removed source.
     */
    ProjectProcessingPlan prepareActiveExecutionPlan(
            ProcessingSelectionWorkspaceModel model,
            ProcessingPlanInputs currentInputs,
            boolean forceReprocess
    ) {
        Set<String> living = activeMeasurementSourceIds();
        model.retainLivingSources(living);
        ProjectProcessingPlan reviewed = model.reviewedPlan();
        boolean stale = forceReprocess
                || reviewed == null
                || reviewed.isEmpty()
                || ProcessingSelectionWorkspaceModel.planContainsInactiveSources(reviewed, living);
        if (stale) {
            if (forceReprocess || model.selection().isEmpty()) {
                prepareProcessingFromDecisionWorkspace();
                model.retainLivingSources(living);
            }
            reviewed = model.rebuildReviewedPlanForSources(currentInputs, living);
        }
        if (ProcessingSelectionWorkspaceModel.planContainsInactiveSources(reviewed, living)) {
            return ProjectProcessingPlan.empty();
        }
        return reviewed;
    }

    ProjectProcessingPlan rebuildActiveReprocessPlan() {
        ProcessingPlanInputs inputs = new ProcessingPlanInputAssembler().assemble(this);
        return prepareActiveExecutionPlan(processingSelectionModel, inputs, true);
    }

    /**
     * Validates that each reviewed job is still executable with the same definition
     * against a fresh plan built from the same channels and current inputs.
     */
    boolean isReviewedPlanStillExecutable(ProjectProcessingPlan reviewed, ProcessingPlanInputs currentInputs) {
        Objects.requireNonNull(reviewed, "reviewed");
        Objects.requireNonNull(currentInputs, "currentInputs");
        if (ProcessingSelectionWorkspaceModel.planContainsInactiveSources(reviewed, activeMeasurementSourceIds())) {
            return false;
        }
        ProjectChannelSelection channels = new ProjectChannelSelection();
        for (ProcessingJob job : reviewed.jobs()) {
            channels.select(job.channel());
        }
        ProjectProcessingPlan validation = new ProcessingPlanBuilder().build(channels, currentInputs);
        if (validation.readiness() != ProcessingPlanReadiness.READY) {
            return false;
        }
        for (ProcessingJob reviewedJob : reviewed.jobs()) {
            ProcessingJob current = null;
            for (ProcessingJob j : validation.jobs()) {
                if (j.key().equals(reviewedJob.key())) {
                    current = j;
                    break;
                }
            }
            if (current == null || !current.executable()) {
                return false;
            }
            String expectedDef = reviewedJob.definitionSourceId().orElse(null);
            String actualDef = current.definitionSourceId().orElse(null);
            if (!Objects.equals(expectedDef, actualDef)) {
                return false;
            }
        }
        return true;
    }

    private Optional<DbcDatabase> loadDefinitionDatabaseCached(
            String definitionId,
            Map<String, DbcDatabase> cache
    ) {
        if (definitionId == null || definitionId.isBlank()) {
            return Optional.of(ProjectPlanExecutor.emptyDefinitionDatabase());
        }
        if (cache.containsKey(definitionId)) {
            return Optional.ofNullable(cache.get(definitionId));
        }
        Optional<WorkspaceFile> file = workspace().findFile(definitionId)
                .filter(f -> f.kind().definition());
        if (file.isEmpty()) {
            cache.put(definitionId, null);
            return Optional.empty();
        }
        try {
            DbcDatabase db = loadDbcDatabase(file.get().path());
            cache.put(definitionId, db);
            return Optional.of(db);
        } catch (DbcParseException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Failed to load definition for plan execution: " + definitionId
                            + " — " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            cache.put(definitionId, null);
            return Optional.empty();
        }
    }


    public void cancelProcessing() {
        String runId = currentProcessingRunId.get();
        ProcessingSelectionWorkspaceModel model = processingSelectionModel;
        if (ProcessingRunStatuses.isProtectedTerminal(finalizedProcessingOutcome.get())
                || latestPersistedProcessingRun()
                .map(run -> ProcessingRunStatuses.isProtectedTerminal(run.status()))
                .orElse(false)) {
            LOG.log(System.Logger.Level.INFO, () -> "Late cancel ignored runId="
                    + (runId == null ? "-" : runId)
                    + " status=" + latestPersistedProcessingRun().map(ProcessingRunMetadata::status).orElse("-"));
            finalizeOrphanRunningRuns(null, "Cancelled");
            requestIsolatedCancel();
            return;
        }
        ProjectProcessingPlan plan = model.executionPlanSnapshot();
        Instant started = latestPersistedProcessingRun().map(ProcessingRunMetadata::startedAt).orElse(Instant.now());
        CoalescingUiDispatcher dispatcher = new CoalescingUiDispatcher(PlatformUi.NOOP);
        if (adoptDurableSuccessfulCompletion(runId, plan, false, started, model, dispatcher, () -> { })) {
            requestIsolatedCancel();
            return;
        }
        session.processingGateway().cancel();
        SimpleCancellationToken planToken = planExecutionToken.get();
        if (planToken != null) {
            planToken.cancel();
        }
        boolean executingMode = model.mode() == ProcessingPageMode.EXECUTING;
        if (executingMode) {
            LOG.log(System.Logger.Level.INFO, () -> "Cancel requested runId="
                    + (runId == null ? "-" : runId));
        }
        if (runId != null && executingMode) {
            finalizeCancelledRun(runId, plan, false, started, model, dispatcher, () -> { }, "Cancelled");
        } else {
            model.cancelActiveExecution();
            if (runId != null && !runId.isBlank() && !"-".equals(runId)
                    && latestPersistedProcessingRun()
                    .map(run -> ProcessingRunStatuses.isRunning(run.status()))
                    .orElse(false)) {
                persistProcessingRun(runId, ProcessingRunStatuses.CANCELLED, false,
                        plan == null ? 0 : plan.jobs().size(), started, Instant.now(), "Cancelled");
            }
        }
        finalizeOrphanRunningRuns(null, "Cancelled");
        requestIsolatedCancel();
    }

    /**
     * Explore Signals: reopen VALID committed artifacts. Never starts processing, never
     * deletes artifacts, never creates jobs.
     */
    public boolean openCompletedSignalsForExploration() {
        if (isPlanExecutionActive()) {
            return false;
        }
        ProjectProcessingPlan plan = processingSelectionModel.reviewedPlan();
        if (plan == null || plan.isEmpty()) {
            plan = processingSelectionModel.executionPlanSnapshot();
        }
        if (plan != null && !plan.isEmpty()) {
            seedReusableArtifactsFromMetadata(plan);
        }
        republishSignalsFromRegistry();
        return signalsModel.catalog() != null && !signalsModel.catalog().entries().isEmpty();
    }

    /**
     * Resolves a queryable store for View/Explore without binding a closed staging handle.
     */
    public com.stellar.platform.processing.catalog.SignalSeriesStore resolveExplorationStore(
            IndependentJobResult result
    ) {
        if (result == null) {
            return null;
        }
        Optional<IndependentJobResult> published = processingResultRegistry.get(result.jobKey());
        com.stellar.platform.processing.catalog.SignalSeriesStore fromRegistry =
                usableExplorationStore(published.flatMap(IndependentJobResult::catalogHandle).orElse(null));
        if (fromRegistry != null) {
            return fromRegistry;
        }
        com.stellar.platform.processing.catalog.SignalSeriesStore fromResult =
                usableExplorationStore(result.catalogHandle().orElse(null));
        if (fromResult != null) {
            return fromResult;
        }
        return reopenDurableJobStore(result);
    }

    /** Applies a pending channel selection from Change Channels, then reprocesses. */
    public void applyChannelSelectionAndReprocess(
            Collection<Integer> channelIds,
            Consumer<Runnable> uiUpdater,
            Runnable onUiRefresh
    ) {
        syncChannelSelection();
        channelSelectionModel.replaceSelection(channelIds);
        startLocalProcessing(uiUpdater, onUiRefresh, ProcessingStartOptions.reprocessFromSignals());
    }

    public ProjectProcessingContext.CompactProgressSnapshot compactProgress() {
        return projectProcessingContext.compactProgress(processingModel, channelSelectionModel);
    }

    public boolean consumeAutoNavigateToSignals() {
        return projectProcessingContext.consumeAutoNavigateToSignals();
    }

    public void searchSignalCatalogAsync(String query, Consumer<List<SignalCatalogEntry>> uiCallback) {
        signalsModel.setSearchQuery(query);
        signalsModel.searchAsync(uiCallback);
    }

    public void loadSelectedSignalSamplesAsync(
            List<SignalCatalogKey> keys,
            Consumer<List<SignalSamplePoint>> uiCallback
    ) {
        signalsModel.loadSamplesAsync(keys, uiCallback);
    }

    public void loadSelectedSignalSamplesPageAsync(
            List<SignalCatalogKey> keys,
            int pageOffset,
            Consumer<SignalSamplePageSnapshot> uiCallback
    ) {
        signalsModel.loadSamplesPageAsync(keys, pageOffset, uiCallback);
    }

    public void loadWorkspaceAlignedAsync(Consumer<AlignedWorkspaceSnapshot> uiCallback) {
        signalsModel.loadWorkspaceAlignedAsync(uiCallback);
    }

    /** @deprecated Prefer {@link #searchSignalCatalogAsync}; retained for compatibility. */
    public void filterSignalsAsync(String signal, String message, String canId, Consumer<?> uiCallback) {
        String q = (signal == null ? "" : signal) + " "
                + (message == null ? "" : message) + " "
                + (canId == null ? "" : canId);
        searchSignalCatalogAsync(q.trim(), entries -> {
            if (uiCallback != null) {
                uiCallback.accept(null);
            }
        });
    }

    public Optional<Path> activeProjectRoot() {
        return Optional.ofNullable(activeProjectRoot);
    }

    public Optional<Path> activeDatabasePath() {
        if (metadataStore instanceof SqliteProjectMetadataStore sqlite) {
            return Optional.of(sqlite.databasePath());
        }
        return Optional.empty();
    }

    /**
     * Persists Analysis Storage and opens the canonical project database there.
     * A failed probe leaves the current database connection in place.
     */
    public synchronized AnalysisStorageValidator.Result saveAnalysisStorage(Path candidate) {
        if (candidate == null || candidate.toString().isBlank()) {
            return AnalysisStorageValidator.notConfigured();
        }
        AnalysisStorageValidator.Result probed = analysisStorageService.testAccess(candidate);
        if (!probed.valid()) {
            return probed;
        }
        Path previousRoot = AnalysisStorageRoots.userSelectedRoot().orElse(null);
        AnalysisStorageRoots.setUserSelectedRoot(probed.userSelectedRoot());
        SqliteProjectMetadataStore opened = null;
        boolean transferred = false;
        try {
            Path db = CentralSqlitePaths.ensureLayout();
            boolean alreadyBound = metadataStore instanceof SqliteProjectMetadataStore sqlite
                    && sqlite.databasePath().equals(db);
            if (!alreadyBound) {
                opened = new SqliteProjectMetadataStore(db);
            }
            AnalysisStorageValidator.Result saved = analysisStorageService.save(probed.userSelectedRoot());
            if (!saved.valid()) {
                closeOpenedProbe(opened);
                restoreAnalysisRoot(previousRoot);
                return saved;
            }
            if (!injectedPersistentStore && opened != null) {
                swapCentralMetadataStore(opened);
                transferred = true;
            } else {
                closeOpenedProbe(opened);
            }
            com.stellar.platform.desktop.DesktopLogging.bindConfiguredStorage();
            return saved;
        } catch (RuntimeException ex) {
            if (!transferred) {
                closeOpenedProbe(opened);
            }
            restoreAnalysisRoot(previousRoot);
            LOG.log(System.Logger.Level.WARNING,
                    "Analysis Storage database was not switched: " + ex.getMessage());
            return new AnalysisStorageValidator.Result(
                    AnalysisStorageValidator.Status.INVALID,
                    candidate.toAbsolutePath().normalize(),
                    null,
                    false,
                    false,
                    null,
                    "Cannot open the project database at the selected location.");
        }
    }

    private void restoreAnalysisRoot(Path previousRoot) {
        if (previousRoot == null) {
            AnalysisStorageRoots.clear();
        } else {
            AnalysisStorageRoots.setUserSelectedRoot(previousRoot);
        }
    }

    private void closeOpenedProbe(SqliteProjectMetadataStore opened) {
        if (opened == null) {
            return;
        }
        try {
            opened.close();
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Closing uncommitted metadata database failed: " + ex.getMessage());
        }
    }

    private void swapCentralMetadataStore(SqliteProjectMetadataStore next) {
        ProjectMetadataStore previous = metadataStore;
        metadataStore = next;
        projectDeletionService = new ProjectDeletionService(next, new ManagedProjectArtifactStore());
        closeStoreQuietly(previous);
    }

    private record LoadOutcome(SavedProjectSnapshot snapshot, boolean copiedFromLegacy) {
    }

    private LoadOutcome loadProjectSnapshot(String projectId) {
        if (!injectedPersistentStore) {
            ensureCentralMetadataStore();
        }
        Optional<SavedProjectSnapshot> existing = metadataStore.loadProject(projectId);
        if (existing.isPresent()) {
            bindActiveProjectRoot(projectId);
            return new LoadOutcome(existing.get(), false);
        }
        if (injectedPersistentStore || !(metadataStore instanceof SqliteProjectMetadataStore sqlite)) {
            return new LoadOutcome(null, false);
        }
        Optional<ProjectStoragePaths> local = ProjectWorkspaceCatalog.locateManagedProject(projectId);
        if (local.isPresent() && local.get().databaseExists()) {
            importLegacyQuietly(sqlite, local.get().databasePath());
        }
        for (Path candidate : SqliteProjectMetadataStore.legacyDatabaseCandidates()) {
            if (Files.isRegularFile(candidate) && !candidate.equals(sqlite.databasePath())) {
                importLegacyQuietly(sqlite, candidate);
            }
        }
        Optional<SavedProjectSnapshot> imported = metadataStore.loadProject(projectId);
        if (imported.isPresent()) {
            bindActiveProjectRoot(projectId);
            return new LoadOutcome(imported.get(), true);
        }
        return new LoadOutcome(null, false);
    }

    private ProjectDashboardSummary locateDashboardSummary(ProjectDashboardSummary row) {
        Path root = ProjectRootIndex.resolve(row.projectId())
                .or(() -> ProjectWorkspaceCatalog.locateManagedProject(row.projectId())
                        .map(ProjectStoragePaths::projectRoot))
                .orElseGet(() -> AnalysisStorageRoots.projectArtifactsRoot(row.projectId()));
        return row.locatedAt(root, row.availability());
    }

    private void bindActiveProjectRoot(String projectId) {
        Optional<Path> indexed = ProjectRootIndex.resolve(projectId);
        if (indexed.isPresent()) {
            activeProjectRoot = indexed.get();
            return;
        }
        Path managed = AnalysisStorageRoots.projectArtifactsRoot(projectId);
        activeProjectRoot = managed;
        ProjectRootIndex.register(projectId, managed);
    }

    private synchronized void ensureCentralMetadataStore() {
        if (injectedPersistentStore) {
            return;
        }
        if (!AnalysisStorageRoots.isConfigured()) {
            return;
        }
        Path db = CentralSqlitePaths.ensureLayout();
        if (!(metadataStore instanceof SqliteProjectMetadataStore sqlite) || !sqlite.databasePath().equals(db)) {
            swapCentralMetadataStore(new SqliteProjectMetadataStore(db));
        }
        importDiscoveredLegacyProjectDatabases((SqliteProjectMetadataStore) metadataStore);
    }

    private void importDiscoveredLegacyProjectDatabases(SqliteProjectMetadataStore store) {
        AnalysisStorageRoots.configuredProjectsDirectory().ifPresent(dir -> importLegacyUnder(store, dir));
        importLegacyUnder(store, StellarAiPaths.homeRoot().resolve("projects"));
        for (Path candidate : SqliteProjectMetadataStore.legacyDatabaseCandidates()) {
            if (Files.isRegularFile(candidate) && !candidate.equals(store.databasePath())) {
                importLegacyQuietly(store, candidate);
            }
        }
        for (var indexed : List.copyOf(ProjectRootIndex.entries().entrySet())) {
            Path localDb = ProjectStoragePaths.of(indexed.getValue()).databasePath();
            if (Files.isRegularFile(localDb) && !localDb.equals(store.databasePath())) {
                importLegacyQuietly(store, localDb);
            } else if (store.loadProject(indexed.getKey()).isEmpty()) {
                ProjectRootIndex.unregister(indexed.getKey());
            }
        }
    }

    private void importLegacyUnder(SqliteProjectMetadataStore store, Path managed) {
        if (managed == null || !Files.isDirectory(managed)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(managed, Files::isDirectory)) {
            for (Path projectRoot : stream) {
                Path localDb = ProjectStoragePaths.of(projectRoot).databasePath();
                if (Files.isRegularFile(localDb)) {
                    importLegacyQuietly(store, localDb);
                }
            }
        } catch (IOException ex) {
            LOG.log(System.Logger.Level.WARNING, "Cannot scan legacy project databases: " + ex.getMessage());
        }
    }

    private void importLegacyQuietly(SqliteProjectMetadataStore store, Path legacyDb) {
        try {
            store.importLegacyDatabase(legacyDb);
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING,
                    "Cannot import leftover project database " + legacyDb + ": " + ex.getMessage());
        }
    }

    private void closeStoreQuietly(ProjectMetadataStore store) {
        if (store == null || store == NoOpProjectMetadataStore.INSTANCE || store == metadataStore) {
            return;
        }
        try {
            store.close();
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Metadata store close failed: " + ex.getMessage());
        }
    }

    private void persistProject() {
        try {
            persistProjectCritical();
            if (Boolean.TRUE.equals(durableNamedProject.get()) && !saveCoordinator.graphPersistPending()) {
                markClean();
            }
        } catch (DuplicateProjectNameException | IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            recordPersistenceFailure(ex);
        }
    }

    private void persistProjectCritical() {
        persistProjectCritical(false, lastSavedAt.get());
    }

    private void persistProjectCritical(boolean updateCheckpoint, Instant checkpointSavedAt) {
        Instant created = metadataProjectCreatedAt.get();
        String display = ProjectNameNormalizer.requireDisplayName(workspace().projectName());
        workspace().setProjectName(display);
        // Never fall back to live navigation for last_workspace — that would overwrite
        // the explicit Save checkpoint on exit/autosave.
        WorkspaceRoute resume = checkpointResumeRoute.get();
        String lastWorkspace = resume == null ? "" : resume.name();
        Instant savedAt = updateCheckpoint ? checkpointSavedAt : lastSavedAt.get();
        if (updateCheckpoint && checkpointSavedAt != null) {
            lastSavedAt.set(checkpointSavedAt);
        }
        metadataStore.upsertProject(new ProjectMetadata(
                metadataProjectId.get(), display, created, Instant.now(), lastWorkspace, savedAt));
        durableNamedProject.set(true);
    }

    private void persistAllFiles() {
        try {
            persistAllFilesCritical();
            if (Boolean.TRUE.equals(durableNamedProject.get()) && !saveCoordinator.graphPersistPending()) {
                markClean();
            } else if (!Boolean.TRUE.equals(durableNamedProject.get())) {
                markDirty();
            }
        } catch (DuplicateProjectNameException | IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            recordPersistenceFailure(ex);
        }
    }

    private void persistAllFilesCritical() {
        persistProjectCritical();
        String selectedMeasurement = workspace().selectedMeasurement().map(WorkspaceFile::id).orElse(null);
        String selectedDefinition = workspace().selectedDefinition().map(WorkspaceFile::id).orElse(null);
        for (WorkspaceFile file : workspace().allFiles()) {
            ProjectFileMetadata metadata = new ProjectFileMetadata(
                    metadataProjectId.get(),
                    file.id(),
                    file.path().toString(),
                    file.kind().name(),
                    file.sizeBytes(),
                    file.status().name(),
                    file.validationMessage(),
                    file.associatedDefinitionId(),
                    file.mf4FinalizationLabel(),
                    file.recoveryCandidate(),
                    Objects.equals(selectedMeasurement, file.id()),
                    Objects.equals(selectedDefinition, file.id())
            );
            metadataStore.upsertFile(metadata);
        }
    }

    /**
     * A report must read channel coverage after an in-flight inventory has committed it.
     * Matching that has not finished is left unavailable. No coverage count is invented.
     */
    private void awaitCommittedChannelMappings(
            com.stellar.platform.analysis.investigation.EngineeringInvestigation investigation
    ) {
        if (investigation == null) {
            return;
        }
        String projectId = metadataProjectId.get();
        if (projectId == null || !projectId.equals(investigation.projectId())) {
            return;
        }
        boolean idle = inventoryCoordinator.awaitIdle(60_000L);
        if (!idle) {
            LOG.log(System.Logger.Level.WARNING,
                    "Report coverage is being read while source inventory is still running");
        }
        flushPendingLogicalPersistence();
    }

    /**
     * Writes the mapping rows for one source that matching just finished computing.
     * Rows for other sources, and rows in another project, are left unchanged.
     */
    private void persistSourceChannelMappings(String projectId, ProjectSourceId sourceId) {
        if (projectId == null || sourceId == null || !projectId.equals(metadataProjectId.get())) {
            return;
        }
        List<SourceChannelMappingEntry> current = new ArrayList<>();
        for (SourceChannelMappingEntry entry : definitionMatchingModel.allMappings()) {
            if (entry != null && sourceId.equals(entry.sourceChannelKey().sourceId())) {
                current.add(entry);
            }
        }
        List<ChannelMappingMetadata> stored = metadataStore.loadProject(projectId)
                .map(com.stellar.platform.storage.metadata.SavedProjectSnapshot::channelMappings)
                .orElse(List.of());
        metadataWrite(() -> metadataStore.runInTransaction(() -> {
            if (!projectId.equals(metadataProjectId.get()) || workspace().findFile(sourceId.value()).isEmpty()) {
                return;
            }
            for (ChannelMappingMetadata row : stored) {
                if (row == null || !sourceId.value().equals(row.sourceId())) {
                    continue;
                }
                boolean kept = false;
                for (SourceChannelMappingEntry entry : current) {
                    if (entry.sourceChannelKey().channelId() == row.channelId()) {
                        kept = true;
                        break;
                    }
                }
                if (!kept) {
                    metadataStore.deleteChannelMapping(projectId, row.sourceId(), row.channelId());
                }
            }
            for (SourceChannelMappingEntry entry : current) {
                persistChannelMappingCritical(entry.sourceChannelKey());
            }
        }));
    }

    private void persistChannelMapping(SourceChannelKey key) {
        if (key == null) {
            return;
        }
        markDirty();
        boolean ok = persistWriteQuiet(() -> persistChannelMappingCritical(key));
        if (ok && Boolean.TRUE.equals(durableNamedProject.get()) && !saveCoordinator.graphPersistPending()) {
            markClean();
        }
    }

    private void persistChannelMappingCritical(SourceChannelKey key) {
        if (key == null) {
            return;
        }
        channelMapping(key).ifPresentOrElse(entry -> {
            ChannelMappingMetadata metadata = new ChannelMappingMetadata(
                    metadataProjectId.get(),
                    key.sourceId().value(),
                    key.channelId(),
                    entry.status().name(),
                    entry.recommendationKind().name(),
                    entry.recommendedDefinitionId().map(DefinitionSourceId::value).orElse(null),
                    entry.mappedDefinitionId().map(DefinitionSourceId::value).orElse(null),
                    entry.observedUniqueMessageCount(),
                    entry.matchedUniqueMessageCount(),
                    entry.coverageRatio()
            );
            metadataStore.upsertChannelMapping(metadata);
        }, () -> metadataStore.deleteChannelMapping(
                metadataProjectId.get(), key.sourceId().value(), key.channelId()));
    }

    /**
     * A started run that is no longer live must not stay RUNNING with finished_at null.
     * Replaced, cancelled, and recovered runs use the existing CANCELLED terminal status.
     */
    void finalizeOrphanRunningRuns(String keepRunId, String message) {
        Instant now = Instant.now();
        String reason = message == null || message.isBlank() ? "Processing interrupted" : message;
        for (ProcessingRunMetadata run : List.copyOf(persistedProcessingRuns.get())) {
            if (run == null || run.runId() == null || run.runId().isBlank() || "-".equals(run.runId())) {
                continue;
            }
            if (keepRunId != null && keepRunId.equals(run.runId())) {
                continue;
            }
            if (!ProcessingRunStatuses.isRunning(run.status())) {
                continue;
            }
            persistProcessingRun(
                    run.runId(),
                    ProcessingRunStatuses.CANCELLED,
                    run.forceReprocess(),
                    run.jobCount(),
                    run.startedAt(),
                    now,
                    reason);
        }
    }

    private void persistProcessingRun(
            String runId,
            String status,
            boolean forceReprocess,
            int jobCount,
            Instant startedAt,
            Instant finishedAt,
            String message
    ) {
        if (runId == null || runId.isBlank() || "-".equals(runId)) {
            LOG.log(System.Logger.Level.WARNING, "Refusing to persist processing run with invalid runId");
            return;
        }
        String projectId = metadataProjectId.get();
        if (projectId == null || projectId.isBlank()) {
            LOG.log(System.Logger.Level.WARNING, "Refusing to persist processing run without a project");
            return;
        }
        ProcessingRunMetadata run = new ProcessingRunMetadata(
                runId, projectId, status, forceReprocess, jobCount,
                startedAt, finishedAt, message);
        persistWriteQuiet(() -> metadataStore.upsertProcessingRun(run));
        // Keep the resident view of runs current even when the metadata write failed.
        recordResidentProcessingRun(run);
    }

    private void recordResidentProcessingRun(ProcessingRunMetadata run) {
        persistedProcessingRuns.updateAndGet(existing -> {
            List<ProcessingRunMetadata> next = new java.util.ArrayList<>(existing.size() + 1);
            next.add(run);
            for (ProcessingRunMetadata previous : existing) {
                if (!previous.runId().equals(run.runId())) {
                    next.add(previous);
                }
            }
            return List.copyOf(next);
        });
    }

    /**
     * Background metadata write. Failures become SAVE_FAILED and are never treated as Saved.
     * Recoverable writes do not crash the application.
     */
    private void metadataWrite(Runnable write) {
        persistWriteQuiet(write);
    }

    private void persistWrite(Runnable write) {
        try {
            write.run();
        } catch (DuplicateProjectNameException | IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            recordPersistenceFailure(ex);
            throw ex;
        }
    }

    private boolean persistWriteQuiet(Runnable write) {
        try {
            persistWrite(write);
            return true;
        } catch (DuplicateProjectNameException | IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void recordPersistenceFailure(RuntimeException ex) {
        LOG.log(System.Logger.Level.ERROR, "Metadata persistence failed: "
                + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
        saveCoordinator.markFailed(ex);
    }

    private static WorkspaceRoute parseLastWorkspace(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            WorkspaceRoute route = WorkspaceRoute.valueOf(stored.trim());
            if (route == WorkspaceRoute.SOURCES
                    || route == WorkspaceRoute.DECODE
                    || route == WorkspaceRoute.SIGNALS
                    || route == WorkspaceRoute.GRAPHS
                    || route == WorkspaceRoute.INVESTIGATIONS) {
                return route;
            }
            return null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    String metadataProjectIdForTest() { return metadataProjectId.get(); }

    ProjectMetadataStore metadataStoreForTest() {
        return metadataStore;
    }

    /** Test-only: publish a ledger run so Processing Complete can resolve source windows. */
    public void rememberPersistedRunForTest(ProcessingRunMetadata run) {
        recordResidentProcessingRun(run);
    }

    void persistProcessingRunForTest(String runId, String status, Instant startedAt, Instant finishedAt) {
        persistProcessingRun(runId, status, false, 0, startedAt, finishedAt, "");
    }

    public ProjectSaveCoordinator saveCoordinatorForTest() {
        return saveCoordinator;
    }

    public void forceOpenHydrationFailureForTest(RuntimeException error) {
        this.openHydrationFailureForTest = error;
    }

    public LocalWorkerBoundary localProcessingWorker() {
        return localProcessingWorker;
    }

    public boolean isolateProductionProcessing() {
        return isolateProductionProcessing;
    }

    boolean processingCoordinatorIsShutdown() {
        return processingExecutor.isShutdown();
    }

    long isolatedWorkerPid() {
        if (localProcessingWorker instanceof ProcessIsolatedLocalWorker isolated && isolated.isAlive()) {
            return isolated.workerPid();
        }
        return -1L;
    }

    Future<?> submitProcessingCoordinatorForTest(Runnable task) {
        return processingExecutor.submit(Objects.requireNonNull(task, "task"));
    }

    ProcessIsolatedLocalWorker ensureIsolatedWorkerForTest() {
        return ensureIsolatedWorker();
    }

    public com.stellar.platform.api.worker.WorkerHealthSnapshot workerHealth() {
        if (localProcessingWorker instanceof ProcessIsolatedLocalWorker isolated) {
            return isolated.health();
        }
        return com.stellar.platform.api.worker.WorkerHealthSnapshot.dead();
    }

    public void shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }
        // Application.stop runs on the JavaFX thread. Joining inventory or decode there
        // freezes the last frame for the full timeout. The closer is not a daemon so
        // SQLite still closes before the process exits.
        if (com.stellar.platform.desktop.ui.FxExecutorRelease.onApplicationThread()) {
            Thread closer = new Thread(this::shutdownBlocking, "stellar-shutdown");
            closer.setDaemon(false);
            closer.start();
            return;
        }
        shutdownBlocking();
    }

    private void shutdownBlocking() {
        saveCoordinator.close();
        cancelProcessing();
        cancelAllInventories();
        localProcessingWorker.shutdown();
        processingExecutor.shutdown();
        inventoryExecutor.shutdownNow();
        metadataReadExecutor.shutdownNow();
        projectSwitchExecutor.shutdownNow();
        signalsModel.shutdown();
        awaitTermination(processingExecutor, "stellar-local-processing");
        awaitTermination(inventoryExecutor, "stellar-source-inventory");
        awaitTermination(metadataReadExecutor, "stellar-metadata-read");
        awaitTermination(projectSwitchExecutor, "stellar-project-switch");
        try { metadataStore.close(); }
        catch (RuntimeException ex) { LOG.log(System.Logger.Level.WARNING, "Metadata store close failed: " + ex.getMessage()); }
        if (configDefaultsStore != null) {
            try { configDefaultsStore.close(); }
            catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING, "Configuration defaults close failed: " + ex.getMessage());
            }
            configDefaultsStore = null;
        }
    }

    private void awaitTermination(ExecutorService executor, String name) {
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                LOG.log(System.Logger.Level.WARNING, name + " did not terminate within 2s");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** Identity UI updater for headless tests. */
    public static final class PlatformUi {
        public static final Consumer<Runnable> NOOP = Runnable::run;

        private PlatformUi() {
        }
    }

    private static int previewCapacity() {
        String prop = System.getProperty("stellar.desktop.signalPreviewCapacity");
        if (prop == null || prop.isBlank()) {
            return SignalPreviewBuffer.DEFAULT_CAPACITY;
        }
        try {
            return Math.max(1, Integer.parseInt(prop.trim()));
        } catch (NumberFormatException e) {
            return SignalPreviewBuffer.DEFAULT_CAPACITY;
        }
    }
}
