package com.stellar.platform.domain;

import java.util.Arrays;
import java.util.Objects;

/**
 * Format-independent CAN / CAN FD frame used across parsers and processing.
 * Payload length may be up to 64 bytes (CAN FD). Classical CAN uses 0..8.
 */
public record CanonicalCanFrame(
        long sequenceNumber,
        long timestampNanos,
        int channel,
        long canId,
        boolean extended,
        FrameDirection direction,
        int dlc,
        byte[] data,
        FrameQuality quality,
        SourceReference source
) {

    public static final int MAX_PAYLOAD_BYTES = 64;

    public CanonicalCanFrame {
        if (sequenceNumber < 0) {
            throw new IllegalArgumentException("sequenceNumber must be >= 0");
        }
        if (channel < 0) {
            throw new IllegalArgumentException("channel must be >= 0");
        }
        if (canId < 0) {
            throw new IllegalArgumentException("canId must be >= 0");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction must not be null");
        }
        if (dlc < 0 || dlc > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("dlc must be in 0.." + MAX_PAYLOAD_BYTES);
        }
        Objects.requireNonNull(data, "data");
        if (data.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("CAN payload must be at most " + MAX_PAYLOAD_BYTES + " bytes");
        }
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        data = Arrays.copyOf(data, data.length);
    }

    @Override
    public byte[] data() {
        return Arrays.copyOf(data, data.length);
    }

    /**
     * Payload size without copying bytes. Equivalent to {@code data().length}.
     */
    public int payloadLength() {
        return data.length;
    }

    /**
     * Reads one payload byte without exposing or copying the backing array. This is intended for
     * high-throughput decoders that need read-only bit access while preserving frame immutability.
     */
    public byte payloadByteAt(int index) {
        if (index < 0 || index >= data.length) {
            throw new IndexOutOfBoundsException("payload index=" + index + ", length=" + data.length);
        }
        return data[index];
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CanonicalCanFrame that)) {
            return false;
        }
        return sequenceNumber == that.sequenceNumber
                && timestampNanos == that.timestampNanos
                && channel == that.channel
                && canId == that.canId
                && extended == that.extended
                && dlc == that.dlc
                && direction == that.direction
                && quality == that.quality
                && Objects.equals(source, that.source)
                && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(sequenceNumber, timestampNanos, channel, canId, extended, direction, dlc, quality, source);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }

    @Override
    public String toString() {
        return "CanonicalCanFrame["
                + "sequenceNumber=" + sequenceNumber
                + ", timestampNanos=" + timestampNanos
                + ", channel=" + channel
                + ", canId=0x" + Long.toHexString(canId)
                + ", extended=" + extended
                + ", direction=" + direction
                + ", dlc=" + dlc
                + ", data=" + Arrays.toString(data)
                + ", quality=" + quality
                + ", source=" + source
                + ']';
    }
}
