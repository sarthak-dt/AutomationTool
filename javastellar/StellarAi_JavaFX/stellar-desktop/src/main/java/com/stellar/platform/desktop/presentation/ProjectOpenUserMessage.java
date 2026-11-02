package com.stellar.platform.desktop.presentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Separates customer-facing Resume/open messages from implementation provenance.
 */
public final class ProjectOpenUserMessage {

    public enum Kind {
        NONE,
        ACTION_REQUIRED
    }

    public record Presentation(Kind kind, String header, String body, List<String> technicalDetails) {
        public Presentation {
            kind = kind == null ? Kind.NONE : kind;
            header = header == null ? "" : header;
            body = body == null ? "" : body;
            technicalDetails = List.copyOf(technicalDetails == null ? List.of() : technicalDetails);
        }

        public boolean showDialog() {
            return kind == Kind.ACTION_REQUIRED && !body.isBlank();
        }
    }

    private static final Pattern SOURCE_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private ProjectOpenUserMessage() {
    }

    public static Presentation fromWarnings(String projectName, List<String> warnings) {
        String name = projectName == null || projectName.isBlank() ? "Project" : projectName.trim();
        List<String> raw = warnings == null ? List.of() : warnings;
        List<String> technical = new ArrayList<>();
        List<String> actionable = new ArrayList<>();
        for (String warning : raw) {
            if (warning == null || warning.isBlank() || dropEntirely(warning)) {
                continue;
            }
            technical.add(warning);
            if (actionRequired(warning)) {
                String human = humanize(warning);
                if (!human.isBlank() && !actionable.contains(human)) {
                    actionable.add(human);
                }
            }
        }
        if (actionable.isEmpty()) {
            return new Presentation(Kind.NONE, "", "", technical);
        }
        return new Presentation(
                Kind.ACTION_REQUIRED,
                name + " needs attention before analysis is trustworthy",
                String.join("\n", actionable),
                technical);
    }

    static boolean dropEntirely(String warning) {
        String lower = warning.toLowerCase(Locale.ROOT);
        return lower.contains("reusable analysis artifacts are available")
                || lower.contains("without reparsing");
    }

    static boolean informational(String warning) {
        String lower = warning.toLowerCase(Locale.ROOT);
        return dropEntirely(warning) || lower.contains("processing-plan fingerprint missing");
    }

    static boolean actionRequired(String warning) {
        String lower = warning.toLowerCase(Locale.ROOT);
        if (informational(warning)) {
            return false;
        }
        return lower.contains("reprocessing required")
                || lower.contains("no longer available")
                || lower.contains("file size changed")
                || lower.contains("measurement changed")
                || lower.contains("invalid saved path")
                || lower.contains("cannot read saved source")
                || lower.contains("analysis storage unavailable")
                || lower.contains("artifact missing")
                || lower.contains("artifact payloads missing")
                || lower.contains("artifact version incompatible")
                || lower.contains("source fingerprint mismatch")
                || lower.contains("definition fingerprint mismatch")
                || (lower.contains("processing-plan fingerprint")
                && (lower.contains("mismatch") || lower.contains("incompatible")));
    }

    static String humanize(String warning) {
        String stripped = SOURCE_UUID.matcher(Objects.requireNonNullElse(warning, "")).replaceAll("this measurement");
        String lower = stripped.toLowerCase(Locale.ROOT);
        if (lower.contains("processing-plan fingerprint")) {
            return "Saved analysis does not match the current process setup. Process again if signals look wrong.";
        }
        if (lower.contains("source fingerprint") || lower.contains("measurement changed")) {
            return "A measurement file changed since it was last processed. Process again to refresh signals.";
        }
        if (lower.contains("definition fingerprint")) {
            return "A DBC definition changed since the last process run. Process again to refresh decoded signals.";
        }
        if (lower.contains("no longer available") || lower.contains("invalid saved path")
                || lower.contains("cannot read saved source")) {
            return "A source file is missing or unreadable. Locate the original measurement to continue.";
        }
        if (lower.contains("file size changed")) {
            return "A source file changed size since the last save. Process again if the recording was replaced.";
        }
        if (lower.contains("analysis storage unavailable")) {
            return "Analysis storage is not available, so saved processed signals cannot be loaded.";
        }
        if (lower.contains("reprocessing required") || lower.contains("artifact")) {
            return "Saved processed signals are incomplete or incompatible. Process again to rebuild analysis.";
        }
        return stripped;
    }
}
