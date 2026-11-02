package com.stellar.platform.dbc.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable DBC signal definition.
 */
public record DbcSignal(
        String name,
        int startBit,
        int bitLength,
        DbcByteOrder byteOrder,
        DbcValueType valueType,
        double factor,
        double offset,
        double minimum,
        double maximum,
        String unit,
        List<String> receivers,
        DbcMultiplexRole multiplexRole,
        Integer multiplexValue,
        /**
         * Vector extended multiplex ({@code mNM}): gated by the parent multiplexor value and
         * itself acts as a secondary multiplexor for leaf {@code mN} signals when
         * {@code SG_MUL_VAL_} is absent.
         */
        boolean secondaryMultiplexor,
        /**
         * Explicit extended multiplex gates from {@code SG_MUL_VAL_}. Empty means legacy
         * {@code M}/{@code mN}/{@code mNM} applicability rules apply.
         */
        List<DbcMultiplexCondition> multiplexConditions,
        String comment,
        DbcValueTable valueTable,
        /**
         * Integer bit field, or IEEE float/double declared by {@code SIG_VALTYPE_}.
         * {@code null} is stored as {@link DbcRawEncoding#INTEGER}.
         */
        DbcRawEncoding rawEncoding,
        /** Explicit {@code GenSigInvalidValue} raw code. Not taken from {@code BA_DEF_DEF_}. */
        Long invalidRawValue,
        /** Explicit {@code GenSigInactiveValue} raw code. A reserved value, not a start value. */
        Long inactiveRawValue,
        /**
         * Explicit {@code GenSigStartValue}. Initialization only; never treated as invalid.
         */
        Long startRawValue
) {

    public DbcSignal {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("signal name must not be blank");
        }
        if (startBit < 0) {
            throw new IllegalArgumentException("startBit must be >= 0");
        }
        if (bitLength <= 0 || bitLength > 64) {
            throw new IllegalArgumentException("bitLength must be in 1..64");
        }
        Objects.requireNonNull(byteOrder, "byteOrder");
        Objects.requireNonNull(valueType, "valueType");
        unit = unit == null ? "" : unit;
        receivers = List.copyOf(Objects.requireNonNullElse(receivers, List.of()));
        Objects.requireNonNull(multiplexRole, "multiplexRole");
        if (multiplexRole == DbcMultiplexRole.MULTIPLEXED && multiplexValue == null
                && (multiplexConditions == null || multiplexConditions.isEmpty())) {
            throw new IllegalArgumentException("multiplexed signal requires multiplexValue or SG_MUL_VAL_ conditions");
        }
        if (secondaryMultiplexor && multiplexRole != DbcMultiplexRole.MULTIPLEXED) {
            throw new IllegalArgumentException("secondaryMultiplexor requires MULTIPLEXED role");
        }
        multiplexConditions = List.copyOf(Objects.requireNonNullElse(multiplexConditions, List.of()));
        valueTable = valueTable == null ? DbcValueTable.empty() : valueTable;
        rawEncoding = rawEncoding == null ? DbcRawEncoding.INTEGER : rawEncoding;
        if (rawEncoding == DbcRawEncoding.IEEE_FLOAT32 && bitLength != 32) {
            throw new IllegalArgumentException("IEEE float signal requires bitLength 32");
        }
        if (rawEncoding == DbcRawEncoding.IEEE_FLOAT64 && bitLength != 64) {
            throw new IllegalArgumentException("IEEE double signal requires bitLength 64");
        }
    }

    /**
     * Convenience constructor for flat (non-extended) multiplex signals.
     */
    public DbcSignal(
            String name,
            int startBit,
            int bitLength,
            DbcByteOrder byteOrder,
            DbcValueType valueType,
            double factor,
            double offset,
            double minimum,
            double maximum,
            String unit,
            List<String> receivers,
            DbcMultiplexRole multiplexRole,
            Integer multiplexValue,
            String comment,
            DbcValueTable valueTable
    ) {
        this(
                name, startBit, bitLength, byteOrder, valueType, factor, offset, minimum, maximum,
                unit, receivers, multiplexRole, multiplexValue, false, List.of(), comment, valueTable,
                DbcRawEncoding.INTEGER, null, null, null
        );
    }

    /**
     * Constructor used by the SG_ line parser (no {@code SG_MUL_VAL_} yet).
     */
    public DbcSignal(
            String name,
            int startBit,
            int bitLength,
            DbcByteOrder byteOrder,
            DbcValueType valueType,
            double factor,
            double offset,
            double minimum,
            double maximum,
            String unit,
            List<String> receivers,
            DbcMultiplexRole multiplexRole,
            Integer multiplexValue,
            boolean secondaryMultiplexor,
            String comment,
            DbcValueTable valueTable
    ) {
        this(
                name, startBit, bitLength, byteOrder, valueType, factor, offset, minimum, maximum,
                unit, receivers, multiplexRole, multiplexValue, secondaryMultiplexor, List.of(),
                comment, valueTable, DbcRawEncoding.INTEGER, null, null, null
        );
    }

    public Optional<Integer> multiplexValueOptional() {
        return Optional.ofNullable(multiplexValue);
    }

    public boolean hasExplicitMultiplexConditions() {
        return !multiplexConditions.isEmpty();
    }

    public boolean isApplicableForMux(Long muxRawValue) {
        return switch (multiplexRole) {
            case NONE, MULTIPLEXOR -> true;
            case MULTIPLEXED -> muxRawValue != null && multiplexValue != null
                    && muxRawValue.longValue() == multiplexValue.longValue();
        };
    }

    /**
     * Explicit {@code SG_MUL_VAL_} applicability: every condition's multiplexor must already
     * be decoded and match at least one of its ranges (AND across distinct multiplexors).
     */
    public boolean isApplicableForExplicitMultiplex(Map<String, Long> decodedRawBySignalName) {
        Objects.requireNonNull(decodedRawBySignalName, "decodedRawBySignalName");
        if (multiplexConditions.isEmpty()) {
            return switch (multiplexRole) {
                case NONE, MULTIPLEXOR -> true;
                case MULTIPLEXED -> false;
            };
        }
        for (DbcMultiplexCondition condition : multiplexConditions) {
            Long raw = decodedRawBySignalName.get(condition.multiplexorSignalName());
            if (raw == null || !condition.matches(raw)) {
                return false;
            }
        }
        return true;
    }

    public DbcSignal withMultiplexConditions(List<DbcMultiplexCondition> conditions) {
        return new DbcSignal(
                name, startBit, bitLength, byteOrder, valueType, factor, offset, minimum, maximum,
                unit, receivers, multiplexRole, multiplexValue, secondaryMultiplexor,
                conditions, comment, valueTable, rawEncoding, invalidRawValue, inactiveRawValue, startRawValue
        );
    }

    public DbcSignal withCommentAndValueTable(String newComment, DbcValueTable table) {
        return new DbcSignal(
                name, startBit, bitLength, byteOrder, valueType, factor, offset, minimum, maximum,
                unit, receivers, multiplexRole, multiplexValue, secondaryMultiplexor,
                multiplexConditions, newComment, table, rawEncoding, invalidRawValue, inactiveRawValue,
                startRawValue
        );
    }

    public DbcSignal withRawEncoding(DbcRawEncoding encoding) {
        return new DbcSignal(
                name, startBit, bitLength, byteOrder, valueType, factor, offset, minimum, maximum,
                unit, receivers, multiplexRole, multiplexValue, secondaryMultiplexor,
                multiplexConditions, comment, valueTable, encoding, invalidRawValue, inactiveRawValue,
                startRawValue
        );
    }

    public DbcSignal withRawAttributes(Long invalidRaw, Long inactiveRaw, Long startRaw) {
        return new DbcSignal(
                name, startBit, bitLength, byteOrder, valueType, factor, offset, minimum, maximum,
                unit, receivers, multiplexRole, multiplexValue, secondaryMultiplexor,
                multiplexConditions, comment, valueTable, rawEncoding, invalidRaw, inactiveRaw, startRaw
        );
    }
}
