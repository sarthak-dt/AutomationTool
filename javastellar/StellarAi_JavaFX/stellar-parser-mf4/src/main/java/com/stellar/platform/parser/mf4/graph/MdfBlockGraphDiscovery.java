package com.stellar.platform.parser.mf4.graph;

import com.stellar.platform.parser.mf4.block.MdfBlockHeader;
import com.stellar.platform.parser.mf4.block.MdfBlockHeaderReader;
import com.stellar.platform.parser.mf4.block.MdfBlockType;
import com.stellar.platform.parser.mf4.block.MdfLinkIssue;
import com.stellar.platform.parser.mf4.block.MdfLinkIssueKind;
import com.stellar.platform.parser.mf4.id.MdfIdentification;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.parser.spi.ParserException;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Discovers the reachable MDF block graph by following links from the HD block.
 */
public final class MdfBlockGraphDiscovery {

    private MdfBlockGraphDiscovery() {
    }

    public static MdfBlockGraph discover(MdfFileAccess access, CancellationToken cancellation)
            throws ParserException {
        Objects.requireNonNull(access, "access");
        CancellationToken token = cancellation == null ? CancellationToken.NONE : cancellation;

        long hdAddress = MdfIdentification.HD_BLOCK_OFFSET;
        MdfBlockGraph.Builder graph = MdfBlockGraph.builder(hdAddress);

        Deque<Long> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        Set<Long> enqueued = new HashSet<>();

        queue.add(hdAddress);
        enqueued.add(hdAddress);

        while (!queue.isEmpty()) {
            if (token.isCancellationRequested()) {
                graph.markCancelled();
                break;
            }

            long address = queue.removeFirst();
            if (!visited.add(address)) {
                continue;
            }

            MdfBlockHeader header;
            try {
                header = MdfBlockHeaderReader.read(access, address);
            } catch (ParserException e) {
                graph.addIssue(new MdfLinkIssue(
                        classifyReadFailure(e),
                        address,
                        MdfBlockType.UNKNOWN,
                        -1,
                        address,
                        e.getMessage()
                ));
                continue;
            }

            MdfBlockNode node = new MdfBlockNode(
                    address,
                    header,
                    header.type(),
                    header.links(),
                    1
            );
            graph.put(node);

            for (int i = 0; i < header.links().size(); i++) {
                long link = header.links().get(i);
                if (link == 0L) {
                    continue;
                }
                if (link < 0 || link >= access.fileSize()) {
                    graph.addIssue(new MdfLinkIssue(
                            MdfLinkIssueKind.OUTSIDE_FILE,
                            address,
                            header.type(),
                            i,
                            link,
                            "Link target " + link + " outside physical file size " + access.fileSize()
                    ));
                    continue;
                }
                if (visited.contains(link) || graph.contains(link)) {
                    MdfBlockNode existing = graph.get(link);
                    if (existing != null) {
                        graph.replace(existing.withIncomingIncrement());
                    }
                    graph.addIssue(new MdfLinkIssue(
                            MdfLinkIssueKind.DUPLICATE_REFERENCE,
                            address,
                            header.type(),
                            i,
                            link,
                            "Duplicate reference to block at " + link
                    ));
                    // Detect simple self-cycle / already-visited cycle signal
                    if (visited.contains(link)) {
                        graph.addIssue(new MdfLinkIssue(
                                MdfLinkIssueKind.CYCLIC_REFERENCE,
                                address,
                                header.type(),
                                i,
                                link,
                                "Cyclic / already-visited link to " + link
                        ));
                    }
                    continue;
                }
                if (enqueued.add(link)) {
                    queue.addLast(link);
                }
            }
        }

        return graph.build();
    }

    private static MdfLinkIssueKind classifyReadFailure(ParserException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("exceeds physical") || msg.contains("exceeds file size")) {
            return MdfLinkIssueKind.IMPOSSIBLE_LENGTH;
        }
        if (msg.contains("overflow") || msg.contains("Overflow")) {
            return MdfLinkIssueKind.ARITHMETIC_OVERFLOW;
        }
        if (msg.contains("premature EOF") || msg.contains("Cannot read")) {
            return MdfLinkIssueKind.PREMATURE_EOF;
        }
        return MdfLinkIssueKind.BROKEN_UNREADABLE;
    }
}
