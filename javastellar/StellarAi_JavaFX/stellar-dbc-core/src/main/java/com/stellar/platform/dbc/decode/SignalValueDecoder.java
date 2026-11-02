package com.stellar.platform.dbc.decode;

import com.stellar.platform.dbc.model.DbcRawEncoding;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.model.DbcValueType;
import com.stellar.platform.domain.SignalValidity;

/**
 * Turns an extracted bit field into a physical value.
 * <p>
 * Integer signals use {@code physical = raw * factor + offset}. IEEE signals
 * reinterpret the extracted bits as float or double first, then apply the same
 * factor and offset. Invalid and inactive raw codes are flagged and are not
 * replaced with zero. {@code GenSigStartValue} is ignored here.
 */
public final class SignalValueDecoder {

    private SignalValueDecoder() {
    }

    public static Decoded decode(DbcSignal signal, long extractedRaw) {
        int bits = signal.bitLength();
        long mask = bits == 64 ? -1L : (1L << bits) - 1L;
        long unsigned = extractedRaw & mask;
        DbcRawEncoding encoding = signal.rawEncoding();
        double real;
        long storedRaw;
        if (encoding == DbcRawEncoding.IEEE_FLOAT32) {
            real = Float.intBitsToFloat((int) unsigned);
            storedRaw = unsigned;
        } else if (encoding == DbcRawEncoding.IEEE_FLOAT64) {
            real = Double.longBitsToDouble(unsigned);
            storedRaw = unsigned;
        } else {
            real = extractedRaw;
            storedRaw = extractedRaw;
        }
        double physical = real * signal.factor() + signal.offset();
        boolean nonFinite = !Double.isFinite(real) || !Double.isFinite(physical);
        boolean invalid = matches(signal.invalidRawValue(), storedRaw, unsigned, bits);
        boolean inactive = !invalid && matches(signal.inactiveRawValue(), storedRaw, unsigned, bits);
        String textual = signal.valueTable().textFor(storedRaw).orElse(null);
        if (textual == null && encoding == DbcRawEncoding.INTEGER) {
            textual = signal.valueTable().textFor(extractedRaw).orElse(null);
        }
        if (textual == null && invalid) {
            textual = "invalid";
        } else if (textual == null && inactive) {
            textual = "inactive";
        }
        String reservation = invalid ? "GenSigInvalidValue" : inactive ? "GenSigInactiveValue" : null;
        SignalValidity validity = invalid
                ? SignalValidity.INVALID_RAW_CODE
                : inactive
                ? SignalValidity.INACTIVE_RAW_CODE
                : nonFinite
                ? SignalValidity.NON_FINITE_VALUE
                : SignalValidity.VALID;
        return new Decoded(physical, storedRaw, textual, invalid || inactive, nonFinite, reservation, validity);
    }

    /**
     * Matches the attribute as the stored raw, the unsigned bit pattern, or the
     * sign-extended integer of that pattern. Does not fold a wider code into the field.
     */
    static boolean matches(Long code, long storedRaw, long unsignedBits, int bitLength) {
        if (code == null) {
            return false;
        }
        if (code.longValue() == storedRaw || code.longValue() == unsignedBits) {
            return true;
        }
        if (bitLength >= 64) {
            return false;
        }
        long signed = SignalBitExtractor.applySignedness(unsignedBits, bitLength, DbcValueType.SIGNED);
        return code.longValue() == signed;
    }

    public record Decoded(
            double physical,
            long raw,
            String textual,
            boolean valueInvalid,
            boolean nonFinite,
            String reservation,
            SignalValidity validity
    ) {
    }
}
