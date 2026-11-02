package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.SignalValidity;

import java.util.Arrays;
import java.util.Objects;

/**
 * One virtualized workspace table row after timestamp alignment of bounded sample pages.
 * {@code null} physical/raw cells mean missing at that timestamp (display as em dash), not zero.
 */
public final class AlignedSampleRow {

    public static final String MISSING = "\u2014";

    private final long index;
    private final long timestampNanos;
    private final Double[] physical;
    private final Long[] raw;
    private final String[] textual;
    private final SignalValidity[] validity;

    public AlignedSampleRow(long index, long timestampNanos, Double[] physical, Long[] raw) {
        this(index, timestampNanos, physical, raw, null);
    }

    public AlignedSampleRow(long index, long timestampNanos, Double[] physical, Long[] raw, String[] textual) {
        this(index, timestampNanos, physical, raw, textual, null);
    }

    public AlignedSampleRow(
            long index,
            long timestampNanos,
            Double[] physical,
            Long[] raw,
            String[] textual,
            SignalValidity[] validity
    ) {
        this.index = index;
        this.timestampNanos = timestampNanos;
        this.physical = physical == null ? new Double[0] : physical.clone();
        this.raw = raw == null ? new Long[0] : raw.clone();
        this.textual = textual == null ? new String[this.physical.length] : textual.clone();
        this.validity = validity == null ? null : validity.clone();
    }

    public long index() {
        return index;
    }

    public long timestampNanos() {
        return timestampNanos;
    }

    public int signalCount() {
        return physical.length;
    }

    public Double physicalAt(int signalIndex) {
        if (signalIndex < 0 || signalIndex >= physical.length) {
            return null;
        }
        return physical[signalIndex];
    }

    public Long rawAt(int signalIndex) {
        if (signalIndex < 0 || signalIndex >= raw.length) {
            return null;
        }
        return raw[signalIndex];
    }

    public String textualAt(int signalIndex) {
        if (signalIndex < 0 || signalIndex >= textual.length) {
            return null;
        }
        return textual[signalIndex];
    }

    public SignalValidity validityAt(int signalIndex) {
        if (validity == null || signalIndex < 0 || signalIndex >= validity.length) {
            return null;
        }
        return validity[signalIndex];
    }

    /** First-signal physical value, or NaN when missing — for single-signal tests. */
    public double physicalValue() {
        Double v = physicalAt(0);
        return v == null ? Double.NaN : v;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AlignedSampleRow other)) {
            return false;
        }
        return index == other.index
                && timestampNanos == other.timestampNanos
                && Arrays.equals(physical, other.physical)
                && Arrays.equals(raw, other.raw)
                && Arrays.equals(textual, other.textual)
                && Arrays.equals(validity, other.validity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, timestampNanos, Arrays.hashCode(physical), Arrays.hashCode(raw),
                Arrays.hashCode(textual), Arrays.hashCode(validity));
    }
}
