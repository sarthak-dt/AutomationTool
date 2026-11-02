package com.stellar.platform.testkit.ascvalidation;

import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.parser.asc.AscFileReader;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.ProcessingResult;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.processing.map.GpsSignalRoleResolver;
import com.stellar.platform.testkit.ascvalidation.IndependentAscScanner.AscInventory;
import com.stellar.platform.testkit.ascvalidation.IndependentAscScanner.RawFrame;
import com.stellar.platform.testkit.ascvalidation.IndependentDbcScanner.DbcInventory;
import com.stellar.platform.testkit.ascvalidation.IndependentDbcScanner.DbcMessageTruth;
import com.stellar.platform.testkit.ascvalidation.IndependentDbcScanner.DbcSignalTruth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Ground-truth ASC validation: independent inventory → parser frames → DBC decode → catalog.
 */
public final class AscDataValidator {

    public static final double PHYSICAL_TOLERANCE = 1e-9;

    private AscDataValidator() {
    }

    public static ValidationResult validate(Path ascPath, Path dbcPath, Path catalogRoot) throws Exception {
        Objects.requireNonNull(ascPath, "ascPath");
        AscInventory raw = IndependentAscScanner.scan(ascPath);
        DbcInventory dbc = dbcPath == null ? null : IndependentDbcScanner.scan(dbcPath);

        List<CanonicalCanFrame> parsed = new ArrayList<>();
        new AscFileReader().readFrames(ascPath, ReaderContext.ofBatchSize(64), parsed::addAll, w -> { });

        List<FrameMismatch> frameMismatches = compareFrames(raw.frames(), parsed);

        Coverage coverage = dbc == null ? Coverage.empty() : coverage(raw, dbc);
        List<ValueCheck> valueChecks = new ArrayList<>();
        if (dbc != null) {
            valueChecks.addAll(spotCheck(raw, dbc, parsed));
        }

        int decodedPhysicalSignals = 0;
        int catalogEntries = 0;
        boolean gpsDecoded = false;
        boolean gpsDiscovered = false;
        ProcessingResult processResult = null;
        if (dbcPath != null && Files.isRegularFile(dbcPath)) {
            DbcDatabase database = new DbcParser().parse(dbcPath);
            MeasurementDecodePipeline pipeline =
                    new MeasurementDecodePipeline(new ParserRegistry(List.of()), new DefaultDbcDecoder());
            try (GraphSignalSessionStore store = GraphSignalSessionStore.createPersistent(
                    catalogRoot, ascPath.getFileName().toString(), dbcPath.getFileName().toString())) {
                List<CanonicalSignal> emitted = new ArrayList<>();
                processResult = pipeline.process(new AscFileReader(), ascPath, database,
                        ReaderContext.ofBatchSize(64), batch -> {
                            emitted.addAll(batch);
                            store.accept(batch);
                        });
                store.checkpoint();
                decodedPhysicalSignals = (int) emitted.stream().map(s -> key(s)).distinct().count();
                catalogEntries = store.snapshot().entries().size();
                gpsDecoded = emitted.stream().anyMatch(s ->
                        IndependentDbcScanner.looksLikeGpsLatitude(s.signalName())
                                || IndependentDbcScanner.looksLikeGpsLongitude(s.signalName()));
                gpsDiscovered = GpsSignalRoleResolver.hasRoute(
                        new GpsSignalRoleResolver().resolve(store.snapshot().entries()));
                valueChecks.addAll(compareDecoded(raw, dbc, emitted));
            }
        }

        GpsClass gps = classifyGps(raw, dbc, gpsDecoded, gpsDiscovered);
        boolean sourceMatch = frameMismatches.isEmpty()
                && valueChecks.stream().allMatch(ValueCheck::pass);
        return new ValidationResult(
                raw,
                dbc,
                parsed.size(),
                coverage,
                decodedPhysicalSignals,
                catalogEntries,
                catalogEntries,
                catalogEntries,
                frameMismatches,
                valueChecks,
                gps,
                sourceMatch,
                processResult == null ? 0 : processResult.statistics().signalsEmitted()
        );
    }

    private static List<FrameMismatch> compareFrames(List<RawFrame> expected, List<CanonicalCanFrame> actual) {
        List<FrameMismatch> mismatches = new ArrayList<>();
        int n = Math.max(expected.size(), actual.size());
        for (int i = 0; i < n; i++) {
            if (i >= expected.size()) {
                mismatches.add(new FrameMismatch(i, "extra parsed frame", "", describe(actual.get(i))));
                continue;
            }
            if (i >= actual.size()) {
                mismatches.add(new FrameMismatch(i, "missing parsed frame", describe(expected.get(i)), ""));
                continue;
            }
            RawFrame exp = expected.get(i);
            CanonicalCanFrame got = actual.get(i);
            if (exp.timestampNanos() != got.timestampNanos()
                    || exp.channel() != got.channel()
                    || exp.canId() != got.canId()
                    || exp.extended() != got.extended()
                    || exp.dlc() != got.dlc()
                    || !java.util.Arrays.equals(exp.data(), got.data())
                    || !exp.direction().equals(got.direction().name())) {
                mismatches.add(new FrameMismatch(i, "field mismatch", describe(exp), describe(got)));
            }
        }
        return mismatches;
    }

    private static Coverage coverage(AscInventory raw, DbcInventory dbc) {
        Set<String> ascIds = new LinkedHashSet<>();
        for (RawFrame frame : raw.frames()) {
            ascIds.add(idKey(frame.canId(), frame.extended()));
        }
        Set<String> dbcIds = new LinkedHashSet<>();
        for (DbcMessageTruth message : dbc.messages()) {
            dbcIds.add(idKey(message.canId(), message.extended()));
        }
        Set<String> matched = new LinkedHashSet<>(ascIds);
        matched.retainAll(dbcIds);
        Set<String> unmatchedAsc = new LinkedHashSet<>(ascIds);
        unmatchedAsc.removeAll(dbcIds);
        Set<String> unusedDbc = new LinkedHashSet<>(dbcIds);
        unusedDbc.removeAll(ascIds);
        int definedInMatched = 0;
        for (DbcMessageTruth message : dbc.messages()) {
            if (matched.contains(idKey(message.canId(), message.extended()))) {
                definedInMatched += message.signals().size();
            }
        }
        return new Coverage(ascIds, dbcIds, matched, unmatchedAsc, unusedDbc, definedInMatched);
    }

    private static List<ValueCheck> spotCheck(AscInventory raw, DbcInventory dbc, List<CanonicalCanFrame> parsed) {
        List<ValueCheck> checks = new ArrayList<>();
        for (var stats : raw.perId()) {
            DbcMessageTruth message = dbc.find(stats.canId(), stats.extended());
            if (message == null) {
                continue;
            }
            for (String which : List.of("first", "middle", "last")) {
                RawFrame rawFrame = raw.representative(stats.channel(), stats.canId(), stats.extended(), which);
                if (rawFrame == null) {
                    continue;
                }
                CanonicalCanFrame parsedFrame = parsed.stream()
                        .filter(f -> f.timestampNanos() == rawFrame.timestampNanos()
                                && f.channel() == rawFrame.channel()
                                && f.canId() == rawFrame.canId())
                        .findFirst()
                        .orElse(null);
                if (parsedFrame == null) {
                    checks.add(new ValueCheck("FRAME", rawFrame.timestampNanos(), rawFrame.payloadHex(),
                            "canonical present", "missing", false));
                    continue;
                }
                checks.add(new ValueCheck(
                        "CAN 0x" + Long.toHexString(rawFrame.canId()).toUpperCase(Locale.ROOT) + " " + which,
                        rawFrame.timestampNanos(),
                        rawFrame.payloadHex(),
                        rawFrame.payloadHex(),
                        hex(parsedFrame.data()),
                        java.util.Arrays.equals(rawFrame.data(), parsedFrame.data())
                ));
            }
        }
        return checks;
    }

    private static List<ValueCheck> compareDecoded(
            AscInventory raw,
            DbcInventory dbc,
            List<CanonicalSignal> decoded
    ) {
        List<ValueCheck> checks = new ArrayList<>();
        for (RawFrame frame : raw.frames()) {
            DbcMessageTruth message = dbc.find(frame.canId(), frame.extended());
            if (message == null) {
                continue;
            }
            for (DbcSignalTruth signal : message.signals()) {
                if (!signal.intel()) {
                    continue;
                }
                long expectedRaw = signal.signed()
                        ? IndependentIntelDecoder.extractSigned(frame.data(), signal.startBit(), signal.bitLength())
                        : IndependentIntelDecoder.extractUnsigned(frame.data(), signal.startBit(), signal.bitLength());
                double expectedPhysical = IndependentIntelDecoder.toPhysical(expectedRaw, signal.factor(), signal.offset());
                CanonicalSignal got = decoded.stream()
                        .filter(s -> s.signalName().equals(signal.name())
                                && s.canId() == frame.canId()
                                && s.channel() == frame.channel()
                                && s.timestampNanos() == frame.timestampNanos())
                        .findFirst()
                        .orElse(null);
                if (got == null) {
                    checks.add(new ValueCheck(signal.name(), frame.timestampNanos(), frame.payloadHex(),
                            String.valueOf(expectedPhysical), "MISSING", false));
                    continue;
                }
                boolean pass = got.rawValue() != null && got.rawValue() == expectedRaw
                        && Math.abs(got.physicalValue() - expectedPhysical) <= PHYSICAL_TOLERANCE;
                checks.add(new ValueCheck(
                        signal.name(),
                        frame.timestampNanos(),
                        frame.payloadHex(),
                        expectedPhysical + " (raw " + expectedRaw + ")",
                        got.physicalValue() + " (raw " + got.rawValue() + ")",
                        pass
                ));
            }
        }
        return checks;
    }

    private static GpsClass classifyGps(
            AscInventory raw,
            DbcInventory dbc,
            boolean decoded,
            boolean discovered
    ) {
        boolean dbcGps = false;
        Set<String> gpsMessageKeys = new LinkedHashSet<>();
        if (dbc != null) {
            for (DbcMessageTruth message : dbc.messages()) {
                boolean lat = message.signals().stream().anyMatch(s -> IndependentDbcScanner.looksLikeGpsLatitude(s.name()));
                boolean lon = message.signals().stream().anyMatch(s -> IndependentDbcScanner.looksLikeGpsLongitude(s.name()));
                if (lat || lon) {
                    dbcGps = true;
                    gpsMessageKeys.add(idKey(message.canId(), message.extended()));
                }
            }
        }
        boolean frames = raw.frames().stream()
                .anyMatch(f -> gpsMessageKeys.contains(idKey(f.canId(), f.extended())));
        if (!dbcGps) {
            return GpsClass.GPS_NOT_PRESENT_IN_SOURCE;
        }
        if (!frames) {
            return GpsClass.GPS_DEFINED_IN_DBC_BUT_NO_MATCHING_ASC_FRAMES;
        }
        if (!decoded) {
            return GpsClass.GPS_FRAMES_PRESENT_BUT_NOT_DECODED;
        }
        if (!discovered) {
            return GpsClass.GPS_DECODED_BUT_NOT_DISCOVERED_BY_STELLARAI;
        }
        return GpsClass.GPS_DISCOVERED_AND_VALID;
    }

    private static String idKey(long canId, boolean extended) {
        return (extended ? "E:" : "S:") + Long.toHexString(canId).toUpperCase(Locale.ROOT);
    }

    private static String key(CanonicalSignal signal) {
        return signal.signalName() + "|" + signal.canId() + "|" + signal.channel();
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.ROOT, "%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    private static String describe(RawFrame frame) {
        return "t=" + frame.timestampNanos() + " ch=" + frame.channel()
                + " id=0x" + Long.toHexString(frame.canId())
                + (frame.extended() ? "x" : "")
                + " " + frame.direction() + " dlc=" + frame.dlc()
                + " " + frame.payloadHex();
    }

    private static String describe(CanonicalCanFrame frame) {
        return "t=" + frame.timestampNanos() + " ch=" + frame.channel()
                + " id=0x" + Long.toHexString(frame.canId())
                + (frame.extended() ? "x" : "")
                + " " + frame.direction() + " dlc=" + frame.dlc()
                + " " + hex(frame.data());
    }

    public enum GpsClass {
        GPS_NOT_PRESENT_IN_SOURCE,
        GPS_DEFINED_IN_DBC_BUT_NO_MATCHING_ASC_FRAMES,
        GPS_FRAMES_PRESENT_BUT_NOT_DECODED,
        GPS_DECODED_BUT_NOT_DISCOVERED_BY_STELLARAI,
        GPS_DISCOVERED_AND_VALID
    }

    public record Coverage(
            Set<String> ascIds,
            Set<String> dbcIds,
            Set<String> matched,
            Set<String> unmatchedAsc,
            Set<String> unusedDbc,
            int signalsDefinedInMatched
    ) {
        static Coverage empty() {
            return new Coverage(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), 0);
        }
    }

    public record FrameMismatch(int index, String reason, String expected, String actual) {
    }

    public record ValueCheck(
            String signal,
            long timestampNanos,
            String rawPayload,
            String expected,
            String stellar,
            boolean pass
    ) {
    }

    public record ValidationResult(
            AscInventory raw,
            DbcInventory dbc,
            int parsedFrames,
            Coverage coverage,
            int decodedPhysicalSignals,
            int catalogEntries,
            int uiPhysicalEntries,
            int uiDisplayedEntries,
            List<FrameMismatch> frameMismatches,
            List<ValueCheck> valueChecks,
            GpsClass gps,
            boolean sourceDataMatch,
            long signalsEmitted
    ) {
        public String formatReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("ASC DATA VALIDATION\n===================\n\n");
            sb.append("File:\n").append(raw.fileName()).append(" (").append(raw.fileSize()).append(" bytes)\n\n");
            sb.append("RAW SOURCE\n----------\n");
            sb.append("Frames: ").append(raw.validFrames()).append('\n');
            sb.append("Unique CAN IDs: ").append(raw.uniqueCanIds().size()).append(' ')
                    .append(raw.uniqueCanIds().stream().map(id -> "0x" + Long.toHexString(id)).toList())
                    .append('\n');
            sb.append("Channels: ").append(raw.channels()).append('\n');
            sb.append("Duration: ").append(raw.durationNanos()).append(" ns\n");
            sb.append("Header/date: ").append(raw.dateHeader().isBlank() ? "(absent)" : raw.dateHeader()).append('\n');
            sb.append("Timestamp mode: ").append(raw.timestampMode()).append("  base=").append(raw.base()).append('\n');
            sb.append("First timestamp: ").append(raw.firstTimestampNanos()).append(" ns\n");
            sb.append("Last timestamp: ").append(raw.lastTimestampNanos()).append(" ns\n");
            sb.append("Standard/Extended: ").append(raw.standardCount()).append('/').append(raw.extendedCount()).append('\n');
            sb.append("Rx/Tx: ").append(raw.rxCount()).append('/').append(raw.txCount()).append('\n');
            sb.append("Malformed/skipped: ").append(raw.malformed()).append('/').append(raw.skipped()).append('\n');
            sb.append("DLC distribution: ").append(raw.dlcDistribution()).append('\n');
            sb.append("Per-ID:\n");
            for (var id : raw.perId()) {
                sb.append("  ch=").append(id.channel())
                        .append(" 0x").append(Long.toHexString(id.canId()))
                        .append(id.extended() ? " EXT" : " STD")
                        .append(" count=").append(id.frameCount())
                        .append(" first=").append(id.firstTimestampNanos())
                        .append(" last=").append(id.lastTimestampNanos())
                        .append(" dlc=").append(id.dlcs())
                        .append('\n');
            }
            sb.append('\n');
            sb.append("DBC\n---\n");
            if (dbc == null) {
                sb.append("Messages: (no DBC supplied)\nSignals: (no DBC supplied)\n");
            } else {
                sb.append("Messages: ").append(dbc.messageCount()).append('\n');
                sb.append("Signals: ").append(dbc.signalCount()).append('\n');
                sb.append("Matched CAN IDs: ").append(coverage.matched()).append('\n');
                sb.append("Unmatched ASC CAN IDs: ").append(coverage.unmatchedAsc()).append('\n');
                sb.append("DBC IDs never observed: ").append(coverage.unusedDbc()).append('\n');
            }
            sb.append('\n');
            sb.append("STELLARAI\n---------\n");
            sb.append("Canonical frames: ").append(parsedFrames).append('\n');
            sb.append("Decoded physical signals: ").append(decodedPhysicalSignals).append('\n');
            sb.append("Catalog entries: ").append(catalogEntries).append('\n');
            sb.append("UI entries: ").append(uiDisplayedEntries)
                    .append(" (default Signals browser = all physical instances; live JavaFX not run)\n\n");
            sb.append("RECONCILIATION\n--------------\n");
            sb.append(String.format(Locale.ROOT, "%-38s %s%n", "Stage", "Count"));
            sb.append("------------------------------------------------\n");
            row(sb, "ASC CAN frames", raw.validFrames());
            row(sb, "ASC unique CAN IDs", raw.uniqueCanIds().size());
            row(sb, "DBC messages", dbc == null ? 0 : dbc.messageCount());
            row(sb, "ASC ↔ DBC matched message IDs", coverage.matched().size());
            row(sb, "Signals defined in matched msgs", coverage.signalsDefinedInMatched());
            row(sb, "Signals decoded with samples", decodedPhysicalSignals);
            row(sb, "Persisted signal catalog entries", catalogEntries);
            row(sb, "Signals UI physical entries", uiPhysicalEntries);
            row(sb, "Signals UI displayed entries", uiDisplayedEntries);
            sb.append("Frame mismatches: ").append(frameMismatches.size()).append('\n');
            sb.append('\n');
            sb.append("VALUE SPOT CHECK\n----------------\n");
            sb.append("Signal | Timestamp | Raw Payload | Expected | StellarAI | Result\n");
            int shown = 0;
            for (ValueCheck check : valueChecks) {
                if (shown++ > 40) {
                    sb.append("… truncated after 40 rows\n");
                    break;
                }
                sb.append(check.signal()).append(" | ").append(check.timestampNanos())
                        .append(" | ").append(check.rawPayload())
                        .append(" | ").append(check.expected())
                        .append(" | ").append(check.stellar())
                        .append(" | ").append(check.pass() ? "PASS" : "FAIL")
                        .append('\n');
            }
            sb.append('\n');
            sb.append("GPS\n---\n");
            boolean dbcGps = dbc != null && dbc.messages().stream().anyMatch(m ->
                    m.signals().stream().anyMatch(s ->
                            IndependentDbcScanner.looksLikeGpsLatitude(s.name())
                                    || IndependentDbcScanner.looksLikeGpsLongitude(s.name())));
            sb.append("Source GPS present: ").append(dbcGps ? "YES" : "NO").append('\n');
            sb.append("DBC GPS definition: ").append(dbcGps ? "YES" : "NO").append('\n');
            sb.append("GPS frames observed: ").append(
                    gps == GpsClass.GPS_DEFINED_IN_DBC_BUT_NO_MATCHING_ASC_FRAMES
                            || gps == GpsClass.GPS_NOT_PRESENT_IN_SOURCE ? "NO" : (dbcGps ? "YES" : "NO")).append('\n');
            sb.append("GPS decoded: ").append(gps == GpsClass.GPS_DISCOVERED_AND_VALID
                    || gps == GpsClass.GPS_DECODED_BUT_NOT_DISCOVERED_BY_STELLARAI ? "YES" : "NO").append('\n');
            sb.append("GPS discovered: ").append(gps == GpsClass.GPS_DISCOVERED_AND_VALID ? "YES" : "NO").append('\n');
            sb.append("Classification:\n").append(gps).append("\n\n");
            sb.append("VERDICT\n-------\nSOURCE DATA MATCH = ").append(sourceDataMatch ? "PASS" : "FAIL").append('\n');
            return sb.toString();
        }

        private static void row(StringBuilder sb, String stage, long count) {
            sb.append(String.format(Locale.ROOT, "%-38s %d%n", stage, count));
        }
    }
}
