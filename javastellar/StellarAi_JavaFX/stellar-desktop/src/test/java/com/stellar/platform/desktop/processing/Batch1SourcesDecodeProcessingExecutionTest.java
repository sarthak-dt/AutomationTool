package com.stellar.platform.desktop.processing;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.FileKindDetector;
import com.stellar.platform.desktop.project.FileValidationStatus;
import com.stellar.platform.desktop.project.ProjectFilesAddResult;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.processing.DetectedMeasurementFormat;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch 1 execution against the read-only measurement folders.
 * Source files are opened only. Evidence is written beside the QA tracker.
 */
@Timeout(value = 20, unit = TimeUnit.MINUTES)
class Batch1SourcesDecodeProcessingExecutionTest {

    private static final Path MF4_EMPTY = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\MF4Files");
    private static final Path MF4 = Path.of("C:\\Users\\DT - User\\Downloads\\OneDrive_2_7-13-2026_MF4 files");
    private static final Path BLF = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\BLF");
    private static final Path ASC = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\ASC");
    private static final Path DAT = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\dat files 3.1\\.dat files 3.1");
    private static final Path DBC = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\dbcfiles");
    private static final String XENON_NAME =
            "Xenon new script C910P41 2020-02-07 16-22-19-999140 Partition 0.dat";
    private static final String XENON_SHA =
            "F66753745C79EC35D7AA87CE96B877D549640C15FD52286585887F2D6ED62CF5";
    private static final Path EVIDENCE = Path.of("D:\\DesktopApp\\DriveT\\docs\\qa-handoff\\batch1-execution.txt");

    private static final StringBuilder NOTES = new StringBuilder();
    private static DesktopAppService service;

    @TempDir
    static Path temp;

    @AfterAll
    static void finish() throws Exception {
        if (service != null) {
            service.shutdown();
            service = null;
        }
        Files.createDirectories(EVIDENCE.getParent());
        Files.writeString(EVIDENCE, NOTES.toString());
    }

    @Test
    void executeSourcesDecodeAndXenonProcessing() throws Exception {
        note("started=" + Instant.now());
        note("mf4FilesFolderCount=" + fileCount(MF4_EMPTY));
        inventoryFolder("MF4", MF4);
        inventoryFolder("BLF", BLF);
        inventoryFolder("ASC", ASC);
        inventoryFolder("DAT", DAT);
        inventoryFolder("DBC", DBC);

        Path xenon = DAT.resolve(XENON_NAME);
        assertTrue(Files.isRegularFile(xenon), "Xenon reference file is missing");
        String xenonHash = sha256(xenon);
        note("xenonSize=" + Files.size(xenon));
        note("xenonSha256=" + xenonHash);
        assertEquals(XENON_SHA, xenonHash);
        note("xenonGroups=" + mdf3Groups(xenon));
        long xenonStamp = Files.getLastModifiedTime(xenon).toMillis();

        Path smallestMf4 = smallest(MF4);
        Path smallestBlf = smallest(BLF);
        Path smallestAsc = smallest(ASC);
        Path dbc = DBC.resolve("1.dbc");
        Path mf4NamedInDbcFolder = DBC.resolve("b1-3.mf4");
        Path junk = temp.resolve("not-a-measurement.txt");
        Files.writeString(junk, "hello\n");

        openService();
        service.newProject("Batch1");
        ProjectFilesAddResult added = service.addMeasurementPaths(
                List.of(xenon, smallestAsc, smallestBlf, smallestMf4),
                DesktopAppService.PlatformUi.NOOP);
        note("addSummary=" + added.summaryMessage());
        note("addCount=" + added.addedCount());
        for (WorkspaceFile file : added.added()) {
            note("added " + file.fileName()
                    + " kind=" + file.kind()
                    + " status=" + file.status()
                    + " message=" + file.validationMessage()
                    + " detected=" + file.detectedFormatLabel());
        }
        ProjectFilesAddResult duplicate = service.addMeasurementPaths(List.of(xenon), DesktopAppService.PlatformUi.NOOP);
        note("duplicateSummary=" + duplicate.summaryMessage());
        note("duplicateMessages=" + duplicate.duplicateMessages());
        ProjectFilesAddResult skipped = service.addMeasurementPaths(List.of(junk), DesktopAppService.PlatformUi.NOOP);
        note("junkSummary=" + skipped.summaryMessage());
        ProjectFilesAddResult definitions = service.addDefinitionPaths(List.of(dbc, mf4NamedInDbcFolder));
        note("definitionSummary=" + definitions.summaryMessage());
        for (WorkspaceFile file : service.workspace().definitionFiles()) {
            note("definition " + file.fileName() + " kind=" + file.kind() + " status=" + file.status());
        }

        assertEquals(4, added.addedCount());
        assertEquals(1, duplicate.duplicateCount());
        assertTrue(duplicate.duplicateMessages().getFirst().contains("already added"));
        assertEquals(0, skipped.addedCount());
        assertEquals(FileKind.MEASUREMENT_DAT, fileNamed(xenon).kind());
        assertEquals(FileValidationStatus.READY, fileNamed(xenon).status());
        assertTrue(fileNamed(xenon).validationMessage().startsWith("Direct processing"));
        assertEquals(xenonStamp, Files.getLastModifiedTime(xenon).toMillis());
        assertEquals(XENON_SHA, sha256(xenon));

        awaitInventory(fileNamed(smallestBlf), 180);
        awaitInventory(fileNamed(smallestAsc), 180);
        awaitInventory(fileNamed(smallestMf4), 240);
        for (WorkspaceFile file : List.of(fileNamed(smallestBlf), fileNamed(smallestAsc), fileNamed(smallestMf4))) {
            Optional<CanSourceInventory> inventory = service.sourceInventoryModel().inventoryFor(file.id());
            note("inventory " + file.fileName()
                    + " job=" + service.inventoryJobState(file.projectSourceId())
                    + " present=" + inventory.isPresent()
                    + " detail=" + inventory.map(this::inventoryDetail).orElse("none"));
        }

        boolean prepared = service.prepareDirectProcessingSelection();
        service.selectMeasurement(fileNamed(xenon).id());
        note("preparedDirect=" + prepared);
        note("selected=" + service.workspace().selectedMeasurement().map(WorkspaceFile::fileName).orElse("none"));
        note("validation=" + service.validateCurrentSelection().issues());
        note("directTargets=" + service.directProcessingTargets().stream().map(WorkspaceFile::fileName).toList());
        processSelected();
        note("processingStatus=" + service.workspace().processingStatus());
        note("processingMessage=" + service.workspace().latestSummary().map(s -> s.userMessage()).orElse(""));
        List<SignalCatalogEntry> catalog = service.signalsModel().catalog().entries();
        note("catalogSignals=" + catalog.size());
        long maxSamples = 0;
        for (SignalCatalogEntry entry : catalog) {
            note("signal " + entry.signalName() + " samples=" + entry.sampleCount() + " unit=" + entry.unit());
            maxSamples = Math.max(maxSamples, entry.sampleCount());
        }
        note("maxSamples=" + maxSamples);
        long aggregateSamples = catalog.stream().mapToLong(SignalCatalogEntry::sampleCount).sum();
        note("aggregateSamples=" + aggregateSamples);
        note("warnings=" + service.processingWarningReport().items().stream()
                .map(item -> item.category() + ": " + item.summary()
                        + (item.technicalDetail().isBlank() ? "" : " (" + item.technicalDetail() + ")"))
                .toList());
        SignalCatalogEntry speed = catalog.stream()
                .filter(entry -> entry.signalName().equals("IN_Vehicle_speed"))
                .findFirst()
                .orElseThrow();
        SignalCatalogEntry coolant = catalog.stream()
                .filter(entry -> entry.signalName().equals("IN_Coolant_temperature"))
                .findFirst()
                .orElseThrow();
        note("speedSamples=" + speed.sampleCount() + " unit=" + speed.unit());
        note("coolantSamples=" + coolant.sampleCount() + " unit=" + coolant.unit());
        AtomicReference<String> speedHit = new AtomicReference<>("missing");
        AtomicReference<String> coolantHit = new AtomicReference<>("missing");
        AtomicReference<String> categorical = new AtomicReference<>("missing");
        service.signalsModel().catalogStore().forEachSample(speed.key(), sample -> {
            if ("missing".equals(speedHit.get()) && sample.rawValue() != null && sample.rawValue() == 9340L) {
                speedHit.set(describe(sample));
            }
        });
        service.signalsModel().catalogStore().forEachSample(coolant.key(), sample -> {
            if ("missing".equals(coolantHit.get()) && sample.rawValue() != null && sample.rawValue() == 320L) {
                coolantHit.set(describe(sample));
            }
        });
        for (SignalCatalogEntry entry : catalog) {
            if (!"missing".equals(categorical.get())) {
                break;
            }
            service.signalsModel().catalogStore().forEachSample(entry.key(), sample -> {
                if ("missing".equals(categorical.get())
                        && sample.textualValue() != null
                        && sample.textualValue().equalsIgnoreCase("FALSE")) {
                    categorical.set(entry.signalName() + " " + describe(sample));
                }
            });
        }
        note("speedRaw9340=" + speedHit.get());
        note("coolantRaw320=" + coolantHit.get());
        note("categoricalFalse=" + categorical.get());
        assertEquals(30, catalog.size());
        assertEquals(268_069L, aggregateSamples);
        assertTrue(speedHit.get().contains("72.96875"), speedHit.get());
        assertTrue(coolantHit.get().contains("80.0") || coolantHit.get().contains("80."), coolantHit.get());
        assertFalse(categorical.get().equals("missing"), categorical.get());
        assertTrue(categorical.get().contains("NaN") || categorical.get().contains("textual=FALSE"), categorical.get());

        String projectId = service.currentProjectId();
        int artifacts = service.metadataStore().loadProject(projectId).orElseThrow().processingArtifacts().size();
        note("artifactsAfterFirstRun=" + artifacts);
        service.shutdown();
        service = null;
        openService();
        assertTrue(service.openSavedProject(projectId).opened());
        note("reopenMeasurements=" + service.workspace().measurementFiles().size());
        note("reopenCatalog=" + service.signalsModel().catalog().entries().size());
        assertEquals(4, service.workspace().measurementFiles().size());
        assertEquals(30, service.signalsModel().catalog().entries().size());
        assertEquals(XENON_SHA, sha256(xenon));

        ProjectFilesAddResult duplicateDbc = service.addDefinitionPaths(List.of(dbc));
        note("duplicateDbc=" + duplicateDbc.summaryMessage() + " " + duplicateDbc.duplicateMessages());
        Path emptyAsc = temp.resolve("empty.asc");
        Files.write(emptyAsc, new byte[0]);
        ProjectFilesAddResult emptyAdd = service.addMeasurementPaths(List.of(emptyAsc), DesktopAppService.PlatformUi.NOOP);
        note("emptyAsc=" + emptyAdd.summaryMessage()
                + " added=" + emptyAdd.addedCount()
                + " skipped=" + emptyAdd.skippedMessages());
        Path corruptDbc = temp.resolve("corrupt.dbc");
        Files.write(corruptDbc, new byte[]{0x00, 0x01, 0x02, 0x03, 0x41, 0x42});
        ProjectFilesAddResult corruptDef = service.addDefinitionPaths(List.of(corruptDbc));
        note("corruptDbc=" + corruptDef.summaryMessage()
                + " added=" + corruptDef.addedCount()
                + " skipped=" + corruptDef.skippedMessages());
        String ascId = service.workspace().measurementFiles().stream()
                .filter(file -> file.fileName().endsWith(".asc"))
                .map(WorkspaceFile::id)
                .findFirst()
                .orElseThrow();
        int beforeRemove = service.workspace().measurementFiles().size();
        boolean removed = service.removeFile(ascId);
        note("removedAsc=" + removed + " before=" + beforeRemove + " after=" + service.workspace().measurementFiles().size());
        note("finished=" + Instant.now());
    }

    private String inventoryDetail(CanSourceInventory inventory) {
        return "status=" + inventory.status()
                + " frames=" + inventory.totalCanFrames()
                + " channels=" + inventory.totalCanChannels()
                + " diagnostics=" + inventory.diagnostics();
    }

    private static String describe(SignalSamplePoint sample) {
        return "raw=" + sample.rawValue()
                + " physical=" + sample.physicalValue()
                + " textual=" + sample.textualValue()
                + " validity=" + sample.validity();
    }

    private void awaitInventory(WorkspaceFile file, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            var state = service.inventoryJobState(file.projectSourceId());
            if (state != null && state.terminal()) {
                return;
            }
            Thread.sleep(200);
        }
        note("inventoryTimeout " + file.fileName());
    }

    private WorkspaceFile fileNamed(Path path) {
        return service.workspace().measurementFiles().stream()
                .filter(file -> file.path().equals(path))
                .findFirst()
                .orElseThrow();
    }

    private void processSelected() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean(false);
        service.startLocalProcessing(DesktopAppService.PlatformUi.NOOP, () -> {
            ProjectProcessingStatus status = service.workspace().processingStatus();
            if (!finished.get() && (status == ProjectProcessingStatus.COMPLETED
                    || status == ProjectProcessingStatus.COMPLETED_WITH_WARNINGS
                    || status == ProjectProcessingStatus.FAILED
                    || status == ProjectProcessingStatus.CANCELLED)) {
                finished.set(true);
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.MINUTES), "processing did not finish");
    }

    private void openService() {
        Path home = temp.resolve("home");
        Path db = temp.resolve("meta.db");
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        System.setProperty("stellarai.metadata.db", db.toString());
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
    }

    private static void inventoryFolder(String label, Path folder) throws Exception {
        note("folder " + label + " exists=" + Files.isDirectory(folder));
        if (!Files.isDirectory(folder)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(folder)) {
            files = stream.filter(Files::isRegularFile).sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
        note(label + " count=" + files.size());
        for (Path file : files) {
            byte[] head = new byte[16];
            int read;
            try (var in = Files.newInputStream(file)) {
                read = in.read(head);
            }
            FileKindDetector.Inspection inspection = FileKindDetector.inspect(file);
            DetectedMeasurementFormat detected = inspection.detected();
            note(label
                    + " file=" + file.getFileName()
                    + " size=" + Files.size(file)
                    + " sha256=" + sha256(file)
                    + " head=" + hex(head, read)
                    + " kind=" + inspection.kind()
                    + " recognized=" + inspection.recognized()
                    + " parser=" + inspection.parserSupported()
                    + " content=" + (detected == null || detected.content() == null
                    ? ""
                    : detected.content().formatKey() + " " + detected.displayLabel()));
        }
    }

    private static int fileCount(Path folder) throws Exception {
        if (!Files.isDirectory(folder)) {
            return -1;
        }
        try (Stream<Path> stream = Files.list(folder)) {
            return (int) stream.filter(Files::isRegularFile).count();
        }
    }

    private static Path smallest(Path folder) throws Exception {
        try (Stream<Path> stream = Files.list(folder)) {
            return stream.filter(Files::isRegularFile)
                    .min(Comparator.comparingLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (Exception ex) {
                            return Long.MAX_VALUE;
                        }
                    }))
                    .orElseThrow();
        }
    }

    private static String mdf3Groups(Path file) throws Exception {
        try (FileChannel channel = FileChannel.open(file)) {
            long size = channel.size();
            ByteBuffer hd = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            channel.position(64);
            readFully(channel, hd);
            if (!"HD".equals(ascii(hd, 0, 2))) {
                return "no HD";
            }
            long dg = Integer.toUnsignedLong(hd.getInt(4));
            StringBuilder groups = new StringBuilder();
            int guard = 0;
            while (dg > 0 && guard++ < 32) {
                ByteBuffer dgBlock = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
                channel.position(dg);
                readFully(channel, dgBlock);
                long nextDg = Integer.toUnsignedLong(dgBlock.getInt(4));
                long cg = Integer.toUnsignedLong(dgBlock.getInt(8));
                while (cg > 0 && guard++ < 64) {
                    ByteBuffer cgBlock = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
                    channel.position(cg);
                    readFully(channel, cgBlock);
                    long nextCg = Integer.toUnsignedLong(cgBlock.getInt(4));
                    int recordSize = Short.toUnsignedInt(cgBlock.getShort(20));
                    long cycles = Integer.toUnsignedLong(cgBlock.getInt(22));
                    if (!groups.isEmpty()) {
                        groups.append(';');
                    }
                    groups.append("cycles=").append(cycles).append(" recordSize=").append(recordSize);
                    cg = nextCg;
                }
                dg = nextDg;
            }
            return groups.toString();
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer) throws Exception {
        buffer.clear();
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                break;
            }
        }
        buffer.flip();
    }

    private static String ascii(ByteBuffer buffer, int offset, int length) {
        byte[] raw = new byte[length];
        buffer.position(offset);
        buffer.get(raw);
        return new String(raw, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest()).toUpperCase(Locale.ROOT);
    }

    private static String hex(byte[] bytes, int length) {
        if (length <= 0) {
            return "";
        }
        return HexFormat.of().formatHex(bytes, 0, length);
    }

    private static void note(String line) {
        NOTES.append(line).append(System.lineSeparator());
    }
}
