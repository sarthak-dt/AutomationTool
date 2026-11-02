package com.stellar.platform.desktop.presentation;

import com.stellar.platform.dbc.model.DbcByteOrder;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.model.DbcValueType;

import java.util.Locale;
import java.util.Objects;

/**
 * Formats {@link DbcSignal} definition metadata for Source Inventory hierarchy rows.
 * Metadata only — no sample decoding.
 */
public final class DbcSignalDefinitionFormatter {

    private DbcSignalDefinitionFormatter() {
    }

    public static String name(DbcSignal signal) {
        return Objects.requireNonNull(signal, "signal").name();
    }

    public static String typeAndOrder(DbcSignal signal) {
        Objects.requireNonNull(signal, "signal");
        String signed = signal.valueType() == DbcValueType.SIGNED ? "Signed" : "Unsigned";
        String order = signal.byteOrder() == DbcByteOrder.MOTOROLA ? "Motorola" : "Intel";
        return signed + " / " + order;
    }

    public static String bitPosition(DbcSignal signal) {
        Objects.requireNonNull(signal, "signal");
        return "bit " + signal.startBit() + " len " + signal.bitLength();
    }

    public static String multiplexLabel(DbcSignal signal) {
        Objects.requireNonNull(signal, "signal");
        return switch (signal.multiplexRole()) {
            case MULTIPLEXOR -> "Multiplexer";
            case MULTIPLEXED -> signal.multiplexValueOptional()
                    .map(v -> "Mux = " + v)
                    .orElse(signal.hasExplicitMultiplexConditions() ? "Mux (SG_MUL_VAL_)" : "Multiplexed");
            case NONE -> "";
        };
    }

    public static String conversionAndUnit(DbcSignal signal) {
        Objects.requireNonNull(signal, "signal");
        String unit = signal.unit() == null || signal.unit().isBlank() ? "" : (" " + signal.unit());
        return String.format(
                Locale.US,
                "x%.6g%+.6g%s [%g..%g]",
                signal.factor(),
                signal.offset(),
                unit,
                signal.minimum(),
                signal.maximum()
        );
    }
}
