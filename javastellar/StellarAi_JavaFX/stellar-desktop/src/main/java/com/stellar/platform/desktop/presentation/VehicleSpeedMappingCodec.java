package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.drive.speed.SpeedConfidence;
import com.stellar.platform.processing.drive.speed.SpeedSourceKind;
import com.stellar.platform.processing.drive.speed.SpeedSourceMapping;
import com.stellar.platform.processing.drive.speed.VehicleSpeedSourceResolver;
import com.stellar.platform.storage.metadata.SemanticSignalMappingMetadata;

import java.time.Instant;
import java.util.Optional;

/**
 * Maps lightweight speed-source resolution to project-local {@code semantic_signal_mapping} rows.
 */
public final class VehicleSpeedMappingCodec {

    private VehicleSpeedMappingCodec() {
    }

    public static SemanticSignalMappingMetadata toMetadata(String projectId, SpeedSourceMapping mapping) {
        if (mapping == null) {
            throw new IllegalArgumentException("mapping");
        }
        String project = projectId == null ? "" : projectId;
        String payload = encodePayload(mapping);
        if (mapping.coordinateDerived() || mapping.key() == null) {
            return new SemanticSignalMappingMetadata(
                    project, mapping.sourceId(), mapping.schemaFingerprint(),
                    SemanticSignalMappingMetadata.GROUP_VEHICLE_SPEED,
                    SemanticSignalMappingMetadata.SCOPE_PROJECT,
                    SemanticSignalMappingMetadata.ROLE_AUTHORITATIVE_SPEED,
                    SemanticSignalMappingMetadata.MODE_MANUAL_TEXT,
                    "", "", mapping.sourceId(), "SPEED", 0, payload, false, Instant.now());
        }
        SignalCatalogKey key = mapping.key();
        return new SemanticSignalMappingMetadata(
                project, mapping.sourceId(), mapping.schemaFingerprint(),
                SemanticSignalMappingMetadata.GROUP_VEHICLE_SPEED,
                SemanticSignalMappingMetadata.SCOPE_PROJECT,
                SemanticSignalMappingMetadata.ROLE_AUTHORITATIVE_SPEED,
                SemanticSignalMappingMetadata.MODE_SIGNAL_REFERENCE,
                key.signalName(), key.sourceType().name(), key.sourceFile(), key.messageName(),
                key.channel(), payload, false, Instant.now());
    }

    public static Optional<SpeedSourceMapping> fromMetadata(SemanticSignalMappingMetadata row) {
        if (row == null || !SemanticSignalMappingMetadata.GROUP_VEHICLE_SPEED.equals(row.configGroup())) {
            return Optional.empty();
        }
        if (!SemanticSignalMappingMetadata.ROLE_AUTHORITATIVE_SPEED.equals(row.role())) {
            return Optional.empty();
        }
        Payload payload = decodePayload(row.manualText());
        SpeedSourceKind kind = payload.kind;
        SignalCatalogKey key = null;
        if (!mappingDerived(row, kind)) {
            SignalSourceType type = parseSourceType(row.sourceType());
            key = new SignalCatalogKey(
                    row.signalName(), type, row.messageName(), 0, row.channel(), row.sourceFile(), "");
        }
        return Optional.of(new SpeedSourceMapping(
                payload.version, row.sourceId(), row.schemaFingerprint(), kind, key,
                payload.unit, payload.confidence, payload.reason));
    }

    private static boolean mappingDerived(SemanticSignalMappingMetadata row, SpeedSourceKind kind) {
        return kind == SpeedSourceKind.GPS_COORDINATE_DERIVED
                || SemanticSignalMappingMetadata.MODE_MANUAL_TEXT.equals(row.mappingMode())
                || row.signalName() == null
                || row.signalName().isBlank();
    }

    private static String encodePayload(SpeedSourceMapping mapping) {
        return mapping.resolverVersion()
                + "|" + mapping.kind().name()
                + "|" + mapping.confidence().name()
                + "|" + sanitize(mapping.originalUnit())
                + "|" + sanitize(mapping.reason());
    }

    private static Payload decodePayload(String manualText) {
        if (manualText == null || manualText.isBlank()) {
            return new Payload(VehicleSpeedSourceResolver.RESOLVER_VERSION,
                    SpeedSourceKind.GPS_COORDINATE_DERIVED, SpeedConfidence.LOW, "", "");
        }
        String[] parts = manualText.split("\\|", 5);
        int version = VehicleSpeedSourceResolver.RESOLVER_VERSION;
        if (parts.length > 0) {
            try {
                version = Integer.parseInt(parts[0]);
            } catch (NumberFormatException ignored) {
                version = VehicleSpeedSourceResolver.RESOLVER_VERSION;
            }
        }
        SpeedSourceKind kind = SpeedSourceKind.GPS_COORDINATE_DERIVED;
        if (parts.length > 1) {
            try {
                kind = SpeedSourceKind.valueOf(parts[1]);
            } catch (RuntimeException ignored) {
                kind = SpeedSourceKind.GPS_COORDINATE_DERIVED;
            }
        }
        SpeedConfidence confidence = SpeedConfidence.LOW;
        if (parts.length > 2) {
            try {
                confidence = SpeedConfidence.valueOf(parts[2]);
            } catch (RuntimeException ignored) {
                confidence = SpeedConfidence.LOW;
            }
        }
        String unit = parts.length > 3 ? parts[3] : "";
        String reason = parts.length > 4 ? parts[4] : "";
        return new Payload(version, kind, confidence, unit, reason);
    }

    private static SignalSourceType parseSourceType(String value) {
        if (value == null || value.isBlank()) {
            return SignalSourceType.MF4_NATIVE;
        }
        try {
            return SignalSourceType.valueOf(value);
        } catch (RuntimeException ignored) {
            return SignalSourceType.MF4_NATIVE;
        }
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace('|', '/');
    }

    private record Payload(
            int version,
            SpeedSourceKind kind,
            SpeedConfidence confidence,
            String unit,
            String reason
    ) {
    }
}
