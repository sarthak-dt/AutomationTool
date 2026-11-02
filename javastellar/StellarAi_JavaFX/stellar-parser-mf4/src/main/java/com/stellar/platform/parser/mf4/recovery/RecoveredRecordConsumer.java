package com.stellar.platform.parser.mf4.recovery;

import com.stellar.platform.parser.spi.ParserException;

/**
 * Internal callback for one structurally recovered MF4 record.
 * CAN role resolution performed during validation is supplied on the validated record.
 */
@FunctionalInterface
public interface RecoveredRecordConsumer {

    /**
     * @return {@code true} when the record was accepted (produced downstream output)
     */
    boolean onRecord(RecoveryValidatedRecord validated) throws ParserException;
}