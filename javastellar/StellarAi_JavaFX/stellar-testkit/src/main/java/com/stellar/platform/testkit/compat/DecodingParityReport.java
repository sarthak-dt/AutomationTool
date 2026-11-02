package com.stellar.platform.testkit.compat;

import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.decode.DecodingResult;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcMultiplexCondition;
import com.stellar.platform.dbc.model.DbcMultiplexGraph;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;

import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded decoding-parity diagnostic: one row per emitted DBC leaf/mux sample for a limited
 * number of matched frames. Test/debug infrastructure only — not a production retention path.
 */
public final class DecodingParityReport {

    private DecodingParityReport() {
    }

    public static void write(
            Path outputDir,
            Path mf4Path,
            Path dbcPath,
            int maxMatchedFrames,
            int maxRows
    ) throws Exception {
        Objects.requireNonNull(outputDir, "outputDir");
        DbcDatabase database = new DbcParser().parse(dbcPath);
        DefaultDbcDecoder decoder = new DefaultDbcDecoder();

        AtomicInteger matchedForParity = new AtomicInteger();
        AtomicInteger rowCount = new AtomicInteger();
        AtomicBoolean stopParity = new AtomicBoolean(false);
        List<String[]> rows = new ArrayList<>();
        Map<String, Integer> emittedInParityWindow = new LinkedHashMap<>();

        // Full-file counters (metadata only — no per-sample retention beyond catalog ring).
        AtomicInteger totalFrames = new AtomicInteger();
        AtomicInteger matchedFrames = new AtomicInteger();
        Map<String, Integer> muxActiveFramesBySignal = new LinkedHashMap<>();
        Map<String, Integer> emittedBySignal = new LinkedHashMap<>();
        InMemorySignalCatalogStore catalogStore = new InMemorySignalCatalogStore(
                mf4Path.getFileName().toString(),
                dbcPath.getFileName().toString(),
                50_000
        );

        // Stream once: bounded parity rows early; continue with counters/catalog for reconciliation.
        new MdfDynamicRecordDecoder().decode(
                mf4Path,
                ReaderContext.ofBatchSize(256),
                batch -> {
                    for (CanonicalCanFrame frame : batch) {
                        totalFrames.incrementAndGet();
                        DecodingResult decoded = decoder.decode(frame, database);
                        if (decoded.matchedMessage() == null) {
                            continue;
                        }
                        matchedFrames.incrementAndGet();
                        catalogStore.accept(decoded.signals());

                        for (CanonicalSignal signal : decoded.signals()) {
                            emittedBySignal.merge(signal.signalName(), 1, Integer::sum);
                            muxActiveFramesBySignal.merge(signal.signalName(), 1, Integer::sum);
                        }

                        if (stopParity.get()) {
                            continue;
                        }
                        int matched = matchedForParity.incrementAndGet();
                        if (matched > maxMatchedFrames || rowCount.get() >= maxRows) {
                            stopParity.set(true);
                            continue;
                        }
                        appendParityRows(
                                rows,
                                rowCount,
                                maxRows,
                                frame,
                                decoded,
                                emittedInParityWindow
                        );
                        if (rowCount.get() >= maxRows) {
                            stopParity.set(true);
                        }
                    }
                },
                w -> {
                }
        );

        try (BufferedWriter w = ReportIo.writer(outputDir, "12-decoding-parity.csv")) {
            ReportIo.writeln(w, ReportIo.csv(
                    "frameSequence", "signalSampleIndex", "sourceByteOffset",
                    "timestampNanos", "timestampSeconds", "canId", "payloadHex",
                    "dbcMessage", "muxSelectorRaws", "signalName", "signalRole",
                    "muxCondition", "startBit", "bitLength", "byteOrder", "signedness",
                    "rawValue", "factor", "offset", "physicalValue", "expectedPhysical",
                    "physicalMatchesFormula", "unit"
            ));
            for (String[] row : rows) {
                ReportIo.writeln(w, ReportIo.csv((Object[]) row));
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("totalCanFrames=").append(totalFrames.get()).append('\n');
        sb.append("matchedMessageFrames=").append(matchedFrames.get()).append('\n');
        sb.append("parityMatchedFramesInspected=")
                .append(Math.min(matchedForParity.get(), maxMatchedFrames)).append('\n');
        sb.append("parityRows=").append(rows.size()).append('\n');
        sb.append("emittedCountBySignal:\n");
        emittedBySignal.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append("  ").append(e.getKey()).append('=').append(e.getValue()).append('\n'));
        sb.append("catalogSampleCountBySignal:\n");
        for (SignalCatalogEntry e : catalogStore.snapshot().entries()) {
            sb.append("  ").append(e.signalName()).append('=').append(e.sampleCount()).append('\n');
        }
        sb.append("note=emitted counts must equal catalog sampleCount for each signal; ")
                .append("UI Samples column reads catalog sampleCount\n");
        ReportIo.writeText(outputDir, "12-decoding-parity-summary.txt", sb.toString());

        writeFocusedTable(outputDir, rows);
    }

    private static void appendParityRows(
            List<String[]> rows,
            AtomicInteger rowCount,
            int maxRows,
            CanonicalCanFrame frame,
            DecodingResult decoded,
            Map<String, Integer> emittedInParityWindow
    ) {
        DbcMessage message = decoded.matchedMessage();
        DbcMultiplexGraph graph = message.multiplexGraph();
        Map<String, Long> muxRaws = new LinkedHashMap<>();
        for (CanonicalSignal signal : decoded.signals()) {
            DbcSignal def = findDef(message, signal.signalName());
            if (def != null && isMuxSelector(def, graph)) {
                muxRaws.put(signal.signalName(), signal.rawValue());
            }
        }

        int sampleIndexBySignal = 0;
        for (CanonicalSignal signal : decoded.signals()) {
            if (rowCount.get() >= maxRows) {
                return;
            }
            DbcSignal def = findDef(message, signal.signalName());
            String role = def == null ? "UNKNOWN" : def.multiplexRole().name();
            if (graph.node(signal.signalName()).isPresent()) {
                var kind = graph.node(signal.signalName()).get().kind();
                if (kind == DbcMultiplexGraph.NodeKind.MULTIPLEXED_MULTIPLEXOR) {
                    role = "MULTIPLEXED_MULTIPLEXOR";
                } else if (kind == DbcMultiplexGraph.NodeKind.MULTIPLEXOR) {
                    role = "MULTIPLEXOR";
                } else if (kind == DbcMultiplexGraph.NodeKind.MULTIPLEXED) {
                    role = "LEAF_SIGNAL";
                }
            }
            String muxCond = "";
            if (def != null && !def.multiplexConditions().isEmpty()) {
                DbcMultiplexCondition c = def.multiplexConditions().getFirst();
                muxCond = c.multiplexorSignalName() + " in "
                        + c.ranges().getFirst().minimum() + "-" + c.ranges().getFirst().maximum();
            }
            double expectPhys = def == null || signal.rawValue() == null
                    ? Double.NaN
                    : signal.rawValue() * def.factor() + def.offset();
            boolean physOk = def == null || signal.rawValue() == null
                    || Math.abs(expectPhys - signal.physicalValue()) < 1e-6
                    || Math.abs(expectPhys - signal.physicalValue())
                    / Math.max(1.0, Math.abs(expectPhys)) < 1e-9;

            emittedInParityWindow.merge(signal.signalName(), 1, Integer::sum);

            rows.add(new String[]{
                    String.valueOf(frame.sequenceNumber()),
                    String.valueOf(sampleIndexBySignal++),
                    String.valueOf(frame.source().sourceByteOffset()),
                    String.valueOf(frame.timestampNanos()),
                    String.format(Locale.ROOT, "%.12g", frame.timestampNanos() / 1_000_000_000.0),
                    "0x" + Long.toHexString(frame.canId()),
                    ReportIo.hex(frame.data()),
                    message.name(),
                    formatMap(muxRaws),
                    signal.signalName(),
                    role,
                    muxCond,
                    def == null ? "" : String.valueOf(def.startBit()),
                    def == null ? "" : String.valueOf(def.bitLength()),
                    def == null ? "" : String.valueOf(def.byteOrder()),
                    def == null ? "" : String.valueOf(def.valueType()),
                    signal.rawValue() == null ? "" : String.valueOf(signal.rawValue()),
                    def == null ? "" : String.valueOf(def.factor()),
                    def == null ? "" : String.valueOf(def.offset()),
                    String.valueOf(signal.physicalValue()),
                    String.valueOf(expectPhys),
                    String.valueOf(physOk),
                    signal.unit() == null ? "" : signal.unit()
            });
            rowCount.incrementAndGet();
        }
    }

    /** Compact acceptance table for a few target signals (name match is diagnostic only). */
    private static void writeFocusedTable(Path outputDir, List<String[]> rows) throws Exception {
        String[] targets = {
                "EngineCoolantTemp", "EngineRPM", "VehicleSpeed", "CalcEngineLoad",
                "IntakeAirTemperature", "ThrottlePosition"
        };
        try (BufferedWriter w = ReportIo.writer(outputDir, "12-decoding-parity-focused.csv")) {
            ReportIo.writeln(w, ReportIo.csv(
                    "signal", "sampleIndex", "sourceOffset", "timestampSeconds",
                    "rawValue", "physicalValue", "unit"
            ));
            Map<String, Integer> idx = new LinkedHashMap<>();
            for (String[] row : rows) {
                String name = row[9];
                boolean hit = false;
                for (String t : targets) {
                    if (name.contains(t)) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    continue;
                }
                int i = idx.merge(name, 1, Integer::sum) - 1;
                ReportIo.writeln(w, ReportIo.csv(
                        name,
                        i,
                        row[2],
                        row[4],
                        row[16],
                        row[19],
                        row[22]
                ));
            }
        }
    }

    private static DbcSignal findDef(DbcMessage message, String name) {
        return message.signals().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private static boolean isMuxSelector(DbcSignal def, DbcMultiplexGraph graph) {
        if (def.multiplexRole() == DbcMultiplexRole.MULTIPLEXOR) {
            return true;
        }
        return graph.node(def.name()).map(n ->
                n.kind() == DbcMultiplexGraph.NodeKind.MULTIPLEXOR
                        || n.kind() == DbcMultiplexGraph.NodeKind.MULTIPLEXED_MULTIPLEXOR
        ).orElse(false);
    }

    private static String formatMap(Map<String, Long> map) {
        if (map.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : map.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append(';');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
