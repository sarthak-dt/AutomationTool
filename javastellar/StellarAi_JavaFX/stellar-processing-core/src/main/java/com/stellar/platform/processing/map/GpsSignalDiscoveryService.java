package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Automatic GPS latitude/longitude discovery for Map Route.
 *
 * <p>Inspects the <em>complete</em> current-scope signal catalog. Graph checkbox membership is
 * irrelevant. Detection is two-stage: metadata scoring, then catalog min/max geographic validation
 * for likely candidates only — no full-series load and no scaling guesses.
 */
public final class GpsSignalDiscoveryService {

    private static final Logger LOG = Logger.getLogger(GpsSignalDiscoveryService.class.getName());

    /** Score gap below which two pairs are treated as ambiguous. */
    private static final int AMBIGUITY_GAP = 12;
    private static final int MIN_RELIABLE_SCORE = 25;

    private final Map<GpsSignalRole, SignalCatalogKey> overrides;
    private final ConcurrentHashMap<String, GpsDetectionResult> cache = new ConcurrentHashMap<>();

    public GpsSignalDiscoveryService() {
        this(Map.of());
    }

    public GpsSignalDiscoveryService(Map<GpsSignalRole, SignalCatalogKey> overrides) {
        this.overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
    }

    /**
     * @param catalogGeneration opaque generation token (project/scope/catalog revision)
     */
    public GpsDetectionResult discover(Collection<SignalCatalogEntry> catalog, String catalogGeneration) {
        return discover(catalog, catalogGeneration, Map.of());
    }

    public GpsDetectionResult discover(
            Collection<SignalCatalogEntry> catalog,
            String catalogGeneration,
            Map<GpsSignalRole, SignalCatalogKey> extraOverrides
    ) {
        Objects.requireNonNull(catalog, "catalog");
        String generation = catalogGeneration == null ? "" : catalogGeneration;
        Map<GpsSignalRole, SignalCatalogKey> merged = mergeOverrides(extraOverrides);
        String cacheKey = generation + "|" + catalog.size() + "|" + merged.hashCode();
        GpsDetectionResult cached = cache.get(cacheKey);
        if (cached != null) {
            LOG.fine(() -> "GPS discovery cache hit generation=" + generation
                    + " signals=" + catalog.size());
            return cached;
        }
        GpsDetectionResult fresh = discoverUncached(catalog, merged);
        cache.put(cacheKey, fresh);
        LOG.fine(() -> "GPS discovery cache store generation=" + generation
                + " route=" + fresh.hasReliableRoute()
                + " summary=" + fresh.summary());
        return fresh;
    }

    /** Convenience when the caller has no generation token. */
    public GpsDetectionResult discover(Collection<SignalCatalogEntry> catalog) {
        return discover(catalog, Integer.toString(System.identityHashCode(catalog)));
    }

    public void invalidate() {
        cache.clear();
    }

    public void invalidate(String catalogGeneration) {
        if (catalogGeneration == null || catalogGeneration.isBlank()) {
            invalidate();
            return;
        }
        cache.keySet().removeIf(k -> k.startsWith(catalogGeneration + "|"));
    }

    private Map<GpsSignalRole, SignalCatalogKey> mergeOverrides(
            Map<GpsSignalRole, SignalCatalogKey> extra) {
        if ((extra == null || extra.isEmpty()) && overrides.isEmpty()) {
            return Map.of();
        }
        EnumMap<GpsSignalRole, SignalCatalogKey> merged = new EnumMap<>(GpsSignalRole.class);
        merged.putAll(overrides);
        if (extra != null) {
            extra.forEach((role, key) -> {
                if (role != null && key != null) {
                    merged.put(role, key);
                }
            });
        }
        return merged;
    }

    private GpsDetectionResult discoverUncached(
            Collection<SignalCatalogEntry> catalog,
            Map<GpsSignalRole, SignalCatalogKey> sessionOverrides
    ) {
        List<SignalCatalogEntry> entries = catalog.stream().filter(Objects::nonNull).toList();
        if (entries.isEmpty()) {
            return GpsDetectionResult.none(
                    "GPS route unavailable. No signals are available in this measurement.",
                    GpsDetectionConfidence.NONE,
                    0, 0, 0);
        }

        if (!sessionOverrides.isEmpty()
                && sessionOverrides.get(GpsSignalRole.LATITUDE) != null
                && sessionOverrides.get(GpsSignalRole.LONGITUDE) != null) {
            SignalCatalogKey lat = sessionOverrides.get(GpsSignalRole.LATITUDE);
            SignalCatalogKey lon = sessionOverrides.get(GpsSignalRole.LONGITUDE);
            // Manual coordinates override the coordinate roles only. Altitude, GPS speed and course
            // are still discovered from the same recording, otherwise mapping Latitude/Longitude by
            // hand would silently strip those roles out of the route and its detail panels.
            Map<GpsSignalRole, SignalCatalogKey> roles = companionRoles(entries, lat, lon);
            roles.putAll(sessionOverrides);
            GpsCoordinatePair forced = buildPair(entries, roles, 100, GpsDetectionConfidence.HIGH,
                    "Manual latitude/longitude override");
            return new GpsDetectionResult(forced, List.of(), false, forced.evidence(),
                    GpsDetectionConfidence.HIGH, entries.size(), 1, 1);
        }

        List<GpsSignalCandidate> latCandidates = new ArrayList<>();
        List<GpsSignalCandidate> lonCandidates = new ArrayList<>();
        for (SignalCatalogEntry entry : entries) {
            int latScore = GpsSignalRoleResolver.scoreRole(GpsSignalRole.LATITUDE, entry);
            if (latScore > 0 && passesCoordinateRange(entry, true)) {
                latCandidates.add(GpsSignalCandidate.of(entry, GpsSignalRole.LATITUDE, latScore,
                        evidenceFor(entry, GpsSignalRole.LATITUDE, latScore)));
            }
            int lonScore = GpsSignalRoleResolver.scoreRole(GpsSignalRole.LONGITUDE, entry);
            if (lonScore > 0 && passesCoordinateRange(entry, false)) {
                lonCandidates.add(GpsSignalCandidate.of(entry, GpsSignalRole.LONGITUDE, lonScore,
                        evidenceFor(entry, GpsSignalRole.LONGITUDE, lonScore)));
            }
        }

        LOG.fine(() -> "GPS discovery scanned=" + entries.size()
                + " latCandidates=" + latCandidates.size()
                + " lonCandidates=" + lonCandidates.size()
                + " resolver=" + resolverLocation());
        traceGpsRelevant(entries);

        if (latCandidates.isEmpty() || lonCandidates.isEmpty()) {
            LOG.info(() -> "GPS discovery found no coordinate candidates resolver=" + resolverLocation()
                    + " scanned=" + entries.size()
                    + " latCandidates=" + latCandidates.size()
                    + " lonCandidates=" + lonCandidates.size()
                    + System.lineSeparator()
                    + gpsRelevantScoreDump(entries));
            return GpsDetectionResult.none(
                    "GPS route unavailable. No reliable latitude/longitude signal pair was found in this measurement."
                            + " Scanned " + entries.size() + " signals"
                            + " (lat candidates=" + latCandidates.size()
                            + ", lon candidates=" + lonCandidates.size() + ").",
                    GpsDetectionConfidence.NONE,
                    entries.size(),
                    latCandidates.size(),
                    lonCandidates.size());
        }

        List<GpsCoordinatePair> pairs = new ArrayList<>();
        for (GpsSignalCandidate lat : latCandidates) {
            for (GpsSignalCandidate lon : lonCandidates) {
                if (lat.key().equals(lon.key())) {
                    continue;
                }
                int pairScore = scorePair(lat, lon, entries);
                if (pairScore < MIN_RELIABLE_SCORE) {
                    continue;
                }
                GpsDetectionConfidence confidence = toConfidence(pairScore, lat.score(), lon.score());
                if (!confidence.isReliable()) {
                    continue;
                }
                Map<GpsSignalRole, SignalCatalogKey> roles = companionRoles(entries, lat.key(), lon.key());
                roles.put(GpsSignalRole.LATITUDE, lat.key());
                roles.put(GpsSignalRole.LONGITUDE, lon.key());
                String evidence = "lat=" + lat.key().signalName()
                        + " (" + lat.score() + ")"
                        + " · lon=" + lon.key().signalName()
                        + " (" + lon.score() + ")"
                        + " · pair=" + pairScore
                        + " · " + confidence
                        + " · source=" + sourceLabel(lat.key());
                pairs.add(buildPair(entries, roles, pairScore, confidence, evidence));
            }
        }

        if (pairs.isEmpty()) {
            return GpsDetectionResult.none(
                    "GPS route unavailable. No Latitude/Longitude pair met the confidence threshold."
                            + " Scanned " + entries.size() + " signals.",
                    GpsDetectionConfidence.LOW,
                    entries.size(),
                    latCandidates.size(),
                    lonCandidates.size());
        }

        pairs.sort(Comparator
                .comparingInt(GpsCoordinatePair::confidence).reversed()
                .thenComparing(p -> p.level().ordinal()));
        GpsCoordinatePair best = pairs.getFirst();
        List<GpsCoordinatePair> alts = pairs.size() > 1 ? List.copyOf(pairs.subList(1, pairs.size())) : List.of();
        boolean ambiguous = !alts.isEmpty()
                && best.confidence() - alts.getFirst().confidence() < AMBIGUITY_GAP
                && compatibleSources(best.latitude(), alts.getFirst().latitude());
        // Prefer a single best pair; never auto-render when two unrelated sources tie.
        if (ambiguous && !sameSourceFile(best.latitude(), alts.getFirst().latitude())) {
            return GpsDetectionResult.none(
                    "GPS route unavailable. Multiple latitude/longitude pairs have similar confidence;"
                            + " no single reliable pair could be chosen.",
                    GpsDetectionConfidence.LOW,
                    entries.size(),
                    latCandidates.size(),
                    lonCandidates.size());
        }
        String summary = "Auto detected · " + best.latitude().signalName()
                + " / " + best.longitude().signalName()
                + " · " + best.level();
        LOG.fine(() -> "GPS discovery selected " + summary + " · " + best.evidence());
        return new GpsDetectionResult(best, ambiguous ? alts : List.of(), ambiguous, summary,
                best.level(), entries.size(), latCandidates.size(), lonCandidates.size());
    }

    public static Map<GpsSignalRole, SignalCatalogKey> toRoleMap(GpsDetectionResult result) {
        EnumMap<GpsSignalRole, SignalCatalogKey> roles = new EnumMap<>(GpsSignalRole.class);
        if (result == null || !result.hasReliableRoute()) {
            return roles;
        }
        GpsCoordinatePair pair = result.pair().orElseThrow();
        roles.put(GpsSignalRole.LATITUDE, pair.latitude());
        roles.put(GpsSignalRole.LONGITUDE, pair.longitude());
        pair.altitudeOptional().ifPresent(k -> roles.put(GpsSignalRole.ALTITUDE, k));
        pair.speedOptional().ifPresent(k -> roles.put(GpsSignalRole.GPS_SPEED, k));
        pair.headingOptional().ifPresent(k -> roles.put(GpsSignalRole.COURSE, k));
        return roles;
    }

    private static GpsCoordinatePair buildPair(
            List<SignalCatalogEntry> catalog,
            Map<GpsSignalRole, SignalCatalogKey> roles,
            int score,
            GpsDetectionConfidence level,
            String evidence) {
        return new GpsCoordinatePair(
                roles.get(GpsSignalRole.LATITUDE),
                roles.get(GpsSignalRole.LONGITUDE),
                roles.get(GpsSignalRole.ALTITUDE),
                roles.get(GpsSignalRole.GPS_SPEED),
                roles.get(GpsSignalRole.COURSE),
                score,
                level,
                evidence);
    }

    private static Map<GpsSignalRole, SignalCatalogKey> companionRoles(
            List<SignalCatalogEntry> catalog, SignalCatalogKey lat, SignalCatalogKey lon) {
        List<SignalCatalogEntry> cohort = catalog.stream()
                .filter(e -> sameSourceFile(e.key(), lat) || sameSourceFile(e.key(), lon))
                .toList();
        Map<GpsSignalRole, SignalCatalogKey> roles = new GpsSignalRoleResolver().resolve(cohort);
        roles.remove(GpsSignalRole.LATITUDE);
        roles.remove(GpsSignalRole.LONGITUDE);
        return new EnumMap<>(roles);
    }

    /**
     * Pair score: individual role scores plus shared-source / shared-context bonuses.
     * Independent best-lat + best-lon across unrelated files is heavily penalised.
     */
    private static int scorePair(GpsSignalCandidate lat, GpsSignalCandidate lon, List<SignalCatalogEntry> catalog) {
        int base = Math.min(lat.score(), lon.score());
        if (!compatibleSources(lat.key(), lon.key())) {
            return 0;
        }
        if (sameSourceFile(lat.key(), lon.key())) {
            base += 20;
        } else {
            // Project-wide logical merge only: empty source files are allowed to pair.
            base += 4;
        }
        if (sameBusOrGroup(lat.key(), lon.key())) {
            base += 10;
        }
        if (sameMessage(lat.key(), lon.key())) {
            base += 8;
        }
        if (namingFamilyCompatible(lat.key().signalName(), lon.key().signalName())) {
            base += 6;
        }
        if (timeRangesOverlap(find(catalog, lat.key()), find(catalog, lon.key()))) {
            base += 6;
        }
        if (sampleCadenceCompatible(find(catalog, lat.key()), find(catalog, lon.key()))) {
            base += 4;
        }
        return base;
    }

    private static boolean compatibleSources(SignalCatalogKey a, SignalCatalogKey b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.sourceType() != b.sourceType()) {
            return false;
        }
        String fa = normalizeFile(a.sourceFile());
        String fb = normalizeFile(b.sourceFile());
        // Blank source file is the project-merged logical identity — may pair across files.
        if (fa.isEmpty() || fb.isEmpty()) {
            return true;
        }
        return fa.equals(fb);
    }

    private static boolean sameSourceFile(SignalCatalogKey a, SignalCatalogKey b) {
        if (a == null || b == null) {
            return false;
        }
        String fa = normalizeFile(a.sourceFile());
        String fb = normalizeFile(b.sourceFile());
        if (fa.isEmpty() || fb.isEmpty()) {
            return fa.equals(fb);
        }
        return fa.equals(fb);
    }

    private static boolean sameBusOrGroup(SignalCatalogKey a, SignalCatalogKey b) {
        return a.channel() == b.channel();
    }

    private static boolean sameMessage(SignalCatalogKey a, SignalCatalogKey b) {
        return a.messageName() != null && a.messageName().equalsIgnoreCase(b.messageName());
    }

    private static boolean namingFamilyCompatible(String latName, String lonName) {
        String a = normalizeTokens(latName);
        String b = normalizeTokens(lonName);
        for (String token : List.of("gps", "gnss", "position", "location", "nav", "vehicle", "coord")) {
            if (a.contains(token) && b.contains(token)) {
                return true;
            }
        }
        // Bare LATITUDE/LONGITUDE pair.
        return (a.contains("lat") && b.contains("lon")) || (a.contains("lat") && b.contains("lng"));
    }

    private static boolean timeRangesOverlap(SignalCatalogEntry a, SignalCatalogEntry b) {
        if (a == null || b == null) {
            return true;
        }
        Long a0 = a.firstTimestampNanos();
        Long a1 = a.lastTimestampNanos();
        Long b0 = b.firstTimestampNanos();
        Long b1 = b.lastTimestampNanos();
        if (a0 == null || a1 == null || b0 == null || b1 == null) {
            return true;
        }
        return a1 >= b0 && b1 >= a0;
    }

    private static boolean sampleCadenceCompatible(SignalCatalogEntry a, SignalCatalogEntry b) {
        if (a == null || b == null) {
            return true;
        }
        long sa = Math.max(1, a.sampleCount());
        long sb = Math.max(1, b.sampleCount());
        double ratio = sa >= sb ? (double) sa / sb : (double) sb / sa;
        return ratio <= 20.0;
    }

    /**
     * Stage-2 validation from catalog extrema only. Missing extrema do not reject the candidate.
     */
    private static boolean passesCoordinateRange(SignalCatalogEntry entry, boolean latitude) {
        Double min = entry.minPhysical();
        Double max = entry.maxPhysical();
        if (min == null || max == null) {
            return true;
        }
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            return false;
        }
        if (latitude) {
            return min >= -90.0 && max <= 90.0;
        }
        return min >= -180.0 && max <= 180.0;
    }

    private static GpsDetectionConfidence toConfidence(int pairScore, int latScore, int lonScore) {
        int roleFloor = Math.min(latScore, lonScore);
        if (pairScore >= 70 && roleFloor >= 40) {
            return GpsDetectionConfidence.HIGH;
        }
        if (pairScore >= MIN_RELIABLE_SCORE && roleFloor >= 20) {
            return GpsDetectionConfidence.MEDIUM;
        }
        if (pairScore >= 15) {
            return GpsDetectionConfidence.LOW;
        }
        return GpsDetectionConfidence.NONE;
    }

    private static String evidenceFor(SignalCatalogEntry entry, GpsSignalRole role, int score) {
        String unit = entry.unit() == null || entry.unit().isBlank() ? "unit=none" : "unit=" + entry.unit();
        return role + " name=" + entry.key().signalName() + " " + unit + " score=" + score;
    }

    private static SignalCatalogEntry find(List<SignalCatalogEntry> catalog, SignalCatalogKey key) {
        for (SignalCatalogEntry entry : catalog) {
            if (entry.key().equals(key)) {
                return entry;
            }
        }
        return null;
    }

    private static String sourceLabel(SignalCatalogKey key) {
        String file = normalizeFile(key.sourceFile());
        return file.isEmpty() ? "project" : file;
    }

    private static String normalizeFile(String file) {
        return file == null ? "" : file.trim().toLowerCase(Locale.ROOT);
    }

    public static String resolverLocation() {
        try {
            var domain = GpsSignalRoleResolver.class.getProtectionDomain();
            if (domain == null || domain.getCodeSource() == null || domain.getCodeSource().getLocation() == null) {
                return "(unknown)";
            }
            return domain.getCodeSource().getLocation().toString();
        } catch (RuntimeException ex) {
            return "(unavailable: " + ex.getMessage() + ")";
        }
    }

    private static void traceGpsRelevant(List<SignalCatalogEntry> entries) {
        if (!LOG.isLoggable(java.util.logging.Level.FINE)) {
            return;
        }
        LOG.fine(() -> gpsRelevantScoreDump(entries));
    }

    private static String gpsRelevantScoreDump(List<SignalCatalogEntry> entries) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("GPS-relevant catalog dump:");
        for (SignalCatalogEntry entry : entries) {
            if (entry == null || !gpsRelevant(entry)) {
                continue;
            }
            int lat = GpsSignalRoleResolver.scoreRole(GpsSignalRole.LATITUDE, entry);
            int lon = GpsSignalRoleResolver.scoreRole(GpsSignalRole.LONGITUDE, entry);
            int alt = GpsSignalRoleResolver.scoreRole(GpsSignalRole.ALTITUDE, entry);
            sb.append(System.lineSeparator())
                    .append("  name=").append(entry.key().signalName())
                    .append(" source=").append(entry.sourceType())
                    .append(" ch=").append(entry.channel())
                    .append(" msg=").append(entry.messageName())
                    .append(" can=").append(entry.canId())
                    .append(" unit=").append(entry.unit())
                    .append(" samples=").append(entry.sampleCount())
                    .append(" min=").append(entry.minPhysical())
                    .append(" max=").append(entry.maxPhysical())
                    .append(" latScore=").append(lat)
                    .append(" lonScore=").append(lon)
                    .append(" altScore=").append(alt)
                    .append(" reject=").append(rejectionReason(entry, lat, lon));
        }
        return sb.toString();
    }

    private static boolean gpsRelevant(SignalCatalogEntry entry) {
        String blob = (entry.signalName() + ' ' + entry.messageName() + ' ' + entry.unit())
                .toLowerCase(Locale.ROOT);
        return blob.contains("gps")
                || blob.contains("gnss")
                || blob.contains("lat")
                || blob.contains("lon")
                || blob.contains("coord")
                || blob.contains("position")
                || blob.contains("alt");
    }

    private static String rejectionReason(SignalCatalogEntry entry, int latScore, int lonScore) {
        if (latScore > 0 && lonScore > 0) {
            return "none";
        }
        if (latScore <= 0 && lonScore <= 0) {
            boolean rangeLat = passesCoordinateRange(entry, true);
            boolean rangeLon = passesCoordinateRange(entry, false);
            if (!rangeLat || !rangeLon) {
                return "range";
            }
            return "score=0";
        }
        if (latScore > 0 && !passesCoordinateRange(entry, true)) {
            return "lat-range";
        }
        if (lonScore > 0 && !passesCoordinateRange(entry, false)) {
            return "lon-range";
        }
        return "none";
    }

    private static String normalizeTokens(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}
