package com.stellar.platform.processing.align;

import com.stellar.platform.domain.SignalValidity;

import java.util.Arrays;
import java.util.Objects;

/**
 * One aligned-table row after timestamp-union merge.
 * <p>
 * {@code null} physical/raw cells mean no sample was emitted for that signal at this
 * timestamp (UI shows a neutral missing marker). Null is distinct from physical {@code 0.0}.
 * {@link #index()} is the aligned-table row index, not a per-signal sample index.
 */
public final class AlignedSignalRow {

    private final long index;
    private final long timestampNanos;
    private final Double[] physical;
    private final Long[] raw;
    private final String[] textual;
    private final SignalValidity[] validity;

    public AlignedSignalRow(long index, long timestampNanos, Double[] physical, Long[] raw) {
        this(index, timestampNanos, physical, raw, null);
    }

    public AlignedSignalRow(long index, long timestampNanos, Double[] physical, Long[] raw, String[] textual) {
        this(index, timestampNanos, physical, raw, textual, null);
    }

    public AlignedSignalRow(
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

    /** Null when this row was built before validity was carried into the table. */
    public SignalValidity[] validityCopy() {
        return validity == null ? null : validity.clone();
    }

    public boolean isMissing(int signalIndex) {
        return physicalAt(signalIndex) == null && rawAt(signalIndex) == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AlignedSignalRow other)) {
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
