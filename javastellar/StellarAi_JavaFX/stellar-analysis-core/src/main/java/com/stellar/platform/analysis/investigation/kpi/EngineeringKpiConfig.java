package com.stellar.platform.analysis.investigation.kpi;

import java.util.ArrayList;
import java.util.List;

/**
 * Engineer-supplied KPI settings. Blank names and missing thresholds stay unset.
 * Nothing in this type is applied unless the engineer entered it.
 */
public record EngineeringKpiConfig(
        Double maxGapSeconds,
        Double movingSpeedThreshold,
        Double engineRunningThreshold,
        Double engineAboveThreshold,
        Double wotThreshold,
        String activeText,
        Double activePhysical,
        String vehicleSpeed,
        String gnssSpeed,
        String engineRpm,
        String accelerator,
        String coolant,
        String transmissionOil,
        List<String> thermocouples,
        String abs,
        String esp,
        String tcs,
        String cruise,
        String brake,
        List<String> warnings
) {
    public EngineeringKpiConfig {
        activeText = activeText == null ? "" : activeText;
        vehicleSpeed = blank(vehicleSpeed);
        gnssSpeed = blank(gnssSpeed);
        engineRpm = blank(engineRpm);
        accelerator = blank(accelerator);
        coolant = blank(coolant);
        transmissionOil = blank(transmissionOil);
        thermocouples = thermocouples == null ? List.of() : List.copyOf(thermocouples);
        abs = blank(abs);
        esp = blank(esp);
        tcs = blank(tcs);
        cruise = blank(cruise);
        brake = blank(brake);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static EngineeringKpiConfig empty() {
        return parse("");
    }

    public static EngineeringKpiConfig parse(String text) {
        Double gap = null;
        Double moving = null;
        Double rpmRun = null;
        Double rpmAbove = null;
        Double wot = null;
        String activeText = "";
        Double activePhysical = null;
        String speed = "";
        String gnss = "";
        String rpm = "";
        String accel = "";
        String coolant = "";
        String trans = "";
        List<String> thermo = new ArrayList<>();
        String abs = "";
        String esp = "";
        String tcs = "";
        String cruise = "";
        String brake = "";
        List<String> warnings = new ArrayList<>();
        if (text != null) {
            for (String raw : text.split("\\R", -1)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 1) {
                    warnings.add("Unrecognised KPI setting: " + line);
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                switch (key) {
                    case "gap" -> gap = number(value, "Maximum gap", warnings);
                    case "move" -> moving = number(value, "Moving speed threshold", warnings);
                    case "rpmRun" -> rpmRun = number(value, "Engine running threshold", warnings);
                    case "rpmAbove" -> rpmAbove = number(value, "Engine RPM threshold", warnings);
                    case "wot" -> wot = number(value, "WOT threshold", warnings);
                    case "active" -> {
                        if (value.isEmpty()) {
                            activeText = "";
                            activePhysical = null;
                        } else if (isNumber(value)) {
                            activeText = "";
                            activePhysical = number(value, "Active state", warnings);
                        } else {
                            activeText = value;
                            activePhysical = null;
                        }
                    }
                    case "speed" -> speed = value;
                    case "gnss" -> gnss = value;
                    case "rpm" -> rpm = value;
                    case "accel" -> accel = value;
                    case "coolant" -> coolant = value;
                    case "trans" -> trans = value;
                    case "thermo" -> thermo.addAll(names(value));
                    case "abs" -> abs = value;
                    case "esp" -> esp = value;
                    case "tcs" -> tcs = value;
                    case "cruise" -> cruise = value;
                    case "brake" -> brake = value;
                    default -> warnings.add("Unrecognised KPI setting: " + key);
                }
            }
        }
        return new EngineeringKpiConfig(
                gap, moving, rpmRun, rpmAbove, wot, activeText, activePhysical,
                speed, gnss, rpm, accel, coolant, trans, thermo, abs, esp, tcs, cruise, brake, warnings);
    }

    private static List<String> names(String value) {
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                out.add(name);
            }
        }
        return out;
    }

    private static boolean isNumber(String value) {
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static Double number(String value, String label, List<String> warnings) {
        if (value.isEmpty()) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) {
                warnings.add(label + " is not a finite number.");
                return null;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            warnings.add(label + " is not a number.");
            return null;
        }
    }

    private static String blank(String value) {
        return value == null ? "" : value.trim();
    }

    public String thresholdText(String label, Double value, String comparison) {
        if (value == null) {
            return "";
        }
        String shown = EngineeringKpiCalculator.format(value);
        return label + " " + comparison + " " + shown + " " + unitHint(label);
    }

    private static String unitHint(String label) {
        return switch (label) {
            case "Moving speed" -> "(signal unit)";
            case "WOT" -> "(signal unit, inclusive)";
            default -> "(signal unit)";
        };
    }

}
