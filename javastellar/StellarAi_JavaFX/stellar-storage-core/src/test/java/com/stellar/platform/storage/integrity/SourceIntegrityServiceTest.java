package com.stellar.platform.storage.integrity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceIntegrityServiceTest {

    @TempDir
    Path temp;

    @Test
    void sameNameSameSizeDifferentContentFailsStrongVerification() throws Exception {
        Path file = temp.resolve("drive.mf4");
        byte[] first = "AAAAAAAA".repeat(64).getBytes();
        byte[] second = "BBBBBBBB".repeat(64).getBytes();
        Files.write(file, first);
        SourceIntegrityService integrity = new SourceIntegrityService();
        String strong = integrity.fingerprintWithHash(file);
        assertTrue(integrity.matchesFast(strong, file));
        assertTrue(integrity.verifyStrong(strong, file));

        Files.write(file, second);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        // Fast path uses size+mtime and should see the mtime change.
        assertFalse(integrity.matchesFast(strong, file));
        assertFalse(integrity.verifyStrong(strong, file),
                "SHA-256 must detect a same-size content swap");
    }

    @Test
    void sameSizeSameMtimeDifferentContentIsCaughtByHash() throws Exception {
        Path file = temp.resolve("same.mf4");
        byte[] first = new byte[256];
        byte[] second = new byte[256];
        second[0] = 1;
        Files.write(file, first);
        FileTime mtime = Files.getLastModifiedTime(file);
        SourceIntegrityService integrity = new SourceIntegrityService();
        String strong = integrity.fingerprintWithHash(file);
        Files.write(file, second);
        Files.setLastModifiedTime(file, mtime);
        assertTrue(integrity.matchesFast(strong, file),
                "fast reopen must not SHA-256 just because size+mtime still match");
        assertFalse(integrity.verifyStrong(strong, file),
                "strong verification must hash content and reject the swap");
    }
}
