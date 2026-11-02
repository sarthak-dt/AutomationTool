package com.stellar.platform.testkit.qualitygate;

import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.decode.DecodeStatus;
import com.stellar.platform.dbc.decode.DecodingResult;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.mf4.dynamicschema.MdfBusKind;
import com.stellar.platform.parser.mf4.dynamicschema.MdfCanRole;
import com.stellar.platform.parser.mf4.dynamicschema.MdfChannelFieldSchema;
import com.stellar.platform.parser.mf4.dynamicschema.MdfRecordSchema;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraph;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraphDiscovery;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.schema.MdfCnMetadata;
import com.stellar.platform.parser.mf4.schema.MdfMetadataSummary;
import com.stellar.platform.parser.mf4.schema.MdfSchemaExtractor;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.ProcessingResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Offline provenance probe: DBC inventory + MF4 channel inventory + match/emit analysis.
 * Not executed by Surefire.
 */
public final class MissingSignalProvenanceProbe {

    private static final Pattern CANDIDATE = Pattern.compile(
            "(?i).*(vehicle.?speed|veh.?spd|speed|odometer|odo|kilometer|km).*");

    public static void main(String[] args) throws Exception {
        Path mf4 = Path.of(args[0]);
        Path dbcPath = Path.of(args[1]);
        System.out.println("MF4=" + mf4);
        System.out.println("DBC=" + dbcPath);

        inventoryDbc(dbcPath);
        inventoryMf4(mf4);
        analyzeOverlap(mf4, dbcPath);
    }

    private static void inventoryDbc(Path dbcPath) throws Exception {
        DbcDatabase db = new DbcParser().parse(dbcPath);
        System.out.println("\n=== DBC INVENTORY ===");
        System.out.println("messages=" + db.messages().size());
        long signals = db.messages().stream().mapToLong(m -> m.signals().size()).sum();
        System.out.println("signals=" + signals);
        long std = db.messages().stream().filter(m -> !m.extended()).count();
        long ext = db.messages().stream().filter(DbcMessage::extended).count();
        System.out.println("standardMsgs=" + std + " extendedMsgs=" + ext);
        System.out.println("CAN_IDs=" + db.messages().stream()
                .map(m -> String.format(Locale.ROOT, "0x%X/%s", m.numericId(), m.extended() ? "EXT" : "STD"))
                .sorted()
                .toList());

        long muxMsgs = db.messages().stream().filter(m -> m.multiplexor().isPresent()).count();
        long muxors = db.messages().stream().flatMap(m -> m.signals().stream())
                .filter(s -> s.multiplexRole() == DbcMultiplexRole.MULTIPLEXOR).count();
        long muxed = db.messages().stream().flatMap(m -> m.signals().stream())
                .filter(s -> s.multiplexRole() == DbcMultiplexRole.MULTIPLEXED).count();
        long secondary = db.messages().stream().flatMap(m -> m.signals().stream())
                .filter(DbcSignal::secondaryMultiplexor).count();
        System.out.println("multiplexedMessages=" + muxMsgs
                + " multiplexors=" + muxors
                + " multiplexedSignals=" + muxed
                + " secondaryMuxors=" + secondary);

        System.out.println("\n=== DBC candidate signals (semantic name match) ===");
        for (DbcMessage m : db.messages()) {
            for (DbcSignal s : m.signals()) {
                if (!CANDIDATE.matcher(s.name()).matches() && !CANDIDATE.matcher(m.name()).matches()) {
                    // still include exact known OBD-style if unit suggests
                    if (!(s.unit() != null && (s.unit().equalsIgnoreCase("km/h") || s.unit().equalsIgnoreCase("km")))) {
                        continue;
                    }
                }
                System.out.printf(Locale.ROOT,
                        "msg=%s id=0x%X ext=%s sig=%s start=%d len=%d order=%s signed=%s factor=%s offset=%s unit=%s mux=%s muxVal=%s secondary=%s%n",
                        m.name(), m.numericId(), m.extended(), s.name(), s.startBit(), s.bitLength(),
                        s.byteOrder(), s.valueType(), s.factor(), s.offset(), s.unit(),
                        s.multiplexRole(), s.multiplexValue(), s.secondaryMultiplexor());
            }
        }
    }

    private static void inventoryMf4(Path mf4) throws Exception {
        System.out.println("\n=== MF4 CHANNEL METADATA ===");
        try (MdfFileAccess access = MdfFileAccess.open(mf4)) {
            MdfBlockGraph graph = MdfBlockGraphDiscovery.discover(access, CancellationToken.NONE);
            MdfMetadataSummary meta = MdfSchemaExtractor.extract(access, graph, CancellationToken.NONE);
            System.out.println("DGs=" + meta.dataGroups().size()
                    + " CGs=" + meta.channelGroups().size()
                    + " CNs=" + meta.channels().size());

            Map<String, Long> byClass = new TreeMap<>();
            List<MdfCnMetadata> candidates = new ArrayList<>();
            for (MdfCnMetadata cn : meta.channels()) {
                String cls = classifyCn(cn);
                byClass.merge(cls, 1L, Long::sum);
                if (CANDIDATE.matcher(cn.name()).matches()
                        || (cn.unit() != null && (cn.unit().toLowerCase(Locale.ROOT).contains("km")))) {
                    candidates.add(cn);
                }
            }
            System.out.println("channelClassCounts=" + byClass);

            System.out.println("\n=== MF4 candidate CN names ===");
            if (candidates.isEmpty()) {
                System.out.println("(none matching VehicleSpeed/Odometer/Kilometer/km semantic pattern)");
            } else {
                for (MdfCnMetadata cn : candidates) {
                    System.out.printf(Locale.ROOT,
                            "name=%s cg=0x%X type=%d sync=%d dtype=%d unit=[%s] ccLink=%d parent=%d composition=%d busFlag=%s class=%s%n",
                            cn.name(), cn.parentCgAddress(), cn.channelType(), cn.syncType(), cn.dataType(),
                            cn.unit(), cn.conversionLink(), cn.parentChannelAddress(), cn.compositionLink(),
                            cn.busEventFlag(), classifyCn(cn));
                }
            }

            // Sample of non-CAN native measurement leaves (not bus-event members)
            System.out.println("\n=== Sample native/non-bus CN (up to 40) ===");
            meta.channels().stream()
                    .filter(cn -> !cn.busEventFlag() && !cn.compositionMember() && cn.channelType() != 2
                            && cn.channelType() != 4)
                    .filter(cn -> cn.bitCount() > 0 && cn.bitCount() <= 64)
                    .limit(40)
                    .forEach(cn -> System.out.printf(Locale.ROOT,
                            "  %s unit=[%s] dtype=%d bits=%d cc=%d class=%s%n",
                            cn.name(), cn.unit(), cn.dataType(), cn.bitCount(), cn.conversionLink(), classifyCn(cn)));
        }

        MdfDynamicRecordDecoder.DecodeResult decoded =
                new MdfDynamicRecordDecoder().decodeAll(mf4, ReaderContext.ofBatchSize(256));
        System.out.println("\n=== Runtime schema bus kinds ===");
        Map<MdfBusKind, Long> kinds = decoded.schemaResult().recordSchemas().stream()
                .collect(Collectors.groupingBy(MdfRecordSchema::busKind, Collectors.counting()));
        System.out.println(kinds);
        long emitting = decoded.schemaResult().recordSchemas().stream()
                .filter(MdfRecordSchema::emitsCanonicalCanFrames).count();
        System.out.println("schemasEmittingCanFrames=" + emitting
                + " totalSchemas=" + decoded.schemaResult().recordSchemas().size());

        // What roles are consumed today
        Set<String> consumedRoles = new HashSet<>();
        for (MdfRecordSchema s : decoded.schemaResult().recordSchemas()) {
            if (!s.emitsCanonicalCanFrames()) {
                continue;
            }
            for (MdfChannelFieldSchema f : s.channelFields()) {
                if (f.canRoleCandidate().role() != MdfCanRole.NONE) {
                    consumedRoles.add(f.name() + "->" + f.canRoleCandidate().role());
                }
            }
        }
        System.out.println("consumedCanFieldExamples(count=" + consumedRoles.size() + ")");
    }

    private static String classifyCn(MdfCnMetadata cn) {
        String n = cn.name() == null ? "" : cn.name().toLowerCase(Locale.ROOT);
        if (cn.channelType() == 2 || cn.channelType() == 4 || n.contains("time") || n.contains("t_stamp")) {
            return "MASTER_TIME";
        }
        if (cn.busEventFlag() || n.contains("can_dataframe") || n.contains("can.")) {
            return "CAN_BUS_EVENT";
        }
        if (cn.compositionMember() || cn.hasCompositionChildren()) {
            return "COMPOSITE";
        }
        if (n.contains("lin")) {
            return "LIN";
        }
        if (n.contains("error") || n.contains("status") || n.contains("checksum")) {
            return "STATUS_ERROR";
        }
        if (cn.bitCount() > 0 && cn.dataType() <= 9) {
            return "NATIVE_MEASUREMENT";
        }
        return "OTHER";
    }

    private static void analyzeOverlap(Path mf4, Path dbcPath) throws Exception {
        System.out.println("\n=== DECODE + MATCH ANALYSIS ===");
        DbcDatabase db = new DbcParser().parse(dbcPath);
        MdfDynamicRecordDecoder.DecodeResult decoded =
                new MdfDynamicRecordDecoder().decodeAll(mf4, ReaderContext.ofBatchSize(512));
        List<CanonicalCanFrame> frames = decoded.frames();
        System.out.println("frames=" + frames.size());

        Map<Long, Long> idCounts = new HashMap<>();
        for (CanonicalCanFrame f : frames) {
            idCounts.merge(f.canId(), 1L, Long::sum);
        }
        Set<Long> dbcIds = db.messages().stream().map(DbcMessage::numericId).collect(Collectors.toSet());
        Set<Long> intersection = idCounts.keySet().stream().filter(dbcIds::contains).collect(Collectors.toSet());
        System.out.println("uniqueMf4Ids=" + idCounts.size() + " dbcIds=" + dbcIds.size()
                + " intersection=" + intersection.stream()
                .map(id -> String.format(Locale.ROOT, "0x%X", id)).sorted().toList());

        DefaultDbcDecoder decoder = new DefaultDbcDecoder();
        Map<String, Long> emitted = new TreeMap<>();
        Map<Integer, Long> pidFrames = new TreeMap<>();
        Map<String, long[]> candidateTrace = new LinkedHashMap<>();

        List<DbcSignal> candidates = db.messages().stream()
                .flatMap(m -> m.signals().stream().map(s -> s))
                .filter(s -> CANDIDATE.matcher(s.name()).matches()
                        || "km/h".equalsIgnoreCase(s.unit())
                        || "km".equalsIgnoreCase(s.unit()))
                .toList();

        for (CanonicalCanFrame f : frames) {
            DecodingResult dr = decoder.decode(f, db);
            if (dr.status() == DecodeStatus.UNMATCHED) {
                continue;
            }
            Long pid = null;
            for (CanonicalSignal s : dr.signals()) {
                emitted.merge(s.signalName(), 1L, Long::sum);
                if (s.signalName().equals("S01PID") || s.signalName().equals("S02PID")
                        || s.signalName().toLowerCase(Locale.ROOT).contains("parameterid")) {
                    pid = s.rawValue();
                }
            }
            if (pid != null) {
                pidFrames.merge(pid.intValue(), 1L, Long::sum);
            }
            for (DbcSignal cand : candidates) {
                boolean nameEmitted = dr.signals().stream().anyMatch(s -> s.signalName().equals(cand.name()));
                long[] acc = candidateTrace.computeIfAbsent(cand.name(), k -> new long[4]);
                // [matchedFramesForMsg, muxApplicableGuess, emitted, representativeRaw]
                acc[0]++;
                if (cand.multiplexRole() == DbcMultiplexRole.MULTIPLEXED && cand.multiplexValue() != null
                        && pid != null && pid.intValue() == cand.multiplexValue()) {
                    acc[1]++;
                }
                if (nameEmitted) {
                    acc[2]++;
                    CanonicalSignal hit = dr.signals().stream()
                            .filter(s -> s.signalName().equals(cand.name())).findFirst().orElse(null);
                    if (hit != null && acc[3] == 0) {
                        acc[3] = hit.rawValue();
                    }
                }
            }
        }

        System.out.println("\nPID/mux value histogram (matched frames with S01PID/S02PID):");
        pidFrames.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.printf(Locale.ROOT, "  mux=0x%X (%d) frames=%d%n",
                        e.getKey(), e.getKey(), e.getValue()));

        System.out.println("\nCandidate DBC signal emit trace:");
        for (DbcSignal cand : candidates) {
            long[] acc = candidateTrace.getOrDefault(cand.name(), new long[4]);
            System.out.printf(Locale.ROOT,
                    "  %s muxRole=%s muxVal=%s matchedMsgFrames=%d muxBranchHits=%d emittedRows=%d sampleRaw=%d%n",
                    cand.name(), cand.multiplexRole(), cand.multiplexValue(),
                    acc[0], acc[1], acc[2], acc[3]);
        }

        System.out.println("\nStellarAI unique emitted signal names=" + emitted.size());
        System.out.println("emittedNames=" + emitted.keySet());

        List<CanonicalSignal> all = new ArrayList<>();
        ProcessingResult result = MeasurementDecodePipeline.createDefault().process(
                ParserRegistry.load().findReader(mf4).orElseThrow(),
                mf4, db, ReaderContext.ofBatchSize(512), all::addAll);
        System.out.println("pipeline matched=" + result.statistics().dbcMatchedFrames()
                + " unmatched=" + result.statistics().dbcUnmatchedFrames()
                + " signals=" + result.statistics().signalsEmitted()
                + " outcome=" + result.outcome());

        // Explicit check for OBD VehicleSpeed / Odometer
        for (String name : List.of("S01PID0D_VehicleSpeed", "S01PIDA6_Odometer", "VehicleSpeed", "TRIP_ODOMETER",
                "REDUNDANT_ODOMETER")) {
            long count = emitted.getOrDefault(name, 0L);
            System.out.println("emittedCount[" + name + "]=" + count);
        }
    }
}
