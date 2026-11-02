package com.stellar.platform.parser.mf4.dynamicschema;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Objects;

/**
 * Channel conversion (CC block) applied to one raw sample.
 * <p>
 * Identity and linear keep the previous exact integer path. Rational, algebraic,
 * and tabular conversions are applied when the CC block is complete. Value-to-text
 * (CC type 7) and value-range-to-text (CC type 8) keep the raw code and return the
 * source label; they do not invent a numeric physical value. Any other type, or a
 * conversion that cannot be evaluated, stays unapplied: callers must keep the raw
 * sample and must not present that raw number as a physical value.
 */
public record MdfChannelConversion(
        int type,
        double offset,
        double factor,
        double[] parameters,
        String formula,
        boolean applied,
        String statusDetail,
        String[] labels,
        String defaultLabel,
        MdfChannelConversion defaultConversion
) {

    public static final int TYPE_IDENTITY = 0;
    public static final int TYPE_LINEAR = 1;
    public static final int TYPE_RATIONAL = 2;
    public static final int TYPE_ALGEBRAIC = 3;
    public static final int TYPE_TABULAR_INTERPOLATED = 4;
    public static final int TYPE_TABULAR_EXACT = 5;
    public static final int TYPE_RANGE_TABULAR = 6;
    /** ASAM value to text / scale (CCBLOCK type 7). */
    public static final int TYPE_VALUE_TO_TEXT = 7;
    /** ASAM value range to text / scale (CCBLOCK type 8). */
    public static final int TYPE_VALUE_RANGE_TO_TEXT = 8;

    public static final MdfChannelConversion IDENTITY = new MdfChannelConversion(TYPE_IDENTITY, 0.0, 1.0);

    public MdfChannelConversion {
        parameters = parameters == null ? new double[0] : Arrays.copyOf(parameters, parameters.length);
        formula = formula == null ? "" : formula;
        statusDetail = statusDetail == null ? "" : statusDetail;
        labels = labels == null ? new String[0] : Arrays.copyOf(labels, labels.length);
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == null) {
                labels[i] = "";
            }
        }
        defaultLabel = defaultLabel == null ? "" : defaultLabel;
    }

    /** Identity or linear conversion used by existing callers. */
    public MdfChannelConversion(int type, double offset, double factor) {
        this(type, offset, factor, new double[] {offset, factor}, "",
                type == TYPE_IDENTITY || type == TYPE_LINEAR, "", null, null, null);
        if (type == TYPE_LINEAR && (!Double.isFinite(offset) || !Double.isFinite(factor))) {
            throw new IllegalArgumentException("linear conversion requires finite offset/factor");
        }
    }

    public static MdfChannelConversion linear(double offset, double factor) {
        return new MdfChannelConversion(TYPE_LINEAR, offset, factor);
    }

    public static MdfChannelConversion rational(double p1, double p2, double p3, double p4, double p5, double p6) {
        double[] p = {p1, p2, p3, p4, p5, p6};
        if (!allFinite(p, 6)) {
            return unsupported(TYPE_RATIONAL, "rational conversion requires six finite parameters");
        }
        return numeric(TYPE_RATIONAL, p, "");
    }

    public static MdfChannelConversion algebraic(String formula) {
        MdfAlgebraicFormula.Prepared prepared = MdfAlgebraicFormula.prepare(formula);
        if (!prepared.accepted()) {
            return unsupported(TYPE_ALGEBRAIC, prepared.error());
        }
        return numeric(TYPE_ALGEBRAIC, new double[0], formula.trim());
    }

    public static MdfChannelConversion tabularInterpolated(double[] pairs) {
        return tabular(TYPE_TABULAR_INTERPOLATED, pairs, "interpolated tabular");
    }

    public static MdfChannelConversion tabularExact(double[] pairs) {
        return tabular(TYPE_TABULAR_EXACT, pairs, "exact tabular");
    }

    public static MdfChannelConversion rangeTabular(double[] triples) {
        if (triples == null || triples.length == 0 || triples.length % 3 != 0) {
            return unsupported(TYPE_RANGE_TABULAR, "range tabular conversion requires raw-min/raw-max/physical triples");
        }
        if (!allFinite(triples, triples.length)) {
            return unsupported(TYPE_RANGE_TABULAR, "range tabular conversion requires finite parameters");
        }
        for (int i = 0; i < triples.length; i += 3) {
            if (triples[i] > triples[i + 1]) {
                return unsupported(TYPE_RANGE_TABULAR, "range tabular minimum exceeds maximum");
            }
        }
        return numeric(TYPE_RANGE_TABULAR, triples, "");
    }

    public static MdfChannelConversion unsupported(int type, String detail) {
        String message = detail == null || detail.isBlank()
                ? "unsupported conversion cc_type=" + type
                : detail;
        return new MdfChannelConversion(type, 0.0, 1.0, new double[0], "", false, message, null, null, null);
    }

    /**
     * Builds a conversion from an ASAM CC data section. Incomplete or unknown
     * types are unapplied rather than identity. Text types without resolved
     * labels stay unapplied so a key is never presented as a label.
     */
    public static MdfChannelConversion fromAsam(int type, double[] values, String formula) {
        return fromAsam(type, values, formula, null, null, null);
    }

    /**
     * Same as {@link #fromAsam(int, double[], String)} with CC type 7/8 text
     * resolved from the source TX blocks. {@code defaultConversion} is the
     * optional extra {@code cc_ref} used only when no key or range matches.
     */
    public static MdfChannelConversion fromAsam(
            int type,
            double[] values,
            String formula,
            String[] labels,
            String defaultLabel,
            MdfChannelConversion defaultConversion
    ) {
        double[] vals = values == null ? new double[0] : values;
        return switch (type) {
            case TYPE_IDENTITY -> IDENTITY;
            case TYPE_LINEAR -> {
                if (vals.length < 2 || !Double.isFinite(vals[0]) || !Double.isFinite(vals[1])) {
                    yield unsupported(type, "linear conversion requires finite offset and factor");
                }
                yield linear(vals[0], vals[1]);
            }
            case TYPE_RATIONAL -> {
                if (vals.length < 6 || !allFinite(vals, 6)) {
                    yield unsupported(type, "rational conversion requires six finite parameters");
                }
                yield rational(vals[0], vals[1], vals[2], vals[3], vals[4], vals[5]);
            }
            case TYPE_ALGEBRAIC -> algebraic(formula);
            case TYPE_TABULAR_INTERPOLATED -> tabularInterpolated(vals);
            case TYPE_TABULAR_EXACT -> tabularExact(vals);
            case TYPE_RANGE_TABULAR -> rangeTabular(vals);
            case TYPE_VALUE_TO_TEXT -> valueToText(vals, labels, defaultLabel, defaultConversion);
            case TYPE_VALUE_RANGE_TO_TEXT -> rangeToText(vals, labels, defaultLabel, defaultConversion);
            default -> unsupported(type, "unsupported conversion cc_type=" + type);
        };
    }

    public boolean isLinear() {
        return applied && type == TYPE_LINEAR;
    }

    public boolean isIdentity() {
        return applied && (type == TYPE_IDENTITY || (type == TYPE_LINEAR && offset == 0.0 && factor == 1.0));
    }

    public BigDecimal toPhysical(long raw) {
        return toPhysical(BigDecimal.valueOf(raw));
    }

    public BigDecimal toPhysical(double raw) {
        if (!Double.isFinite(raw)) {
            throw new IllegalArgumentException("raw conversion input must be finite");
        }
        return toPhysical(BigDecimal.valueOf(raw));
    }

    public ConversionResult convert(double raw) {
        if (!applied) {
            return ConversionResult.rejected(statusDetail.isBlank()
                    ? "unsupported conversion cc_type=" + type
                    : statusDetail);
        }
        if (!Double.isFinite(raw)) {
            return ConversionResult.rejected("non-finite raw value");
        }
        return switch (type) {
            case TYPE_IDENTITY -> ConversionResult.ok(raw);
            case TYPE_LINEAR -> ConversionResult.ok(factor * raw + offset);
            case TYPE_RATIONAL -> rational(raw);
            case TYPE_ALGEBRAIC -> algebraic(raw);
            case TYPE_TABULAR_INTERPOLATED -> interpolate(raw);
            case TYPE_TABULAR_EXACT -> exact(raw);
            case TYPE_RANGE_TABULAR -> range(raw);
            case TYPE_VALUE_TO_TEXT -> valueToText(raw);
            case TYPE_VALUE_RANGE_TO_TEXT -> rangeToText(raw);
            default -> ConversionResult.rejected("unsupported conversion cc_type=" + type);
        };
    }

    private BigDecimal toPhysical(BigDecimal raw) {
        if (!applied) {
            throw new IllegalStateException(statusDetail.isBlank()
                    ? "unsupported conversion cc_type=" + type
                    : statusDetail);
        }
        if (isIdentity() || type == TYPE_IDENTITY) {
            return raw;
        }
        if (type == TYPE_LINEAR) {
            return BigDecimal.valueOf(factor)
                    .multiply(raw)
                    .add(BigDecimal.valueOf(offset));
        }
        ConversionResult result = convert(raw.doubleValue());
        if (!result.applied() || !result.text().isBlank()) {
            throw new IllegalStateException(result.text().isBlank()
                    ? result.statusDetail()
                    : "conversion produced text");
        }
        if (!Double.isFinite(result.physical())) {
            throw new IllegalStateException(result.statusDetail().isBlank()
                    ? "conversion produced a non-finite value"
                    : result.statusDetail());
        }
        return BigDecimal.valueOf(result.physical());
    }

    private ConversionResult rational(double raw) {
        double x2 = raw * raw;
        double numerator = parameters[0] * x2 + parameters[1] * raw + parameters[2];
        double denominator = parameters[3] * x2 + parameters[4] * raw + parameters[5];
        if (denominator == 0.0 || !Double.isFinite(denominator)) {
            return ConversionResult.rejected("rational denominator is zero");
        }
        double physical = numerator / denominator;
        if (!Double.isFinite(physical)) {
            return ConversionResult.rejected("rational conversion is non-finite");
        }
        return ConversionResult.ok(physical);
    }

    private ConversionResult algebraic(double raw) {
        MdfAlgebraicFormula.Prepared prepared = MdfAlgebraicFormula.prepare(formula);
        if (!prepared.accepted()) {
            return ConversionResult.rejected(prepared.error());
        }
        double physical = prepared.eval(raw);
        if (!Double.isFinite(physical)) {
            return ConversionResult.rejected("algebraic conversion is non-finite");
        }
        return ConversionResult.ok(physical);
    }

    private ConversionResult interpolate(double raw) {
        int pairs = parameters.length / 2;
        double firstKey = parameters[0];
        double lastKey = parameters[(pairs - 1) * 2];
        if (raw < firstKey || raw > lastKey) {
            return ConversionResult.rejected("tabular key is outside the defined range");
        }
        for (int i = 0; i < pairs; i++) {
            double key = parameters[i * 2];
            double physical = parameters[i * 2 + 1];
            if (raw == key) {
                return ConversionResult.ok(physical);
            }
            if (i + 1 < pairs && raw < parameters[(i + 1) * 2]) {
                double nextKey = parameters[(i + 1) * 2];
                double nextPhysical = parameters[(i + 1) * 2 + 1];
                double span = nextKey - key;
                double fraction = (raw - key) / span;
                return ConversionResult.ok(physical + fraction * (nextPhysical - physical));
            }
        }
        return ConversionResult.rejected("tabular key is outside the defined range");
    }

    private ConversionResult exact(double raw) {
        for (int i = 0; i < parameters.length; i += 2) {
            if (raw == parameters[i]) {
                return ConversionResult.ok(parameters[i + 1]);
            }
        }
        return ConversionResult.rejected("tabular key has no exact entry");
    }

    private ConversionResult range(double raw) {
        for (int i = 0; i < parameters.length; i += 3) {
            if (raw >= parameters[i] && raw <= parameters[i + 1]) {
                return ConversionResult.ok(parameters[i + 2]);
            }
        }
        return ConversionResult.rejected("tabular key is outside the defined range");
    }

    private ConversionResult valueToText(double raw) {
        for (int i = 0; i < parameters.length && i < labels.length; i++) {
            if (raw != parameters[i]) {
                continue;
            }
            if (labels[i].isBlank()) {
                return ConversionResult.rejected("text conversion reference is missing");
            }
            return ConversionResult.text(labels[i]);
        }
        return unmatched(raw);
    }

    private ConversionResult rangeToText(double raw) {
        int ranges = Math.min(labels.length, parameters.length / 2);
        for (int i = 0; i < ranges; i++) {
            double lower = parameters[i * 2];
            double upper = parameters[i * 2 + 1];
            if (lower > upper || raw < lower || raw > upper) {
                continue;
            }
            if (labels[i].isBlank()) {
                return ConversionResult.rejected("text conversion reference is missing");
            }
            return ConversionResult.text(labels[i]);
        }
        return unmatched(raw);
    }

    private ConversionResult unmatched(double raw) {
        if (!defaultLabel.isBlank()) {
            return ConversionResult.text(defaultLabel);
        }
        if (defaultConversion != null) {
            return defaultConversion.convert(raw);
        }
        return ConversionResult.rejected(type == TYPE_VALUE_TO_TEXT
                ? "tabular key has no exact entry"
                : "tabular key is outside the defined range");
    }

    private static MdfChannelConversion valueToText(
            double[] keys,
            String[] labels,
            String defaultLabel,
            MdfChannelConversion defaultConversion
    ) {
        String[] text = labels == null ? new String[0] : labels;
        if (keys.length != text.length) {
            return unsupported(TYPE_VALUE_TO_TEXT, "value to text conversion requires one label per key");
        }
        if (keys.length > 0 && !allFinite(keys, keys.length)) {
            return unsupported(TYPE_VALUE_TO_TEXT, "value to text conversion requires finite keys");
        }
        if (!hasUsableText(text) && !hasDefault(defaultLabel, defaultConversion)) {
            return unsupported(TYPE_VALUE_TO_TEXT, "text conversion references are missing");
        }
        return new MdfChannelConversion(
                TYPE_VALUE_TO_TEXT, 0.0, 1.0, keys, "", true, "", text, defaultLabel, defaultConversion);
    }

    private static MdfChannelConversion rangeToText(
            double[] limits,
            String[] labels,
            String defaultLabel,
            MdfChannelConversion defaultConversion
    ) {
        String[] text = labels == null ? new String[0] : labels;
        if (limits.length % 2 != 0) {
            return unsupported(TYPE_VALUE_RANGE_TO_TEXT, "value range to text requires lower/upper pairs");
        }
        if (text.length != limits.length / 2) {
            return unsupported(TYPE_VALUE_RANGE_TO_TEXT, "value range to text requires one label per range");
        }
        if (limits.length > 0 && !allFinite(limits, limits.length)) {
            return unsupported(TYPE_VALUE_RANGE_TO_TEXT, "value range to text requires finite limits");
        }
        if (!hasUsableRange(limits, text) && !hasDefault(defaultLabel, defaultConversion)) {
            return unsupported(
                    TYPE_VALUE_RANGE_TO_TEXT,
                    hasUsableText(text)
                            ? "range tabular minimum exceeds maximum"
                            : "text conversion references are missing");
        }
        return new MdfChannelConversion(
                TYPE_VALUE_RANGE_TO_TEXT, 0.0, 1.0, limits, "", true, "", text, defaultLabel, defaultConversion);
    }

    private static MdfChannelConversion numeric(int type, double[] parameters, String formula) {
        return new MdfChannelConversion(type, 0.0, 1.0, parameters, formula, true, "", null, null, null);
    }

    private static boolean hasDefault(String defaultLabel, MdfChannelConversion defaultConversion) {
        return (defaultLabel != null && !defaultLabel.isBlank()) || defaultConversion != null;
    }

    private static boolean hasUsableText(String[] labels) {
        for (String label : labels) {
            if (label != null && !label.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUsableRange(double[] limits, String[] labels) {
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] != null && !labels[i].isBlank() && limits[i * 2] <= limits[i * 2 + 1]) {
                return true;
            }
        }
        return false;
    }

    private static MdfChannelConversion tabular(int type, double[] pairs, String label) {
        if (pairs == null || pairs.length < 2 || pairs.length % 2 != 0) {
            return unsupported(type, label + " conversion requires raw/physical pairs");
        }
        if (!allFinite(pairs, pairs.length)) {
            return unsupported(type, label + " conversion requires finite parameters");
        }
        for (int i = 2; i < pairs.length; i += 2) {
            if (!(pairs[i] > pairs[i - 2])) {
                return unsupported(type, "tabular keys are not strictly increasing");
            }
        }
        return numeric(type, pairs, "");
    }

    private static boolean allFinite(double[] values, int count) {
        for (int i = 0; i < count; i++) {
            if (!Double.isFinite(values[i])) {
                return false;
            }
        }
        return true;
    }

    public static long physicalToNanos(BigDecimal physical) {
        Objects.requireNonNull(physical, "physical");
        return physical.movePointRight(9).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public record ConversionResult(boolean applied, double physical, String statusDetail, String text) {
        public ConversionResult {
            statusDetail = statusDetail == null ? "" : statusDetail;
            text = text == null ? "" : text;
        }

        public static ConversionResult ok(double physical) {
            return new ConversionResult(true, physical, "", "");
        }

        public static ConversionResult text(String label) {
            return new ConversionResult(true, Double.NaN, "", label == null ? "" : label);
        }

        public static ConversionResult rejected(String statusDetail) {
            return new ConversionResult(false, Double.NaN, statusDetail == null ? "" : statusDetail, "");
        }
    }
}
