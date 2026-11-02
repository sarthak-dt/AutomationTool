package com.stellar.platform.desktop.processing;

import com.stellar.platform.dbc.decode.DecodingResult;
import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.model.DbcByteOrder;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcRawEncoding;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.model.DbcValueType;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.parser.mf4.Mf4Inspector;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraph;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraphDiscovery;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.report.MdfInspectionReport;
import com.stellar.platform.parser.mf4.schema.MdfCnMetadata;
import com.stellar.platform.parser.mf4.schema.MdfMetadataSummary;
import com.stellar.platform.parser.mf4.schema.MdfSchemaExtractor;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.parser.spi.ReaderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read-only N597 definition and recording check. StellarAI output is compared with a second
 * implementation of the Vector bit and scale rules. That is not an external MF4 reference.
 */
class N597PhysicalDecodeExperimentTest {

    private static final Path DBC = Path.of(
            "C:\\Users\\DT - User\\Downloads\\DriveTechData\\MF4Files\\City - Highway_MF4\\Required DBC\\N597_ABSEMS_CAN dbc _Rev7 (1).dbc");
    private static final Path DIR = Path.of(
            "C:\\Users\\DT - User\\Downloads\\DriveTechData\\MF4Files\\City - Highway_MF4");
    private static final Path EVIDENCE = Path.of(
            "D:\\DesktopApp\\DriveT\\docs\\qa-handoff\\n597-physical-decoding.txt");

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void compareN597PayloadArithmeticWithStellarOutput() throws Exception {
        assertTrue(Files.isRegularFile(DBC));
        List<Path> recordings = Files.list(DIR)
                .filter(path -> path.getFileName().toString().startsWith("N597_AFT_")
                        && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mf4"))
                .sorted()
                .toList();
        assertEquals(4, recordings.size());
        Map<Path, String> before = new LinkedHashMap<>();
        before.put(DBC, sha256(DBC));
        for (Path recording : recordings) {
            before.put(recording, sha256(recording));
        }
        List<String> lines = new ArrayList<>();
        lines.add("started=" + Instant.now());
        lines.add("dbc=" + DBC);
        lines.add("dbcSha256=" + before.get(DBC));
        lines.add("encoding=Not UTF-8. No BOM. Eleven bytes above 0x7F: ten 0xB0 and one 0xB5. None in 0x80-0x9F. ISO-8859-1 and Windows-1252 decode those two bytes to U+00B0 and U+00B5. Character identity is shared. The charset name is not uniquely one of the two labels.");
        lines.add("independentDbcTool=NONE. No cantools, asammdf, CANoe, or CANalyzer installation was found.");
        lines.add("evidenceLevel=LEVEL_2_PENDING");
        try {
            DbcDatabase database = new DbcParser().parse(DBC);
            lines.add("messages=" + database.messages().size());
            assertEquals(28, database.messages().size());
            Mf4Inspector inspector = new Mf4Inspector();
            Path selected = null;
            long selectedSize = Long.MAX_VALUE;
            for (Path recording : recordings) {
                MdfInspectionReport report = inspector.inspect(recording);
                List<String> gps = gpsNames(recording);
                lines.add("recording=" + recording.getFileName()
                        + " sha256=" + before.get(recording)
                        + " size=" + Files.size(recording)
                        + " version=" + report.mdfVersion()
                        + " finalization=" + report.finalizationStatus()
                        + " dg=" + report.dgCount()
                        + " cg=" + report.cgCount()
                        + " cn=" + report.cnCount()
                        + " declaredCycles=" + report.declaredCycleCountTotal()
                        + " possibleRecords=" + report.possibleRecordCount()
                        + " physicalBytes=" + report.physicalDataRegionLength()
                        + " gpsChannels=" + gps
                        + " reasons=" + report.finalizationReasons());
                long size = Files.size(recording);
                if (size < selectedSize) {
                    selectedSize = size;
                    selected = recording;
                }
            }
            lines.add("selected=" + selected.getFileName()
                    + " reason=Smallest of the four recordings. All four are MDF 4.11 and INCONSISTENT. This selection does not make the file finalized.");

            List<Wanted> wanted = selectSignals(database);
            for (Wanted want : wanted) {
                lines.add("wanted kind=" + want.kind
                        + (want.signal == null
                        ? " ABSENT"
                        : " message=" + want.message.name()
                        + " id=" + want.message.canId().canId()
                        + " extended=" + want.message.canId().extended()
                        + " signal=" + want.signal.name()
                        + " start=" + want.signal.startBit()
                        + " length=" + want.signal.bitLength()
                        + " order=" + want.signal.byteOrder()
                        + " signed=" + want.signal.valueType()
                        + " factor=" + want.signal.factor()
                        + " offset=" + want.signal.offset()
                        + " unit=" + want.signal.unit()));
            }

            Map<Long, CanonicalCanFrame> first = new LinkedHashMap<>();
            Map<String, CanonicalCanFrame> exercised = new LinkedHashMap<>();
            TreeSet<Long> observed = new TreeSet<>();
            AtomicLong frames = new AtomicLong();
            AtomicLong previous = new AtomicLong(Long.MIN_VALUE);
            AtomicLong firstTs = new AtomicLong(Long.MIN_VALUE);
            AtomicLong lastTs = new AtomicLong();
            AtomicLong decreases = new AtomicLong();
            new MdfDynamicRecordDecoder().decode(
                    selected,
                    ReaderContext.ofBatchSize(512),
                    batch -> {
                        for (CanonicalCanFrame frame : batch) {
                            frames.incrementAndGet();
                            observed.add(frame.canId());
                            long ts = frame.timestampNanos();
                            if (firstTs.get() == Long.MIN_VALUE) {
                                firstTs.set(ts);
                            }
                            if (previous.get() != Long.MIN_VALUE && ts < previous.get()) {
                                decreases.incrementAndGet();
                            }
                            previous.set(ts);
                            lastTs.set(ts);
                            first.putIfAbsent(frame.canId(), frame);
                            for (Wanted want : wanted) {
                                if (want.signal == null || frame.canId() != want.message.canId().canId()
                                        || exercised.containsKey(want.kind)) {
                                    continue;
                                }
                                if (frame.payloadLength() <= want.signal.startBit() / 8) {
                                    continue;
                                }
                                long raw = independentRaw(frame.data(), want.signal);
                                boolean useful = switch (want.kind) {
                                    case "nontrivial-scale", "nonzero-offset" -> raw != 0L;
                                    case "signed" -> raw < 0L;
                                    default -> true;
                                };
                                if (useful) {
                                    exercised.put(want.kind, frame);
                                }
                            }
                        }
                    },
                    warning -> {
                    }
            );
            int matched = 0;
            for (DbcMessage message : database.messages()) {
                if (observed.contains(message.canId().canId())) {
                    matched++;
                }
            }
            List<String> signedSeen = new ArrayList<>();
            for (DbcMessage message : database.messages()) {
                if (!observed.contains(message.canId().canId())) {
                    continue;
                }
                for (DbcSignal signal : message.signals()) {
                    if (signal.valueType() == DbcValueType.SIGNED) {
                        signedSeen.add(message.canId().canId() + ":" + signal.name());
                    }
                }
            }
            lines.add("signedSignalsOnObservedIds=" + (signedSeen.isEmpty() ? "NONE" : signedSeen));
            lines.add("selectedFrames=" + frames.get()
                    + " observedIds=" + observed.size()
                    + " dbcMessagesMatched=" + matched + "/28"
                    + " firstTimestampNanos=" + firstTs.get()
                    + " lastTimestampNanos=" + lastTs.get()
                    + " timestampDecreases=" + decreases.get());
            assertEquals(21, observed.size());
            assertEquals(21, matched);

            DefaultDbcDecoder decoder = new DefaultDbcDecoder();
            int compared = 0;
            for (Wanted want : wanted) {
                if (want.signal == null) {
                    continue;
                }
                CanonicalCanFrame frame = exercised.get(want.kind);
                if (frame == null) {
                    frame = first.get(want.message.canId().canId());
                    lines.add("compare kind=" + want.kind + (frame == null
                            ? " NO_OBSERVED_FRAME"
                            : " NO_EXERCISING_SAMPLE firstFrameOnly=true"));
                    if (frame == null) {
                        continue;
                    }
                }
                byte[] payload = frame.data();
                long independentRaw = independentRaw(payload, want.signal);
                double independentPhysical = independentRaw * want.signal.factor() + want.signal.offset();
                DecodingResult decoded = decoder.decode(frame, database);
                CanonicalSignal stellar = decoded.signals().stream()
                        .filter(signal -> signal.signalName().equals(want.signal.name()))
                        .findFirst()
                        .orElse(null);
                String payloadHex = HexFormat.of().formatHex(payload).toUpperCase(Locale.ROOT);
                if (stellar == null || stellar.rawValue() == null) {
                    lines.add("compare kind=" + want.kind
                            + " signal=" + want.signal.name()
                            + " payload=" + payloadHex
                            + " independentRaw=" + independentRaw
                            + " stellar=ABSENT status=" + decoded.status());
                    continue;
                }
                boolean rawMatch = stellar.rawValue() == independentRaw;
                boolean physicalMatch = Math.abs(stellar.physicalValue() - independentPhysical) <= 1e-6;
                boolean unitMatch = want.signal.unit().equals(stellar.unit());
                lines.add("compare kind=" + want.kind
                        + " signal=" + want.signal.name()
                        + " message=" + want.message.name()
                        + " id=" + frame.canId()
                        + " channel=" + frame.channel()
                        + " timestampNanos=" + frame.timestampNanos()
                        + " dlc=" + frame.dlc()
                        + " payload=" + payloadHex
                        + " independentRaw=" + independentRaw
                        + " stellarRaw=" + stellar.rawValue()
                        + " independentPhysical=" + independentPhysical
                        + " stellarPhysical=" + stellar.physicalValue()
                        + " unit=" + stellar.unit()
                        + " rawMatch=" + rawMatch
                        + " physicalMatch=" + physicalMatch
                        + " unitMatch=" + unitMatch
                        + " validity=" + stellar.validity());
                assertTrue(rawMatch, want.signal.name());
                assertTrue(physicalMatch, want.signal.name());
                assertTrue(unitMatch, want.signal.name());
                compared++;
            }
            lines.add("level2Comparisons=" + compared);
            lines.add("evidenceLevel=LEVEL_2");
            lines.add("level3=UNAVAILABLE");
            lines.add("MF4_PHYSICAL_ORACLE_MISSING");
            lines.add("javafx=NOT RUN. The recording is inconsistent and no external physical reference exists.");
            lines.add("decision=NO-GO");
            assertTrue(compared >= 1);
        } finally {
            for (Map.Entry<Path, String> entry : before.entrySet()) {
                assertEquals(entry.getValue(), sha256(entry.getKey()), entry.getKey().toString());
                lines.add("hashUnchanged file=" + entry.getKey().getFileName() + " true");
            }
            lines.add("finished=" + Instant.now());
            Files.createDirectories(EVIDENCE.getParent());
            Files.writeString(EVIDENCE, String.join(System.lineSeparator(), lines));
        }
        String text = Files.readString(EVIDENCE);
        assertTrue(text.contains("MF4_PHYSICAL_ORACLE_MISSING"));
        assertTrue(text.contains("evidenceLevel=LEVEL_2"));
    }

    private static List<Wanted> selectSignals(DbcDatabase database) {
        DbcSignal unsignedIntel = null;
        DbcMessage unsignedIntelMessage = null;
        DbcSignal scaled = null;
        DbcMessage scaledMessage = null;
        DbcSignal signed = null;
        DbcMessage signedMessage = null;
        DbcSignal motorola = null;
        DbcMessage motorolaMessage = null;
        DbcSignal offset = null;
        DbcMessage offsetMessage = null;
        List<DbcMessage> messages = new ArrayList<>(database.messages());
        messages.sort(Comparator.comparingLong(message -> message.canId().canId()));
        for (DbcMessage message : messages) {
            for (DbcSignal signal : message.signals()) {
                if (signal.multiplexRole() != DbcMultiplexRole.NONE
                        || signal.rawEncoding() != DbcRawEncoding.INTEGER) {
                    continue;
                }
                boolean nontrivial = signal.factor() != 1.0 || signal.offset() != 0.0;
                if (unsignedIntel == null && signal.byteOrder() == DbcByteOrder.INTEL
                        && signal.valueType() == DbcValueType.UNSIGNED) {
                    unsignedIntel = signal;
                    unsignedIntelMessage = message;
                }
                if (scaled == null && nontrivial) {
                    scaled = signal;
                    scaledMessage = message;
                }
                if (signed == null && signal.valueType() == DbcValueType.SIGNED) {
                    signed = signal;
                    signedMessage = message;
                }
                if (motorola == null && signal.byteOrder() == DbcByteOrder.MOTOROLA) {
                    motorola = signal;
                    motorolaMessage = message;
                }
                if (offset == null && signal.offset() != 0.0) {
                    offset = signal;
                    offsetMessage = message;
                }
            }
        }
        return List.of(
                new Wanted("unsigned-intel", unsignedIntelMessage, unsignedIntel),
                new Wanted("nontrivial-scale", scaledMessage, scaled),
                new Wanted("signed", signedMessage, signed),
                new Wanted("motorola", motorolaMessage, motorola),
                new Wanted("nonzero-offset", offsetMessage, offset));
    }

    private static long independentRaw(byte[] payload, DbcSignal signal) {
        long unsigned = signal.byteOrder() == DbcByteOrder.INTEL
                ? intel(payload, signal.startBit(), signal.bitLength())
                : motorola(payload, signal.startBit(), signal.bitLength());
        int length = signal.bitLength();
        long mask = length == 64 ? -1L : (1L << length) - 1L;
        long masked = unsigned & mask;
        if (signal.valueType() != DbcValueType.SIGNED || length == 64) {
            return masked;
        }
        long sign = 1L << (length - 1);
        if ((masked & sign) != 0) {
            return masked | ~mask;
        }
        return masked;
    }

    private static long intel(byte[] payload, int startBit, int bitLength) {
        long value = 0L;
        for (int i = 0; i < bitLength; i++) {
            int bitPos = startBit + i;
            int bit = (payload[bitPos / 8] >> (bitPos % 8)) & 1;
            value |= ((long) bit) << i;
        }
        return value;
    }

    private static long motorola(byte[] payload, int startBit, int bitLength) {
        long value = 0L;
        int bitPos = startBit;
        for (int i = 0; i < bitLength; i++) {
            int bit = (payload[bitPos / 8] >> (bitPos % 8)) & 1;
            value = (value << 1) | bit;
            bitPos = (bitPos % 8) == 0 ? bitPos + 15 : bitPos - 1;
        }
        return value;
    }

    private static List<String> gpsNames(Path file) throws Exception {
        List<String> names = new ArrayList<>();
        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            MdfBlockGraph graph = MdfBlockGraphDiscovery.discover(access, CancellationToken.NONE);
            MdfMetadataSummary meta = MdfSchemaExtractor.extract(access, graph, CancellationToken.NONE);
            for (MdfCnMetadata channel : meta.channels()) {
                String name = channel.name() == null ? "" : channel.name();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("gps") || lower.contains("latitude") || lower.contains("longitude")
                        || lower.contains("altitude") || lower.contains("gnss")) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static String sha256(Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        return HexFormat.of().formatHex(digest).toUpperCase(Locale.ROOT);
    }

    private record Wanted(String kind, DbcMessage message, DbcSignal signal) {
    }
}
