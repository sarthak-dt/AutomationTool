package com.stellar.platform.desktop.presentation;

import java.util.Locale;
import java.util.OptionalLong;

/**
 * Presentation-level CAN ID search normalization for Source Inventory.
 * Does not create domain identities or mutate inventory.
 */
public final class CanIdSearchQuery {

    private CanIdSearchQuery() {
    }

    public record Parsed(String rawNormalized, OptionalLong exactId, String textToken) {
    }

    public static Parsed parse(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return new Parsed("", OptionalLong.empty(), "");
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        OptionalLong exact = tryParseCanId(trimmed);
        return new Parsed(normalized, exact, normalized);
    }

    public static boolean matches(Parsed query, long canId, boolean extended, String channelLabel) {
        if (query == null || query.rawNormalized().isEmpty()) {
            return true;
        }
        if (query.exactId().isPresent() && query.exactId().getAsLong() == canId) {
            return true;
        }
        String hex = Long.toHexString(canId).toLowerCase(Locale.ROOT);
        String hexPrefixed = "0x" + hex;
        String dec = Long.toString(canId);
        String token = query.textToken();
        if (hex.contains(token) || hexPrefixed.contains(token) || dec.contains(token)) {
            return true;
        }
        return channelLabel != null && channelLabel.toLowerCase(Locale.ROOT).contains(token);
    }

    static OptionalLong tryParseCanId(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            if (s.regionMatches(true, 0, "0x", 0, 2)) {
                if (s.length() <= 2) {
                    return OptionalLong.empty();
                }
                return OptionalLong.of(Long.parseUnsignedLong(s.substring(2), 16));
            }
            if (s.chars().allMatch(Character::isDigit)) {
                return OptionalLong.of(Long.parseUnsignedLong(s, 10));
            }
            boolean hasHexLetter = s.chars().anyMatch(c ->
                    (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'));
            boolean hexChars = s.chars().allMatch(c -> Character.digit(c, 16) >= 0);
            if (hasHexLetter && hexChars) {
                return OptionalLong.of(Long.parseUnsignedLong(s, 16));
            }
        } catch (NumberFormatException ignored) {
            return OptionalLong.empty();
        }
        return OptionalLong.empty();
    }
}
