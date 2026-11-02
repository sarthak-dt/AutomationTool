package com.stellar.platform.desktop.session;

import com.stellar.platform.api.StellarPlatformClient;
import com.stellar.platform.api.StellarPlatformClients;
import com.stellar.platform.api.dto.CreateProjectRequest;
import com.stellar.platform.api.http.HttpStellarPlatformClient;
import com.stellar.platform.api.local.LocalStellarPlatformClient;
import com.stellar.platform.desktop.processing.LocalProcessingGateway;
import com.stellar.platform.desktop.processing.ProcessingGateway;
import com.stellar.platform.desktop.processing.RemoteProcessingGateway;
import com.stellar.platform.desktop.project.ProjectWorkspace;
import com.stellar.platform.desktop.validation.InputValidationService;
import com.stellar.platform.processing.ExecutionMode;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Application-scoped services for the desktop vertical slice.
 * LOCAL keeps in-process decode; REMOTE routes through {@link StellarPlatformClient} HTTP adapters.
 */
public final class DesktopSession {

    private final ParserRegistry parserRegistry;
    private final ProjectWorkspace workspace;
    private final InputValidationService validationService;
    private final ExecutionMode executionMode;
    private final MeasurementDecodePipeline decodePipeline;
    private final StellarPlatformClient platformClient;
    private final ProcessingGateway processingGateway;
    private final AtomicReference<String> remoteProjectId = new AtomicReference<>();
    private final AtomicReference<Supplier<String>> preferredProjectId = new AtomicReference<>(() -> "");

    public DesktopSession() {
        this(ParserRegistry.load(), StellarPlatformClients.resolveMode(), StellarPlatformClients.resolveBaseUrl());
    }

    public DesktopSession(ParserRegistry parserRegistry) {
        this(parserRegistry, StellarPlatformClients.resolveMode(), StellarPlatformClients.resolveBaseUrl());
    }

    public DesktopSession(ParserRegistry parserRegistry, ExecutionMode executionMode, String apiBaseUrl) {
        this.parserRegistry = Objects.requireNonNull(parserRegistry, "parserRegistry");
        this.executionMode = Objects.requireNonNullElse(executionMode, ExecutionMode.LOCAL);
        this.workspace = ProjectWorkspace.createNew("Untitled Project");
        this.validationService = new InputValidationService(parserRegistry);
        this.decodePipeline = new MeasurementDecodePipeline(parserRegistry);
        Path localDataRoot = localApiDataRoot();
        this.platformClient = StellarPlatformClients.create(this.executionMode, localDataRoot, apiBaseUrl);
        if (this.executionMode == ExecutionMode.REMOTE) {
            this.processingGateway = new RemoteProcessingGateway(platformClient, this::ensureRemoteProjectId);
        } else {
            this.processingGateway = new LocalProcessingGateway(decodePipeline);
        }
    }

    /**
     * Bind the durable desktop project id so REMOTE create/ensure uses the same logical id.
     */
    public void bindPreferredProjectId(Supplier<String> preferredProjectId) {
        this.preferredProjectId.set(preferredProjectId == null ? () -> "" : preferredProjectId);
    }

    /**
     * Scratch for the LOCAL API client. After Analysis Storage is configured and the
     * controlled directory is a real directory this is
     * {@code StellarAIAnalysis/temp/desktop-api}. An invalid configured root must not
     * receive session or asset directories; project creation still reports
     * {@code AnalysisStorageRequiredException} from its own storage gate.
     */
    public static Path localApiDataRoot() {
        if (AnalysisStorageRoots.isConfigured()) {
            var controlled = AnalysisStorageRoots.controlledRoot();
            if (controlled.isPresent() && Files.isDirectory(controlled.get())) {
                return AnalysisStorageRoots.tempRoot().resolve("desktop-api");
            }
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "stellar-desktop-api");
    }

    public ParserRegistry parserRegistry() {
        return parserRegistry;
    }

    public ProjectWorkspace workspace() {
        return workspace;
    }

    public InputValidationService validationService() {
        return validationService;
    }

    public ExecutionMode executionMode() {
        return executionMode;
    }

    public MeasurementDecodePipeline decodePipeline() {
        return decodePipeline;
    }

    public StellarPlatformClient platformClient() {
        return platformClient;
    }

    public ProcessingGateway processingGateway() {
        return processingGateway;
    }

    /** Compatibility accessor for call sites typed to the LOCAL gateway. */
    public LocalProcessingGateway localProcessingGateway() {
        if (processingGateway instanceof LocalProcessingGateway local) {
            return local;
        }
        throw new IllegalStateException("LOCAL processing gateway is not active (mode=" + executionMode + ")");
    }

    public String ensureRemoteProjectId() {
        if (executionMode != ExecutionMode.REMOTE) {
            return remoteProjectId.get();
        }
        String preferred = preferredProjectId.get().get();
        if (preferred != null) {
            preferred = preferred.trim();
        } else {
            preferred = "";
        }
        String existing = remoteProjectId.get();
        if (existing != null && !existing.isBlank()) {
            if (preferred.isBlank() || preferred.equals(existing)) {
                return existing;
            }
            // Durable desktop project id changed — drop stale remote binding.
            remoteProjectId.set(null);
        }
        synchronized (remoteProjectId) {
            String again = remoteProjectId.get();
            if (again != null && !again.isBlank()
                    && (preferred.isBlank() || preferred.equals(again))) {
                return again;
            }
            String created = platformClient.projects()
                    .createProject(new CreateProjectRequest(
                            workspace.projectName(),
                            "",
                            preferred))
                    .projectId();
            remoteProjectId.set(created);
            return created;
        }
    }

    public void newProject(String name) {
        workspace.reset(name);
        remoteProjectId.set(null);
    }

    public boolean isRemoteHttpClient() {
        return platformClient instanceof HttpStellarPlatformClient;
    }

    public boolean isLocalApiClient() {
        return platformClient instanceof LocalStellarPlatformClient;
    }
}
