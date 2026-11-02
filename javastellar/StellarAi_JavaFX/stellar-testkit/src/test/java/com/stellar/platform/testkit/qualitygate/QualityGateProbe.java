package com.stellar.platform.testkit.qualitygate;

import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.decode.DecodeStatus;
import com.stellar.platform.dbc.decode.DecodingResult;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.parse.DbcDiagnosticCode;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.parser.mf4.decode.MdfDecodeDiagnostic;
import com.stellar.platform.parser.mf4.decode.MdfDecodeDiagnosticCode;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.mf4.dynamicschema.MdfBusKind;
import com.stellar.platform.parser.mf4.dynamicschema.MdfCanRole;
import com.stellar.platform.parser.mf4.dynamicschema.MdfRecordSchema;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.DiagnosticAccumulator;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.ProcessingResult;
import com.stellar.platform.processing.ProcessingStatistics;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Offline quality-gate probe for a real MF4 + DBC pair.
 * Not executed by Surefire by default (no {@code @Test}).
 */
public final class QualityGateProbe {

    public static void main(String[] args) throws Exception {
        Path mf4 = Path.of(args.length > 0 ? args[0]
                : "C:/Users/ADMIN/Desktop/DriveTech/OBD2 Data_Lavasa_0002260_20240305_102230.mf4");
        Path dbcPath = Path.of(args.length > 1 ? args[1]
                : "C:/Users/ADMIN/Desktop/DriveTech/11_bit_OBD2 2.dbc");

        System.out.println("=== MF4 decode (frames only) ===");
        MdfDynamicRecordDecoder.DecodeResult decoded =
                new MdfDynamicRecordDecoder().decodeAll(mf4, ReaderContext.ofBatchSize(512));
        List<CanonicalCanFrame> frames = decoded.frames();
        long valid = frames.stream().filter(f -> f.quality() == FrameQuality.VALID
                || f.quality() == FrameQuality.OK).count();
        long recovered = frames.stream().filter(f -> f.quality() == FrameQuality.RECOVERED).count();
        long standard = frames.stream().filter(f -> !f.extended()).count();
        long extended = frames.stream().filter(CanonicalCanFrame::extended).count();
        long fdByDlc = frames.stream().filter(f -> f.dlc() > 8 || f.data().length > 8).count();
        long classic = frames.size() - fdByDlc;

        Map<MdfDecodeDiagnosticCode, Long> diagCounts = decoded.diagnostics().stream()
                .collect(Collectors.groupingBy(MdfDecodeDiagnostic::code, Collectors.counting()));

        long canCgs = decoded.schemaResult().recordSchemas().stream()
                .filter(MdfRecordSchema::emitsCanonicalCanFrames).count();
        long nonCanCgs = decoded.schemaResult().recordSchemas().stream()
                .filter(s -> s.busKind() == MdfBusKind.LIN || s.busKind() == MdfBusKind.ERROR_STATUS
                        || !s.emitsCanonicalCanFrames())
                .count();

        System.out.println("totalFrames=" + frames.size());
        System.out.println("VALID/OK=" + valid + " RECOVERED=" + recovered);
        System.out.println("standard=" + standard + " extended=" + extended);
        System.out.println("canFdByDlcOrPayload=" + fdByDlc + " classic=" + classic);
        System.out.println("CAN_CGs=" + canCgs + " nonCAN_or_nonEmitting_CGs=" + nonCanCgs);
        System.out.println("decodeDiagnostics=" + diagCounts);

        Set<Long> seq = new HashSet<>();
        long dupSeq = 0;
        Map<Long, Integer> offsetHits = new HashMap<>();
        for (CanonicalCanFrame f : frames) {
            if (!seq.add(f.sequenceNumber())) {
                dupSeq++;
            }
            long off = f.source() == null ? -1L : f.source().sourceByteOffset();
            offsetHits.merge(off, 1, Integer::sum);
        }
        long overlappingOffsets = offsetHits.entrySet().stream()
                .filter(e -> e.getKey() >= 0 && e.getValue() > 1)
                .count();
        System.out.println("duplicateSequenceNumbers=" + dupSeq);
        System.out.println("sourceOffsetsWithMultipleFrames=" + overlappingOffsets);

        long id7e8 = frames.stream().filter(f -> f.canId() == 0x7E8L).count();
        System.out.println("framesWithCanId_0x7E8=" + id7e8);

        System.out.println("\n=== Timestamp channel schema ===");
        decoded.schemaResult().recordSchemas().stream()
                .filter(MdfRecordSchema::emitsCanonicalCanFrames)
                .forEach(s -> s.channelFields().stream()
                        .filter(f -> f.canRoleCandidate().role() == MdfCanRole.TIMESTAMP)
                        .forEach(f -> System.out.println("CG@" + s.channelGroupAddress()
                                + " name=" + f.name()
                                + " unit=[" + f.unit() + "]"
                                + " bits=" + f.bitLength()
                                + " dtype=" + f.dataTypeCode()
                                + " ccLink=" + f.conversionLink())));
        DbcDatabase db = new DbcParser().parse(dbcPath);
        long stdMsg = db.messages().stream().filter(m -> !m.extended()).count();
        long extMsg = db.messages().stream().filter(DbcMessage::extended).count();
        long muxMeta = db.messages().stream()
                .flatMap(m -> m.signals().stream())
                .filter(s -> s.multiplexRole() != DbcMultiplexRole.NONE)
                .count();
        long secondary = db.messages().stream()
                .flatMap(m -> m.signals().stream())
                .filter(DbcSignal::secondaryMultiplexor)
                .count();
        System.out.println("BO_messages=" + db.messages().size() + " standard=" + stdMsg + " extended=" + extMsg);
        System.out.println("signalsWithMuxMetadata=" + muxMeta + " secondaryMultiplexors=" + secondary);

        List<CanonicalSignal> allSignals = new ArrayList<>();
        ProcessingResult result = MeasurementDecodePipeline.createDefault().process(
                ParserRegistry.load().findReader(mf4).orElseThrow(),
                mf4,
                db,
                ReaderContext.ofBatchSize(512),
                allSignals::addAll
        );
        ProcessingStatistics stats = result.statistics();
        System.out.println("pipeline framesRead=" + stats.sourceFramesRead()
                + " valid=" + stats.validFrames()
                + " recovered=" + stats.recoveredFrames()
                + " standard=" + stats.standardFrames()
                + " extended=" + stats.extendedFrames()
                + " matched=" + stats.dbcMatchedFrames()
                + " unmatched=" + stats.dbcUnmatchedFrames()
                + " signals=" + stats.signalsEmitted()
                + " nonCanSkipped=" + stats.nonCanGroupsSkipped());
        System.out.println("outcome=" + result.outcome());
        System.out.println("aggregatedDiagnostics:");
        result.aggregatedDiagnostics().forEach(d ->
                System.out.println("  " + d.code() + " count=" + d.count() + " :: " + d.message()));

        Map<String, Long> signalNameCounts = allSignals.stream()
                .collect(Collectors.groupingBy(CanonicalSignal::signalName, TreeMap::new, Collectors.counting()));
        System.out.println("uniqueActiveSignalNames=" + signalNameCounts.size());
        System.out.println("activeNames=" + signalNameCounts.keySet());

        DefaultDbcDecoder decoder = new DefaultDbcDecoder();
        Map<Long, Long> unmatchedIds = new HashMap<>();
        Map<String, long[]> byPid = new LinkedHashMap<>(); // pidKey -> [frames, signals]
        Map<String, Integer> oorDefs = new LinkedHashMap<>();
        CanonicalCanFrame golden = null;
        CanonicalSignal goldenCoolant = null;

        for (CanonicalCanFrame f : frames) {
            DecodingResult dr = decoder.decode(f, db);
            if (dr.status() == DecodeStatus.UNMATCHED) {
                unmatchedIds.merge(f.canId(), 1L, Long::sum);
                continue;
            }
            if (f.canId() == 0x7E8L && bytesHex(f.data()).equalsIgnoreCase("0341057B00000000")) {
                golden = f;
                goldenCoolant = dr.signals().stream()
                        .filter(s -> s.signalName().contains("EngineCoolantTemp") || s.signalName().contains("Coolant"))
                        .findFirst().orElse(null);
            }
            Long pid = null;
            for (CanonicalSignal s : dr.signals()) {
                if (s.signalName().equals("S01PID") || s.signalName().equals("S02PID") || s.signalName().equals("Pid")) {
                    pid = s.rawValue();
                }
            }
            String key = "mux=" + (pid == null ? "?" : ("0x" + Long.toHexString(pid)));
            long[] acc = byPid.computeIfAbsent(key, k -> new long[2]);
            acc[0]++;
            acc[1] += dr.signals().size();
            for (var d : dr.diagnostics()) {
                if (d.code() == DbcDiagnosticCode.OUT_OF_RANGE_PHYSICAL_VALUE) {
                    oorDefs.merge(d.message(), 1, Integer::sum);
                }
            }
        }

        System.out.println("\n=== Multiplex branch summary (matched frames) ===");
        byPid.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.println(e.getKey()
                        + " frames=" + e.getValue()[0]
                        + " signalRows=" + e.getValue()[1]
                        + " avgSignalsPerFrame=" + String.format(Locale.ROOT, "%.3f",
                        e.getValue()[0] == 0 ? 0 : (double) e.getValue()[1] / e.getValue()[0])));

        System.out.println("\n=== Golden PID05 frame ===");
        if (golden == null) {
            System.out.println("not found");
        } else {
            System.out.println("tsNanos=" + golden.timestampNanos()
                    + " tsSec=" + (golden.timestampNanos() / 1_000_000_000.0)
                    + " payload=" + bytesHex(golden.data()));
            if (goldenCoolant == null) {
                System.out.println("coolant signal missing; signals="
                        + decoder.decode(golden, db).signals().stream()
                        .map(CanonicalSignal::signalName).toList());
            } else {
                System.out.println("coolant name=" + goldenCoolant.signalName()
                        + " raw=" + goldenCoolant.rawValue()
                        + " phys=" + goldenCoolant.physicalValue()
                        + " unit=" + goldenCoolant.unit());
            }
        }

        System.out.println("\n=== OUT_OF_RANGE unique messages (top 20) ===");
        oorDefs.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(20)
                .forEach(e -> System.out.println(e.getValue() + " :: " + e.getKey()));

        System.out.println("\n=== POC comparison targets ===");
        System.out.println("POC frames=373109 java=" + frames.size());
        System.out.println("POC matchedRows=12928 javaMatched=" + stats.dbcMatchedFrames()
                + " java0x7E8=" + id7e8);
        System.out.println("POC signalRows=39142 java=" + stats.signalsEmitted());
        System.out.println("POC active=30 java=" + signalNameCounts.size());

        System.out.println("\ntop20 unmatched CAN IDs:");
        unmatchedIds.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .limit(20)
                .forEach(e -> System.out.printf(Locale.ROOT, "  0x%X (%d) count=%d%n",
                        e.getKey(), e.getKey(), e.getValue()));

        System.out.println("\nUNSUPPORTED_DATA_TYPE count="
                + diagCounts.getOrDefault(MdfDecodeDiagnosticCode.UNSUPPORTED_DATA_TYPE, 0L));
        System.out.println("AMBIGUOUS_REQUIRED_CAN_FIELD count="
                + diagCounts.getOrDefault(MdfDecodeDiagnosticCode.AMBIGUOUS_REQUIRED_CAN_FIELD, 0L));
        System.out.println("highVolumeInformational(UNMATCHED)="
                + DiagnosticAccumulator.isHighVolumeInformational("UNMATCHED_CAN_ID"));
    }

    private static String bytesHex(byte[] data) {
        if (data == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format(Locale.ROOT, "%02X", b));
        }
        return sb.toString();
    }
}
