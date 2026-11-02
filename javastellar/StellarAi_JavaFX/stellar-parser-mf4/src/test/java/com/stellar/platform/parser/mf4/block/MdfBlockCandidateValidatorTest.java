package com.stellar.platform.parser.mf4.block;

import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MdfBlockCandidateValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsRawHashHashWithoutStructuralHeader() throws Exception {
        Path file = tempDir.resolve("raw-hash.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            b.writeHd(0, 0, 0);
            b.appendRaw(new byte[]{'#', '#', 0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
        }
        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            long candidate = access.fileSize() - 8;
            assertEqualsHashAt(access, candidate);
            assertTrue(MdfBlockCandidateValidator.validateBlockCandidate(access, candidate).isEmpty());
        }
    }

    @Test
    void acceptsKnownAlignedBlock() throws Exception {
        Path file = tempDir.resolve("valid-tx.mf4");
        long tx;
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            b.writeHd(0, 0, 0);
            tx = b.writeTx("ok");
        }
        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            assertTrue(MdfBlockCandidateValidator.validateBlockCandidate(access, tx).isPresent());
            assertFalse(MdfBlockCandidateValidator.validateBlockCandidate(access, tx + 1).isPresent());
        }
    }

    private static void assertEqualsHashAt(MdfFileAccess access, long offset) throws Exception {
        byte[] b = access.readFully(offset, 2, "hash");
        assertTrue(b[0] == '#' && b[1] == '#');
    }
}
