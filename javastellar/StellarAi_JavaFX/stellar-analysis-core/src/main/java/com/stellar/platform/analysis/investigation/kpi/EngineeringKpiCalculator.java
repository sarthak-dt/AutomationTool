package com.stellar.platform.analysis.investigation.kpi;

import com.stellar.platform.analysis.investigation.ReportFactKind;
import com.stellar.platform.analysis.investigation.kpi.EngineeringKpiSeries.Availability;
import com.stellar.platform.analysis.investigation.kpi.EngineeringKpiSeries.Sample;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Engineering KPI pack, method {@code v2-hold-1}.
 *
 * <p>A valid sample holds until the next sample when the gap is within the configured maximum.
 * That is a zero-order hold, not interpolation. The hold stops at the next sample. A gap longer
 * than the configured maximum is excluded. An invalid sample does not start a hold and is not
 * treated as zero. A duplicate timestamp keeps the last value and adds no time. Files are not
 * placed on one timeline and are not summed together.
 */
public final class EngineeringKpiCalculator {

    public static final String METHOD = "v2-hold-1";

    public static final String SPEED = "speed";
    public static final String GNSS = "gnss";
    public static final String RPM = "rpm";
    public static final String ACCEL = "accel";
    public static final String COOLANT = "coolant";
    public static final String TRANS = "trans";
    public static final String THERMO = "thermo";
    public static final String ABS = "abs";
    public static final String ESP = "esp";
    public static final String TCS = "tcs";
    public static final String CRUISE = "cruise";
    public static final String BRAKE = "brake";

    private EngineeringKpiCalculator() {
    }

    public static List<EngineeringKpiResult> calculate(EngineeringKpiConfig config, List<EngineeringKpiSeries> series) {
        EngineeringKpiConfig cfg = config == null ? EngineeringKpiConfig.empty() : config;
        List<EngineeringKpiSeries> rows = series == null ? List.of() : series;
        List<EngineeringKpiResult> out = new ArrayList<>();
        for (String warning : cfg.warnings()) {
            out.add(unavailable("config.warning", "KPI configuration", "", "", warning));
        }
        speed(out, cfg, rows);
        engine(out, cfg, rows);
        accelerator(out, cfg, rows);
        thermal(out, cfg, rows);
        state(out, cfg, rows, ABS, "ABS active duration", "ABS active count", true);
        state(out, cfg, rows, ESP, "ESP active duration", "ESP active count", true);
        state(out, cfg, rows, TCS, "TCS active duration", "TCS active count", true);
        state(out, cfg, rows, CRUISE, "Cruise control active duration", "", false);
        state(out, cfg, rows, BRAKE, "Brake active duration", "Brake application count", true);
        return List.copyOf(out);
    }

    private static void speed(List<EngineeringKpiResult> out, EngineeringKpiConfig cfg, List<EngineeringKpiSeries> series) {
        List<EngineeringKpiSeries> speed = resolve(series, SPEED, cfg.vehicleSpeed());
        emitExtremum(out, cfg, speed, "vehicle.speed.max", "Maximum vehicle speed", true);
        emitMoving(out, cfg, speed);
        emitDistance(out, cfg, speed, "vehicle.distance.speed", "Distance from speed");
        List<EngineeringKpiSeries> gnss = resolve(series, GNSS, cfg.gnssSpeed());
        emitDistance(out, cfg, gnss, "vehicle.distance.gnss", "Distance from GNSS speed");
    }

    private static void engine(List<EngineeringKpiResult> out, EngineeringKpiConfig cfg, List<EngineeringKpiSeries> series) {
        List<EngineeringKpiSeries> rpm = resolve(series, RPM, cfg.engineRpm());
        emitExtremum(out, cfg, rpm, "engine.rpm.max", "Maximum engine RPM", true);
        for (EngineeringKpiSeries row : rpm) {
            if (row.availability() != Availability.PRESENT) {
                out.add(blocked(row, "engine.rpm.meanRunning", "Mean RPM while running"));
                out.add(blocked(row, "engine.rpm.timeAbove", "Time above RPM threshold"));
                continue;
            }
            Hold hold = hold(row, cfg);
            if (cfg.engineRunningThreshold() == null) {
                out.add(unavailable("engine.rpm.meanRunning", "Mean RPM while running",
                        row, "Engine running threshold is not configured."));
            } else if (hold.runningNanos == 0L) {
                out.add(rule(row, "engine.rpm.meanRunning", "Mean RPM while running",
                        null, "", hold, "RPM > " + format(cfg.engineRunningThreshold()),
                        "No held interval was above the running threshold."));
            } else {
                double mean = hold.runningWeighted / hold.runningNanos;
                out.add(rule(row, "engine.rpm.meanRunning", "Mean RPM while running",
                        mean, row.unit(), hold, "RPM > " + format(cfg.engineRunningThreshold()),
                        holdNote(cfg, hold)));
            }
            if (cfg.engineAboveThreshold() == null) {
                out.add(unavailable("engine.rpm.timeAbove", "Time above RPM threshold",
                        row, "RPM threshold is not configured."));
            } else if (!gapReady(cfg)) {
                out.add(unavailable("engine.rpm.timeAbove", "Time above RPM threshold",
                        row, "Maximum gap is not configured."));
            } else {
                out.add(rule(row, "engine.rpm.timeAbove", "Time above RPM threshold",
                        seconds(hold.aboveNanos), "s", hold,
                        "RPM > " + format(cfg.engineAboveThreshold()), holdNote(cfg, hold)));
            }
        }
    }

    private static void accelerator(List<EngineeringKpiResult> out, EngineeringKpiConfig cfg, List<EngineeringKpiSeries> series) {
        List<EngineeringKpiSeries> pedal = resolve(series, ACCEL, cfg.accelerator());
        emitExtremum(out, cfg, pedal, "accel.pedal.max", "Maximum pedal position", true);
        for (EngineeringKpiSeries row : pedal) {
            if (row.availability() != Availability.PRESENT) {
                out.add(blocked(row, "accel.wot.time", "Time above WOT threshold"));
                continue;
            }
            if (cfg.wotThreshold() == null) {
                out.add(unavailable("accel.wot.time", "Time above WOT threshold",
                        row, "WOT threshold is not configured."));
                continue;
            }
            if (!gapReady(cfg)) {
                out.add(unavailable("accel.wot.time", "Time above WOT threshold",
                        row, "Maximum gap is not configured."));
                continue;
            }
            Hold hold = hold(row, cfg);
            out.add(rule(row, "accel.wot.time", "Time above WOT threshold",
                    seconds(hold.wotNanos), "s", hold,
                    "pedal >= " + format(cfg.wotThreshold()), holdNote(cfg, hold)));
        }
    }

    private static void thermal(List<EngineeringKpiResult> out, EngineeringKpiConfig cfg, List<EngineeringKpiSeries> series) {
        emitExtremum(out, cfg, resolve(series, COOLANT, cfg.coolant()),
                "thermal.coolant.min", "Minimum coolant temperature", false);
        emitExtremum(out, cfg, resolve(series, COOLANT, cfg.coolant()),
                "thermal.coolant.max", "Maximum coolant temperature", true);
        emitExtremum(out, cfg, resolve(series, TRANS, cfg.transmissionOil()),
                "thermal.trans.max", "Maximum transmission oil temperature", true);
        for (String name : cfg.thermocouples()) {
            List<EngineeringKpiSeries> rows = resolve(series, THERMO, name);
            emitExtremum(out, cfg, rows, "thermal.tc.min", "Minimum " + name, false);
            emitExtremum(out, cfg, rows, "thermal.tc.max", "Maximum " + name, true);
        }
    }

    private static void state(
            List<EngineeringKpiResult> out,
            EngineeringKpiConfig cfg,
            List<EngineeringKpiSeries> series,
            String role,
            String durationName,
            String countName,
            boolean includeCount
    ) {
        String configured = switch (role) {
            case ABS -> cfg.abs();
            case ESP -> cfg.esp();
            case TCS -> cfg.tcs();
            case BRAKE -> cfg.brake();
            default -> cfg.cruise();
        };
        for (EngineeringKpiSeries row : resolve(series, role, configured)) {
            String durationId = "state." + role + ".duration";
            String countId = "state." + role + ".count";
            if (row.availability() != Availability.PRESENT) {
                out.add(blocked(row, durationId, durationName));
                if (includeCount) {
                    out.add(blocked(row, countId, countName));
                }
                continue;
            }
            if (cfg.activeText().isBlank() && cfg.activePhysical() == null) {
                out.add(unavailable(durationId, durationName, row, "Active state is not configured."));
                if (includeCount) {
                    out.add(unavailable(countId, countName, row, "Active state is not configured."));
                }
                continue;
            }
            if (!gapReady(cfg)) {
                out.add(unavailable(durationId, durationName, row, "Maximum gap is not configured."));
            } else {
                Hold hold = holdState(row, cfg);
                String rule = cfg.activeText().isBlank()
                        ? "physical = " + format(cfg.activePhysical())
                        : "text = " + cfg.activeText();
                out.add(rule(row, durationId, durationName, seconds(hold.activeNanos), "s", hold, rule,
                        holdNote(cfg, hold) + " A gap or invalid sample ends the active interval."));
                if (includeCount) {
                    out.add(rule(row, countId, countName, (double) hold.rising, "", hold, rule,
                            "Count is rising edges on original samples. It is not a 100 ms sample count. "
                                    + holdNote(cfg, hold)));
                }
            }
            if (includeCount && !gapReady(cfg)) {
                out.add(unavailable(countId, countName, row, "Maximum gap is not configured."));
            }
        }
    }

    private static void emitExtremum(
            List<EngineeringKpiResult> out,
            EngineeringKpiConfig cfg,
            List<EngineeringKpiSeries> rows,
            String id,
            String name,
            boolean maximum
    ) {
        for (EngineeringKpiSeries row : rows) {
            if (row.availability() != Availability.PRESENT) {
                out.add(blocked(row, id, name));
                continue;
            }
            Hold hold = hold(row, cfg);
            if (!hold.any) {
                out.add(unavailable(id, name, row, "The signal has no valid samples. Missing values were not treated as zero."));
                continue;
            }
            double value = maximum ? hold.max : hold.min;
            out.add(calculated(row, id, name, value, row.unit(), hold, extremumNote(hold)));
        }
    }

    private static void emitMoving(List<EngineeringKpiResult> out, EngineeringKpiConfig cfg, List<EngineeringKpiSeries> rows) {
        for (EngineeringKpiSeries row : rows) {
            if (row.availability() != Availability.PRESENT) {
                out.add(blocked(row, "vehicle.speed.meanMoving", "Mean moving speed"));
                out.add(blocked(row, "vehicle.speed.movingDuration", "Moving duration"));
                continue;
            }
            if (cfg.movingSpeedThreshold() == null) {
                out.add(unavailable("vehicle.speed.meanMoving", "Mean moving speed", row,
                        "Moving speed threshold is not configured."));
                out.add(unavailable("vehicle.speed.movingDuration", "Moving duration", row,
                        "Moving speed threshold is not configured."));
                continue;
            }
            if (!gapReady(cfg)) {
                out.add(unavailable("vehicle.speed.meanMoving", "Mean moving speed", row,
                        "Maximum gap is not configured."));
                out.add(unavailable("vehicle.speed.movingDuration", "Moving duration", row,
                        "Maximum gap is not configured."));
                continue;
            }
            Hold hold = hold(row, cfg);
            String rule = "speed > " + format(cfg.movingSpeedThreshold()) + " (signal unit)";
            out.add(rule(row, "vehicle.speed.movingDuration", "Moving duration",
                    seconds(hold.movingNanos), "s", hold, rule, holdNote(cfg, hold)));
            if (hold.movingNanos == 0L) {
                out.add(unavailable("vehicle.speed.meanMoving", "Mean moving speed", row,
                        "No held interval was above the moving threshold. " + rule));
            } else {
                out.add(rule(row, "vehicle.speed.meanMoving", "Mean moving speed",
                        hold.movingWeighted / hold.movingNanos, row.unit(), hold, rule, holdNote(cfg, hold)));
            }
        }
    }

    private static void emitDistance(
            List<EngineeringKpiResult> out,
            EngineeringKpiConfig cfg,
            List<EngineeringKpiSeries> rows,
            String id,
            String name
    ) {
        for (EngineeringKpiSeries row : rows) {
            if (row.availability() != Availability.PRESENT) {
                out.add(blocked(row, id, name));
                continue;
            }
            SpeedUnit unit = speedUnit(row.unit());
            if (unit == null) {
                out.add(unavailable(id, name, row,
                        "Unit '" + row.unit() + "' is not a supported speed unit. Distance was not calculated."));
                continue;
            }
            if (!gapReady(cfg)) {
                out.add(unavailable(id, name, row, "Maximum gap is not configured."));
                continue;
            }
            Hold hold = hold(row, cfg);
            double km = hold.meters / 1000d;
            out.add(calculated(row, id, name, km, "km", hold,
                    "Rectangular hold of speed. No interpolation. " + holdNote(cfg, hold)
                            + " Coordinates were not converted into a route."));
        }
    }

    private static List<EngineeringKpiSeries> resolve(List<EngineeringKpiSeries> series, String role, String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        List<EngineeringKpiSeries> found = new ArrayList<>();
        for (EngineeringKpiSeries row : series) {
            if (role.equals(row.role()) && name.equals(row.signalName())) {
                found.add(row);
            }
        }
        if (found.isEmpty()) {
            return List.of(EngineeringKpiSeries.missing(role, name, Availability.NOT_FOUND));
        }
        return found;
    }

    private static Hold hold(EngineeringKpiSeries row, EngineeringKpiConfig cfg) {
        List<Sample> points = ordered(row.samples());
        Hold hold = new Hold();
        hold.unsorted = unsorted(row.samples());
        if (points.isEmpty()) {
            return hold;
        }
        hold.first = points.getFirst().timestampNanos();
        hold.last = points.getLast().timestampNanos();
        boolean gapConfigured = gapReady(cfg);
        long maxGap = gapConfigured ? Math.round(cfg.maxGapSeconds() * 1_000_000_000d) : 0L;
        Double moving = cfg.movingSpeedThreshold();
        Double running = cfg.engineRunningThreshold();
        Double above = cfg.engineAboveThreshold();
        Double wot = cfg.wotThreshold();
        SpeedUnit unit = speedUnit(row.unit());
        for (int i = 0; i < points.size(); i++) {
            Sample sample = points.get(i);
            if (!usable(sample)) {
                continue;
            }
            hold.any = true;
            hold.valid++;
            hold.min = Math.min(hold.min, sample.value());
            hold.max = Math.max(hold.max, sample.value());
            if (i + 1 >= points.size()) {
                continue;
            }
            long dt = points.get(i + 1).timestampNanos() - sample.timestampNanos();
            if (dt <= 0L || !gapConfigured || dt > maxGap) {
                if (dt > maxGap && gapConfigured) {
                    hold.gaps++;
                }
                continue;
            }
            hold.covered += dt;
            if (moving != null && sample.value() > moving) {
                hold.movingNanos += dt;
                hold.movingWeighted += sample.value() * dt;
            }
            if (running != null && sample.value() > running) {
                hold.runningNanos += dt;
                hold.runningWeighted += sample.value() * dt;
            }
            if (above != null && sample.value() > above) {
                hold.aboveNanos += dt;
            }
            if (wot != null && sample.value() >= wot) {
                hold.wotNanos += dt;
            }
            if (unit != null) {
                hold.meters += toMetersPerSecond(sample.value(), unit) * (dt / 1_000_000_000d);
            }
        }
        for (int i = 0; i + 1 < points.size(); i++) {
            if (!usable(points.get(i)) && points.get(i + 1).timestampNanos() > points.get(i).timestampNanos()) {
                hold.invalidBreaks++;
            }
        }
        return hold;
    }

    private static Hold holdState(EngineeringKpiSeries row, EngineeringKpiConfig cfg) {
        List<Sample> points = ordered(row.samples());
        Hold hold = new Hold();
        hold.unsorted = unsorted(row.samples());
        if (!points.isEmpty()) {
            hold.first = points.getFirst().timestampNanos();
            hold.last = points.getLast().timestampNanos();
        }
        boolean gapConfigured = gapReady(cfg);
        long maxGap = gapConfigured ? Math.round(cfg.maxGapSeconds() * 1_000_000_000d) : 0L;
        boolean previousHeld = false;
        for (int i = 0; i < points.size(); i++) {
            Sample sample = points.get(i);
            boolean on = active(sample, cfg);
            if (on && !previousHeld) {
                hold.rising++;
            }
            boolean held = false;
            if (i + 1 < points.size()) {
                long dt = points.get(i + 1).timestampNanos() - sample.timestampNanos();
                if (dt > maxGap && gapConfigured && dt > 0L) {
                    hold.gaps++;
                }
                held = on && gapConfigured && dt > 0L && dt <= maxGap;
                if (held) {
                    hold.activeNanos += dt;
                    hold.covered += dt;
                }
            }
            previousHeld = held;
            if (usable(sample) || on) {
                hold.any = true;
                hold.valid++;
            }
        }
        return hold;
    }

    private static boolean active(Sample sample, EngineeringKpiConfig cfg) {
        if (!cfg.activeText().isBlank()) {
            String text = sample.text() == null ? "" : sample.text().trim();
            return cfg.activeText().equals(text);
        }
        return cfg.activePhysical() != null && usable(sample)
                && Double.compare(sample.value(), cfg.activePhysical()) == 0;
    }

    private static boolean usable(Sample sample) {
        return sample.valid() && Double.isFinite(sample.value());
    }

    private static List<Sample> ordered(List<Sample> samples) {
        List<Sample> copy = new ArrayList<>(samples);
        copy.sort(Comparator.comparingLong(Sample::timestampNanos));
        List<Sample> collapsed = new ArrayList<>();
        for (Sample sample : copy) {
            if (!collapsed.isEmpty()
                    && collapsed.getLast().timestampNanos() == sample.timestampNanos()) {
                collapsed.set(collapsed.size() - 1, sample);
            } else {
                collapsed.add(sample);
            }
        }
        return collapsed;
    }

    private static boolean unsorted(List<Sample> samples) {
        for (int i = 1; i < samples.size(); i++) {
            if (samples.get(i).timestampNanos() < samples.get(i - 1).timestampNanos()) {
                return true;
            }
        }
        return false;
    }

    private static boolean gapReady(EngineeringKpiConfig cfg) {
        return cfg.maxGapSeconds() != null && Double.isFinite(cfg.maxGapSeconds()) && cfg.maxGapSeconds() >= 0d;
    }

    static String format(double value) {
        if (!Double.isFinite(value)) {
            return "";
        }
        BigDecimal decimal = new BigDecimal(Double.toString(value)).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
        return decimal.toPlainString();
    }

    private static double seconds(long nanos) {
        return nanos / 1_000_000_000d;
    }

    private static EngineeringKpiResult blocked(EngineeringKpiSeries row, String id, String name) {
        String note = switch (row.availability()) {
            case NOT_CONFIGURED -> "Source signal is not configured.";
            case NOT_FOUND -> "Source signal was not found: " + row.signalName() + ". Missing data was not treated as zero.";
            case SAMPLES_NOT_LOADED -> "Samples are not loaded, so this KPI was not calculated.";
            case PRESENT -> "The signal has no samples.";
        };
        return unavailable(id, name, row.signalName(), row.sourceFile(), note);
    }

    private static EngineeringKpiResult unavailable(String id, String name, EngineeringKpiSeries row, String note) {
        return unavailable(id, name, row.signalName(), row.sourceFile(), note);
    }

    private static EngineeringKpiResult unavailable(String id, String name, String signal, String file, String note) {
        return new EngineeringKpiResult(
                id, name, "Unavailable", null, "", signal, file, "", METHOD, "", "",
                ReportFactKind.UNAVAILABLE, note);
    }

    private static EngineeringKpiResult calculated(
            EngineeringKpiSeries row, String id, String name, double value, String unit, Hold hold, String note
    ) {
        return result(row, id, name, value, unit, hold, "", ReportFactKind.CALCULATED, note);
    }

    private static EngineeringKpiResult rule(
            EngineeringKpiSeries row, String id, String name, Double value, String unit, Hold hold,
            String threshold, String note
    ) {
        return result(row, id, name, value, unit, hold, threshold, ReportFactKind.RULE_RESULT, note);
    }

    private static EngineeringKpiResult result(
            EngineeringKpiSeries row, String id, String name, Double value, String unit, Hold hold,
            String threshold, ReportFactKind kind, String note
    ) {
        String shown = value == null ? "Unavailable" : format(value);
        ReportFactKind provenance = value == null ? ReportFactKind.UNAVAILABLE : kind;
        return new EngineeringKpiResult(
                id, name, shown, value, value == null ? "" : unit,
                row.signalName(), row.sourceFile(), interval(hold), METHOD, coverage(hold),
                threshold, provenance, note);
    }

    private static String interval(Hold hold) {
        if (hold.first == 0L && hold.last == 0L && !hold.any) {
            return "";
        }
        return String.format(Locale.ROOT, "%.3f s – %.3f s, measurement-relative",
                hold.first / 1_000_000_000d, hold.last / 1_000_000_000d);
    }

    private static String coverage(Hold hold) {
        long span = hold.last - hold.first;
        if (span <= 0L) {
            return hold.any ? "single sample" : "";
        }
        double pct = 100d * hold.covered / span;
        return String.format(Locale.ROOT, "%.1f%% (%.3f s held of %.3f s)",
                pct, hold.covered / 1_000_000_000d, span / 1_000_000_000d);
    }

    private static String holdNote(EngineeringKpiConfig cfg, Hold hold) {
        String gap = cfg.maxGapSeconds() == null ? "no maximum gap" : format(cfg.maxGapSeconds()) + " s maximum gap";
        String unsorted = hold.unsorted
                ? " Samples were ordered by their own timestamps. No clock was invented."
                : "";
        return "Zero-order hold, " + gap + ". Gaps excluded: " + hold.gaps
                + ". Invalid breaks: " + hold.invalidBreaks + ". No interpolation." + unsorted;
    }

    private static String extremumNote(Hold hold) {
        String unsorted = hold.unsorted
                ? " Samples were ordered by their own timestamps. No clock was invented."
                : "";
        return "Minimum and maximum use valid samples only. Missing values were not treated as zero." + unsorted;
    }

    static SpeedUnit speedUnit(String unit) {
        if (unit == null) {
            return null;
        }
        String normalized = unit.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        return switch (normalized) {
            case "km/h", "kph", "kmh" -> SpeedUnit.KMH;
            case "m/s", "mps" -> SpeedUnit.MS;
            case "mph" -> SpeedUnit.MPH;
            default -> null;
        };
    }

    private static double toMetersPerSecond(double value, SpeedUnit unit) {
        return switch (unit) {
            case KMH -> value / 3.6d;
            case MS -> value;
            case MPH -> value * 0.44704d;
        };
    }

    private enum SpeedUnit {
        KMH, MS, MPH
    }

    private static final class Hold {
        private boolean any;
        private boolean unsorted;
        private int valid;
        private int gaps;
        private int invalidBreaks;
        private int rising;
        private long first;
        private long last;
        private long covered;
        private long movingNanos;
        private long runningNanos;
        private long aboveNanos;
        private long wotNanos;
        private long activeNanos;
        private double movingWeighted;
        private double runningWeighted;
        private double meters;
        private double min = Double.POSITIVE_INFINITY;
        private double max = Double.NEGATIVE_INFINITY;
    }
}
