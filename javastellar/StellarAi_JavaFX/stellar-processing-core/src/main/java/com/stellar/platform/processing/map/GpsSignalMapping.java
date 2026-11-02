package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.time.Instant;
import java.util.Objects;

/**
 * Persisted or in-session assignment of a signal to a GPS semantic role.
 *
 * <p>A {@link RouteMappingScope#PROJECT} mapping is scoped to one measurement source; reuse on
 * another file is allowed only when {@link #schemaFingerprint()} matches that file's catalog
 * fingerprint. A {@link RouteMappingScope#DEFAULT} mapping is the product-wide fallback and carries
 * neither project nor source.
 *
 * <p>{@link RouteMappingMode#MANUAL_TEXT} mappings have no {@link #key()}: the engineer typed a
 * signal name, and it binds to whichever catalog entry carries that name when a measurement is open.
 */
public record GpsSignalMapping(
        String projectId,
        String sourceId,
        String schemaFingerprint,
        GpsSignalRole role,
        RouteMappingMode mode,
        SignalCatalogKey key,
        String manualSignalName,
        RouteMappingScope scope,
        boolean userConfirmed,
        Instant updatedAt
) {
    public GpsSignalMapping {
        projectId = projectId == null ? "" : projectId;
        sourceId = sourceId == null ? "" : sourceId;
        schemaFingerprint = schemaFingerprint == null ? "" : schemaFingerprint;
        Objects.requireNonNull(role, "role");
        mode = mode == null ? RouteMappingMode.SIGNAL_REFERENCE : mode;
        manualSignalName = manualSignalName == null ? "" : manualSignalName.trim();
        scope = scope == null ? RouteMappingScope.PROJECT : scope;
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
        if (mode == RouteMappingMode.SIGNAL_REFERENCE) {
            Objects.requireNonNull(key, "key");
        } else if (manualSignalName.isEmpty()) {
            throw new IllegalArgumentException("A manual mapping needs the signal name the user typed");
        }
    }

    /** Project-scoped mapping pointing at a catalog signal. */
    public GpsSignalMapping(
            String projectId,
            String sourceId,
            String schemaFingerprint,
            GpsSignalRole role,
            SignalCatalogKey key,
            boolean userConfirmed,
            Instant updatedAt
    ) {
        this(projectId, sourceId, schemaFingerprint, role, RouteMappingMode.SIGNAL_REFERENCE, key, "",
                RouteMappingScope.PROJECT, userConfirmed, updatedAt);
    }

    /** Mapping for a signal name the engineer typed rather than picked. */
    public static GpsSignalMapping manual(
            String projectId,
            String sourceId,
            String schemaFingerprint,
            GpsSignalRole role,
            String signalName,
            RouteMappingScope scope,
            Instant updatedAt
    ) {
        return new GpsSignalMapping(projectId, sourceId, schemaFingerprint, role,
                RouteMappingMode.MANUAL_TEXT, null, signalName, scope, true, updatedAt);
    }

    public boolean manual() {
        return mode == RouteMappingMode.MANUAL_TEXT;
    }

    /** The signal name this mapping asks for, whichever input method produced it. */
    public String requestedSignalName() {
        return manual() ? manualSignalName : (key == null ? "" : key.signalName());
    }
}
