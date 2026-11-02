package com.stellar.platform.api.nft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Parser for NFT jstat output. Not a GB run. */
class ReleaseNftSupportHeapParseTest {

    @Test
    void parseJstatUsedBytesSumsSurvivorEdenOld() {
        String out = """
                S0C    S1C    S0U    S1U      EC       EU        OC         OU
                1024.0 1024.0 0.0    128.0    8192.0   2048.0    16384.0    4096.0
                """;
        assertEquals((128L + 2048L + 4096L) * 1024L, ReleaseNftSupport.parseJstatUsedBytes(out));
    }

    @Test
    void parseJstatUsedBytesRejectsMalformed() {
        assertNull(ReleaseNftSupport.parseJstatUsedBytes(""));
        assertNull(ReleaseNftSupport.parseJstatUsedBytes("S0U\nnot-a-number"));
    }
}
