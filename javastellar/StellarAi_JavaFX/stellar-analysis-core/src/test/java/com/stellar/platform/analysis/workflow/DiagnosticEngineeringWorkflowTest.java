package com.stellar.platform.analysis.workflow;

import com.stellar.platform.analysis.AutomotiveAnalysisFixtures;
import com.stellar.platform.analysis.diagnostics.DiagnosticFinding;
import com.stellar.platform.analysis.diagnostics.DiagnosticRunResult;
import com.stellar.platform.analysis.model.AnalysisSeverity;
import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.EngineeringFinding;
import com.stellar.platform.analysis.model.FindingType;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.rules.BindingState;
import com.stellar.platform.analysis.rules.CompareOp;
import com.stellar.platform.analysis.rules.DiagnosticRuleDefinition;
import com.stellar.platform.analysis.rules.DiagnosticRulePack;
import com.stellar.platform.analysis.rules.GenericEngineeringPack;
import com.stellar.platform.analysis.rules.RuleCategory;
import com.stellar.platform.analysis.rules.RuleCondition;
import com.stellar.platform.analysis.rules.RuleOrigin;
import com.stellar.platform.analysis.rules.RulePackJson;
import com.stellar.platform.analysis.rules.RuleParameter;
import com.stellar.platform.analysis.rules.SignalBinding;
import com.stellar.platform.analysis.rules.UnitRegistry;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticEngineeringWorkflowTest {

    @Test
    void readinessMissingAmbiguousAndReady() {
        DiagnosticRuleDefinition rule = GenericEngineeringPack.coolant(Instant.EPOCH);
        assertEquals(RuleReadiness.DISABLED, RuleReadinessEvaluator.evaluate(rule, Map.of(), false));
        assertEquals(RuleReadiness.MISSING_INPUT, RuleReadinessEvaluator.evaluate(rule, Map.of(), true));
        SignalCatalogEntry a = entry(AutomotiveAnalysisFixtures.COOLANT, "°C");
        SignalCatalogKey extra = AutomotiveAnalysisFixtures.key("CoolantTemperature", "ECM2", 0x601);
        SignalCatalogEntry b = entry(extra, "°C");
        Map<String, SignalBinding> ambiguous = BindingWorkspace.resolveShared(
                packOf(rule), List.of(a, b), Map.of());
        assertEquals(RuleReadiness.BINDING_REQUIRED,
                RuleReadinessEvaluator.evaluate(rule, ambiguous, true));
        Map<String, SignalBinding> bound = BindingWorkspace.resolveShared(
                packOf(rule), List.of(a), Map.of());
        assertEquals(RuleReadiness.READY, RuleReadinessEvaluator.evaluate(rule, bound, true));
    }

    @Test
    void staleBindingDoesNotSubstituteSameName() {
        DiagnosticRuleDefinition rule = GenericEngineeringPack.coolant(Instant.EPOCH);
        SignalCatalogKey gone = AutomotiveAnalysisFixtures.key("CoolantTemperature", "GONE", 0x1);
        SignalCatalogEntry remaining = entry(AutomotiveAnalysisFixtures.COOLANT, "°C");
        Map<String, SignalBinding> resolved = BindingWorkspace.resolveShared(
                packOf(rule), List.of(remaining), Map.of("COOLANT_TEMPERATURE", gone));
        assertEquals(BindingState.STALE, resolved.get("COOLANT_TEMPERATURE").state());
        assertTrue(BindingWorkspace.stale(resolved.get("COOLANT_TEMPERATURE")));
        assertNotEquals(remaining.key(), resolved.get("COOLANT_TEMPERATURE").key());
    }

    @Test
    void sharedRoleBindsOnce() {
        DiagnosticRuleDefinition a = RuleTemplates.sustainedThreshold(
                "Speed A", "VEHICLE_SPEED", 80, "km/h", 1, 1, AnalysisSeverity.WARNING, RuleCategory.VEHICLE_DYNAMICS);
        DiagnosticRuleDefinition b = RuleTemplates.sustainedThreshold(
                "Speed B", "VEHICLE_SPEED", 100, "km/h", 1, 1, AnalysisSeverity.WARNING, RuleCategory.VEHICLE_DYNAMICS);
        DiagnosticRulePack pack = ProjectCustomPack.of(List.of(a, b));
        assertEquals(1, BindingWorkspace.uniqueRoles(pack).size());
        SignalCatalogKey speed = AutomotiveAnalysisFixtures.key("VEHICLE_SPEED", "ABS", 0x200);
        Map<String, SignalBinding> resolved = BindingWorkspace.resolveShared(
                pack, List.of(entry(speed, "km/h")), Map.of());
        assertTrue(resolved.get("VEHICLE_SPEED").bound());
    }

    @Test
    void ambiguousChooserListsCandidatesWithoutAutoSelect() {
        DiagnosticRuleDefinition rule = GenericEngineeringPack.coolant(Instant.EPOCH);
        SignalCatalogKey extra = AutomotiveAnalysisFixtures.key("CoolantTemperature", "ECM2", 0x601);
        List<BindingCandidate> candidates = BindingWorkspace.candidates(
                rule.inputs().getFirst(),
                List.of(entry(AutomotiveAnalysisFixtures.COOLANT, "°C"), entry(extra, "°C")));
        assertEquals(2, candidates.size());
        assertEquals("CoolantTemperature", candidates.getFirst().signalName());
        assertFalse(candidates.getFirst().source().isBlank() || candidates.get(1).key().equals(candidates.getFirst().key()));
    }

    @Test
    void templatesGeneratePhase5Trees() {
        DiagnosticRuleDefinition sustained = RuleTemplates.sustainedThreshold(
                "Sustained High Coolant Temp", "COOLANT_TEMPERATURE", 105, "°C", 10, 3,
                AnalysisSeverity.WARNING, RuleCategory.THERMAL);
        assertTrue(sustained.condition() instanceof RuleCondition.Threshold);
        assertEquals(RuleOrigin.PROJECT_CUSTOM, sustained.origin());
        DiagnosticRuleDefinition delta = RuleTemplates.difference(
                "Torque gap", "REQUESTED_TORQUE", "ACTUAL_TORQUE", 100, "Nm", 0.5, AnalysisSeverity.WARNING);
        assertTrue(delta.condition() instanceof RuleCondition.Delta);
        DiagnosticRuleDefinition delay = RuleTemplates.responseDelay(
                "Brake delay", "BRAKE_PEDAL", "OFF", "ON", "BRAKE_PRESSURE", 20, "bar", 500,
                AnalysisSeverity.WARNING);
        assertTrue(delay.condition() instanceof RuleCondition.Sequence);
        DiagnosticRuleDefinition state = RuleTemplates.stateAndCondition(
                "Brake+speed", "BRAKE_PEDAL", "PRESSED", "VEHICLE_SPEED", CompareOp.GT, 80, "km/h",
                500, AnalysisSeverity.WARNING);
        assertTrue(state.condition() instanceof RuleCondition.Logical);
        DiagnosticRuleDefinition rate = RuleTemplates.rateLimit(
                "Steer rate", "STEERING_ANGLE", 400, "deg/s", AnalysisSeverity.OBSERVATION);
        assertTrue(rate.condition() instanceof RuleCondition.Rate);
        DiagnosticRuleDefinition range = RuleTemplates.operatingRange(
                "Band", "COOLANT_TEMPERATURE", 80, 105, "°C", true, AnalysisSeverity.INFO);
        assertTrue(range.condition() instanceof RuleCondition.Range);
    }

    @Test
    void parameterValidationRejectsNonFinite() {
        RuleParameter p = new RuleParameter("threshold", "NUMBER", "°C", 105, 80d, 150d, "");
        assertEquals(105, p.validate(105));
        assertThrows(IllegalArgumentException.class, () -> p.validate(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> p.validate(200));
        assertThrows(IllegalArgumentException.class, () -> RuleTemplates.sustainedThreshold(
                "x", "R", Double.POSITIVE_INFINITY, "°C", 10, 3, AnalysisSeverity.WARNING, RuleCategory.THERMAL));
    }

    @Test
    void unitAwareAuthoringStoresThresholdAndUnit() {
        DiagnosticRuleDefinition rule = RuleTemplates.sustainedThreshold(
                "Hot", "COOLANT_TEMPERATURE", 80, "km/h", 10, 3, AnalysisSeverity.WARNING, RuleCategory.CUSTOM);
        RuleCondition.Threshold t = (RuleCondition.Threshold) rule.condition();
        assertEquals(80, t.enter());
        assertEquals("km/h", t.unit());
        assertTrue(UnitRegistry.compatible("km/h", "km/h"));
    }

    @Test
    void previewAndValidationAreStructured() {
        DiagnosticRuleDefinition rule = RuleTemplates.sustainedThreshold(
                "Hot", "COOLANT_TEMPERATURE", 105, "°C", 10, 3, AnalysisSeverity.WARNING, RuleCategory.THERMAL);
        String preview = RulePreviewFormatter.preview(rule, Map.of());
        assertTrue(preview.contains("IF"));
        assertTrue(preview.contains("COOLANT_TEMPERATURE"));
        assertTrue(preview.contains("EXIT"));
        List<ValidationLine> lines = ValidationLine.forRule(rule, Map.of(
                "COOLANT_TEMPERATURE", SignalBinding.missing("COOLANT_TEMPERATURE")));
        assertTrue(lines.stream().anyMatch(l -> l.message().contains("Rule ID valid")));
        assertTrue(lines.stream().anyMatch(l -> !l.ok() && l.message().contains("COOLANT_TEMPERATURE")));
    }

    @Test
    void builtInCloneDoesNotMutateOriginal() {
        DiagnosticRuleDefinition builtIn = GenericEngineeringPack.coolant(Instant.EPOCH);
        DiagnosticRuleDefinition clone = builtIn.asProjectCustom("project.coolant");
        assertEquals(RuleOrigin.BUILT_IN, builtIn.origin());
        assertEquals(RuleOrigin.PROJECT_CUSTOM, clone.origin());
        assertEquals("thermal.high-coolant", builtIn.ruleId());
        assertEquals("project.coolant", clone.ruleId());
    }

    @Test
    void versionIncrementKeepsHistoricalIdentity() {
        DiagnosticRuleDefinition v1 = RuleTemplates.sustainedThreshold(
                "Hot", "COOLANT_TEMPERATURE", 105, "°C", 10, 3, AnalysisSeverity.WARNING, RuleCategory.THERMAL);
        DiagnosticRuleDefinition v2 = RuleTemplates.incrementVersion(v1);
        assertEquals(v1.ruleId(), v2.ruleId());
        assertNotEquals(v1.version(), v2.version());
        DiagnosticFinding historic = finding(v1.ruleId(), v1.version(), 1_000_000_000L);
        assertEquals(v1.version(), historic.ruleVersion());
        assertEquals("1", historic.ruleVersion());
    }

    @Test
    void customPackRoundTripPreservesLogicalAndRange() {
        DiagnosticRuleDefinition logical = RuleTemplates.stateAndCondition(
                "State", "BRAKE_PEDAL", "PRESSED", "VEHICLE_SPEED", CompareOp.GT, 80, "km/h",
                500, AnalysisSeverity.WARNING);
        DiagnosticRuleDefinition range = RuleTemplates.operatingRange(
                "Range", "COOLANT_TEMPERATURE", 80, 110, "°C", true, AnalysisSeverity.INFO);
        DiagnosticRulePack pack = ProjectCustomPack.of(List.of(logical, range));
        DiagnosticRulePack round = RulePackJson.read(RulePackJson.write(pack));
        assertEquals(2, round.rules().size());
        assertTrue(round.rules().getFirst().condition() instanceof RuleCondition.Logical);
        assertTrue(round.rules().get(1).condition() instanceof RuleCondition.Range);
    }

    @Test
    void importConflictsAreExplicit() {
        DiagnosticRulePack existing = GenericEngineeringPack.pack();
        PackImportPreview same = PackImportPreview.parse(RulePackJson.write(existing), existing);
        assertFalse(same.importable());
        assertTrue(same.conflicts().stream().anyMatch(c -> c.contains("Same pack ID")));
        PackImportPreview bad = PackImportPreview.parse("{\"schemaVersion\":99}", null);
        assertFalse(bad.importable());
    }

    @Test
    void findingFiltersArePresentationOnly() {
        List<DiagnosticFinding> findings = List.of(
                finding("thermal.high-coolant", "1", 10_000_000_000L, RuleCategory.THERMAL),
                finding("braking.response-delay", "1", 20_000_000_000L, RuleCategory.BRAKING));
        FindingListFilter filter = new FindingListFilter(AnalysisSeverity.WARNING, RuleCategory.THERMAL, "", "");
        assertEquals(1, filter.apply(findings).size());
        assertEquals(2, findings.size());
    }

    @Test
    void graphFocusAddsContextWithoutChangingMembership() {
        DiagnosticFinding finding = finding("r", "1", 125_000_000_000L, 128_000_000_000L);
        GraphFocusRange range = GraphFocusRange.around(finding, 5_000_000_000L);
        assertEquals(120_000_000_000L, range.startNanos());
        assertEquals(133_000_000_000L, range.endNanos());
    }

    @Test
    void runComparisonAndStaleReason() {
        DiagnosticFinding a = finding("r", "1", 1_000_000_000L);
        DiagnosticFinding b = finding("r", "1", 2_000_000_000L);
        DiagnosticRunResult runA = result(List.of(a, b));
        DiagnosticRunResult runB = result(List.of(b, finding("r", "1", 3_000_000_000L)));
        RunComparison cmp = RunComparison.compare(runA, runB);
        assertEquals(1, cmp.persistent().size());
        assertEquals(1, cmp.resolved().size());
        assertEquals(1, cmp.added().size());
        assertTrue(StaleRunReason.staleStatus("INVALIDATED"));
        assertTrue(StaleRunReason.staleMessage(StaleRunReason.MESSAGE));
    }

    @Test
    void largeRuleListPresentationDoesNotMaterialiseControls() {
        List<DiagnosticRuleDefinition> rules = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            rules.add(RuleTemplates.sustainedThreshold(
                    "Rule " + i, "COOLANT_TEMPERATURE", 100 + (i % 10), "°C", 10, 3,
                    AnalysisSeverity.WARNING, RuleCategory.THERMAL));
        }
        RuleListPresentationModel model = new RuleListPresentationModel();
        model.setSource(rules);
        model.setFilter(new RuleListFilter("Rule 12", null, null, null));
        assertEquals(500, model.sourceSize());
        assertTrue(model.viewSize() >= 1);
        assertTrue(model.viewSize() < 20);
        assertEquals(10, RuleListPresentationModel.window(model.view(), 0, 10).size());
    }

    @Test
    void reviewStateIsInvestigationOnly() {
        assertEquals(FindingReviewState.NEW, FindingReviewState.valueOf("NEW"));
        assertEquals(FindingReviewState.REVIEWED, FindingReviewState.valueOf("REVIEWED"));
        assertEquals(FindingReviewState.ACKNOWLEDGED, FindingReviewState.valueOf("ACKNOWLEDGED"));
    }

    private static DiagnosticRulePack packOf(DiagnosticRuleDefinition rule) {
        return new DiagnosticRulePack(1, "t", "1", "t", "", "1", List.of(rule));
    }

    private static SignalCatalogEntry entry(SignalCatalogKey key, String unit) {
        return new SignalCatalogEntry(key, unit, 20, 0L, 20_000_000_000L, 0d, 200d, "synth.mf4", "veh.dbc");
    }

    private static DiagnosticFinding finding(String ruleId, String version, long start) {
        return finding(ruleId, version, start, start + 1_000_000_000L, RuleCategory.THERMAL);
    }

    private static DiagnosticFinding finding(String ruleId, String version, long start, long end) {
        return finding(ruleId, version, start, end, RuleCategory.THERMAL);
    }

    private static DiagnosticFinding finding(String ruleId, String version, long start, RuleCategory category) {
        return finding(ruleId, version, start, start + 1_000_000_000L, category);
    }

    private static DiagnosticFinding finding(
            String ruleId, String version, long start, long end, RuleCategory category
    ) {
        EngineeringFinding f = new EngineeringFinding(
                "f-" + start, "run", FindingType.DIAGNOSTIC, AnalysisSeverity.WARNING,
                List.of(), start, end, "summary", "details", Map.of(), Instant.EPOCH);
        return new DiagnosticFinding(f, "run", ruleId, version, category,
                List.of(), List.of(), List.of());
    }

    private static DiagnosticRunResult result(List<DiagnosticFinding> findings) {
        return new DiagnosticRunResult("run", AnalysisStatus.COMPLETED, 1, 1, 0,
                List.of(), findings, TimeWindow.of(0, 10), Instant.EPOCH, Instant.EPOCH, "", 0, "1");
    }
}
