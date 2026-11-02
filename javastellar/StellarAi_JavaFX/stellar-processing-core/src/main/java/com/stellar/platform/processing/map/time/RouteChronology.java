package com.stellar.platform.processing.map.time;

import com.stellar.platform.processing.map.ProjectRouteAssembler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The only GPS-route measurement order. Comparable recording starts sort first.
 * Everything else keeps its existing order in a trailing unordered group.
 * Routes are not joined.
 */
public final class RouteChronology {

    private RouteChronology() {
    }

    public record Result(
            List<ProjectRouteAssembler.PreparedMeasurement> measurements,
            String status,
            int comparableCount,
            int unorderedCount
    ) {
        public Result {
            measurements = List.copyOf(measurements == null ? List.of() : measurements);
            status = status == null ? "" : status;
        }

        public boolean fullyOrdered() {
            return comparableCount > 0 && unorderedCount == 0;
        }
    }

    public static Result order(
            List<ProjectRouteAssembler.PreparedMeasurement> prepared,
            Map<String, RecordingClock> clocks
    ) {
        List<ProjectRouteAssembler.PreparedMeasurement> rows = prepared == null ? List.of() : prepared;
        if (rows.size() <= 1) {
            return new Result(rows, "", 0, 0);
        }
        Map<String, RecordingClock> byId = clocks == null ? Map.of() : clocks;
        List<Indexed> comparable = new ArrayList<>();
        List<ProjectRouteAssembler.PreparedMeasurement> unordered = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            ProjectRouteAssembler.PreparedMeasurement row = rows.get(i);
            RecordingClock clock = clockFor(byId, row.measurementId());
            if (clock != null && clock.comparable()) {
                comparable.add(new Indexed(i, row, clock.utcStartNanos()));
            } else {
                unordered.add(row);
            }
        }
        if (comparable.isEmpty()) {
            return new Result(rows, unknownStatus(), 0, rows.size());
        }
        comparable.sort(Comparator
                .comparingLong(Indexed::utcStartNanos)
                .thenComparing(item -> item.row.measurementId(), String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(Indexed::index));
        List<ProjectRouteAssembler.PreparedMeasurement> ordered = new ArrayList<>(rows.size());
        for (Indexed item : comparable) {
            ordered.add(item.row);
        }
        ordered.addAll(unordered);
        int comparableCount = comparable.size();
        int unorderedCount = unordered.size();
        String status = unorderedCount == 0
                ? "Ordered by recording time · " + comparableCount
                + (comparableCount == 1 ? " measurement" : " measurements")
                : "Ordered by recording time · " + comparableCount + " of " + rows.size()
                + " · " + unorderedCount + " not ordered";
        return new Result(ordered, status, comparableCount, unorderedCount);
    }

    public static String unknownStatus() {
        return "Recording order unknown";
    }

    private static RecordingClock clockFor(Map<String, RecordingClock> clocks, String measurementId) {
        if (measurementId == null || measurementId.isBlank()) {
            return null;
        }
        RecordingClock direct = clocks.get(measurementId);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, RecordingClock> entry : clocks.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(measurementId)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private record Indexed(
            int index,
            ProjectRouteAssembler.PreparedMeasurement row,
            long utcStartNanos
    ) {
    }
}
