package com.stellar.platform.processing.drive.speed;

import java.util.Locale;
import java.util.Optional;

/**
 * Canonical speed-unit conversion. Unknown or ambiguous metadata is not guessed.
 */
public enum SpeedUnit {
    KILOMETRES_PER_HOUR(1.0),
    METRES_PER_SECOND(3.6),
    MILES_PER_HOUR(1.609344);

    public static final SpeedUnit CANONICAL = KILOMETRES_PER_HOUR;

    private final double toKph;

    SpeedUnit(double toKph) {
        this.toKph = toKph;
    }

    public double toKilometresPerHour(double value) {
        return value * toKph;
    }

    public String symbol() {
        return switch (this) {
            case KILOMETRES_PER_HOUR -> "km/h";
            case METRES_PER_SECOND -> "m/s";
            case MILES_PER_HOUR -> "mph";
        };
    }

    /**
     * @return empty when the string is blank or not a recognised speed unit
     */
    public static Optional<SpeedUnit> parse(String unit) {
        String normalized = normalize(unit);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return switch (normalized) {
            case "km/h", "kmh", "kph", "kmph", "km/hr", "kmperh", "kmperhour" ->
                    Optional.of(KILOMETRES_PER_HOUR);
            case "m/s", "mps", "m/sec", "meter/s", "meters/s", "metre/s", "metres/s" ->
                    Optional.of(METRES_PER_SECOND);
            case "mph", "mi/h", "mi/hr", "mile/h", "miles/h", "mile/hr", "miles/hr" ->
                    Optional.of(MILES_PER_HOUR);
            default -> Optional.empty();
        };
    }

    static String normalize(String unit) {
        if (unit == null || unit.isBlank()) {
            return "";
        }
        String folded = unit.toLowerCase(Locale.ROOT).trim()
                .replace("kilometres", "km")
                .replace("kilometers", "km")
                .replace("kilometre", "km")
                .replace("kilometer", "km")
                .replace("metres", "m")
                .replace("meters", "m")
                .replace("metre", "m")
                .replace("meter", "m")
                .replace("hours", "h")
                .replace("hour", "h")
                .replace("secs", "s")
                .replace("sec", "s")
                .replace("seconds", "s")
                .replace("second", "s");
        StringBuilder out = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            if (!Character.isWhitespace(c) && c != '_') {
                out.append(c);
            }
        }
        return out.toString();
    }
}
