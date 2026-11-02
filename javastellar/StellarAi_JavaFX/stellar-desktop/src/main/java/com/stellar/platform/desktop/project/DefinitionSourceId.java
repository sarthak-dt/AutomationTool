package com.stellar.platform.desktop.project;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable project identity for a definition source (e.g. DBC).
 * Display name / filename is never used as identity.
 */
public final class DefinitionSourceId {

    private final String value;

    private DefinitionSourceId(String value) {
        this.value = Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("DefinitionSourceId must not be blank");
        }
    }

    public static DefinitionSourceId of(String value) {
        return new DefinitionSourceId(value);
    }

    public static DefinitionSourceId generate() {
        return new DefinitionSourceId(UUID.randomUUID().toString());
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DefinitionSourceId that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
