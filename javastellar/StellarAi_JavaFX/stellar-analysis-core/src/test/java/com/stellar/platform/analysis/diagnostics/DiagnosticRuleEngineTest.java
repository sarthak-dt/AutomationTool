package com.stellar.platform.analysis.diagnostics;

import com.stellar.platform.analysis.AutomotiveAnalysisFixtures;
import com.stellar.platform.analysis.model.AnalysisSeverity;
import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.TimeRangeSource;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.rules.BindingResolver;
import com.stellar.platform.analysis.rules.BindingState;
import com.stellar.platform.analysis.rules.CompareOp;
import com.stellar.platform.analysis.rules.DiagnosticRuleDefinition;
import com.stellar.platform.analysis.rules.DiagnosticRulePack;
import com.stellar.platform.analysis.rules.GenericEngineeringPack;
import com.stellar.platform.analysis.rules.LogicalOp;
import com.stellar.platform.analysis.rules.RuleCategory;
import com.stellar.platform.analysis.rules.RuleCondition;
import com.stellar.platform.analysis.rules.RuleEngineVersion;
import com.stellar.platform.analysis.rules.RuleEvaluationState;
import com.stellar.platform.analysis.rules.RuleInput;
import com.stellar.platform.analysis.rules.RuleOrigin;
import com.stellar.platform.analysis.rules.RulePackJson;
import com.stellar.platform.analysis.rules.RuleValidationIssue;
import com.stellar.platform.analysis.rules.RuleValidator;
import com.stellar.platform.analysis.rules.SignalBinding;
import com.stellar.platform.analysis.rules.TemporalConstraint;
import com.stellar.platform.analysis.rules.UnitRegistry;
import com.stellar.platform.analysis.source.InMemoryAnalysisSampleSource;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.parser.spi.SimpleCancellationToken;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticRuleEngineTest {

    private final DiagnosticRuleEngine engine = new DiagnosticRuleEngine();

    @Test
    void packAndRuleValidation() {
        DiagnosticRulePack pack = GenericEngineeringPack.pack();
        assertTrue(RuleValidator.validatePack(pack).isEmpty());
        String json = RulePackJson.write(pack);
        DiagnosticRulePack round = RulePackJson.read(json);
        assertEquals(pack.packId(), round.packId());
        assertEquals(pack.rules().size(), round.rules().size());
        assertThrows(IllegalArgumentException.class, () -> RulePackJson.read("{\"schemaVersion\":99}"));
        DiagnosticRuleDefinition bad = new DiagnosticRuleDefinition(
                "x", "1", "x", "", RuleCategory.CUSTOM, AnalysisSeverity.INFO, true,
                List.of(), List.of(),
                new RuleCondition.Logical(LogicalOp.AND, List.of()),
                List.of(), List.of(), RuleOrigin.PROJECT_CUSTOM, Instant.EPOCH, Instant.EPOCH);
        List<RuleValidationIssue> issues = RuleValidator.validateRule(bad);
        assertTrue(issues.stream().anyMatch(i -> "LOGIC".equals(i.code())));
    }

    @Test
    void bindingExplicitMissingAmbiguous() {
        DiagnosticRuleDefinition rule = GenericEngineeringPack.coolant(Instant.EPOCH);
        Map<String, SignalBinding> missing = BindingResolver.resolve(rule, List.of(), Map.of());
        assertEquals(BindingState.MISSING, missing.get("COOLANT_TEMPERATURE").state());
        Map<String, SignalBinding> bound = BindingResolver.resolve(
                rule, DiagnosticFixtures.workstationCatalog(), Map.of());
        assertEquals(BindingState.BOUND, bound.get("COOLANT_TEMPERATURE").state());
        SignalCatalogKey extra = AutomotiveAnalysisFixtures.key("CoolantTemperature", "ECM2", 0x601);
        List<SignalCatalogEntry> ambiguous = List.of(
                DiagnosticFixtures.entry(AutomotiveAnalysisFixtures.COOLANT, "°C"),
                DiagnosticFixtures.entry(extra, "°C"));
        assertEquals(BindingState.AMBIGUOUS, BindingResolver.resolve(rule, ambiguous, Map.of())
                .get("COOLANT_TEMPERATURE").state());
        Map<String, SignalBinding> explicit = BindingResolver.resolve(
                rule, ambiguous, Map.of("COOLANT_TEMPERATURE", extra));
        assertEquals(BindingState.BOUND, explicit.get("COOLANT_TEMPERATURE").state());
        assertEquals(extra, explicit.get("COOLANT_TEMPERATURE").key());
    }

    @Test
    void unitsCompatibleConversionIncompatible() {
        assertTrue(UnitRegistry.compatible("km/h", "m/s"));
        assertEquals(100d / 3.6d, UnitRegistry.convert(100d, "km/h", "m/s").orElseThrow(), 1e-9);
        assertEquals(221d, UnitRegistry.convert(105d, "°C", "°F").orElseThrow(), 1e-9);
        assertTrue(UnitRegistry.convert(1d, "bar", "rpm").isEmpty());
        DiagnosticRuleDefinition rule = GenericEngineeringPack.coolant(Instant.EPOCH);
        SignalCatalogKey key = AutomotiveAnalysisFixtures.COOLANT;
        Map<String, SignalBinding> incompatible = BindingResolver.resolve(
                rule, List.of(DiagnosticFixtures.entry(key, "km/h")), Map.of());
        assertEquals(BindingState.INCOMPATIBLE_UNIT, incompatible.get("COOLANT_TEMPERATURE").state());
    }

    @Test
    void coolantSustainedVsSpike() {
        DiagnosticRunResult hot = run(packOf(GenericEngineeringPack.coolant(Instant.EPOCH)),
                DiagnosticFixtures.thermalSustained(), DiagnosticFixtures.workstationCatalog());
        assertEquals(AnalysisStatus.COMPLETED, hot.status());
        assertEquals(1, hot.findings().size());
        DiagnosticFinding f = hot.findings().getFirst();
        assertTrue(f.finding().details().contains("107"));
        assertTrue(f.evidence().stream().anyMatch(e -> e.measured().getOrDefault("peak", 0d) >= 107));
        DiagnosticRunResult spike = run(packOf(GenericEngineeringPack.coolant(Instant.EPOCH)),
                DiagnosticFixtures.thermalSpike(), DiagnosticFixtures.workstationCatalog());
        assertTrue(spike.findings().isEmpty());
        assertEquals(RuleEvaluationState.PASS_NO_FINDING, spike.outcomes().getFirst().state());
    }

    @Test
    void torqueDeviationAndTracking() {
        DiagnosticRunResult bad = run(packOf(GenericEngineeringPack.torque(Instant.EPOCH)),
                DiagnosticFixtures.torqueDeviation(), DiagnosticFixtures.workstationCatalog());
        assertEquals(1, bad.findings().size());
        assertTrue(bad.findings().getFirst().evidence().getFirst().measured().get("peakAbsError") >= 60);
        DiagnosticRunResult ok = run(packOf(GenericEngineeringPack.torque(Instant.EPOCH)),
                DiagnosticFixtures.torqueTracking(), DiagnosticFixtures.workstationCatalog());
        assertTrue(ok.findings().isEmpty());
    }

    @Test
    void brakeDelayVsNormal() {
        DiagnosticRunResult delayed = run(packOf(GenericEngineeringPack.brake(Instant.EPOCH)),
                DiagnosticFixtures.brakeDelayed(), DiagnosticFixtures.workstationCatalog());
        assertEquals(1, delayed.findings().size());
        assertTrue(delayed.findings().getFirst().evidence().stream()
                .anyMatch(e -> e.measured().getOrDefault("observedDelayMs", 0d) > 500));
        DiagnosticRunResult normal = run(packOf(GenericEngineeringPack.brake(Instant.EPOCH)),
                DiagnosticFixtures.brakeNormal(), DiagnosticFixtures.workstationCatalog());
        assertTrue(normal.findings().isEmpty());
    }

    @Test
    void steeringRate() {
        DiagnosticRunResult result = run(packOf(GenericEngineeringPack.steering(Instant.EPOCH)),
                DiagnosticFixtures.fastSteer(), DiagnosticFixtures.workstationCatalog());
        assertEquals(1, result.findings().size());
    }

    @Test
    void missingDataIsNotPass() {
        DiagnosticRunResult result = run(packOf(GenericEngineeringPack.coolant(Instant.EPOCH)),
                new InMemoryAnalysisSampleSource(), DiagnosticFixtures.workstationCatalog());
        assertEquals(RuleEvaluationState.NOT_EVALUATED, result.outcomes().getFirst().state());
        assertTrue(result.findings().isEmpty());
    }

    @Test
    void logicalAndOrNot() {
        Instant now = Instant.EPOCH;
        RuleCondition cool = new RuleCondition.Comparison(
                "COOLANT_TEMPERATURE", CompareOp.GTE, 105d, "°C", TemporalConstraint.atLeast(1_000_000_000L));
        RuleCondition rpm = new RuleCondition.Comparison(
                "ENGINE_RPM", CompareOp.GTE, 1000d, "rpm", TemporalConstraint.none());
        DiagnosticRuleDefinition and = rule("and", new RuleCondition.Logical(LogicalOp.AND, List.of(cool, rpm)),
                RuleInput.required("COOLANT_TEMPERATURE", "°C", "CoolantTemperature"),
                RuleInput.required("ENGINE_RPM", "rpm", "EngineRPM"));
        InMemoryAnalysisSampleSource src = DiagnosticFixtures.thermalSustained();
        for (int i = 0; i <= 14; i++) {
            src.add(AutomotiveAnalysisFixtures.RPM, i * 1_000_000_000L, 1500);
        }
        List<SignalCatalogEntry> cat = DiagnosticFixtures.workstationCatalog();
        assertFalse(run(packOf(and), src, cat).findings().isEmpty());
        DiagnosticRuleDefinition not = rule("not", new RuleCondition.Logical(LogicalOp.NOT, List.of(
                new RuleCondition.Comparison("COOLANT_TEMPERATURE", CompareOp.GTE, 200d, "°C",
                        TemporalConstraint.none()))),
                RuleInput.required("COOLANT_TEMPERATURE", "°C", "CoolantTemperature"));
        assertFalse(run(packOf(not), DiagnosticFixtures.thermalSustained(), cat).findings().isEmpty());
        DiagnosticRuleDefinition or = rule("or", new RuleCondition.Logical(LogicalOp.OR, List.of(cool, rpm)),
                RuleInput.required("COOLANT_TEMPERATURE", "°C", "CoolantTemperature"),
                RuleInput.required("ENGINE_RPM", "rpm", "EngineRPM"));
        assertFalse(run(packOf(or), src, cat).findings().isEmpty());
    }

    @Test
    void transitionIgnoresRepeats() {
        DiagnosticRuleDefinition rule = rule("gear",
                new RuleCondition.Transition("GEAR", "PARK", "DRIVE"),
                RuleInput.required("GEAR", "", "GearState"));
        DiagnosticRunResult result = run(packOf(rule), AutomotiveAnalysisFixtures.workstation(),
                DiagnosticFixtures.workstationCatalog());
        assertEquals(1, result.findings().size());
        assertEquals(2_000_000_000L, result.findings().getFirst().startTimestampNanos());
    }

    @Test
    void hysteresisAndDebounce() {
        Instant now = Instant.EPOCH;
        DiagnosticRuleDefinition hyst = rule("hyst",
                new RuleCondition.Threshold("COOLANT_TEMPERATURE",
                        com.stellar.platform.analysis.model.ThresholdOperator.GREATER_OR_EQUAL,
                        105d, 102d, "°C", TemporalConstraint.none()),
                RuleInput.required("COOLANT_TEMPERATURE", "°C", "CoolantTemperature"));
        DiagnosticRunResult coalesced = run(packOf(hyst), AutomotiveAnalysisFixtures.workstation(),
                DiagnosticFixtures.workstationCatalog());
        assertEquals(1, coalesced.findings().size());
        DiagnosticRuleDefinition debounce = rule("deb",
                new RuleCondition.Comparison("COOLANT_TEMPERATURE", CompareOp.GTE, 105d, "°C",
                        new TemporalConstraint(com.stellar.platform.analysis.rules.DurationOp.NONE, 0L, null,
                                2_000_000_000L, 0L)),
                RuleInput.required("COOLANT_TEMPERATURE", "°C", "CoolantTemperature"));
        DiagnosticRunResult filtered = run(packOf(debounce), DiagnosticFixtures.thermalSpike(),
                DiagnosticFixtures.workstationCatalog());
        assertTrue(filtered.findings().isEmpty());
    }

    @Test
    void coalescingOneFindingForContinuousOverheat() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        for (int i = 0; i <= 40; i++) {
            src.add(AutomotiveAnalysisFixtures.COOLANT, i * 1_000_000_000L, 107);
        }
        DiagnosticRunResult result = run(packOf(GenericEngineeringPack.coolant(Instant.EPOCH)),
                src, DiagnosticFixtures.workstationCatalog());
        assertEquals(1, result.findings().size());
    }

    @Test
    void fahrenheitConversion() {
        List<SignalCatalogEntry> cat = List.of(
                DiagnosticFixtures.entry(AutomotiveAnalysisFixtures.COOLANT, "°F"));
        DiagnosticRunResult result = run(packOf(GenericEngineeringPack.coolant(Instant.EPOCH)),
                DiagnosticFixtures.fahrenheitCoolantSustained(), cat);
        assertEquals(1, result.findings().size());
    }

    @Test
    void unresolvedRuleIsSkipped() {
        DiagnosticRunResult result = run(GenericEngineeringPack.pack(),
                DiagnosticFixtures.thermalSustained(), List.of());
        assertEquals(0, result.rulesExecuted());
        assertTrue(result.rulesSkipped() >= 1);
        assertTrue(result.outcomes().stream().allMatch(o -> o.state() == RuleEvaluationState.SKIPPED));
    }

    @Test
    void cancellationIsNotCompleted() {
        SimpleCancellationToken token = new SimpleCancellationToken();
        token.cancel();
        DiagnosticRunResult result = engine.execute(
                request(packOf(GenericEngineeringPack.coolant(Instant.EPOCH))),
                DiagnosticFixtures.thermalSustained(),
                DiagnosticFixtures.workstationCatalog(),
                token);
        assertEquals(AnalysisStatus.CANCELLED, result.status());
    }

    @Test
    void sourceProtection() {
        InMemoryAnalysisSampleSource src = DiagnosticFixtures.thermalSustained();
        List<SignalSamplePoint> before = src.querySamples(AutomotiveAnalysisFixtures.COOLANT, 0, 100);
        run(packOf(GenericEngineeringPack.coolant(Instant.EPOCH)), src, DiagnosticFixtures.workstationCatalog());
        List<SignalSamplePoint> after = src.querySamples(AutomotiveAnalysisFixtures.COOLANT, 0, 100);
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).timestampNanos(), after.get(i).timestampNanos());
            assertEquals(before.get(i).physicalValue(), after.get(i).physicalValue());
            assertEquals(before.get(i).rawValue(), after.get(i).rawValue());
        }
    }

    @Test
    void boundedExecutionScalesWithRules() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        for (int i = 0; i < 5_000; i++) {
            src.add(AutomotiveAnalysisFixtures.COOLANT, i * 1_000_000L, 90);
        }
        bench(10, src);
        bench(50, src);
        bench(100, src);
    }

    private void bench(int rules, InMemoryAnalysisSampleSource src) {
        List<DiagnosticRuleDefinition> list = new ArrayList<>();
        Instant now = Instant.EPOCH;
        for (int i = 0; i < rules; i++) {
            list.add(new DiagnosticRuleDefinition(
                    "r" + i, "1", "r" + i, "", RuleCategory.THERMAL, AnalysisSeverity.INFO, true,
                    List.of(RuleInput.required("COOLANT_TEMPERATURE", "°C", "CoolantTemperature")),
                    List.of(),
                    new RuleCondition.Comparison("COOLANT_TEMPERATURE", CompareOp.GTE, 200d, "°C",
                            TemporalConstraint.none()),
                    List.of(), List.of(), RuleOrigin.BUILT_IN, now, now));
        }
        long t0 = System.nanoTime();
        DiagnosticRunResult result = run(new DiagnosticRulePack(1, "bench", "1", "b", "", "1", list),
                src, DiagnosticFixtures.workstationCatalog());
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertEquals(AnalysisStatus.COMPLETED, result.status());
        assertEquals(rules, result.rulesExecuted());
        System.out.println("diagnostic rules=" + rules + " timeMs=" + ms
                + " findings=" + result.findings().size() + " cacheHits=" + result.analysisCacheHits());
        assertTrue(ms < 60_000, "bounded synthetic run should finish under 60s");
    }

    private DiagnosticRunResult run(
            DiagnosticRulePack pack,
            InMemoryAnalysisSampleSource source,
            List<SignalCatalogEntry> catalog
    ) {
        return engine.execute(request(pack), source, catalog, null);
    }

    private static DiagnosticRunRequest request(DiagnosticRulePack pack) {
        return new DiagnosticRunRequest("p", "run-1", pack, List.of(), TimeRangeSource.FULL,
                TimeWindow.unbounded(), Map.of(), 1L, "fp");
    }

    private static DiagnosticRulePack packOf(DiagnosticRuleDefinition rule) {
        return new DiagnosticRulePack(1, "t", "1", "t", "", RuleEngineVersion.CURRENT, List.of(rule));
    }

    private static DiagnosticRuleDefinition rule(String id, RuleCondition condition, RuleInput... inputs) {
        return new DiagnosticRuleDefinition(
                id, "1", id, "", RuleCategory.CUSTOM, AnalysisSeverity.WARNING, true,
                List.of(inputs), List.of(), condition, List.of(), List.of(),
                RuleOrigin.PROJECT_CUSTOM, Instant.EPOCH, Instant.EPOCH);
    }
}
