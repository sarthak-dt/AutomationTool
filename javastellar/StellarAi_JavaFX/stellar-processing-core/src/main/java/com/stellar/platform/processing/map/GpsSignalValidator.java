package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;

/**
 * Advisory geographic validation for GPS roles. Manual mapping is never silently rejected.
 */
public final class GpsSignalValidator {

    public enum Severity {
        OK,
        SUSPICIOUS,
        IMPOSSIBLE
    }

    public record Result(Severity severity, String message) {
        public Result {
            severity = severity == null ? Severity.OK : severity;
            message = message == null ? "" : message;
        }

        public boolean blocking() {
            return severity == Severity.IMPOSSIBLE;
        }
    }

    /** Reported when a coordinate signal looks like it carries radians instead of degrees. */
    public static final String RADIAN_ADVISORY = "Coordinates may be in radians rather than degrees.";

    /** All observed fixes are exactly (0,0) — missing GPS, not a Gulf of Guinea drive. */
    public static final String ZERO_ZERO_ADVISORY =
            "All observed coordinates are (0,0). This is treated as a missing GPS fix, not a geographic route.";

    private static final double RADIAN_LATITUDE_LIMIT = Math.PI / 2 + 1e-9;
    private static final double RADIAN_LONGITUDE_LIMIT = Math.PI + 1e-9;

    private GpsSignalValidator() {
    }

    public static Result validate(GpsSignalRole role, SignalCatalogEntry entry) {
        if (role == null || entry == null) {
            return new Result(Severity.IMPOSSIBLE, "No signal is selected.");
        }
        Double min = entry.minPhysical();
        Double max = entry.maxPhysical();
        if (min != null && !Double.isFinite(min) || max != null && !Double.isFinite(max)) {
            return new Result(Severity.IMPOSSIBLE, "Selected signal contains non-numeric values.");
        }
        if ((role == GpsSignalRole.LATITUDE || role == GpsSignalRole.LONGITUDE)
                && declaresRadians(entry)) {
            return new Result(Severity.SUSPICIOUS,
                    RADIAN_ADVISORY + " Selected signal reports its unit as \"" + entry.unit().trim() + "\".");
        }
        return switch (role) {
            case LATITUDE -> validateRange(min, max, -90.0, 90.0, "Latitude");
            case LONGITUDE -> validateRange(min, max, -180.0, 180.0, "Longitude");
            case ALTITUDE -> validateAltitude(min, max);
            default -> new Result(Severity.OK, "");
        };
    }

    /**
     * Pair-level plausibility for the coordinate roles.
     *
     * <p>Radians cannot be recognised from one channel alone: a longitude of 0.12 is ambiguous
     * between radians and a genuine recording near Greenwich. Judging both coordinates together is
     * what makes the difference decidable, because a real latitude anywhere outside the tropics
     * cannot fit inside ±&pi;/2. Nothing is converted — a wrongly scaled route is preferable to a
     * silently rewritten measurement, so this only produces an advisory.
     */
    public static Result coordinatePairAdvisory(SignalCatalogEntry latitude, SignalCatalogEntry longitude) {
        if (latitude == null || longitude == null) {
            return new Result(Severity.OK, "");
        }
        if (exactlyZeroZero(latitude, longitude)) {
            return new Result(Severity.SUSPICIOUS, ZERO_ZERO_ADVISORY);
        }
        if (declaresDegrees(latitude) || declaresDegrees(longitude)) {
            return new Result(Severity.OK, "");
        }
        if (declaresRadians(latitude) || declaresRadians(longitude)) {
            String unit = !normalizedUnit(latitude).isEmpty() ? latitude.unit().trim() : longitude.unit().trim();
            return new Result(Severity.SUSPICIOUS,
                    RADIAN_ADVISORY + " Selected signal reports its unit as \"" + unit + "\".");
        }
        if (!withinMagnitude(latitude, RADIAN_LATITUDE_LIMIT)
                || !withinMagnitude(longitude, RADIAN_LONGITUDE_LIMIT)) {
            return new Result(Severity.OK, "");
        }
        return new Result(Severity.SUSPICIOUS, RADIAN_ADVISORY
                + " Both coordinates stay inside the radian range"
                + " (latitude " + format(latitude.minPhysical()) + " – " + format(latitude.maxPhysical())
                + ", longitude " + format(longitude.minPhysical()) + " – " + format(longitude.maxPhysical())
                + "). Verify the unit before trusting the route position.");
    }

    private static boolean withinMagnitude(SignalCatalogEntry entry, double limit) {
        Double min = entry.minPhysical();
        Double max = entry.maxPhysical();
        if (min == null || max == null || !Double.isFinite(min) || !Double.isFinite(max)) {
            return false;
        }
        return Math.max(Math.abs(min), Math.abs(max)) <= limit;
    }

    private static boolean declaresRadians(SignalCatalogEntry entry) {
        String unit = normalizedUnit(entry);
        return unit.equals("rad") || unit.equals("radian") || unit.equals("radians");
    }

    private static boolean declaresDegrees(SignalCatalogEntry entry) {
        String unit = normalizedUnit(entry);
        if (unit.isEmpty()) {
            return false;
        }
        if (unit.equals("°") || unit.equals("degree") || unit.equals("degrees")) {
            return true;
        }
        if (unit.contains("decimal degree")) {
            return true;
        }
        if (unit.startsWith("deg")) {
            return !isTemperatureUnit(unit);
        }
        return false;
    }

    private static boolean isTemperatureUnit(String unit) {
        return unit.startsWith("degc") || unit.startsWith("degf") || unit.startsWith("degk")
                || unit.contains("celsius") || unit.contains("fahrenheit");
    }

    private static boolean exactlyZeroZero(SignalCatalogEntry latitude, SignalCatalogEntry longitude) {
        return isExactZero(latitude) && isExactZero(longitude);
    }

    private static boolean isExactZero(SignalCatalogEntry entry) {
        Double min = entry.minPhysical();
        Double max = entry.maxPhysical();
        return min != null && max != null && min == 0.0 && max == 0.0;
    }

    private static String normalizedUnit(SignalCatalogEntry entry) {
        String unit = entry.unit();
        return unit == null ? "" : unit.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static boolean validSample(GpsSignalRole role, double value) {
        if (!Double.isFinite(value)) {
            return false;
        }
        return switch (role) {
            case LATITUDE -> GeoDistance.validLatitude(value);
            case LONGITUDE -> GeoDistance.validLongitude(value);
            default -> true;
        };
    }

    /**
     * A long run of exact (0,0) fixes is treated as missing GPS, not a Gulf of Guinea drive.
     * Isolated (0,0) points are left intact.
     */
    public static boolean suspiciousZeroZeroRun(int consecutiveZeros, int totalPoints) {
        if (totalPoints <= 0 || consecutiveZeros <= 0) {
            return false;
        }
        return consecutiveZeros >= 20 || consecutiveZeros == totalPoints;
    }

    private static Result validateRange(Double min, Double max, double lo, double hi, String label) {
        if (min == null || max == null) {
            return new Result(Severity.OK, "");
        }
        if (max < lo || min > hi) {
            return new Result(Severity.SUSPICIOUS,
                    "Selected signal does not appear to be a valid " + label
                            + " signal. Observed range: " + format(min) + " – " + format(max)
                            + ". Expected " + format(lo) + " – " + format(hi) + ".");
        }
        if (min < lo || max > hi) {
            return new Result(Severity.SUSPICIOUS,
                    "Selected signal does not appear to be a valid " + label
                            + " signal. Observed range: " + format(min) + " – " + format(max)
                            + ". Expected " + format(lo) + " – " + format(hi) + ".");
        }
        return new Result(Severity.OK, "");
    }

    private static Result validateAltitude(Double min, Double max) {
        if (min == null || max == null) {
            return new Result(Severity.OK, "");
        }
        if (min < -500 || max > 20_000) {
            return new Result(Severity.SUSPICIOUS,
                    "Selected signal is an unusual altitude range: "
                            + format(min) + " – " + format(max) + ".");
        }
        return new Result(Severity.OK, "");
    }

    private static String format(Double value) {
        return value == null ? "—" : String.format(java.util.Locale.ROOT, "%.4f", value);
    }
}
