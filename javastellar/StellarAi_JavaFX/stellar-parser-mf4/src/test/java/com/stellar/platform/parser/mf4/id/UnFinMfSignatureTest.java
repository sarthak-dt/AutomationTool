package com.stellar.platform.parser.mf4.id;

import com.stellar.platform.parser.mf4.Mf4Inspector;
import com.stellar.platform.parser.mf4.analysis.MdfFinalizationStatus;
import com.stellar.platform.parser.mf4.report.MdfInspectionReport;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ParserException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnFinMfSignatureTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsUnFinMfSignatureAsUnfinalizedMdf4() throws Exception {
        Path file = tempDir.resolve("unfin.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("UnFinMF ", "4.11", 411, 0x25);
            long hd = b.writeHd(0, 0, 0);
            long cn = b.writeCn(0, b.writeTx("Data"), 0, 0, 0, 0, 0, 0, 8, 0);
            long cg = b.writeCg(0, cn, 1, 0, 0, 1, 0);
            long dt = b.writeDt(new byte[]{0x42});
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }

        assertTrue(MdfIdentificationReader.isRecognizedMdfSignature("UnFinMF"));
        MdfInspectionReport report = new Mf4Inspector().inspect(file);
        assertEquals(MdfFinalizationStatus.UNFINALIZED, report.finalizationStatus());
        assertTrue(report.recoveryCandidate() || report.physicalDataDetected());
        assertTrue(report.finalizationReasons().stream()
                .anyMatch(r -> r.toLowerCase().contains("unfinmf")));
    }

    @Test
    void stillRejectsNonMdfSignatures() throws Exception {
        Path file = tempDir.resolve("junk.bin");
        byte[] payload = new byte[128];
        byte[] hello = "HELLO WORLD NOT MDF FILE CONTENT".getBytes();
        System.arraycopy(hello, 0, payload, 0, hello.length);
        Files.write(file, payload);
        ParserException ex = assertThrows(ParserException.class, () -> new Mf4Inspector().inspect(file));
        String msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("not an mdf") || msg.contains("signature") || msg.contains("expected signature"),
                () -> "Unexpected: " + ex.getMessage());
        assertFalse(MdfIdentificationReader.isRecognizedMdfSignature("HELLO"));
    }
}
