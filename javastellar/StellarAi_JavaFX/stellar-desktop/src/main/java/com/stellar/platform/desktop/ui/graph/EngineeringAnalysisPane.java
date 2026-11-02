package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.analysis.report.EngineeringValueFormatter;
import com.stellar.platform.desktop.presentation.CategoricalSummary;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphRecordingAlignment;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import com.stellar.platform.processing.analysis.AutomotiveAnalysisEngine;
import com.stellar.platform.processing.analysis.AutomotiveAnalysisEngine.*;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.util.StringConverter;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

/**
 * Phase 12 advanced engineering analysis over renderer-neutral viewport data.
 * This pane is deliberately observational: it never mutates canonical samples,
 * parser output, DBC mapping, timestamps, physical values, or decode semantics.
 */
public final class EngineeringAnalysisPane extends BorderPane {
    private static final long MAX_ENGINEERING_LAG_NANOS = 10_000_000_000L; // bounded observational lag search (±10 s)
    private final Label headline = new Label("Select signals to analyse");
    private final Label guidance = new Label("Engineering summary follows the active waveform viewport.");
    private final TableView<Row> table = new TableView<>();
    private final TableView<PairRow> relationships = new TableView<>();
    private final TableView<EventRow> events = new TableView<>();
    private final TableView<WindowRow> eventWindow = new TableView<>();
    private final TableView<BookmarkRow> bookmarks = new TableView<>();
    private final TableView<FeatureRow> advancedAnalytics = new TableView<>();
    private final TableView<FindingRow> diagnosticFindings = new TableView<>();
    private List<DiagnosticRule> diagnosticRules = List.of();
    private final javafx.collections.ObservableList<BookmarkRow> bookmarkItems = FXCollections.observableArrayList();
    private List<GraphRenderSeries> currentSeries = List.of();
    private Map<SignalCatalogKey, String> seriesLabels = Map.of();
    private final ExecutorService analysisWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "stellar-engineering");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Future<?> pendingAnalysis;
    private int analysisGeneration;
    private volatile boolean workerRanOffFx;
    private LongConsumer drillDownHandler = t -> {};
    private java.util.function.Consumer<BookmarkRow> bookmarkPersistHandler;
    private java.util.function.Consumer<RunSpec> runHandler = spec -> {};
    private java.util.function.Consumer<Boolean> overlayHandler = visible -> {};
    private java.util.function.Consumer<Path> exportHandler = path -> {};
    private Runnable addToInvestigationHandler = () -> {};
    private final EngineeringDiagnosticsPane diagnosticsPane = new EngineeringDiagnosticsPane();
    private final EngineeringRulesPane rulesPane = new EngineeringRulesPane();
    private final AdvancedAnalyticsPane advancedPane = new AdvancedAnalyticsPane();
    private final ComboBox<String> analysisType = new ComboBox<>();
    private final ComboBox<String> rangeSource = new ComboBox<>();
    private final TextField thresholdField = new TextField();
    private final TextField hysteresisField = new TextField();
    private final CheckBox showOverlays = new CheckBox("Show events on graph");
    private final TextArea resultReport = new TextArea();

    public record RunSpec(String type, String range, Double threshold, Double hysteresisExit) {}

    public EngineeringAnalysisPane() {
        setPadding(new Insets(14, 16, 10, 16));
        headline.getStyleClass().add("section-title");
        guidance.getStyleClass().add("muted");
        guidance.setWrapText(true);
        analysisType.getItems().addAll(
                "STATISTICS", "CURSOR_DELTA", "RATE_OF_CHANGE", "THRESHOLD",
                "STATE_TRANSITION", "DURATION", "OPERATING_BAND", "COMPARISON",
                "DERIVED", "CORRELATION", "LAG");
        analysisType.getSelectionModel().select("STATISTICS");
        rangeSource.getItems().addAll("FULL", "VIEWPORT", "CURSOR_A_TO_B", "CUSTOM");
        rangeSource.getSelectionModel().select("VIEWPORT");
        thresholdField.setPromptText("Threshold");
        hysteresisField.setPromptText("Hysteresis exit");
        presentChoice(analysisType, AnalyticsControlLabels::analysisType);
        presentChoice(rangeSource, AnalyticsControlLabels::range);
        analysisType.getStyleClass().add("analytics-field-control");
        rangeSource.getStyleClass().add("analytics-field-control");
        Button run = new Button("Run Analysis");
        run.getStyleClass().add("primary");
        run.setOnAction(e -> runHandler.accept(currentSpec()));
        Button export = new Button("Export CSV");
        export.setOnAction(e -> exportHandler.accept(null));
        Button addEvidence = new Button("Add to Investigation Evidence");
        addEvidence.setOnAction(e -> addToInvestigationHandler.run());
        showOverlays.setSelected(true);
        showOverlays.selectedProperty().addListener((o, a, b) -> overlayHandler.accept(b));
        resultReport.setEditable(false);
        resultReport.setPrefRowCount(8);
        resultReport.getStyleClass().addAll("mono", "analytics-results");
        resultReport.setPromptText("ENGINEERING ANALYSIS");
        Label typeLabel = new Label("Analysis Type");
        typeLabel.getStyleClass().add("analytics-field-label");
        Label rangeLabel = new Label("Range");
        rangeLabel.getStyleClass().add("analytics-field-label");
        FlowPane controls = new FlowPane(8, 8, typeLabel, analysisType, rangeLabel, rangeSource,
                thresholdField, hysteresisField, run, export, addEvidence, showOverlays);
        controls.getStyleClass().add("analytics-run-bar");
        thresholdField.getStyleClass().add("analytics-field-control");
        hysteresisField.getStyleClass().add("analytics-field-control");
        controls.setPrefWrapLength(720);
        widthProperty().addListener((o, a, b) -> {
            double available = b.doubleValue() - 32;
            if (available > 280 && Math.abs(controls.getPrefWrapLength() - available) > 4) {
                controls.setPrefWrapLength(available);
            }
        });
        VBox top = new VBox(6, headline, guidance, controls, resultReport);
        top.setPadding(new Insets(0,0,10,0));

        configureSummary();
        configureRelationships();
        configureEvents();
        configureEventWindow();
        configureBookmarks();
        configureAdvancedAnalytics();
        configureDiagnosticFindings();

        TabPane details = new TabPane();
        details.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        Tab summaryTab = new Tab("Signal summary", table);
        Tab relationTab = new Tab("Relationships", relationships);
        Tab eventTab = new Tab("Transitions / extrema", events);
        Tab windowTab = new Tab("Event window", eventWindow);
        Tab bookmarkTab = new Tab("Bookmarks", bookmarks);
        Tab rulesTab = new Tab("Diagnostic findings", diagnosticFindings);
        Tab analyticsTab = new Tab("Advanced analytics", advancedAnalytics);
        details.getTabs().addAll(summaryTab, relationTab, eventTab, windowTab, bookmarkTab,
                rulesTab, analyticsTab);

        TabPane domainTabs = new TabPane();
        domainTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        domainTabs.getTabs().addAll(
                new Tab("Time Domain", details),
                new Tab("Frequency Domain", advancedPane),
                new Tab("Comparison", new Label("Lag, coherence and two-signal spectra are in Frequency Domain.")));

        BorderPane analysis = new BorderPane();
        analysis.setTop(top);
        analysis.setCenter(domainTabs);

        TabPane workspace = new TabPane();
        workspace.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        workspace.getTabs().addAll(
                new Tab("Analysis", analysis),
                new Tab("Diagnostics", diagnosticsPane),
                new Tab("Rules", rulesPane));
        setCenter(workspace);
    }

    public void setDrillDownHandler(LongConsumer handler) {
        drillDownHandler = handler == null ? t -> {} : handler;
    }

    public void setRunHandler(java.util.function.Consumer<RunSpec> handler) {
        runHandler = handler == null ? spec -> {} : handler;
    }

    public void setOverlayHandler(java.util.function.Consumer<Boolean> handler) {
        overlayHandler = handler == null ? visible -> {} : handler;
    }

    public void setExportHandler(java.util.function.Consumer<Path> handler) {
        exportHandler = handler == null ? path -> {} : handler;
    }

    public void setAddToInvestigationHandler(Runnable handler) {
        addToInvestigationHandler = handler == null ? () -> {} : handler;
    }

    public void showAnalysisReport(String report) {
        resultReport.setText(report == null ? "" : report);
    }

    public void restoreAnalysisType(String type) {
        if (type != null && analysisType.getItems().contains(type)) {
            analysisType.getSelectionModel().select(type);
        }
    }

    public void setRunInputsForTest(String type, String range, String threshold) {
        restoreAnalysisType(type);
        if (range != null && rangeSource.getItems().contains(range)) {
            rangeSource.getSelectionModel().select(range);
        }
        if (threshold != null) {
            thresholdField.setText(threshold);
        }
    }

    public String thresholdTextForTest() {
        return thresholdField.getText();
    }

    public String analysisTypeForTest() {
        String value = analysisType.getValue();
        return value == null ? "" : value;
    }

    public boolean overlaysVisible() {
        return showOverlays.isSelected();
    }

    public EngineeringDiagnosticsPane diagnostics() {
        return diagnosticsPane;
    }

    public EngineeringRulesPane rules() {
        return rulesPane;
    }

    public AdvancedAnalyticsPane advanced() {
        return advancedPane;
    }

    private RunSpec currentSpec() {
        Double th = parse(thresholdField.getText());
        Double hy = parse(hysteresisField.getText());
        return new RunSpec(analysisType.getValue(), rangeSource.getValue(), th, hy);
    }

    private static Double parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void configureSummary() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No samples in the current viewport"));
        table.getColumns().add(column("Signal", Row::signal, 220));
        table.getColumns().add(column("Unit", Row::unit, 75));
        table.getColumns().add(column("Min", Row::min, 95));
        table.getColumns().add(column("Max", Row::max, 95));
        table.getColumns().add(column("Range", Row::range, 95));
        table.getColumns().add(column("Viewport samples", Row::samples, 120));
        table.getColumns().add(column("Viewport coverage", Row::coverage, 120));
        table.getColumns().add(column("Activity", Row::activity, 95, "Descriptive variation classification based on represented viewport movement; not diagnostic severity."));
        table.getColumns().add(column("Engineering cue", Row::cue, 260));
    }

    private void configureRelationships() {
        relationships.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        relationships.setPlaceholder(new Label("Select at least two signals with overlapping viewport data"));
        relationships.getColumns().add(column("Signal A", PairRow::a, 220));
        relationships.getColumns().add(column("Signal B", PairRow::b, 220));
        relationships.getColumns().add(column("Aligned points", PairRow::points, 105, "Time-aligned represented pairs. Alignment tolerance is derived from the active viewport."));
        relationships.getColumns().add(column("Correlation", PairRow::correlation, 105, "Pearson association of aligned represented points. Correlation does not imply causation."));
        relationships.getColumns().add(column("Strength", PairRow::strength, 105));
        relationships.getColumns().add(column("Direction", PairRow::direction, 105));
        relationships.getColumns().add(column("Lag", PairRow::lag, 105, "Absolute best observational lag within a bounded ±10 s engineering search window."));
        relationships.getColumns().add(column("Lead / lag", PairRow::timing, 210, "Explicit direction: which signal leads and which follows. A boundary result is flagged for review."));
        relationships.getColumns().add(column("Engineering cue", PairRow::cue, 300));
    }

    private void configureEvents() {
        events.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        events.setPlaceholder(new Label("No significant transitions/extrema in the current viewport"));
        events.getColumns().add(column("Time", EventRow::time, 120));
        events.getColumns().add(column("Signal", EventRow::signal, 230));
        events.getColumns().add(column("Type", EventRow::type, 120));
        events.getColumns().add(column("Value", EventRow::value, 110));
        events.getColumns().add(column("Δ (% viewport range)", EventRow::magnitude, 145, "Absolute step size normalized by this signal’s represented viewport range."));
        events.getColumns().add(column("Engineering cue", EventRow::cue, 320));
        events.setRowFactory(v -> {
            TableRow<EventRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (row.isEmpty()) return;
                updateEventWindow(row.getItem());
                if (e.getClickCount() == 2) drillDownHandler.accept(row.getItem().timestampNanos());
            });
            ContextMenu menu = new ContextMenu();
            MenuItem bookmark = new MenuItem("Add engineering bookmark");
            bookmark.setOnAction(e -> { if (!row.isEmpty()) addBookmark(row.getItem()); });
            MenuItem drill = new MenuItem("Drill into waveform");
            drill.setOnAction(e -> { if (!row.isEmpty()) drillDownHandler.accept(row.getItem().timestampNanos()); });
            menu.getItems().addAll(drill, bookmark);
            row.contextMenuProperty().bind(javafx.beans.binding.Bindings.when(row.emptyProperty()).then((ContextMenu)null).otherwise(menu));
            return row;
        });
    }

    private void configureEventWindow() {
        eventWindow.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        eventWindow.setPlaceholder(new Label("Select a transition/extrema event to inspect synchronized signals"));
        eventWindow.getColumns().add(column("Signal", WindowRow::signal, 230));
        eventWindow.getColumns().add(column("Unit", WindowRow::unit, 80));
        eventWindow.getColumns().add(column("Before (-window)", WindowRow::before, 130));
        eventWindow.getColumns().add(column("At event", WindowRow::at, 120));
        eventWindow.getColumns().add(column("After (+window)", WindowRow::after, 130));
        eventWindow.getColumns().add(column("Δ before→after", WindowRow::delta, 135));
        eventWindow.getColumns().add(column("Engineering cue", WindowRow::cue, 300));
    }

    /** Phase 13 rules are injected as configuration; no signal-specific diagnostic knowledge is hardcoded here. */
    public void setDiagnosticRules(List<DiagnosticRule> rules) {
        diagnosticRules = rules == null ? List.of() : List.copyOf(rules);
        refreshPhase13And14();
    }

    private void configureDiagnosticFindings() {
        diagnosticFindings.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        diagnosticFindings.setPlaceholder(new Label("No enabled diagnostic rule is triggered in this viewport — this does not mean no fault is present"));
        diagnosticFindings.getColumns().add(column("Time", FindingRow::time, 120));
        diagnosticFindings.getColumns().add(column("Severity", FindingRow::severity, 90));
        diagnosticFindings.getColumns().add(column("Rule", FindingRow::rule, 150));
        diagnosticFindings.getColumns().add(column("Finding", FindingRow::title, 240));
        diagnosticFindings.getColumns().add(column("Rule confidence", FindingRow::confidence, 120, "Configured/evidence confidence for this rule finding; not ML probability."));
        diagnosticFindings.getColumns().add(column("Evidence", FindingRow::evidence, 330));
        diagnosticFindings.getColumns().add(column("Explanation", FindingRow::explanation, 360));
        diagnosticFindings.setRowFactory(v -> { TableRow<FindingRow> row=new TableRow<>(); row.setOnMouseClicked(e->{if(e.getClickCount()==2&&!row.isEmpty())drillDownHandler.accept(row.getItem().timestampNanos());}); return row; });
    }

    private void configureAdvancedAnalytics() {
        advancedAnalytics.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        advancedAnalytics.setPlaceholder(new Label("No represented samples available for advanced analytics"));
        advancedAnalytics.getColumns().add(column("Signal", FeatureRow::signal, 220));
        advancedAnalytics.getColumns().add(column("Unit", FeatureRow::unit, 75));
        advancedAnalytics.getColumns().add(column("Mean", FeatureRow::mean, 95));
        advancedAnalytics.getColumns().add(column("Median", FeatureRow::median, 95));
        advancedAnalytics.getColumns().add(column("Std dev", FeatureRow::sd, 95));
        advancedAnalytics.getColumns().add(column("RMS", FeatureRow::rms, 95));
        advancedAnalytics.getColumns().add(column("P05 / P95", FeatureRow::percentiles, 150));
        advancedAnalytics.getColumns().add(column("Slope (unit/s)", FeatureRow::slope, 115));
        advancedAnalytics.getColumns().add(column("Drift (unit/min)", FeatureRow::drift, 125));
        advancedAnalytics.getColumns().add(column("Mean |rate| (unit/s)", FeatureRow::rate, 145));
        advancedAnalytics.getColumns().add(column("Peaks / valleys", FeatureRow::extrema, 125));
        advancedAnalytics.getColumns().add(column("Steps", FeatureRow::steps, 70));
        advancedAnalytics.getColumns().add(column("Outliers", FeatureRow::outliers, 75));
        advancedAnalytics.getColumns().add(column("Jitter (s)", FeatureRow::jitter, 100));
        advancedAnalytics.getColumns().add(column("Oscillation (Hz)", FeatureRow::oscillation, 120));
    }

    private void refreshPhase13And14() {
        List<SignalFeatureSet> features=AutomotiveAnalysisEngine.extractFeatures(currentSeries);
        ArrayList<FeatureRow> fr=new ArrayList<>();
        for(SignalFeatureSet f:features) fr.add(new FeatureRow(f.signal(),f.unit(),fmt(f.mean()),fmt(f.median()),fmt(f.standardDeviation()),fmt(f.rms()),fmt(f.p05())+" / "+fmt(f.p95()),fmt(f.slopePerSecond()),fmt(f.driftPerMinute()),fmt(f.meanAbsRatePerSecond()),f.peakCount()+" / "+f.valleyCount(),Integer.toString(f.stepChangeCount()),Integer.toString(f.outlierCount()),fmt(f.jitter()),fmt(f.oscillationHz())));
        advancedAnalytics.setItems(FXCollections.observableArrayList(fr));
        ArrayList<FindingRow> rr=new ArrayList<>();
        for(DiagnosticFinding f:AutomotiveAnalysisEngine.evaluateRules(currentSeries,diagnosticRules)){
            String evidence=f.evidence().stream().map(e->e.signal()+"="+fmt(e.value())+" ("+e.condition()+")").reduce((a,b)->a+"; "+b).orElse("—");
            rr.add(new FindingRow(String.format("%.6f s",f.timestampNanos()/1e9),f.severity().name(),f.ruleId()+" v"+f.ruleVersion(),f.title(),String.format("%.0f%%",f.confidence()*100),evidence,f.explanation(),f.timestampNanos()));
        }
        diagnosticFindings.setItems(FXCollections.observableArrayList(rr));
    }

    private void configureBookmarks() {
        bookmarks.setItems(bookmarkItems);
        bookmarks.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        bookmarks.setPlaceholder(new Label("Right-click an engineering event to bookmark it — bookmarks persist with the project"));
        bookmarks.getColumns().add(column("Time", BookmarkRow::time, 120));
        bookmarks.getColumns().add(column("Signal", BookmarkRow::signal, 230));
        bookmarks.getColumns().add(column("Event", BookmarkRow::type, 120));
        bookmarks.getColumns().add(column("Observation", BookmarkRow::observation, 360));
        bookmarks.setRowFactory(v -> { TableRow<BookmarkRow> row=new TableRow<>(); row.setOnMouseClicked(e->{if(e.getClickCount()==2&&!row.isEmpty())drillDownHandler.accept(row.getItem().timestampNanos());}); return row; });
    }

    private void addBookmark(EventRow e) {
        boolean exists=bookmarkItems.stream().anyMatch(b->b.timestampNanos()==e.timestampNanos()&&b.signal().equals(e.signal()));
        if(!exists) {
            BookmarkRow row = new BookmarkRow(e.time(),e.signal(),e.type(),e.cue(),e.timestampNanos());
            bookmarkItems.add(row);
            if (bookmarkPersistHandler != null) {
                bookmarkPersistHandler.accept(row);
            }
        }
    }

    /** Replaces session bookmarks (used when opening a saved project). */
    public void replaceBookmarks(List<BookmarkRow> rows) {
        bookmarkItems.setAll(rows == null ? List.of() : rows);
    }

    public void clearBookmarks() {
        bookmarkItems.clear();
    }

    public void setBookmarkPersistHandler(java.util.function.Consumer<BookmarkRow> handler) {
        bookmarkPersistHandler = handler;
    }

    private void updateEventWindow(EventRow event) {
        if(event==null||currentSeries.isEmpty()){eventWindow.getItems().clear();return;}
        long viewportSpan=currentSeries.stream().mapToLong(s->Math.max(1,s.viewportEndTimestampNanos()-s.viewportStartTimestampNanos())).max().orElse(1);
        long half=Math.max(1,viewportSpan/20); ArrayList<WindowRow> rows=new ArrayList<>();
        for(GraphRenderSeries s:currentSeries){
            List<TimedValue> p=representativePoints(s); if(p.isEmpty())continue;
            TimedValue before=nearest(p,event.timestampNanos()-half), at=nearest(p,event.timestampNanos()), after=nearest(p,event.timestampNanos()+half);
            if(at==null)continue; String delta=before==null||after==null?"—":fmt(after.value-before.value);
            String cue=before!=null&&after!=null&&Math.abs(after.value-before.value)>0?"Signal changed across synchronized event window":"No material represented change across window";
            rows.add(new WindowRow(displayName(s, seriesLabels),s.unit(),val(before)+" @ -"+String.format("%.3f s",half/1e9),val(at),val(after)+" @ +"+String.format("%.3f s",half/1e9),delta,cue));
        }
        eventWindow.setItems(FXCollections.observableArrayList(rows));
    }
    private static TimedValue nearest(List<TimedValue> p,long t){ TimedValue best=null; long d=Long.MAX_VALUE; for(TimedValue x:p){long q=Math.abs(x.timestamp-t);if(q<d){d=q;best=x;}}return best; }
    private static String val(TimedValue p){return p==null?"—":fmt(p.value);}

    private static <T> TableColumn<T,String> column(String title, java.util.function.Function<T,String> f, double width) {
        return column(title, f, width, null);
    }

    /** Every text cell exposes its complete value on hover, even when JavaFX clips it with an ellipsis. */
    private static <T> TableColumn<T,String> column(String title, java.util.function.Function<T,String> f, double width, String headerHelp) {
        TableColumn<T,String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        if (headerHelp != null && !headerHelp.isBlank()) { c.setText(""); c.setGraphic(helpHeader(title, headerHelp)); }
        c.setCellValueFactory(v -> new SimpleStringProperty(f.apply(v.getValue())));
        c.setCellFactory(col -> new TableCell<>() {
            private final Tooltip tooltip = new Tooltip();
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setTooltip(null); setContextMenu(null); return; }
                setText(item);
                tooltip.setText(item);
                setTooltip(tooltip);
                ContextMenu menu = new ContextMenu();
                MenuItem copy = new MenuItem("Copy full value");
                copy.setOnAction(e -> { ClipboardContent cc=new ClipboardContent(); cc.putString(item); Clipboard.getSystemClipboard().setContent(cc); });
                menu.getItems().add(copy);
                setContextMenu(menu);
            }
        });
        return c;
    }

    private static Label helpHeader(String title, String help) {
        Label label = new Label(title);
        label.setTooltip(new Tooltip(help));
        return label;
    }

    public void setSeries(List<GraphRenderSeries> series) {
        setScopedSeries(series, Map.of(), "");
    }

    public void showTimelineRefusal(String message) {
        analysisGeneration++;
        currentSeries = List.of();
        clearViewportDerivedTables();
        headline.setText("Analysis unavailable");
        guidance.setText(message == null ? "" : message);
    }

    public void setScopedSeries(List<GraphRenderSeries> series, Map<SignalCatalogKey, String> labels, String scopeNote) {
        int token = ++analysisGeneration;
        List<GraphRenderSeries> safe = series == null ? List.of() : List.copyOf(series);
        Map<SignalCatalogKey, String> labelCopy = labels == null ? Map.of() : Map.copyOf(labels);
        String scope = scopeNote == null ? "" : scopeNote;
        currentSeries = safe;
        seriesLabels = labelCopy;
        if (safe.isEmpty()) {
            clearViewportDerivedTables();
            headline.setText("Select signals to analyse");
            guidance.setText("Engineering summary follows the active waveform viewport.");
            return;
        }
        List<DiagnosticRule> rules = List.copyOf(diagnosticRules);
        pendingAnalysis = analysisWorker.submit(() -> {
            workerRanOffFx = !Platform.isFxApplicationThread();
            Prepared prepared = prepare(safe, labelCopy, scope, rules);
            Platform.runLater(() -> {
                if (token != analysisGeneration) {
                    return;
                }
                publish(prepared);
            });
        });
    }

    public void flushEngineeringForTest() throws Exception {
        Future<?> pending = pendingAnalysis;
        if (pending != null) {
            pending.get(5, TimeUnit.SECONDS);
        }
    }

    public boolean lastComputeLeftFxThreadForTest() {
        return workerRanOffFx;
    }

    public String headlineForTest() {
        return headline.getText();
    }

    public String guidanceForTest() {
        return guidance.getText();
    }

    public int relationshipCountForTest() {
        return relationships.getItems().size();
    }

    private void publish(Prepared prepared) {
        table.setItems(FXCollections.observableArrayList(prepared.rows()));
        relationships.setItems(FXCollections.observableArrayList(prepared.relationships()));
        events.setItems(FXCollections.observableArrayList(prepared.events()));
        eventWindow.getItems().clear();
        advancedAnalytics.setItems(FXCollections.observableArrayList(prepared.features()));
        diagnosticFindings.setItems(FXCollections.observableArrayList(prepared.findings()));
        headline.setText(prepared.headline());
        guidance.setText(prepared.guidance());
    }

    private static Prepared prepare(List<GraphRenderSeries> safe, Map<SignalCatalogKey, String> labels, String scope, List<DiagnosticRule> rules) {
        ArrayList<Row> rows = new ArrayList<>();
        long totalViewportSamples = 0;
        for (GraphRenderSeries s : safe) {
            String name = displayName(s, labels);
            long count = s.buckets().stream().mapToLong(GraphBucket::sampleCount).sum();
            totalViewportSamples += count;
            if (s.buckets().isEmpty()) {
                rows.add(new Row(name, s.unit(), "—", "—", "—", "0", "0%", "No data", "No samples in viewport"));
                continue;
            }
            double min = s.buckets().stream().filter(GraphBucket::minUsable).mapToDouble(GraphBucket::minValue).min().orElse(Double.NaN);
            double max = s.buckets().stream().filter(GraphBucket::maxUsable).mapToDouble(GraphBucket::maxValue).max().orElse(Double.NaN);
            String categorical = s.buckets().stream().map(GraphBucket::categoricalLabel).filter(label -> label != null && !label.isBlank()).findFirst().orElse(null);
            if (categorical != null && !Double.isFinite(min)) {
                rows.add(new Row(name, s.unit(),
                        CategoricalSummary.UNAVAILABLE,
                        CategoricalSummary.UNAVAILABLE,
                        CategoricalSummary.UNAVAILABLE,
                        String.format("%,d", count),
                        String.format("%.1f%%", s.sourceSampleCount() <= 0 ? 0d : Math.min(100d, count * 100d / s.sourceSampleCount())),
                        "Low", CategoricalSummary.engineeringCue(categorical)));
                continue;
            }
            double range = max - min;
            double coverage = s.sourceSampleCount() <= 0 ? 0d : Math.min(100d, count * 100d / s.sourceSampleCount());
            double activity = activityScore(s, min, max);
            String activityText = activity >= 0.60 ? "High" : activity >= 0.20 ? "Medium" : "Low";
            String cue;
            if (!Double.isFinite(range)) cue = "Non-finite values present";
            else if (range == 0d) cue = "Constant in viewport";
            else if (count <= 2) cue = "Sparse data — inspect timing";
            else if (activity >= 0.60) cue = "High variation — inspect transitions/extrema";
            else cue = "Stable/normal variation in viewport";
            rows.add(new Row(name, s.unit(), fmt(min), fmt(max), fmt(range), String.format("%,d", count), String.format("%.1f%%", coverage), activityText, cue));
        }
        rows.sort(Comparator.comparing(Row::activity, Comparator.reverseOrder()).thenComparing(Row::signal));
        boolean mixed = mixedRecordings(safe);
        String headline = safe.size() + " signal(s)" + (scope.isBlank() ? "" : " · " + scope) + " · engineering viewport summary";
        String guidance = String.format("%,d viewport samples represented · relationships use time-aligned LOD points with bounded lag search · double-click evidence to drill into waveform · cues are descriptive, not diagnostic conclusions.", totalViewportSamples);
        if (!scope.isBlank()) {
            guidance = "Measurement: " + scope + " · " + guidance;
        }
        if (mixed) {
            guidance = GraphRecordingAlignment.CROSS_FILE_UNAVAILABLE + " " + guidance;
        }
        return new Prepared(rows, buildRelationships(safe, labels), buildEvents(safe, labels), featuresOf(safe), findingsOf(safe, rules), headline, guidance);
    }

    private static String displayName(GraphRenderSeries series, Map<SignalCatalogKey, String> labels) {
        if (series == null || series.key() == null) {
            return "";
        }
        String label = labels == null ? null : labels.get(series.key());
        return label == null || label.isBlank() ? series.key().signalName() : label;
    }

    private static boolean mixedRecordings(List<GraphRenderSeries> series) {
        String first = null;
        for (GraphRenderSeries seriesItem : series) {
            if (seriesItem.key() == null || seriesItem.key().sourceFile() == null) {
                continue;
            }
            String source = seriesItem.key().sourceFile().trim();
            if (source.isEmpty()) {
                continue;
            }
            if (first == null) {
                first = source;
            } else if (!first.equals(source)) {
                return true;
            }
        }
        return false;
    }

    private static List<FeatureRow> featuresOf(List<GraphRenderSeries> series) {
        ArrayList<FeatureRow> rows = new ArrayList<>();
        for (SignalFeatureSet feature : AutomotiveAnalysisEngine.extractFeatures(series)) {
            rows.add(new FeatureRow(feature.signal(), feature.unit(), fmt(feature.mean()), fmt(feature.median()), fmt(feature.standardDeviation()), fmt(feature.rms()), fmt(feature.p05()) + " / " + fmt(feature.p95()), fmt(feature.slopePerSecond()), fmt(feature.driftPerMinute()), fmt(feature.meanAbsRatePerSecond()), feature.peakCount() + " / " + feature.valleyCount(), Integer.toString(feature.stepChangeCount()), Integer.toString(feature.outlierCount()), fmt(feature.jitter()), fmt(feature.oscillationHz())));
        }
        return rows;
    }

    private static List<FindingRow> findingsOf(List<GraphRenderSeries> series, List<DiagnosticRule> rules) {
        ArrayList<FindingRow> rows = new ArrayList<>();
        for (DiagnosticFinding finding : AutomotiveAnalysisEngine.evaluateRules(series, rules)) {
            String evidence = finding.evidence().stream().map(item -> item.signal() + "=" + fmt(item.value()) + " (" + item.condition() + ")").reduce((left, right) -> left + "; " + right).orElse("—");
            rows.add(new FindingRow(String.format("%.6f s", finding.timestampNanos() / 1e9), finding.severity().name(), finding.ruleId() + " v" + finding.ruleVersion(), finding.title(), String.format("%.0f%%", finding.confidence() * 100), evidence, finding.explanation(), finding.timestampNanos()));
        }
        return rows;
    }

    private record Prepared(List<Row> rows, List<PairRow> relationships, List<EventRow> events, List<FeatureRow> features, List<FindingRow> findings, String headline, String guidance) {}

    /**
     * Graph Clear: drop temporary analysis input tables while keeping persisted bookmarks.
     */
    public void clearViewportState() {
        analysisGeneration++;
        currentSeries = List.of();
        clearViewportDerivedTables();
        headline.setText("Select signals to analyse");
        guidance.setText("Engineering summary follows the active waveform viewport.");
    }

    private void clearViewportDerivedTables() {
        table.getItems().clear();
        relationships.getItems().clear();
        events.getItems().clear();
        eventWindow.getItems().clear();
        advancedAnalytics.getItems().clear();
        diagnosticFindings.getItems().clear();
    }

    /** NFT helper. */
    public int currentSeriesCountForTest() {
        return currentSeries.size();
    }

    public int bookmarkCountForTest() {
        return bookmarkItems.size();
    }

    private static List<PairRow> buildRelationships(List<GraphRenderSeries> series, Map<SignalCatalogKey, String> labels) {
        ArrayList<PairRow> out = new ArrayList<>();
        for (int i=0;i<series.size();i++) for (int j=i+1;j<series.size();j++) {
            GraphRenderSeries a=series.get(i), b=series.get(j);
            if (!GraphRecordingAlignment.sameRecording(a, b)) {
                continue;
            }
            List<TimedValue> aa=representativePoints(a), bb=representativePoints(b);
            List<double[]> aligned=align(aa,bb,a.viewportStartTimestampNanos(),a.viewportEndTimestampNanos());
            if (aligned.size()<3) continue;
            double r=pearson(aligned);
            if (!Double.isFinite(r)) continue;
            LagResult lag=bestLag(aa,bb,a.viewportStartTimestampNanos(),a.viewportEndTimestampNanos());
            double ar=Math.abs(r);
            String strength=ar>=.85?"Very strong":ar>=.65?"Strong":ar>=.40?"Moderate":ar>=.20?"Weak":"Very weak";
            String direction=r>.05?"Positive":r<-.05?"Negative":"Neutral";
            String aName=displayName(a, labels), bName=displayName(b, labels);
            boolean boundary=lag.searchLimitNanos>0 && Math.abs(lag.lagNanos)>=lag.searchLimitNanos;
            String timing=lag.lagNanos>0?aName+" leads "+bName:lag.lagNanos<0?bName+" leads "+aName:"Synchronous / no represented lag";
            if(boundary) timing += " · boundary";
            String cue=(ar>=.65?"Association visible":"Limited association")+" — timing is observational, not causality; correlation does not imply causation"+(boundary?" · best result reached lag-window boundary; widen only if engineering context justifies it":"");
            out.add(new PairRow(aName,bName,Integer.toString(aligned.size()),String.format("%+.3f",r),strength,direction,String.format("%.3f s",Math.abs(lag.lagNanos)/1e9),timing,cue,ar));
        }
        out.sort(Comparator.comparingDouble(PairRow::absCorrelation).reversed());
        return out;
    }

    private static List<EventRow> buildEvents(List<GraphRenderSeries> series, Map<SignalCatalogKey, String> labels) {
        ArrayList<EventRow> out=new ArrayList<>();
        for(GraphRenderSeries s:series){
            List<TimedValue> p=representativePoints(s);
            if(p.isEmpty())continue;
            double min=p.stream().mapToDouble(TimedValue::value).min().orElse(Double.NaN), max=p.stream().mapToDouble(TimedValue::value).max().orElse(Double.NaN);
            double span=max-min;
            if(!Double.isFinite(span)||span<=0)continue;
            for(int i=1;i<p.size();i++){
                TimedValue prev=p.get(i-1), cur=p.get(i); double delta=cur.value-prev.value;
                double normalized=Math.abs(delta)/span;
                if(normalized>=0.35) out.add(event(displayName(s, labels),cur,"Transition",cur.value,normalized,"Large relative step — inspect surrounding signals"));
            }
            TimedValue lo=p.stream().min(Comparator.comparingDouble(TimedValue::value)).orElse(null);
            TimedValue hi=p.stream().max(Comparator.comparingDouble(TimedValue::value)).orElse(null);
            if(lo!=null)out.add(event(displayName(s, labels),lo,"Minimum",lo.value,0,"Viewport minimum"));
            if(hi!=null&&hi.timestamp!=lo.timestamp)out.add(event(displayName(s, labels),hi,"Maximum",hi.value,0,"Viewport maximum"));
        }
        out.sort(Comparator.comparingLong(EventRow::timestampNanos));
        if(out.size()>500)return List.copyOf(out.subList(0,500));
        return out;
    }

    private static EventRow event(String signal,TimedValue p,String type,double value,double magnitude,String cue){
        return new EventRow(String.format("%.6f s",p.timestamp/1e9),signal,type,fmt(value),magnitude==0?"—":String.format("%.1f%%",magnitude*100),cue,p.timestamp);
    }

    /** Build a bounded, time ordered representation from LOD buckets without touching raw/canonical data. */
    private static List<TimedValue> representativePoints(GraphRenderSeries s){
        ArrayList<TimedValue> p=new ArrayList<>(s.buckets().size()*2);
        for(GraphBucket b:s.buckets()){
            if(b.firstUsable())p.add(new TimedValue(b.firstTimestampNanos(),b.firstValue()));
            if(b.lastTimestampNanos()!=b.firstTimestampNanos()&&b.lastUsable())p.add(new TimedValue(b.lastTimestampNanos(),b.lastValue()));
        }
        p.sort(Comparator.comparingLong(TimedValue::timestamp));
        return p;
    }

    private static List<double[]> align(List<TimedValue>a,List<TimedValue>b,long start,long end){
        if(a.isEmpty()||b.isEmpty())return List.of();
        long tolerance=Math.max(1,(end-start)/250); ArrayList<double[]> out=new ArrayList<>(); int j=0;
        for(TimedValue x:a){
            while(j+1<b.size()&&Math.abs(b.get(j+1).timestamp-x.timestamp)<=Math.abs(b.get(j).timestamp-x.timestamp))j++;
            TimedValue y=b.get(j); if(Math.abs(y.timestamp-x.timestamp)<=tolerance)out.add(new double[]{x.value,y.value});
        }
        return out;
    }

    private static LagResult bestLag(List<TimedValue>a,List<TimedValue>b,long start,long end){
        long span=Math.max(1,end-start);
        long searchLimit=Math.min(MAX_ENGINEERING_LAG_NANOS, Math.max(1,span/10));
        long step=Math.max(1,searchLimit/20);
        double best=Double.NaN; long bestLag=0;
        for(int k=-20;k<=20;k++){long lag=k*step; ArrayList<double[]> pairs=new ArrayList<>(); int j=0;
            for(TimedValue x:a){long target=x.timestamp+lag;while(j+1<b.size()&&Math.abs(b.get(j+1).timestamp-target)<=Math.abs(b.get(j).timestamp-target))j++;TimedValue y=b.get(j);if(Math.abs(y.timestamp-target)<=step*2)pairs.add(new double[]{x.value,y.value});}
            if(pairs.size()<3)continue;double rr=pearson(pairs);if(Double.isFinite(rr)&&(!Double.isFinite(best)||Math.abs(rr)>Math.abs(best))){best=rr;bestLag=lag;}
        } return new LagResult(bestLag,best,searchLimit);
    }

    private static double pearson(List<double[]> v){
        int n=v.size(); double sx=0,sy=0,sxx=0,syy=0,sxy=0;
        for(double[]p:v){sx+=p[0];sy+=p[1];sxx+=p[0]*p[0];syy+=p[1]*p[1];sxy+=p[0]*p[1];}
        double num=n*sxy-sx*sy, den=Math.sqrt((n*sxx-sx*sx)*(n*syy-sy*sy)); return den==0?Double.NaN:num/den;
    }

    private static double activityScore(GraphRenderSeries s, double min, double max) {
        double span = max - min;
        if (!Double.isFinite(span) || span <= 0 || s.buckets().isEmpty()) return 0;
        double movement = 0; int n = 0; Double previous = null;
        for (GraphBucket b : s.buckets()) {
            boolean saw = false;
            if (b.firstUsable()) {
                if (previous != null) { movement += Math.abs(b.firstValue() - previous); n++; }
                previous = b.firstValue();
                saw = true;
            }
            if (b.lastTimestampNanos() != b.firstTimestampNanos() && b.lastUsable()) {
                if (previous != null) { movement += Math.abs(b.lastValue() - previous); n++; }
                previous = b.lastValue();
                saw = true;
            }
            if (!saw) previous = null;
        }
        return n == 0 ? 0 : Math.min(1d, movement / (n * span));
    }
    private static String fmt(double v) {
        return EngineeringValueFormatter.decimal(v);
    }

    private static void presentChoice(ComboBox<String> box, java.util.function.Function<String, String> label) {
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(String value) {
                return value == null ? "" : label.apply(value);
            }

            @Override
            public String fromString(String text) {
                return box.getValue();
            }
        });
    }
    private record TimedValue(long timestamp,double value){}
    public record Row(String signal,String unit,String min,String max,String range,String samples,String coverage,String activity,String cue) {}
    public record PairRow(String a,String b,String points,String correlation,String strength,String direction,String lag,String timing,String cue,double absCorrelation) {}
    private record LagResult(long lagNanos,double correlation,long searchLimitNanos) {}
    public record WindowRow(String signal,String unit,String before,String at,String after,String delta,String cue) {}
    public record BookmarkRow(String time,String signal,String type,String observation,long timestampNanos) {}
    public record EventRow(String time,String signal,String type,String value,String magnitude,String cue,long timestampNanos) {}
    public record FeatureRow(String signal,String unit,String mean,String median,String sd,String rms,String percentiles,String slope,String drift,String rate,String extrema,String steps,String outliers,String jitter,String oscillation) {}
    public record FindingRow(String time,String severity,String rule,String title,String confidence,String evidence,String explanation,long timestampNanos) {}
}
