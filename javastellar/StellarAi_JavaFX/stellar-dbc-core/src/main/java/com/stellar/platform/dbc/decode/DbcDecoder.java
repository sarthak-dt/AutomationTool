package com.stellar.platform.dbc.decode;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.domain.CanonicalCanFrame;

import java.util.List;

/**
 * Reusable common DBC decoder — format-independent (ASC / MF4 / BLF frames).
 */
public interface DbcDecoder {

    DecodingResult decode(CanonicalCanFrame frame, DbcDatabase database);

    /**
     * Decode a frame already matched to {@code message}. Does not emit UNMATCHED_CAN_ID.
     * Default implementation delegates to {@link #decode}.
     */
    default DecodingResult decodeMatched(
            CanonicalCanFrame frame,
            DbcDatabase database,
            com.stellar.platform.dbc.model.DbcMessage message
    ) {
        return decode(frame, database);
    }

    /**
     * Batch/streaming decode. Signals are pushed to {@code consumer} and need not be retained.
     */
    DecodeStatistics decodeBatch(
            List<CanonicalCanFrame> frames,
            DbcDatabase database,
            SignalConsumer consumer
    );
}
