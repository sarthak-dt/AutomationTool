package com.stellar.platform.analysis.investigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks an engineer event against the measurement it names.
 * A failed check is reported. It does not invent a sample, a coordinate, or a pass/fail.
 */
public final class EngineerEventValidation {

    public static final String SNAPSHOT_OK = "OK";
    public static final String SNAPSHOT_MISSING = "MISSING";
    public static final String SNAPSHOT_STALE = "STALE";

    private EngineerEventValidation() {
    }

    public static List<String> blocking(
            EngineerEventDefinition event,
            Long rangeStartNanos,
            Long rangeEndNanos,
            Set<String> signalsOnSource
    ) {
        List<String> issues = new ArrayList<>();
        if (event == null || event.name().isBlank()) {
            issues.add("Enter an event name.");
            return issues;
        }
        if (event.window() && event.endNanos() < event.startNanos()) {
            issues.add("The end timestamp is before the start timestamp.");
        }
        if (rangeStartNanos != null && event.startNanos() < rangeStartNanos) {
            issues.add("The start timestamp is before the selected measurement.");
        }
        if (rangeEndNanos != null && event.startNanos() > rangeEndNanos) {
            issues.add("The start timestamp is after the selected measurement.");
        }
        if (event.window() && rangeEndNanos != null && event.endNanos() > rangeEndNanos) {
            issues.add("The end timestamp is after the selected measurement.");
        }
        if (event.window() && rangeStartNanos != null && event.endNanos() < rangeStartNanos) {
            issues.add("The end timestamp is before the selected measurement.");
        }
        if (signalsOnSource != null) {
            for (String signal : event.signals()) {
                String name = signalName(signal);
                if (!signalsOnSource.contains(name) && !signalsOnSource.contains(signal)) {
                    issues.add(name + " is not a signal on the selected measurement.");
                }
            }
        }
        return List.copyOf(issues);
    }

    public static List<String> warnings(
            EngineerEventDefinition event,
            Map<String, String> snapshotState,
            boolean clocksComparable
    ) {
        List<String> issues = new ArrayList<>();
        if (event == null) {
            return issues;
        }
        if (!clocksComparable) {
            issues.add("Another file is not placed on this event's clock. Chronological order was not assumed.");
        }
        if (snapshotState != null) {
            for (String id : event.snapshotIds()) {
                String state = snapshotState.getOrDefault(id, SNAPSHOT_MISSING);
                if (SNAPSHOT_STALE.equals(state)) {
                    issues.add("Graph snapshot " + id + " is stale and was not regenerated.");
                } else if (!SNAPSHOT_OK.equals(state)) {
                    issues.add("Graph snapshot " + id + " is missing and was not regenerated.");
                }
            }
        }
        if (!event.gpsAttached()) {
            issues.add("GPS evidence was not attached. No coordinate was invented.");
        } else if (event.gnssStatement().isBlank()
                || event.gnssStatement().contains("no GNSS")
                || event.gnssStatement().contains("No GNSS")
                || event.gnssStatement().contains("not marked")) {
            issues.add("No GNSS sample on this recording matches the event. No coordinate was invented.");
        }
        return List.copyOf(issues);
    }

    /** Display name before a unit suffix such as {@code CH01 (°C)}. */
    public static String signalName(String signal) {
        if (signal == null) {
            return "";
        }
        int unit = signal.indexOf(" (");
        return (unit < 0 ? signal : signal.substring(0, unit)).strip();
    }
}
