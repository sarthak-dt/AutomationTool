package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Priority for GPS role assignment:
 * <ol>
 *   <li>Project mapping for this source, when the signals still exist</li>
 *   <li>Project mapping from another source with the same schema fingerprint</li>
 *   <li>Product default configuration</li>
 *   <li>Reliable automatic detection</li>
 *   <li>Ask the user to map signals</li>
 * </ol>
 *
 * <p>Roles resolve independently. Only Latitude and Longitude can invalidate a stored mapping: an
 * optional role whose signal disappeared is dropped and reported, because a route drawn from valid
 * coordinates must not be lost because a heading channel is no longer recorded.
 *
 * <p>A mapping may name its signal by catalog key or by a name the engineer typed. Both resolve
 * against the same catalog; a typed name that no signal carries is reported rather than guessed at.
 */
public final class GpsMappingPolicy {

    /** Without both of these there is no route, so their absence invalidates the whole mapping. */
    private static final Set<GpsSignalRole> REQUIRED_ROLES =
            Set.of(GpsSignalRole.LATITUDE, GpsSignalRole.LONGITUDE);

    public enum Origin {
        USER_CONFIRMED,
        SCHEMA_REUSED,
        PRODUCT_DEFAULT,
        AUTO,
        UNRESOLVED
    }

    public record Resolution(
            Map<GpsSignalRole, SignalCatalogKey> overrides,
            Origin origin,
            boolean persistedMissing,
            String statusMessage,
            List<GpsSignalRole> unavailableOptionalRoles,
            List<GpsSignalMapping> appliedMappings
    ) {
        public Resolution {
            overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
            origin = origin == null ? Origin.UNRESOLVED : origin;
            statusMessage = statusMessage == null ? "" : statusMessage;
            unavailableOptionalRoles = unavailableOptionalRoles == null
                    ? List.of() : List.copyOf(unavailableOptionalRoles);
            appliedMappings = appliedMappings == null ? List.of() : List.copyOf(appliedMappings);
        }

        public Resolution(
                Map<GpsSignalRole, SignalCatalogKey> overrides,
                Origin origin,
                boolean persistedMissing,
                String statusMessage
        ) {
            this(overrides, origin, persistedMissing, statusMessage, List.of(), List.of());
        }

        public Resolution(
                Map<GpsSignalRole, SignalCatalogKey> overrides,
                Origin origin,
                boolean persistedMissing,
                String statusMessage,
                List<GpsSignalRole> unavailableOptionalRoles
        ) {
            this(overrides, origin, persistedMissing, statusMessage, unavailableOptionalRoles, List.of());
        }

        public boolean hasRouteOverrides() {
            return GpsSignalRoleResolver.hasRoute(overrides);
        }

        /** True when the coordinates survived but a stored optional role had to be dropped. */
        public boolean optionalRolesDropped() {
            return !unavailableOptionalRoles.isEmpty();
        }
    }

    private GpsMappingPolicy() {
    }

    public static Resolution resolve(
            String sourceId,
            String schemaFingerprint,
            Collection<SignalCatalogEntry> catalog,
            Collection<GpsSignalMapping> persisted
    ) {
        return resolve(sourceId, schemaFingerprint, catalog, persisted, List.of());
    }

    /**
     * @param persisted project-scoped mappings for the whole project, not only this source
     * @param defaults  product-wide mappings, consulted only when the project has none that apply
     */
    public static Resolution resolve(
            String sourceId,
            String schemaFingerprint,
            Collection<SignalCatalogEntry> catalog,
            Collection<GpsSignalMapping> persisted,
            Collection<GpsSignalMapping> defaults
    ) {
        Objects.requireNonNull(catalog, "catalog");
        List<GpsSignalMapping> mappings = persisted == null ? List.of() : List.copyOf(persisted);
        Map<SignalCatalogKey, SignalCatalogEntry> byKey = index(catalog);

        List<GpsSignalMapping> sourceConfirmed = mappings.stream()
                .filter(m -> m.userConfirmed() && sameSource(sourceId, m.sourceId()))
                .toList();
        if (!sourceConfirmed.isEmpty()) {
            return fromPersisted(sourceConfirmed, byKey, Origin.USER_CONFIRMED,
                    "Restored GPS mapping",
                    "Previously mapped GPS signal is no longer available.");
        }

        String fingerprint = schemaFingerprint == null ? "" : schemaFingerprint;
        if (!fingerprint.isBlank()) {
            List<GpsSignalMapping> schemaConfirmed = mappings.stream()
                    .filter(m -> m.userConfirmed() && fingerprint.equals(m.schemaFingerprint()))
                    .toList();
            if (!schemaConfirmed.isEmpty()) {
                return fromPersisted(schemaConfirmed, byKey, Origin.SCHEMA_REUSED,
                        "Reused GPS mapping from a compatible measurement schema",
                        "Previously mapped GPS signal is no longer available.");
            }
        }

        List<GpsSignalMapping> productDefaults = defaults == null ? List.of() : defaults.stream()
                .filter(m -> m != null && m.userConfirmed())
                .toList();
        if (!productDefaults.isEmpty()) {
            return fromPersisted(productDefaults, byKey, Origin.PRODUCT_DEFAULT,
                    "Using the product default route configuration",
                    "The product default names a signal this measurement does not contain.");
        }
        return new Resolution(Map.of(), Origin.UNRESOLVED, false, "");
    }

    private static Resolution fromPersisted(
            List<GpsSignalMapping> mappings,
            Map<SignalCatalogKey, SignalCatalogEntry> byKey,
            Origin origin,
            String okMessage,
            String missingMessage
    ) {
        EnumMap<GpsSignalRole, SignalCatalogKey> roles = new EnumMap<>(GpsSignalRole.class);
        List<String> missingRequired = new ArrayList<>();
        List<GpsSignalRole> missingOptional = new ArrayList<>();
        for (GpsSignalMapping mapping : mappings) {
            SignalCatalogKey resolved = mapping.manual()
                    ? findByName(mapping.manualSignalName(), byKey)
                    : findCompatible(mapping.key(), byKey);
            if (resolved != null) {
                roles.put(mapping.role(), resolved);
            } else if (REQUIRED_ROLES.contains(mapping.role())) {
                missingRequired.add(mapping.role().displayName() + " → " + mapping.requestedSignalName());
            } else {
                missingOptional.add(mapping.role());
            }
        }
        if (!missingRequired.isEmpty()) {
            return new Resolution(Map.of(), Origin.UNRESOLVED, true,
                    missingMessage + " Missing: " + String.join(", ", missingRequired),
                    missingOptional, mappings);
        }
        if (!missingOptional.isEmpty()) {
            // The coordinates are intact, so the route stands; only the dropped roles are reported.
            return new Resolution(roles, origin, false, optionalDroppedMessage(missingOptional),
                    missingOptional, mappings);
        }
        return new Resolution(roles, origin, false, okMessage, List.of(), mappings);
    }

    private static String optionalDroppedMessage(List<GpsSignalRole> roles) {
        String names = roles.stream().map(GpsSignalRole::displayName).collect(Collectors.joining(", "));
        return roles.size() == 1
                ? names + " mapping is no longer available."
                : names + " mappings are no longer available.";
    }

    private static SignalCatalogKey findCompatible(
            SignalCatalogKey wanted, Map<SignalCatalogKey, SignalCatalogEntry> byKey) {
        if (wanted == null) {
            return null;
        }
        if (byKey.containsKey(wanted)) {
            return wanted;
        }
        for (SignalCatalogKey key : byKey.keySet()) {
            if (key.signalName().equals(wanted.signalName())
                    && key.sourceType() == wanted.sourceType()) {
                return key;
            }
        }
        return null;
    }

    /**
     * Binds a typed signal name to a catalog entry. The measurement's own spelling wins, but the
     * lookup ignores case so a configuration typed from a datasheet still binds.
     */
    private static SignalCatalogKey findByName(
            String wanted, Map<SignalCatalogKey, SignalCatalogEntry> byKey) {
        if (wanted == null || wanted.isBlank()) {
            return null;
        }
        String name = wanted.trim();
        for (SignalCatalogKey key : byKey.keySet()) {
            if (key.signalName().equals(name)) {
                return key;
            }
        }
        for (SignalCatalogKey key : byKey.keySet()) {
            if (key.signalName().equalsIgnoreCase(name)) {
                return key;
            }
        }
        return null;
    }

    private static Map<SignalCatalogKey, SignalCatalogEntry> index(Collection<SignalCatalogEntry> catalog) {
        Map<SignalCatalogKey, SignalCatalogEntry> byKey = new java.util.LinkedHashMap<>();
        for (SignalCatalogEntry entry : catalog) {
            if (entry != null) {
                byKey.put(entry.key(), entry);
            }
        }
        return byKey;
    }

    private static boolean sameSource(String expected, String actual) {
        String a = expected == null ? "" : expected.trim();
        String b = actual == null ? "" : actual.trim();
        return a.equalsIgnoreCase(b);
    }
}
