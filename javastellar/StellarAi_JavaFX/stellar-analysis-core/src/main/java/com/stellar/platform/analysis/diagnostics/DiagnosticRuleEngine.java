package com.stellar.platform.analysis.diagnostics;

import com.stellar.platform.analysis.model.AnalysisSeverity;
import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.EngineeringFinding;
import com.stellar.platform.analysis.model.FindingType;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.rules.BindingResolver;
import com.stellar.platform.analysis.rules.CompiledDiagnosticRule;
import com.stellar.platform.analysis.rules.DiagnosticRuleDefinition;
import com.stellar.platform.analysis.rules.RuleCompiler;
import com.stellar.platform.analysis.rules.RuleEngineVersion;
import com.stellar.platform.analysis.rules.RuleParameterApplicator;
import com.stellar.platform.analysis.rules.RuleEvaluationState;
import com.stellar.platform.analysis.rules.RuleInput;
import com.stellar.platform.analysis.rules.RuleValidationIssue;
import com.stellar.platform.analysis.rules.RuleValidator;
import com.stellar.platform.analysis.rules.SignalBinding;
import com.stellar.platform.analysis.source.AnalysisCancelledException;
import com.stellar.platform.analysis.source.AnalysisSampleSource;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.parser.spi.CancellationToken;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DiagnosticRuleEngine {

    public DiagnosticRunResult execute(
            DiagnosticRunRequest request,
            AnalysisSampleSource source,
            List<SignalCatalogEntry> catalog,
            CancellationToken token
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(source, "source");
        Instant started = Instant.now();
        List<RuleValidationIssue> packIssues = RuleValidator.validatePack(request.pack());
        if (!packIssues.isEmpty()) {
            return new DiagnosticRunResult(request.runId(), AnalysisStatus.FAILED, 0, 0, 0,
                    List.of(), List.of(), request.window(), started, Instant.now(),
                    packIssues.getFirst().message(), 0, RuleEngineVersion.CURRENT);
        }
        List<DiagnosticRuleDefinition> selected = select(request);
        List<RuleOutcome> outcomes = new ArrayList<>();
        List<DiagnosticFinding> findings = new ArrayList<>();
        int executed = 0;
        int skipped = 0;
        int hits = 0;
        try {
            for (DiagnosticRuleDefinition rule : selected) {
                if (token != null && token.isCancellationRequested()) {
                    return DiagnosticRunResult.cancelled(request.runId(), started);
                }
                if (!rule.enabled()) {
                    skipped++;
                    outcomes.add(new RuleOutcome(rule.ruleId(), RuleEvaluationState.SKIPPED, "disabled", List.of()));
                    continue;
                }
                DiagnosticRuleDefinition effective = RuleParameterApplicator.apply(rule, request.parameterOverrides());
                Map<String, SignalBinding> bindings = BindingResolver.resolve(
                        effective, catalog, request.explicitBindings());
                String unresolved = unresolvedRequired(effective, bindings);
                if (unresolved != null) {
                    skipped++;
                    outcomes.add(new RuleOutcome(effective.ruleId(), RuleEvaluationState.SKIPPED, unresolved, List.of()));
                    continue;
                }
                CompiledDiagnosticRule compiled;
                try {
                    compiled = RuleCompiler.compile(effective, bindings);
                } catch (IllegalArgumentException ex) {
                    outcomes.add(new RuleOutcome(effective.ruleId(), RuleEvaluationState.FAILED,
                            ex.getMessage() == null ? "invalid rule" : ex.getMessage(), List.of()));
                    continue;
                }
                ConditionEvaluator evaluator = new ConditionEvaluator(
                        source, compiled.bindings(), request.window(), token);
                ConditionEvaluator.Eval eval = evaluator.evaluate(compiled.definition().condition());
                hits += evaluator.cacheHits();
                executed++;
                if (eval.insufficient()) {
                    outcomes.add(new RuleOutcome(compiled.definition().ruleId(),
                            RuleEvaluationState.NOT_EVALUATED, eval.reason(), List.of()));
                    continue;
                }
                List<DiagnosticFinding> ruleFindings = toFindings(
                        request, compiled.definition(), compiled.bindings(), eval.events());
                findings.addAll(ruleFindings);
                outcomes.add(new RuleOutcome(
                        compiled.definition().ruleId(),
                        ruleFindings.isEmpty() ? RuleEvaluationState.PASS_NO_FINDING : RuleEvaluationState.FINDING,
                        ruleFindings.isEmpty() ? "condition not met" : ruleFindings.size() + " finding(s)",
                        ruleFindings));
            }
        } catch (AnalysisCancelledException cancelled) {
            return DiagnosticRunResult.cancelled(request.runId(), started);
        } catch (RuntimeException ex) {
            return new DiagnosticRunResult(request.runId(), AnalysisStatus.FAILED, selected.size(), executed, skipped,
                    outcomes, findings, request.window(), started, Instant.now(),
                    ex.getMessage() == null ? ex.toString() : ex.getMessage(), hits, RuleEngineVersion.CURRENT);
        }
        return new DiagnosticRunResult(
                request.runId(), AnalysisStatus.COMPLETED, selected.size(), executed, skipped,
                outcomes, findings, request.window(), started, Instant.now(), "", hits, RuleEngineVersion.CURRENT);
    }

    public Map<String, SignalBinding> previewBindings(
            DiagnosticRuleDefinition rule,
            List<SignalCatalogEntry> catalog,
            Map<String, SignalCatalogKey> explicit
    ) {
        return BindingResolver.resolve(rule, catalog, explicit);
    }

    private static List<DiagnosticRuleDefinition> select(DiagnosticRunRequest request) {
        if (request.ruleIds().isEmpty()) {
            return request.pack().rules();
        }
        List<DiagnosticRuleDefinition> out = new ArrayList<>();
        for (String id : request.ruleIds()) {
            out.add(request.pack().requireRule(id));
        }
        return out;
    }

    private static String unresolvedRequired(DiagnosticRuleDefinition rule, Map<String, SignalBinding> bindings) {
        for (RuleInput input : rule.inputs()) {
            if (!input.required()) {
                continue;
            }
            SignalBinding b = bindings.get(input.role());
            if (b == null || !b.bound()) {
                return b == null ? "Missing required input: " + input.role() : b.reason();
            }
        }
        return null;
    }

    private static List<DiagnosticFinding> toFindings(
            DiagnosticRunRequest request,
            DiagnosticRuleDefinition rule,
            Map<String, SignalBinding> bindings,
            List<ConditionEvaluator.Event> events
    ) {
        List<DiagnosticFinding> out = new ArrayList<>();
        List<SignalCatalogKey> keys = new ArrayList<>();
        bindings.values().forEach(b -> {
            if (b.key() != null) {
                keys.add(b.key());
            }
        });
        for (ConditionEvaluator.Event event : events) {
            Map<String, Double> metrics = new LinkedHashMap<>();
            event.evidence().forEach(e -> metrics.putAll(e.measured()));
            metrics.put("durationSeconds", (event.endNanos() - event.startNanos()) / 1_000_000_000.0d);
            EngineeringFinding finding = new EngineeringFinding(
                    null, request.runId(), FindingType.DIAGNOSTIC, rule.severity(),
                    keys, event.startNanos(), event.endNanos(),
                    rule.name().isBlank() ? rule.ruleId() : rule.name(),
                    explain(rule, event),
                    metrics, Instant.now());
            out.add(new DiagnosticFinding(
                    finding, request.runId(), rule.ruleId(), rule.version(), rule.category(),
                    event.evidence(), event.trace(), rule.guidance()));
        }
        return out;
    }

    private static String explain(DiagnosticRuleDefinition rule, ConditionEvaluator.Event event) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generic engineering reference rule — not manufacturer-certified.\n");
        sb.append(event.summary()).append('\n');
        for (FindingEvidence e : event.evidence()) {
            sb.append(e.role()).append(": ").append(e.statement()).append('\n');
        }
        for (ClauseTrace t : event.trace()) {
            sb.append(t.passed() ? "PASS" : "FAIL").append("  ").append(t.clause())
                    .append("  ").append(t.observed()).append('\n');
        }
        if (!rule.guidance().isEmpty()) {
            sb.append("Recommended checks:\n");
            rule.guidance().forEach(g -> sb.append("- ").append(g).append('\n'));
        }
        sb.append("Observed condition; not a proven physical root cause.");
        return sb.toString();
    }
}
