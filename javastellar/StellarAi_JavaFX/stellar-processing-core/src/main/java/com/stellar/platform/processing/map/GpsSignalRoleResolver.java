package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves GPS engineering roles from an existing signal catalog.
 *
 * <p>Detection is name/unit driven because measurement files carry no role metadata. Scoring keeps
 * one customer's naming from being hardcoded: exact canonical names win, then prefixed variants,
 * then abbreviations. Callers may override any role explicitly when detection is ambiguous.
 */
public final class GpsSignalRoleResolver {

    /** Highest score wins; ties resolve to the entry with more samples. */
    private static final int SCORE_EXACT = 100;
    private static final int SCORE_QUALIFIED = 70;
    private static final int SCORE_CONTAINS = 40;
    private static final int SCORE_ABBREVIATION = 25;
    private static final int UNIT_BONUS = 8;
    private static final int RANGE_BONUS = 8;
    private static final int MESSAGE_BONUS = 6;

    private static final Set<String> LATITUDE_STRONG_ALIASES = Set.of(
            "gpslatitude", "gnsslatitude", "vehiclelatitude", "navlatitude");
    private static final Set<String> LATITUDE_ALIASES = Set.of(
            "lat", "gpslat", "gnsslat", "positionlat", "navlat");
    private static final Set<String> LONGITUDE_STRONG_ALIASES = Set.of(
            "gpslongitude", "gnsslongitude", "vehiclelongitude", "navlongitude");
    private static final Set<String> LONGITUDE_ALIASES = Set.of(
            "lon", "lng", "long", "gpslon", "gpslng", "gnsslon", "positionlon", "navlon");
    private static final Set<String> ALTITUDE_STRONG_ALIASES = Set.of(
            "gpsaltitude", "gnssheight", "vehiclealtitude");
    private static final Set<String> ALTITUDE_ALIASES = Set.of(
            "alt", "elevation", "height", "gpsalt", "gnssalt");

    private final Map<GpsSignalRole, SignalCatalogKey> overrides;

    public GpsSignalRoleResolver() {
        this(Map.of());
    }

    public GpsSignalRoleResolver(Map<GpsSignalRole, SignalCatalogKey> overrides) {
        this.overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
    }

    /**
     * @return resolved roles; absent roles simply have no suitable signal in this measurement.
     */
    public Map<GpsSignalRole, SignalCatalogKey> resolve(Collection<SignalCatalogEntry> catalog) {
        Objects.requireNonNull(catalog, "catalog");
        Map<GpsSignalRole, SignalCatalogKey> resolved = new EnumMap<>(GpsSignalRole.class);
        Map<GpsSignalRole, Integer> bestScore = new EnumMap<>(GpsSignalRole.class);
        Map<GpsSignalRole, Long> bestSamples = new EnumMap<>(GpsSignalRole.class);

        for (SignalCatalogEntry entry : catalog) {
            if (entry == null) {
                continue;
            }
            for (GpsSignalRole role : GpsSignalRole.values()) {
                int score = score(role, entry);
                if (score <= 0) {
                    continue;
                }
                Integer current = bestScore.get(role);
                long samples = entry.sampleCount();
                boolean better = current == null
                        || score > current
                        || (score == current && samples > bestSamples.getOrDefault(role, -1L));
                if (better) {
                    bestScore.put(role, score);
                    bestSamples.put(role, samples);
                    resolved.put(role, entry.key());
                }
            }
        }

        resolved.putAll(overrides);
        return resolved;
    }

    public Optional<SignalCatalogKey> resolve(Collection<SignalCatalogEntry> catalog, GpsSignalRole role) {
        return Optional.ofNullable(resolve(catalog).get(role));
    }

    /**
     * Best role for one entry judged on its own metadata, without catalog context.
     *
     * <p>Unlike {@link #resolve(Collection)} this makes no "one signal per role" decision, so it
     * stays usable for classification where several recordings are mixed together.
     */
    public static Optional<GpsSignalRole> roleOf(SignalCatalogEntry entry) {
        if (entry == null) {
            return Optional.empty();
        }
        GpsSignalRole best = null;
        int bestScore = 0;
        for (GpsSignalRole role : GpsSignalRole.values()) {
            int score = score(role, entry);
            if (score > bestScore) {
                bestScore = score;
                best = role;
            }
        }
        return Optional.ofNullable(best);
    }

    /** A route can only be drawn when both coordinate roles are known. */
    public static boolean hasRoute(Map<GpsSignalRole, SignalCatalogKey> roles) {
        return roles != null
                && roles.get(GpsSignalRole.LATITUDE) != null
                && roles.get(GpsSignalRole.LONGITUDE) != null;
    }

    /** Package/test access to the role score for a single catalog entry. */
    public static int scoreRole(GpsSignalRole role, SignalCatalogEntry entry) {
        return score(role, entry);
    }

    private static int score(GpsSignalRole role, SignalCatalogEntry entry) {
        String name = SignalNameNormalizer.normalize(entry.key().signalName());
        if (name.isEmpty()) {
            return 0;
        }
        // Correction/offset factors that contain "longitude"/"latitude" must not become coordinates.
        if ((role == GpsSignalRole.LATITUDE || role == GpsSignalRole.LONGITUDE)
                && isCoordinateFalsePositive(name)) {
            return 0;
        }
        String unit = entry.unit() == null ? "" : entry.unit().trim().toLowerCase(Locale.ROOT);
        int base = aliasScore(role, name);
        if (base <= 0) {
            base = switch (role) {
                case LATITUDE -> coordinateScore(name, "latitude", "lat");
                case LONGITUDE -> coordinateScore(name, "longitude", "lon", "lng", "long");
                case ALTITUDE -> plainScore(name, "altitude", "alt", "height", "elevation");
                case GPS_SPEED -> gpsSpeedScore(name);
                case COURSE -> plainScore(name, "courseoverground", "course", "heading", "track", "bearing");
                case DISTANCE -> qualifiedScore(name, "distance", "grounddistance", "gpsdistance", "odometergps");
                case SATELLITE_COUNT -> plainScore(name, "numbersatellites", "numsatellites", "satellites", "satcount", "numsats");
                case GPS_QUALITY -> plainScore(name, "gpsquality", "fixquality", "gpsfix", "fixtype");
                case HORIZONTAL_ACCURACY -> plainScore(name, "horizontalaccuracy", "hdop", "horizontaldilution", "haccuracy");
                case VERTICAL_ACCURACY -> plainScore(name, "verticalaccuracy", "vdop", "verticaldilution", "vaccuracy");
            };
        }
        if (base <= 0) {
            return 0;
        }
        return base + unitBonus(role, unit) + messageBonus(entry) + rangeBonus(role, entry);
    }

    private static int aliasScore(GpsSignalRole role, String name) {
        Set<String> strong = switch (role) {
            case LATITUDE -> LATITUDE_STRONG_ALIASES;
            case LONGITUDE -> LONGITUDE_STRONG_ALIASES;
            case ALTITUDE -> ALTITUDE_STRONG_ALIASES;
            default -> Set.of();
        };
        if (strong.contains(name)) {
            return SCORE_EXACT - 5;
        }
        Set<String> aliases = switch (role) {
            case LATITUDE -> LATITUDE_ALIASES;
            case LONGITUDE -> LONGITUDE_ALIASES;
            case ALTITUDE -> ALTITUDE_ALIASES;
            default -> Set.of();
        };
        return aliases.contains(name) ? SCORE_QUALIFIED : 0;
    }

    private static int messageBonus(SignalCatalogEntry entry) {
        String message = SignalNameNormalizer.normalize(entry.key().messageName());
        if (message.contains("gps") || message.contains("gnss")
                || message.contains("position") || message.contains("nav")
                || message.contains("location")) {
            return MESSAGE_BONUS;
        }
        return 0;
    }

    private static int rangeBonus(GpsSignalRole role, SignalCatalogEntry entry) {
        Double min = entry.minPhysical();
        Double max = entry.maxPhysical();
        if (min == null || max == null || !Double.isFinite(min) || !Double.isFinite(max)) {
            return 0;
        }
        return switch (role) {
            case LATITUDE -> min >= -90.0 && max <= 90.0 ? RANGE_BONUS : 0;
            case LONGITUDE -> min >= -180.0 && max <= 180.0 ? RANGE_BONUS : 0;
            case ALTITUDE -> min >= -500.0 && max <= 20_000.0 ? RANGE_BONUS : 0;
            default -> 0;
        };
    }

    /**
     * Rejects engineering factors that embed coordinate vocabulary without describing a position
     * (e.g. {@code LongitudeCorrectionFactor}, {@code LatOffset}).
     */
    private static boolean isCoordinateFalsePositive(String name) {
        return name.contains("correction")
                || name.contains("factor")
                || name.contains("offset")
                || name.contains("bias")
                || name.contains("error")
                || name.contains("delta")
                || name.contains("scale")
                || name.contains("steering")
                || name.contains("wheel")
                || name.contains("angle")
                || name.contains("accuracy")
                || name.contains("uncertainty")
                || name.contains("quality")
                || name.contains("status")
                || name.contains("dop")
                || name.contains("fix")
                || name.contains("heading")
                || name.contains("bearing")
                || name.contains("course")
                || name.contains("speed")
                || name.contains("velocity")
                || name.contains("distance")
                || name.contains("sog");
    }

    /**
     * Coordinates are scored separately: bare {@code lat}/{@code lon} are valid names but must not
     * outrank a fully spelled signal, and must not match unrelated words that merely contain them.
     */
    private static int coordinateScore(String name, String canonical, String... abbreviations) {
        if (name.equals(canonical)) {
            return SCORE_EXACT;
        }
        for (String abbreviation : abbreviations) {
            if (name.equals(abbreviation)) {
                return SCORE_EXACT - 5;
            }
        }
        if (name.endsWith(canonical) || name.startsWith(canonical)) {
            return SCORE_QUALIFIED;
        }
        if (name.contains(canonical)) {
            return SCORE_CONTAINS;
        }
        for (String abbreviation : abbreviations) {
            if (isQualifiedAbbreviation(name, abbreviation)) {
                return SCORE_ABBREVIATION;
            }
        }
        return 0;
    }

    private static int plainScore(String name, String canonical, String... alternates) {
        if (name.equals(canonical)) {
            return SCORE_EXACT;
        }
        for (String alternate : alternates) {
            if (name.equals(alternate)) {
                return SCORE_EXACT - 5;
            }
        }
        if (name.endsWith(canonical) || name.startsWith(canonical)) {
            return SCORE_QUALIFIED;
        }
        if (name.contains(canonical)) {
            return SCORE_CONTAINS;
        }
        for (String alternate : alternates) {
            if (isQualifiedAbbreviation(name, alternate)) {
                return SCORE_ABBREVIATION;
            }
        }
        return 0;
    }

    /**
     * For words that are common outside navigation, an exact canonical name wins outright but a
     * bare occurrence is only accepted with a location qualifier. This keeps {@code BrakeDistance}
     * out of the location roles while {@code GROUND_DISTANCE} is recognised.
     */
    private static int qualifiedScore(String name, String bare, String... canonicals) {
        for (String canonical : canonicals) {
            if (name.equals(canonical)) {
                return SCORE_EXACT;
            }
        }
        if (!name.contains(bare)) {
            return 0;
        }
        boolean located = name.contains("gps") || name.contains("gnss")
                || name.contains("ground") || name.contains("overground");
        if (!located) {
            return 0;
        }
        return name.endsWith(bare) || name.startsWith(bare) ? SCORE_QUALIFIED : SCORE_CONTAINS;
    }

    /** {@code GPS_Speed} must outrank generic {@code VehicleSpeed} for the GPS speed role. */
    private static int gpsSpeedScore(String name) {
        boolean speed = name.contains("speed") || name.contains("sog");
        if (!speed) {
            return 0;
        }
        if (name.equals("gpsspeed") || name.equals("speedoverground") || name.equals("sog")) {
            return SCORE_EXACT;
        }
        if (name.contains("gps") || name.contains("gnss") || name.contains("overground")) {
            return SCORE_QUALIFIED;
        }
        return 0;
    }

    /**
     * Only accept a short abbreviation when it is delimited, so {@code Lat} matches but the
     * {@code lat} inside {@code Latch_State} does not.
     */
    private static boolean isQualifiedAbbreviation(String name, String abbreviation) {
        int index = name.indexOf(abbreviation);
        while (index >= 0) {
            boolean startBoundary = index == 0;
            int endIndex = index + abbreviation.length();
            boolean endBoundary = endIndex == name.length();
            if (startBoundary && endBoundary) {
                return true;
            }
            // Original names are normalized by removing separators, so require a GPS/position qualifier.
            // Allow a short trailing instance suffix (GPS_LAT_A → gpslata) and a trailing role token
            // (GPS_LAT_INFO / GPS_LONG_INFO → gpslatinfo) — never a hardcoded signal name.
            if (hasPositionQualifier(name)
                    && (startBoundary || endBoundary || isMinorInstanceSuffix(name, endIndex)
                    || isTrailingRoleToken(name, endIndex))) {
                return true;
            }
            index = name.indexOf(abbreviation, index + 1);
        }
        return false;
    }

    /** {@code GPS_LAT_A} / {@code Lon1} style suffixes after separator stripping. */
    private static boolean isMinorInstanceSuffix(String name, int endIndex) {
        if (endIndex >= name.length()) {
            return true;
        }
        String suffix = name.substring(endIndex);
        return suffix.length() <= 2 && suffix.chars().allMatch(Character::isLetterOrDigit);
    }

    /**
     * Trailing token after a qualified abbreviation: {@code GPS_LAT_INFO}, {@code GPS_LONG_INFO},
     * {@code GNSS_LAT_DATA}. Length is bounded so unrelated concatenations do not become coordinates.
     */
    private static boolean isTrailingRoleToken(String name, int endIndex) {
        if (endIndex >= name.length()) {
            return true;
        }
        String suffix = name.substring(endIndex);
        return suffix.length() <= 8 && suffix.chars().allMatch(Character::isLetterOrDigit);
    }

    private static boolean hasPositionQualifier(String name) {
        return name.contains("gps")
                || name.contains("gnss")
                || name.contains("position")
                || name.contains("location")
                || name.contains("coord")
                || name.contains("vehicle")
                || name.contains("nav");
    }

    private static int unitBonus(GpsSignalRole role, String unit) {
        if (unit.isEmpty()) {
            return 0;
        }
        return switch (role) {
            case LATITUDE, LONGITUDE, COURSE ->
                    unit.startsWith("deg") || unit.equals("°") || unit.equals("degree") || unit.equals("degrees")
                            ? UNIT_BONUS : 0;
            case ALTITUDE -> unit.equals("m") || unit.startsWith("met")
                    || unit.equals("ft") || unit.startsWith("feet") || unit.equals("foot")
                    ? UNIT_BONUS : 0;
            case GPS_SPEED -> unit.equals("km/h") || unit.equals("kmh") || unit.equals("m/s") || unit.equals("mph") ? UNIT_BONUS : 0;
            case DISTANCE, HORIZONTAL_ACCURACY, VERTICAL_ACCURACY ->
                    unit.equals("m") || unit.equals("km") || unit.startsWith("met") ? UNIT_BONUS : 0;
            case SATELLITE_COUNT -> unit.equals("count") || unit.isEmpty() ? UNIT_BONUS : 0;
            case GPS_QUALITY -> 0;
        };
    }

}
