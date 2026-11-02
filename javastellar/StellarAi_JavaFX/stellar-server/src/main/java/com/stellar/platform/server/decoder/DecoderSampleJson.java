package com.stellar.platform.server.decoder;

import com.stellar.platform.domain.catalog.SignalSamplePoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON-safe sample values.
 * <p>
 * Timestamps and raw integers are exact decimal strings. Finite physical values use
 * {@link Double#toString(double)}, which is the round-trip form of the stored IEEE-754 value.
 * NaN and infinities are the tokens {@code NaN}, {@code Infinity}, and {@code -Infinity}
 * inside a string, never bare JSON numeric literals. A missing raw value is
 * {@code unavailable}, which is distinct from a stored number, a null textual label,
 * and a validity status.
 */
public final class DecoderSampleJson {

    private DecoderSampleJson() {
    }

    public static Map<String, Object> sample(SignalSamplePoint point, long sampleIndex) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("timestampNs", Long.toString(point.timestampNanos()));
        sample.put("raw", raw(point.rawValue()));
        sample.put("physical", physical(point.physicalValue()));
        sample.put("textualValue", point.textualValue());
        sample.put("validity", point.validity().name());
        sample.put("sampleIndex", Long.toString(sampleIndex));
        return sample;
    }

    public static Map<String, Object> raw(Long rawValue) {
        Map<String, Object> raw = new LinkedHashMap<>();
        if (rawValue == null) {
            raw.put("representation", "unavailable");
            raw.put("value", null);
            return raw;
        }
        raw.put("representation", "integer");
        raw.put("value", Long.toString(rawValue));
        return raw;
    }

    public static Map<String, Object> physical(double value) {
        Map<String, Object> physical = new LinkedHashMap<>();
        if (Double.isNaN(value)) {
            physical.put("representation", "non-finite");
            physical.put("value", "NaN");
            return physical;
        }
        if (value == Double.POSITIVE_INFINITY) {
            physical.put("representation", "non-finite");
            physical.put("value", "Infinity");
            return physical;
        }
        if (value == Double.NEGATIVE_INFINITY) {
            physical.put("representation", "non-finite");
            physical.put("value", "-Infinity");
            return physical;
        }
        physical.put("representation", "java-double");
        physical.put("value", Double.toString(value));
        return physical;
    }

    /**
     * Parallel asammdf-style columns for one page of one signal.
     * <p>
     * {@code timestamps} are {@code timestampNanos / 1e9} seconds. Nothing is subtracted from
     * the stored timestamp. Values whose magnitude exceeds the 53-bit integer range of IEEE-754
     * cannot round-trip through a JSON number; {@code timestampsNs} is the exact signed
     * nanosecond value as a decimal string. Finite {@code samples} are the stored physical
     * doubles. Non-finite samples are objects, never bare JSON {@code NaN} or {@code Infinity}.
     * {@code rawSamples} are JSON numbers inside {@code ±9007199254740991} and decimal strings
     * beyond that. A null raw value stays null. {@code sourceDatetime} is the file's recording
     * clock plus a relative sample offset, or the absolute sample clock when the timestamp
     * already contains a date. It is null when the file has no recording date.
     */
    public static Map<String, List<?>> columns(List<SignalSamplePoint> points, long firstIndex) {
        return columns(points, firstIndex, null);
    }

    public static Map<String, List<?>> columns(
            List<SignalSamplePoint> points,
            long firstIndex,
            SourceClock clock
    ) {
        List<Double> timestamps = new ArrayList<>(points.size());
        List<String> timestampsNs = new ArrayList<>(points.size());
        List<String> sourceDatetime = new ArrayList<>(points.size());
        List<Object> samples = new ArrayList<>(points.size());
        List<Object> rawSamples = new ArrayList<>(points.size());
        List<String> textValues = new ArrayList<>(points.size());
        List<String> validity = new ArrayList<>(points.size());
        List<String> sampleIndex = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            SignalSamplePoint point = points.get(i);
            timestamps.add(point.timestampNanos() / 1_000_000_000d);
            timestampsNs.add(Long.toString(point.timestampNanos()));
            sourceDatetime.add(clock == null || !clock.available() ? null : clock.format(point.timestampNanos()));
            samples.add(physicalColumn(point.physicalValue()));
            rawSamples.add(rawColumn(point.rawValue()));
            textValues.add(point.textualValue());
            validity.add(point.validity().name());
            sampleIndex.add(Long.toString(firstIndex + i));
        }
        Map<String, List<?>> columns = new LinkedHashMap<>();
        columns.put("timestamps", timestamps);
        columns.put("timestampsNs", timestampsNs);
        columns.put("sourceDatetime", sourceDatetime);
        columns.put("samples", samples);
        columns.put("rawSamples", rawSamples);
        columns.put("textValues", textValues);
        columns.put("validity", validity);
        columns.put("sampleIndex", sampleIndex);
        return columns;
    }

    static final long MAX_JSON_SAFE_INTEGER = 9_007_199_254_740_991L;

    private static Object physicalColumn(double value) {
        if (Double.isFinite(value)) {
            return value;
        }
        return physical(value);
    }

    private static Object rawColumn(Long rawValue) {
        if (rawValue == null) {
            return null;
        }
        if (rawValue < -MAX_JSON_SAFE_INTEGER || rawValue > MAX_JSON_SAFE_INTEGER) {
            return Long.toString(rawValue);
        }
        return rawValue;
    }
}
