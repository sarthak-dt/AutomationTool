package com.stellar.platform.dbc.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * DBC value descriptions for one signal. {@code tableName} records a
 * {@code VAL_TABLE_} that supplied the entries. A null name means the entries
 * came from that signal's own {@code VAL_}.
 */
public record DbcValueTable(Map<Long, String> entries, String tableName) {

    public DbcValueTable {
        entries = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(entries, "entries")));
        tableName = tableName == null || tableName.isBlank() ? null : tableName;
    }

    public DbcValueTable(Map<Long, String> entries) {
        this(entries, null);
    }

    public Optional<String> textFor(long rawValue) {
        return Optional.ofNullable(entries.get(rawValue));
    }

    public DbcValueTable withName(String name) {
        return new DbcValueTable(entries, name);
    }

    public static DbcValueTable empty() {
        return new DbcValueTable(Map.of());
    }
}
