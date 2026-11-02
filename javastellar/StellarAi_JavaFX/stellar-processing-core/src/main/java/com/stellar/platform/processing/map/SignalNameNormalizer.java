package com.stellar.platform.processing.map;

import java.util.Locale;

/**
 * Case-insensitive signal-name folding for semantic matching.
 *
 * <p>Separators (space, underscore, hyphen, dot) and camelCase boundaries are removed so
 * {@code GPS_Latitude}, {@code gps latitude} and {@code GPSLatitude} compare equal. This is
 * matching metadata only — catalog identity stays the original name.
 */
public final class SignalNameNormalizer {

    private SignalNameNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String spaced = raw.replaceAll("([a-z])([A-Z])", "$1 $2");
        StringBuilder sb = new StringBuilder(spaced.length());
        for (int i = 0; i < spaced.length(); i++) {
            char c = spaced.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    public static String normalizeLoose(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}
