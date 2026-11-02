package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * Converts a master/time channel sample to {@code timestampNanos} exactly once:
 * raw → CC physical → unit scale → nanoseconds.
 */
public final class MdfMasterTimestampConverter {

    private MdfMasterTimestampConverter() {
    }

    public static OptionalLong toTimestampNanos(MdfDecodedField field) {
        if (field == null || !field.valid()) {
            return OptionalLong.empty();
        }
        MdfChannelConversion conversion = field.schema().conversion();
        MdfBitFieldExtractor.ExtractedValue value = field.value();
        if (value instanceof MdfBitFieldExtractor.ExtractedValue.IntegerValue iv) {
            long raw = iv.signedOrUnsigned();
            if (raw < 0) {
                return OptionalLong.empty();
            }
            try {
                if (conversion.isLinear()) {
                    BigDecimal physical = conversion.toPhysical(raw);
                    return scalePhysicalToNanos(physical, field.schema().unit(), true);
                }
                String unit = unit(field);
                if (unit.contains("ns") || (unit.isBlank() && field.schema().bitLength() >= 64)) {
                    return OptionalLong.of(raw);
                }
                if (unit.contains("us") || unit.contains("µs")) {
                    return OptionalLong.of(Math.multiplyExact(raw, 1_000L));
                }
                if (unit.contains("ms")) {
                    return OptionalLong.of(Math.multiplyExact(raw, 1_000_000L));
                }
                if (unit.contains("s") && !unit.contains("ns") && !unit.contains("ms") && !unit.contains("us")) {
                    return OptionalLong.of(Math.multiplyExact(raw, 1_000_000_000L));
                }
                return OptionalLong.of(raw);
            } catch (ArithmeticException e) {
                return OptionalLong.empty();
            }
        }
        if (value instanceof MdfBitFieldExtractor.ExtractedValue.FloatValue fv) {
            double raw = fv.value();
            if (!Double.isFinite(raw) || raw < 0) {
                return OptionalLong.empty();
            }
            try {
                BigDecimal physical = conversion.isLinear()
                        ? conversion.toPhysical(raw)
                        : BigDecimal.valueOf(raw);
                if (physical.signum() < 0) {
                    return OptionalLong.empty();
                }
                return scalePhysicalToNanos(physical, field.schema().unit(), true);
            } catch (ArithmeticException e) {
                return OptionalLong.empty();
            }
        }
        return OptionalLong.empty();
    }

    private static OptionalLong scalePhysicalToNanos(
            BigDecimal physical,
            String unit,
            boolean blankMeansSeconds
    ) {
        String u = unit == null ? "" : unit.toLowerCase(Locale.ROOT);
        try {
            if (u.contains("ns")) {
                return OptionalLong.of(physical.setScale(0, RoundingMode.HALF_UP).longValueExact());
            }
            if (u.contains("us") || u.contains("µs")) {
                return OptionalLong.of(MdfChannelConversion.physicalToNanos(physical.movePointLeft(3)));
            }
            if (u.contains("ms")) {
                return OptionalLong.of(MdfChannelConversion.physicalToNanos(physical.movePointLeft(6)));
            }
            if (u.contains("s") || (blankMeansSeconds && u.isBlank())) {
                return OptionalLong.of(MdfChannelConversion.physicalToNanos(physical));
            }
            return OptionalLong.of(physical.setScale(0, RoundingMode.HALF_UP).longValueExact());
        } catch (ArithmeticException e) {
            return OptionalLong.empty();
        }
    }

    private static String unit(MdfDecodedField field) {
        String u = field.schema().unit();
        return u == null ? "" : u.toLowerCase(Locale.ROOT);
    }
}
