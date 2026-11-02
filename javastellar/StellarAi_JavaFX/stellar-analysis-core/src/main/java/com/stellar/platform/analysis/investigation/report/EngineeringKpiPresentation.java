package com.stellar.platform.analysis.investigation.report;

import com.stellar.platform.analysis.investigation.ReportFactKind;
import com.stellar.platform.analysis.investigation.kpi.EngineeringKpiResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Display grouping for KPI rows. The calculator results on the report model are unchanged.
 */
public final class EngineeringKpiPresentation {

    public record Row(String domain, String name, String value, String unit, String detail) {
    }

    private EngineeringKpiPresentation() {
    }

    public static List<Row> calculated(List<EngineeringKpiResult> kpis) {
        List<Row> rows = new ArrayList<>();
        if (kpis == null) {
            return rows;
        }
        for (EngineeringKpiResult kpi : kpis) {
            if (kpi == null || kpi.provenance() == ReportFactKind.UNAVAILABLE) {
                continue;
            }
            rows.add(new Row(domain(kpi.id()), shortName(kpi), kpi.valueText(), kpi.unit(), detail(kpi)));
        }
        return List.copyOf(rows);
    }

    public static List<String> limitations(List<EngineeringKpiResult> kpis) {
        List<String> lines = new ArrayList<>();
        if (kpis == null) {
            return lines;
        }
        for (EngineeringKpiResult kpi : kpis) {
            if (kpi == null || kpi.provenance() != ReportFactKind.UNAVAILABLE) {
                continue;
            }
            String note = kpi.notes() == null || kpi.notes().isBlank()
                    ? "This metric was not calculated." : kpi.notes();
            String source = kpi.sourceSignal() == null || kpi.sourceSignal().isBlank()
                    ? "" : " " + kpi.sourceSignal() + ".";
            lines.add(shortName(kpi) + "." + source + " " + note);
        }
        return List.copyOf(lines);
    }

    static String domain(String id) {
        String key = id == null ? "" : id;
        if (key.startsWith("vehicle.")) {
            return "Speed and distance";
        }
        if (key.startsWith("engine.")) {
            return "Engine";
        }
        if (key.startsWith("accel.")) {
            return "Accelerator";
        }
        if (key.startsWith("thermal.")) {
            return "Temperature";
        }
        if (key.startsWith("state.")) {
            return "Vehicle state";
        }
        return "Other";
    }

    static String shortName(EngineeringKpiResult kpi) {
        String id = kpi.id() == null ? "" : kpi.id();
        return switch (id) {
            case "vehicle.speed.max" -> "Maximum speed";
            case "vehicle.speed.meanMoving" -> "Mean moving speed";
            case "vehicle.speed.movingDuration" -> "Moving duration";
            case "vehicle.distance.speed" -> "Distance from vehicle speed";
            case "vehicle.distance.gnss" -> "Distance from GNSS speed";
            case "engine.rpm.max" -> "Maximum engine speed";
            case "engine.rpm.meanRunning" -> "Mean running engine speed";
            case "engine.rpm.timeAbove" -> "Time above engine-speed threshold";
            case "accel.pedal.max" -> "Maximum accelerator";
            case "accel.wot.time" -> "Time at wide-open accelerator";
            case "thermal.coolant.min" -> "Minimum coolant";
            case "thermal.coolant.max" -> "Maximum coolant";
            case "thermal.trans.max" -> "Maximum transmission temperature";
            case "thermal.tc.min" -> "Minimum";
            case "thermal.tc.max" -> "Maximum";
            case "state.abs.duration" -> "ABS active duration";
            case "state.abs.count" -> "ABS applications";
            case "state.esp.duration" -> "ESP active duration";
            case "state.esp.count" -> "ESP applications";
            case "state.tcs.duration" -> "TCS active duration";
            case "state.tcs.count" -> "TCS applications";
            case "state.cruise.duration" -> "Cruise active duration";
            case "state.brake.duration" -> "Brake active duration";
            case "state.brake.count" -> "Brake applications";
            default -> kpi.name() == null || kpi.name().isBlank() ? id : kpi.name();
        };
    }

    private static String detail(EngineeringKpiResult kpi) {
        StringBuilder text = new StringBuilder();
        if (kpi.sourceSignal() != null && !kpi.sourceSignal().isBlank()) {
            text.append(kpi.sourceSignal());
        }
        if (kpi.coverage() != null && !kpi.coverage().isBlank()) {
            append(text, kpi.coverage());
        }
        if (kpi.threshold() != null && !kpi.threshold().isBlank()) {
            append(text, kpi.threshold());
        }
        if (kpi.method() != null && !kpi.method().isBlank()) {
            append(text, kpi.method());
        }
        return text.toString();
    }

    private static void append(StringBuilder text, String value) {
        if (!text.isEmpty()) {
            text.append(" · ");
        }
        text.append(value);
    }
}
