package com.stellar.platform.parser.spi;

import com.stellar.platform.domain.ProcessingWarning;

/**
 * Receives non-fatal processing warnings while a reader streams frames.
 */
@FunctionalInterface
public interface WarningConsumer {

    void onWarning(ProcessingWarning warning);
}
