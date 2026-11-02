package com.stellar.platform.dbc.decode;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcRawEncoding;
import com.stellar.platform.dbc.model.DbcValueType;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcMultiplexCondition;
import com.stellar.platform.dbc.model.DbcMultiplexGraph;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.parse.DbcDiagnostic;
import com.stellar.platform.dbc.parse.DbcDiagnosticCode;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Default streaming-capable common DBC decoder.
 * <p>
 * Supports flat multiplexing ({@code M}/{@code mN}), Vector {@code mNM} secondary multiplexors,
 * and extended multiplexing via {@code SG_MUL_VAL_} conditions (named multiplexor + value ranges).
 */
public final class DefaultDbcDecoder implements DbcDecoder {

    private final IdentityHashMap<DbcDatabase, Boolean> compiledDatabases = new IdentityHashMap<>();
    private final IdentityHashMap<DbcMessage, DbcMessageDecodePlan> plans = new IdentityHashMap<>();

    private DbcMessageDecodePlan planFor(DbcDatabase database, DbcMessage message) {
        ensureCompiled(database);
        DbcMessageDecodePlan plan = plans.get(message);
        if (plan != null) {
            return plan;
        }
        plan = DbcMessageDecodePlan.compile(message);
        plans.put(message, plan);
        return plan;
    }

    private void ensureCompiled(DbcDatabase database) {
        if (compiledDatabases.putIfAbsent(database, Boolean.TRUE) != null) {
            return;
        }
        for (DbcMessage message : database.messages()) {
            plans.put(message, DbcMessageDecodePlan.compile(message));
        }
    }

    @Override
    public DecodingResult decode(CanonicalCanFrame frame, DbcDatabase database) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(database, "database");
        Optional<DbcMessage> matched = MessageMatcher.match(frame, database);
        return decodeInternal(frame, database, matched, true);
    }

    @Override
    public DecodingResult decodeMatched(CanonicalCanFrame frame, DbcDatabase database, DbcMessage message) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(message, "message");
        return decodeInternal(frame, database, Optional.of(message), false);
    }

    private DecodingResult decodeInternal(
            CanonicalCanFrame frame,
            DbcDatabase database,
            Optional<DbcMessage> matched,
            boolean emitUnmatchedDiagnostic
    ) {
        List<DbcDiagnostic> diagnostics = new ArrayList<>();
        if (frame.payloadLength() < 0) {
            diagnostics.add(DbcDiagnostic.of(DbcDiagnosticCode.MALFORMED_FRAME, "Frame payload is null"));
            return new DecodingResult(DecodeStatus.MALFORMED, null, List.of(), diagnostics);
        }

        if (matched.isEmpty()) {
            if (emitUnmatchedDiagnostic) {
                diagnostics.add(DbcDiagnostic.ofContext(
                        DbcDiagnosticCode.UNMATCHED_CAN_ID,
                        "No DBC message for CAN ID 0x" + Long.toHexString(frame.canId())
                                + (frame.extended() ? " (extended)" : " (standard)"),
                        frame.source() == null ? null : frame.source().sourceLineNumber(),
                        "canId=" + frame.canId()
                ));
            }
            return new DecodingResult(DecodeStatus.UNMATCHED, null, List.of(), diagnostics);
        }

        DbcMessage message = matched.get();
        int payloadLength = frame.payloadLength();
        if (message.sizeBytes() > 0 && payloadLength < message.sizeBytes()) {
            diagnostics.add(DbcDiagnostic.ofContext(
                    DbcDiagnosticCode.INSUFFICIENT_PAYLOAD,
                    "Frame payload " + payloadLength + "B < message size " + message.sizeBytes() + "B",
                    frame.source().sourceLineNumber(),
                    message.name()
            ));
        }

        DbcMessageDecodePlan plan = planFor(database, message);
        List<CanonicalSignal> signals;
        boolean anyError;
        if (plan.useExplicitMulVal()) {
            ExplicitDecode explicit = decodeWithExplicitMultiplex(frame, plan, diagnostics);
            signals = explicit.signals();
            anyError = explicit.anyError();
        } else {
            LegacyDecode legacy = decodeWithLegacyMultiplex(frame, plan, diagnostics);
            signals = legacy.signals();
            anyError = legacy.anyError();
        }

        maybeReportInactiveMultiplex(plan, signals, diagnostics, frame);

        DecodeStatus status;
        if (signals.isEmpty() && anyError) {
            status = DecodeStatus.ERROR;
        } else if (anyError || !diagnostics.isEmpty()) {
            status = signals.isEmpty() ? DecodeStatus.ERROR : DecodeStatus.PARTIAL;
        } else {
            status = DecodeStatus.DECODED;
        }
        if (!diagnostics.isEmpty() && signals.isEmpty() && !anyError) {
            status = DecodeStatus.PARTIAL;
        }

        return new DecodingResult(status, message, signals, diagnostics);
    }

    /**
     * {@code SG_MUL_VAL_}-driven decode: extract signals in dependency order using named
     * multiplexor conditions and inclusive value ranges.
     */
    private static ExplicitDecode decodeWithExplicitMultiplex(
            CanonicalCanFrame frame,
            DbcMessageDecodePlan plan,
            List<DbcDiagnostic> diagnostics
    ) {
        DbcMessage message = plan.message();
        Map<String, Long> rawByName = new LinkedHashMap<>();
        List<CanonicalSignal> signals = new ArrayList<>();
        boolean anyError = false;
        Set<String> muxSelectorNames = plan.selectorNames();

        boolean progressed;
        do {
            progressed = false;
            for (DbcSignal signal : message.signals()) {
                if (rawByName.containsKey(signal.name())) {
                    continue;
                }
                if (!isApplicableExplicit(signal, rawByName, plan)) {
                    continue;
                }

                SignalBitExtractor.ExtractResult extracted = SignalBitExtractor.extract(
                        frame,
                        signal.startBit(),
                        signal.bitLength(),
                        signal.byteOrder(),
                        extractType(signal)
                );
                if (!extracted.isOk()) {
                    diagnostics.add(extracted.error());
                    anyError = true;
                    continue;
                }

                long raw = extracted.rawValue();
                rawByName.put(signal.name(), raw);
                progressed = true;
                signals.add(toCanonical(
                        frame, message.name(), signal, raw,
                        !muxSelectorNames.contains(signal.name()), diagnostics
                ));
            }
        } while (progressed);

        return new ExplicitDecode(signals, anyError);
    }

    private static boolean isApplicableExplicit(
            DbcSignal signal,
            Map<String, Long> rawByName,
            DbcMessageDecodePlan plan
    ) {
        if (signal.hasExplicitMultiplexConditions()) {
            return signal.isApplicableForExplicitMultiplex(rawByName);
        }
        return switch (signal.multiplexRole()) {
            case NONE, MULTIPLEXOR -> true;
            case MULTIPLEXED -> {
                DbcSignal root = plan.rootMultiplexor();
                if (root == null) {
                    yield false;
                }
                Long rootRaw = rawByName.get(root.name());
                yield signal.isApplicableForMux(rootRaw);
            }
        };
    }

    private static LegacyDecode decodeWithLegacyMultiplex(
            CanonicalCanFrame frame,
            DbcMessageDecodePlan plan,
            List<DbcDiagnostic> diagnostics
    ) {
        DbcMessage message = plan.message();
        int independentMultiplexors = 0;
        for (DbcSignal signal : message.signals()) {
            if (signal.multiplexRole() == DbcMultiplexRole.MULTIPLEXOR) {
                independentMultiplexors++;
            }
        }
        if (independentMultiplexors > 1) {
            diagnostics.add(DbcDiagnostic.of(
                    DbcDiagnosticCode.UNSUPPORTED_MULTIPLEXING,
                    "Message '" + message.name() + "' has " + independentMultiplexors
                            + " independent multiplexors. Leaf signals were not decoded."
            ));
        }
        Long rootMuxValue = null;
        DbcSignal mux = plan.rootMultiplexor();
        if (mux != null) {
            SignalBitExtractor.ExtractResult muxExtract = SignalBitExtractor.extract(
                    frame,
                    mux.startBit(),
                    mux.bitLength(),
                    mux.byteOrder(),
                    extractType(mux)
            );
            if (!muxExtract.isOk()) {
                diagnostics.add(muxExtract.error());
                diagnostics.add(DbcDiagnostic.of(
                        DbcDiagnosticCode.UNSUPPORTED_MULTIPLEXING,
                        "Failed to decode multiplexor signal '" + mux.name() + "'"
                ));
                return new LegacyDecode(List.of(), true);
            }
            rootMuxValue = muxExtract.rawValue();
        }

        boolean hasSecondary = plan.hasSecondary();
        Long leafMuxValue = rootMuxValue;
        if (hasSecondary && rootMuxValue != null) {
            leafMuxValue = null;
            for (DbcSignal signal : message.signals()) {
                if (!signal.secondaryMultiplexor() || !signal.isApplicableForMux(rootMuxValue)) {
                    continue;
                }
                SignalBitExtractor.ExtractResult secondaryExtract = SignalBitExtractor.extract(
                        frame,
                        signal.startBit(),
                        signal.bitLength(),
                        signal.byteOrder(),
                        extractType(signal)
                );
                if (!secondaryExtract.isOk()) {
                    diagnostics.add(secondaryExtract.error());
                    diagnostics.add(DbcDiagnostic.of(
                            DbcDiagnosticCode.UNSUPPORTED_MULTIPLEXING,
                            "Failed to decode secondary multiplexor '" + signal.name() + "'"
                    ));
                    return new LegacyDecode(List.of(), true);
                }
                leafMuxValue = secondaryExtract.rawValue();
                break;
            }
        }

        List<CanonicalSignal> signals = new ArrayList<>();
        boolean anyError = false;
        for (DbcSignal signal : message.signals()) {
            if (independentMultiplexors > 1 && signal.multiplexRole() == DbcMultiplexRole.MULTIPLEXED) {
                continue;
            }
            if (!isApplicableLegacy(signal, rootMuxValue, leafMuxValue, hasSecondary)) {
                continue;
            }

            SignalBitExtractor.ExtractResult extracted = SignalBitExtractor.extract(
                    frame,
                    signal.startBit(),
                    signal.bitLength(),
                    signal.byteOrder(),
                    extractType(signal)
            );
            if (!extracted.isOk()) {
                diagnostics.add(extracted.error());
                anyError = true;
                continue;
            }

            signals.add(toCanonical(
                    frame, message.name(), signal, extracted.rawValue(),
                    !isMuxSelectorLegacy(signal), diagnostics
            ));
        }
        return new LegacyDecode(signals, anyError);
    }

    private static boolean isApplicableLegacy(
            DbcSignal signal,
            Long rootMuxValue,
            Long leafMuxValue,
            boolean hasSecondary
    ) {
        return switch (signal.multiplexRole()) {
            case NONE, MULTIPLEXOR -> true;
            case MULTIPLEXED -> {
                if (signal.secondaryMultiplexor()) {
                    yield signal.isApplicableForMux(rootMuxValue);
                }
                if (hasSecondary) {
                    yield leafMuxValue != null && signal.isApplicableForMux(leafMuxValue);
                }
                yield signal.isApplicableForMux(rootMuxValue);
            }
        };
    }

    private static DbcValueType extractType(DbcSignal signal) {
        return signal.rawEncoding() == DbcRawEncoding.INTEGER
                ? signal.valueType()
                : DbcValueType.UNSIGNED;
    }

    /**
     * Emits the decoded sample unchanged when it is invalid, inactive, or non-finite.
     * Those conditions add a diagnostic; they do not substitute zero.
     */
    private static CanonicalSignal toCanonical(
            CanonicalCanFrame frame,
            String messageName,
            DbcSignal signal,
            long extractedRaw,
            boolean checkRange,
            List<DbcDiagnostic> diagnostics
    ) {
        SignalValueDecoder.Decoded decoded = SignalValueDecoder.decode(signal, extractedRaw);
        Long line = frame.source() == null ? null : frame.source().sourceLineNumber();
        if (decoded.nonFinite()) {
            diagnostics.add(DbcDiagnostic.ofContext(
                    DbcDiagnosticCode.NON_FINITE_PHYSICAL_VALUE,
                    "Signal '" + signal.name() + "' physical value is non-finite",
                    line,
                    messageName
            ));
        } else if (checkRange
                && PhysicalValueConverter.isOutsideBounds(
                        decoded.physical(), signal.minimum(), signal.maximum())) {
            diagnostics.add(DbcDiagnostic.ofContext(
                    DbcDiagnosticCode.OUT_OF_RANGE_PHYSICAL_VALUE,
                    "Signal '" + signal.name() + "' physical=" + decoded.physical()
                            + " outside [" + signal.minimum() + "," + signal.maximum() + "]",
                    line,
                    messageName
            ));
        }
        if (decoded.valueInvalid()) {
            diagnostics.add(DbcDiagnostic.ofContext(
                    DbcDiagnosticCode.INVALID_RAW_VALUE,
                    "Signal '" + signal.name() + "' raw " + decoded.raw()
                            + " is " + decoded.reservation(),
                    line,
                    messageName
            ));
        }
        return new CanonicalSignal(
                signal.name(),
                messageName,
                frame.timestampNanos(),
                frame.canId(),
                frame.channel(),
                decoded.physical(),
                decoded.raw(),
                signal.unit(),
                decoded.textual(),
                frame.source(),
                decoded.validity()
        );
    }

    private static boolean isMuxSelectorLegacy(DbcSignal signal) {
        return signal.multiplexRole() == DbcMultiplexRole.MULTIPLEXOR || signal.secondaryMultiplexor();
    }


    private static void maybeReportInactiveMultiplex(
            DbcMessageDecodePlan plan,
            List<CanonicalSignal> signals,
            List<DbcDiagnostic> diagnostics,
            CanonicalCanFrame frame
    ) {
        DbcMessage message = plan.message();
        DbcMultiplexGraph graph = plan.graph();
        for (String d : graph.diagnostics()) {
            diagnostics.add(DbcDiagnostic.of(DbcDiagnosticCode.UNSUPPORTED_MULTIPLEXING, d));
        }

        if (!plan.hasLeafDefinitions()) {
            return;
        }
        java.util.Set<String> leafNames = plan.leafNames();
        boolean anyLeaf = false;
        for (CanonicalSignal signal : signals) {
            if (leafNames.contains(signal.signalName())) {
                anyLeaf = true;
                break;
            }
        }
        if (anyLeaf) {
            return;
        }

        StringBuilder detail = new StringBuilder();
        detail.append("CAN ID matched message '").append(message.name())
                .append("' but nested multiplex graph activated no leaf (");
        detail.append("muxLevels=").append(graph.muxLevelCount()).append("). ");
        DbcSignal root = plan.rootMultiplexor();
        if (root != null) {
            var rootExtract = SignalBitExtractor.extract(
                    frame, root.startBit(), root.bitLength(),
                    root.byteOrder(), extractType(root));
            if (rootExtract.isOk()) {
                long rootRaw = rootExtract.rawValue();
                detail.append("root '").append(root.name()).append("'=").append(rootRaw).append(';');
                graph.node(root.name()).ifPresent(node -> {
                    boolean anyChild = false;
                    for (DbcMultiplexGraph.Edge edge : node.outboundEdges()) {
                        if (edge.matches(rootRaw)) {
                            anyChild = true;
                            detail.append(" next='").append(edge.childSignalName()).append("'");
                        }
                    }
                    if (!anyChild) {
                        detail.append(" no child edge matches root value (traversal stops)");
                    }
                });
            }
        }
        detail.append(". Selectors may still be present; leaf names were not truncated.");

        diagnostics.add(DbcDiagnostic.ofContext(
                DbcDiagnosticCode.MUX_NO_ACTIVE_LEAF,
                detail.toString(),
                frame.source() == null ? null : frame.source().sourceLineNumber(),
                message.name()
        ));
    }

    @Override
    public DecodeStatistics decodeBatch(
            List<CanonicalCanFrame> frames,
            DbcDatabase database,
            SignalConsumer consumer
    ) {
        Objects.requireNonNull(frames, "frames");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(consumer, "consumer");

        DecodeStatistics stats = new DecodeStatistics();
        for (CanonicalCanFrame frame : frames) {
            stats.recordFrame();
            DecodingResult result = decode(frame, database);
            switch (result.status()) {
                case UNMATCHED -> stats.recordUnmatched();
                case MALFORMED, ERROR -> {
                    stats.recordMalformed();
                    if (result.matchedMessage() != null) {
                        stats.recordMatched();
                    }
                }
                case DECODED, PARTIAL -> {
                    stats.recordMatched();
                    stats.recordSignals(result.signals().size());
                }
            }
            for (CanonicalSignal signal : result.signals()) {
                try {
                    consumer.onSignal(signal);
                } catch (Exception e) {
                    throw new IllegalStateException("SignalConsumer failed for " + signal.signalName(), e);
                }
            }
        }
        return stats;
    }

    private record ExplicitDecode(List<CanonicalSignal> signals, boolean anyError) {
    }

    private record LegacyDecode(List<CanonicalSignal> signals, boolean anyError) {
    }
}
