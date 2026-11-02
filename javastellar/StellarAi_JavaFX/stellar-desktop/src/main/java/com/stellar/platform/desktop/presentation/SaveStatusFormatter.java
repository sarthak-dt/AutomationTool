package com.stellar.platform.desktop.presentation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Presentation formatting for the last explicit project Save timestamp.
 * Persistence stores {@link Instant}; this class never writes storage.
 */
public final class SaveStatusFormatter {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.UK);
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss", Locale.UK);

    private SaveStatusFormatter() {
    }

    public static String compactStatus(Instant savedAt, ZoneId zone) {
        if (savedAt == null) {
            return "Saved";
        }
        ZoneId z = zone == null ? ZoneId.systemDefault() : zone;
        LocalDate savedDay = LocalDate.ofInstant(savedAt, z);
        LocalDate today = LocalDate.now(z);
        if (savedDay.equals(today)) {
            return "Saved " + TIME.withZone(z).format(savedAt);
        }
        if (savedDay.getYear() == today.getYear()) {
            return "Saved " + DAY_TIME.withZone(z).format(savedAt);
        }
        return "Saved " + DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.UK).withZone(z).format(savedAt);
    }

    public static String tooltip(Instant savedAt, ZoneId zone) {
        if (savedAt == null) {
            return "Last saved: unknown";
        }
        ZoneId z = zone == null ? ZoneId.systemDefault() : zone;
        return "Last saved: " + FULL.withZone(z).format(Objects.requireNonNull(savedAt));
    }
}
