package com.stellar.platform.parser.mf4.graph;

import com.stellar.platform.parser.mf4.block.MdfBlockHeader;
import com.stellar.platform.parser.mf4.block.MdfBlockType;

import java.util.List;
import java.util.Objects;

/**
 * One discovered block node with retained physical address and outgoing links.
 */
public record MdfBlockNode(
        long address,
        MdfBlockHeader header,
        MdfBlockType type,
        List<Long> outgoingLinks,
        int incomingReferenceCount
) {

    public MdfBlockNode {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(type, "type");
        outgoingLinks = List.copyOf(Objects.requireNonNull(outgoingLinks, "outgoingLinks"));
    }

    public MdfBlockNode withIncomingIncrement() {
        return new MdfBlockNode(address, header, type, outgoingLinks, incomingReferenceCount + 1);
    }
}
