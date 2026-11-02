package com.stellar.platform.testkit.qualitygate;

import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.decode.DecodeStatus;
import com.stellar.platform.dbc.decode.DecodingResult;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.ProcessingResult;
import com.stellar.platform.processing.ProcessingStatistics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Offline probe: CAN ID vs DBC compatibility for a recovered/unfinalized MF4.
 * Not run by Surefire.
 */
public final class ZeroSignalCompatProbe {

    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && "scan".equalsIgnoreCase(args[0])) {
            Path root = Path.of(args.length > 1 ? args[1] : "C:/Users/ADMIN/Desktop/DriveTech");
            try (var stream = Files.walk(root)) {
                List<Path> mf4s = stream
                        .filter(p -> {
                            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                            return n.endsWith(".mf4");
                        })
                        .filter(Files::isRegularFile)
                        .toList();
                for (Path mf4 : mf4s) {
                    try {
                        var decoded = new MdfDynamicRecordDecoder()
                                .decodeAll(mf4, ReaderContext.ofBatchSize(512));
                        long recovered = decoded.frames().stream()
                                .filter(f -> f.quality() == FrameQuality.RECOVERED).count();
                        long valid = decoded.frames().stream()
                                .filter(f -> f.quality() == FrameQuality.VALID
                                        || f.quality() == FrameQuality.OK).count();
                        System.out.printf(Locale.ROOT, "%d frames valid=%d recovered=%d  %s%n",
                                decoded.frames().size(), valid, recovered, mf4);
                        if (decoded.frames().size() == 17_219) {
                            System.out.println("*** MATCH 17219 *** " + mf4);
                        }
                    } catch (Exception e) {
                        System.out.println("FAIL " + mf4 + " :: " + e.getMessage());
                    }
                }
            }
            return;
        }

        Path mf4 = Path.of(args[0]);
        Path dbcPath = Path.of(args.length > 1 ? args[1]
                : "C:/Users/ADMIN/Desktop/DriveTech/11_bit_OBD2 2.dbc");
        analyze(mf4, dbcPath);
    }

    private static void analyze(Path mf4, Path dbcPath) throws Exception {
        System.out.println("MF4=" + mf4);
        System.out.println("DBC=" + dbcPath);

        MdfDynamicRecordDecoder.DecodeResult decoded =
                new MdfDynamicRecordDecoder().decodeAll(mf4, ReaderContext.ofBatchSize(512));
        List<CanonicalCanFrame> frames = decoded.frames();
        long valid = frames.stream().filter(f -> f.quality() == FrameQuality.VALID
                || f.quality() == FrameQuality.OK).count();
        long recovered = frames.stream().filter(f -> f.quality() == FrameQuality.RECOVERED).count();
        long standard = frames.stream().filter(f -> !f.extended()).count();
        long extended = frames.stream().filter(CanonicalCanFrame::extended).count();

        System.out.println("\n=== Frame totals ===");
        System.out.println("totalFrames=" + frames.size());
        System.out.println("VALID/OK=" + valid + " RECOVERED=" + recovered);
        System.out.println("standard=" + standard + " extended=" + extended);

        DbcDatabase db = new DbcParser().parse(dbcPath);
        System.out.println("\n=== DBC ===");
        System.out.println("BO_messages=" + db.messages().size());
        for (DbcMessage m : db.messages()) {
            System.out.printf(Locale.ROOT, "  BO_ %s id=0x%X (%d) extended=%s size=%d signals=%d%n",
                    m.name(), m.numericId(), m.numericId(), m.extended(), m.sizeBytes(), m.signals().size());
        }
        List<Long> dbcIds = db.messages().stream().map(DbcMessage::numericId).sorted().toList();
        System.out.println("DBC_CAN_IDs=" + dbcIds.stream()
                .map(id -> String.format(Locale.ROOT, "0x%X", id)).toList());

        Map<String, Long> idCounts = new TreeMap<>();
        Map<String, Map<Integer, Long>> dlcDist = new HashMap<>();
        Map<String, Boolean> idExtended = new HashMap<>();
        for (CanonicalCanFrame f : frames) {
            String key = String.format(Locale.ROOT, "0x%X/%s", f.canId(), f.extended() ? "EXT" : "STD");
            idCounts.merge(key, 1L, Long::sum);
            idExtended.put(key, f.extended());
            dlcDist.computeIfAbsent(key, k -> new TreeMap<>()).merge(f.dlc(), 1L, Long::sum);
        }

        System.out.println("\n=== Unique MF4 CAN IDs (" + idCounts.size() + ") ===");
        idCounts.forEach((k, v) -> System.out.println(k + " count=" + v));

        boolean has7e8 = frames.stream().anyMatch(f -> f.canId() == 0x7E8L);
        System.out.println("has_0x7E8=" + has7e8);

        System.out.println("\n=== Top 30 CAN IDs ===");
        idCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(30)
                .forEach(e -> {
                    System.out.println(e.getKey() + " count=" + e.getValue()
                            + " dlcDist=" + dlcDist.get(e.getKey()));
                });

        DefaultDbcDecoder decoder = new DefaultDbcDecoder();
        long matched = 0;
        long unmatched = 0;
        long signals = 0;
        Map<Long, Long> unmatchedById = new HashMap<>();
        for (CanonicalCanFrame f : frames) {
            DecodingResult dr = decoder.decode(f, db);
            if (dr.status() == DecodeStatus.UNMATCHED) {
                unmatched++;
                unmatchedById.merge(f.canId(), 1L, Long::sum);
            } else {
                matched++;
                signals += dr.signals().size();
            }
        }
        System.out.println("\n=== DBC match stats (from CanonicalCanFrame + DefaultDbcDecoder) ===");
        System.out.println("matched=" + matched + " unmatched=" + unmatched + " signals=" + signals);

        List<Long> mf4Ids = frames.stream().map(CanonicalCanFrame::canId).distinct().sorted().toList();
        List<Long> intersection = mf4Ids.stream().filter(id ->
                db.messages().stream().anyMatch(m -> m.numericId() == id)).toList();
        System.out.println("MF4_unique_ids=" + mf4Ids.stream()
                .map(id -> String.format(Locale.ROOT, "0x%X", id)).toList());
        System.out.println("intersection=" + intersection.stream()
                .map(id -> String.format(Locale.ROOT, "0x%X", id)).toList());

        long coveredFrames = frames.stream()
                .filter(f -> intersection.contains(f.canId()))
                .count();
        // More precise: match with extended flag
        long coveredExact = 0;
        for (CanonicalCanFrame f : frames) {
            if (db.messages().stream().anyMatch(m ->
                    m.numericId() == f.canId() && m.extended() == f.extended())) {
                coveredExact++;
            }
        }
        System.out.println("coveredFrames_byIdOnly=" + coveredFrames);
        System.out.println("coveredFrames_idAndExtended=" + coveredExact);
        System.out.printf(Locale.ROOT, "coverage_pct=%.4f%%%n",
                frames.isEmpty() ? 0.0 : (100.0 * coveredExact / frames.size()));

        System.out.println("\n=== Pipeline ===");
        ProcessingResult result = MeasurementDecodePipeline.createDefault().process(
                ParserRegistry.load().findReader(mf4).orElseThrow(),
                mf4,
                db,
                ReaderContext.ofBatchSize(512),
                batch -> {
                }
        );
        ProcessingStatistics stats = result.statistics();
        System.out.println("pipeline framesRead=" + stats.sourceFramesRead()
                + " valid=" + stats.validFrames()
                + " recovered=" + stats.recoveredFrames()
                + " standard=" + stats.standardFrames()
                + " extended=" + stats.extendedFrames()
                + " matched=" + stats.dbcMatchedFrames()
                + " unmatched=" + stats.dbcUnmatchedFrames()
                + " signals=" + stats.signalsEmitted());
        System.out.println("outcome=" + result.outcome());
        result.aggregatedDiagnostics().forEach(d ->
                System.out.println("  " + d.code() + " count=" + d.count() + " :: " + d.message()));

        System.out.println("\n=== Representative recovered frames (sample) ===");
        List<CanonicalCanFrame> sample = new ArrayList<>();
        frames.stream().filter(f -> !f.extended()).findFirst().ifPresent(sample::add);
        frames.stream().filter(CanonicalCanFrame::extended).findFirst().ifPresent(sample::add);
        frames.stream().filter(f -> f.quality() == FrameQuality.RECOVERED).skip(100).findFirst()
                .ifPresent(sample::add);
        // min/max id samples
        frames.stream().min(Comparator.comparingLong(CanonicalCanFrame::canId)).ifPresent(sample::add);
        frames.stream().max(Comparator.comparingLong(CanonicalCanFrame::canId)).ifPresent(sample::add);

        Map<Long, CanonicalCanFrame> dedup = new LinkedHashMap<>();
        for (CanonicalCanFrame f : sample) {
            dedup.put(f.sequenceNumber(), f);
        }
        for (CanonicalCanFrame f : dedup.values()) {
            printFrameDetail(f);
            // range checks
            if (!f.extended() && f.canId() > 0x7FFL) {
                System.out.println("  !! STD ID outside 11-bit range");
            }
            if (f.extended() && f.canId() > 0x1FFFFFFFL) {
                System.out.println("  !! EXT ID outside 29-bit range");
            }
            if (!f.extended() && f.canId() <= 0x7FFL) {
                System.out.println("  STD range OK (0..0x7FF), extendedFlag=" + f.extended());
            }
            if (f.extended() && f.canId() <= 0x1FFFFFFFL) {
                System.out.println("  EXT range OK (0..0x1FFFFFFF), extendedFlag=" + f.extended()
                        + " (flag independent of whether value fits in 11 bits: id11fit="
                        + (f.canId() <= 0x7FFL) + ")");
            }
        }

        System.out.println("\n=== Verdict ===");
        if (matched == 0) {
            System.out.println("REASON: DBC_NO_MATCHING_CAN_IDS — MF4 parse SUCCESS, zero BO_ overlap");
        } else if (signals == 0) {
            System.out.println("REASON: matched>0 but signals=0 — investigate mux/payload");
        } else {
            System.out.println("REASON: signals produced=" + signals);
        }
    }

    private static void printFrameDetail(CanonicalCanFrame f) {
        System.out.printf(Locale.ROOT,
                "seq=%d off=%s ts=%d id=0x%X ext=%s dlc=%d q=%s dir=%s data=%s%n",
                f.sequenceNumber(),
                f.source() == null ? "?" : Long.toString(f.source().sourceByteOffset()),
                f.timestampNanos(),
                f.canId(),
                f.extended(),
                f.dlc(),
                f.quality(),
                f.direction(),
                bytesHex(f.data()));
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
