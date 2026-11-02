package com.stellar.platform.parser.mf4.graph;

import com.stellar.platform.parser.mf4.block.MdfBlockType;
import com.stellar.platform.parser.mf4.block.MdfLinkIssue;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reachable block graph discovered by following MDF links from the HD root.
 */
public final class MdfBlockGraph {

    private final Map<Long, MdfBlockNode> nodesByAddress;
    private final List<MdfLinkIssue> linkIssues;
    private final long hdAddress;
    private final boolean cancelled;

    public MdfBlockGraph(
            Map<Long, MdfBlockNode> nodesByAddress,
            List<MdfLinkIssue> linkIssues,
            long hdAddress,
            boolean cancelled
    ) {
        this.nodesByAddress = Map.copyOf(Objects.requireNonNull(nodesByAddress, "nodesByAddress"));
        this.linkIssues = List.copyOf(Objects.requireNonNull(linkIssues, "linkIssues"));
        this.hdAddress = hdAddress;
        this.cancelled = cancelled;
    }

    public long hdAddress() {
        return hdAddress;
    }

    public boolean cancelled() {
        return cancelled;
    }

    public Collection<MdfBlockNode> nodes() {
        return nodesByAddress.values();
    }

    public Map<Long, MdfBlockNode> nodesByAddress() {
        return nodesByAddress;
    }

    public Optional<MdfBlockNode> nodeAt(long address) {
        return Optional.ofNullable(nodesByAddress.get(address));
    }

    public List<MdfLinkIssue> linkIssues() {
        return linkIssues;
    }

    public long countByType(MdfBlockType type) {
        return nodesByAddress.values().stream().filter(n -> n.type() == type).count();
    }

    public List<MdfBlockNode> nodesOfType(MdfBlockType type) {
        return nodesByAddress.values().stream().filter(n -> n.type() == type).toList();
    }

    public List<MdfBlockNode> unknownNodes() {
        return nodesOfType(MdfBlockType.UNKNOWN);
    }

    public static Builder builder(long hdAddress) {
        return new Builder(hdAddress);
    }

    public static final class Builder {
        private final Map<Long, MdfBlockNode> nodes = new LinkedHashMap<>();
        private final List<MdfLinkIssue> issues = new java.util.ArrayList<>();
        private final long hdAddress;
        private boolean cancelled;

        private Builder(long hdAddress) {
            this.hdAddress = hdAddress;
        }

        public boolean contains(long address) {
            return nodes.containsKey(address);
        }

        public void put(MdfBlockNode node) {
            nodes.put(node.address(), node);
        }

        public MdfBlockNode get(long address) {
            return nodes.get(address);
        }

        public void replace(MdfBlockNode node) {
            nodes.put(node.address(), node);
        }

        public void addIssue(MdfLinkIssue issue) {
            issues.add(issue);
        }

        public void markCancelled() {
            this.cancelled = true;
        }

        public MdfBlockGraph build() {
            return new MdfBlockGraph(nodes, issues, hdAddress, cancelled);
        }
    }
}
