package com.stellar.platform.parser.spi;

import java.nio.file.Path;

/**
 * Optional early CAN channel discovery from format metadata.
 * Implementations must not load the full measurement into memory.
 */
public interface CanChannelDiscoveryCapable {

    CanChannelDiscoveryResult discoverCanChannels(Path path, CancellationToken cancellation)
            throws ParserException;
}
