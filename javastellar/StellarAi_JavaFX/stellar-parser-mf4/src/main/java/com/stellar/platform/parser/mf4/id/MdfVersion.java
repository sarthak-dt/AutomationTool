package com.stellar.platform.parser.mf4.id;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * MDF format version (major.minor), e.g. 4.10 / 4.11 / 4.20.
 */
public record MdfVersion(int major, int minor, int versionCode, String versionString) {

    public MdfVersion {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("Version components must be non-negative");
        }
        Objects.requireNonNull(versionString, "versionString");
    }

    public boolean isMdf4() {
        return major == 4;
    }

    public static Optional<MdfVersion> parse(String versionString, int versionCode) {
        if (versionString == null || versionString.isBlank()) {
            return Optional.empty();
        }
        String trimmed = versionString.trim();
        String[] parts = trimmed.split("\\.");
        if (parts.length < 2) {
            return Optional.empty();
        }
        try {
            int major = Integer.parseInt(parts[0].trim());
            String minorRaw = parts[1].trim();
            // "10", "11", "20" — also tolerate "1.0" style by taking leading digits
            int minor = Integer.parseInt(minorRaw.replaceAll("[^0-9].*$", ""));
            int code = versionCode > 0 ? versionCode : major * 100 + minor;
            return Optional.of(new MdfVersion(major, minor, code, trimmed));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public static MdfVersion ofCode(int versionCode) {
        int major = versionCode / 100;
        int minor = versionCode % 100;
        return new MdfVersion(major, minor, versionCode, major + "." + String.format(Locale.ROOT, "%02d", minor));
    }

    @Override
    public String toString() {
        return versionString;
    }
}
