package com.stellar.platform.desktop.project;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable project identity for a measurement source.
 * Display name / filename is never used as identity.
 */
public final class ProjectSourceId {

    private final String value;

    private ProjectSourceId(String value) {
        this.value = Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("ProjectSourceId must not be blank");
        }
    }

    public static ProjectSourceId of(String value) {
        return new ProjectSourceId(value);
    }

    public static ProjectSourceId generate() {
        return new ProjectSourceId(UUID.randomUUID().toString());
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ProjectSourceId that && value.equals(that.value);
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
