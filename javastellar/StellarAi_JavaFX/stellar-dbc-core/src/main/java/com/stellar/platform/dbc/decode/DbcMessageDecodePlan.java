package com.stellar.platform.dbc.decode;

import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcMultiplexCondition;
import com.stellar.platform.dbc.model.DbcMultiplexGraph;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable DBC message decode plan compiled once per {@link DbcMessage}.
 */
public final class DbcMessageDecodePlan {

    private final DbcMessage message;
    private final DbcSignal rootMultiplexor;
    private final boolean useExplicitMulVal;
    private final boolean hasSecondary;
    private final DbcMultiplexGraph graph;
    private final Set<String> selectorNames;
    private final Set<String> leafNames;
    private final boolean hasLeafDefinitions;

    private DbcMessageDecodePlan(
            DbcMessage message,
            DbcSignal rootMultiplexor,
            boolean useExplicitMulVal,
            boolean hasSecondary,
            DbcMultiplexGraph graph,
            Set<String> selectorNames,
            Set<String> leafNames,
            boolean hasLeafDefinitions
    ) {
        this.message = message;
        this.rootMultiplexor = rootMultiplexor;
        this.useExplicitMulVal = useExplicitMulVal;
        this.hasSecondary = hasSecondary;
        this.graph = graph;
        this.selectorNames = selectorNames;
        this.leafNames = leafNames;
        this.hasLeafDefinitions = hasLeafDefinitions;
    }

    public static DbcMessageDecodePlan compile(DbcMessage message) {
        Objects.requireNonNull(message, "message");
        boolean explicit = false;
        boolean secondary = false;
        DbcSignal root = null;
        Set<String> selectors = new HashSet<>();
        Set<String> leaves = new HashSet<>();
        for (DbcSignal signal : message.signals()) {
            if (signal.hasExplicitMultiplexConditions()) {
                explicit = true;
            }
            if (signal.secondaryMultiplexor()) {
                secondary = true;
                selectors.add(signal.name());
            }
            if (signal.multiplexRole() == DbcMultiplexRole.MULTIPLEXOR) {
                if (root == null) {
                    root = signal;
                }
                selectors.add(signal.name());
            }
            for (DbcMultiplexCondition condition : signal.multiplexConditions()) {
                selectors.add(condition.multiplexorSignalName());
            }
        }
        for (DbcSignal signal : message.signals()) {
            if (signal.multiplexRole() == DbcMultiplexRole.MULTIPLEXED && !selectors.contains(signal.name())) {
                leaves.add(signal.name());
            }
        }
        DbcMultiplexGraph graph = DbcMultiplexGraph.build(message);
        return new DbcMessageDecodePlan(
                message,
                root,
                explicit,
                secondary,
                graph,
                Set.copyOf(selectors),
                Set.copyOf(leaves),
                !leaves.isEmpty()
        );
    }

    public DbcMessage message() {
        return message;
    }

    public DbcSignal rootMultiplexor() {
        return rootMultiplexor;
    }

    public boolean useExplicitMulVal() {
        return useExplicitMulVal;
    }

    public boolean hasSecondary() {
        return hasSecondary;
    }

    public DbcMultiplexGraph graph() {
        return graph;
    }

    public Set<String> selectorNames() {
        return selectorNames;
    }

    public Set<String> leafNames() {
        return leafNames;
    }

    public boolean hasLeafDefinitions() {
        return hasLeafDefinitions;
    }

    public List<String> graphDiagnostics() {
        return graph.diagnostics();
    }
}
