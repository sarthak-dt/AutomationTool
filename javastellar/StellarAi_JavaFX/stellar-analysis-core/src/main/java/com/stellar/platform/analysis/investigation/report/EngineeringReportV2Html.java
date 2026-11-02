package com.stellar.platform.analysis.investigation.report;

import com.stellar.platform.analysis.investigation.EngineeringObservation;
import com.stellar.platform.analysis.investigation.EngineeringReportSection;
import com.stellar.platform.analysis.investigation.HtmlEscaper;
import com.stellar.platform.analysis.investigation.ReportFactKind;
import com.stellar.platform.analysis.investigation.kpi.EngineeringKpiCalculator;
import com.stellar.platform.analysis.investigation.kpi.EngineeringKpiResult;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Formats an Engineering Report V2 model. It does not calculate measurements. */
final class EngineeringReportV2Html {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    private EngineeringReportV2Html() {
    }

    static String render(EngineeringReportModel model) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"/>");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>");
        html.append("<title>").append(esc(title(model))).append("</title>");
        html.append("<style>").append(EngineeringHtmlReportRenderer.css()).append(extraCss()).append("</style>");
        html.append("</head><body><main class=\"sheet\">");
        List<EngineeringReportSection> visible = EngineeringReportV2Composition.visible(model);
        if (visible.contains(EngineeringReportSection.COVER)) {
            cover(html, model);
        }
        contents(html, visible, model);
        boolean eventsShown = false;
        for (EngineeringReportSection section : visible) {
            if (section == EngineeringReportSection.COVER) {
                continue;
            }
            if (!eventsShown && afterGraphs(section)) {
                events(html, model);
                evaluations(html, model);
                eventsShown = true;
            }
            body(html, model, section);
            if (!eventsShown && section == EngineeringReportSection.GRAPH_EVIDENCE) {
                events(html, model);
                evaluations(html, model);
                eventsShown = true;
            }
        }
        if (!eventsShown) {
            events(html, model);
            evaluations(html, model);
        }
        html.append("</main></body></html>");
        return html.toString();
    }

    private static void cover(StringBuilder html, EngineeringReportModel model) {
        var header = model.header();
        html.append("<header class=\"cover\" id=\"sec-cover\">");
        html.append("<p class=\"brand\">StellarAI</p>");
        html.append("<h1>").append(esc(title(model))).append("</h1>");
        html.append("<p class=\"report-title\">Engineering Report V2 · template ")
                .append(esc(model.template().version())).append("</p>");
        html.append("<table class=\"meta\"><thead><tr><th>Field</th><th>Value</th><th>Basis</th></tr></thead>");
        if (header != null && header.projectName() != null && !header.projectName().isBlank()) {
            row(html, "Project", header.projectName(), ReportFactKind.MEASURED);
        }
        if (header != null && header.author() != null && !header.author().isBlank()) {
            row(html, "Engineer", header.author(), ReportFactKind.ENGINEER_INTERPRETATION);
        }
        if (header != null && header.generatedAt() != null) {
            row(html, "Generated", TIME.format(header.generatedAt()), ReportFactKind.MEASURED);
        }
        if (header != null && header.applicationVersion() != null && !header.applicationVersion().isBlank()) {
            row(html, "Application", header.applicationVersion(), ReportFactKind.MEASURED);
        }
        if (model.project() != null) {
            row(html, "Measurements", Integer.toString(model.project().measurementCount()), ReportFactKind.MEASURED);
            row(html, "DBC definitions", Integer.toString(model.project().definitionCount()), ReportFactKind.MEASURED);
        }
        html.append("</table></header>");
    }

    private static void contents(StringBuilder html, List<EngineeringReportSection> visible, EngineeringReportModel model) {
        List<EngineeringReportSection> listed = new ArrayList<>();
        for (EngineeringReportSection section : visible) {
            if (section != EngineeringReportSection.COVER) {
                listed.add(section);
            }
        }
        if (listed.isEmpty() && !hasEvents(model) && !hasEvaluations(model)) {
            return;
        }
        html.append("<nav class=\"toc\"><h2>Contents</h2><ol>");
        boolean eventsListed = false;
        for (EngineeringReportSection section : listed) {
            html.append("<li><a href=\"#sec-").append(section.ordinal()).append("\">")
                    .append(esc(section.title())).append("</a></li>");
            if (!eventsListed && section == EngineeringReportSection.GRAPH_EVIDENCE && hasEvents(model)) {
                eventContentsItem(html);
                eventsListed = true;
            }
            if (section == EngineeringReportSection.GRAPH_EVIDENCE && hasEvaluations(model)) {
                evaluationContentsItem(html);
            }
        }
        if (!eventsListed && hasEvents(model)) {
            eventContentsItem(html);
        }
        if (hasEvaluations(model) && listed.stream().noneMatch(section -> section == EngineeringReportSection.GRAPH_EVIDENCE)) {
            evaluationContentsItem(html);
        }
        html.append("</ol></nav>");
    }

    private static void eventContentsItem(StringBuilder html) {
        html.append("<li><a href=\"#sec-events\">Engineering Events</a></li>");
    }

    private static void evaluationContentsItem(StringBuilder html) {
        html.append("<li><a href=\"#sec-evaluation\">Expected vs Actual</a></li>");
    }

    private static boolean hasEvaluations(EngineeringReportModel model) {
        return model.evaluations() != null && !model.evaluations().isEmpty();
    }

    private static boolean hasEvents(EngineeringReportModel model) {
        return model.events() != null && !model.events().isEmpty();
    }

    private static void body(StringBuilder html, EngineeringReportModel model, EngineeringReportSection section) {
        html.append("<section id=\"sec-").append(section.ordinal()).append("\"><h2>")
                .append(esc(section.title())).append("</h2>");
        switch (section) {
            case EXECUTIVE_SUMMARY -> prose(html, text(model.investigation() == null
                    ? "" : model.investigation().description()),
                    "No executive summary has been entered.", ReportFactKind.ENGINEER_INTERPRETATION);
            case MEASUREMENT_OVERVIEW -> overview(html, model);
            case SIGNAL_ANALYSIS -> signalAnalysis(html, EngineeringReportV2Composition.analysisImages(model));
            case GRAPH_EVIDENCE -> figures(html, EngineeringReportV2Composition.figures(model));
            case GPS_ROUTE -> route(html, model);
            case OBSERVATIONS -> observations(html, model);
            case CONCLUSIONS -> prose(html, model.conclusion(),
                    "No engineering conclusion has been entered.", ReportFactKind.ENGINEER_INTERPRETATION);
            case ASSUMPTIONS -> assumptions(html, model);
            case SOURCE_APPENDIX -> sources(html, model);
            case SIGNAL_APPENDIX -> signals(html, model);
            case ENGINEERING_KPIS -> kpis(html, model);
            case COVER -> { }
        }
        html.append("</section>");
    }

    private static void overview(StringBuilder html, EngineeringReportModel model) {
        kind(html, ReportFactKind.MEASURED);
        html.append("<p>").append(esc(model.timestampBasis())).append("</p>");
        if (model.investigation() != null) {
            html.append("<table class=\"meta\"><thead><tr><th>Field</th><th>Value</th><th>Basis</th></tr></thead>");
            row(html, "Investigation range", model.investigation().timeRange(), ReportFactKind.MEASURED);
            row(html, "Duration", model.investigation().duration(), ReportFactKind.CALCULATED);
            html.append("</table>");
        }
        contextLines(html, model);
        if (model.sources().isEmpty()) {
            unavailable(html, "No measurement files are attached.");
        } else {
            html.append("<table><thead><tr><th>File</th><th>Type</th><th>Result</th></tr></thead><tbody>");
            for (var source : model.sources()) {
                html.append("<tr><td>").append(esc(source.displayName())).append("</td><td>")
                        .append(esc(source.fileType())).append("</td><td>")
                        .append(esc(source.outcome())).append("</td></tr>");
            }
            html.append("</tbody></table>");
        }
        if (!model.findings().isEmpty() && model.shows(EngineeringReportSection.ASSUMPTIONS)) {
            html.append("<p>Evidence quality findings: ").append(model.findings().size())
                    .append(". The findings are listed in Assumptions and Limitations.</p>");
        }
        quality(html, model, EngineeringReportSection.MEASUREMENT_OVERVIEW);
    }

    private static void contextLines(StringBuilder html, EngineeringReportModel model) {
        for (String line : List.of(
                EngineeringReportV2Composition.processingLine(model),
                EngineeringReportV2Composition.dbcLine(model),
                EngineeringReportV2Composition.signalCountLine(model))) {
            if (!line.isBlank()) {
                html.append("<p>").append(esc(line)).append("</p>");
            }
        }
    }

    private static void signalAnalysis(StringBuilder html, List<SignalAnalysisSection> sections) {
        if (sections.isEmpty()) {
            unavailable(html, "No signal-analysis image is attached.");
            return;
        }
        for (SignalAnalysisSection section : sections) {
            html.append("<figure><figcaption>").append(esc(section.title()))
                    .append(" · ").append(esc(ReportFactKind.MEASURED.label())).append("</figcaption>");
            if (section.imageReference() == null || section.imageReference().isBlank()) {
                html.append("<p class=\"unavailable\">").append(esc(blank(section.unavailableNote(),
                        "Signal analysis unavailable."))).append("</p>");
            } else {
                html.append("<img class=\"report-waveform\" alt=\"\" src=\"")
                        .append(esc(section.imageReference())).append("\"/>");
            }
            html.append("</figure>");
        }
    }

    private static void figures(StringBuilder html, List<EngineeringReportModel.Figure> figures) {
        if (figures.isEmpty()) {
            unavailable(html, "No graph evidence is attached to this investigation.");
            return;
        }
        for (var figure : figures) {
            html.append("<figure><figcaption>Figure ").append(figure.number()).append(". ")
                    .append(esc(figure.caption()));
            if (figure.timeRange() != null && !figure.timeRange().isBlank()) {
                html.append(" · ").append(esc(figure.timeRange()));
            }
            html.append(" · ").append(esc(ReportFactKind.MEASURED.label())).append("</figcaption>");
            if (figure.assetRelativePath() == null || figure.assetRelativePath().isBlank()) {
                html.append("<p class=\"unavailable\">Graph image unavailable.</p>");
            } else {
                html.append("<img class=\"report-waveform\" alt=\"\" src=\"")
                        .append(esc(figure.assetRelativePath())).append("\"/>");
            }
            html.append("</figure>");
        }
    }

    private static boolean afterGraphs(EngineeringReportSection section) {
        return section == EngineeringReportSection.GPS_ROUTE
                || section == EngineeringReportSection.OBSERVATIONS
                || section == EngineeringReportSection.CONCLUSIONS
                || section == EngineeringReportSection.ASSUMPTIONS
                || section == EngineeringReportSection.SOURCE_APPENDIX
                || section == EngineeringReportSection.SIGNAL_APPENDIX;
    }

    private static void events(StringBuilder html, EngineeringReportModel model) {
        if (model.events().isEmpty()) {
            return;
        }
        html.append("<section id=\"sec-events\"><h2>Engineering Events</h2>");
        html.append("<p class=\"kind\">").append(esc(ReportFactKind.ENGINEER_INTERPRETATION.label())).append("</p>");
        html.append("<p>Included events are engineer-authored records. No event identity or pass/fail was inferred.</p>");
        html.append("<table class=\"events\"><thead><tr><th>Event</th><th>Type</th><th>Start</th><th>End</th><th>Signals</th><th>Evidence</th></tr></thead><tbody>");
        for (EngineeringReportModel.EventRow event : model.events()) {
            html.append("<tr><td>").append(esc(event.name())).append("</td><td>")
                    .append(esc(event.type())).append("</td><td>")
                    .append(esc(event.start())).append("</td><td>")
                    .append(esc(blank(event.end()))).append("</td><td>")
                    .append(esc(event.signals())).append("</td><td>")
                    .append(esc(EngineerEventReportRows.evidenceSummary(event)))
                    .append("</td></tr>");
        }
        html.append("</tbody></table>");
        for (EngineeringReportModel.EventRow event : model.events()) {
            eventDetail(html, model, event);
        }
        html.append("</section>");
    }

    private static void evaluations(StringBuilder html, EngineeringReportModel model) {
        if (model.evaluations() == null || model.evaluations().isEmpty()) {
            return;
        }
        html.append("<section id=\"sec-evaluation\"><h2>Expected vs Actual</h2>");
        kind(html, ReportFactKind.RULE_RESULT);
        html.append("<p>Engineer-entered expectations stay on the event. ")
                .append("Each row below is the result stored for one rule version. ")
                .append("A later rule version does not change that result. ")
                .append("Outcomes are not a vehicle pass or fail.</p>");
        html.append("<table><thead><tr><th>Event</th><th>Rule</th><th>Expected</th><th>Actual</th><th>Outcome</th></tr></thead><tbody>");
        for (EngineeringReportModel.EvaluationRow row : model.evaluations()) {
            html.append("<tr><td>").append(esc(row.eventName())).append("</td><td>")
                    .append(esc(row.ruleIdentity())).append("</td><td>")
                    .append(esc(row.expected())).append("</td><td>")
                    .append(esc(row.actual())).append("</td><td>")
                    .append(esc(row.outcome())).append("</td></tr>");
        }
        html.append("</tbody></table>");
        for (EngineeringReportModel.EvaluationRow row : model.evaluations()) {
            html.append("<h3>").append(esc(row.eventName())).append(" · ").append(esc(row.ruleIdentity())).append("</h3>");
            detailLine(html, "Procedure", row.procedure());
            detailLine(html, "Approval", row.approval());
            detailLine(html, "Time", row.timeRange());
            detailLine(html, "Unit", row.unit());
            detailLine(html, "Coverage", row.coverage());
            detailLine(html, "Calculation", row.method());
            detailLine(html, "Outcome", row.outcome());
            if (!row.reason().isBlank()) {
                detailLine(html, "Reason", row.reason());
            }
            if (!row.detail().isBlank()) {
                detailBlock(html, "Detail", row.detail(), "");
            }
            detailBlock(html, "Evidence", row.evidence(), "");
            if (row.exploratory()) {
                html.append("<p>Draft rule. This exploratory result is not a customer-approved compliance result.</p>");
            }
        }
        html.append("</section>");
    }

    private static void eventDetail(StringBuilder html, EngineeringReportModel model, EngineeringReportModel.EventRow event) {
        html.append("<h3>").append(esc(event.name())).append("</h3>");
        EngineeringReportModel.EventNarrative narrative = event.narrative();
        detailLine(html, "Confirmation", narrative.confirmation().isBlank() ? "Engineer record" : narrative.confirmation());
        detailLine(html, "Time", event.end() == null || event.end().isBlank()
                ? event.start() : event.start() + " to " + event.end());
        detailLine(html, "Source measurement", event.sourceFile());
        detailLine(html, "Signals", event.signals());
        detailBlock(html, "Expected behaviour", narrative.expected(), "Not entered.");
        detailBlock(html, "Measured evidence", narrative.measured(), "Not entered.");
        detailBlock(html, "Graph snapshot", event.snapshot(), "No graph snapshot is linked.");
        if (hasSnapshotImage(event.snapshot()) && snapshotAlreadyFigured(model, event.snapshot())) {
            html.append("<p>This snapshot is already shown in Graph Evidence and is not repeated here.</p>");
        } else if (hasSnapshotImage(event.snapshot())) {
            html.append("<img class=\"report-waveform\" alt=\"\" src=\"").append(esc(event.snapshot())).append("\"/>");
        }
        detailBlock(html, "GNSS", event.location(), "No GNSS sample was correlated.");
        detailBlock(html, "Engineer interpretation", event.description(), "Not entered.");
        detailBlock(html, "Assumptions and limitations", narrative.limitations(), "");
        if (event.provenance() != null && !event.provenance().isBlank()) {
            html.append("<p class=\"kind\">").append(esc(event.provenance())).append("</p>");
        }
    }

    private static void detailLine(StringBuilder html, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        html.append("<p><strong>").append(esc(label)).append(".</strong> ").append(esc(value)).append("</p>");
    }

    private static void detailBlock(StringBuilder html, String label, String value, String empty) {
        String shown = value == null || value.isBlank() ? empty : value;
        if (shown == null || shown.isBlank()) {
            return;
        }
        html.append("<h4>").append(esc(label)).append("</h4><p>").append(esc(shown)).append("</p>");
    }

    private static boolean snapshotAlreadyFigured(EngineeringReportModel model, String snapshot) {
        if (model == null || model.figures() == null || snapshot == null) {
            return false;
        }
        for (EngineeringReportModel.Figure figure : model.figures()) {
            String path = figure.assetRelativePath();
            if (path == null || path.isBlank()) {
                continue;
            }
            if (path.equals(snapshot) || path.endsWith("/" + snapshot)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSnapshotImage(String snapshot) {
        return snapshot != null && snapshot.endsWith(".png") && !snapshot.contains(" ") && !snapshot.contains("\n");
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static void route(StringBuilder html, EngineeringReportModel model) {
        var map = model.routeMap();
        kind(html, ReportFactKind.MEASURED);
        boolean image = map != null && map.imageReference() != null && !map.imageReference().isBlank();
        if (image) {
            html.append("<p>The route image is the verified GPS evidence already attached. ")
                    .append("An event marker is drawn only when a GNSS sample falls inside that event ")
                    .append("on the same recording clock. The marker is the first valid GNSS sample ")
                    .append("inside the window, not the whole event. Coordinates are not interpolated.</p>");
        }
        if (!image) {
            unavailable(html, map == null || map.unavailableNote() == null || map.unavailableNote().isBlank()
                    ? "No GPS route is attached. No route, geofence, or event location was inferred."
                    : map.unavailableNote());
            html.append("<p>Linked event samples stay with the event. No map or coordinate was created.</p>");
            return;
        }
        html.append("<figure><img class=\"report-map\" alt=\"\" src=\"")
                .append(esc(map.imageReference())).append("\"/>");
        html.append("<figcaption>").append(esc(map.title()));
        appendCaption(html, "Start", map.startLabel());
        appendCaption(html, "End", map.endLabel());
        appendCaption(html, "Duration", map.durationLabel());
        appendCaption(html, "Distance", map.distanceLabel());
        html.append("</figcaption></figure>");
    }

    private static void observations(StringBuilder html, EngineeringReportModel model) {
        if (model.observations().isEmpty()) {
            unavailable(html, "No engineer observations are attached.");
            return;
        }
        html.append("<table><thead><tr><th>Title</th><th>Note</th><th>Kind</th></tr></thead><tbody>");
        for (EngineeringObservation observation : model.observations()) {
            html.append("<tr><td>").append(esc(observation.title() == null || observation.title().isBlank()
                    ? "Engineer Observation" : observation.title())).append("</td><td>")
                    .append(esc(observation.note())).append("</td><td>")
                    .append(esc(ReportFactKind.ENGINEER_INTERPRETATION.label())).append("</td></tr>");
        }
        html.append("</tbody></table>");
    }

    private static void assumptions(StringBuilder html, EngineeringReportModel model) {
        kind(html, ReportFactKind.ASSUMPTION);
        html.append("<ul>");
        html.append("<li>").append(esc(model.timestampBasis())).append("</li>");
        html.append("<li>Event detection, cycle identification, geofences, and pass/fail judgements are not part of this report.</li>");
        for (String line : model.limitations()) {
            html.append("<li>").append(esc(line)).append("</li>");
        }
        if (model.shows(EngineeringReportSection.ENGINEERING_KPIS)) {
            for (String line : EngineeringKpiPresentation.limitations(model.kpis())) {
                html.append("<li>").append(esc(line)).append("</li>");
            }
        }
        html.append("</ul>");
        quality(html, model, EngineeringReportSection.ASSUMPTIONS);
        if (!model.warnings().isEmpty()) {
            kind(html, ReportFactKind.MEASURED);
            html.append("<p>Processing diagnostics</p><ul>");
            for (String warning : model.warnings()) {
                html.append("<li>").append(esc(warning)).append("</li>");
            }
            html.append("</ul>");
        }
    }

    private static void kpis(StringBuilder html, EngineeringReportModel model) {
        kind(html, ReportFactKind.ASSUMPTION);
        html.append("<p>").append(esc(EngineeringKpiCalculator.METHOD))
                .append(" uses each valid sample until the next sample when the gap is within the configured maximum. ")
                .append("There is no interpolation and no pass/fail rule. Files are not combined onto one timeline.</p>");
        List<EngineeringKpiPresentation.Row> rows = EngineeringKpiPresentation.calculated(model.kpis());
        if (rows.isEmpty()) {
            unavailable(html, "No engineering KPIs were calculated from the mapped signals.");
        } else {
            String domain = "";
            for (EngineeringKpiPresentation.Row row : rows) {
                if (!row.domain().equals(domain)) {
                    if (!domain.isEmpty()) {
                        html.append("</tbody></table>");
                    }
                    domain = row.domain();
                    html.append("<h3>").append(esc(domain)).append("</h3>");
                    html.append("<table class=\"kpi\"><thead><tr><th>Metric</th><th>Value</th><th>Unit</th></tr></thead><tbody>");
                }
                html.append("<tr><td>").append(esc(row.name())).append("</td><td class=\"value\">")
                        .append(esc(row.value())).append("</td><td>").append(esc(row.unit())).append("</td></tr>");
                if (!row.detail().isBlank()) {
                    html.append("<tr><td class=\"detail\" colspan=\"3\">").append(esc(row.detail())).append("</td></tr>");
                }
            }
            if (!domain.isEmpty()) {
                html.append("</tbody></table>");
            }
        }
        if (!model.shows(EngineeringReportSection.ASSUMPTIONS)) {
            for (String line : EngineeringKpiPresentation.limitations(model.kpis())) {
                unavailable(html, line);
            }
        }
    }

    private static void sources(StringBuilder html, EngineeringReportModel model) {
        if (model.sources().isEmpty()) {
            unavailable(html, "No source files are recorded.");
            return;
        }
        kind(html, ReportFactKind.MEASURED);
        html.append("<table><thead><tr><th>File</th><th>Type</th><th>Size</th><th>Result</th></tr></thead><tbody>");
        for (var source : model.sources()) {
            html.append("<tr><td>").append(esc(source.displayName())).append("</td><td>")
                    .append(esc(source.fileType())).append("</td><td>")
                    .append(esc(source.sizeLabel())).append("</td><td>")
                    .append(esc(source.outcome())).append("</td></tr>");
        }
        html.append("</tbody></table>");
    }

    private static void signals(StringBuilder html, EngineeringReportModel model) {
        if (!model.definitions().isEmpty()) {
            kind(html, ReportFactKind.MEASURED);
            html.append("<table><thead><tr><th>DBC</th><th>Type</th><th>Status</th></tr></thead><tbody>");
            for (var definition : model.definitions()) {
                html.append("<tr><td>").append(esc(definition.filename())).append("</td><td>")
                        .append(esc(definition.type())).append("</td><td>")
                        .append(esc(definition.status())).append("</td></tr>");
            }
            html.append("</tbody></table>");
        }
        if (model.signals().isEmpty()) {
            unavailable(html, "No signals are recorded for this report.");
            quality(html, model, EngineeringReportSection.SIGNAL_APPENDIX);
            return;
        }
        html.append("<table><thead><tr><th>Signal</th><th>Unit</th><th>Source</th><th>Min</th><th>Max</th></tr></thead><tbody>");
        for (var signal : model.signals()) {
            html.append("<tr><td>").append(esc(signal.name())).append("</td><td>")
                    .append(esc(signal.unit())).append("</td><td>")
                    .append(esc(signal.source())).append("</td><td>")
                    .append(esc(signal.min())).append("</td><td>")
                    .append(esc(signal.max())).append("</td></tr>");
        }
        html.append("</tbody></table>");
        quality(html, model, EngineeringReportSection.SIGNAL_APPENDIX);
    }

    private static void quality(StringBuilder html, EngineeringReportModel model, EngineeringReportSection here) {
        if (!qualityHere(model, here)) {
            return;
        }
        kind(html, ReportFactKind.MEASURED);
        html.append("<p>Evidence quality. Each row is a stored observation, a calculation, or an unavailable result. ")
                .append("None of these rows is a vehicle defect or a pass/fail judgement.</p>");
        html.append("<table class=\"events\"><thead><tr><th>Finding</th><th>Status</th><th>Signal</th><th>Evidence</th><th>Limitation</th></tr></thead><tbody>");
        for (QualityFinding finding : model.findings()) {
            html.append("<tr><td>").append(esc(finding.id())).append("<br>").append(esc(finding.type())).append("</td><td>")
                    .append(esc(finding.status().label())).append("</td><td>")
                    .append(esc(finding.signal().isBlank() ? finding.sourceFile() : finding.signal())).append("</td><td>")
                    .append(esc(finding.explanation())).append("<br>").append(esc(finding.measured()))
                    .append(finding.time().isBlank() ? "" : "<br>" + esc(finding.time())).append("</td><td>")
                    .append(esc(finding.limitations())).append("</td></tr>");
        }
        html.append("</tbody></table>");
    }

    private static boolean qualityHere(EngineeringReportModel model, EngineeringReportSection here) {
        if (model.findings().isEmpty() || !model.shows(here)) {
            return false;
        }
        if (model.shows(EngineeringReportSection.ASSUMPTIONS)) {
            return here == EngineeringReportSection.ASSUMPTIONS;
        }
        if (model.shows(EngineeringReportSection.MEASUREMENT_OVERVIEW)) {
            return here == EngineeringReportSection.MEASUREMENT_OVERVIEW;
        }
        return here == EngineeringReportSection.SIGNAL_APPENDIX;
    }

    private static void prose(StringBuilder html, String value, String empty, ReportFactKind kind) {
        if (value == null || value.isBlank()) {
            unavailable(html, empty);
            return;
        }
        kind(html, kind);
        html.append("<p>").append(esc(value)).append("</p>");
    }

    private static void unavailable(StringBuilder html, String message) {
        kind(html, ReportFactKind.UNAVAILABLE);
        html.append("<p class=\"unavailable\">").append(esc(message)).append("</p>");
    }

    private static void kind(StringBuilder html, ReportFactKind kind) {
        html.append("<p class=\"kind\">").append(esc(kind.label())).append("</p>");
    }

    private static void row(StringBuilder html, String label, String value, ReportFactKind kind) {
        html.append("<tr><th>").append(esc(label)).append("</th><td>")
                .append(esc(value)).append("</td><td class=\"kind\">")
                .append(esc(kind.label())).append("</td></tr>");
    }

    private static void appendCaption(StringBuilder html, String label, String value) {
        if (value != null && !value.isBlank()) {
            html.append(" · ").append(esc(label)).append(" ").append(esc(value));
        }
    }

    private static String title(EngineeringReportModel model) {
        if (model.header() != null && model.header().title() != null && !model.header().title().isBlank()) {
            return model.header().title();
        }
        return "Engineering Report V2";
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String esc(String value) {
        return HtmlEscaper.escape(value);
    }

    private static String extraCss() {
        return """
                .cover{margin-bottom:18px;}
                nav.toc{margin-bottom:12px;}
                nav.toc ol{margin:8px 0 12px;padding-left:20px;}
                nav.toc a{color:#1F4E79;text-decoration:none;}
                p.kind,td.kind{color:#5C6E82;font-size:11px;letter-spacing:0.02em;margin:0 0 6px;}
                table.events{table-layout:fixed;}
                table.events th:nth-child(1),table.events td:nth-child(1){width:16%;}
                table.events th:nth-child(2),table.events td:nth-child(2){width:14%;}
                table.events th:nth-child(3),table.events td:nth-child(3),
                table.events th:nth-child(4),table.events td:nth-child(4){width:12%;}
                table.events th:nth-child(5),table.events td:nth-child(5){width:28%;}
                table.events td,table.events th{overflow-wrap:anywhere;}
                p.unavailable{border-left:3px solid #8A5A00;padding:4px 8px;color:#5C4A20;}
                h3{font-size:14px;color:#0E2A47;margin:14px 0 6px;}
                h4{font-size:13px;color:#1F4E79;margin:10px 0 4px;}
                table.kpi td.value{font-size:16px;font-weight:700;color:#0E2A47;}
                table.kpi td.detail{color:#5C6E82;font-size:12px;}
                h2{break-after:avoid;page-break-after:avoid;}
                figure{break-inside:avoid;page-break-inside:avoid;}
                main.sheet>section:has(~section)::after{
                content:"";display:block;height:1px;margin:16px 0 4px;background:#D7E2EE;
                break-before:avoid;page-break-before:avoid;break-after:avoid;page-break-after:avoid;}
                main.sheet>header.cover:has(~section)+nav,main.sheet>header.cover:has(~section)+section{
                border-top:1px solid #D7E2EE;margin-top:16px;padding-top:12px;
                break-before:avoid;page-break-before:avoid;}
                @media print{
                section{break-inside:auto;}
                thead{display:table-header-group;}
                h2{break-after:avoid;page-break-after:avoid;}
                figure{break-inside:avoid;page-break-inside:avoid;}
                main.sheet>section:has(~section)::after{break-before:avoid;page-break-before:avoid;}
                }
                """;
    }
}
