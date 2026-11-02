package com.stellar.platform.analysis.investigation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Traceable graph snapshot. PNG bytes live beside this metadata; never in SQLite. */
public record GraphSnapshotMetadata(
        String snapshotId,
        String investigationId,
        Instant createdAt,
        Long rangeStartNanos,
        Long rangeEndNanos,
        List<String> signalKeys,
        List<String> visibleSignals,
        String layout,
        String sourceFingerprints,
        String fileReference,
        List<String> units,
        String caption,
        String timeBasis
) {
    public GraphSnapshotMetadata {
        snapshotId = snapshotId == null ? "" : snapshotId;
        investigationId = investigationId == null ? "" : investigationId;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        signalKeys = signalKeys == null ? List.of() : List.copyOf(signalKeys);
        visibleSignals = visibleSignals == null ? List.of() : List.copyOf(visibleSignals);
        layout = layout == null ? "" : layout;
        sourceFingerprints = sourceFingerprints == null ? "" : sourceFingerprints;
        fileReference = fileReference == null ? "" : fileReference;
        units = units == null ? List.of() : List.copyOf(units);
        caption = caption == null ? "" : caption;
        timeBasis = timeBasis == null ? "" : timeBasis;
    }

    public String toJson() {
        return "{\"snapshotId\":\"" + esc(snapshotId)
                + "\",\"investigationId\":\"" + esc(investigationId)
                + "\",\"createdAt\":\"" + createdAt
                + "\",\"rangeStartNanos\":" + num(rangeStartNanos)
                + ",\"rangeEndNanos\":" + num(rangeEndNanos)
                + ",\"layout\":\"" + esc(layout)
                + "\",\"sourceFingerprints\":\"" + esc(sourceFingerprints)
                + "\",\"fileReference\":\"" + esc(fileReference)
                + "\",\"caption\":\"" + esc(caption)
                + "\",\"timeBasis\":\"" + esc(timeBasis)
                + "\",\"signalKeys\":" + array(signalKeys)
                + ",\"visibleSignals\":" + array(visibleSignals)
                + ",\"units\":" + array(units) + "}";
    }

    /** Reads a snapshot document. Older files without signal or unit arrays still load. */
    public static GraphSnapshotMetadata fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        String created = str(json, "createdAt");
        Instant when;
        try {
            when = created.isBlank() ? Instant.EPOCH : Instant.parse(created);
        } catch (RuntimeException ex) {
            when = Instant.EPOCH;
        }
        return new GraphSnapshotMetadata(
                str(json, "snapshotId"),
                str(json, "investigationId"),
                when,
                longOrNull(json, "rangeStartNanos"),
                longOrNull(json, "rangeEndNanos"),
                arrayOf(json, "signalKeys"),
                arrayOf(json, "visibleSignals"),
                str(json, "layout"),
                str(json, "sourceFingerprints"),
                str(json, "fileReference"),
                arrayOf(json, "units"),
                str(json, "caption"),
                str(json, "timeBasis"));
    }

    private static String num(Long value) {
        return value == null ? "null" : Long.toString(value);
    }

    private static String esc(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String array(List<String> values) {
        StringBuilder text = new StringBuilder("[");
        boolean any = false;
        for (String value : values) {
            if (any) {
                text.append(',');
            }
            text.append('"').append(esc(value)).append('"');
            any = true;
        }
        return text.append(']').toString();
    }

    private static String str(String json, String key) {
        String token = "\"" + key + "\":\"";
        int at = json.indexOf(token);
        if (at < 0) {
            return "";
        }
        int index = at + token.length();
        StringBuilder out = new StringBuilder();
        while (index < json.length()) {
            char current = json.charAt(index);
            if (current == '\\' && index + 1 < json.length()) {
                out.append(json.charAt(index + 1));
                index += 2;
                continue;
            }
            if (current == '"') {
                break;
            }
            out.append(current);
            index++;
        }
        return out.toString();
    }

    private static Long longOrNull(String json, String key) {
        String token = "\"" + key + "\":";
        int at = json.indexOf(token);
        if (at < 0) {
            return null;
        }
        int from = at + token.length();
        while (from < json.length() && Character.isWhitespace(json.charAt(from))) {
            from++;
        }
        if (json.startsWith("null", from)) {
            return null;
        }
        int end = from;
        if (end < json.length() && json.charAt(end) == '-') {
            end++;
        }
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == from) {
            return null;
        }
        try {
            return Long.parseLong(json.substring(from, end));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static List<String> arrayOf(String json, String key) {
        String token = "\"" + key + "\":";
        int at = json.indexOf(token);
        if (at < 0) {
            return List.of();
        }
        int index = json.indexOf('[', at + token.length());
        if (index < 0) {
            return List.of();
        }
        index++;
        List<String> out = new ArrayList<>();
        while (index < json.length()) {
            while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
                index++;
            }
            if (index >= json.length() || json.charAt(index) == ']') {
                break;
            }
            if (json.charAt(index) == ',') {
                index++;
                continue;
            }
            if (json.charAt(index) != '"') {
                break;
            }
            index++;
            StringBuilder value = new StringBuilder();
            while (index < json.length()) {
                char current = json.charAt(index);
                if (current == '\\' && index + 1 < json.length()) {
                    value.append(json.charAt(index + 1));
                    index += 2;
                    continue;
                }
                if (current == '"') {
                    index++;
                    break;
                }
                value.append(current);
                index++;
            }
            out.add(value.toString());
        }
        return List.copyOf(out);
    }
}
