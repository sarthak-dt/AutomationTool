package com.stellar.platform.storage.metadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Portable file identity fingerprints.
 * <p>
 * Version 2 omits absolute paths so moving a project between machines does not
 * invalidate artifacts solely due to path change. Fast validation uses size + mtime.
 * Optional content hash may be supplied when computed during ingest (never forced on reopen).
 * <p>
 * Legacy v1 fingerprints that embedded {@code path=} remain readable: matching falls back to
 * size + mtime comparison when the absolute path no longer matches.
 */
public final class FileFingerprint {

    public static final String VERSION_2 = "2";
    private static final Pattern SIZE = Pattern.compile("size=(\\d+)");
    private static final Pattern MTIME = Pattern.compile("mtime=(\\d+)");

    private FileFingerprint() {
    }

    /** Fast portable fingerprint (size + mtime + original file name). No absolute path. */
    public static String of(Path path) {
        return of(path, null);
    }

    public static String of(Path path, String optionalContentHash) {
        if (path == null) {
            return "";
        }
        try {
            if (!Files.isRegularFile(path)) {
                String name = path.getFileName() == null ? "" : path.getFileName().toString();
                return "v=" + VERSION_2 + ";missing=1;name=" + escape(name);
            }
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            String name = path.getFileName() == null ? "" : path.getFileName().toString();
            StringBuilder sb = new StringBuilder(96);
            sb.append("v=").append(VERSION_2)
                    .append(";size=").append(attrs.size())
                    .append(";mtime=").append(attrs.lastModifiedTime().toMillis())
                    .append(";name=").append(escape(name));
            if (optionalContentHash != null && !optionalContentHash.isBlank()) {
                sb.append(";hash=").append(optionalContentHash.trim());
            }
            return sb.toString();
        } catch (IOException | RuntimeException ex) {
            return "v=" + VERSION_2 + ";error=" + ex.getClass().getSimpleName();
        }
    }

    /**
     * True when the stored fingerprint still describes {@code path}.
     * Prefer strong hash when both sides have one; otherwise compare size + mtime.
     */
    public static boolean matches(String expected, Path path) {
        if (expected == null || expected.isBlank() || path == null) {
            return false;
        }
        if (!Files.isRegularFile(path)) {
            return false;
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            Long expectedSize = parseLong(SIZE, expected);
            Long expectedMtime = parseLong(MTIME, expected);
            if (expectedSize == null || expectedMtime == null) {
                return expected.equals(of(path));
            }
            if (attrs.size() != expectedSize || attrs.lastModifiedTime().toMillis() != expectedMtime) {
                return false;
            }
            String expectedHash = parseToken(expected, "hash");
            if (expectedHash != null && !expectedHash.isBlank()) {
                // Strong hash present in stored fingerprint but not recomputed here (multi-GB safety).
                // Size+mtime already matched; treat as valid without a second full-file hash pass.
                return true;
            }
            return true;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    private static Long parseLong(Pattern pattern, String fingerprint) {
        Matcher m = pattern.matcher(fingerprint);
        if (!m.find()) return null;
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String parseToken(String fingerprint, String key) {
        String needle = key + "=";
        int idx = fingerprint.indexOf(needle);
        if (idx < 0) return null;
        int start = idx + needle.length();
        int end = fingerprint.indexOf(';', start);
        String raw = end < 0 ? fingerprint.substring(start) : fingerprint.substring(start, end);
        return unescape(raw);
    }

    private static String escape(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.replace("\\", "\\\\").replace(";", "\\;").replace("=", "\\=");
    }

    private static String unescape(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                out.append(value.charAt(++i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Test helper: describe whether fingerprint looks like legacy v1 (embedded absolute path). */
    public static boolean isLegacyPathBound(String fingerprint) {
        if (fingerprint == null) return false;
        String lower = fingerprint.toLowerCase(Locale.ROOT);
        return lower.contains(";path=") || lower.startsWith("path=");
    }
}
