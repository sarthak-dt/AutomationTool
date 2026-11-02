package com.stellar.platform.server.decoder;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParseException;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.DetectedMeasurementFormat;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.ProcessingDiagnostic;
import com.stellar.platform.processing.ProcessingOutcome;
import com.stellar.platform.processing.ProcessingResult;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single validation workflow. Project reads never reprocess. File reads decode once into an
 * isolated session and page that snapshot.
 */
@Service
@ConditionalOnProperty(name = "stellar.decoder.validation-api", havingValue = "true")
public class DecoderValidationService {

    private static final Logger LOG = LoggerFactory.getLogger(DecoderValidationService.class);
    private static final String FILE_SOURCE_ID = "file";
    static final String RESPONSE_SCHEMA = "2.0";
    private static final List<String> VALUE_LIMITATIONS = List.of(
            "timestamps are stored nanoseconds divided by 1e9, with no origin subtracted. "
                    + "timestampsNs is the exact signed nanosecond value. "
                    + "A JSON number cannot preserve every nanosecond once the magnitude exceeds 2^53.",
            "samples are stored physical values. Non-finite values are objects with representation non-finite. "
                    + "rawSamples are JSON numbers inside +/-9007199254740991 and decimal strings outside that range. "
                    + "A null raw sample was not stored and is not zero.",
            "textValues are DBC labels and do not replace samples. "
                    + "Missing, invalid, and inactive samples stay in place with their original validity.");

    private final DecoderValidationProperties properties;
    private final ParserRegistry parserRegistry;
    private final Environment environment;
    private final DecoderSnapshots snapshots = new DecoderSnapshots();
    private final SecureRandom random = new SecureRandom();
    private final byte[] cursorKey = new byte[32];
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final Semaphore decodePermits;
    private final ScheduledExecutorService cleaner;
    private Path sessionDirectory;

    public DecoderValidationService(
            DecoderValidationProperties properties,
            ParserRegistry parserRegistry,
            Environment environment
    ) {
        this.properties = properties;
        this.parserRegistry = parserRegistry;
        this.environment = environment;
        random.nextBytes(cursorKey);
        int permits = Math.max(0, properties.getMaxConcurrentDecodes());
        this.decodePermits = new Semaphore(permits, true);
        this.cleaner = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "decoder-validation-cleanup");
            thread.setDaemon(true);
            return thread;
        });
    }

    @jakarta.annotation.PostConstruct
    void start() {
        if (properties.getMaxPageSize() < 1 || properties.getDefaultPageSize() < 1) {
            throw new IllegalStateException("Decoder page size must be positive.");
        }
        if (properties.getDefaultPageSize() > properties.getMaxPageSize()) {
            properties.setDefaultPageSize(properties.getMaxPageSize());
        }
        if (properties.getCursorTtlSeconds() < 1) {
            throw new IllegalStateException("Decoder cursor TTL must be at least one second.");
        }
        String address = environment.getProperty("server.address", "0.0.0.0");
        if (!isLoopback(address)) {
            throw new IllegalStateException(
                    "Decoder validation API refuses to start when server.address is not a loopback address.");
        }
        sessionDirectory = resolveSessionDirectory();
        try {
            Files.createDirectories(sessionDirectory);
        } catch (IOException ex) {
            throw new IllegalStateException("Decoder session directory is not available.");
        }
        recoverSessions(true);
        int interval = Math.max(1, properties.getCleanupIntervalSeconds());
        cleaner.scheduleWithFixedDelay(() -> {
            try {
                snapshots.expireDue(Instant.now());
                recoverSessions(false);
            } catch (RuntimeException ex) {
                LOG.info("Decoder session recovery skipped");
            }
        }, interval, interval, TimeUnit.SECONDS);
        LOG.info("Decoder validation API ready on {}", address);
    }

    @PreDestroy
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        cleaner.shutdownNow();
        snapshots.closeAll();
    }

    /** Test hook. Expires one cursor without waiting for the TTL sweep. */
    public void expireCursorForTests(String cursor) {
        ParsedCursor parsed = parseCursor(cursor);
        snapshots.expire(parsed.id());
    }

    public Map<String, Object> validate(Map<String, Object> request) {
        if (shutdown.get()) {
            throw new DecoderValidationException(
                    "INTERNAL_ERROR",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "The decoder validation API is shut down.");
        }
        Request input = Request.parse(request);
        if (input.cursor != null) {
            return continueCursor(input);
        }
        if (input.hasProject == input.hasFile) {
            throw new DecoderValidationException(
                    "INVALID_REQUEST",
                    HttpStatus.BAD_REQUEST,
                    "Exactly one of projectName or filePath is required.");
        }
        if (input.hasFile) {
            return decodeFile(input);
        }
        return readProject(input);
    }

    private Map<String, Object> continueCursor(Request input) {
        if (input.hasProject || input.hasFile || input.signal != null || input.hasSignalNames()
                || input.dbcPath != null || input.includeIncomplete) {
            throw new DecoderValidationException(
                    "INVALID_REQUEST",
                    HttpStatus.BAD_REQUEST,
                    "A cursor request cannot include a new source or signal.");
        }
        ParsedCursor parsed = parseCursor(input.cursor);
        int pageSize = input.pageSize == null ? properties.getDefaultPageSize() : input.pageSize;
        rejectPageSize(pageSize);
        DecoderSnapshots.Snapshot snapshot = snapshots.require(parsed.id(), Instant.now());
        Map<String, Object> page;
        synchronized (snapshot) {
            page = snapshot.page(parsed.signalOrdinal(), parsed.sampleOffset(), pageSize);
        }
        Map<String, Object> body = envelope(snapshot, page, pageSize);
        if (Boolean.TRUE.equals(page.get("paginationComplete"))) {
            snapshots.expire(snapshot.id);
            body.put("nextCursor", null);
            body.put("cursorExpiresAt", null);
        }
        return body;
    }

    private Map<String, Object> readProject(Request input) {
        Path database = metadataDatabase();
        int pageSize = input.pageSize == null ? properties.getDefaultPageSize() : input.pageSize;
        rejectPageSize(pageSize);
        ReadOnlyProjectCatalog.ProjectRef project;
        List<ReadOnlyProjectCatalog.SourceRow> sources;
        try (ReadOnlyProjectCatalog catalog = ReadOnlyProjectCatalog.open(database, properties.allowedRootPaths())) {
            ReadOnlyProjectCatalog.ProjectMatch match = catalog.findProjects(input.projectName);
            if (match.projects().isEmpty()) {
                throw new DecoderValidationException(
                        "PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND, "No saved project matches that name.");
            }
            if (match.projects().size() > 1) {
                throw new DecoderValidationException(
                        "PROJECT_AMBIGUOUS",
                        HttpStatus.CONFLICT,
                        "More than one saved project matches that name.",
                        Map.of("candidates", match.projects().stream()
                                .map(item -> Map.<String, Object>of(
                                        "projectId", item.projectId(),
                                        "projectName", item.projectName()))
                                .toList()));
            }
            project = match.projects().getFirst();
            sources = catalog.sourcesFor(project.projectId());
        }
        if (sources.stream().anyMatch(source -> "AMBIGUOUS".equals(source.completeness()))) {
            throw new DecoderValidationException(
                    "SOURCE_AMBIGUOUS",
                    HttpStatus.CONFLICT,
                    "A measurement has more than one completed signal store.",
                    Map.of("sources", sourceDetails(sources)));
        }
        boolean anyIncomplete = sources.stream().anyMatch(source -> !source.completed());
        boolean anyCompleted = sources.stream().anyMatch(ReadOnlyProjectCatalog.SourceRow::completed);
        if (sources.isEmpty() || !anyCompleted || (anyIncomplete && !input.includeIncomplete)) {
            throw new DecoderValidationException(
                    "PROJECT_NOT_PROCESSED",
                    HttpStatus.CONFLICT,
                    "The project has measurements without completed decoded results.",
                    Map.of("projectId", project.projectId(), "projectName", project.projectName(),
                            "sources", sourceDetails(sources)));
        }
        List<DecoderSnapshots.OpenedStore> opened = new ArrayList<>();
        try {
            List<DecoderSnapshots.OpenedSource> readable = new ArrayList<>();
            List<ReadOnlyProjectCatalog.SourceRow> effective = new ArrayList<>();
            for (ReadOnlyProjectCatalog.SourceRow source : sources) {
                if (!source.completed()) {
                    effective.add(source);
                    continue;
                }
                try {
                    GraphSignalSessionStore store = GraphSignalSessionStore.openExisting(source.store());
                    DecoderSnapshots.OpenedStore handle = new DecoderSnapshots.OpenedStore(store, source.store(), false);
                    opened.add(handle);
                    readable.add(new DecoderSnapshots.OpenedSource(source.sourceId(), handle));
                    effective.add(source);
                } catch (RuntimeException ex) {
                    LOG.info("Stored results could not be opened for source {}", source.sourceId());
                    effective.add(new ReadOnlyProjectCatalog.SourceRow(
                            source.sourceId(), source.fileName(), "INCOMPLETE", "STORE_UNREADABLE",
                            null, source.sourceFingerprint(), source.dbcFingerprint(), source.decoderVersion(),
                            source.measurementPath()));
                }
            }
            boolean stillIncomplete = effective.stream().anyMatch(source -> !source.completed());
            if (readable.isEmpty() || (stillIncomplete && !input.includeIncomplete)) {
                throw new DecoderValidationException(
                        "PROJECT_NOT_PROCESSED",
                        HttpStatus.CONFLICT,
                        "The project has measurements without completed decoded results.",
                        Map.of("projectId", project.projectId(), "projectName", project.projectName(),
                                "sources", sourceDetails(effective)));
            }
            String status = stillIncomplete ? "INCOMPLETE" : "COMPLETED";
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("projectId", project.projectId());
            identity.put("projectName", project.projectName());
            return publish(
                    status,
                    "PROJECT",
                    "STORED",
                    Map.of("project", identity),
                    sourceDetails(effective),
                    stillIncomplete
                            ? List.of("Incomplete measurements were omitted. status is INCOMPLETE.")
                            : List.of(),
                    decoderVersion(effective),
                    opened,
                    readable,
                    recordingClocks(effective),
                    input,
                    pageSize);
        } catch (RuntimeException ex) {
            for (DecoderSnapshots.OpenedStore store : opened) {
                store.close();
            }
            throw ex;
        }
    }

    private Map<String, Object> decodeFile(Request input) {
        int pageSize = input.pageSize == null ? properties.getDefaultPageSize() : input.pageSize;
        rejectPageSize(pageSize);
        if (input.includeIncomplete) {
            throw new DecoderValidationException(
                    "INVALID_REQUEST",
                    HttpStatus.BAD_REQUEST,
                    "includeIncomplete applies only to an existing project.");
        }
        Path measurement = requireAllowedFile(input.filePath, "measurement");
        DetectedMeasurementFormat detected = parserRegistry.detect(measurement);
        if (!detected.recognized() || !detected.parserSupported() || parserRegistry.findReader(measurement).isEmpty()) {
            throw new DecoderValidationException(
                    "UNSUPPORTED_FORMAT",
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "The measurement format is not supported by the Java decoder.");
        }
        String format = detected.routingFormatKey().toLowerCase(Locale.ROOT);
        boolean dbcRequired = !"mf4".equals(format) && !"mdf".equals(format) && !"text".equals(format);
        Path dbc = null;
        DbcDatabase database;
        if (input.dbcPath != null) {
            dbc = requireAllowedFile(input.dbcPath, "dbc");
            database = parseDbc(dbc);
        } else if (dbcRequired) {
            throw new DecoderValidationException(
                    "DBC_REQUIRED",
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "This measurement format requires a DBC. No engineering values were invented.");
        } else {
            database = new DbcDatabase("", List.of(), List.of(), List.of());
        }
        if (!decodePermits.tryAcquire()) {
            throw new DecoderValidationException(
                    "RESOURCE_LIMIT",
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many decoding requests are already running.");
        }
        String sessionId = randomId();
        Path root = sessionDirectory.resolve(sessionId);
        GraphSignalSessionStore store = null;
        boolean keep = false;
        try {
            awaitDecodeDelay();
            if (shutdown.get()) {
                throw new DecoderValidationException(
                        "INTERNAL_ERROR",
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "The decoder validation API is shut down.");
            }
            String measurementLabel = measurement.getFileName().toString();
            String definitionLabel = dbc == null ? "" : dbc.getFileName().toString();
            store = GraphSignalSessionStore.createPersistent(root, measurementLabel, definitionLabel);
            markSession(root);
            MeasurementDecodePipeline pipeline = new MeasurementDecodePipeline(parserRegistry);
            AtomicBoolean cancel = shutdown;
            ProcessingResult result = pipeline.process(
                    measurement,
                    database,
                    new ReaderContext(512, () -> cancel.get() || Thread.currentThread().isInterrupted()),
                    store);
            store.finishWriteSession();
            if (result.cancelled() || shutdown.get()) {
                throw new DecoderValidationException(
                        "DECODING_FAILED",
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Decoding was cancelled before it completed.");
            }
            ProcessingOutcome outcome = result.outcome();
            if (outcome != ProcessingOutcome.COMPLETED && outcome != ProcessingOutcome.COMPLETED_WITH_WARNINGS) {
                throw new DecoderValidationException(
                        "DECODING_FAILED",
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Decoding did not complete.",
                        Map.of("outcome", outcome.name()));
            }
            if (GraphSignalSessionStore.hasIncompleteFlag(root)) {
                throw new DecoderValidationException(
                        "DECODING_FAILED",
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Decoding left an incomplete result and no samples were returned.");
            }
            long samples = store.losslessStore().totalSampleCount();
            if (samples > properties.getMaxDecodedSamples() || store.losslessStore().keys().size() > properties.getMaxSignals()) {
                throw new DecoderValidationException(
                        "RESOURCE_LIMIT",
                        HttpStatus.TOO_MANY_REQUESTS,
                        "The decoded result exceeds the configured sample or signal limit.");
            }
            List<String> diagnostics = diagnostics(result);
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("sourceId", FILE_SOURCE_ID);
            source.put("fileName", measurementLabel);
            source.put("format", format);
            source.put("completeness", "COMPLETED");
            source.put("reason", "");
            source.put("sourceFingerprint", fingerprint(measurement));
            source.put("dbcFingerprint", dbc == null ? "" : fingerprint(dbc));
            source.put("signalCount", store.losslessStore().keys().size());
            source.put("sampleCount", samples);
            DecoderSnapshots.OpenedStore opened = new DecoderSnapshots.OpenedStore(store, root, true);
            keep = true;
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("fileName", measurementLabel);
            identity.put("format", format);
            return publish(
                    "COMPLETED",
                    "FILE",
                    "FRESH_DECODE",
                    Map.of("file", identity),
                    List.of(source),
                    diagnostics,
                    readGeneration(root),
                    List.of(opened),
                    List.of(new DecoderSnapshots.OpenedSource(FILE_SOURCE_ID, opened)),
                    clocksFor(measurement, format, FILE_SOURCE_ID),
                    input,
                    pageSize);
        } catch (ParserException ex) {
            LOG.info("Decoding failed: {}", sanitize(ex.getMessage()));
            throw new DecoderValidationException(
                    "DECODING_FAILED", HttpStatus.UNPROCESSABLE_ENTITY, "The measurement could not be decoded.");
        } catch (DecoderValidationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            LOG.info("Decoding failed");
            throw new DecoderValidationException(
                    "DECODING_FAILED", HttpStatus.UNPROCESSABLE_ENTITY, "The measurement could not be decoded.");
        } finally {
            decodePermits.release();
            if (!keep) {
                if (store != null) {
                    try {
                        store.close();
                    } catch (RuntimeException ignored) {
                        // The temp session is deleted next.
                    }
                }
                DecoderSnapshots.deleteTree(root);
            }
        }
    }

    private Map<String, Object> publish(
            String status,
            String sourceType,
            String resultOrigin,
            Map<String, Object> identity,
            List<Map<String, Object>> sourceSummaries,
            List<String> diagnostics,
            String decoderVersion,
            List<DecoderSnapshots.OpenedStore> opened,
            List<DecoderSnapshots.OpenedSource> readable,
            SourceClocks clocks,
            Request input,
            int pageSize
    ) {
        try {
            List<DecoderSnapshots.SignalSlot> catalog = DecoderSnapshots.slots(readable);
            long catalogSamples = 0L;
            for (DecoderSnapshots.SignalSlot slot : catalog) {
                catalogSamples += slot.sampleCount();
            }
            if (catalogSamples > properties.getMaxDecodedSamples() || catalog.size() > properties.getMaxSignals()) {
                throw new DecoderValidationException(
                        "RESOURCE_LIMIT",
                        HttpStatus.TOO_MANY_REQUESTS,
                        "The result exceeds the configured sample or signal limit.");
            }
            List<DecoderSnapshots.SignalSlot> slots = filter(catalog, input);
            snapshots.expireDue(Instant.now());
            if (snapshots.size() >= properties.getMaxSnapshots()) {
                throw new DecoderValidationException(
                        "RESOURCE_LIMIT",
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Too many validation results are open.");
            }
            String id = randomId();
            Instant expires = Instant.now().plusSeconds(properties.getCursorTtlSeconds());
            DecoderSnapshots.Snapshot snapshot = snapshots.create(
                    id, expires, status, sourceType, resultOrigin, identity, sourceSummaries,
                    diagnostics, decoderVersion, catalog.size(), catalogSamples, opened, slots,
                    clocks.clocks(), clocks.reasons());
            Map<String, Object> page = snapshot.page(0, 0, pageSize);
            Map<String, Object> body = envelope(snapshot, page, pageSize);
            if (Boolean.TRUE.equals(page.get("paginationComplete"))) {
                snapshots.expire(id);
                body.put("nextCursor", null);
                body.put("cursorExpiresAt", null);
            }
            return body;
        } catch (RuntimeException ex) {
            for (DecoderSnapshots.OpenedStore store : opened) {
                store.close();
            }
            throw ex;
        }
    }

    private Map<String, Object> envelope(DecoderSnapshots.Snapshot snapshot, Map<String, Object> page, int pageSize) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", RESPONSE_SCHEMA);
        body.put("status", snapshot.status);
        body.put("sourceType", snapshot.sourceType);
        body.put("resultOrigin", snapshot.resultOrigin);
        body.put("applicationVersion", properties.getApplicationVersion());
        body.put("decoderVersion", snapshot.decoderVersion);
        body.put("datetimeAvailability", snapshot.datetimeAvailability);
        body.put("datetimeAvailabilityReason", snapshot.datetimeAvailabilityReason);
        Object project = snapshot.identity.get("project");
        if (project instanceof Map<?, ?> projectIdentity && projectIdentity.get("projectName") != null) {
            body.put("projectName", projectIdentity.get("projectName"));
        }
        body.put("measurementFiles", measurementFiles(snapshot));
        body.put("dbcFiles", dbcFiles(snapshot));
        body.putAll(snapshot.identity);
        body.put("sources", snapshot.sourceSummaries);
        body.put("processing", Map.of(
                "completeness", snapshot.status,
                "reprocessed", false));
        body.put("catalogSignalCount", snapshot.catalogSignalCount);
        body.put("catalogSampleCount", snapshot.catalogSampleCount);
        body.put("totalSignalCount", snapshot.signals.size());
        body.put("totalSampleCount", snapshot.totalSamples);
        body.put("returnedSampleCount", page.get("returnedSampleCount"));
        body.put("pageSize", pageSize);
        boolean done = Boolean.TRUE.equals(page.get("paginationComplete"));
        body.put("paginationComplete", done);
        if (done) {
            body.put("nextCursor", null);
            body.put("cursorExpiresAt", null);
        } else {
            body.put("nextCursor", cursor(snapshot.id, (Integer) page.get("nextSignalOrdinal"), (Integer) page.get("nextSampleOffset")));
            body.put("cursorExpiresAt", snapshot.expiresAt.toString());
        }
        body.put("diagnostics", snapshot.diagnostics);
        body.put("valueLimitations", VALUE_LIMITATIONS);
        body.put("signals", page.get("signals"));
        return body;
    }

    private static List<String> measurementFiles(DecoderSnapshots.Snapshot snapshot) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> source : snapshot.sourceSummaries) {
            if (!"COMPLETED".equals(String.valueOf(source.get("completeness")))) {
                continue;
            }
            String name = fileNameOnly(String.valueOf(source.getOrDefault("fileName", "")));
            if (!name.isBlank() && !names.contains(name)) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private static List<String> dbcFiles(DecoderSnapshots.Snapshot snapshot) {
        List<String> names = new ArrayList<>();
        for (DecoderSnapshots.SignalSlot slot : snapshot.signals) {
            String name = fileNameOnly(slot.key().sourceDatabase());
            if (!name.isBlank() && !names.contains(name)) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private List<DecoderSnapshots.SignalSlot> filter(List<DecoderSnapshots.SignalSlot> slots, Request input) {
        if (input.signal != null && input.hasSignalNames()) {
            throw new DecoderValidationException(
                    "INVALID_REQUEST",
                    HttpStatus.BAD_REQUEST,
                    "signal and signalNames cannot be combined.");
        }
        if (input.signal != null) {
            return filterOne(slots, input.signal);
        }
        if (!input.hasSignalNames()) {
            return slots;
        }
        List<DecoderSnapshots.SignalSlot> selected = new ArrayList<>();
        for (String name : input.signalNames) {
            List<DecoderSnapshots.SignalSlot> matches = match(slots, name);
            if (matches.isEmpty()) {
                throw new DecoderValidationException(
                        "SIGNAL_NOT_FOUND",
                        HttpStatus.NOT_FOUND,
                        "The signal was not found in the decoded result.",
                        Map.of("signal", name));
            }
            for (DecoderSnapshots.SignalSlot match : matches) {
                if (selected.stream().noneMatch(existing -> sameSlot(existing, match))) {
                    selected.add(match);
                }
            }
        }
        List<DecoderSnapshots.SignalSlot> ordered = new ArrayList<>();
        for (DecoderSnapshots.SignalSlot slot : slots) {
            if (selected.stream().anyMatch(chosen -> sameSlot(chosen, slot))) {
                ordered.add(slot);
            }
        }
        return List.copyOf(ordered);
    }

    private static boolean sameSlot(DecoderSnapshots.SignalSlot left, DecoderSnapshots.SignalSlot right) {
        return left.sourceId().equals(right.sourceId()) && left.key().equals(right.key());
    }

    private List<DecoderSnapshots.SignalSlot> filterOne(List<DecoderSnapshots.SignalSlot> slots, String signal) {
        List<DecoderSnapshots.SignalSlot> matches = match(slots, signal);
        if (matches.isEmpty()) {
            throw new DecoderValidationException(
                    "SIGNAL_NOT_FOUND", HttpStatus.NOT_FOUND, "The signal was not found in the decoded result.");
        }
        if (!DecoderCanonicalIds.isCanonical(signal) && matches.size() > 1) {
            throw new DecoderValidationException(
                    "SIGNAL_AMBIGUOUS",
                    HttpStatus.CONFLICT,
                    "More than one signal has that name.",
                    Map.of("candidates", matches.stream().map(this::candidate).toList()));
        }
        return matches;
    }

    private List<DecoderSnapshots.SignalSlot> match(List<DecoderSnapshots.SignalSlot> slots, String signal) {
        if (DecoderCanonicalIds.isCanonical(signal)) {
            DecoderCanonicalIds.Parsed parsed;
            try {
                parsed = DecoderCanonicalIds.parse(signal);
            } catch (IllegalArgumentException ex) {
                throw new DecoderValidationException(
                        "INVALID_REQUEST", HttpStatus.BAD_REQUEST, "signal identity is malformed.");
            }
            return slots.stream()
                    .filter(slot -> slot.sourceId().equals(parsed.sourceId()) && slot.key().equals(parsed.key()))
                    .toList();
        }
        return slots.stream()
                .filter(slot -> slot.key().signalName().equals(signal))
                .toList();
    }

    private Map<String, Object> candidate(DecoderSnapshots.SignalSlot slot) {
        SignalCatalogKey key = slot.key();
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("canonicalId", DecoderCanonicalIds.canonical(slot.sourceId(), key));
        candidate.put("name", key.signalName());
        candidate.put("messageName", key.messageName());
        candidate.put("canId", Long.toString(key.canId()));
        candidate.put("channel", key.channel());
        candidate.put("sourceId", slot.sourceId());
        candidate.put("sourceFile", fileNameOnly(key.sourceFile()));
        return candidate;
    }

    private static String fileNameOnly(String sourceFile) {
        if (sourceFile == null || sourceFile.isBlank()) {
            return "";
        }
        try {
            Path name = Path.of(sourceFile).getFileName();
            return name == null ? sourceFile : name.toString();
        } catch (RuntimeException ex) {
            return sourceFile;
        }
    }

    private Path metadataDatabase() {
        String configured = properties.getMetadataDb().trim();
        if (configured.isEmpty()) {
            throw new DecoderValidationException(
                    "INTERNAL_ERROR",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Project catalog is not configured.");
        }
        return Path.of(configured);
    }

    private Path requireAllowedFile(String raw, String role) {
        Path path = Path.of(raw).toAbsolutePath().normalize();
        if (!insideAllowed(path)) {
            throw new DecoderValidationException(
                    "PATH_NOT_ALLOWED",
                    HttpStatus.FORBIDDEN,
                    "The path is outside the allowed directories.",
                    Map.of("role", role));
        }
        if (!Files.isRegularFile(path)) {
            throw new DecoderValidationException(
                    "FILE_NOT_FOUND",
                    HttpStatus.NOT_FOUND,
                    "dbc".equals(role)
                            ? "The DBC file does not exist or is not a file."
                            : "The measurement file does not exist or is not a file.",
                    Map.of("role", role));
        }
        try {
            Path real = path.toRealPath();
            if (!insideAllowed(real)) {
                throw new DecoderValidationException(
                        "PATH_NOT_ALLOWED",
                        HttpStatus.FORBIDDEN,
                        "The path is outside the allowed directories.",
                        Map.of("role", role));
            }
            return real;
        } catch (IOException ex) {
            throw new DecoderValidationException(
                    "FILE_NOT_FOUND",
                    HttpStatus.NOT_FOUND,
                    "The measurement file does not exist or is not a file.",
                    Map.of("role", role));
        }
    }

    private boolean insideAllowed(Path path) {
        for (Path root : properties.allowedRootPaths()) {
            if (isSameOrChild(root, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compares both the normalized path and the real path. Windows 8.3 short names and
     * symlinks otherwise look like a different directory from the configured root.
     */
    static boolean isSameOrChild(Path root, Path candidate) {
        if (root == null || candidate == null) {
            return false;
        }
        Path normalRoot = root.toAbsolutePath().normalize();
        Path normalCandidate = candidate.toAbsolutePath().normalize();
        if (normalCandidate.startsWith(normalRoot)) {
            return true;
        }
        try {
            if (Files.exists(root)) {
                Path realRoot = root.toRealPath();
                Path realCandidate = Files.exists(candidate) ? candidate.toRealPath() : normalCandidate;
                return realCandidate.startsWith(realRoot);
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }

    private DbcDatabase parseDbc(Path dbc) {
        try {
            DbcDatabase database = new DbcParser().parse(dbc);
            if (database.messages().isEmpty()) {
                throw new DecoderValidationException(
                        "INVALID_DBC",
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "The DBC does not define any messages.");
            }
            return database;
        } catch (DbcParseException ex) {
            LOG.info("DBC rejected: {}", sanitize(ex.getMessage()));
            throw new DecoderValidationException(
                    "INVALID_DBC", HttpStatus.UNPROCESSABLE_ENTITY, "The DBC could not be parsed.");
        } catch (DecoderValidationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new DecoderValidationException(
                    "INVALID_DBC", HttpStatus.UNPROCESSABLE_ENTITY, "The DBC could not be parsed.");
        }
    }

    private void awaitDecodeDelay() {
        long delay = properties.getDecodeStartDelayMs();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, delay));
        while (System.nanoTime() < deadline) {
            if (shutdown.get()) {
                throw new DecoderValidationException(
                        "INTERNAL_ERROR",
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "The decoder validation API is shut down.");
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new DecoderValidationException(
                        "DECODING_FAILED",
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Decoding was cancelled before it completed.");
            }
        }
    }

    private void rejectPageSize(int pageSize) {
        if (pageSize < 1 || pageSize > properties.getMaxPageSize()) {
            throw new DecoderValidationException(
                    "INVALID_REQUEST",
                    HttpStatus.BAD_REQUEST,
                    "pageSize must be from 1 through " + properties.getMaxPageSize() + ".");
        }
    }

    private String cursor(String id, int signalOrdinal, int sampleOffset) {
        String payload = id + "." + signalOrdinal + "." + sampleOffset;
        String token = payload + "." + mac(payload);
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private ParsedCursor parseCursor(String cursor) {
        byte[] decoded;
        try {
            decoded = java.util.Base64.getUrlDecoder().decode(cursor);
        } catch (IllegalArgumentException ex) {
            throw new DecoderValidationException(
                    "INVALID_REQUEST", HttpStatus.BAD_REQUEST, "cursor is not valid.");
        }
        String text = new String(decoded, java.nio.charset.StandardCharsets.US_ASCII);
        String[] parts = text.split("\\.");
        if (parts.length != 4) {
            throw new DecoderValidationException(
                    "INVALID_REQUEST", HttpStatus.BAD_REQUEST, "cursor is not valid.");
        }
        String payload = parts[0] + "." + parts[1] + "." + parts[2];
        if (!MessageDigest.isEqual(mac(payload).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                parts[3].getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new DecoderValidationException(
                    "CURSOR_EXPIRED", HttpStatus.GONE, "The validation cursor has expired.");
        }
        try {
            int ordinal = Integer.parseInt(parts[1]);
            int offset = Integer.parseInt(parts[2]);
            if (parts[0].isBlank() || ordinal < 0 || offset < 0) {
                throw new NumberFormatException("range");
            }
            return new ParsedCursor(parts[0], ordinal, offset);
        } catch (NumberFormatException ex) {
            throw new DecoderValidationException(
                    "INVALID_REQUEST", HttpStatus.BAD_REQUEST, "cursor is not valid.");
        }
    }

    private String mac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cursorKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Cursor protection is unavailable.");
        }
    }

    private String randomId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private void recoverSessions(boolean alwaysLog) {
        if (sessionDirectory == null) {
            return;
        }
        DecoderSessionRecovery.Result recovery = DecoderSessionRecovery.recover(
                sessionDirectory,
                Duration.ofSeconds(Math.max(0, properties.getUnownedSessionMinAgeSeconds())),
                Instant.now());
        if (alwaysLog || recovery.removed() > 0) {
            LOG.info(
                    "Decoder session recovery removed {} abandoned session(s), kept {} live, skipped {}",
                    recovery.removed(), recovery.keptLive(), recovery.skipped());
        }
    }

    private static void markSession(Path root) {
        try {
            DecoderSessionRecovery.writeOwner(root);
        } catch (IOException ex) {
            throw new DecoderValidationException(
                    "INTERNAL_ERROR",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "The decoding session could not be recorded.");
        }
    }

    private Path resolveSessionDirectory() {
        String configured = properties.getSessionDirectory().trim();
        if (!configured.isEmpty()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "stellarai-decoder-validation");
    }

    private static SourceClocks clocksFor(Path measurement, String format, String sourceId) {
        DecoderRecordingClock.Read read = DecoderRecordingClock.read(measurement, format);
        Map<String, SourceClock> clocks = read.clock().available()
                ? Map.of(sourceId, read.clock())
                : Map.of();
        return new SourceClocks(clocks, Map.of(sourceId, read.reason()));
    }

    private SourceClocks recordingClocks(List<ReadOnlyProjectCatalog.SourceRow> sources) {
        Map<String, SourceClock> clocks = new LinkedHashMap<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        for (ReadOnlyProjectCatalog.SourceRow source : sources) {
            if (!source.completed()) {
                continue;
            }
            Path path = source.measurementPath();
            if (path == null) {
                reasons.put(source.sourceId(), "measurement file is not available for recording time");
                continue;
            }
            DecoderRecordingClock.Read read = verifiedClock(path, source.sourceFingerprint());
            reasons.put(source.sourceId(), read.reason());
            if (read.clock().available()) {
                clocks.put(source.sourceId(), read.clock());
            }
        }
        return new SourceClocks(Map.copyOf(clocks), Map.copyOf(reasons));
    }

    private static DecoderRecordingClock.Read verifiedClock(Path path, String expectedFingerprint) {
        if (expectedFingerprint != null && !expectedFingerprint.isBlank()) {
            String actual = fingerprint(path);
            if (actual.isBlank() || !actual.equalsIgnoreCase(expectedFingerprint.trim())) {
                return new DecoderRecordingClock.Read(
                        SourceClock.missing(), "stored measurement no longer matches its fingerprint");
            }
        }
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        return DecoderRecordingClock.read(path, name);
    }

    private record SourceClocks(Map<String, SourceClock> clocks, Map<String, String> reasons) {
    }

    private static String fingerprint(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            return "";
        }
    }

    private static String readGeneration(Path root) {
        try {
            Path marker = root.resolve(GraphSignalSessionStore.DECODER_GENERATION_FILE);
            if (Files.isRegularFile(marker)) {
                return Files.readString(marker).trim();
            }
        } catch (IOException ignored) {
            return GraphSignalSessionStore.DECODER_GENERATION;
        }
        return GraphSignalSessionStore.DECODER_GENERATION;
    }

    private static String decoderVersion(List<ReadOnlyProjectCatalog.SourceRow> sources) {
        List<String> versions = sources.stream()
                .filter(ReadOnlyProjectCatalog.SourceRow::completed)
                .map(ReadOnlyProjectCatalog.SourceRow::decoderVersion)
                .filter(version -> version != null && !version.isBlank())
                .distinct()
                .toList();
        if (versions.isEmpty()) {
            return "";
        }
        if (versions.size() == 1) {
            return versions.getFirst();
        }
        return "MIXED";
    }

    private static List<Map<String, Object>> sourceDetails(List<ReadOnlyProjectCatalog.SourceRow> sources) {
        List<Map<String, Object>> details = new ArrayList<>();
        for (ReadOnlyProjectCatalog.SourceRow source : sources) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceId", source.sourceId());
            row.put("fileName", source.fileName());
            row.put("completeness", source.completeness());
            row.put("reason", source.reason());
            row.put("sourceFingerprint", source.sourceFingerprint() == null ? "" : source.sourceFingerprint());
            row.put("dbcFingerprint", source.dbcFingerprint() == null ? "" : source.dbcFingerprint());
            details.add(row);
        }
        return details;
    }

    private static List<String> diagnostics(ProcessingResult result) {
        List<String> lines = new ArrayList<>();
        lines.add("outcome=" + result.outcome().name());
        for (ProcessingDiagnostic diagnostic : result.diagnostics()) {
            if (lines.size() >= 50) {
                break;
            }
            lines.add(diagnostic.code() + ": " + sanitize(diagnostic.message()));
        }
        return List.copyOf(lines);
    }

    static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String cleaned = message.replaceAll("[A-Za-z]:\\\\[^\\s\"']+", "[path]");
        if (cleaned.length() > 240) {
            return cleaned.substring(0, 240);
        }
        return cleaned;
    }

    private static boolean isLoopback(String address) {
        String value = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        return value.equals("127.0.0.1") || value.equals("localhost") || value.equals("::1")
                || value.equals("0:0:0:0:0:0:0:1");
    }

    private record ParsedCursor(String id, int signalOrdinal, int sampleOffset) {
    }

    private record Request(
            boolean hasProject,
            boolean hasFile,
            String projectName,
            String filePath,
            String dbcPath,
            String signal,
            List<String> signalNames,
            Integer pageSize,
            String cursor,
            boolean includeIncomplete
    ) {
        boolean hasSignalNames() {
            return signalNames != null && !signalNames.isEmpty();
        }

        static Request parse(Map<String, Object> body) {
            Map<String, Object> request = body == null ? Map.of() : body;
            String projectName = text(request, "projectName");
            String filePath = text(request, "filePath");
            String dbcPath = text(request, "dbcPath");
            String signal = text(request, "signal");
            List<String> signalNames = signalNames(request);
            String cursor = text(request, "cursor");
            Integer pageSize = pageSize(request.get("pageSize"));
            boolean includeIncomplete = flag(request.get("includeIncomplete"));
            return new Request(
                    projectName != null,
                    filePath != null,
                    projectName,
                    filePath,
                    dbcPath,
                    signal,
                    signalNames,
                    pageSize,
                    cursor,
                    includeIncomplete);
        }

        private static List<String> signalNames(Map<String, Object> body) {
            if (!body.containsKey("signalNames") || body.get("signalNames") == null) {
                return List.of();
            }
            Object value = body.get("signalNames");
            if (!(value instanceof List<?> list) || list.isEmpty()) {
                throw new DecoderValidationException(
                        "INVALID_REQUEST",
                        HttpStatus.BAD_REQUEST,
                        "signalNames must be a non-empty array of strings.");
            }
            List<String> names = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof String text) || text.isBlank()) {
                    throw new DecoderValidationException(
                            "INVALID_REQUEST",
                            HttpStatus.BAD_REQUEST,
                            "signalNames must be a non-empty array of strings.");
                }
                names.add(text.trim());
            }
            return List.copyOf(names);
        }

        private static String text(Map<String, Object> body, String field) {
            if (!body.containsKey(field) || body.get(field) == null) {
                return null;
            }
            Object value = body.get(field);
            if (!(value instanceof String text)) {
                throw new DecoderValidationException(
                        "INVALID_REQUEST", HttpStatus.BAD_REQUEST, field + " must be a string.");
            }
            String trimmed = text.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }

        private static Integer pageSize(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof Integer integer) {
                return integer;
            }
            if (value instanceof Long number) {
                if (number < 1 || number > Integer.MAX_VALUE) {
                    throw new DecoderValidationException(
                            "INVALID_REQUEST", HttpStatus.BAD_REQUEST, "pageSize is out of range.");
                }
                return number.intValue();
            }
            throw new DecoderValidationException(
                    "INVALID_REQUEST", HttpStatus.BAD_REQUEST, "pageSize must be an integer.");
        }

        private static boolean flag(Object value) {
            if (value == null) {
                return false;
            }
            if (value instanceof Boolean flag) {
                return flag;
            }
            throw new DecoderValidationException(
                    "INVALID_REQUEST", HttpStatus.BAD_REQUEST, "includeIncomplete must be a boolean.");
        }
    }
}
