package com.stellar.platform.dbc.model;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable parsed DBC database.
 */
public final class DbcDatabase {

    private final String version;
    private final List<String> nodes;
    private final Map<CanIdKey, DbcMessage> messagesById;
    private final List<String> diagnostics;

    public DbcDatabase(
            String version,
            List<String> nodes,
            Collection<DbcMessage> messages,
            List<String> diagnostics
    ) {
        this.version = version == null ? "" : version;
        this.nodes = List.copyOf(Objects.requireNonNullElse(nodes, List.of()));
        Map<CanIdKey, DbcMessage> map = new LinkedHashMap<>();
        for (DbcMessage message : Objects.requireNonNull(messages, "messages")) {
            map.put(message.canId(), message);
        }
        this.messagesById = Map.copyOf(map);
        this.diagnostics = List.copyOf(Objects.requireNonNullElse(diagnostics, List.of()));
    }

    public String version() {
        return version;
    }

    public List<String> nodes() {
        return nodes;
    }

    public Collection<DbcMessage> messages() {
        return messagesById.values();
    }

    public Optional<DbcMessage> findMessage(CanIdKey key) {
        return Optional.ofNullable(messagesById.get(key));
    }

    public Optional<DbcMessage> findMessage(long canId, boolean extended) {
        return findMessage(CanIdKey.fromFrame(canId, extended));
    }

    public List<String> diagnostics() {
        return diagnostics;
    }
}
