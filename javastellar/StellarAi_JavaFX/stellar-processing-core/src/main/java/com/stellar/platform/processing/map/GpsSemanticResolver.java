package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Semantic GPS resolution outside the MF4 parser.
 *
 * <p>The parser only exposes catalog metadata and values. This resolver scores names, units,
 * message context and catalog extrema, then leaves LOW-confidence matches unmapped.
 */
public final class GpsSemanticResolver {

    private final GpsSignalDiscoveryService discovery;

    public GpsSemanticResolver() {
        this.discovery = new GpsSignalDiscoveryService();
    }

    public GpsSemanticResolver(GpsSignalDiscoveryService discovery) {
        this.discovery = discovery == null ? new GpsSignalDiscoveryService() : discovery;
    }

    public GpsDetectionResult detect(Collection<SignalCatalogEntry> catalog, String generation) {
        return discovery.discover(catalog, generation);
    }

    public GpsDetectionResult detect(
            Collection<SignalCatalogEntry> catalog,
            String generation,
            java.util.Map<GpsSignalRole, SignalCatalogKey> overrides
    ) {
        return discovery.discover(catalog, generation, overrides);
    }

    /**
     * Suggested candidates for a role, highest score first. The original catalog order is not
     * mutated — callers keep a separate "all signals" list.
     */
    public List<GpsSignalCandidate> suggest(GpsSignalRole role, Collection<SignalCatalogEntry> catalog) {
        Objects.requireNonNull(role, "role");
        List<GpsSignalCandidate> out = new ArrayList<>();
        if (catalog == null) {
            return List.of();
        }
        for (SignalCatalogEntry entry : catalog) {
            if (entry == null) {
                continue;
            }
            int score = GpsSignalRoleResolver.scoreRole(role, entry);
            if (score <= 0) {
                continue;
            }
            out.add(GpsSignalCandidate.of(entry, role, score,
                    role + " name=" + entry.key().signalName()
                            + " unit=" + (entry.unit() == null ? "" : entry.unit())
                            + " score=" + score));
        }
        out.sort(Comparator.comparingInt(GpsSignalCandidate::score).reversed());
        return List.copyOf(out);
    }

    public GpsDetectionConfidence confidenceOf(GpsSignalRole role, SignalCatalogEntry entry) {
        int score = GpsSignalRoleResolver.scoreRole(role, entry);
        return confidenceForScore(score);
    }

    public static GpsDetectionConfidence confidenceForScore(int score) {
        if (score >= 70) {
            return GpsDetectionConfidence.HIGH;
        }
        if (score >= 25) {
            return GpsDetectionConfidence.MEDIUM;
        }
        if (score > 0) {
            return GpsDetectionConfidence.LOW;
        }
        return GpsDetectionConfidence.NONE;
    }

    public static String schemaFingerprint(Collection<SignalCatalogEntry> catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return "0:empty";
        }
        List<String> names = catalog.stream()
                .filter(Objects::nonNull)
                .map(e -> e.key().sourceType().name() + "\t" + e.key().signalName().toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String name : names) {
                digest.update(name.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return names.size() + ":" + HexFormat.of().formatHex(digest.digest(), 0, 8);
        } catch (NoSuchAlgorithmException ex) {
            return names.size() + ":" + Integer.toHexString(names.hashCode());
        }
    }
}
