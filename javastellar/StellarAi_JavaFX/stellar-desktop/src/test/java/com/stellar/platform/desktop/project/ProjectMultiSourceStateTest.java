package com.stellar.platform.desktop.project;

import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.processing.ParserRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MF-2: multi measurement/definition project input state.
 * No multi-file parsing orchestration is exercised here.
 */
class ProjectMultiSourceStateTest {

    @TempDir
    Path tempDir;

    @Test
    void fourMeasurementSourcesHaveDistinctIdsAndSupportRemoval() throws Exception {
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        Path run01 = touch(tempDir.resolve("Run_01.mf4"));
        Path run02 = touch(tempDir.resolve("Run_02.mf4"));
        Path run03 = touch(tempDir.resolve("Run_03.mf4"));
        Path run04 = touch(tempDir.resolve("Run_04.mf4"));

        ProjectFilesAddResult result = service.addMeasurementPaths(
                List.of(run01, run02, run03, run04), DesktopAppService.PlatformUi.NOOP);

        assertEquals(4, result.addedCount());
        assertEquals(4, service.workspace().measurementSourceCount());

        Set<String> ids = new HashSet<>();
        for (WorkspaceFile file : service.workspace().measurementFiles()) {
            assertTrue(ids.add(file.projectSourceId().value()));
            assertEquals("Added", file.projectInputStatusLabel());
        }
        assertEquals(4, ids.size());

        WorkspaceFile run03File = service.workspace().measurementFiles().stream()
                .filter(f -> f.fileName().equals("Run_03.mf4"))
                .findFirst()
                .orElseThrow();
        ProjectSourceId removedId = run03File.projectSourceId();
        assertTrue(service.workspace().removeMeasurement(removedId));
        assertEquals(3, service.workspace().measurementSourceCount());
        assertTrue(service.workspace().measurementFiles().stream()
                .noneMatch(f -> f.projectSourceId().equals(removedId)));
        assertTrue(service.workspace().measurementFiles().stream()
                .anyMatch(f -> f.fileName().equals("Run_01.mf4")));
        assertTrue(service.workspace().measurementFiles().stream()
                .anyMatch(f -> f.fileName().equals("Run_04.mf4")));
    }

    @Test
    void twoDefinitionsHaveDistinctIdsAndSupportRemoval() throws Exception {
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        Path powertrain = touch(tempDir.resolve("Powertrain.dbc"));
        Path body = touch(tempDir.resolve("Body.dbc"));

        ProjectFilesAddResult result = service.addDefinitionPaths(List.of(powertrain, body));
        assertEquals(2, result.addedCount());
        assertEquals(2, service.workspace().definitionSourceCount());

        Set<String> ids = new HashSet<>();
        for (WorkspaceFile file : service.workspace().definitionFiles()) {
            assertTrue(ids.add(file.definitionSourceId().value()));
        }
        assertEquals(2, ids.size());

        WorkspaceFile bodyFile = service.workspace().definitionFiles().stream()
                .filter(f -> f.fileName().equals("Body.dbc"))
                .findFirst()
                .orElseThrow();
        DefinitionSourceId bodyId = bodyFile.definitionSourceId();
        assertTrue(service.workspace().removeDefinition(bodyId));
        assertEquals(1, service.workspace().definitionSourceCount());
        assertEquals("Powertrain.dbc", service.workspace().definitionFiles().getFirst().fileName());
    }

    @Test
    void windows1252DbcIsAddedWithAWarning() throws Exception {
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        Path dbc = tempDir.resolve("GenericQuote.dbc");
        byte[] raw = """
                VERSION ""
                BU_: NodeA
                BO_ 256 Engine: 8 NodeA
                 SG_ Speed : 0|8@1+ (1,0) [0|0] "" NodeA
                CM_ SG_ 256 Speed "position \u0000resume";
                """.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == 0) {
                raw[i] = (byte) 0x93;
                break;
            }
        }
        Files.write(dbc, raw);

        ProjectFilesAddResult result = service.addDefinitionPaths(List.of(dbc));

        assertEquals(1, result.addedCount(), result.summaryMessage());
        assertEquals(0, result.skippedCount(), result.summaryMessage());
        assertEquals(1, result.warningCount(), result.summaryMessage());
        assertTrue(result.summaryMessage().contains(DbcParser.WINDOWS_1252_WARNING_PREFIX), result.summaryMessage());
        assertTrue(result.warningMessages().getFirst().contains("byte 0x93"), result.warningMessages().toString());
        WorkspaceFile added = result.added().getFirst();
        assertEquals(FileValidationStatus.READY_WITH_WARNINGS, added.status());
        assertTrue(added.validationMessage().startsWith(DbcParser.WINDOWS_1252_WARNING_PREFIX));
        assertTrue(added.status().processingAllowed());
    }

    @Test
    void undefinedWindows1252DbcIsSkipped() throws Exception {
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        Path dbc = tempDir.resolve("Undefined.dbc");
        byte[] raw = "VERSION \"\u0000\"\r\nBU_: NodeA\r\n".getBytes(StandardCharsets.US_ASCII);
        raw[9] = (byte) 0x81;
        Files.write(dbc, raw);

        ProjectFilesAddResult result = service.addDefinitionPaths(List.of(dbc));

        assertEquals(0, result.addedCount(), result.summaryMessage());
        assertEquals(1, result.skippedCount(), result.summaryMessage());
        assertEquals(0, result.warningCount(), result.summaryMessage());
        assertTrue(result.summaryMessage().contains("not a defined Windows-1252"), result.summaryMessage());
    }

    @Test
    void sameFilenameDifferentPathsAreDistinctSources() throws Exception {
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        Path a = touch(tempDir.resolve("A").resolve("run.mf4"));
        Path b = touch(tempDir.resolve("B").resolve("run.mf4"));

        ProjectFilesAddResult result = service.addMeasurementPaths(
                List.of(a, b), DesktopAppService.PlatformUi.NOOP);
        assertEquals(2, result.addedCount());
        assertEquals(2, service.workspace().measurementSourceCount());
        List<WorkspaceFile> files = service.workspace().measurementFiles();
        assertEquals("run.mf4", files.get(0).fileName());
        assertEquals("run.mf4", files.get(1).fileName());
        assertNotEquals(files.get(0).projectSourceId(), files.get(1).projectSourceId());
        assertNotEquals(files.get(0).path(), files.get(1).path());
    }

    @Test
    void samePhysicalPathAddedTwiceIsDuplicate() throws Exception {
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        Path run = touch(tempDir.resolve("Run_01.mf4"));

        ProjectFilesAddResult first = service.addMeasurementPaths(
                List.of(run), DesktopAppService.PlatformUi.NOOP);
        ProjectFilesAddResult second = service.addMeasurementPaths(
                List.of(run), DesktopAppService.PlatformUi.NOOP);

        assertEquals(1, first.addedCount());
        assertEquals(0, second.addedCount());
        assertEquals(1, second.duplicateCount());
        assertTrue(second.duplicateMessages().getFirst().contains("already added"));
        assertEquals(1, service.workspace().measurementSourceCount());
    }

    @Test
    void unsupportedFileIsSkippedWithoutRejectingValidPeers() throws Exception {
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        Path ok1 = touch(tempDir.resolve("Run_01.mf4"));
        Path bad = touch(tempDir.resolve("broken.xyz"));
        Path ok2 = touch(tempDir.resolve("Run_02.mf4"));

        ProjectFilesAddResult result = service.addMeasurementPaths(
                List.of(ok1, bad, ok2), DesktopAppService.PlatformUi.NOOP);
        assertEquals(2, result.addedCount());
        assertEquals(1, result.skippedCount());
        assertEquals(2, service.workspace().measurementSourceCount());
    }

    @Test
    void singleMeasurementAndDbcRemainCompatible() throws Exception {
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        Path mf4 = touch(tempDir.resolve("single.mf4"));
        Path dbc = touch(tempDir.resolve("single.dbc"));

        service.addMeasurementPaths(List.of(mf4), DesktopAppService.PlatformUi.NOOP);
        service.addDefinitionPaths(List.of(dbc));

        assertEquals(1, service.workspace().measurementSourceCount());
        assertEquals(1, service.workspace().definitionSourceCount());
        assertTrue(service.workspace().selectedMeasurement().isPresent());
        assertTrue(service.workspace().selectedDefinition().isPresent());
        assertEquals(
                service.workspace().selectedDefinition().orElseThrow().id(),
                service.workspace().selectedMeasurement().orElseThrow().associatedDefinitionId());
    }

    @Test
    void unsupportedDlcIsSkippedWhileValidPeersAndWarningsStillImport() throws Exception {
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        Path oversized = tempDir.resolve("oversized.dbc");
        Files.writeString(oversized, """
                VERSION ""
                BU_: N
                BO_ 2024 OBD2_DTC_HEADER: 100 Vector__XXX
                 SG_ A : 0|8@1+ (1,0) [0|0] "" Vector__XXX
                """);
        Path classic = tempDir.resolve("classic.dbc");
        Files.writeString(classic, """
                VERSION ""
                BU_: N
                BO_ 256 Msg: 8 N
                 SG_ Speed : 0|8@1+ (0.25,0) [0|0] "" N
                """);
        Path canFd = tempDir.resolve("canfd.dbc");
        Files.writeString(canFd, """
                VERSION ""
                BU_: N
                BO_ 512 CanFdSensors: 16 N
                 SG_ A : 0|8@1+ (1,0) [0|0] "" N
                """);
        Path partial = tempDir.resolve("partial.dbc");
        Files.writeString(partial, """
                VERSION ""
                BU_: N
                BO_ 256 M: 8 N
                 SG_ Mode : 0|8@1+ (1,0) [0|0] "" N
                VAL_ 256 Mode 0 "Off"
                1 "On"
                BO_ 257 Next: 8 N
                 SG_ Keep : 0|8@1+ (1,0) [0|0] "" N
                """);
        List<Path> batch = new java.util.ArrayList<>();
        batch.add(oversized);
        batch.add(classic);
        batch.add(canFd);
        batch.add(partial);
        Path customer = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\Multiple DBC's to match\\can1-11-bit-OBD2-isotp-DTCs-header-v4.0.dbc");
        if (Files.isRegularFile(customer)) {
            batch.add(customer);
        }

        ProjectFilesAddResult result = service.addDefinitionPaths(batch);

        assertEquals(3, result.addedCount(), result.summaryMessage());
        assertTrue(result.summaryMessage().contains("Unsupported DLC 100"), result.summaryMessage());
        assertTrue(result.summaryMessage().contains("oversized.dbc"), result.summaryMessage());
        assertTrue(result.summaryMessage().contains("id 2024"), result.summaryMessage());
        assertTrue(result.summaryMessage().contains("not truncated"), result.summaryMessage());
        if (Files.isRegularFile(customer)) {
            assertTrue(result.summaryMessage().contains("can1-11-bit-OBD2-isotp-DTCs-header-v4.0.dbc"),
                    result.summaryMessage());
            assertEquals(2, result.skippedCount(), result.summaryMessage());
        } else {
            assertEquals(1, result.skippedCount(), result.summaryMessage());
        }
        assertEquals(FileValidationStatus.READY, statusOf(result, "classic.dbc"));
        assertEquals(FileValidationStatus.READY, statusOf(result, "canfd.dbc"));
        WorkspaceFile warned = result.added().stream()
                .filter(file -> file.fileName().equals("partial.dbc"))
                .findFirst()
                .orElseThrow();
        assertEquals(FileValidationStatus.READY_WITH_WARNINGS, warned.status());
        assertTrue(warned.validationMessage().contains(DbcParser.MULTILINE_VAL_WARNING_PREFIX),
                warned.validationMessage());
        assertTrue(warned.status().processingAllowed());
        assertTrue(result.summaryMessage().contains("1 value description was not loaded"), result.summaryMessage());

        Path measurement = touch(tempDir.resolve("peer.mf4"));
        service.addMeasurementPaths(List.of(measurement), DesktopAppService.PlatformUi.NOOP);
        service.selectDefinition(warned.id());
        var validation = service.validateCurrentSelection();
        WorkspaceFile afterValidation = service.workspace().definitionFiles().stream()
                .filter(file -> file.fileName().equals("partial.dbc"))
                .findFirst()
                .orElseThrow();
        assertEquals(FileValidationStatus.READY_WITH_WARNINGS, afterValidation.status());
        assertTrue(afterValidation.validationMessage().contains(DbcParser.MULTILINE_VAL_WARNING_PREFIX),
                afterValidation.validationMessage());
        assertTrue(validation.warningMessages().stream()
                .anyMatch(message -> message.contains(DbcParser.MULTILINE_VAL_WARNING_PREFIX)));
        assertTrue(validation.hasCode(com.stellar.platform.desktop.validation.ValidationIssueCode.DBC_MULTILINE_VAL));
    }

    private static FileValidationStatus statusOf(ProjectFilesAddResult result, String name) {
        return result.added().stream()
                .filter(file -> file.fileName().equals(name))
                .findFirst()
                .orElseThrow()
                .status();
    }

    private static Path touch(Path path) throws Exception {
        Files.createDirectories(path.getParent() == null ? path.getFileSystem().getPath(".") : path.getParent());
        if (!Files.exists(path)) {
            String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (name.endsWith(".dbc")) {
                Files.writeString(path, """
                        VERSION ""
                        BO_ 256 Msg: 8 Vector__XXX
                         SG_ Sig : 0|8@1+ (1,0) [0|0] "" Vector__XXX
                        """);
            } else if (name.endsWith(".mf4") || name.endsWith(".asc") || name.endsWith(".blf")
                    || name.endsWith(".dat") || name.endsWith(".log")) {
                com.stellar.platform.desktop.support.TestMeasurementFixtures.writeStubMeasurement(path);
            } else {
                Files.writeString(path, "x");
            }
        }
        return path;
    }
}
