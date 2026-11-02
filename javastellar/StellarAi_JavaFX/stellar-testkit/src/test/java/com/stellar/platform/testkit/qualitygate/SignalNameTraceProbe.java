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
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.ProcessingResult;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Trace signal name "S" vs full DBC names through each pipeline layer. Offline only.
 */
public final class SignalNameTraceProbe {

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "C:/Users/ADMIN/Desktop/DriveTech");
        Path dbcPath = Path.of(args.length > 1 ? args[1] : root.resolve("11_bit_OBD2 2.dbc").toString());
        List<Path> mf4s = new ArrayList<>();
        if (args.length > 2) {
            mf4s.add(Path.of(args[2]));
        } else {
            try (var s = Files.list(root)) {
                s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mf4"))
                        .sorted()
                        .forEach(mf4s::add);
            }
        }

        System.out.println("=== LAYER: DBC source text for VehicleSpeed-like names ===");
        List<String> dbcLines = Files.readAllLines(dbcPath);
        for (String line : dbcLines) {
            String t = line.trim();
            if (t.startsWith("SG_") && (t.contains("VehicleSpeed") || t.contains("EngineRPM")
                    || t.contains("EngineCoolant") || t.matches("SG_\\s+S\\s+.*")
                    || t.contains("S01PID0D") || t.contains("S01PID0C"))) {
                System.out.println("  SRC: " + t);
            }
        }

        System.out.println("\n=== LAYER: DbcParser -> DbcSignal model ===");
        DbcDatabase db = new DbcParser().parse(dbcPath);
        System.out.println("BO_=" + db.messages().size());
        long totalSg = db.messages().stream().mapToLong(m -> m.signals().size()).sum();
        System.out.println("SG_=" + totalSg);
        for (DbcMessage m : db.messages()) {
            System.out.println("Message " + m.name() + " id=" + m.canId() + " ext=" + m.extended()
                    + " signals=" + m.signals().size());
            for (DbcSignal s : m.signals()) {
                if (s.name().equals("S") || s.name().contains("VehicleSpeed") || s.name().contains("EngineRPM")
                        || s.name().contains("EngineCoolant") || s.name().contains("Throttle")
                        || s.name().contains("IntakeAir") || s.name().contains("CalcEngineLoad")) {
                    System.out.println("  SIGNAL name=[" + s.name() + "]"
                            + " role=" + s.multiplexRole()
                            + " mux=" + s.multiplexValue()
                            + " secondaryMux=" + s.secondaryMultiplexor()
                            + " start=" + s.startBit() + " len=" + s.bitLength()
                            + " order=" + s.byteOrder() + " signed=" + s.valueType()
                            + " factor=" + s.factor() + " offset=" + s.offset()
                            + " unit=[" + s.unit() + "]");
                }
            }
            // Count names that are exactly "S" or length 1
            long exactlyS = m.signals().stream().filter(s -> "S".equals(s.name())).count();
            long shortNames = m.signals().stream().filter(s -> s.name().length() <= 2).count();
            System.out.println("  exactlyNameS=" + exactlyS + " nameLen<=2=" + shortNames);
            System.out.println("  ALL signal names (" + m.signals().size() + "):");
            m.signals().stream().map(DbcSignal::name).sorted().forEach(n -> System.out.println("    " + n));
        }

        DbcSignal vehicleSpeed = db.messages().stream()
                .flatMap(m -> m.signals().stream())
                .filter(s -> s.name().contains("VehicleSpeed"))
                .findFirst()
                .orElse(null);
        System.out.println("\nTRACE VehicleSpeed model present=" + (vehicleSpeed != null)
                + (vehicleSpeed == null ? "" : (" name=[" + vehicleSpeed.name() + "]")));

        DefaultDbcDecoder decoder = new DefaultDbcDecoder();
        for (Path mf4 : mf4s) {
            System.out.println("\n========== MF4 " + mf4.getFileName() + " ==========");
            MdfDynamicRecordDecoder.DecodeResult decoded =
                    new MdfDynamicRecordDecoder().decodeAll(mf4, ReaderContext.ofBatchSize(256));
            List<CanonicalCanFrame> frames = decoded.frames();
            System.out.println("frames=" + frames.size());

            Map<Long, Long> idCounts = frames.stream()
                    .collect(Collectors.groupingBy(CanonicalCanFrame::canId, TreeMap::new, Collectors.counting()));
            System.out.println("uniqueCanIds=" + idCounts.size());
            idCounts.entrySet().stream().limit(20).forEach(e ->
                    System.out.println("  0x" + Long.toHexString(e.getKey()) + " count=" + e.getValue()));

            // Decode first matched frames and collect signal names
            Map<String, Long> nameCounts = new TreeMap<>();
            Map<Integer, Long> muxValueCounts = new TreeMap<>();
            int matched = 0;
            int unmatched = 0;
            Set<String> namesFromDecoder = new LinkedHashSet<>();
            List<CanonicalSignal> firstBatchNames = new ArrayList<>();
            for (CanonicalCanFrame f : frames) {
                DecodingResult dr = decoder.decode(f, db);
                if (dr.status() == DecodeStatus.UNMATCHED) {
                    unmatched++;
                    continue;
                }
                matched++;
                for (CanonicalSignal s : dr.signals()) {
                    nameCounts.merge(s.signalName(), 1L, Long::sum);
                    namesFromDecoder.add(s.signalName());
                    if ("S".equals(s.signalName()) && s.rawValue() != null) {
                        muxValueCounts.merge(s.rawValue().intValue(), 1L, Long::sum);
                    }
                    if (firstBatchNames.size() < 40) {
                        firstBatchNames.add(s);
                    }
                }
            }
            System.out.println("matchedFrames=" + matched + " unmatchedFrames=" + unmatched);
            System.out.println("uniqueDecoderSignalNames=" + namesFromDecoder.size());
            System.out.println("names=" + namesFromDecoder);
            System.out.println("muxValueCounts(from S)=" + muxValueCounts);
            System.out.println("topNameCounts:");
            nameCounts.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(40)
                    .forEach(e -> System.out.println("  " + e.getKey() + "=" + e.getValue()));

            System.out.println("\nFirst decoded signals (name preservation check):");
            for (CanonicalSignal s : firstBatchNames) {
                System.out.println("  CanonicalSignal.signalName=[" + s.signalName() + "]"
                        + " msg=" + s.messageName()
                        + " raw=" + s.rawValue()
                        + " phys=" + s.physicalValue());
            }

            // Catalog layer
            InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(
                    mf4.getFileName().toString(), dbcPath.getFileName().toString(), 50_000);
            ProcessingResult result = MeasurementDecodePipeline.createDefault().process(
                    ParserRegistry.load().findReader(mf4).orElseThrow(),
                    mf4,
                    db,
                    ReaderContext.ofBatchSize(256),
                    store::accept
            );
            SignalCatalog catalog = store.snapshot();
            System.out.println("\n=== LAYER: SignalCatalog ===");
            System.out.println("pipelineSignals=" + result.statistics().signalsEmitted()
                    + " matched=" + result.statistics().dbcMatchedFrames()
                    + " unmatched=" + result.statistics().dbcUnmatchedFrames());
            System.out.println("catalogEntries=" + catalog.size()
                    + " dbc=" + catalog.dbcCanSignalCount()
                    + " native=" + catalog.nativeMf4SignalCount());
            catalog.entries().forEach(e -> System.out.println("  [" + e.signalName() + "]"
                    + " type=" + e.sourceType()
                    + " samples=" + e.sampleCount()
                    + " key=" + e.key()));

            boolean hasVehicle = catalog.entries().stream()
                    .anyMatch(e -> e.signalName().contains("VehicleSpeed"));
            boolean onlyS = catalog.dbcCanSignalCount() == 1
                    && catalog.entries().stream().anyMatch(e -> "S".equals(e.signalName()));
            System.out.println("HAS_VehicleSpeed=" + hasVehicle + " ONLY_S_DBC=" + onlyS);
        }
    }
}
