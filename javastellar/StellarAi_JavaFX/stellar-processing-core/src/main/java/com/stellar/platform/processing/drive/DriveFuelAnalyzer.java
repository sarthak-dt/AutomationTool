package com.stellar.platform.processing.drive;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.graph.GraphSeriesQuery;

import java.util.List;
import java.util.Locale;

/**
 * Fuel metrics are emitted only when a consumption-rate or cumulative-used signal has known units.
 * Tank level is never treated as authoritative fuel used.
 */
public final class DriveFuelAnalyzer {

    public static final int MAX_SAMPLES = 8_192;

    public DriveFuelSummary analyze(GraphSeriesQuery query,
                                    SignalCatalogEntry rateEntry,
                                    SignalCatalogEntry usedEntry,
                                    SignalCatalogEntry levelEntry,
                                    long startNanos,
                                    long endNanos,
                                    double distanceMeters) {
        if (query == null || endNanos < startNanos) {
            return DriveFuelSummary.unavailable("No processed fuel signal in this interval.");
        }
        if (rateEntry != null) {
            DriveFuelSummary fromRate = integrateRate(query, rateEntry, startNanos, endNanos, distanceMeters);
            if (fromRate.availability() != DriveFuelSummary.Availability.NOT_AVAILABLE) {
                return fromRate;
            }
        }
        if (usedEntry != null) {
            DriveFuelSummary fromUsed = deltaUsed(query, usedEntry, startNanos, endNanos, distanceMeters);
            if (fromUsed.availability() != DriveFuelSummary.Availability.NOT_AVAILABLE) {
                return fromUsed;
            }
        }
        if (levelEntry != null) {
            return DriveFuelSummary.tankLevelUnreliable(levelEntry.signalName());
        }
        return DriveFuelSummary.unavailable("No mapped fuel-rate or fuel-used signal.");
    }

    private static DriveFuelSummary integrateRate(GraphSeriesQuery query,
                                                  SignalCatalogEntry entry,
                                                  long startNanos,
                                                  long endNanos,
                                                  double distanceMeters) {
        String unit = firstNonBlank(query.unit(entry.key()), entry.unit());
        FuelRateUnit parsed = FuelRateUnit.parse(unit);
        if (parsed == FuelRateUnit.UNKNOWN) {
            return DriveFuelSummary.unknownUnits(entry.signalName(), unit);
        }
        List<SignalSamplePoint> samples = query.queryRange(entry.key(), startNanos, endNanos, MAX_SAMPLES);
        if (samples == null || samples.size() < 2) {
            return DriveFuelSummary.unavailable("Fuel rate has too few samples in this interval.");
        }
        double liters = 0;
        int used = 0;
        for (int i = 1; i < samples.size(); i++) {
            long dtNanos = samples.get(i).timestampNanos() - samples.get(i - 1).timestampNanos();
            if (dtNanos <= 0) {
                continue;
            }
            SignalSamplePoint previous = samples.get(i - 1);
            SignalSamplePoint current = samples.get(i);
            if (!previous.usableMeasurement() || !current.usableMeasurement()) {
                continue;
            }
            double rate = previous.physicalValue();
            if (rate < 0) {
                continue;
            }
            liters += parsed.toLiters(rate) * (dtNanos / 1e9);
            used++;
        }
        if (used == 0) {
            return DriveFuelSummary.unavailable("Fuel rate samples were not numerically usable.");
        }
        return DriveFuelSummary.available(liters, economy(distanceMeters, liters), entry.signalName());
    }

    private static DriveFuelSummary deltaUsed(GraphSeriesQuery query,
                                              SignalCatalogEntry entry,
                                              long startNanos,
                                              long endNanos,
                                              double distanceMeters) {
        String unit = firstNonBlank(query.unit(entry.key()), entry.unit());
        if (looksLikeTankPercent(unit, entry.signalName())) {
            return DriveFuelSummary.tankLevelUnreliable(entry.signalName());
        }
        FuelVolumeUnit parsed = FuelVolumeUnit.parse(unit);
        if (parsed == FuelVolumeUnit.UNKNOWN) {
            return DriveFuelSummary.unknownUnits(entry.signalName(), unit);
        }
        List<SignalSamplePoint> samples = query.queryRange(entry.key(), startNanos, endNanos, MAX_SAMPLES);
        if (samples == null || samples.size() < 2) {
            return DriveFuelSummary.unavailable("Fuel-used signal has too few samples in this interval.");
        }
        Double first = null;
        Double last = null;
        int eligible = 0;
        for (SignalSamplePoint sample : samples) {
            if (!sample.usableMeasurement()) {
                continue;
            }
            eligible++;
            if (first == null) {
                first = sample.physicalValue();
            }
            last = sample.physicalValue();
        }
        if (eligible < 2 || first == null || last == null || last < first) {
            return DriveFuelSummary.unavailable("Fuel-used signal did not increase over the interval.");
        }
        double liters = parsed.toLiters(last - first);
        return DriveFuelSummary.available(liters, economy(distanceMeters, liters), entry.signalName());
    }

    private static Double economy(double distanceMeters, double liters) {
        if (!(liters > 0) || !(distanceMeters > 0)) {
            return null;
        }
        return (distanceMeters / 1000.0) / liters;
    }

    private static boolean looksLikeTankPercent(String unit, String name) {
        String text = ((unit == null ? "" : unit) + " " + (name == null ? "" : name)).toLowerCase(Locale.ROOT);
        return text.contains("%") || text.contains("percent") || text.contains("level") || text.contains("tank");
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null ? "" : b;
    }

    enum FuelRateUnit {
        LITERS_PER_HOUR(1.0 / 3600.0),
        LITERS_PER_SECOND(1.0),
        MILLILITERS_PER_HOUR(1.0 / 3_600_000.0),
        MILLILITERS_PER_SECOND(0.001),
        UNKNOWN(Double.NaN);

        private final double toLitersPerSecond;

        FuelRateUnit(double toLitersPerSecond) {
            this.toLitersPerSecond = toLitersPerSecond;
        }

        double toLiters(double rate) {
            return rate * toLitersPerSecond;
        }

        static FuelRateUnit parse(String unit) {
            String u = normalize(unit);
            if (u.isEmpty()) {
                return UNKNOWN;
            }
            if (u.equals("l/h") || u.equals("l/hr") || u.equals("lph") || u.equals("l/hour")) {
                return LITERS_PER_HOUR;
            }
            if (u.equals("l/s") || u.equals("lps") || u.equals("l/sec")) {
                return LITERS_PER_SECOND;
            }
            if (u.equals("ml/h") || u.equals("ml/hr") || u.equals("mlph")) {
                return MILLILITERS_PER_HOUR;
            }
            if (u.equals("ml/s") || u.equals("mlps")) {
                return MILLILITERS_PER_SECOND;
            }
            return UNKNOWN;
        }
    }

    enum FuelVolumeUnit {
        LITERS(1.0),
        MILLILITERS(0.001),
        UNKNOWN(Double.NaN);

        private final double toLiters;

        FuelVolumeUnit(double toLiters) {
            this.toLiters = toLiters;
        }

        double toLiters(double volume) {
            return volume * toLiters;
        }

        static FuelVolumeUnit parse(String unit) {
            String u = normalize(unit);
            if (u.equals("l") || u.equals("liter") || u.equals("liters")) {
                return LITERS;
            }
            if (u.equals("ml") || u.equals("milliliter") || u.equals("milliliters")) {
                return MILLILITERS;
            }
            return UNKNOWN;
        }
    }

    private static String normalize(String unit) {
        if (unit == null) {
            return "";
        }
        return unit.toLowerCase(Locale.ROOT)
                .replace("litres", "l")
                .replace("litre", "l")
                .replace("liters", "l")
                .replace("liter", "l")
                .replace(" ", "")
                .trim();
    }
}
