package com.stellar.platform.analysis.rules;

import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Explicit conversion only. Unknown units are never guessed.
 */
public final class UnitRegistry {

    public enum Family { SPEED, TEMPERATURE, PRESSURE, ANGLE, ROTATION, TORQUE, DIMENSIONLESS }

    private record UnitDef(Family family, double toCanonical, String canonical) {
    }

    private static final Map<String, UnitDef> UNITS = Map.ofEntries(
            entry("m/s", Family.SPEED, 1d, "m/s"),
            entry("km/h", Family.SPEED, 1d / 3.6d, "m/s"),
            entry("kph", Family.SPEED, 1d / 3.6d, "m/s"),
            entry("kmh", Family.SPEED, 1d / 3.6d, "m/s"),
            entry("mph", Family.SPEED, 0.44704d, "m/s"),
            entry("c", Family.TEMPERATURE, 1d, "C"),
            entry("°c", Family.TEMPERATURE, 1d, "C"),
            entry("degc", Family.TEMPERATURE, 1d, "C"),
            entry("f", Family.TEMPERATURE, Double.NaN, "C"),
            entry("°f", Family.TEMPERATURE, Double.NaN, "C"),
            entry("degf", Family.TEMPERATURE, Double.NaN, "C"),
            entry("pa", Family.PRESSURE, 1d, "Pa"),
            entry("kpa", Family.PRESSURE, 1000d, "Pa"),
            entry("bar", Family.PRESSURE, 100_000d, "Pa"),
            entry("deg", Family.ANGLE, 1d, "deg"),
            entry("°", Family.ANGLE, 1d, "deg"),
            entry("rad", Family.ANGLE, 180d / Math.PI, "deg"),
            entry("rpm", Family.ROTATION, 1d, "rpm"),
            entry("nm", Family.TORQUE, 1d, "Nm"),
            entry("n·m", Family.TORQUE, 1d, "Nm"),
            entry("", Family.DIMENSIONLESS, 1d, ""),
            entry("1", Family.DIMENSIONLESS, 1d, "")
    );

    private UnitRegistry() {
    }

    public static String normalize(String unit) {
        return unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean known(String unit) {
        return UNITS.containsKey(normalize(unit));
    }

    public static OptionalDouble convert(double value, String fromUnit, String toUnit) {
        if (!Double.isFinite(value)) {
            return OptionalDouble.empty();
        }
        String from = normalize(fromUnit);
        String to = normalize(toUnit);
        if (from.equals(to)) {
            return OptionalDouble.of(value);
        }
        UnitDef a = UNITS.get(from);
        UnitDef b = UNITS.get(to);
        if (a == null || b == null || a.family != b.family) {
            return OptionalDouble.empty();
        }
        if (a.family == Family.TEMPERATURE) {
            double celsius = toCelsius(value, from);
            if (!Double.isFinite(celsius)) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(fromCelsius(celsius, to));
        }
        if (!Double.isFinite(a.toCanonical) || !Double.isFinite(b.toCanonical) || b.toCanonical == 0d) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(value * a.toCanonical / b.toCanonical);
    }

    public static boolean compatible(String fromUnit, String toUnit) {
        return convert(1d, fromUnit, toUnit).isPresent();
    }

    private static double toCelsius(double value, String unit) {
        return switch (unit) {
            case "c", "°c", "degc" -> value;
            case "f", "°f", "degf" -> (value - 32d) * 5d / 9d;
            default -> Double.NaN;
        };
    }

    private static double fromCelsius(double celsius, String unit) {
        return switch (unit) {
            case "c", "°c", "degc" -> celsius;
            case "f", "°f", "degf" -> celsius * 9d / 5d + 32d;
            default -> Double.NaN;
        };
    }

    private static Map.Entry<String, UnitDef> entry(String unit, Family family, double factor, String canonical) {
        return Map.entry(unit, new UnitDef(family, factor, canonical));
    }
}
