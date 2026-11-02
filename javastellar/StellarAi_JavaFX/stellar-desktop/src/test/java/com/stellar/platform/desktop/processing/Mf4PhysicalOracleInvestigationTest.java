package com.stellar.platform.desktop.processing;

import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.parse.DbcParseException;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalMeasurementSample;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.parser.mf4.Mf4Inspector;
import com.stellar.platform.parser.mf4.analysis.MdfFinalizationStatus;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraph;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraphDiscovery;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.report.MdfInspectionReport;
import com.stellar.platform.parser.mf4.schema.MdfCnMetadata;
import com.stellar.platform.parser.mf4.schema.MdfMetadataSummary;
import com.stellar.platform.parser.mf4.schema.MdfSchemaExtractor;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.inventory.SourceInventoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Classifies the supplied real MF4 containers and searches for an independent physical oracle.
 * Frame inventory is recorded as inventory. It is not a physical-decoding pass.
 */
class Mf4PhysicalOracleInvestigationTest {

    private static final Path MF4_A = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\MF4Files");
    private static final Path MF4_B = Path.of("C:\\Users\\DT - User\\Downloads\\OneDrive_2_7-13-2026_MF4 files");
    private static final Path DBC_DIR = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\dbcfiles");
    private static final Path SYNTHETIC = Path.of("D:\\StellarAI-E2E\\InstalledWork\\installed.mf4");
    private static final Path EVIDENCE = Path.of("D:\\DesktopApp\\DriveT\\docs\\qa-handoff\\mf4-physical-verification.txt");
    private static final String KNOWN = "4BE883CD_00000975_00000001-63C7A554.MF4";

    @Test
    @Timeout(value = 45, unit = TimeUnit.MINUTES)
    void classifySuppliedMf4AndSearchForPhysicalOracle() throws Exception {
        assertTrue(Files.isDirectory(MF4_A));
        assertTrue(Files.isDirectory(MF4_B));
        List<String> lines = new ArrayList<>();
        lines.add("started=" + Instant.now());
        lines.add("purpose=Classify 33 real MF4 containers and search for an independent physical oracle.");
        lines.add("physicalPass=NOT CLAIMED. Frame counts are inventory only.");
        Map<Path, String> before = new LinkedHashMap<>();
        try {
            List<Path> mf4 = new ArrayList<>();
            collect(MF4_A, mf4);
            collect(MF4_B, mf4);
            collect(DBC_DIR, mf4);
            mf4.sort(Comparator.comparing(Path::toString));
            lines.add("mf4Paths=" + mf4.size());

            List<Path> dbcPaths = new ArrayList<>();
            collectDbc(MF4_A, dbcPaths);
            collectDbc(DBC_DIR, dbcPaths);
            Map<String, DbcRef> dbcs = new LinkedHashMap<>();
            DbcParser parser = new DbcParser();
            for (Path dbc : dbcPaths) {
                String sha = sha256(dbc);
                before.put(dbc, sha);
                ParsedDbc parsed = parseDbc(parser, dbc);
                if (parsed.database != null && !dbcs.containsKey(sha)) {
                    TreeSet<CanIdKey> ids = new TreeSet<>(Comparator
                            .comparingLong(CanIdKey::canId)
                            .thenComparing(CanIdKey::extended));
                    for (DbcMessage message : parsed.database.messages()) {
                        ids.add(message.canId());
                    }
                    dbcs.put(sha, new DbcRef(dbc.getFileName().toString(), dbc, sha, ids));
                }
                lines.add("dbc file=" + dbc.getFileName()
                        + " sha256=" + sha
                        + " utf8=" + parsed.utf8
                        + " charset=" + parsed.charset
                        + " messages=" + (parsed.database == null ? "UNREADABLE" : parsed.database.messages().size())
                        + " detail=" + parsed.detail
                        + " path=" + dbc);
            }

            Mf4Inspector inspector = new Mf4Inspector();
            SourceInventoryService inventory = SourceInventoryService.createDefault();
            Map<String, String> inventoryBySha = new LinkedHashMap<>();
            int finalized = 0;
            int unfinalized = 0;
            int inconsistent = 0;
            for (Path file : mf4) {
                String sha = sha256(file);
                before.put(file, sha);
                MdfInspectionReport report;
                SchemaFacts schema;
                try {
                    report = inspector.inspect(file);
                    schema = schema(file);
                } catch (RuntimeException | com.stellar.platform.parser.spi.ParserException ex) {
                    lines.add("file=" + file.getFileName()
                            + " sha256=" + sha
                            + " class=INSPECT_FAILED"
                            + " error=" + ex.getClass().getSimpleName() + ": " + ex.getMessage()
                            + " path=" + file);
                    continue;
                }
                if (report.finalizationStatus() == MdfFinalizationStatus.FINALIZED) {
                    finalized++;
                } else if (report.finalizationStatus() == MdfFinalizationStatus.UNFINALIZED) {
                    unfinalized++;
                } else if (report.finalizationStatus() == MdfFinalizationStatus.INCONSISTENT) {
                    inconsistent++;
                }
                String inventoryLine = inventoryBySha.get(sha);
                if (inventoryLine == null) {
                    try {
                        CanSourceInventory inv = inventory.inventory(file, ReaderContext.ofBatchSize(512), null);
                        inventoryLine = inventoryText(inv);
                        inventoryBySha.put(sha, inventoryLine);
                        lines.add("dbcMatch file=" + file.getFileName() + " " + match(inv, dbcs));
                    } catch (RuntimeException ex) {
                        inventoryLine = "canStatus=INVENTORY_FAILED error=" + ex.getClass().getSimpleName()
                                + ": " + ex.getMessage();
                        inventoryBySha.put(sha, inventoryLine);
                    }
                } else {
                    inventoryLine = inventoryLine + " duplicateOfSameSha=true";
                }
                lines.add("file=" + file.getFileName()
                        + " sha256=" + sha
                        + " size=" + Files.size(file)
                        + " version=" + report.mdfVersion()
                        + " finalization=" + report.finalizationStatus()
                        + " class=" + classify(report)
                        + " recoveryCandidate=" + report.recoveryCandidate()
                        + " physicalData=" + report.physicalDataDetected()
                        + " dg=" + report.dgCount()
                        + " cg=" + report.cgCount()
                        + " cn=" + report.cnCount()
                        + " dt=" + report.dtCount()
                        + " dz=" + report.dzCount()
                        + " declaredCycles=" + report.declaredCycleCountTotal()
                        + " possibleRecords=" + report.possibleRecordCount()
                        + " physicalBytes=" + report.physicalDataRegionLength()
                        + " busEventChannels=" + schema.busEvent
                        + " convertedChannels=" + schema.converted
                        + " identityChannels=" + schema.identity
                        + " gpsNamedChannels=" + schema.gpsNamed
                        + " channelNames=" + schema.names
                        + " reasons=" + report.finalizationReasons()
                        + " unsupported=" + report.unsupportedStructures()
                        + " " + inventoryLine
                        + " path=" + file);
                System.out.println("MF4 " + file.getFileName() + " " + report.finalizationStatus() + " " + inventoryLine);
            }

            lines.add("summary paths=" + mf4.size()
                    + " finalized=" + finalized
                    + " unfinalized=" + unfinalized
                    + " inconsistent=" + inconsistent);
            assertEquals(33, mf4.size());
            assertEquals(0, finalized);

            probeCopy(lines, knownPath(mf4), "known-unfinalized");
            probeCopy(lines, smallestInconsistent(mf4, inspector), "smallest-inconsistent");

            if (Files.isRegularFile(SYNTHETIC)) {
                MdfInspectionReport synthetic = inspector.inspect(SYNTHETIC);
                lines.add("syntheticInstalledMf4 sha256=" + sha256(SYNTHETIC)
                        + " version=" + synthetic.mdfVersion()
                        + " finalization=" + synthetic.finalizationStatus()
                        + " size=" + Files.size(SYNTHETIC)
                        + " origin=DesktopFixtures.writeFinalizedMf4"
                        + " role=SYNTHETIC_PARSER_FIXTURE"
                        + " realFileOracle=false");
            } else {
                lines.add("syntheticInstalledMf4 absent");
            }

            lines.add("oracleSearch repositoryMf4=0");
            lines.add("oracleSearch companionExpectedFiles=0");
            lines.add("oracleSearch approvedRealFinalizedFixture=NONE");
            lines.add("MF4_PHYSICAL_ORACLE_MISSING");
            lines.add("e2e=NOT RUN. Independent physical verification is impossible without a finalized real fixture and known raw/physical values.");
            lines.add("javafx=NOT RUN. No suitable fixture.");
            lines.add("gps=NOT APPLICABLE. No chosen fixture.");
            lines.add("recoveryFeature=NOT ADDED. Existing recovery reads in memory and does not write a finalized container.");
            lines.add("decision=NO-GO");
            lines.add("result=MF4_PHYSICAL_ORACLE_MISSING");
        } finally {
            for (Map.Entry<Path, String> entry : before.entrySet()) {
                String after = sha256(entry.getKey());
                lines.add("hashUnchanged file=" + entry.getKey().getFileName() + " " + entry.getValue().equals(after));
                assertEquals(entry.getValue(), after, entry.getKey().toString());
            }
            lines.add("finished=" + Instant.now());
            Files.createDirectories(EVIDENCE.getParent());
            Files.writeString(EVIDENCE, String.join(System.lineSeparator(), lines));
        }
        String text = Files.readString(EVIDENCE);
        assertTrue(text.contains("MF4_PHYSICAL_ORACLE_MISSING"));
        assertTrue(text.contains("decision=NO-GO"));
    }

    private static void probeCopy(List<String> lines, Path original, String label) throws Exception {
        if (original == null) {
            lines.add("recoveryProbe " + label + " skipped");
            return;
        }
        String before = sha256(original);
        Path dir = Files.createTempDirectory("mf4-probe-");
        Path copy = dir.resolve(original.getFileName().toString() + ".working-copy.mf4");
        Files.copy(original, copy);
        AtomicLong frames = new AtomicLong();
        AtomicLong nativeSamples = new AtomicLong();
        AtomicReference<String> firstNative = new AtomicReference<>("none");
        List<String> warnings = new ArrayList<>();
        try {
            new MdfDynamicRecordDecoder().decode(
                    copy,
                    ReaderContext.ofBatchSize(256),
                    batch -> {
                        for (CanonicalCanFrame ignored : batch) {
                            frames.incrementAndGet();
                        }
                    },
                    batch -> {
                        for (CanonicalMeasurementSample sample : batch) {
                            nativeSamples.incrementAndGet();
                            firstNative.compareAndSet("none", sample.signalName()
                                    + " raw=" + sample.rawValue()
                                    + " decoderPhysical=" + sample.physicalValue()
                                    + " unit=" + sample.unit()
                                    + " validity=" + sample.validity());
                        }
                    },
                    warning -> {
                        if (warnings.size() < 8) {
                            warnings.add(String.valueOf(warning));
                        }
                    }
            );
            lines.add("recoveryProbe label=" + label
                    + " input=WORKING_COPY"
                    + " original=" + original.getFileName()
                    + " copy=" + copy.getFileName()
                    + " frames=" + frames.get()
                    + " nativeSamples=" + nativeSamples.get()
                    + " firstDecoderNative=" + firstNative.get()
                    + " warnings=" + warnings
                    + " independentPhysicalVerification=false"
                    + " note=Decoder output is not an oracle.");
        } catch (RuntimeException | com.stellar.platform.parser.spi.ParserException ex) {
            lines.add("recoveryProbe label=" + label
                    + " input=WORKING_COPY"
                    + " original=" + original.getFileName()
                    + " failed=" + ex.getClass().getSimpleName() + ": " + ex.getMessage()
                    + " independentPhysicalVerification=false");
        } finally {
            Files.deleteIfExists(copy);
            Files.deleteIfExists(dir);
            assertEquals(before, sha256(original));
        }
    }

    private static Path knownPath(List<Path> mf4) {
        for (Path file : mf4) {
            if (file.getFileName().toString().equals(KNOWN)) {
                return file;
            }
        }
        return null;
    }

    private static Path smallestInconsistent(List<Path> mf4, Mf4Inspector inspector) throws Exception {
        Path best = null;
        long bestSize = Long.MAX_VALUE;
        for (Path file : mf4) {
            MdfInspectionReport report = inspector.inspect(file);
            if (report.finalizationStatus() != MdfFinalizationStatus.INCONSISTENT) {
                continue;
            }
            long size = Files.size(file);
            if (size < bestSize) {
                bestSize = size;
                best = file;
            }
        }
        return best;
    }

    private static String match(CanSourceInventory inv, Map<String, DbcRef> dbcs) {
        TreeSet<CanIdKey> seen = new TreeSet<>(Comparator.comparingLong(CanIdKey::canId).thenComparing(CanIdKey::extended));
        for (CanChannelInventory channel : inv.channels()) {
            for (CanMessageInventory message : channel.messages()) {
                seen.add(CanIdKey.fromFrame(message.canId(), message.extended()));
            }
        }
        StringBuilder text = new StringBuilder();
        text.append("observedIds=").append(seen.size());
        int best = 0;
        String bestName = "none";
        for (DbcRef dbc : unique(dbcs)) {
            int overlap = 0;
            for (CanIdKey id : seen) {
                if (dbc.ids.contains(id)) {
                    overlap++;
                }
            }
            text.append(" | ").append(dbc.name).append(" overlap=").append(overlap).append("/").append(dbc.ids.size());
            if (overlap > best) {
                best = overlap;
                bestName = dbc.name;
            }
        }
        text.append(" | best=").append(bestName).append(" overlapIds=").append(best);
        if (best == 0) {
            text.append(" compatibility=NO_DBC_ID_OVERLAP");
        } else {
            text.append(" compatibility=ID_OVERLAP_NOT_A_PHYSICAL_ORACLE");
        }
        return text.toString();
    }

    private static List<DbcRef> unique(Map<String, DbcRef> dbcs) {
        return new ArrayList<>(dbcs.values());
    }

    private static String inventoryText(CanSourceInventory inv) {
        return "canStatus=" + inv.status()
                + " canChannels=" + inv.totalCanChannels()
                + " canFrames=" + inv.totalCanFrames()
                + " uniqueIds=" + inv.totalUniqueMessageIds()
                + " diagnostics=" + inv.diagnostics();
    }

    private static String classify(MdfInspectionReport report) {
        String reasons = report.finalizationReasons().toString();
        if (reasons.contains("UnFinMF")) {
            return report.physicalDataDetected()
                    ? "UNFINALIZED_WITH_PHYSICAL_BYTES"
                    : "UNFINALIZED_INCOMPLETE_NO_DECLARED_PHYSICAL_REGION";
        }
        if (report.possibleRecordCount() != null && report.possibleRecordCount() == 0L && report.physicalDataDetected()) {
            return "INCONSISTENT_PHYSICAL_BYTES_WITHOUT_USABLE_RECORD_COUNT";
        }
        if (reasons.contains("cycle_count=0")) {
            return "INCONSISTENT_ZERO_CYCLE_COUNT_WITH_PHYSICAL_DATA";
        }
        if (report.finalizationStatus() == MdfFinalizationStatus.INCONSISTENT) {
            return "INCONSISTENT_OTHER";
        }
        return report.finalizationStatus().name();
    }

    private static SchemaFacts schema(Path file) throws Exception {
        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            MdfBlockGraph graph = MdfBlockGraphDiscovery.discover(access, CancellationToken.NONE);
            MdfMetadataSummary meta = MdfSchemaExtractor.extract(access, graph, CancellationToken.NONE);
            int bus = 0;
            int converted = 0;
            int identity = 0;
            int gps = 0;
            List<String> names = new ArrayList<>();
            for (MdfCnMetadata cn : meta.channels()) {
                if ((cn.flags() & MdfCnMetadata.FLAG_BUS_EVENT) != 0) {
                    bus++;
                }
                MdfChannelConversion conversion = cn.conversion();
                if (conversion != null && conversion.type() != MdfChannelConversion.TYPE_IDENTITY && conversion.applied()) {
                    converted++;
                } else {
                    identity++;
                }
                String name = cn.name() == null ? "" : cn.name();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("gps") || lower.contains("lat") || lower.contains("lon") || lower.contains("altitude")) {
                    gps++;
                }
                if (names.size() < 24 && !name.isBlank()) {
                    names.add(name);
                }
            }
            return new SchemaFacts(bus, converted, identity, gps, names);
        }
    }

    private static void collect(Path root, List<Path> out) throws Exception {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mf4"))
                    .forEach(out::add);
        }
    }

    private static void collectDbc(Path root, List<Path> out) throws Exception {
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dbc"))
                    .forEach(out::add);
        }
    }

    private static ParsedDbc parseDbc(DbcParser parser, Path dbc) {
        try {
            return new ParsedDbc(parser.parse(dbc), true, "UTF-8", "product parser accepted UTF-8");
        } catch (DbcParseException utf8Failure) {
            try {
                DbcDatabase latin = parser.parse(dbc, Charset.forName("windows-1252"));
                return new ParsedDbc(latin, false, "windows-1252",
                        "product UTF-8 parser rejected the file: " + utf8Failure.getMessage()
                                + ". Investigation reread the bytes as windows-1252. The product parser was not changed.");
            } catch (DbcParseException latinFailure) {
                return new ParsedDbc(null, false, "unreadable",
                        "UTF-8: " + utf8Failure.getMessage() + " | windows-1252: " + latinFailure.getMessage());
            }
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(path));
        return HexFormat.of().formatHex(digest.digest()).toUpperCase(Locale.ROOT);
    }

    private record SchemaFacts(int busEvent, int converted, int identity, int gpsNamed, List<String> names) {
    }

    private record DbcRef(String name, Path path, String sha, TreeSet<CanIdKey> ids) {
    }

    private record ParsedDbc(DbcDatabase database, boolean utf8, String charset, String detail) {
    }
}
