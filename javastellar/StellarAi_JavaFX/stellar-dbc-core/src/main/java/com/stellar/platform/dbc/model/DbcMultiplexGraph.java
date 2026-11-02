package com.stellar.platform.dbc.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable multiplex relationship graph for one DbcMessage, built from SG_ roles
 * and SG_MUL_VAL_ conditions. Supports nested extended multiplexing.
 */
public final class DbcMultiplexGraph {

    public enum NodeKind {
        NORMAL,
        MULTIPLEXOR,
        MULTIPLEXED,
        MULTIPLEXED_MULTIPLEXOR
    }

    public record Edge(
            String childSignalName,
            String parentMultiplexorName,
            List<DbcMultiplexRange> ranges
    ) {
        public Edge {
            Objects.requireNonNull(childSignalName, "childSignalName");
            Objects.requireNonNull(parentMultiplexorName, "parentMultiplexorName");
            ranges = List.copyOf(Objects.requireNonNull(ranges, "ranges"));
        }

        public boolean matches(long parentRaw) {
            for (DbcMultiplexRange range : ranges) {
                if (range.contains(parentRaw)) {
                    return true;
                }
            }
            return false;
        }
    }

    public record Node(
            String signalName,
            NodeKind kind,
            List<Edge> outboundEdges
    ) {
        public Node {
            Objects.requireNonNull(signalName, "signalName");
            Objects.requireNonNull(kind, "kind");
            outboundEdges = List.copyOf(Objects.requireNonNull(outboundEdges, "outboundEdges"));
        }
    }

    private final String messageName;
    private final Map<String, Node> nodesByName;
    private final List<String> rootMultiplexorNames;
    private final List<String> diagnostics;

    private DbcMultiplexGraph(
            String messageName,
            Map<String, Node> nodesByName,
            List<String> rootMultiplexorNames,
            List<String> diagnostics
    ) {
        this.messageName = Objects.requireNonNull(messageName, "messageName");
        this.nodesByName = Map.copyOf(nodesByName);
        this.rootMultiplexorNames = List.copyOf(rootMultiplexorNames);
        this.diagnostics = List.copyOf(diagnostics);
    }

    public String messageName() {
        return messageName;
    }

    public Map<String, Node> nodesByName() {
        return nodesByName;
    }

    public List<String> rootMultiplexorNames() {
        return rootMultiplexorNames;
    }

    public List<String> diagnostics() {
        return diagnostics;
    }

    public Optional<Node> node(String signalName) {
        return Optional.ofNullable(nodesByName.get(signalName));
    }

    public int muxLevelCount() {
        return maxDepth();
    }

    public int maxDepth() {
        int max = 0;
        for (String root : rootMultiplexorNames) {
            max = Math.max(max, depthFrom(root, new LinkedHashSet<>()));
        }
        return max;
    }

    private int depthFrom(String name, Set<String> stack) {
        if (!stack.add(name)) {
            return stack.size();
        }
        Node node = nodesByName.get(name);
        if (node == null || node.outboundEdges().isEmpty()) {
            stack.remove(name);
            return 1;
        }
        int maxChild = 0;
        for (Edge edge : node.outboundEdges()) {
            maxChild = Math.max(maxChild, depthFrom(edge.childSignalName(), stack));
        }
        stack.remove(name);
        return 1 + maxChild;
    }

    public String renderTree() {
        StringBuilder sb = new StringBuilder();
        sb.append("Message ").append(messageName).append('\n');
        if (rootMultiplexorNames.isEmpty()) {
            sb.append("  (no multiplexor)\n");
            return sb.toString();
        }
        for (String root : rootMultiplexorNames) {
            renderNode(sb, root, "  ", new LinkedHashSet<>(), 0);
        }
        return sb.toString();
    }

    private void renderNode(StringBuilder sb, String name, String indent, Set<String> stack, int depth) {
        if (depth > 6) {
            sb.append(indent).append("...\n");
            return;
        }
        if (!stack.add(name)) {
            sb.append(indent).append(name).append(" <cycle>\n");
            return;
        }
        Node node = nodesByName.get(name);
        if (node == null) {
            sb.append(indent).append(name).append(" <missing>\n");
            stack.remove(name);
            return;
        }
        sb.append(indent).append(name).append(" [").append(node.kind()).append("]\n");

        // Group children: nested multiplexors first (expand), then summarize leaf counts by range.
        Map<String, List<Edge>> nestedByRange = new LinkedHashMap<>();
        Map<String, List<Edge>> leavesByRange = new LinkedHashMap<>();
        for (Edge edge : node.outboundEdges()) {
            String key = formatRanges(edge.ranges());
            Node child = nodesByName.get(edge.childSignalName());
            boolean nested = child != null && (child.kind() == NodeKind.MULTIPLEXOR
                    || child.kind() == NodeKind.MULTIPLEXED_MULTIPLEXOR);
            (nested ? nestedByRange : leavesByRange).computeIfAbsent(key, k -> new ArrayList<>()).add(edge);
        }
        for (Map.Entry<String, List<Edge>> e : nestedByRange.entrySet()) {
            sb.append(indent).append("  +- value/range ").append(e.getKey()).append('\n');
            for (Edge edge : e.getValue()) {
                renderNode(sb, edge.childSignalName(), indent + "  |   ", stack, depth + 1);
            }
        }
        for (Map.Entry<String, List<Edge>> e : leavesByRange.entrySet()) {
            List<Edge> edges = e.getValue();
            sb.append(indent).append("  +- value/range ").append(e.getKey())
                    .append(" -> ").append(edges.size()).append(" leaf(s)");
            if (edges.size() <= 3) {
                sb.append(": ");
                for (int i = 0; i < edges.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(edges.get(i).childSignalName());
                }
            } else {
                sb.append(" e.g. ").append(edges.get(0).childSignalName())
                        .append(", ").append(edges.get(1).childSignalName()).append(", ...");
            }
            sb.append('\n');
        }
        stack.remove(name);
    }

    private static String formatRanges(List<DbcMultiplexRange> ranges) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ranges.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            DbcMultiplexRange r = ranges.get(i);
            sb.append(r.minimum()).append('-').append(r.maximum());
        }
        return sb.toString();
    }

    public static DbcMultiplexGraph build(DbcMessage message) {
        Objects.requireNonNull(message, "message");
        List<String> diagnostics = new ArrayList<>();
        Map<String, DbcSignal> byName = new LinkedHashMap<>();
        for (DbcSignal signal : message.signals()) {
            byName.put(signal.name(), signal);
        }

        Set<String> referencedAsMultiplexor = new LinkedHashSet<>();
        Map<String, List<Edge>> outbound = new LinkedHashMap<>();

        for (DbcSignal signal : message.signals()) {
            outbound.putIfAbsent(signal.name(), new ArrayList<>());
            for (DbcMultiplexCondition condition : signal.multiplexConditions()) {
                String parent = condition.multiplexorSignalName();
                referencedAsMultiplexor.add(parent);
                if (!byName.containsKey(parent)) {
                    diagnostics.add("Missing multiplexor '" + parent + "' referenced by '" + signal.name() + "'");
                    continue;
                }
                // Several SG_MUL_VAL_ parents are an AND gate, not a broken tree.
                Edge edge = new Edge(signal.name(), parent, condition.ranges());
                outbound.computeIfAbsent(parent, k -> new ArrayList<>()).add(edge);
            }
        }

        boolean anyExplicit = message.signals().stream().anyMatch(DbcSignal::hasExplicitMultiplexConditions);
        if (!anyExplicit) {
            Optional<DbcSignal> root = message.multiplexor();
            if (root.isPresent()) {
                referencedAsMultiplexor.add(root.get().name());
                for (DbcSignal signal : message.signals()) {
                    if (signal.multiplexRole() != DbcMultiplexRole.MULTIPLEXED
                            || signal.multiplexValue() == null) {
                        continue;
                    }
                    Edge edge = new Edge(
                            signal.name(),
                            root.get().name(),
                            List.of(DbcMultiplexRange.ofSingle(signal.multiplexValue()))
                    );
                    outbound.computeIfAbsent(root.get().name(), k -> new ArrayList<>()).add(edge);
                }
            }
        }

        detectCycles(outbound, diagnostics);

        Map<String, Node> nodes = new LinkedHashMap<>();
        List<String> roots = new ArrayList<>();
        for (DbcSignal signal : message.signals()) {
            NodeKind kind = classify(signal, referencedAsMultiplexor);
            nodes.put(signal.name(), new Node(
                    signal.name(),
                    kind,
                    outbound.getOrDefault(signal.name(), List.of())
            ));
            if (kind == NodeKind.MULTIPLEXOR) {
                roots.add(signal.name());
            }
        }

        return new DbcMultiplexGraph(message.name(), nodes, roots, diagnostics);
    }

    private static NodeKind classify(DbcSignal signal, Set<String> referencedAsMultiplexor) {
        if (signal.multiplexRole() == DbcMultiplexRole.MULTIPLEXOR) {
            return NodeKind.MULTIPLEXOR;
        }
        if (signal.multiplexRole() == DbcMultiplexRole.NONE) {
            return NodeKind.NORMAL;
        }
        boolean isMux = referencedAsMultiplexor.contains(signal.name()) || signal.secondaryMultiplexor();
        if (isMux) {
            return NodeKind.MULTIPLEXED_MULTIPLEXOR;
        }
        return NodeKind.MULTIPLEXED;
    }

    private static void detectCycles(Map<String, List<Edge>> outbound, List<String> diagnostics) {
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        for (String start : outbound.keySet()) {
            dfsCycle(start, outbound, visiting, visited, diagnostics);
        }
    }

    private static void dfsCycle(
            String name,
            Map<String, List<Edge>> outbound,
            Set<String> visiting,
            Set<String> visited,
            List<String> diagnostics
    ) {
        if (visited.contains(name)) {
            return;
        }
        if (!visiting.add(name)) {
            diagnostics.add("Cycle detected in multiplex graph at '" + name + "'");
            return;
        }
        for (Edge edge : outbound.getOrDefault(name, List.of())) {
            dfsCycle(edge.childSignalName(), outbound, visiting, visited, diagnostics);
        }
        visiting.remove(name);
        visited.add(name);
    }
}