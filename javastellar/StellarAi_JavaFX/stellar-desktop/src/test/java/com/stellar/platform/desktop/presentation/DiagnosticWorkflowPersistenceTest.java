package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.diagnostics.DiagnosticFinding;
import com.stellar.platform.analysis.diagnostics.DiagnosticRunResult;
import com.stellar.platform.analysis.model.AnalysisSeverity;
import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.EngineeringFinding;
import com.stellar.platform.analysis.model.FindingType;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.rules.DiagnosticRuleDefinition;
import com.stellar.platform.analysis.rules.DiagnosticRulePack;
import com.stellar.platform.analysis.rules.GenericEngineeringPack;
import com.stellar.platform.analysis.rules.RuleCategory;
import com.stellar.platform.analysis.rules.RuleOrigin;
import com.stellar.platform.analysis.rules.RulePackJson;
import com.stellar.platform.analysis.workflow.FindingReviewState;
import com.stellar.platform.analysis.workflow.PackImportPreview;
import com.stellar.platform.analysis.workflow.ProjectCustomPack;
import com.stellar.platform.analysis.workflow.RuleTemplates;
import com.stellar.platform.analysis.workflow.StaleRunReason;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.storage.metadata.DiagnosticRunMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticWorkflowPersistenceTest {

    @TempDir Path tempDir;
    private DesktopAppService service;
    private SqliteProjectMetadataStore store;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private void openService() {
        System.setProperty("stellarai.home", tempDir.resolve("home").toString());
        Path db = tempDir.resolve("meta.db");
        System.setProperty("stellarai.metadata.db", db.toString());
        store = new SqliteProjectMetadataStore(db);
        service = new DesktopAppService(new DesktopSession(), store);
    }

    @Test
    void customRuleBindingsReviewAndRunRestoreWithoutReprocess() {
        openService();
        service.newProject("Diag Persist");
        String id = service.currentProjectId();
        DiagnosticRuleDefinition rule = RuleTemplates.sustainedThreshold(
                "Sustained High Coolant Temp", "COOLANT_TEMPERATURE", 105, "°C", 10, 3,
                AnalysisSeverity.WARNING, RuleCategory.THERMAL);
        service.saveCustomDiagnosticRule(rule);
        SignalCatalogKey coolant = new SignalCatalogKey(
                "CoolantTemperature", SignalSourceType.DBC_CAN, "ECM", 0x600, 1, "run.mf4", "veh.dbc");
        service.persistDiagnosticBindings(ProjectCustomPack.PACK_ID, Map.of("COOLANT_TEMPERATURE", coolant));
        DiagnosticFinding finding = finding("f1", rule.ruleId(), rule.version());
        DiagnosticRunResult result = new DiagnosticRunResult(
                "run-1", AnalysisStatus.COMPLETED, 1, 1, 0, List.of(), List.of(finding),
                TimeWindow.of(1, 2), Instant.now(), Instant.now(), "", 0, "1");
        service.engineeringDiagnostics().restoreLast(result, ProjectCustomPack.PACK_ID);
        store.upsertDiagnosticRun(new DiagnosticRunMetadata(
                "run-1", id, ProjectCustomPack.PACK_ID, ProjectCustomPack.PACK_VERSION,
                "COMPLETED", "VIEWPORT", 1L, 2L, 1, 1, 0, "k", 0L, "1", "", Instant.now(), Instant.now()));
        store.replaceDiagnosticFindings("run-1", List.of(
                new com.stellar.platform.storage.metadata.DiagnosticFindingRow(
                        "f1", "run-1", id, rule.ruleId(), rule.version(), "WARNING", "THERMAL",
                        1L, 2L, "hot", "details", "COOLANT=107", Instant.now())));
        service.saveDiagnosticFindingReview("f1", FindingReviewState.REVIEWED, "Occurred during aggressive brake test.");
        assertTrue(service.hasUnsavedChanges());
        assertTrue(service.saveProject().saved());
        service.closeProject();
        service.shutdown();
        service = null;
        store.close();

        store = new SqliteProjectMetadataStore(tempDir.resolve("meta.db"));
        service = new DesktopAppService(new DesktopSession(), store);
        assertTrue(service.openSavedProject(id).opened());
        DiagnosticRulePack custom = service.projectCustomRulePack();
        assertEquals(1, custom.rules().size());
        assertEquals("Sustained High Coolant Temp", custom.rules().getFirst().name());
        assertEquals(coolant, service.loadDiagnosticBindings(ProjectCustomPack.PACK_ID).get("COOLANT_TEMPERATURE"));
        assertEquals("REVIEWED", service.loadDiagnosticReviews().get("f1").reviewState());
        assertEquals("Occurred during aggressive brake test.", service.loadDiagnosticReviews().get("f1").engineerNote());
        assertEquals(1, service.diagnosticRunHistory().size());
    }

    @Test
    void importConflictDoesNotOverwrite() {
        openService();
        service.newProject("Import Conflict");
        String json = RulePackJson.write(GenericEngineeringPack.pack());
        service.importDiagnosticPack(json);
        PackImportPreview preview = service.previewDiagnosticPackImport(json);
        assertFalse(preview.importable());
        assertThrows(IllegalArgumentException.class, () -> service.importDiagnosticPack(json));
    }

    @Test
    void staleRunReconstructsWithWarning() {
        openService();
        service.newProject("Stale Run");
        String id = service.currentProjectId();
        store.upsertDiagnosticRun(new DiagnosticRunMetadata(
                "stale-1", id, GenericEngineeringPack.PACK_ID, "1.0",
                "INVALIDATED", "FULL", 1L, 2L, 1, 1, 0, "k", 0L, "1", "", Instant.now(), Instant.now()));
        store.replaceDiagnosticFindings("stale-1", List.of(
                new com.stellar.platform.storage.metadata.DiagnosticFindingRow(
                        "f-stale", "stale-1", id, "thermal.high-coolant", "1", "WARNING", "THERMAL",
                        1L, 2L, "hot", "d", "e", Instant.now())));
        DiagnosticRunResult restored = service.loadDiagnosticRun("stale-1");
        assertNotNull(restored);
        assertEquals(1, restored.findings().size());
        assertTrue(StaleRunReason.staleMessage(restored.message()));
    }

    @Test
    void builtInRulesRemainImmutableOnSaveAttempt() {
        openService();
        service.newProject("Immutable");
        DiagnosticRuleDefinition builtIn = GenericEngineeringPack.coolant(Instant.now());
        assertEquals(RuleOrigin.BUILT_IN, builtIn.origin());
        assertThrows(IllegalArgumentException.class,
                () -> service.engineeringDiagnostics().saveCustomRule(
                        store, service.currentProjectId(), builtIn));
    }

    private static DiagnosticFinding finding(String id, String ruleId, String version) {
        EngineeringFinding f = new EngineeringFinding(
                id, "run-1", FindingType.DIAGNOSTIC, AnalysisSeverity.WARNING,
                List.of(), 1L, 2L, "hot", "details", Map.of(), Instant.now());
        return new DiagnosticFinding(f, "run-1", ruleId, version, RuleCategory.THERMAL,
                List.of(), List.of(), List.of());
    }
}
