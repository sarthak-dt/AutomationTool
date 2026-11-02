package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.SourceReference;

/**
 * Builds {@link CanonicalCanFrame} from resolved CAN fields.
 */
public final class MdfCanonicalCanFrameFactory {

    private MdfCanonicalCanFrameFactory() {
    }

    public static CanonicalCanFrame create(
            long sequenceNumber,
            MdfCanFieldResolver.ResolvedCanFields fields,
            long physicalOffset
    ) {
        return create(sequenceNumber, fields, physicalOffset, FrameQuality.VALID);
    }

    public static CanonicalCanFrame create(
            long sequenceNumber,
            MdfCanFieldResolver.ResolvedCanFields fields,
            long physicalOffset,
            FrameQuality quality
    ) {
        return new CanonicalCanFrame(
                sequenceNumber,
                fields.timestampNanos(),
                fields.channel(),
                fields.canId(),
                fields.extended(),
                fields.direction(),
                fields.dlc(),
                fields.payload(),
                quality == null ? FrameQuality.VALID : quality,
                new SourceReference(sequenceNumber + 1, physicalOffset)
        );
    }
}
