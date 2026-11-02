package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The single definition of what a GPS mapping is scoped to.
 *
 * <p>A GPS mapping belongs to a measurement schema, so both the screen that saves one and the map
 * that resolves one have to agree on which catalog that schema is computed from. When they did not,
 * Configuration saved a mapping fingerprinted over one measurement's signals while Map Route looked
 * it up by a fingerprint taken over every signal in the project — the two never matched, and a
 * mapping saved for the second measurement of a project simply had no effect.
 *
 * <p>Scoping is by source file. Entries with no source file belong to the project-merged logical
 * identity and are visible to every source, because that is how a project assembled from processed
 * artifacts rather than named measurement files presents itself.
 */
public final class GpsMappingScope {

    /** Source identity for a processed catalog that carries no measurement file name. */
    public static final String MERGED_SOURCE = "Measurement loaded";

    private GpsMappingScope() {
    }

    /** Measurement sources a GPS mapping can be configured for, in catalog order. */
    public static List<String> sourceIds(Collection<SignalCatalogEntry> catalog) {
        if (catalog == null) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (SignalCatalogEntry entry : catalog) {
            String file = entry == null ? null : entry.sourceFile();
            if (file != null && !file.isBlank()) {
                ids.add(file);
            }
        }
        return List.copyOf(ids);
    }

    public static String defaultSourceId(Collection<SignalCatalogEntry> catalog) {
        List<String> ids = sourceIds(catalog);
        return ids.isEmpty() ? MERGED_SOURCE : ids.getFirst();
    }

    /** True for a source identity that means "the whole processed catalog". */
    public static boolean isMerged(String sourceId) {
        return sourceId == null || sourceId.isBlank() || MERGED_SOURCE.equals(sourceId);
    }

    /**
     * Catalog a mapping for {@code sourceId} may choose from, and the catalog its schema fingerprint
     * must be computed over. Unknown or unmatched sources fail closed (empty). Whole-project
     * fallback is forbidden when named measurements exist.
     */
    public static List<SignalCatalogEntry> catalogFor(String sourceId, Collection<SignalCatalogEntry> catalog) {
        List<SignalCatalogEntry> all = catalog == null ? List.of() : List.copyOf(catalog);
        if (isMerged(sourceId)) {
            return sourceIds(all).isEmpty() ? all : List.of();
        }
        List<SignalCatalogEntry> scoped = new ArrayList<>();
        for (SignalCatalogEntry entry : all) {
            if (entry == null) {
                continue;
            }
            if (belongsToSource(entry, sourceId)) {
                scoped.add(entry);
            }
        }
        return List.copyOf(scoped);
    }

    static boolean belongsToSource(SignalCatalogEntry entry, String sourceId) {
        String display = entry.sourceFile();
        String identity = entry.key() == null ? "" : entry.key().sourceFile();
        if ((display == null || display.isBlank()) && (identity == null || identity.isBlank())) {
            return true;
        }
        return matchesSource(display, sourceId) || matchesSource(identity, sourceId);
    }

    private static boolean matchesSource(String value, String sourceId) {
        if (value == null || value.isBlank() || sourceId == null || sourceId.isBlank()) {
            return false;
        }
        if (value.equalsIgnoreCase(sourceId)) {
            return true;
        }
        return fileName(value).equalsIgnoreCase(fileName(sourceId));
    }

    private static String fileName(String pathOrName) {
        if (pathOrName == null || pathOrName.isBlank()) {
            return "";
        }
        String normalized = pathOrName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 && slash < normalized.length() - 1 ? normalized.substring(slash + 1) : normalized;
    }

    /** Schema fingerprint for one source. Must be used on both save and resolution. */
    public static String fingerprintFor(String sourceId, Collection<SignalCatalogEntry> catalog) {
        return GpsSemanticResolver.schemaFingerprint(catalogFor(sourceId, catalog));
    }

    /**
     * Which measurement a route should resolve its GPS mapping for.
     *
     * <p>The displayed measurement decides. An unknown or blank preferred identity fails closed
     * (empty string) instead of borrowing another file's mapping or the first catalog source.
     * Schema reuse still happens later inside {@link GpsMappingPolicy} for the resolved source.
     *
     * @param preferredSourceId canonical source id or display filename of the active measurement
     */
    public static String resolveRouteSource(
            String preferredSourceId,
            Collection<SignalCatalogEntry> catalog,
            Collection<GpsSignalMapping> persisted
    ) {
        List<String> ids = sourceIds(catalog);
        if (ids.isEmpty()) {
            return preferredSourceId == null || preferredSourceId.isBlank()
                    || MERGED_SOURCE.equals(preferredSourceId)
                    ? MERGED_SOURCE
                    : "";
        }
        if (preferredSourceId == null || preferredSourceId.isBlank()) {
            return "";
        }
        for (String id : ids) {
            if (matchesSource(id, preferredSourceId)) {
                return id;
            }
        }
        List<SignalCatalogEntry> all = catalog == null ? List.of() : List.copyOf(catalog);
        for (SignalCatalogEntry entry : all) {
            if (entry != null && belongsToSource(entry, preferredSourceId)) {
                String identity = entry.key() == null ? "" : entry.key().sourceFile();
                String display = entry.sourceFile();
                if (matchesSource(identity, preferredSourceId) && identity != null && !identity.isBlank()) {
                    return identity;
                }
                if (display != null && !display.isBlank()) {
                    return display;
                }
                return identity == null ? "" : identity;
            }
        }
        return "";
    }

    /**
     * Legacy GPS rows may be keyed by display filename. Match those without creating a second row.
     */
    public static boolean mappingBelongsToSource(
            String mappingSourceId,
            String canonicalSourceId,
            String fileName
    ) {
        return matchesSource(mappingSourceId, canonicalSourceId)
                || matchesSource(mappingSourceId, fileName);
    }

    public static List<GpsSignalMapping> mappingsForMeasurement(
            String canonicalSourceId,
            String fileName,
            Collection<GpsSignalMapping> persisted
    ) {
        if (persisted == null || persisted.isEmpty()) {
            return List.of();
        }
        List<GpsSignalMapping> out = new ArrayList<>();
        for (GpsSignalMapping mapping : persisted) {
            if (mapping == null || !mappingBelongsToSource(mapping.sourceId(), canonicalSourceId, fileName)) {
                continue;
            }
            if (canonicalSourceId != null && !canonicalSourceId.isBlank()
                    && !canonicalSourceId.equals(mapping.sourceId())) {
                out.add(new GpsSignalMapping(
                        mapping.projectId(),
                        canonicalSourceId,
                        mapping.schemaFingerprint(),
                        mapping.role(),
                        mapping.mode(),
                        mapping.key(),
                        mapping.manualSignalName(),
                        mapping.scope(),
                        mapping.userConfirmed(),
                        mapping.updatedAt()));
            } else {
                out.add(mapping);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Rewrites this measurement's persisted rows onto {@code lookupSourceId} so Configuration
     * (display filename) and Map Route (canonical source id) resolve the same mapping without
     * duplicating rows. Other measurements stay unchanged so schema reuse can still find them.
     */
    public static List<GpsSignalMapping> aliasMappingsForLookup(
            String lookupSourceId,
            String canonicalSourceId,
            String fileName,
            Collection<GpsSignalMapping> persisted
    ) {
        if (persisted == null || persisted.isEmpty()) {
            return List.of();
        }
        String lookup = lookupSourceId == null ? "" : lookupSourceId;
        List<GpsSignalMapping> out = new ArrayList<>();
        for (GpsSignalMapping mapping : persisted) {
            if (mapping == null) {
                continue;
            }
            boolean owned = mappingBelongsToSource(mapping.sourceId(), canonicalSourceId, fileName)
                    || mappingBelongsToSource(mapping.sourceId(), lookup, lookup);
            if (owned && !lookup.isBlank() && !lookup.equals(mapping.sourceId())) {
                out.add(copyWithSource(mapping, lookup));
            } else {
                out.add(mapping);
            }
        }
        return List.copyOf(out);
    }

    private static GpsSignalMapping copyWithSource(GpsSignalMapping mapping, String sourceId) {
        return new GpsSignalMapping(
                mapping.projectId(),
                sourceId,
                mapping.schemaFingerprint(),
                mapping.role(),
                mapping.mode(),
                mapping.key(),
                mapping.manualSignalName(),
                mapping.scope(),
                mapping.userConfirmed(),
                mapping.updatedAt());
    }

    /** True when {@code sourceId} owns a user-confirmed mapping that still resolves to its catalog. */
    private static boolean hasStoredMapping(
            String sourceId,
            Collection<SignalCatalogEntry> catalog,
            Collection<GpsSignalMapping> persisted
    ) {
        if (sourceId == null || sourceId.isBlank() || persisted == null || persisted.isEmpty()) {
            return false;
        }
        boolean owned = persisted.stream()
                .anyMatch(m -> m != null && m.userConfirmed() && sourceId.equals(m.sourceId()));
        if (!owned) {
            return false;
        }
        List<SignalCatalogEntry> scoped = catalogFor(sourceId, catalog);
        return GpsMappingPolicy
                .resolve(sourceId, GpsSemanticResolver.schemaFingerprint(scoped), scoped, persisted)
                .hasRouteOverrides();
    }
}
