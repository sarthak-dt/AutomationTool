package com.stellar.platform.parser.blf;

import com.stellar.platform.parser.spi.ParserException;

/**
 * An object header was valid, but its bytes continue past the end of this LOG_CONTAINER.
 * Vector logs fill containers to a fixed uncompressed size and finish the object in the next one.
 */
final class BlfContainerSplit extends ParserException {

    private final long offset;

    BlfContainerSplit(long offset) {
        super("BLF object continues in the next container at offset " + offset);
        this.offset = offset;
    }

    long offset() {
        return offset;
    }
}
