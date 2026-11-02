package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineerEventDefinition;
import com.stellar.platform.analysis.investigation.eval.EvaluationDocuments;
import com.stellar.platform.analysis.investigation.eval.EvaluationOutcome;
import com.stellar.platform.analysis.investigation.eval.EvaluationResult;
import com.stellar.platform.analysis.investigation.eval.EvaluationRule;
import com.stellar.platform.analysis.investigation.eval.EvaluationSample;
import com.stellar.platform.analysis.investigation.eval.EvaluationSeries;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A new rule version does not rewrite a result stored against the previous version. */
class EvaluationRulePersistenceTest {

    @TempDir Path temp;

    @Test
    void savedResultStaysOnTheRuleVersionThatProducedIt() {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve("meta.db").toString());
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        try {
            service.newProject("Rules");
            EngineerEventDefinition event = new EngineerEventDefinition(
                    "", "Window", "Thermal observation", "src", "file.mf4", 0L, 1_000_000_000L,
                    List.of("CH01"), "", "", "", List.of(), false, "Draft", "", "", "");
            String eventId = service.saveEngineerEvent(event);
            event = event.withId(eventId);
            String before = service.findEngineeringBookmark(eventId).orElseThrow().observation();
            EvaluationRule draft = rule("r-unused", 0.0d, 5.0d);
            EvaluationRule first = service.saveEvaluationRule(draft.withIdentity("", 0, "", ""));
            assertEquals(1, first.version());
            EvaluationSeries series = new EvaluationSeries("CH01", "C", "src", List.of(
                    new EvaluationSample(0L, 1.0d, true, ""),
                    new EvaluationSample(1_000_000_000L, 2.0d, true, "")));
            EvaluationResult stored = service.evaluateAndStore(event, first, List.of(series), true, true);
            assertEquals(EvaluationOutcome.MEETS_CRITERION, stored.outcome());
            assertEquals(1, stored.ruleVersion());
            EvaluationRule revised = service.saveEvaluationRule(new EvaluationRule(
                    first.ruleId(), 1, first.procedureName(), first.procedureVersion(), first.eventCategory(),
                    first.requiredSignals(), first.sourceId(), first.operator(), null, 4.0d, 5.0d, "",
                    first.unit(), first.lowBoundary(), first.highBoundary(), null, first.maxGapNanos(),
                    first.minCoverage(), first.approval(), "", first.approvalReference(),
                    first.createdAt(), first.modifiedAt()));
            assertEquals(2, revised.version());
            assertNotEquals(first.expectedLow(), revised.expectedLow());
            EvaluationResult again = service.evaluateAndStore(event, revised, List.of(series), true, false);
            assertEquals(EvaluationOutcome.DOES_NOT_MEET_CRITERION, again.outcome());
            assertEquals(2, again.ruleVersion());
            String projectId = service.currentProjectId();
            var rows = service.metadataStore().listEvaluationResults(projectId);
            assertEquals(2, rows.size());
            EvaluationResult reopened = EvaluationDocuments.decodeResult(rows.getFirst().document());
            assertEquals(1, reopened.ruleVersion());
            assertEquals(EvaluationOutcome.MEETS_CRITERION, reopened.outcome());
            assertTrue(reopened.expectedText().contains("0"));
            assertEquals(2, service.metadataStore().listEvaluationRules(projectId).size());
            assertEquals(before, service.findEngineeringBookmark(eventId).orElseThrow().observation());
            service.closeProject();
            assertTrue(service.openSavedProject(projectId).opened());
            assertEquals(2, service.listEvaluationRules().size());
            EvaluationResult afterReopen = EvaluationDocuments.decodeResult(
                    service.metadataStore().listEvaluationResults(projectId).getFirst().document());
            assertEquals(reopened.expectedText(), afterReopen.expectedText());
            assertEquals(EvaluationOutcome.MEETS_CRITERION, afterReopen.outcome());
        } finally {
            service.shutdown();
        }
    }

    private static EvaluationRule rule(String id, double low, double high) {
        return new EvaluationRule(id, 0, "QA procedure", "1", "Thermal observation", List.of("CH01"), "src",
                EvaluationRule.ComparisonOperator.MINIMUM_WITHIN_RANGE, null, low, high, "", "C",
                EvaluationRule.BoundaryPolicy.INCLUSIVE, EvaluationRule.BoundaryPolicy.INCLUSIVE, null,
                1_000_000_000L, 1.0d, EvaluationRule.RuleApproval.DRAFT, "",
                "QA-only fixture. Not a customer-approved criterion.", "", "");
    }
}
