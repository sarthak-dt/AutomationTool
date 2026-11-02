package com.stellar.platform.analysis.investigation.report;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Unique HTML report names for one destination folder.
 * Windows names are compared without case. The unsuffixed name is used when it
 * is free; otherwise the lowest positive {@code _N} suffix is chosen.
 */
public final class ReportNameAllocator {

    public static final String NAME_REQUIRED = "Report name is required.";
    public static final String NAME_EXISTS = "A report with this name already exists.";
    public static final String NAME_NOT_ALLOWED = "Report name is not allowed.";
    public static final String NAME_TOO_LONG = "Report name is too long.";
    public static final String NAME_INVALID_CHARACTERS =
            "Report name cannot contain any of these characters: < > : \" / \\ | ? *";

    private static final int MAX_DISPLAY = 180;
    private static final Pattern SUFFIX = Pattern.compile("[1-9]\\d{0,8}");
    private static final Pattern RESERVED = Pattern.compile("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$", Pattern.CASE_INSENSITIVE);

    private ReportNameAllocator() {
    }

    public static String requireDisplayName(String raw) {
        String error = validationError(raw);
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        return normalize(raw);
    }

    /** User-facing validation. {@code null} means the name can be used. */
    public static String validationError(String raw) {
        if (raw == null || raw.isBlank()) {
            return NAME_REQUIRED;
        }
        String name = normalize(raw);
        if (name.isBlank()) {
            return NAME_REQUIRED;
        }
        if (containsInvalidCharacter(name)) {
            return NAME_INVALID_CHARACTERS;
        }
        if (RESERVED.matcher(name).matches()) {
            return NAME_NOT_ALLOWED;
        }
        if (name.length() > MAX_DISPLAY) {
            return NAME_TOO_LONG;
        }
        return null;
    }

    /**
     * Next HTML filename for {@code raw}. Does not create a file.
     * {@code existingFileNames} are file names in the destination folder, not paths.
     */
    public static String allocate(String raw, Collection<String> existingFileNames) {
        String base = requireDisplayName(raw);
        Set<Integer> used = occupied(base, existingFileNames);
        if (!used.contains(0)) {
            return base + ".html";
        }
        int suffix = 1;
        while (used.contains(suffix)) {
            suffix++;
        }
        return base + "_" + suffix + ".html";
    }

    /** Next PDF filename. HTML allocation is unchanged and does not treat a PDF as the same file. */
    public static String allocatePdf(String raw, Collection<String> existingFileNames) {
        String base = requireDisplayName(stripPdf(raw));
        Set<Integer> used = occupiedPdf(base, existingFileNames);
        if (!used.contains(0)) {
            return base + ".pdf";
        }
        int suffix = 1;
        while (used.contains(suffix)) {
            suffix++;
        }
        return base + "_" + suffix + ".pdf";
    }

    private static String stripPdf(String raw) {
        String name = raw == null ? "" : raw.trim();
        while (name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            name = name.substring(0, name.length() - 4).trim();
        }
        return name;
    }

    private static Set<Integer> occupiedPdf(String base, Collection<String> existingFileNames) {
        Set<Integer> used = new HashSet<>();
        if (existingFileNames == null) {
            return used;
        }
        String needle = base.toLowerCase(Locale.ROOT);
        String prefix = needle + "_";
        for (String existing : existingFileNames) {
            if (existing == null || !existing.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                continue;
            }
            String stem = existing.substring(0, existing.length() - 4).toLowerCase(Locale.ROOT);
            if (stem.equals(needle)) {
                used.add(0);
            } else if (stem.startsWith(prefix)) {
                String suffix = stem.substring(prefix.length());
                if (SUFFIX.matcher(suffix).matches()) {
                    try {
                        used.add(Integer.parseInt(suffix));
                    } catch (NumberFormatException ignored) {
                        // Not an allocated suffix.
                    }
                }
            }
        }
        return used;
    }

    /**
     * True when another file already uses the unsuffixed HTML name.
     * {@code currentFileName} is ignored so a report can keep its own name.
     */
    public static boolean nameTaken(String raw, Collection<String> existingFileNames, String currentFileName) {
        String base = requireDisplayName(raw);
        String target = (base + ".html").toLowerCase(Locale.ROOT);
        String self = currentFileName == null ? "" : currentFileName.toLowerCase(Locale.ROOT);
        if (target.equals(self)) {
            return false;
        }
        if (existingFileNames == null) {
            return false;
        }
        for (String existing : existingFileNames) {
            if (existing != null && existing.toLowerCase(Locale.ROOT).equals(target)) {
                return true;
            }
        }
        return false;
    }

    public static String stemOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.trim();
        if (name.toLowerCase(Locale.ROOT).endsWith(".html")) {
            name = name.substring(0, name.length() - 5);
        }
        return name;
    }

    static String normalize(String raw) {
        String name = raw == null ? "" : raw.trim();
        while (name.toLowerCase(Locale.ROOT).endsWith(".html")) {
            name = name.substring(0, name.length() - 5).trim();
        }
        while (name.endsWith(".") || name.endsWith(" ")) {
            name = name.substring(0, name.length() - 1);
        }
        return name.trim();
    }

    private static boolean containsInvalidCharacter(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static Set<Integer> occupied(String base, Collection<String> existingFileNames) {
        Set<Integer> used = new HashSet<>();
        if (existingFileNames == null) {
            return used;
        }
        String needle = base.toLowerCase(Locale.ROOT);
        String prefix = needle + "_";
        for (String existing : existingFileNames) {
            if (existing == null || existing.isBlank()) {
                continue;
            }
            String stem = stemOf(existing).toLowerCase(Locale.ROOT);
            if (stem.equals(needle)) {
                used.add(0);
            } else if (stem.startsWith(prefix)) {
                String suffix = stem.substring(prefix.length());
                if (SUFFIX.matcher(suffix).matches()) {
                    try {
                        used.add(Integer.parseInt(suffix));
                    } catch (NumberFormatException ignored) {
                        // Not an allocated suffix.
                    }
                }
            }
        }
        return used;
    }
}
