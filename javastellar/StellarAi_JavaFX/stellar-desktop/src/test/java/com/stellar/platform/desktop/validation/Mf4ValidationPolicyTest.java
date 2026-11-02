package com.stellar.platform.desktop.validation;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.FileValidationStatus;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.support.TestMeasurementFixtures;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.DesktopFixtures;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.processing.ParserRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mf4ValidationPolicyTest {

    @TempDir
    Path tempDir;

    private ParserRegistry registry;
    private Path dbc;

    @BeforeEach
    void setUp() throws Exception {
        registry = ParserRegistry.load();
        dbc = DesktopFixtures.copyResource("dbc/desktop_engine.dbc", tempDir.resolve("engine.dbc"));
    }

    @Test
    void finalizedMf4_readyAndProcessable() throws Exception {
        Path mf4 = DesktopFixtures.writeFinalizedMf4(tempDir.resolve("final.mf4"));
        DesktopAppService service = newApp();
        WorkspaceFile m = service.addPath(mf4);
        WorkspaceFile d = service.addPath(dbc);
        service.selectMeasurement(m.id());
        service.selectDefinition(d.id());

        InputValidationResult result = service.validateCurrentSelection();
        assertTrue(result.processingAllowed(), result.errorMessages().toString());
        assertEquals(FileValidationStatus.READY, m.status());
        assertTrue(m.status().processingAllowed());
    }

    @Test
    void unfinalizedZeroCycle_readyWithWarningsAndProcessable() throws Exception {
        Path mf4 = DesktopFixtures.writeUnfinalizedMf4(tempDir.resolve("unfin-zero.mf4"));
        DesktopAppService service = newApp();
        WorkspaceFile m = service.addPath(mf4);
        WorkspaceFile d = service.addPath(dbc);
        service.selectMeasurement(m.id());
        service.selectDefinition(d.id());

        InputValidationResult result = service.validateCurrentSelection();
        assertTrue(result.processingAllowed(), result.errorMessages().toString());
        assertEquals(FileValidationStatus.READY_WITH_WARNINGS, m.status());
        assertTrue(result.hasCode(ValidationIssueCode.MF4_RECOVERY_REQUIRED)
                || !result.warningMessages().isEmpty());
    }

    @Test
    void unFinMfSignature_readyWithWarningsAndProcessable() throws Exception {
        Path mf4 = tempDir.resolve("unfinmf-sig.mf4");
        writeUnFinMfSigned(mf4, 0, List.of(DesktopFixtures.busRecord(1L, 0x100, DesktopFixtures.enginePayload(2500, 50, 1))), 0x25);
        DesktopAppService service = newApp();
        WorkspaceFile m = service.addPath(mf4);
        WorkspaceFile d = service.addPath(dbc);
        service.selectMeasurement(m.id());
        service.selectDefinition(d.id());

        InputValidationResult result = service.validateCurrentSelection();
        assertTrue(result.processingAllowed(), result.errorMessages().toString());
        assertTrue(m.status().processingAllowed());
        assertEquals(FileValidationStatus.READY_WITH_WARNINGS, m.status());
    }

    @Test
    void corruptNonRecoverable_invalidAndNotProcessable() throws Exception {
        Path mf4 = TestMeasurementFixtures.recognizedButCorruptMf4(tempDir.resolve("corrupt.mf4"));
        DesktopAppService service = newApp();
        WorkspaceFile m = service.addPath(mf4);
        assertEquals(FileKind.MEASUREMENT_MF4, m.kind());
        WorkspaceFile d = service.addPath(dbc);
        service.selectMeasurement(m.id());
        service.selectDefinition(d.id());

        InputValidationResult result = service.validateCurrentSelection();
        assertFalse(result.processingAllowed());
        assertEquals(FileValidationStatus.INVALID, m.status());
        assertTrue(result.hasCode(ValidationIssueCode.MF4_INVALID));
        assertFalse(result.errorMessages().stream().anyMatch(s -> s.toLowerCase().contains("select a measurement")));
    }

    @Test
    void nonMdfRenamedMf4_isUnsupportedAndNotAdded() throws Exception {
        Path mf4 = tempDir.resolve("fake.mf4");
        Files.writeString(mf4, "HELLO WORLD NOT MDF FILE CONTENT");
        DesktopAppService service = newApp();
        WorkspaceFile m = service.addPath(mf4);
        assertEquals(FileKind.UNKNOWN, m.kind());
        assertEquals(FileValidationStatus.UNSUPPORTED, m.status());
        assertTrue(m.validationMessage().toLowerCase().contains("could not determine"), m.validationMessage());
        assertTrue(service.workspace().measurementFiles().isEmpty());

        InputValidationResult result = service.validateCurrentSelection();
        assertFalse(result.processingAllowed());
        assertFalse(result.hasCode(ValidationIssueCode.MF4_INVALID));
    }

    @Test
    void measurementPresentButInvalid_doesNotSaySelectMeasurement() throws Exception {
        Path mf4 = TestMeasurementFixtures.recognizedButCorruptMf4(tempDir.resolve("bad.mf4"));
        DesktopAppService service = newApp();
        WorkspaceFile m = service.addPath(mf4);
        assertEquals(FileKind.MEASUREMENT_MF4, m.kind());
        service.selectMeasurement(m.id());

        InputValidationResult result = service.validateCurrentSelection();
        assertFalse(result.processingAllowed());
        assertEquals(FileValidationStatus.INVALID, m.status());
        assertTrue(result.hasCode(ValidationIssueCode.MF4_INVALID));
        assertFalse(result.errorMessages().stream()
                .anyMatch(msg -> msg.toLowerCase().contains("select a measurement file (asc or mf4)")));
    }

    @Test
    void oneValidMf4AndOneValidDbc_autoAssociatesAndPasses() throws Exception {
        Path mf4 = DesktopFixtures.writeFinalizedMf4(tempDir.resolve("pair.mf4"));
        DesktopAppService service = newApp();
        service.addPath(mf4);
        service.addPath(dbc);
        // no explicit select — singleton auto-association
        InputValidationResult result = service.validateCurrentSelection();
        assertTrue(result.processingAllowed(), result.errorMessages().toString());
        assertTrue(service.workspace().selectedMeasurement().isPresent());
        assertTrue(service.workspace().selectedDefinition().isPresent());
        assertEquals(
                service.workspace().selectedDefinition().orElseThrow().id(),
                service.workspace().selectedMeasurement().orElseThrow().associatedDefinitionId()
        );
    }

    @Test
    void missingDbc_reportsDbcSpecificError() throws Exception {
        Path mf4 = DesktopFixtures.writeFinalizedMf4(tempDir.resolve("need-dbc.mf4"));
        DesktopAppService service = newApp();
        WorkspaceFile m = service.addPath(mf4);
        service.selectMeasurement(m.id());

        InputValidationResult result = service.validateCurrentSelection();
        assertFalse(result.processingAllowed());
        assertTrue(result.hasCode(ValidationIssueCode.MISSING_DBC));
        assertTrue(result.errorMessages().stream().anyMatch(s -> s.toLowerCase().contains("dbc")));
        assertFalse(result.hasCode(ValidationIssueCode.NO_MEASUREMENT_FILE_SELECTED));
    }

    @Test
    @EnabledIf("demoDtPresent")
    void realDemoDtUnFinMf_isProcessableWithWarnings() throws Exception {
        Path mf4 = demoDtPath();
        DesktopAppService service = newApp();
        WorkspaceFile m = service.addPath(mf4);
        WorkspaceFile d = service.addPath(dbc);
        service.selectMeasurement(m.id());
        service.selectDefinition(d.id());

        InputValidationResult result = service.validateCurrentSelection();
        assertTrue(result.processingAllowed(), result.errorMessages().toString());
        assertTrue(m.status().processingAllowed());
        assertEquals(FileValidationStatus.READY_WITH_WARNINGS, m.status());
    }

    static boolean demoDtPresent() {
        Path path = demoDtPath();
        try {
            // External DriveTech fixture; skip when absent or truncated stub (<1 KiB is not a real Demo_DT).
            return Files.isRegularFile(path) && Files.size(path) > 1024L;
        } catch (Exception e) {
            return false;
        }
    }

    static Path demoDtPath() {
        return Path.of("C:\\Users\\ADMIN\\Desktop\\DriveTech\\Devendra\\Prasad Sangpal\\Demo_DT_00000968_00000001-63C790AE.MF4");
    }

    private DesktopAppService newApp() {
        return new DesktopAppService(new DesktopSession(registry));
    }

    private static void writeUnFinMfSigned(Path file, long cycles, List<byte[]> records, int unfin)
            throws Exception {
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("UnFinMF ", "4.11", 411, unfin);
            long hd = b.writeHd(0, 0, 0);
            long time = b.writeCn(0, b.writeTx("Timestamp"), 0, 2, 1, 0, 15, 0, 64, 0);
            long data = b.writeCn(time, b.writeTx("DataBytes"), 0, 0, 0, 9, 7, 0, 64, 0);
            long dlc = b.writeCn(data, b.writeTx("DLC"), 0, 0, 0, 0, 6, 0, 8, 0);
            long id = b.writeCn(dlc, b.writeTx("CAN_ID"), 0, 0, 0, 0, 2, 0, 11, 0);
            long dir = b.writeCn(id, b.writeTx("Direction"), 0, 0, 0, 0, 1, 0, 8, 0);
            long head = b.writeCn(dir, b.writeTx("BusChannel"), 0, 0, 0, 0, 0, 0, 8, 0);
            long cg = b.writeCg(0, head, 1, cycles, 0, 23, 0);
            byte[] all = concat(records);
            long dt = b.writeDt(all);
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
    }

    private static byte[] concat(List<byte[]> records) {
        int total = records.stream().mapToInt(r -> r.length).sum();
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] r : records) {
            System.arraycopy(r, 0, out, pos, r.length);
            pos += r.length;
        }
        return out;
    }
}
