package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.analysis.advanced.AdvancedAnalyticsResult;
import com.stellar.platform.analysis.advanced.AdvancedAnalyticsType;
import com.stellar.platform.analysis.advanced.CrossingMode;
import com.stellar.platform.analysis.advanced.EnvelopeMode;
import com.stellar.platform.analysis.advanced.FilterPhaseMode;
import com.stellar.platform.analysis.advanced.ResampleMethod;
import com.stellar.platform.analysis.advanced.SampleHandling;
import com.stellar.platform.analysis.advanced.SpectralPeak;
import com.stellar.platform.analysis.advanced.WindowFunction;
import com.stellar.platform.analysis.advanced.WindowUnit;
import com.stellar.platform.analysis.advanced.WindowedStatistic;
import com.stellar.platform.analysis.report.EngineeringValueFormatter;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Time / Frequency / Comparison controls inside Engineering → Analysis.
 * Changing the visible operation only changes which parameter fields are shown.
 */
public final class AdvancedAnalyticsPane extends VBox {

    public enum Category {
        TIME, FREQUENCY, COMPARISON
    }

    public record AdvancedRunSpec(
            AdvancedAnalyticsType type,
            String range,
            SampleHandling sampleHandling,
            ResampleMethod resampleMethod,
            double targetRateHz,
            WindowFunction window,
            int fftSize,
            int welchSegment,
            int welchOverlap,
            double lowCutoffHz,
            double highCutoffHz,
            int filterOrder,
            FilterPhaseMode phaseMode,
            double smoothWindow,
            WindowUnit smoothUnit,
            double alpha,
            CrossingMode crossingMode,
            WindowedStatistic windowedStatistic,
            EnvelopeMode envelopeMode,
            long maxLagNanos,
            boolean persistDerived
    ) {
    }

    private final ComboBox<String> range = new ComboBox<>();
    private final ComboBox<AdvancedAnalyticsType> freqType = new ComboBox<>();
    private final ComboBox<SampleHandling> handling = new ComboBox<>();
    private final ComboBox<ResampleMethod> resample = new ComboBox<>();
    private final ComboBox<WindowFunction> windowFn = new ComboBox<>();
    private final TextField targetRate = new TextField();
    private final TextField fftSize = new TextField();
    private final TextField welchSeg = new TextField();
    private final TextField welchOv = new TextField();
    private final ComboBox<AdvancedAnalyticsType> timeType = new ComboBox<>();
    private final TextField cutoffLo = new TextField();
    private final TextField cutoffHi = new TextField();
    private final TextField order = new TextField();
    private final ComboBox<FilterPhaseMode> phase = new ComboBox<>();
    private final TextField windowSize = new TextField();
    private final ComboBox<WindowUnit> windowUnit = new ComboBox<>();
    private final TextField alpha = new TextField();
    private final ComboBox<WindowedStatistic> winStat = new ComboBox<>();
    private final ComboBox<EnvelopeMode> envelope = new ComboBox<>();
    private final ComboBox<CrossingMode> crossing = new ComboBox<>();
    private final ComboBox<AdvancedAnalyticsType> cmpType = new ComboBox<>();
    private final TextField maxLagMs = new TextField();
    private final SpectralChartCanvas chart = new SpectralChartCanvas();
    private final Label cursor = new Label("Frequency: —");
    private final TableView<PeakRow> peaks = new TableView<>();
    private final TextArea report = new TextArea();
    private final FlowPane form = new FlowPane();
    private final HBox actions = new HBox();
    private final VBox timeOperationField;
    private final VBox frequencyOperationField;
    private final VBox comparisonOperationField;
    private final VBox rangeField;
    private final VBox handlingField;
    private final VBox resampleField;
    private final VBox rateField;
    private final VBox windowField;
    private final VBox fftField;
    private final VBox welchSegmentField;
    private final VBox welchOverlapField;
    private final VBox cutoffField;
    private final VBox cutoffHighField;
    private final VBox orderField;
    private final VBox phaseField;
    private final VBox windowSizeField;
    private final VBox windowUnitField;
    private final VBox alphaField;
    private final VBox statisticField;
    private final VBox envelopeField;
    private final VBox crossingField;
    private final VBox lagField;
    private final Label cutoffLabel;
    private final ToggleGroup categories = new ToggleGroup();
    private final ToggleButton timeCategory = categoryButton("Time Domain", Category.TIME);
    private final ToggleButton frequencyCategory = categoryButton("Frequency Domain", Category.FREQUENCY);
    private final ToggleButton comparisonCategory = categoryButton("Comparison", Category.COMPARISON);
    private final Button runFreq = new Button("Run");
    private final Button runTime = new Button("Run");
    private final Button preview = new Button("Preview filter");
    private final Button saveDerived = new Button("Save Derived Signal");
    private final Button runCmp = new Button("Run");
    private final Button export = new Button("Export CSV");
    private final Button addEvidence = new Button("Add to Evidence");
    private Consumer<AdvancedRunSpec> runHandler = spec -> {};
    private Consumer<Path> exportHandler = path -> {};
    private Runnable saveDerivedHandler = () -> {};
    private Runnable addToInvestigationHandler = () -> {};
    private Double highlightHz = Double.NaN;
    private boolean formRefresh;

    public record PeakRow(String rank, String frequency, String magnitude, String period, String harmonic, double hz) {
    }

    public AdvancedAnalyticsPane() {
        getStyleClass().add("analytics-section");
        setSpacing(8);
        setPadding(new Insets(4, 0, 0, 0));
        range.getItems().addAll("VIEWPORT", "CURSOR_A_TO_B");
        range.getSelectionModel().select("VIEWPORT");
        present(range, AnalyticsControlLabels::range);
        freqType.getItems().addAll(
                AdvancedAnalyticsType.FFT_SPECTRUM,
                AdvancedAnalyticsType.POWER_SPECTRAL_DENSITY,
                AdvancedAnalyticsType.SPECTRAL_PEAKS);
        freqType.getSelectionModel().select(AdvancedAnalyticsType.FFT_SPECTRUM);
        present(freqType, AnalyticsControlLabels::operation);
        handling.getItems().addAll(SampleHandling.ORIGINAL, SampleHandling.RESAMPLE);
        handling.getSelectionModel().select(SampleHandling.ORIGINAL);
        present(handling, AnalyticsControlLabels::sampleHandling);
        resample.getItems().addAll(ResampleMethod.values());
        resample.getSelectionModel().select(ResampleMethod.LINEAR_INTERPOLATION);
        present(resample, AnalyticsControlLabels::resample);
        windowFn.getItems().addAll(WindowFunction.values());
        windowFn.getSelectionModel().select(WindowFunction.HANN);
        present(windowFn, AnalyticsControlLabels::window);
        targetRate.setPromptText("Hz");
        fftSize.setPromptText("Auto");
        welchSeg.setText("256");
        welchOv.setText("128");
        timeType.getItems().addAll(
                AdvancedAnalyticsType.LOW_PASS_FILTER,
                AdvancedAnalyticsType.HIGH_PASS_FILTER,
                AdvancedAnalyticsType.BAND_PASS_FILTER,
                AdvancedAnalyticsType.MOVING_AVERAGE,
                AdvancedAnalyticsType.EXPONENTIAL_SMOOTHING,
                AdvancedAnalyticsType.ENVELOPE,
                AdvancedAnalyticsType.WINDOWED_STATISTICS,
                AdvancedAnalyticsType.ZERO_CROSSING_ANALYSIS,
                AdvancedAnalyticsType.CYCLE_ANALYSIS,
                AdvancedAnalyticsType.RESAMPLE);
        timeType.getSelectionModel().select(AdvancedAnalyticsType.LOW_PASS_FILTER);
        present(timeType, AnalyticsControlLabels::operation);
        order.setText("2");
        phase.getItems().addAll(FilterPhaseMode.values());
        phase.getSelectionModel().select(FilterPhaseMode.ZERO_PHASE);
        present(phase, AnalyticsControlLabels::phase);
        windowSize.setText("100");
        windowUnit.getItems().addAll(WindowUnit.values());
        windowUnit.getSelectionModel().select(WindowUnit.MILLISECONDS);
        present(windowUnit, AnalyticsControlLabels::windowUnit);
        alpha.setText("0.2");
        winStat.getItems().addAll(WindowedStatistic.values());
        winStat.getSelectionModel().select(WindowedStatistic.RMS);
        present(winStat, AnalyticsControlLabels::statistic);
        envelope.getItems().addAll(EnvelopeMode.values());
        envelope.getSelectionModel().select(EnvelopeMode.ABS_PEAK);
        present(envelope, AnalyticsControlLabels::envelope);
        crossing.getItems().addAll(CrossingMode.values());
        crossing.getSelectionModel().select(CrossingMode.ALL);
        present(crossing, AnalyticsControlLabels::crossing);
        cmpType.getItems().addAll(
                AdvancedAnalyticsType.LAG_ANALYSIS,
                AdvancedAnalyticsType.COHERENCE,
                AdvancedAnalyticsType.FFT_SPECTRUM);
        cmpType.getSelectionModel().select(AdvancedAnalyticsType.LAG_ANALYSIS);
        present(cmpType, AnalyticsControlLabels::operation);
        maxLagMs.setText("500");
        sizeInput(range);
        sizeInput(freqType);
        sizeInput(handling);
        sizeInput(resample);
        sizeInput(windowFn);
        sizeInput(targetRate);
        sizeInput(fftSize);
        sizeInput(welchSeg);
        sizeInput(welchOv);
        sizeInput(timeType);
        sizeInput(cutoffLo);
        sizeInput(cutoffHi);
        sizeInput(order);
        sizeInput(phase);
        sizeInput(windowSize);
        sizeInput(windowUnit);
        sizeInput(alpha);
        sizeInput(winStat);
        sizeInput(envelope);
        sizeInput(crossing);
        sizeInput(cmpType);
        sizeInput(maxLagMs);

        runFreq.getStyleClass().add("primary");
        runFreq.setOnAction(e -> runHandler.accept(spec(freqType.getValue(), false)));
        runTime.setOnAction(e -> runHandler.accept(spec(timeType.getValue(), false)));
        preview.setOnAction(e -> runHandler.accept(spec(timeType.getValue(), false)));
        saveDerived.setOnAction(e -> saveDerivedHandler.run());
        runCmp.setOnAction(e -> runHandler.accept(spec(cmpType.getValue(), false)));
        export.setOnAction(e -> exportHandler.accept(null));
        addEvidence.setOnAction(e -> addToInvestigationHandler.run());

        Label heading = new Label("Advanced Analytics");
        heading.getStyleClass().add("analytics-heading");
        HBox tabs = new HBox(timeCategory, frequencyCategory, comparisonCategory);
        tabs.getStyleClass().add("analytics-category-tabs");
        timeCategory.setToggleGroup(categories);
        frequencyCategory.setToggleGroup(categories);
        comparisonCategory.setToggleGroup(categories);

        timeOperationField = field("Operation", timeType);
        frequencyOperationField = field("Operation", freqType);
        comparisonOperationField = field("Operation", cmpType);
        rangeField = field("Input Range", range);
        handlingField = field("Sample Handling", handling);
        resampleField = field("Resampling Method", resample);
        rateField = field("Target Rate", withUnit(targetRate, "Hz"));
        windowField = field("Window Function", windowFn);
        fftField = field("FFT Size", fftSize);
        welchSegmentField = field("Welch Segment", welchSeg);
        welchOverlapField = field("Welch Overlap", welchOv);
        cutoffLabel = new Label("Cutoff Frequency");
        cutoffField = field(cutoffLabel, withUnit(cutoffLo, "Hz"));
        cutoffHighField = field("High Cutoff", withUnit(cutoffHi, "Hz"));
        orderField = field("Order", order);
        phaseField = field("Filter Mode", phase);
        windowSizeField = field("Window", windowSize);
        windowUnitField = field("Window Unit", windowUnit);
        alphaField = field("Alpha", alpha);
        statisticField = field("Statistic", winStat);
        envelopeField = field("Envelope", envelope);
        crossingField = field("Crossings", crossing);
        lagField = field("Maximum Lag", withUnit(maxLagMs, "ms"));

        form.getStyleClass().add("analytics-form");
        form.setPrefWrapLength(640);
        form.getChildren().addAll(
                rangeField, handlingField, resampleField, rateField, windowField, fftField,
                welchSegmentField, welchOverlapField, cutoffField, cutoffHighField, orderField, phaseField,
                windowSizeField, windowUnitField, alphaField, statisticField, envelopeField, crossingField, lagField);
        widthProperty().addListener((obs, previous, next) -> {
            double available = next.doubleValue() - 4;
            if (available > 240 && Math.abs(form.getPrefWrapLength() - available) > 4) {
                form.setPrefWrapLength(available);
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        actions.getStyleClass().add("analytics-actions");
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getChildren().add(spacer);

        chart.widthProperty().bind(widthProperty().subtract(8));
        chart.setHoverHandler(hz -> {
            if (!Double.isFinite(hz)) {
                cursor.setText("Frequency: —");
                return;
            }
            double mag = chart.valueAt(hz);
            String period = hz == 0d ? "—" : EngineeringValueFormatter.decimal(1000d / hz) + " ms";
            cursor.setText("Frequency: " + EngineeringValueFormatter.hertz(hz)
                    + "    Magnitude: " + EngineeringValueFormatter.decimal(mag)
                    + "    Period: " + period);
        });
        configurePeaks();
        report.setEditable(false);
        report.setPrefRowCount(6);
        report.getStyleClass().addAll("mono", "analytics-results");
        report.setPromptText("ADVANCED SIGNAL ANALYTICS");
        cursor.getStyleClass().add("analytics-result-value");
        Label note = new Label("Spectrum uses the selected time range in seconds, not pixels. "
                + "Results are measurements, not automatic diagnoses.");
        note.getStyleClass().add("muted");
        note.setWrapText(true);

        categories.selectedToggleProperty().addListener((obs, previous, next) -> refreshForm());
        timeType.valueProperty().addListener((obs, previous, next) -> refreshForm());
        freqType.valueProperty().addListener((obs, previous, next) -> refreshForm());
        cmpType.valueProperty().addListener((obs, previous, next) -> refreshForm());
        handling.valueProperty().addListener((obs, previous, next) -> refreshForm());
        timeCategory.setSelected(true);
        refreshForm();

        getChildren().addAll(heading, tabs, timeOperationField, frequencyOperationField, comparisonOperationField,
                form, actions, chart, cursor, peaks, report, note);
        VBox.setVgrow(peaks, Priority.SOMETIMES);
    }

    public void setRunHandler(Consumer<AdvancedRunSpec> handler) {
        runHandler = handler == null ? spec -> {} : handler;
    }

    public void setExportHandler(Consumer<Path> handler) {
        exportHandler = handler == null ? path -> {} : handler;
    }

    public void setSaveDerivedHandler(Runnable handler) {
        saveDerivedHandler = handler == null ? () -> {} : handler;
    }

    public void setAddToInvestigationHandler(Runnable handler) {
        addToInvestigationHandler = handler == null ? () -> {} : handler;
    }

    public String selectedRange() {
        return range.getValue();
    }

    public Category selectedCategory() {
        Toggle selected = categories.getSelectedToggle();
        return selected == null ? Category.TIME : (Category) selected.getUserData();
    }

    public void selectCategory(Category category) {
        ToggleButton button = switch (category) {
            case TIME -> timeCategory;
            case FREQUENCY -> frequencyCategory;
            case COMPARISON -> comparisonCategory;
        };
        button.setSelected(true);
    }

    public AdvancedAnalyticsType selectedOperation() {
        return switch (selectedCategory()) {
            case TIME -> timeType.getValue();
            case FREQUENCY -> freqType.getValue();
            case COMPARISON -> cmpType.getValue();
        };
    }

    public void selectOperation(AdvancedAnalyticsType type) {
        if (type == null) {
            return;
        }
        ComboBox<AdvancedAnalyticsType> active = switch (selectedCategory()) {
            case TIME -> timeType;
            case FREQUENCY -> freqType;
            case COMPARISON -> cmpType;
        };
        if (active.getItems().contains(type)) {
            active.getSelectionModel().select(type);
            return;
        }
        if (timeType.getItems().contains(type)) {
            timeType.getSelectionModel().select(type);
            selectCategory(Category.TIME);
        } else if (freqType.getItems().contains(type)) {
            freqType.getSelectionModel().select(type);
            selectCategory(Category.FREQUENCY);
        } else if (cmpType.getItems().contains(type)) {
            cmpType.getSelectionModel().select(type);
            selectCategory(Category.COMPARISON);
        }
    }

    public List<String> visibleFieldLabels() {
        List<String> labels = new ArrayList<>();
        for (Node node : form.getChildren()) {
            if (node.isManaged() && node instanceof VBox box && !box.getChildren().isEmpty()
                    && box.getChildren().getFirst() instanceof Label label) {
                labels.add(label.getText());
            }
        }
        return List.copyOf(labels);
    }

    public AdvancedRunSpec currentSpec() {
        return spec(selectedOperation(), false);
    }

    public AdvancedRunSpec specFor(AdvancedAnalyticsType type) {
        return spec(type, false);
    }

    public void editFilterParameters(String lowCutoff, String highCutoff, String orderText) {
        cutoffLo.setText(lowCutoff);
        cutoffHi.setText(highCutoff);
        order.setText(orderText);
    }

    public void show(AdvancedAnalyticsResult result, String text) {
        report.setText(text == null ? "" : text);
        if (result == null) {
            return;
        }
        if (result.spectrum() != null) {
            chart.setSeries(result.spectrum().frequencyHz(), result.spectrum().magnitude(), "Magnitude");
            if (result.comparisonSpectrum() != null) {
                chart.setComparison(result.comparisonSpectrum().magnitude());
            } else {
                chart.setComparison(null);
            }
        } else if (result.psd() != null) {
            String unit = result.psd().densityUnit().isBlank() ? "PSD" : result.psd().densityUnit();
            chart.setSeries(result.psd().frequencyHz(), result.psd().powerDensity(), unit);
            chart.setComparison(null);
        }
        List<Double> marks = new ArrayList<>();
        List<PeakRow> rows = new ArrayList<>();
        for (SpectralPeak p : result.peaks()) {
            marks.add(p.frequencyHz());
            rows.add(new PeakRow(
                    Integer.toString(p.rank()),
                    EngineeringValueFormatter.hertz(p.frequencyHz()),
                    EngineeringValueFormatter.decimal(p.magnitude()),
                    EngineeringValueFormatter.decimal(p.periodSeconds() * 1000d) + " ms",
                    p.harmonicLabel() == null ? "" : p.harmonicLabel(),
                    p.frequencyHz()));
        }
        chart.setPeaks(marks);
        peaks.setItems(FXCollections.observableArrayList(rows));
    }

    private void refreshForm() {
        if (formRefresh) {
            return;
        }
        formRefresh = true;
        try {
            Category category = selectedCategory();
            AdvancedAnalyticsType operation = selectedOperation();
            show(timeOperationField, category == Category.TIME);
            show(frequencyOperationField, category == Category.FREQUENCY);
            show(comparisonOperationField, category == Category.COMPARISON);
            boolean filter = operation == AdvancedAnalyticsType.LOW_PASS_FILTER
                    || operation == AdvancedAnalyticsType.HIGH_PASS_FILTER
                    || operation == AdvancedAnalyticsType.BAND_PASS_FILTER;
            boolean frequency = operation == AdvancedAnalyticsType.FFT_SPECTRUM
                    || operation == AdvancedAnalyticsType.POWER_SPECTRAL_DENSITY
                    || operation == AdvancedAnalyticsType.SPECTRAL_PEAKS
                    || operation == AdvancedAnalyticsType.COHERENCE;
            boolean resampleOp = operation == AdvancedAnalyticsType.RESAMPLE;
            boolean showHandling = filter || frequency || operation == AdvancedAnalyticsType.LAG_ANALYSIS;
            boolean showResample = resampleOp || (showHandling && handling.getValue() == SampleHandling.RESAMPLE);
            boolean moving = operation == AdvancedAnalyticsType.MOVING_AVERAGE
                    || operation == AdvancedAnalyticsType.ENVELOPE
                    || operation == AdvancedAnalyticsType.WINDOWED_STATISTICS;
            cutoffLabel.setText(operation == AdvancedAnalyticsType.BAND_PASS_FILTER ? "Low Cutoff" : "Cutoff Frequency");
            show(rangeField, true);
            show(handlingField, showHandling);
            show(resampleField, showResample);
            show(rateField, showResample);
            show(windowField, frequency);
            show(fftField, operation == AdvancedAnalyticsType.FFT_SPECTRUM
                    || operation == AdvancedAnalyticsType.SPECTRAL_PEAKS);
            show(welchSegmentField, operation == AdvancedAnalyticsType.POWER_SPECTRAL_DENSITY
                    || operation == AdvancedAnalyticsType.COHERENCE);
            show(welchOverlapField, operation == AdvancedAnalyticsType.POWER_SPECTRAL_DENSITY
                    || operation == AdvancedAnalyticsType.COHERENCE);
            show(cutoffField, filter);
            show(cutoffHighField, operation == AdvancedAnalyticsType.BAND_PASS_FILTER);
            show(orderField, filter);
            show(phaseField, filter);
            show(windowSizeField, moving);
            show(windowUnitField, moving);
            show(alphaField, operation == AdvancedAnalyticsType.EXPONENTIAL_SMOOTHING);
            show(statisticField, operation == AdvancedAnalyticsType.WINDOWED_STATISTICS);
            show(envelopeField, operation == AdvancedAnalyticsType.ENVELOPE);
            show(crossingField, operation == AdvancedAnalyticsType.ZERO_CROSSING_ANALYSIS);
            show(lagField, operation == AdvancedAnalyticsType.LAG_ANALYSIS);
            actions.getChildren().removeIf(node -> node instanceof Button);
            if (category == Category.TIME) {
                actions.getChildren().addAll(runTime, preview, saveDerived);
            } else if (category == Category.FREQUENCY) {
                actions.getChildren().addAll(runFreq, export, addEvidence);
            } else {
                actions.getChildren().addAll(runCmp);
            }
        } finally {
            formRefresh = false;
        }
    }

    private AdvancedRunSpec spec(AdvancedAnalyticsType type, boolean persist) {
        return new AdvancedRunSpec(
                type,
                range.getValue(),
                handling.getValue(),
                resample.getValue(),
                parse(targetRate.getText(), 0),
                windowFn.getValue(),
                (int) parse(fftSize.getText(), 0),
                (int) parse(welchSeg.getText(), 256),
                (int) parse(welchOv.getText(), 128),
                parse(cutoffLo.getText(), 0),
                parse(cutoffHi.getText(), 0),
                (int) parse(order.getText(), 2),
                phase.getValue(),
                parse(windowSize.getText(), 100),
                windowUnit.getValue(),
                parse(alpha.getText(), 0.2),
                crossing.getValue(),
                winStat.getValue(),
                envelope.getValue(),
                Math.round(parse(maxLagMs.getText(), 500) * 1_000_000d),
                persist
        );
    }

    private void configurePeaks() {
        peaks.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        peaks.setPlaceholder(new Label("No spectral peaks"));
        peaks.setPrefHeight(110);
        peaks.getColumns().add(col("Rank", PeakRow::rank, 50));
        peaks.getColumns().add(col("Frequency", PeakRow::frequency, 90));
        peaks.getColumns().add(col("Magnitude / PSD", PeakRow::magnitude, 120));
        peaks.getColumns().add(col("Period", PeakRow::period, 90));
        peaks.getColumns().add(col("Harmonic", PeakRow::harmonic, 160));
        peaks.setRowFactory(tv -> {
            var row = new javafx.scene.control.TableRow<PeakRow>();
            row.setOnMouseClicked(e -> {
                if (!row.isEmpty()) {
                    highlightHz = row.getItem().hz();
                    chart.highlight(highlightHz);
                }
            });
            return row;
        });
    }

    private static TableColumn<PeakRow, String> col(String title, java.util.function.Function<PeakRow, String> f, double w) {
        TableColumn<PeakRow, String> c = new TableColumn<>(title);
        c.setPrefWidth(w);
        c.setCellValueFactory(v -> new SimpleStringProperty(f.apply(v.getValue())));
        return c;
    }

    private static ToggleButton categoryButton(String text, Category category) {
        ToggleButton button = new ToggleButton(text);
        button.setUserData(category);
        button.setFocusTraversable(true);
        return button;
    }

    private static VBox field(String name, Node editor) {
        return field(namedLabel(name), editor);
    }

    private static VBox field(Label label, Node editor) {
        label.getStyleClass().add("analytics-field-label");
        label.setWrapText(true);
        label.setMinWidth(Region.USE_PREF_SIZE);
        VBox box = new VBox(4, label, editor);
        box.getStyleClass().add("analytics-field");
        return box;
    }

    private static Label namedLabel(String name) {
        return new Label(name);
    }

    private static HBox withUnit(TextField input, String unitText) {
        input.getStyleClass().remove("analytics-field-control");
        input.getStyleClass().add("analytics-unit-input");
        Label unit = new Label(unitText);
        unit.getStyleClass().add("analytics-unit");
        unit.setMinWidth(Region.USE_PREF_SIZE);
        HBox.setHgrow(input, Priority.NEVER);
        HBox row = new HBox(6, input, unit);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("analytics-input-row");
        return row;
    }

    private static void sizeInput(javafx.scene.control.Control input) {
        input.getStyleClass().add("analytics-field-control");
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static <T> void present(ComboBox<T> box, java.util.function.Function<T, String> label) {
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : label.apply(value);
            }

            @Override
            public T fromString(String text) {
                return box.getValue();
            }
        });
    }

    private static double parse(String raw, double fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
