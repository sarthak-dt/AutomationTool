package com.stellar.platform.storage.metadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Stable fingerprint of the processing intent that produced one durable artifact.
 * <p>
 * Scoped to a single job (source/channel/definition/lifecycle/format) so incremental
 * addition of unrelated jobs does not invalidate prior VALID artifacts.
 */
public final class ProcessingPlanFingerprint {

    private ProcessingPlanFingerprint() {
    }

    public static String forJob(
            String projectSourceId,
            int channelId,
            String definitionSourceId,
            String mappingLifecycle,
            String sourceFormat
    ) {
        String canonical = String.join("|",
                normalize(projectSourceId),
                Integer.toString(Math.max(0, channelId)),
                normalize(definitionSourceId),
                normalize(mappingLifecycle).toUpperCase(Locale.ROOT),
                normalize(sourceFormat).toUpperCase(Locale.ROOT));
        return "v1:" + sha256Hex(canonical);
    }

    /**
     * Blank expected fingerprints (legacy rows) are treated as matching.
     */
    public static boolean matches(String expected, String actual) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return expected.equals(actual == null ? "" : actual);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
