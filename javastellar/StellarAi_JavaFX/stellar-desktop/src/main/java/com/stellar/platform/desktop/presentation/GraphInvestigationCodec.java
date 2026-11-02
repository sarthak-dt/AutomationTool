package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;

import java.util.ArrayList;
import java.util.List;

/**
 * Encodes {@link GraphInvestigationSnapshot} traces as a line-oriented blob for SQLite.
 * Field separator is a unit separator so signal names may contain pipes or commas.
 */
public final class GraphInvestigationCodec {

    public static final int STATE_VERSION = 1;
    static final String VERSION_HEADER = "GIV1";
    static final String VERSION_HEADER_V2 = "GIV2";
    private static final char UNIT = '\u001f';

    private GraphInvestigationCodec() {
    }

    /**
     * True when the blob is empty, a Phase-5 unversioned blob, or an explicit GIV1 payload.
     * Unknown {@code GIVx} headers are incompatible and must not be reinterpreted.
     */
    public static boolean compatibleTracesBlob(String blob) {
        if (blob == null || blob.isBlank()) {
            return true;
        }
        if (!blob.startsWith("GIV")) {
            return true;
        }
        int newline = blob.indexOf('\n');
        String header = newline < 0 ? blob.trim() : blob.substring(0, newline).trim();
        return VERSION_HEADER.equals(header) || VERSION_HEADER_V2.equals(header);
    }

    public static String encodeTraces(List<GraphInvestigationSnapshot.Trace> traces) {
        String body = encodeTraceBody(traces);
        return body.isEmpty() ? VERSION_HEADER : VERSION_HEADER + "\n" + body;
    }

    /**
     * GIV2 adds the timeline domain. GIV1 readers ignore an unknown header, so this writer
     * is only stored where {@link #compatibleTracesBlob} accepts both versions.
     * Slot numbers stay in the viewport columns and are labeled by {@code viewportDomain}.
     */
    public static String encodeState(List<GraphInvestigationSnapshot.Trace> traces,
                                      String timelineGroup, String fileFocusMeasurementId, String viewportDomain) {
        String domainLine = "D" + UNIT + nullToEmpty(timelineGroup) + UNIT
                + nullToEmpty(fileFocusMeasurementId) + UNIT + nullToEmpty(viewportDomain);
        String body = encodeTraceBody(traces);
        return body.isEmpty() ? VERSION_HEADER_V2 + "\n" + domainLine
                : VERSION_HEADER_V2 + "\n" + domainLine + "\n" + body;
    }

    public record Domain(String timelineGroup, String fileFocusMeasurementId, String viewportDomain) {
        public Domain {
            timelineGroup = timelineGroup == null ? "" : timelineGroup;
            fileFocusMeasurementId = fileFocusMeasurementId == null ? "" : fileFocusMeasurementId;
            viewportDomain = viewportDomain == null ? "" : viewportDomain;
        }
    }

    public static Domain decodeDomain(String blob) {
        if (blob == null || !blob.startsWith(VERSION_HEADER_V2)) {
            return new Domain("", "", "");
        }
        String body = stripVersionHeader(blob);
        for (String line : body.split("\n", -1)) {
            if (line.startsWith("D" + UNIT)) {
                String[] parts = split(line, 4);
                if (parts.length >= 4) {
                    return new Domain(parts[1], parts[2], parts[3]);
                }
            }
        }
        return new Domain("", "", "");
    }

    private static String encodeTraceBody(List<GraphInvestigationSnapshot.Trace> traces) {
        if (traces == null || traces.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (GraphInvestigationSnapshot.Trace trace : traces) {
            if (trace == null || trace.key() == null) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(trace.hidden() ? 'H' : 'V')
                    .append(UNIT)
                    .append(trace.colorIndex())
                    .append(UNIT)
                    .append(encodeKey(trace.key()));
        }
        return sb.toString();
    }

    public static List<GraphInvestigationSnapshot.Trace> decodeTraces(String blob) {
        if (blob == null || blob.isBlank() || !compatibleTracesBlob(blob)) {
            return List.of();
        }
        String body = stripVersionHeader(blob);
        if (body.isBlank()) {
            return List.of();
        }
        List<GraphInvestigationSnapshot.Trace> out = new ArrayList<>();
        for (String line : body.split("\n", -1)) {
            if (line.isBlank() || line.startsWith("D" + UNIT)) {
                continue;
            }
            int first = line.indexOf(UNIT);
            int second = first < 0 ? -1 : line.indexOf(UNIT, first + 1);
            if (first < 0 || second < 0) {
                continue;
            }
            String flag = line.substring(0, first);
            String colorText = line.substring(first + 1, second);
            SignalCatalogKey key = decodeKey(line.substring(second + 1));
            if (key == null) {
                continue;
            }
            boolean hidden = !flag.isEmpty() && flag.charAt(0) == 'H';
            int color = 0;
            try {
                color = Integer.parseInt(colorText);
            } catch (NumberFormatException ignored) {
                color = 0;
            }
            out.add(new GraphInvestigationSnapshot.Trace(key, hidden, color));
        }
        return List.copyOf(out);
    }

    public static String encodeKey(SignalCatalogKey key) {
        if (key == null) {
            return "";
        }
        return key.signalName() + UNIT
                + key.sourceType().name() + UNIT
                + key.messageName() + UNIT
                + key.canId() + UNIT
                + key.channel() + UNIT
                + nullToEmpty(key.sourceFile()) + UNIT
                + nullToEmpty(key.sourceDatabase());
    }

    public static SignalCatalogKey decodeKey(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] parts = split(encoded, 7);
        if (parts.length < 7) {
            return null;
        }
        try {
            SignalSourceType type = SignalSourceType.valueOf(parts[1]);
            return new SignalCatalogKey(
                    parts[0], type, parts[2],
                    Long.parseLong(parts[3]), Integer.parseInt(parts[4]),
                    parts[5], parts[6]);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String[] split(String value, int expected) {
        List<String> parts = new ArrayList<>(expected);
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == UNIT) {
                parts.add(value.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(value.substring(start));
        return parts.toArray(String[]::new);
    }

    private static String stripVersionHeader(String blob) {
        if (blob.startsWith(VERSION_HEADER_V2) || blob.startsWith(VERSION_HEADER)) {
            int newline = blob.indexOf('\n');
            return newline < 0 ? "" : blob.substring(newline + 1);
        }
        return blob;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
