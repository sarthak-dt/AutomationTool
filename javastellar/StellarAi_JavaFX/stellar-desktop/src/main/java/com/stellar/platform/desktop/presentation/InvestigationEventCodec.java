package com.stellar.platform.desktop.presentation;

/**
 * Title and optional note share the existing bookmark {@code observation} column so events
 * persist without a schema change. First line is the title; the remainder is the note.
 */
public final class InvestigationEventCodec {

    public static final String[] CATEGORIES = {
            "Thermal observation", "Braking", "Acceleration", "Durability",
            "Vehicle stability", "Sensor anomaly", "Custom event"
    };

    /** Categories saved by earlier builds. They stay editable. */
    private static final String[] LEGACY_CATEGORIES = {
            "Observation", "Issue", "Diagnostic", "Other"
    };

    private InvestigationEventCodec() {
    }

    public static String encode(String title, String note) {
        String heading = title == null ? "" : title.strip();
        String body = note == null ? "" : note.strip();
        if (body.isBlank()) {
            return heading;
        }
        if (heading.isBlank()) {
            return body;
        }
        return heading + "\n" + body;
    }

    public static String titleOf(String observation) {
        if (observation == null || observation.isBlank()) {
            return "Event";
        }
        int breakAt = observation.indexOf('\n');
        String title = breakAt < 0 ? observation : observation.substring(0, breakAt);
        String trimmed = title.strip();
        return trimmed.isBlank() ? "Event" : trimmed;
    }

    public static String noteOf(String observation) {
        if (observation == null) {
            return "";
        }
        int breakAt = observation.indexOf('\n');
        String rest = breakAt < 0 ? "" : observation.substring(breakAt + 1);
        int sentinel = rest.indexOf(EngineerEventDocuments.SENTINEL);
        if (sentinel >= 0) {
            rest = rest.substring(0, sentinel);
        }
        return rest.strip();
    }

    public static String categoryOf(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return "Observation";
        }
        for (String category : CATEGORIES) {
            if (category.equalsIgnoreCase(eventType.strip())) {
                return category;
            }
        }
        for (String category : LEGACY_CATEGORIES) {
            if (category.equalsIgnoreCase(eventType.strip())) {
                return category;
            }
        }
        return eventType.strip();
    }

    /** True only for categories created by the Inspector Add Event form. */
    public static boolean isManual(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return false;
        }
        for (String category : CATEGORIES) {
            if (category.equalsIgnoreCase(eventType.strip())) {
                return true;
            }
        }
        for (String category : LEGACY_CATEGORIES) {
            if (category.equalsIgnoreCase(eventType.strip())) {
                return true;
            }
        }
        return false;
    }
}
