package com.stellar.platform.analysis.investigation;

import java.nio.file.Path;
import java.util.Locale;

/** Strips private absolute paths from report/export metadata. */
public final class PathSanitizer {

    private PathSanitizer() {
    }

    public static String displayName(String pathOrName) {
        if (pathOrName == null || pathOrName.isBlank()) {
            return "";
        }
        String trimmed = pathOrName.trim().replace('\\', '/');
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }

    public static String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String out = text;
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            out = replaceIgnoreCase(out, home, "<home>");
            out = replaceIgnoreCase(out, home.replace('\\', '/'), "<home>");
        }
        String user = System.getProperty("user.name");
        if (user != null && user.length() > 1) {
            out = out.replace("\\" + user + "\\", "\\<user>\\");
            out = out.replace("/" + user + "/", "/<user>/");
        }
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp != null && !tmp.isBlank()) {
            out = replaceIgnoreCase(out, tmp, "<temp>");
        }
        out = out.replaceAll("(?i)[A-Z]:\\\\Users\\\\[^\\\\/]+", "<home>");
        out = out.replaceAll("(?i)/Users/[^/]+", "<home>");
        return out;
    }

    public static String sanitizePath(Path path) {
        return path == null ? "" : sanitize(path.toString());
    }

    private static String replaceIgnoreCase(String haystack, String needle, String replacement) {
        if (needle == null || needle.isBlank()) {
            return haystack;
        }
        String lower = haystack.toLowerCase(Locale.ROOT);
        String n = needle.toLowerCase(Locale.ROOT);
        int i = lower.indexOf(n);
        if (i < 0) {
            return haystack;
        }
        return haystack.substring(0, i) + replacement + haystack.substring(i + needle.length());
    }
}
