package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.analysis.model.AnalysisSeverity;
import com.stellar.platform.analysis.rules.CompareOp;
import com.stellar.platform.analysis.rules.DiagnosticRuleDefinition;
import com.stellar.platform.analysis.rules.DiagnosticRulePack;
import com.stellar.platform.analysis.rules.GenericEngineeringPack;
import com.stellar.platform.analysis.rules.RuleCategory;
import com.stellar.platform.analysis.rules.RuleOrigin;
import com.stellar.platform.analysis.rules.RuleParameter;
import com.stellar.platform.analysis.rules.SignalBinding;
import com.stellar.platform.analysis.workflow.ProjectCustomPack;
import com.stellar.platform.analysis.workflow.RuleListFilter;
import com.stellar.platform.analysis.workflow.RuleListPresentationModel;
import com.stellar.platform.analysis.workflow.RulePreviewFormatter;
import com.stellar.platform.analysis.workflow.RuleReadiness;
import com.stellar.platform.analysis.workflow.RuleTemplates;
import com.stellar.platform.analysis.workflow.ValidationLine;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Engineering → Rules. Visual authoring for PROJECT CUSTOM rules. Built-in rules are read-only.
 */
public final class EngineeringRulesPane extends BorderPane {

    private final TableView<RuleRow> library = new TableView<>();
    private final TextField search = new TextField();
    private final ComboBox<String> categoryFilter = new ComboBox<>();
    private final ComboBox<String> templateBox = new ComboBox<>();
    private final TextField nameField = new TextField();
    private final ComboBox<RuleCategory> categoryBox = new ComboBox<>();
    private final ComboBox<AnalysisSeverity> severityBox = new ComboBox<>();
    private final TextArea description = new TextArea();
    private final TextField roleField = new TextField();
    private final TextField thresholdField = new TextField();
    private final TextField unitField = new TextField();
    private final TextField durationField = new TextField();
    private final TextField hysteresisField = new TextField();
    private final TextField secondRoleField = new TextField();
    private final TextField stateField = new TextField();
    private final TextArea preview = new TextArea();
    private final TextArea validation = new TextArea();
    private final TextArea impact = new TextArea();
    private final Label editorStatus = new Label("Select a rule or create from a template.");
    private final RuleListPresentationModel model = new RuleListPresentationModel();

    private DiagnosticRulePack builtIn = GenericEngineeringPack.pack();
    private DiagnosticRulePack custom = ProjectCustomPack.empty();
    private DiagnosticRuleDefinition editing;
    private boolean clonePending;
    private Map<String, SignalBinding> bindings = Map.of();
    private Consumer<DiagnosticRuleDefinition> saveHandler = r -> {};
    private Consumer<String> deleteHandler = id -> {};
    private Consumer<DiagnosticRuleDefinition> selectHandler = r -> {};

    public EngineeringRulesPane() {
        setPadding(new Insets(8, 0, 0, 0));
        search.setPromptText("Search by name");
        search.textProperty().addListener((o, a, b) -> refreshLibrary());
        categoryFilter.getItems().add("ALL");
        for (RuleCategory c : RuleCategory.values()) {
            categoryFilter.getItems().add(c.name());
        }
        categoryFilter.getSelectionModel().select("ALL");
        categoryFilter.setOnAction(e -> refreshLibrary());
        templateBox.getItems().addAll(
                "Sustained Threshold",
                "Signal Difference",
                "State + Condition",
                "Response Delay",
                "Rate Limit",
                "Operating Range");
        templateBox.getSelectionModel().selectFirst();
        categoryBox.getItems().addAll(RuleCategory.values());
        categoryBox.getSelectionModel().select(RuleCategory.THERMAL);
        severityBox.getItems().addAll(AnalysisSeverity.values());
        severityBox.getSelectionModel().select(AnalysisSeverity.WARNING);
        description.setPrefRowCount(3);
        preview.setEditable(false);
        preview.getStyleClass().add("mono");
        preview.setPrefRowCount(8);
        validation.setEditable(false);
        validation.getStyleClass().add("mono");
        validation.setPrefRowCount(6);
        impact.setEditable(false);
        impact.getStyleClass().add("muted");
        impact.setPrefRowCount(2);
        editorStatus.getStyleClass().add("muted");
        editorStatus.setWrapText(true);
        roleField.setPromptText("Role e.g. COOLANT_TEMPERATURE");
        thresholdField.setPromptText("Threshold");
        unitField.setPromptText("Unit e.g. °C");
        durationField.setPromptText("Duration seconds / ms");
        hysteresisField.setPromptText("Hysteresis");
        secondRoleField.setPromptText("Second role / response role");
        stateField.setPromptText("State e.g. PRESSED");
        configureLibrary();

        Button neu = new Button("New Rule");
        neu.setOnAction(e -> startTemplate());
        Button clone = new Button("Clone as Project Rule");
        clone.setTooltip(new Tooltip("Built-in rules cannot be edited. Clone creates a project-local copy."));
        clone.setOnAction(e -> cloneSelected());
        Button edit = new Button("Edit");
        edit.setOnAction(e -> editSelected());
        Button dup = new Button("Duplicate");
        dup.setOnAction(e -> duplicateSelected());
        Button del = new Button("Delete");
        del.setOnAction(e -> deleteSelected());
        Button validate = new Button("Validate");
        validate.setOnAction(e -> refreshPreview());
        Button save = new Button("Save");
        save.getStyleClass().add("primary");
        save.setOnAction(e -> saveEditing());
        Button previewBtn = new Button("Preview Rule");
        previewBtn.setOnAction(e -> refreshPreview());

        HBox actions = new HBox(8, neu, clone, edit, dup, del);
        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(6);
        int r = 0;
        form.add(new Label("Template"), 0, r);
        form.add(templateBox, 1, r++);
        form.add(new Label("Name"), 0, r);
        form.add(nameField, 1, r++);
        form.add(new Label("Category"), 0, r);
        form.add(categoryBox, 1, r++);
        form.add(new Label("Severity"), 0, r);
        form.add(severityBox, 1, r++);
        form.add(new Label("Description"), 0, r);
        form.add(description, 1, r++);
        form.add(new Label("Primary role"), 0, r);
        form.add(roleField, 1, r++);
        form.add(new Label("Second role"), 0, r);
        form.add(secondRoleField, 1, r++);
        form.add(new Label("State / transition"), 0, r);
        form.add(stateField, 1, r++);
        form.add(new Label("Threshold"), 0, r);
        form.add(thresholdField, 1, r++);
        form.add(new Label("Unit"), 0, r);
        form.add(unitField, 1, r++);
        form.add(new Label("Duration"), 0, r);
        form.add(durationField, 1, r++);
        form.add(new Label("Hysteresis / delay"), 0, r);
        form.add(hysteresisField, 1, r++);
        HBox saveRow = new HBox(8, previewBtn, validate, save);

        VBox left = new VBox(6, new Label("Rule library"), new HBox(8, search, categoryFilter),
                library, actions);
        VBox.setVgrow(library, Priority.ALWAYS);
        VBox right = new VBox(6, editorStatus, form, saveRow,
                new Label("Validation"), validation,
                new Label("Preview"), preview, impact);
        VBox.setVgrow(preview, Priority.ALWAYS);
        setLeft(left);
        setCenter(right);
        left.setPrefWidth(420);
        left.setPadding(new Insets(0, 10, 0, 0));
        refreshLibrary();
    }

    public void setSaveHandler(Consumer<DiagnosticRuleDefinition> handler) {
        saveHandler = handler == null ? r -> {} : handler;
    }

    public void setDeleteHandler(Consumer<String> handler) {
        deleteHandler = handler == null ? id -> {} : handler;
    }

    public void setSelectHandler(Consumer<DiagnosticRuleDefinition> handler) {
        selectHandler = handler == null ? r -> {} : handler;
    }

    public void setPacks(DiagnosticRulePack builtInPack, DiagnosticRulePack customPack) {
        if (builtInPack != null) {
            builtIn = builtInPack;
        }
        if (customPack != null) {
            custom = customPack;
        }
        refreshLibrary();
    }

    public void setBindings(Map<String, SignalBinding> next) {
        bindings = next == null ? Map.of() : Map.copyOf(next);
        refreshPreview();
    }

    public DiagnosticRuleDefinition editing() {
        return editing;
    }

    private void configureLibrary() {
        library.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        library.getColumns().add(col("Origin", RuleRow::origin, 90));
        library.getColumns().add(col("Name", RuleRow::name, 180));
        library.getColumns().add(col("Category", RuleRow::category, 110));
        library.getColumns().add(col("Severity", RuleRow::severity, 90));
        library.getColumns().add(col("Version", RuleRow::version, 70));
        library.setRowFactory(v -> {
            TableRow<RuleRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (!row.isEmpty()) {
                    load(row.getItem().rule());
                }
            });
            return row;
        });
    }

    private void refreshLibrary() {
        List<DiagnosticRuleDefinition> all = new ArrayList<>();
        all.addAll(builtIn.rules());
        all.addAll(custom.rules());
        model.setSource(all);
        RuleCategory cat = "ALL".equals(categoryFilter.getValue())
                ? null : RuleCategory.valueOf(categoryFilter.getValue());
        model.setFilter(new RuleListFilter(search.getText(), cat, null, null));
        List<RuleRow> rows = new ArrayList<>();
        for (DiagnosticRuleDefinition rule : model.view()) {
            rows.add(new RuleRow(
                    rule.origin() == RuleOrigin.BUILT_IN ? "BUILT-IN" : "PROJECT",
                    rule.name(), rule.category().name(), rule.severity().name(), rule.version(), rule));
        }
        library.setItems(FXCollections.observableArrayList(rows));
    }

    public void authorForTest(
            String name, String role, String threshold, String unit, String duration, String hysteresis
    ) {
        startTemplate();
        nameField.setText(name);
        roleField.setText(role);
        thresholdField.setText(threshold);
        unitField.setText(unit);
        durationField.setText(duration);
        hysteresisField.setText(hysteresis);
    }

    public String editorStatusForTest() {
        return editorStatus.getText() == null ? "" : editorStatus.getText();
    }

    private void startTemplate() {
        clonePending = false;
        populateFrom(buildFromForm(null));
        editorStatus.setText("New project-custom rule from template. Built-in packs are not modified.");
        refreshPreview();
    }

    private void cloneSelected() {
        RuleRow row = library.getSelectionModel().getSelectedItem();
        if (row == null) {
            return;
        }
        DiagnosticRuleDefinition clone = row.rule().asProjectCustom(row.rule().ruleId() + ".custom");
        clonePending = false;
        populateFrom(clone);
        editorStatus.setText("Cloned as project rule. The built-in definition is unchanged.");
        refreshPreview();
    }

    private void editSelected() {
        RuleRow row = library.getSelectionModel().getSelectedItem();
        if (row == null) {
            return;
        }
        if (row.rule().origin() == RuleOrigin.BUILT_IN) {
            cloneSelected();
            return;
        }
        populateFrom(row.rule());
        editorStatus.setText("Editing project-custom rule " + row.rule().ruleId());
        refreshPreview();
    }

    private void duplicateSelected() {
        RuleRow row = library.getSelectionModel().getSelectedItem();
        if (row == null) {
            return;
        }
        DiagnosticRuleDefinition dup = row.rule().asProjectCustom(row.rule().ruleId() + ".copy");
        populateFrom(dup);
        editorStatus.setText("Duplicated as a new project rule.");
        refreshPreview();
    }

    private void deleteSelected() {
        RuleRow row = library.getSelectionModel().getSelectedItem();
        if (row == null || row.rule().origin() != RuleOrigin.PROJECT_CUSTOM) {
            editorStatus.setText("Only PROJECT CUSTOM rules may be deleted.");
            return;
        }
        deleteHandler.accept(row.rule().ruleId());
    }

    private void saveEditing() {
        DiagnosticRuleDefinition rule = buildFromForm(editing);
        if (rule.origin() != RuleOrigin.PROJECT_CUSTOM) {
            editorStatus.setText("Built-in rules are read-only. Clone as a project rule first.");
            return;
        }
        List<ValidationLine> lines = ValidationLine.forRule(rule, bindings);
        boolean blocking = lines.stream().anyMatch(l -> !l.ok() && !l.warning());
        if (blocking) {
            refreshPreview();
            return;
        }
        saveHandler.accept(rule);
        editorStatus.setText("Saved project rule " + rule.ruleId() + " v" + rule.version());
    }

    private void load(DiagnosticRuleDefinition rule) {
        populateFrom(rule);
        selectHandler.accept(rule);
        if (rule.origin() == RuleOrigin.BUILT_IN) {
            editorStatus.setText("BUILT-IN — read-only. Use Clone as Project Rule to edit.");
        } else {
            editorStatus.setText("PROJECT CUSTOM " + rule.ruleId() + " v" + rule.version());
        }
        refreshPreview();
    }

    private void populateFrom(DiagnosticRuleDefinition rule) {
        editing = rule;
        nameField.setText(rule.name());
        categoryBox.getSelectionModel().select(rule.category());
        severityBox.getSelectionModel().select(rule.severity());
        description.setText(rule.description());
        if (!rule.inputs().isEmpty()) {
            roleField.setText(rule.inputs().getFirst().role());
            unitField.setText(rule.inputs().getFirst().expectedUnit());
        }
        if (rule.inputs().size() > 1) {
            secondRoleField.setText(rule.inputs().get(1).role());
        }
        for (RuleParameter p : rule.parameters()) {
            switch (p.name()) {
                case "threshold", "rate", "pressureThreshold" -> thresholdField.setText(Double.toString(p.defaultValue()));
                case "durationSeconds" -> durationField.setText(Double.toString(p.defaultValue()));
                case "durationMs", "maxDelayMs" -> durationField.setText(Double.toString(p.defaultValue()));
                case "hysteresis" -> hysteresisField.setText(Double.toString(p.defaultValue()));
                default -> {
                }
            }
            if (!p.unit().isBlank() && unitField.getText().isBlank()) {
                unitField.setText(p.unit());
            }
        }
    }

    private DiagnosticRuleDefinition buildFromForm(DiagnosticRuleDefinition previous) {
        String name = nameField.getText();
        AnalysisSeverity severity = severityBox.getValue() == null
                ? AnalysisSeverity.WARNING : severityBox.getValue();
        RuleCategory category = categoryBox.getValue() == null
                ? RuleCategory.CUSTOM : categoryBox.getValue();
        String role = blank(roleField.getText(), "COOLANT_TEMPERATURE");
        String unit = blank(unitField.getText(), "°C");
        double threshold = parse(thresholdField.getText(), 105);
        double duration = parse(durationField.getText(), 10);
        double hysteresis = parse(hysteresisField.getText(), 3);
        String second = blank(secondRoleField.getText(), "ACTUAL_TORQUE");
        String state = blank(stateField.getText(), "PRESSED");
        DiagnosticRuleDefinition created = switch (templateBox.getValue() == null
                ? "Sustained Threshold" : templateBox.getValue()) {
            case "Signal Difference" -> RuleTemplates.difference(
                    name, role, second, threshold, unit, duration, severity);
            case "State + Condition" -> RuleTemplates.stateAndCondition(
                    name, role, state, second, CompareOp.GTE, threshold, unit, duration, severity);
            case "Response Delay" -> RuleTemplates.responseDelay(
                    name, role, "OFF", state.isBlank() ? "ON" : state, second, threshold, unit, duration, severity);
            case "Rate Limit" -> RuleTemplates.rateLimit(name, role, threshold, unit, severity);
            case "Operating Range" -> RuleTemplates.operatingRange(
                    name, role, threshold, threshold + Math.abs(hysteresis), unit, true, severity);
            default -> RuleTemplates.sustainedThreshold(
                    name, role, threshold, unit, duration, hysteresis, severity, category);
        };
        created = new DiagnosticRuleDefinition(
                previous == null ? created.ruleId() : previous.ruleId(),
                previous == null ? "1" : previous.version(),
                blank(name, created.name()),
                blank(description.getText(), created.description()),
                category, severity, true, created.inputs(), created.parameters(),
                created.condition(), created.guidance(), created.tags(),
                RuleOrigin.PROJECT_CUSTOM, created.createdAt(), created.updatedAt());
        return created;
    }

    private void refreshPreview() {
        if (nameField.getText() == null && editing == null) {
            return;
        }
        try {
            DiagnosticRuleDefinition rule = buildFromForm(editing);
            editing = rule;
            List<ValidationLine> lines = ValidationLine.forRule(rule, bindings);
            StringBuilder sb = new StringBuilder();
            lines.forEach(l -> sb.append(l.label()).append('\n'));
            validation.setText(sb.toString());
            preview.setText(RulePreviewFormatter.preview(rule, bindings));
            if (editing != null && !"1".equals(editing.version())) {
                impact.setText("This rule has existing diagnostic results.\n"
                        + "Future runs will use the updated version. Historical findings keep ruleId + ruleVersion.");
            } else {
                impact.setText("");
            }
        } catch (RuntimeException ex) {
            validation.setText("FAIL  " + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
        }
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static double parse(String raw, double fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            double v = Double.parseDouble(raw.trim());
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("parameter must be finite");
            }
            return v;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid number: " + raw);
        }
    }

    private static <T> TableColumn<T, String> col(String title, java.util.function.Function<T, String> f, double w) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setPrefWidth(w);
        c.setCellValueFactory(v -> new SimpleStringProperty(f.apply(v.getValue())));
        return c;
    }

    public record RuleRow(String origin, String name, String category, String severity, String version,
                          DiagnosticRuleDefinition rule) {}
}
