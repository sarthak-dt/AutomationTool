package com.stellar.platform.parser.mf4.dynamicschema;

import com.stellar.platform.domain.SignalValidity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * MDF 3.x channel conversion (CC block). Type numbers are the MDF 3 identifiers.
 * They are not the MDF 4 {@link MdfChannelConversion} numbers.
 * <p>
 * Internal value to physical value, ASAM MDF 3.3:
 * <ul>
 *   <li>0 linear: {@code phys = raw * P2 + P1}</li>
 *   <li>1 tabular with interpolation</li>
 *   <li>2 tabular, no interpolation</li>
 *   <li>6 polynomial</li>
 *   <li>7 exponential</li>
 *   <li>8 logarithmic</li>
 *   <li>9 rational</li>
 *   <li>10 algebraic text formula in {@code X}</li>
 *   <li>11 value to text</li>
 *   <li>12 value range to text</li>
 * </ul>
 * A missing block is identity. A truncated or unknown block does not yield the raw
 * number as a physical value.
 */
public final class Mdf3Conversion {

    public static final int TYPE_LINEAR = 0;
    public static final int TYPE_TABULAR_INTERPOLATED = 1;
    public static final int TYPE_TABULAR = 2;
    public static final int TYPE_POLYNOMIAL = 6;
    public static final int TYPE_EXPONENTIAL = 7;
    public static final int TYPE_LOGARITHMIC = 8;
    public static final int TYPE_RATIONAL = 9;
    public static final int TYPE_ALGEBRAIC = 10;
    public static final int TYPE_VALUE_TO_TEXT = 11;
    public static final int TYPE_VALUE_RANGE_TO_TEXT = 12;
    /** MDF 3 "no conversion" marker. It is identity, not an MDF 4 type number. */
    public static final int TYPE_NONE = 65535;

    private static final int HEADER_BYTES = 46;
    private static final int TEXT_BYTES = 32;
    private static final Mdf3Conversion IDENTITY = new Mdf3Conversion(
            -1, true, 0.0, 1.0, new double[0], new String[0], null, "");

    private final int type;
    private final boolean structurallyApplied;
    private final double offset;
    private final double factor;
    private final double[] parameters;
    private final String[] texts;
    private final MdfAlgebraicFormula.Prepared formula;
    private final String failure;

    private Mdf3Conversion(
            int type,
            boolean structurallyApplied,
            double offset,
            double factor,
            double[] parameters,
            String[] texts,
            MdfAlgebraicFormula.Prepared formula,
            String failure
    ) {
        this.type = type;
        this.structurallyApplied = structurallyApplied;
        this.offset = offset;
        this.factor = factor;
        this.parameters = parameters;
        this.texts = texts;
        this.formula = formula;
        this.failure = failure == null ? "" : failure;
    }

    public static Mdf3Conversion identity() {
        return IDENTITY;
    }

    public static Mdf3Conversion failed(String detail) {
        String message = detail == null || detail.isBlank()
                ? "MDF3 conversion could not be applied"
                : detail;
        return new Mdf3Conversion(-1, false, 0.0, 1.0, new double[0], new String[0], null, message);
    }

    /**
     * @param block the CC block, little-endian, including the {@code CC} identifier
     */
    public static Mdf3Conversion parse(ByteBuffer block) {
        if (block == null || block.limit() < HEADER_BYTES) {
            return failed("truncated MDF3 CC block");
        }
        int size = Math.min(block.limit(), Short.toUnsignedInt(block.getShort(2)));
        if (size < HEADER_BYTES) {
            return failed("truncated MDF3 CC block");
        }
        int type = Short.toUnsignedInt(block.getShort(42));
        int count = Short.toUnsignedInt(block.getShort(44));
        return switch (type) {
            case TYPE_NONE -> identity();
            case TYPE_LINEAR -> linear(block, size);
            case TYPE_TABULAR_INTERPOLATED, TYPE_TABULAR -> tabular(type, block, size, count);
            case TYPE_POLYNOMIAL, TYPE_RATIONAL -> polynomialOrRational(type, block, size);
            case TYPE_EXPONENTIAL, TYPE_LOGARITHMIC -> exponentialOrLog(type, block, size);
            case TYPE_ALGEBRAIC -> algebraic(block, size);
            case TYPE_VALUE_TO_TEXT -> valueToText(block, size, count);
            case TYPE_VALUE_RANGE_TO_TEXT -> valueRangeToText(block, size, count);
            default -> failed("unsupported MDF3 conversion cc_type=" + type);
        };
    }

    public int type() {
        return type;
    }

    public boolean structurallyApplied() {
        return structurallyApplied;
    }

    public String failure() {
        return failure;
    }

    public Result apply(double raw) {
        if (!structurallyApplied) {
            return Result.failed(failure);
        }
        if (!Double.isFinite(raw)) {
            return Result.nonFinite("non-finite raw value");
        }
        if (type < 0) {
            return Result.numeric(raw);
        }
        return switch (type) {
            case TYPE_LINEAR -> Result.numeric(raw * factor + offset);
            case TYPE_TABULAR_INTERPOLATED -> interpolate(raw);
            case TYPE_TABULAR -> step(raw);
            case TYPE_POLYNOMIAL -> polynomial(raw);
            case TYPE_RATIONAL -> rational(raw);
            case TYPE_EXPONENTIAL -> exponential(raw, true);
            case TYPE_LOGARITHMIC -> exponential(raw, false);
            case TYPE_ALGEBRAIC -> algebraic(raw);
            case TYPE_VALUE_TO_TEXT -> valueToText(raw);
            case TYPE_VALUE_RANGE_TO_TEXT -> valueRangeToText(raw);
            default -> Result.failed(failure.isBlank() ? "unsupported MDF3 conversion cc_type=" + type : failure);
        };
    }

    private static Mdf3Conversion linear(ByteBuffer block, int size) {
        if (size < HEADER_BYTES + 16) {
            return failed("truncated MDF3 linear conversion");
        }
        double p1 = block.getDouble(HEADER_BYTES);
        double p2 = block.getDouble(HEADER_BYTES + 8);
        if (!Double.isFinite(p1) || !Double.isFinite(p2)) {
            return failed("MDF3 linear conversion parameters are not finite");
        }
        return new Mdf3Conversion(TYPE_LINEAR, true, p1, p2, new double[] {p1, p2}, new String[0], null, "");
    }

    private static Mdf3Conversion tabular(int type, ByteBuffer block, int size, int pairs) {
        if (pairs <= 0) {
            return failed("MDF3 tabular conversion has no pairs");
        }
        if (size < HEADER_BYTES + pairs * 16L) {
            return failed("truncated MDF3 tabular conversion");
        }
        double[] keys = new double[pairs];
        double[] physical = new double[pairs];
        for (int i = 0; i < pairs; i++) {
            keys[i] = block.getDouble(HEADER_BYTES + i * 16);
            physical[i] = block.getDouble(HEADER_BYTES + i * 16 + 8);
            if (!Double.isFinite(keys[i]) || !Double.isFinite(physical[i])) {
                return failed("MDF3 tabular conversion parameters are not finite");
            }
            if (i > 0 && keys[i] <= keys[i - 1]) {
                return failed("MDF3 tabular conversion keys are not strictly increasing");
            }
        }
        double[] packed = new double[pairs * 2];
        for (int i = 0; i < pairs; i++) {
            packed[i] = keys[i];
            packed[pairs + i] = physical[i];
        }
        return new Mdf3Conversion(type, true, 0.0, 1.0, packed, new String[0], null, "");
    }

    private static Mdf3Conversion polynomialOrRational(int type, ByteBuffer block, int size) {
        if (size < HEADER_BYTES + 48) {
            return failed("truncated MDF3 polynomial conversion");
        }
        double[] p = readDoubles(block, 6);
        if (!allFinite(p)) {
            return failed("MDF3 polynomial conversion parameters are not finite");
        }
        return new Mdf3Conversion(type, true, 0.0, 1.0, p, new String[0], null, "");
    }

    private static Mdf3Conversion exponentialOrLog(int type, ByteBuffer block, int size) {
        if (size < HEADER_BYTES + 56) {
            return failed("truncated MDF3 exponential conversion");
        }
        double[] p = readDoubles(block, 7);
        if (!allFinite(p)) {
            return failed("MDF3 exponential conversion parameters are not finite");
        }
        boolean branchA = p[3] == 0.0 && p[0] != 0.0 && p[1] != 0.0;
        boolean branchB = p[0] == 0.0 && p[3] != 0.0 && p[4] != 0.0;
        if (!branchA && !branchB) {
            return failed("MDF3 exponential conversion parameters are not a supported branch");
        }
        return new Mdf3Conversion(type, true, 0.0, 1.0, p, new String[0], null, "");
    }

    private static Mdf3Conversion algebraic(ByteBuffer block, int size) {
        if (size <= HEADER_BYTES) {
            return failed("MDF3 algebraic formula is missing");
        }
        int length = size - HEADER_BYTES;
        byte[] raw = new byte[length];
        for (int i = 0; i < length; i++) {
            raw[i] = block.get(HEADER_BYTES + i);
        }
        int end = 0;
        while (end < raw.length && raw[end] != 0) {
            end++;
        }
        String formula = new String(raw, 0, end, StandardCharsets.ISO_8859_1).trim();
        MdfAlgebraicFormula.Prepared prepared = MdfAlgebraicFormula.prepare(formula);
        if (!prepared.accepted()) {
            return failed(prepared.error() == null ? "MDF3 algebraic formula is not supported" : prepared.error());
        }
        return new Mdf3Conversion(TYPE_ALGEBRAIC, true, 0.0, 1.0, new double[0], new String[0], prepared, "");
    }

    private static Mdf3Conversion valueToText(ByteBuffer block, int size, int entries) {
        if (entries <= 0) {
            return failed("MDF3 value-to-text conversion has no entries");
        }
        int stride = 8 + TEXT_BYTES;
        if (size < HEADER_BYTES + (long) entries * stride) {
            return failed("truncated MDF3 value-to-text conversion");
        }
        double[] keys = new double[entries];
        String[] texts = new String[entries];
        for (int i = 0; i < entries; i++) {
            int at = HEADER_BYTES + i * stride;
            keys[i] = block.getDouble(at);
            if (!Double.isFinite(keys[i])) {
                return failed("MDF3 value-to-text key is not finite");
            }
            texts[i] = textAt(block, at + 8);
            if (!plainLabel(texts[i])) {
                return failed("MDF3 value-to-text entry is not a plain label");
            }
        }
        return new Mdf3Conversion(TYPE_VALUE_TO_TEXT, true, 0.0, 1.0, keys, texts, null, "");
    }

    private static Mdf3Conversion valueRangeToText(ByteBuffer block, int size, int entries) {
        if (entries <= 0) {
            return failed("MDF3 value-range-to-text conversion has no entries");
        }
        int stride = 16 + TEXT_BYTES;
        if (size < HEADER_BYTES + (long) entries * stride) {
            return failed("truncated MDF3 value-range-to-text conversion");
        }
        double[] bounds = new double[entries * 2];
        String[] texts = new String[entries];
        for (int i = 0; i < entries; i++) {
            int at = HEADER_BYTES + i * stride;
            double lower = block.getDouble(at);
            double upper = block.getDouble(at + 8);
            if (!Double.isFinite(lower) || !Double.isFinite(upper) || lower > upper) {
                return failed("MDF3 value-range-to-text bounds are invalid");
            }
            bounds[i * 2] = lower;
            bounds[i * 2 + 1] = upper;
            texts[i] = textAt(block, at + 16);
            if (!plainLabel(texts[i])) {
                return failed("MDF3 value-range-to-text entry is not a plain label");
            }
        }
        return new Mdf3Conversion(TYPE_VALUE_RANGE_TO_TEXT, true, 0.0, 1.0, bounds, texts, null, "");
    }

    private Result interpolate(double raw) {
        int pairs = parameters.length / 2;
        double[] keys = parameters;
        if (raw <= keys[0]) {
            return Result.numeric(parameters[pairs]);
        }
        if (raw >= keys[pairs - 1]) {
            return Result.numeric(parameters[pairs + pairs - 1]);
        }
        int hi = 1;
        while (hi < pairs && keys[hi] < raw) {
            hi++;
        }
        int lo = hi - 1;
        double span = keys[hi] - keys[lo];
        double t = (raw - keys[lo]) / span;
        double physical = parameters[pairs + lo] + t * (parameters[pairs + hi] - parameters[pairs + lo]);
        return finiteOrNot(physical);
    }

    private Result step(double raw) {
        int pairs = parameters.length / 2;
        int chosen = 0;
        for (int i = 0; i < pairs; i++) {
            if (parameters[i] <= raw) {
                chosen = i;
            } else {
                break;
            }
        }
        return Result.numeric(parameters[pairs + chosen]);
    }

    private Result polynomial(double raw) {
        double p1 = parameters[0];
        double p2 = parameters[1];
        double p3 = parameters[2];
        double p4 = parameters[3];
        double p5 = parameters[4];
        double p6 = parameters[5];
        double shifted = raw - p5 - p6;
        double denominator = p3 * shifted - p1;
        if (denominator == 0.0) {
            return Result.failed("MDF3 polynomial denominator is zero");
        }
        return finiteOrNot((p2 - p4 * shifted) / denominator);
    }

    private Result rational(double raw) {
        double x2 = raw * raw;
        double denominator = parameters[3] * x2 + parameters[4] * raw + parameters[5];
        if (denominator == 0.0) {
            return Result.failed("MDF3 rational denominator is zero");
        }
        double numerator = parameters[0] * x2 + parameters[1] * raw + parameters[2];
        return finiteOrNot(numerator / denominator);
    }

    private Result exponential(double raw, boolean exponential) {
        double inner;
        if (parameters[3] == 0.0) {
            inner = ((raw - parameters[6]) * parameters[5] - parameters[2]) / parameters[0];
            if (!exponential && inner <= 0.0) {
                return Result.failed("MDF3 logarithmic argument is not positive");
            }
            double magnitude = exponential ? Math.exp(inner) : Math.log(inner);
            return finiteOrNot(magnitude / parameters[1]);
        }
        if (raw == parameters[6]) {
            return Result.failed("MDF3 exponential divisor is zero");
        }
        inner = (parameters[2] / (raw - parameters[6]) - parameters[5]) / parameters[3];
        if (!exponential && inner <= 0.0) {
            return Result.failed("MDF3 logarithmic argument is not positive");
        }
        double magnitude = exponential ? Math.exp(inner) : Math.log(inner);
        return finiteOrNot(magnitude / parameters[4]);
    }

    private Result algebraic(double raw) {
        double value = formula.eval(raw);
        return finiteOrNot(value);
    }

    private Result valueToText(double raw) {
        for (int i = 0; i < parameters.length; i++) {
            if (Double.compare(parameters[i], raw) == 0) {
                return Result.text(texts[i]);
            }
        }
        return Result.failed("MDF3 value-to-text key is not in the table");
    }

    private Result valueRangeToText(double raw) {
        for (int i = 0; i < texts.length; i++) {
            double lower = parameters[i * 2];
            double upper = parameters[i * 2 + 1];
            if (raw >= lower && raw <= upper) {
                return Result.text(texts[i]);
            }
        }
        return Result.failed("MDF3 value is outside every text range");
    }

    private static Result finiteOrNot(double value) {
        if (!Double.isFinite(value)) {
            return Result.nonFinite("MDF3 conversion produced a non-finite physical value");
        }
        return Result.numeric(value);
    }

    private static double[] readDoubles(ByteBuffer block, int count) {
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = block.getDouble(HEADER_BYTES + i * 8);
        }
        return values;
    }

    private static boolean allFinite(double[] values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean plainLabel(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String upper = text.toUpperCase(java.util.Locale.ROOT);
        return !upper.contains("LINEAR") && !upper.contains("CONV") && text.indexOf('=') < 0 && text.indexOf('{') < 0;
    }

    private static String textAt(ByteBuffer block, int offset) {
        byte[] raw = new byte[TEXT_BYTES];
        for (int i = 0; i < TEXT_BYTES; i++) {
            raw[i] = block.get(offset + i);
        }
        int end = 0;
        while (end < raw.length) {
            int c = raw[end] & 0xFF;
            if (c == 0) {
                break;
            }
            end++;
        }
        return new String(raw, 0, end, StandardCharsets.ISO_8859_1).trim();
    }

    public record Result(double physical, String text, SignalValidity validity, String detail) {
        public static Result numeric(double physical) {
            return new Result(physical, null, SignalValidity.VALID, "");
        }

        public static Result text(String text) {
            // VALID + NaN + label. Not CONVERSION_FAILED, and not a numeric zero.
            return new Result(Double.NaN, text == null ? "" : text, SignalValidity.VALID, "");
        }

        public static Result failed(String detail) {
            String message = detail == null || detail.isBlank()
                    ? "MDF3 conversion could not be applied"
                    : detail;
            return new Result(Double.NaN, null, SignalValidity.CONVERSION_FAILED, message);
        }

        public static Result nonFinite(String detail) {
            return new Result(Double.NaN, null, SignalValidity.NON_FINITE_VALUE,
                    detail == null || detail.isBlank() ? "non-finite physical value" : detail);
        }

        public boolean numericApplied() {
            return validity == SignalValidity.VALID && text == null && Double.isFinite(physical);
        }
    }
}
