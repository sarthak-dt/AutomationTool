package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineerEventDefinition;
import com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores an engineer event in the existing bookmark observation column.
 * The first line remains the title so older readers still show the event name.
 */
public final class EngineerEventDocuments {

    public static final String SENTINEL = "\u001eSTELLAR_EVENT_V1";

    private EngineerEventDocuments() {
    }

    public static String observation(EngineerEventDefinition event) {
        StringBuilder text = new StringBuilder();
        text.append(event.name().isBlank() ? "Event" : event.name()).append('\n');
        if (!event.description().isBlank()) {
            text.append(event.description().replace(SENTINEL, " ")).append('\n');
        }
        text.append(SENTINEL).append('\n');
        line(text, "end", event.endNanos() == null ? "" : Long.toString(event.endNanos()));
        line(text, "signals", join(event.signals()));
        line(text, "expected", oneLine(event.expected()));
        line(text, "measured", oneLine(event.measured()));
        line(text, "snapshots", join(event.snapshotIds()));
        line(text, "gps", event.gpsAttached() ? "yes" : "no");
        line(text, "confirmation", oneLine(event.confirmation()));
        line(text, "sourceFile", oneLine(event.sourceFile()));
        line(text, "gnss", oneLine(event.gnssStatement()));
        line(text, "limitations", oneLine(event.limitations()));
        line(text, "snapshotNote", oneLine(event.snapshotNote()));
        return text.toString();
    }

    /**
     * Graph Add Event document. The end time and signal identities live in the existing
     * observation block. A null end is a point. Display names stay in {@code signals}.
     */
    public static String graphObservation(
            String title,
            String note,
            Long endNanos,
            List<String> signalNames,
            List<String> signalKeys,
            String sourceFile
    ) {
        return graphObservation(title, note, endNanos, signalNames, signalKeys, sourceFile, "", null);
    }

    /**
     * Point events store the plotted sample in the existing measured line and a numeric
     * {@code sampleNumber} line. A null number leaves older documents unchanged.
     */
    public static String graphObservation(
            String title,
            String note,
            Long endNanos,
            List<String> signalNames,
            List<String> signalKeys,
            String sourceFile,
            String sampleText,
            Double sampleNumber
    ) {
        EngineerEventDefinition event = new EngineerEventDefinition(
                "", title == null ? "" : title, "", "", sourceFile, 0L, endNanos,
                signalNames, note == null ? "" : note, "", sampleText == null ? "" : sampleText,
                List.of(), false, "Draft", "", "", "");
        String text = observation(event) + "signalKeys=" + join(signalKeys) + "\n";
        if (sampleNumber != null && Double.isFinite(sampleNumber)) {
            text = text + "sampleNumber=" + Double.toString(sampleNumber) + "\n";
        }
        return text;
    }

    /** Display text of the plotted sample, or blank for a window or an older bookmark. */
    public static String sampleText(EngineeringBookmarkMetadata bookmark) {
        EngineerEventDefinition event = decode(bookmark);
        return event == null || event.measured() == null ? "" : event.measured();
    }

    /** Original plotted sample, when the bookmark recorded one. */
    public static Double sampleNumber(EngineeringBookmarkMetadata bookmark) {
        String raw = observationValue(bookmark, "sampleNumber");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw);
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Encoded catalog keys saved with a graph event. Empty for older bookmarks. */
    public static List<String> signalKeys(EngineeringBookmarkMetadata bookmark) {
        EngineerEventDefinition event = decode(bookmark);
        if (event == null || bookmark.observation() == null) {
            return List.of();
        }
        int at = bookmark.observation().indexOf(SENTINEL);
        if (at < 0) {
            return List.of();
        }
        return split(value(bookmark.observation().substring(at + SENTINEL.length()), "signalKeys"));
    }

    public static EngineerEventDefinition decode(EngineeringBookmarkMetadata bookmark) {
        if (bookmark == null || bookmark.observation() == null
                || !bookmark.observation().contains(SENTINEL)) {
            return null;
        }
        String observation = bookmark.observation();
        int at = observation.indexOf(SENTINEL);
        String body = observation.substring(at + SENTINEL.length());
        String end = value(body, "end");
        Long endNanos = end.isBlank() ? null : Long.valueOf(end);
        return new EngineerEventDefinition(
                bookmark.bookmarkId(),
                InvestigationEventCodec.titleOf(observation),
                bookmark.eventType(),
                bookmark.sourceId(),
                value(body, "sourceFile"),
                bookmark.timestampNanos(),
                endNanos,
                split(value(body, "signals")),
                InvestigationEventCodec.noteOf(observation),
                value(body, "expected"),
                value(body, "measured"),
                split(value(body, "snapshots")),
                "yes".equals(value(body, "gps")),
                value(body, "confirmation"),
                value(body, "gnss"),
                value(body, "limitations"),
                value(body, "snapshotNote"));
    }

    /**
     * Stores snapshot evidence ids on the existing event. Image bytes stay in the snapshot file.
     * A plain older bookmark keeps its title and note, and gains the same sentinel block.
     */
    public static String withSnapshots(String observation, List<String> snapshotIds, String note) {
        String ids = join(snapshotIds);
        String storedNote = oneLine(note);
        if (observation == null || !observation.contains(SENTINEL)) {
            String base = observation == null || observation.isBlank() ? "Event\n" : observation;
            if (!base.endsWith("\n")) {
                base = base + "\n";
            }
            return base + SENTINEL + "\n"
                    + "snapshots=" + ids + "\n"
                    + "snapshotNote=" + storedNote + "\n";
        }
        String updated = replaceLine(observation, "snapshots", ids);
        return replaceLine(updated, "snapshotNote", storedNote);
    }

    /** GPS facts stay in the existing observation. A blank status clears a previous match. */
    public static String withGps(
            String observation,
            boolean attached,
            String statement,
            String status,
            String sampleNanos,
            String latitude,
            String longitude,
            String deltaNanos,
            String policy,
            String sourceId,
            String sourceFile,
            String sampleIndex,
            String kind,
            String latitudeChannel,
            String longitudeChannel
    ) {
        String text = observation == null || observation.isBlank() ? "Event\n" : observation;
        if (!text.contains(SENTINEL)) {
            if (!text.endsWith("\n")) {
                text = text + "\n";
            }
            text = text + SENTINEL + "\n";
        }
        text = replaceLine(text, "gps", attached ? "yes" : "no");
        text = replaceLine(text, "gnss", oneLine(statement));
        text = replaceLine(text, "gnssStatus", oneLine(status));
        text = replaceLine(text, "gnssSampleNanos", oneLine(sampleNanos));
        text = replaceLine(text, "gnssLat", oneLine(latitude));
        text = replaceLine(text, "gnssLon", oneLine(longitude));
        text = replaceLine(text, "gnssDeltaNanos", oneLine(deltaNanos));
        text = replaceLine(text, "gnssPolicy", oneLine(policy));
        text = replaceLine(text, "gnssSourceId", oneLine(sourceId));
        text = replaceLine(text, "gnssSourceFile", oneLine(sourceFile));
        text = replaceLine(text, "gnssSampleIndex", oneLine(sampleIndex));
        text = replaceLine(text, "gnssKind", oneLine(kind));
        text = replaceLine(text, "gnssLatChannel", oneLine(latitudeChannel));
        return replaceLine(text, "gnssLonChannel", oneLine(longitudeChannel));
    }

    public static String observationValue(EngineeringBookmarkMetadata bookmark, String key) {
        if (bookmark == null || bookmark.observation() == null || key == null) {
            return "";
        }
        int at = bookmark.observation().indexOf(SENTINEL);
        if (at < 0) {
            return "";
        }
        return value(bookmark.observation().substring(at + SENTINEL.length()), key);
    }

    /** Keeps the structured block when the graph editor changes only the title and note. */
    public static String withTitleAndNote(String observation, String title, String note) {
        String encoded = InvestigationEventCodec.encode(title, note);
        if (observation == null || !observation.contains(SENTINEL)) {
            return encoded;
        }
        return encoded + "\n" + observation.substring(observation.indexOf(SENTINEL));
    }

    public static boolean skipMapPin(EngineeringBookmarkMetadata bookmark) {
        if (GraphEventGps.MATCHED.equals(observationValue(bookmark, "gnssStatus"))) {
            return false;
        }
        EngineerEventDefinition event = decode(bookmark);
        if (event == null) {
            return false;
        }
        String status = observationValue(bookmark, "gnssStatus");
        if (!status.isBlank()) {
            return true;
        }
        if (event.window()) {
            return true;
        }
        String gnss = event.gnssStatement();
        return !event.gpsAttached()
                || gnss.contains("No GNSS")
                || gnss.contains("no GNSS")
                || gnss.contains("not attached")
                || gnss.contains("not marked")
                || gnss.contains("No coordinate");
    }

    public static Long endNanos(EngineeringBookmarkMetadata bookmark) {
        EngineerEventDefinition event = decode(bookmark);
        return event == null ? null : event.endNanos();
    }

    /**
     * A decimal is measurement-relative seconds. An integer is the original nanosecond count.
     */
    public static long parseTime(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Enter a timestamp.");
        }
        String value = text.trim();
        if (value.endsWith("s") || value.endsWith("S")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        if (value.indexOf('.') >= 0 || value.indexOf('e') >= 0 || value.indexOf('E') >= 0) {
            return Math.round(Double.parseDouble(value) * 1_000_000_000d);
        }
        return Long.parseLong(value);
    }

    private static void line(StringBuilder text, String key, String value) {
        text.append(key).append('=').append(value == null ? "" : value).append('\n');
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').replace(SENTINEL, " ");
    }

    private static String join(List<String> values) {
        StringBuilder text = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append('|');
            }
            text.append(value.replace('|', '/').replace('\n', ' '));
        }
        return text.toString();
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : value.split("\\|")) {
            if (!part.isBlank()) {
                out.add(part);
            }
        }
        return List.copyOf(out);
    }

    private static String replaceLine(String text, String key, String value) {
        String prefix = key + "=";
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean found = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith(prefix)) {
                if (!found) {
                    out.append(prefix).append(value == null ? "" : value);
                    found = true;
                }
            } else {
                out.append(line);
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        if (!found) {
            if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
            out.append(prefix).append(value == null ? "" : value).append('\n');
        }
        return out.toString();
    }

    private static String value(String body, String key) {
        String prefix = key + "=";
        for (String line : body.split("\n", -1)) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).strip();
            }
        }
        return "";
    }
}
