package com.stellar.platform.desktop.presentation;

import com.stellar.platform.dbc.model.DbcByteOrder;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.model.DbcValueType;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Read-only DBC signal definition metadata for Source Inventory drill-down. No sample values. */
public final class DbcSignalDefinitionView {

    private final String name;
    private final int startBit;
    private final int bitLength;
    private final DbcByteOrder byteOrder;
    private final DbcValueType valueType;
    private final double factor;
    private final double offset;
    private final double definedMinimum;
    private final double definedMaximum;
    private final String unit;
    private final DbcMultiplexRole multiplexRole;
    private final Integer multiplexValue;
    private final List<String> receivers;

    public DbcSignalDefinitionView(
            String name,
            int startBit,
            int bitLength,
            DbcByteOrder byteOrder,
            DbcValueType valueType,
            double factor,
            double offset,
            double definedMinimum,
            double definedMaximum,
            String unit,
            DbcMultiplexRole multiplexRole,
            Integer multiplexValue,
            List<String> receivers
    ) {
        this.name = Objects.requireNonNull(name, "name");
        this.startBit = startBit;
        this.bitLength = bitLength;
        this.byteOrder = Objects.requireNonNull(byteOrder, "byteOrder");
        this.valueType = Objects.requireNonNull(valueType, "valueType");
        this.factor = factor;
        this.offset = offset;
        this.definedMinimum = definedMinimum;
        this.definedMaximum = definedMaximum;
        this.unit = unit == null ? "" : unit;
        this.multiplexRole = Objects.requireNonNull(multiplexRole, "multiplexRole");
        this.multiplexValue = multiplexValue;
        this.receivers = List.copyOf(Objects.requireNonNullElse(receivers, List.of()));
    }

    public static DbcSignalDefinitionView from(DbcSignal signal) {
        Objects.requireNonNull(signal, "signal");
        return new DbcSignalDefinitionView(
                signal.name(), signal.startBit(), signal.bitLength(), signal.byteOrder(), signal.valueType(),
                signal.factor(), signal.offset(), signal.minimum(), signal.maximum(), signal.unit(),
                signal.multiplexRole(), signal.multiplexValue(), signal.receivers());
    }

    public String name() { return name; }
    public int startBit() { return startBit; }
    public int bitLength() { return bitLength; }
    public DbcByteOrder byteOrder() { return byteOrder; }

    public String byteOrderLabel() {
        return switch (byteOrder) {
            case INTEL -> "Intel / Little Endian";
            case MOTOROLA -> "Motorola / Big Endian";
        };
    }

    public DbcValueType valueType() { return valueType; }
    public String signednessLabel() { return valueType == DbcValueType.SIGNED ? "Signed" : "Unsigned"; }
    public double factor() { return factor; }
    public double offset() { return offset; }
    public double definedMinimum() { return definedMinimum; }
    public double definedMaximum() { return definedMaximum; }
    public String unit() { return unit; }
    public String unitDisplay() { return unit.isBlank() ? "\u2014" : unit; }
    public DbcMultiplexRole multiplexRole() { return multiplexRole; }
    public Optional<Integer> multiplexValue() { return Optional.ofNullable(multiplexValue); }

    public String multiplexLabel() {
        return switch (multiplexRole) {
            case NONE -> "\u2014";
            case MULTIPLEXOR -> "Multiplexer";
            case MULTIPLEXED -> multiplexValue == null ? "Mux" : "Mux " + multiplexValue;
        };
    }

    public List<String> receivers() { return receivers; }
    public String receiversDisplay() { return receivers.isEmpty() ? "\u2014" : String.join(", ", receivers); }

    public String conversionHint() {
        return String.format(Locale.ROOT, "physical = raw \u00d7 %s + %s", trimNumber(factor), trimNumber(offset));
    }

    private static String trimNumber(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }
}
