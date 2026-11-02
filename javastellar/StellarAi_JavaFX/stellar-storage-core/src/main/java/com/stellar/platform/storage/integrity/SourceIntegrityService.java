package com.stellar.platform.storage.integrity;

import com.stellar.platform.storage.metadata.FileFingerprint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Fast size+mtime identity for everyday reopen, plus optional SHA-256 for first ingest
 * and explicit validation. Never hashes a multi-GB file on a normal project open.
 */
public final class SourceIntegrityService {

    public String fastFingerprint(Path path) {
        return FileFingerprint.of(path);
    }

    public String fingerprintWithHash(Path path) {
        return FileFingerprint.of(path, sha256(path));
    }

    public boolean matchesFast(String expected, Path path) {
        return FileFingerprint.matches(expected, path);
    }

    public boolean verifyStrong(String expectedFingerprint, Path path) {
        if (expectedFingerprint == null || path == null || !Files.isRegularFile(path)) {
            return false;
        }
        String expectedHash = hashToken(expectedFingerprint);
        if (expectedHash == null || expectedHash.isBlank()) {
            return FileFingerprint.matches(expectedFingerprint, path);
        }
        return expectedHash.equalsIgnoreCase(sha256(path));
    }

    public String sha256(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    digest.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static String hashToken(String fingerprint) {
        int idx = fingerprint.indexOf("hash=");
        if (idx < 0) {
            return null;
        }
        int start = idx + 5;
        int end = fingerprint.indexOf(';', start);
        return end < 0 ? fingerprint.substring(start) : fingerprint.substring(start, end);
    }
}
