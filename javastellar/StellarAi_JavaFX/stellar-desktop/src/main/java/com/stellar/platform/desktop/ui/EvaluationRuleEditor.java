package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.investigation.EngineerEventDefinition;
import com.stellar.platform.analysis.investigation.EngineeringFormats;
import com.stellar.platform.analysis.investigation.eval.EvaluationRule;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/** Rule draft dialog. It does not evaluate and it does not change the engineer event. */
final class EvaluationRuleEditor {

    private final EngineerEventDefinition event;
    private final Dialog<ButtonType> dialog;
    private final ComboBox<String> existing = new ComboBox<>();
    private final TextField procedure = new TextField();
    private final TextField procedureVersion = new TextField();
    private final TextField signals = new TextField();
    private final ComboBox<EvaluationRule.ComparisonOperator> operator = new ComboBox<>();
    private final TextField expectedLow = new TextField();
    private final TextField expectedHigh = new TextField();
    private final TextField expectedValue = new TextField();
    private final TextField expectedState = new TextField();
    private final TextField unit = new TextField();
    private final TextField tolerance = new TextField();
    private final ComboBox<EvaluationRule.BoundaryPolicy> lowBoundary = new ComboBox<>();
    private final ComboBox<EvaluationRule.BoundaryPolicy> highBoundary = new ComboBox<>();
    private final TextField maxGapSeconds = new TextField();
    private final TextField minCoverage = new TextField();
    private final ComboBox<EvaluationRule.RuleApproval> approval = new ComboBox<>();
    private final TextField approver = new TextField();
    private final TextField approvalReference = new TextField();
    private final CheckBox include = new CheckBox("Include this result in the report");
    private final List<EvaluationRule> rules;

    EvaluationRuleEditor(EngineerEventDefinition event, List<EvaluationRule> rules) {
        this.event = event;
        this.rules = rules == null ? List.of() : rules;
        dialog = new Dialog<>();
        dialog.setTitle("Evaluate event");
        dialog.getDialogPane().getButtonTypes().addAll(
                new ButtonType("Save rule", javafx.scene.control.ButtonBar.ButtonData.OK_DONE),
                new ButtonType("Run evaluation", javafx.scene.control.ButtonBar.ButtonData.APPLY),
                ButtonType.CANCEL);
        operator.getItems().addAll(EvaluationRule.ComparisonOperator.values());
        operator.getSelectionModel().select(EvaluationRule.ComparisonOperator.MINIMUM_WITHIN_RANGE);
        lowBoundary.getItems().addAll(EvaluationRule.BoundaryPolicy.values());
        highBoundary.getItems().addAll(EvaluationRule.BoundaryPolicy.values());
        lowBoundary.getSelectionModel().select(EvaluationRule.BoundaryPolicy.INCLUSIVE);
        highBoundary.getSelectionModel().select(EvaluationRule.BoundaryPolicy.INCLUSIVE);
        approval.getItems().addAll(EvaluationRule.RuleApproval.values());
        approval.getSelectionModel().select(EvaluationRule.RuleApproval.DRAFT);
        include.setSelected(true);
        procedureVersion.setText("1");
        minCoverage.setText("1");
        maxGapSeconds.setText("1");
        if (event != null) {
            signals.setText(String.join(", ", event.signals()));
        }
        existing.getItems().add("New draft");
        for (EvaluationRule rule : latest(this.rules)) {
            existing.getItems().add(rule.ruleId() + " version " + rule.version() + "\t" + rule.ruleId());
        }
        existing.getSelectionModel().selectFirst();
        existing.setOnAction(action -> applySelected());
        String interval = event == null ? ""
                : (event.window()
                ? EngineeringFormats.interval(event.startNanos(), event.endNanos())
                : EngineeringFormats.seconds(event.startNanos()) + " point");
        VBox form = new VBox(8,
                new Label("Event interval: " + interval + ". Stored samples are read as recorded."),
                new Label("Rule"), existing,
                new Label("Procedure name"), procedure,
                new Label("Procedure version"), procedureVersion,
                new Label("Required signals"), signals,
                new Label("Comparison"), operator,
                new Label("Expected low"), expectedLow,
                new Label("Expected high"), expectedHigh,
                new Label("Expected value"), expectedValue,
                new Label("Expected state"), expectedState,
                new Label("Unit"), unit,
                new Label("Tolerance"), tolerance,
                new Label("Low boundary"), lowBoundary,
                new Label("High boundary"), highBoundary,
                new Label("Maximum gap (seconds)"), maxGapSeconds,
                new Label("Minimum coverage (0 to 1)"), minCoverage,
                new Label("Approval"), approval,
                new Label("Approver"), approver,
                new Label("Approval reference"), approvalReference,
                include);
        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(420);
        dialog.getDialogPane().setContent(scroll);
        dialog.setResultConverter(button -> button);
    }

    Dialog<ButtonType> dialog() {
        return dialog;
    }

    EngineerEventDefinition event() {
        return event;
    }

    boolean includeSelected() {
        return include.isSelected();
    }

    void fill(
            String procedureName, String version, String signalList, EvaluationRule.ComparisonOperator comparison,
            String low, String high, String value, String state, String unitName, String toleranceText,
            String gapSeconds, String coverage, String reference
    ) {
        procedure.setText(procedureName);
        procedureVersion.setText(version);
        signals.setText(signalList);
        operator.getSelectionModel().select(comparison);
        expectedLow.setText(low);
        expectedHigh.setText(high);
        expectedValue.setText(value);
        expectedState.setText(state);
        unit.setText(unitName);
        tolerance.setText(toleranceText);
        maxGapSeconds.setText(gapSeconds);
        minCoverage.setText(coverage);
        approvalReference.setText(reference);
    }

    EvaluationRule draft() {
        String ruleId = "";
        String selected = existing.getValue();
        if (selected != null && selected.contains("\t")) {
            ruleId = selected.substring(selected.lastIndexOf('\t') + 1);
        }
        Double low = decimal(expectedLow.getText());
        Double high = decimal(expectedHigh.getText());
        Double value = decimal(expectedValue.getText());
        Double band = decimal(tolerance.getText());
        Long gap = gapNanos(maxGapSeconds.getText());
        Double coverage = decimal(minCoverage.getText());
        return new EvaluationRule(
                ruleId, 0,
                procedure.getText(), procedureVersion.getText(),
                event == null ? "" : event.category(),
                split(signals.getText()),
                event == null ? "" : event.sourceId(),
                operator.getValue(),
                value, low, high, expectedState.getText(), unit.getText(),
                lowBoundary.getValue(), highBoundary.getValue(), band, gap, coverage,
                approval.getValue(), approver.getText(), approvalReference.getText(), "", "");
    }

    private void applySelected() {
        String selected = existing.getValue();
        if (selected == null || !selected.contains("\t")) {
            return;
        }
        String ruleId = selected.substring(selected.lastIndexOf('\t') + 1);
        EvaluationRule rule = null;
        for (EvaluationRule candidate : rules) {
            if (ruleId.equals(candidate.ruleId()) && (rule == null || candidate.version() > rule.version())) {
                rule = candidate;
            }
        }
        if (rule == null) {
            return;
        }
        procedure.setText(rule.procedureName());
        procedureVersion.setText(rule.procedureVersion());
        signals.setText(String.join(", ", rule.requiredSignals()));
        operator.getSelectionModel().select(rule.operator());
        expectedLow.setText(rule.expectedLow() == null ? "" : Double.toString(rule.expectedLow()));
        expectedHigh.setText(rule.expectedHigh() == null ? "" : Double.toString(rule.expectedHigh()));
        expectedValue.setText(rule.expectedValue() == null ? "" : Double.toString(rule.expectedValue()));
        expectedState.setText(rule.expectedState());
        unit.setText(rule.unit());
        tolerance.setText(rule.tolerance() == null ? "" : Double.toString(rule.tolerance()));
        lowBoundary.getSelectionModel().select(rule.lowBoundary());
        highBoundary.getSelectionModel().select(rule.highBoundary());
        maxGapSeconds.setText(rule.maxGapNanos() == null ? "" : Double.toString(rule.maxGapNanos() / 1_000_000_000d));
        minCoverage.setText(rule.minCoverage() == null ? "" : Double.toString(rule.minCoverage()));
        approval.getSelectionModel().select(rule.approval());
        approver.setText(rule.approver());
        approvalReference.setText(rule.approvalReference());
    }

    private static List<EvaluationRule> latest(List<EvaluationRule> rules) {
        List<EvaluationRule> out = new ArrayList<>();
        for (EvaluationRule rule : rules) {
            boolean replaced = false;
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).ruleId().equals(rule.ruleId())) {
                    if (rule.version() > out.get(i).version()) {
                        out.set(i, rule);
                    }
                    replaced = true;
                }
            }
            if (!replaced) {
                out.add(rule);
            }
        }
        return out;
    }

    private static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String part : text.split(",")) {
            if (!part.isBlank()) {
                out.add(part.strip());
            }
        }
        return out;
    }

    private static Double decimal(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return Double.parseDouble(text.strip());
    }

    private static Long gapNanos(String text) {
        Double seconds = decimal(text);
        if (seconds == null) {
            return null;
        }
        return Math.round(seconds * 1_000_000_000d);
    }
}
