package com.stellar.platform.testkit.qualitygate;

import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.decode.DecodingResult;
import com.stellar.platform.dbc.decode.SignalBitExtractor;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcMultiplexGraph;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.spi.ReaderContext;

import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class MultiplexGraphFrameTrace {
    public static void main(String[] args) throws Exception {
        Path dbcPath = Path.of(args.length > 0 ? args[0] : "C:/Users/ADMIN/Desktop/DriveTech/11_bit_OBD2 2.dbc");
        Path mf4 = Path.of(args.length > 1 ? args[1]
                : "C:/Users/ADMIN/Desktop/DriveTech/Devendra/Prasad Sangpal/Demo_DT_00000985_00000001-63C8CA43.MF4");

        DbcDatabase db = new DbcParser().parse(dbcPath);
        DbcMessage msg = db.messages().iterator().next();
        DbcMultiplexGraph graph = msg.multiplexGraph();

        System.out.println("=== MULTIPLEX GRAPH ===");
        System.out.println(graph.renderTree());
        System.out.println("muxLevels=" + graph.muxLevelCount());
        System.out.println("graphDiagnostics=" + graph.diagnostics());
        long mulVal = msg.signals().stream().filter(DbcSignal::hasExplicitMultiplexConditions).count();
        System.out.println("sgMulValSignals=" + mulVal);

        System.out.println("=== MULTIPLEXOR BIT LAYOUTS ===");
        for (DbcSignal s : msg.signals()) {
            var kind = graph.node(s.name()).map(DbcMultiplexGraph.Node::kind).orElse(null);
            if (kind == DbcMultiplexGraph.NodeKind.MULTIPLEXOR
                    || kind == DbcMultiplexGraph.NodeKind.MULTIPLEXED_MULTIPLEXOR) {
                System.out.println(s.name() + " kind=" + kind
                        + " start=" + s.startBit() + " len=" + s.bitLength()
                        + " order=" + s.byteOrder() + " signed=" + s.valueType());
            }
        }

        System.out.println("=== GOLDEN PAYLOAD 03 41 05 7B ===");
        byte[] golden = new byte[]{0x03, 0x41, 0x05, 0x7B, 0, 0, 0, 0};
        CanonicalCanFrame goldenFrame = new CanonicalCanFrame(
                0, 0, 0, msg.numericId(), msg.extended(), FrameDirection.RX, 8, golden,
                FrameQuality.OK, new SourceReference(1, 0));
        traceFrame("GOLDEN", goldenFrame, msg, db, graph);

        var decoded = new MdfDynamicRecordDecoder().decodeAll(mf4, ReaderContext.ofBatchSize(256));
        List<CanonicalCanFrame> matched = decoded.frames().stream()
                .filter(f -> f.canId() == msg.numericId() && f.extended() == msg.extended())
                .toList();
        System.out.println("=== MF4 === frames=" + decoded.frames().size() + " matched=" + matched.size());

        DefaultDbcDecoder decoder = new DefaultDbcDecoder();
        Set<String> leaves = new LinkedHashSet<>();
        Set<String> selectors = new LinkedHashSet<>();
        Set<String> nestedSelectors = new LinkedHashSet<>();
        int total = 0;
        Map<Long, Set<String>> leavesByRoot = new LinkedHashMap<>();

        int i = 0;
        for (CanonicalCanFrame f : matched) {
            traceFrame("FRAME" + (++i), f, msg, db, graph);
            DecodingResult r = decoder.decode(f, db);
            DbcSignal root = msg.multiplexor().orElseThrow();
            long rootRaw = SignalBitExtractor.extract(
                    f.data(), root.startBit(), root.bitLength(), root.byteOrder(), root.valueType()).rawValue();
            Set<String> leafSet = leavesByRoot.computeIfAbsent(rootRaw, k -> new LinkedHashSet<>());
            for (CanonicalSignal cs : r.signals()) {
                total++;
                var kind = graph.node(cs.signalName()).map(DbcMultiplexGraph.Node::kind).orElse(null);
                if (kind == DbcMultiplexGraph.NodeKind.MULTIPLEXOR) {
                    selectors.add(cs.signalName());
                } else if (kind == DbcMultiplexGraph.NodeKind.MULTIPLEXED_MULTIPLEXOR) {
                    nestedSelectors.add(cs.signalName());
                } else {
                    leaves.add(cs.signalName());
                    leafSet.add(cs.signalName());
                }
            }
        }

        System.out.println("=== SUMMARY ===");
        System.out.println("rootSelectorsEmitted=" + selectors);
        System.out.println("nestedSelectorsEmitted=" + nestedSelectors);
        System.out.println("uniqueLeafSignals=" + leaves.size() + " names=" + leaves);
        System.out.println("totalCanonicalSignals=" + total);
        System.out.println("leavesByRootSelector=" + leavesByRoot);
    }

    private static void traceFrame(
            String label,
            CanonicalCanFrame frame,
            DbcMessage msg,
            DbcDatabase db,
            DbcMultiplexGraph graph
    ) {
        System.out.println("--- " + label + " payload=" + HexFormat.of().formatHex(frame.data())
                + " dlc=" + frame.dlc() + " ---");
        Map<String, Long> raw = new LinkedHashMap<>();
        for (DbcSignal s : msg.signals()) {
            var kind = graph.node(s.name()).map(DbcMultiplexGraph.Node::kind).orElse(null);
            if (kind != DbcMultiplexGraph.NodeKind.MULTIPLEXOR
                    && kind != DbcMultiplexGraph.NodeKind.MULTIPLEXED_MULTIPLEXOR) {
                continue;
            }
            boolean applicable = !s.hasExplicitMultiplexConditions()
                    || s.isApplicableForExplicitMultiplex(raw);
            if (!applicable) {
                System.out.println("  mux " + s.name() + " inactive (parent gate failed)");
                continue;
            }
            var ex = SignalBitExtractor.extract(
                    frame.data(), s.startBit(), s.bitLength(), s.byteOrder(), s.valueType());
            System.out.println("  mux " + s.name() + " start=" + s.startBit() + " len=" + s.bitLength()
                    + " order=" + s.byteOrder() + " signed=" + s.valueType()
                    + " raw=" + ex.rawValue());
            raw.put(s.name(), ex.rawValue());
            graph.node(s.name()).ifPresent(node -> {
                List<String> next = node.outboundEdges().stream()
                        .filter(e -> e.matches(ex.rawValue()))
                        .map(DbcMultiplexGraph.Edge::childSignalName)
                        .toList();
                System.out.println("    nextActiveChildren=" + next.size()
                        + (next.size() <= 5 ? " " + next : " e.g. " + next.stream().limit(3).toList()));
            });
        }
        DecodingResult r = new DefaultDbcDecoder().decode(frame, db);
        System.out.println("  emitted=" + r.signals().stream().map(CanonicalSignal::signalName).toList());
        r.signals().stream()
                .filter(cs -> graph.node(cs.signalName())
                        .map(n -> n.kind() == DbcMultiplexGraph.NodeKind.MULTIPLEXED)
                        .orElse(false))
                .forEach(cs -> System.out.println("  leaf " + cs.signalName()
                        + " raw=" + cs.rawValue() + " phys=" + cs.physicalValue() + " unit=" + cs.unit()));
        r.diagnostics().forEach(d -> System.out.println("  diag " + d.code() + ": " + d.message()));
    }
}