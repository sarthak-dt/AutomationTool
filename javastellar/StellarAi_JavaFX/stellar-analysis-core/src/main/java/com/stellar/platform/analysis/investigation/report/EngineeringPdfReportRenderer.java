package com.stellar.platform.analysis.investigation.report;

import com.stellar.platform.analysis.investigation.EngineeringEvidenceItem;
import com.stellar.platform.analysis.investigation.EngineeringObservation;
import com.stellar.platform.analysis.investigation.EngineeringReportSection;
import com.stellar.platform.analysis.investigation.EngineeringReportTemplate;
import com.stellar.platform.analysis.investigation.InvestigationHealth;
import com.stellar.platform.analysis.investigation.ReportFactKind;
import com.stellar.platform.analysis.investigation.kpi.EngineeringKpiCalculator;
import com.stellar.platform.analysis.investigation.kpi.EngineeringKpiResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * A4 PDF of an already-built {@link EngineeringReportModel}. It does not query stores or JavaFX.
 * Apache PDFBox 3 (Apache License 2.0) draws the pages. A Windows TrueType font is embedded when
 * present so units and non-ASCII text stay readable.
 */
public final class EngineeringPdfReportRenderer {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final float MARGIN = 48f;
    private static final float BODY = 10.5f;
    private static final float HEAD = 14f;

    private EngineeringPdfReportRenderer() {
    }

    public static void write(EngineeringReportModel model, Path destination, Path assetRoot) throws IOException {
        if (model == null || destination == null) {
            throw new IOException("Choose a destination for the report.");
        }
        Path root = assetRoot == null
                ? (destination.getParent() == null ? Path.of(".") : destination.getParent())
                : assetRoot;
        Path writing = destination.resolveSibling(destination.getFileName().toString() + ".writing");
        try (PDDocument document = new PDDocument()) {
            PDFont regular = loadFont(document, false);
            PDFont bold = loadFont(document, true);
            Pages pages = new Pages(document, regular, bold);
            if (model.template() == EngineeringReportTemplate.ENGINEERING_V2) {
                renderV2(pages, model, root);
            } else {
            pages.brand("StellarAI");
            pages.heading("Engineering Analysis Report");
            header(pages, model);
            if (model.health() != InvestigationHealth.CURRENT) {
                pages.heading("Investigation health: " + model.health().name());
                pages.paragraph("Some attached evidence is historical. Re-run or keep the reference.");
            }
            project(pages, model);
            sources(pages, model);
            definitions(pages, model);
            processing(pages, model);
            warnings(pages, model);
            investigation(pages, model);
            signals(pages, model);
            signalAnalysis(pages, model, root, true);
            routeMap(pages, model, root, true);
            figures(pages, model, root, true);
            results(pages, "Analysis results", model.analysis());
            results(pages, "Diagnostic findings", model.diagnostics());
            results(pages, "Frequency-domain analysis", model.frequency());
            observations(pages, model);
            conclusion(pages, model);
            limitations(pages, model);
            appendix(pages, model);
            evidence(pages, model);
            }
            pages.close();
            stamp(document, regular, model);
            document.save(writing.toFile());
        } catch (IOException ex) {
            Files.deleteIfExists(writing);
            throw ex;
        }
        try {
            Files.move(writing, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomic) {
            try {
                Files.move(writing, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallback) {
                Files.deleteIfExists(writing);
                fallback.addSuppressed(atomic);
                throw fallback;
            }
        }
    }

    private static void renderV2(Pages pages, EngineeringReportModel model, Path root) throws IOException {
        String title = model.header() == null || model.header().title() == null || model.header().title().isBlank()
                ? "Engineering Report V2" : model.header().title();
        List<EngineeringReportSection> visible = EngineeringReportV2Composition.visible(model);
        boolean eventsShown = false;
        boolean opened = false;
        if (visible.contains(EngineeringReportSection.COVER)) {
            pages.brand("StellarAI");
            pages.heading(title);
            pages.paragraph("Engineering Report V2  ·  template " + model.template().version());
            if (model.header() != null) {
                if (model.header().projectName() != null && !model.header().projectName().isBlank()) {
                    pages.pair("Project", model.header().projectName());
                }
                if (model.header().author() != null && !model.header().author().isBlank()) {
                    pages.pair("Engineer", model.header().author());
                }
                if (model.header().generatedAt() != null) {
                    pages.pair("Generated", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                            .withZone(ZoneId.systemDefault()).format(model.header().generatedAt()));
                }
            }
            opened = true;
        }
        for (EngineeringReportSection section : visible) {
            if (section == EngineeringReportSection.COVER) {
                continue;
            }
            if (!eventsShown && afterGraphs(section)) {
                opened = insertEvidenceSections(pages, model, root, opened);
                eventsShown = true;
            }
            opened = openSection(pages, opened);
            switch (section) {
                case EXECUTIVE_SUMMARY -> {
                    pages.sectionHeading(section.title(), 48f);
                    authored(pages, model.investigation() == null
                            ? "" : model.investigation().description(), "No executive summary has been entered.");
                }
                case MEASUREMENT_OVERVIEW -> {
                    pages.sectionHeading(section.title(), 72f);
                    overview(pages, model);
                }
                case SIGNAL_ANALYSIS -> signalAnalysis(
                        pages, EngineeringReportV2Composition.analysisImages(model), root, section.title());
                case GRAPH_EVIDENCE -> figures(
                        pages, EngineeringReportV2Composition.figures(model), root, section.title());
                case GPS_ROUTE -> {
                    EngineeringReportModel.RouteMapSection map = model.routeMap();
                    boolean image = map != null && map.imageReference() != null && !map.imageReference().isBlank();
                    if (!image) {
                        pages.sectionHeading(section.title(), 48f);
                        String note = map == null || map.unavailableNote() == null || map.unavailableNote().isBlank()
                                ? "No GPS route is attached. No route, geofence, or event location was inferred."
                                : map.unavailableNote();
                        pages.paragraph(ReportFactKind.UNAVAILABLE.label() + " — " + note);
                        pages.paragraph("Linked event samples stay with the event. No map or coordinate was created.");
                    } else {
                        routeMap(pages, model, root, section.title());
                    }
                }
                case OBSERVATIONS -> {
                    pages.sectionHeading(section.title(), 48f);
                    observationSection(pages, model);
                }
                case CONCLUSIONS -> {
                    pages.sectionHeading(section.title(), 48f);
                    authored(pages, model.conclusion(), "No engineering conclusion has been entered.");
                }
                case ASSUMPTIONS -> {
                    pages.sectionHeading(section.title(), 48f);
                    assumptions(pages, model);
                }
                case SOURCE_APPENDIX -> {
                    pages.sectionHeading(section.title(), 48f);
                    sourceTable(pages, model);
                }
                case SIGNAL_APPENDIX -> {
                    pages.sectionHeading(section.title(), 64f);
                    signalTable(pages, model);
                }
                case ENGINEERING_KPIS -> {
                    pages.sectionHeading(section.title(), 64f);
                    kpiTable(pages, model);
                }
                case COVER -> { }
            }
            if (!eventsShown && section == EngineeringReportSection.GRAPH_EVIDENCE) {
                opened = insertEvidenceSections(pages, model, root, opened);
                eventsShown = true;
            }
        }
        if (!eventsShown) {
            insertEvidenceSections(pages, model, root, opened);
        }
    }

    private static boolean openSection(Pages pages, boolean opened) {
        if (opened) {
            pages.armSectionDivider();
        }
        return true;
    }

    private static boolean insertEvidenceSections(
            Pages pages, EngineeringReportModel model, Path root, boolean opened
    ) throws IOException {
        if (!model.events().isEmpty()) {
            opened = openSection(pages, opened);
            eventTable(pages, model, root);
        }
        if (model.evaluations() != null && !model.evaluations().isEmpty()) {
            opened = openSection(pages, opened);
            evaluationTable(pages, model);
        }
        return opened;
    }

    private static boolean afterGraphs(EngineeringReportSection section) {
        return section == EngineeringReportSection.GPS_ROUTE
                || section == EngineeringReportSection.OBSERVATIONS
                || section == EngineeringReportSection.CONCLUSIONS
                || section == EngineeringReportSection.ASSUMPTIONS
                || section == EngineeringReportSection.SOURCE_APPENDIX
                || section == EngineeringReportSection.SIGNAL_APPENDIX;
    }

    private static void eventTable(Pages pages, EngineeringReportModel model, Path root) throws IOException {
        if (model.events().isEmpty()) {
            return;
        }
        pages.sectionHeading("Engineering Events", 120f);
        pages.paragraph(ReportFactKind.ENGINEER_INTERPRETATION.label());
        pages.paragraph("Included events are engineer-authored records. No event identity or pass/fail was inferred.");
        List<String[]> rows = new ArrayList<>();
        for (EngineeringReportModel.EventRow event : model.events()) {
            rows.add(new String[] {
                    event.name(), event.type(), event.start(),
                    event.end() == null || event.end().isBlank() ? "—" : event.end(),
                    event.signals(), EngineerEventReportRows.evidenceSummary(event)
            });
        }
        pages.grid(new String[] {"Event", "Type", "Start", "End", "Signals", "Evidence"}, rows,
                new float[] {1.2f, 1.0f, 0.8f, 0.8f, 2.0f, 1.0f});
        for (EngineeringReportModel.EventRow event : model.events()) {
            eventDetail(pages, model, event, root);
        }
    }

    private static void evaluationTable(Pages pages, EngineeringReportModel model) throws IOException {
        if (model.evaluations() == null || model.evaluations().isEmpty()) {
            return;
        }
        pages.sectionHeading("Expected vs Actual", 140f);
        pages.paragraph(ReportFactKind.RULE_RESULT.label());
        pages.paragraph("Engineer-entered expectations stay on the event. "
                + "Each result is stored for one rule version. A later rule version does not change it. "
                + "Outcomes are not a vehicle pass or fail.");
        List<String[]> rows = new ArrayList<>();
        for (EngineeringReportModel.EvaluationRow row : model.evaluations()) {
            rows.add(new String[] {row.eventName(), row.ruleIdentity(), row.expected(), row.actual(), row.outcome()});
        }
        pages.grid(new String[] {"Event", "Rule", "Expected", "Actual", "Outcome"}, rows,
                new float[] {1.1f, 1.2f, 1.2f, 1.3f, 1.0f});
        for (EngineeringReportModel.EvaluationRow row : model.evaluations()) {
            pages.sectionHeading(row.eventName() + " · " + row.ruleIdentity(), 160f);
            pages.pair("Procedure", row.procedure());
            pages.pair("Approval", row.approval());
            pages.pair("Time", row.timeRange());
            pages.pair("Unit", row.unit());
            pages.pair("Coverage", row.coverage());
            pages.pair("Calculation", row.method());
            pages.pair("Outcome", row.outcome());
            if (row.reason() != null && !row.reason().isBlank()) {
                pages.pair("Reason", row.reason());
            }
            if (row.detail() != null && !row.detail().isBlank()) {
                pages.paragraph(row.detail());
            }
            pages.paragraph(row.evidence());
            if (row.exploratory()) {
                pages.paragraph("Draft rule. This exploratory result is not a customer-approved compliance result.");
            }
        }
    }

    private static void eventDetail(
            Pages pages, EngineeringReportModel model, EngineeringReportModel.EventRow event, Path root
    ) throws IOException {
        pages.sectionHeading(event.name(), 96f);
        EngineeringReportModel.EventNarrative narrative = event.narrative();
        pages.pair("Confirmation", narrative.confirmation().isBlank() ? "Engineer record" : narrative.confirmation());
        pages.pair("Time", event.end() == null || event.end().isBlank()
                ? event.start() : event.start() + " to " + event.end());
        if (event.sourceFile() != null && !event.sourceFile().isBlank()) {
            pages.pair("Source measurement", event.sourceFile());
        }
        if (event.signals() != null && !event.signals().isBlank()) {
            pages.pair("Signals", event.signals());
        }
        labeledBlock(pages, "Expected behaviour",
                narrative.expected().isBlank() ? "Not entered." : narrative.expected());
        labeledBlock(pages, "Measured evidence",
                narrative.measured().isBlank() ? "Not entered." : narrative.measured());
        labeledBlock(pages, "Graph snapshot",
                event.snapshot() == null || event.snapshot().isBlank()
                        ? "No graph snapshot is linked." : event.snapshot());
        Path image = snapshotFile(root, event.snapshot());
        if (image != null && snapshotAlreadyFigured(model, event.snapshot())) {
            pages.paragraph("This snapshot is already shown in Graph Evidence and is not repeated here.");
        } else if (image != null) {
            pages.placeFigure(null, event.name() + " snapshot", image, "Graph snapshot file is missing.");
        }
        labeledBlock(pages, "GNSS", event.location() == null || event.location().isBlank()
                ? "No GNSS sample was correlated." : event.location());
        labeledBlock(pages, "Engineer interpretation",
                event.description() == null || event.description().isBlank()
                        ? "Not entered." : event.description());
        if (!narrative.limitations().isBlank()) {
            pages.paragraph("Assumptions and limitations");
            pages.paragraph(narrative.limitations());
        }
        if (event.provenance() != null && !event.provenance().isBlank()) {
            pages.paragraph(event.provenance());
        }
    }

    private static void labeledBlock(Pages pages, String label, String value) throws IOException {
        pages.keep(36f);
        pages.paragraph(label);
        pages.paragraph(value);
    }

    private static boolean snapshotAlreadyFigured(EngineeringReportModel model, String snapshot) {
        if (model == null || snapshot == null) {
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

    private static Path snapshotFile(Path root, String snapshot) {
        if (root == null || snapshot == null || !snapshot.endsWith(".png")
                || snapshot.contains(" ") || snapshot.contains("\n")) {
            return null;
        }
        Path file = root.resolve(snapshot);
        return Files.isRegularFile(file) ? file : null;
    }

    private static void authored(Pages pages, String value, String empty) throws IOException {
        if (value == null || value.isBlank()) {
            pages.paragraph(ReportFactKind.UNAVAILABLE.label() + " — " + empty);
            return;
        }
        pages.paragraph(ReportFactKind.ENGINEER_INTERPRETATION.label());
        pages.paragraph(value);
    }

    private static void overview(Pages pages, EngineeringReportModel model) throws IOException {
        pages.paragraph(ReportFactKind.MEASURED.label());
        pages.paragraph(model.timestampBasis());
        if (model.investigation() != null) {
            pages.pair("Investigation range", model.investigation().timeRange());
            pages.pair("Duration", model.investigation().duration());
        }
        for (String line : List.of(
                EngineeringReportV2Composition.processingLine(model),
                EngineeringReportV2Composition.dbcLine(model),
                EngineeringReportV2Composition.signalCountLine(model))) {
            if (!line.isBlank()) {
                pages.paragraph(line);
            }
        }
        if (model.sources().isEmpty()) {
            pages.paragraph(ReportFactKind.UNAVAILABLE.label() + " — No measurement files are attached.");
        } else {
            List<String[]> rows = new ArrayList<>();
            for (var source : model.sources()) {
                rows.add(new String[] {source.displayName(), source.fileType(), source.outcome()});
            }
            pages.grid(new String[] {"File", "Type", "Result"}, rows, new float[] {2.6f, 0.6f, 0.8f});
        }
        if (!model.findings().isEmpty() && model.shows(EngineeringReportSection.ASSUMPTIONS)) {
            pages.paragraph("Evidence quality findings: " + model.findings().size()
                    + ". The findings are listed in Assumptions and Limitations.");
        }
        quality(pages, model, EngineeringReportSection.MEASUREMENT_OVERVIEW);
    }

    private static void observationSection(Pages pages, EngineeringReportModel model) throws IOException {
        if (model.observations().isEmpty()) {
            pages.paragraph(ReportFactKind.UNAVAILABLE.label() + " — No engineer observations are attached.");
            return;
        }
        List<String[]> rows = new ArrayList<>();
        for (EngineeringObservation observation : model.observations()) {
            rows.add(new String[] {observation.title(), observation.note(),
                    ReportFactKind.ENGINEER_INTERPRETATION.label()});
        }
        pages.grid(new String[] {"Title", "Note", "Kind"}, rows);
    }

    private static void assumptions(Pages pages, EngineeringReportModel model) throws IOException {
        pages.paragraph(ReportFactKind.ASSUMPTION.label());
        pages.paragraph(model.timestampBasis());
        pages.paragraph("Event detection, cycle identification, geofences, and pass/fail judgements are not part of this report.");
        for (String line : model.limitations()) {
            pages.paragraph(line);
        }
        if (model.shows(EngineeringReportSection.ENGINEERING_KPIS)) {
            for (String line : EngineeringKpiPresentation.limitations(model.kpis())) {
                pages.paragraph(line);
            }
        }
        if (!model.warnings().isEmpty()) {
            pages.paragraph(ReportFactKind.MEASURED.label());
            pages.paragraph("Processing diagnostics");
            for (String warning : model.warnings()) {
                pages.paragraph(warning);
            }
        }
        quality(pages, model, EngineeringReportSection.ASSUMPTIONS);
    }

    private static void kpiTable(Pages pages, EngineeringReportModel model) throws IOException {
        pages.paragraph(ReportFactKind.ASSUMPTION.label());
        pages.paragraph(EngineeringKpiCalculator.METHOD
                + " uses each valid sample until the next sample when the gap is within the configured maximum. "
                + "There is no interpolation and no pass/fail rule. Files are not combined onto one timeline.");
        List<EngineeringKpiPresentation.Row> rows = EngineeringKpiPresentation.calculated(model.kpis());
        if (rows.isEmpty()) {
            pages.paragraph(ReportFactKind.UNAVAILABLE.label()
                    + " — No engineering KPIs were calculated from the mapped signals.");
        } else {
            String domain = "";
            List<String[]> table = new ArrayList<>();
            for (EngineeringKpiPresentation.Row row : rows) {
                if (!row.domain().equals(domain)) {
                    if (!table.isEmpty()) {
                        pages.grid(new String[] {"Metric", "Value", "Unit"}, table,
                                new float[] {1.6f, 1.1f, 0.6f});
                        table = new ArrayList<>();
                    }
                    domain = row.domain();
                    pages.heading(domain);
                }
                table.add(new String[] {row.name(), row.value(), row.unit()});
                if (!row.detail().isBlank()) {
                    table.add(new String[] {row.detail(), "", ""});
                }
            }
            if (!table.isEmpty()) {
                pages.grid(new String[] {"Metric", "Value", "Unit"}, table,
                        new float[] {1.6f, 1.1f, 0.6f});
            }
        }
        if (!model.shows(EngineeringReportSection.ASSUMPTIONS)) {
            for (String line : EngineeringKpiPresentation.limitations(model.kpis())) {
                pages.paragraph(ReportFactKind.UNAVAILABLE.label() + " — " + line);
            }
        }
    }

    private static void sourceTable(Pages pages, EngineeringReportModel model) throws IOException {
        if (model.sources().isEmpty()) {
            pages.paragraph(ReportFactKind.UNAVAILABLE.label() + " — No source files are recorded.");
            return;
        }
        List<String[]> rows = new ArrayList<>();
        for (var source : model.sources()) {
            rows.add(new String[] {source.displayName(), source.fileType(), source.sizeLabel(), source.outcome()});
        }
        pages.grid(new String[] {"File", "Type", "Size", "Result"}, rows, new float[] {2.4f, 0.6f, 0.6f, 0.8f});
    }

    private static void signalTable(Pages pages, EngineeringReportModel model) throws IOException {
        if (!model.definitions().isEmpty()) {
            List<String[]> defs = new ArrayList<>();
            for (var definition : model.definitions()) {
                defs.add(new String[] {definition.filename(), definition.type(), definition.status()});
            }
            pages.grid(new String[] {"DBC", "Type", "Status"}, defs);
        }
        if (model.signals().isEmpty()) {
            pages.paragraph(ReportFactKind.UNAVAILABLE.label() + " — No signals are recorded for this report.");
        } else {
            List<String[]> rows = new ArrayList<>();
            for (var signal : model.signals()) {
                String source = signal.source() == null ? "" : signal.source().replace(" · ", "\n");
                rows.add(new String[] {signal.name(), signal.unit(), source, signal.min(), signal.max()});
            }
            pages.grid(new String[] {"Signal", "Unit", "Source", "Min", "Max"}, rows,
                    new float[] {1.8f, 0.4f, 0.75f, 0.75f, 0.75f});
        }
        quality(pages, model, EngineeringReportSection.SIGNAL_APPENDIX);
    }

    private static void quality(Pages pages, EngineeringReportModel model, EngineeringReportSection here)
            throws IOException {
        if (model.findings().isEmpty() || !model.shows(here)) {
            return;
        }
        boolean assumptions = model.shows(EngineeringReportSection.ASSUMPTIONS);
        boolean overview = model.shows(EngineeringReportSection.MEASUREMENT_OVERVIEW);
        boolean place = assumptions
                ? here == EngineeringReportSection.ASSUMPTIONS
                : overview
                ? here == EngineeringReportSection.MEASUREMENT_OVERVIEW
                : here == EngineeringReportSection.SIGNAL_APPENDIX;
        if (!place) {
            return;
        }
        pages.paragraph(ReportFactKind.MEASURED.label());
        pages.paragraph("Evidence quality. Each row is a stored observation, a calculation, or an unavailable result. "
                + "None of these rows is a vehicle defect or a pass/fail judgement.");
        List<String[]> rows = new ArrayList<>();
        for (QualityFinding finding : model.findings()) {
            String who = finding.signal().isBlank() ? finding.sourceFile() : finding.signal();
            rows.add(new String[] {
                    finding.id() + "\n" + finding.type(),
                    finding.status().label(),
                    who,
                    finding.explanation() + "\n" + finding.measured()
                            + (finding.time().isBlank() ? "" : "\n" + finding.time()),
                    finding.limitations()
            });
        }
        pages.grid(new String[] {"Finding", "Status", "Signal", "Evidence", "Limitation"}, rows,
                new float[] {1.2f, 0.8f, 1.0f, 2.0f, 1.4f});
    }

    private static void stamp(PDDocument document, PDFont font, EngineeringReportModel model) throws IOException {
        int count = document.getNumberOfPages();
        boolean v2 = model.template() == EngineeringReportTemplate.ENGINEERING_V2;
        String title = model.header() == null || model.header().title() == null ? "" : model.header().title();
        String header = "StellarAI  |  " + (title.isBlank() ? "Engineering Report V2" : title);
        for (int i = 0; i < count; i++) {
            PDPage page = document.getPage(i);
            try (PDPageContentStream stream = new PDPageContentStream(
                    document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                if (v2) {
                    stream.beginText();
                    stream.setFont(font, 8f);
                    stream.newLineAtOffset(MARGIN, page.getMediaBox().getHeight() - 28);
                    stream.showText(fitWidth(font, header, 8f, page.getMediaBox().getWidth() - (2 * MARGIN) - 72f));
                    stream.endText();
                }
                String footer = "Page " + (i + 1) + " of " + count;
                stream.beginText();
                stream.setFont(font, 8f);
                stream.newLineAtOffset(MARGIN, 28);
                stream.showText(footer);
                stream.endText();
            }
        }
    }

    private static float fitScale(float imageWidth, float imageHeight, float maxWidth, float maxHeight) {
        if (imageWidth <= 0f || imageHeight <= 0f) {
            return 1f;
        }
        float scale = Math.min(maxWidth / imageWidth, maxHeight / imageHeight);
        if (imageWidth < 400f && scale > 2f) {
            return 2f;
        }
        return scale;
    }

    private static String fitWidth(PDFont font, String text, float size, float width) throws IOException {
        String shown = glyphSafe(font, text);
        if (shown.isEmpty() || font.getStringWidth(shown) / 1000f * size <= width) {
            return shown;
        }
        while (shown.length() > 1 && font.getStringWidth(shown + "...") / 1000f * size > width) {
            shown = shown.substring(0, shown.length() - 1);
        }
        return shown + "...";
    }

    private static String glyphSafe(PDFont font, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int point = text.codePointAt(i);
            i += Character.charCount(point);
            String glyph = new String(Character.toChars(point));
            try {
                font.encode(glyph);
                out.append(glyph);
            } catch (IllegalArgumentException | IOException ex) {
                out.append('?');
            }
        }
        return out.toString();
    }

    private static void header(Pages pages, EngineeringReportModel model) throws IOException {
        var header = model.header();
        if (header == null) {
            return;
        }
        if (header.title() != null && !header.title().isBlank()) {
            pages.paragraph(header.title());
        }
        pages.pair("Project", header.projectName());
        if (header.investigationName() != null && !header.investigationName().isBlank()
                && !header.investigationName().equals(header.title())) {
            pages.pair("Investigation", header.investigationName());
        }
        pages.pair("Generated", header.generatedAt() == null ? "" : TIME.format(header.generatedAt()));
        pages.pair("Application version", header.applicationVersion());
        if (header.author() != null && !header.author().isBlank()) {
            pages.pair("Author", header.author());
        }
    }

    private static void project(Pages pages, EngineeringReportModel model) throws IOException {
        if (model.project() == null) {
            return;
        }
        var project = model.project();
        pages.heading("Project summary");
        pages.pair("Project name", project.name());
        pages.pair("Project status", project.processingStatus());
        pages.pair("Measurement files", Integer.toString(project.measurementCount()));
        pages.pair("DBC definitions", Integer.toString(project.definitionCount()));
        pages.pair("Warnings", Integer.toString(project.warningCount()));
        pages.pair("Catalog entries", Integer.toString(project.signalCount()));
    }

    private static void sources(Pages pages, EngineeringReportModel model) throws IOException {
        if (model.sources().isEmpty()) {
            return;
        }
        pages.heading("Data sources");
        for (var source : model.sources()) {
            pages.paragraph(text(source.displayName()) + "  " + text(source.fileType())
                    + "  " + text(source.sizeLabel()) + "  " + text(source.outcome()));
        }
    }

    private static void definitions(Pages pages, EngineeringReportModel model) throws IOException {
        if (model.definitions().isEmpty()) {
            return;
        }
        pages.heading("Definitions");
        for (var definition : model.definitions()) {
            pages.paragraph(text(definition.filename()) + "  " + text(definition.type()) + "  " + text(definition.status()));
        }
    }

    private static void processing(Pages pages, EngineeringReportModel model) throws IOException {
        var processing = model.processing();
        if (processing == null) {
            return;
        }
        pages.heading("Processing summary");
        pages.pair("Processing status", processing.status());
        pages.pair("Successful measurements", Integer.toString(processing.successfulJobs()));
        pages.pair("Failed measurements", Integer.toString(processing.failedJobs()));
        pages.pair("Warning count", Integer.toString(processing.warningCount()));
    }

    private static void warnings(Pages pages, EngineeringReportModel model) throws IOException {
        if (model.warnings().isEmpty()) {
            return;
        }
        pages.heading("Warnings");
        for (String warning : model.warnings()) {
            pages.paragraph(warning);
        }
    }

    private static void investigation(Pages pages, EngineeringReportModel model) throws IOException {
        if (model.investigation() == null) {
            return;
        }
        var investigation = model.investigation();
        pages.heading("Investigation");
        pages.pair("Title", investigation.name());
        pages.pair("Status", investigation.status() == null ? "" : investigation.status().name());
        if (model.investigationCreatedAt() != null) {
            pages.pair("Created", TIME.format(model.investigationCreatedAt()));
        }
        if (model.investigationUpdatedAt() != null) {
            pages.pair("Updated", TIME.format(model.investigationUpdatedAt()));
        }
        if (investigation.timeRange() != null && !investigation.timeRange().isBlank()) {
            pages.pair("Time range", investigation.timeRange());
        }
        if (investigation.duration() != null && !investigation.duration().isBlank()) {
            pages.pair("Duration", investigation.duration());
        }
        if (investigation.description() != null && !investigation.description().isBlank()) {
            pages.paragraph(investigation.description());
        }
    }

    private static void signals(Pages pages, EngineeringReportModel model) throws IOException {
        if (model.signals().isEmpty()) {
            return;
        }
        pages.heading("Signals analysed");
        for (var signal : model.signals()) {
            pages.paragraph(text(signal.name()) + "  " + blankUnit(signal.unit())
                    + "  " + text(signal.source()) + "  min " + text(signal.min()) + "  max " + text(signal.max()));
        }
    }

    private static void signalAnalysis(Pages pages, EngineeringReportModel model, Path root, boolean heading)
            throws IOException {
        if (model.signalAnalysis() == null || model.signalAnalysis().isEmpty()) {
            return;
        }
        signalAnalysis(pages, model.signalAnalysis(), root, heading ? "Signal Analysis" : null);
    }

    private static void signalAnalysis(Pages pages, List<SignalAnalysisSection> visuals, Path root, String heading)
            throws IOException {
        if (visuals == null || visuals.isEmpty()) {
            return;
        }
        boolean first = true;
        for (SignalAnalysisSection visual : visuals) {
            pages.placeFigure(first ? heading : null, visual.title(), resolve(root, visual.imageReference()),
                    visual.unavailableNote() == null || visual.unavailableNote().isBlank()
                            ? SignalAnalysisSection.UNAVAILABLE : visual.unavailableNote());
            first = false;
        }
    }

    private static String routeFacts(EngineeringReportModel.RouteMapSection map) {
        StringBuilder facts = new StringBuilder();
        appendFact(facts, "Distance", map.distanceLabel());
        appendFact(facts, "Start", map.startLabel());
        appendFact(facts, "End", map.endLabel());
        appendFact(facts, "Duration", map.durationLabel());
        appendFact(facts, "Samples", map.sampleCountLabel());
        return facts.toString();
    }

    private static void appendFact(StringBuilder facts, String label, String value) {
        if (value != null && !value.isBlank()) {
            facts.append(" ").append(label).append(": ").append(value).append(".");
        }
    }

    private static void routeMap(Pages pages, EngineeringReportModel model, Path root, boolean heading)
            throws IOException {
        routeMap(pages, model, root, heading, null);
    }

    private static void routeMap(
            Pages pages, EngineeringReportModel model, Path root, String keepWithHeading
    ) throws IOException {
        routeMap(pages, model, root, false, keepWithHeading);
    }

    private static void routeMap(
            Pages pages, EngineeringReportModel model, Path root, boolean heading, String keepWithHeading
    ) throws IOException {
        EngineeringReportModel.RouteMapSection map = model.routeMap();
        if (map == null) {
            return;
        }
        String missing = map.unavailableNote() == null || map.unavailableNote().isBlank()
                ? EngineeringReportModel.RouteMapSection.UNAVAILABLE : map.unavailableNote();
        if (heading) {
            pages.heading(map.title());
            pages.image(resolve(root, map.imageReference()), missing);
        } else {
            String caption = map.title() == null ? "" : map.title();
            if (keepWithHeading != null) {
                caption = "The route image is the verified GPS evidence already attached. "
                        + "An event marker is drawn only when a GNSS sample falls inside that event "
                        + "on the same recording clock. The marker is the first valid GNSS sample "
                        + "inside the window, not the whole event. Coordinates are not interpolated. "
                        + caption + "." + routeFacts(map);
            }
            pages.placeFigure(keepWithHeading, caption, resolve(root, map.imageReference()), missing);
        }
        if (keepWithHeading != null) {
            return;
        }
        if (map.distanceLabel() != null && !map.distanceLabel().isBlank()) {
            pages.pair("Distance", map.distanceLabel());
        }
        if (map.startLabel() != null && !map.startLabel().isBlank()) {
            pages.pair("Start", map.startLabel());
        }
        if (map.endLabel() != null && !map.endLabel().isBlank()) {
            pages.pair("End", map.endLabel());
        }
        if (map.durationLabel() != null && !map.durationLabel().isBlank()) {
            pages.pair("Duration", map.durationLabel());
        }
    }

    private static void figures(Pages pages, EngineeringReportModel model, Path root, boolean heading) throws IOException {
        figures(pages, model.figures(), root, heading ? "Evidence" : null);
    }

    private static void figures(Pages pages, List<EngineeringReportModel.Figure> figures, Path root, String heading)
            throws IOException {
        if (figures == null || figures.isEmpty()) {
            return;
        }
        boolean first = true;
        for (var figure : figures) {
            String caption = figure.caption() == null || figure.caption().isBlank()
                    ? "Captured evidence" : figure.caption();
            pages.placeFigure(first ? heading : null, "Figure " + figure.number() + " — " + caption
                    + (figure.timeRange() == null || figure.timeRange().isBlank() ? "" : " (" + figure.timeRange() + ")"),
                    resolve(root, figure.assetRelativePath()), "Evidence image unavailable.");
            first = false;
        }
    }

    private static void results(Pages pages, String title, List<EngineeringReportModel.ResultBlock> blocks) throws IOException {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        pages.heading(title);
        for (var block : blocks) {
            pages.paragraph(block.heading() + (block.stale() ? " STALE" : ""));
            pages.paragraph(block.body());
        }
    }

    private static void observations(Pages pages, EngineeringReportModel model) throws IOException {
        if (model.observations().isEmpty()) {
            return;
        }
        pages.heading("Observations");
        pages.paragraph("User-authored. Not diagnostic engine output.");
        for (EngineeringObservation observation : model.observations()) {
            pages.paragraph(observation.title() == null || observation.title().isBlank()
                    ? "Engineer Observation" : observation.title());
            pages.paragraph(observation.note());
        }
    }

    private static void conclusion(Pages pages, EngineeringReportModel model) throws IOException {
        boolean hasText = model.conclusion() != null && !model.conclusion().isBlank();
        if (!hasText && model.investigation() == null) {
            return;
        }
        pages.heading("Engineering conclusion");
        pages.paragraph(hasText ? model.conclusion() : "No conclusion recorded.");
    }

    private static void limitations(Pages pages, EngineeringReportModel model) throws IOException {
        if (model.limitations().isEmpty()) {
            return;
        }
        pages.heading("Limitations");
        for (String item : model.limitations()) {
            pages.paragraph(item);
        }
    }

    private static void appendix(Pages pages, EngineeringReportModel model) throws IOException {
        if (model.appendix().isEmpty()) {
            return;
        }
        pages.heading("Appendix");
        for (String item : model.appendix()) {
            pages.paragraph(item);
        }
    }

    private static void evidence(Pages pages, EngineeringReportModel model) throws IOException {
        if (model.evidence().isEmpty()) {
            return;
        }
        pages.heading("Evidence order");
        for (EngineeringEvidenceItem item : model.evidence()) {
            String caption = item.caption() == null || item.caption().isBlank() ? item.summary() : item.caption();
            pages.paragraph((item.stale() ? "STALE " : "") + item.kind().name() + " — " + caption);
        }
    }

    private static Path resolve(Path root, String relative) {
        if (relative == null || relative.isBlank()) {
            return null;
        }
        Path path = Path.of(relative);
        if (path.isAbsolute()) {
            return path;
        }
        return root.resolve(relative);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String blankUnit(String unit) {
        return unit == null || unit.isBlank() ? "—" : unit;
    }

    private static PDFont loadFont(PDDocument document, boolean bold) throws IOException {
        String windir = System.getenv("WINDIR");
        if (windir != null) {
            Path file = Path.of(windir, "Fonts", bold ? "arialbd.ttf" : "arial.ttf");
            if (Files.isRegularFile(file)) {
                return PDType0Font.load(document, file.toFile());
            }
        }
        return new PDType1Font(bold
                ? Standard14Fonts.FontName.HELVETICA_BOLD
                : Standard14Fonts.FontName.HELVETICA);
    }

    private static final class Pages implements AutoCloseable {
        private final PDDocument document;
        private final PDFont regular;
        private final PDFont bold;
        private PDPageContentStream stream;
        private float y;

        private Pages(PDDocument document, PDFont regular, PDFont bold) throws IOException {
            this.document = document;
            this.regular = regular;
            this.bold = bold;
            nextPage();
        }

        private void brand(String text) throws IOException {
            write(regular, 9f, text, 4f);
        }

        private void heading(String text) throws IOException {
            write(bold, HEAD, text, 8f);
        }

        private void paragraph(String text) throws IOException {
            write(regular, BODY, text == null ? "" : text, 4f);
        }

        private void pair(String label, String value) throws IOException {
            write(regular, BODY, label + ": " + (value == null ? "" : value), 2f);
        }

        private void image(Path file, String missing) throws IOException {
            if (file == null || !Files.isRegularFile(file)) {
                paragraph(missing);
                return;
            }
            PDImageXObject image;
            try {
                image = PDImageXObject.createFromFileByExtension(file.toFile(), document);
            } catch (IOException | IllegalArgumentException ex) {
                paragraph(missing);
                return;
            }
            float maxWidth = PDRectangle.A4.getWidth() - (2 * MARGIN);
            float maxHeight = PDRectangle.A4.getHeight() - (2 * MARGIN) - 36f;
            float scale = fitScale(image.getWidth(), image.getHeight(), maxWidth, maxHeight);
            float width = image.getWidth() * scale;
            float height = image.getHeight() * scale;
            ensure(height + 8f);
            stream.drawImage(image, MARGIN, y - height, width, height);
            y -= height + 8f;
        }

        private boolean sectionDividerArmed;

        private void armSectionDivider() {
            sectionDividerArmed = true;
        }

        /**
         * The rule stays with the following block. A block that moves to the next page
         * does not leave the rule behind on the previous page.
         */
        private void keepDividerWithFollowing(float want) throws IOException {
            float body = PDRectangle.A4.getHeight() - (2 * MARGIN);
            float room = y - MARGIN;
            if (room < body - 4f && room < want) {
                sectionDividerArmed = false;
                nextPage();
                return;
            }
            takeSectionDivider(want);
        }

        private void takeSectionDivider(float followingWant) throws IOException {
            if (!sectionDividerArmed) {
                return;
            }
            sectionDividerArmed = false;
            float top = PDRectangle.A4.getHeight() - MARGIN;
            if (y >= top - 12f || y - MARGIN < 14f || followingWant < 0f) {
                return;
            }
            float lineY = y - 2.5f;
            stream.setStrokingColor(215f / 255f, 226f / 255f, 238f / 255f);
            stream.setLineWidth(0.75f);
            stream.moveTo(MARGIN, lineY);
            stream.lineTo(PDRectangle.A4.getWidth() - MARGIN, lineY);
            stream.stroke();
            stream.setStrokingColor(0f, 0f, 0f);
        }

        private void sectionHeading(String title, float contentNeed) throws IOException {
            float want = HEAD + 8f + contentNeed;
            keepDividerWithFollowing(want);
            heading(title);
        }

        private void placeFigure(String headingOrNull, String caption, Path file, String missing) throws IOException {
            float body = PDRectangle.A4.getHeight() - (2 * MARGIN);
            String shown = caption == null ? "" : caption;
            int lineCount = Math.max(1, wrap(regular, BODY, shown).size());
            float captionHeight = lineCount * (BODY + 4f);
            boolean withHeading = headingOrNull != null && !headingOrNull.isBlank();
            float headingHeight = withHeading ? HEAD + 8f : 0f;
            float natural = fittedImageHeight(file);
            float imageRoom = Math.max(80f, body - headingHeight - captionHeight - 8f);
            float drawHeight = natural <= 0f ? BODY + 8f : Math.min(natural, imageRoom);
            float need = headingHeight + captionHeight + drawHeight + 8f;
            keepDividerWithFollowing(need);
            if (withHeading) {
                heading(headingOrNull);
            }
            paragraph(shown);
            if (natural <= 0f) {
                paragraph(missing);
                return;
            }
            drawFitted(file, missing, Math.max(40f, y - MARGIN - 8f));
        }

        private void drawFitted(Path file, String missing, float maxHeight) throws IOException {
            if (file == null || !Files.isRegularFile(file)) {
                paragraph(missing);
                return;
            }
            PDImageXObject image;
            try {
                image = PDImageXObject.createFromFileByExtension(file.toFile(), document);
            } catch (IOException | IllegalArgumentException ex) {
                paragraph(missing);
                return;
            }
            float maxWidth = PDRectangle.A4.getWidth() - (2 * MARGIN);
            float scale = fitScale(image.getWidth(), image.getHeight(), maxWidth, maxHeight);
            float width = image.getWidth() * scale;
            float height = image.getHeight() * scale;
            stream.drawImage(image, MARGIN, y - height, width, height);
            y -= height + 8f;
        }

        private void captionedImage(String caption, Path file, String missing) throws IOException {
            float captionHeight = BODY + 8f;
            float imageHeight = fittedImageHeight(file);
            if (imageHeight > 0f && y - (captionHeight + imageHeight) < MARGIN) {
                nextPage();
            }
            paragraph(caption);
            image(file, missing);
        }

        private float fittedImageHeight(Path file) {
            if (file == null || !Files.isRegularFile(file)) {
                return 0f;
            }
            try {
                BufferedImage image = ImageIO.read(file.toFile());
                if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                    return 0f;
                }
                float maxWidth = PDRectangle.A4.getWidth() - (2 * MARGIN);
                float maxHeight = PDRectangle.A4.getHeight() - (2 * MARGIN) - 36f;
                float scale = fitScale(image.getWidth(), image.getHeight(), maxWidth, maxHeight);
                return image.getHeight() * scale;
            } catch (IOException ex) {
                return 0f;
            }
        }

        private void grid(String[] headers, List<String[]> rows) throws IOException {
            float[] even = new float[headers.length];
            for (int i = 0; i < even.length; i++) {
                even[i] = 1f;
            }
            grid(headers, rows, even);
        }

        private void grid(String[] headers, List<String[]> rows, float[] weights) throws IOException {
            int cols = headers.length;
            float width = PDRectangle.A4.getWidth() - (2 * MARGIN);
            float weightSum = 0f;
            for (float weight : weights) {
                weightSum += weight;
            }
            float[] col = new float[cols];
            for (int c = 0; c < cols; c++) {
                col[c] = width * (c < weights.length ? weights[c] : 1f) / weightSum;
            }
            float size = 8f;
            float line = size + 3f;
            float headerNeed = line + 6f;
            if (rows == null || rows.isEmpty()) {
                return;
            }
            boolean headerOnPage = false;
            float body = PDRectangle.A4.getHeight() - (2 * MARGIN);
            for (String[] row : rows) {
                List<List<String>> wrapped = new ArrayList<>();
                int lines = 1;
                for (int c = 0; c < cols; c++) {
                    String cell = row != null && c < row.length && row[c] != null ? row[c] : "";
                    List<String> parts = wrap(regular, size, cell, col[c] - 6f);
                    wrapped.add(parts);
                    lines = Math.max(lines, Math.max(1, parts.size()));
                }
                int drawn = 0;
                while (drawn < lines) {
                    int remaining = lines - drawn;
                    float remainingNeed = remaining * line + 4f;
                    float room = y - MARGIN;
                    boolean atTop = room >= body - 4f;
                    boolean wholeFitsHere = headerOnPage
                            ? room >= remainingNeed
                            : room >= headerNeed + remainingNeed;
                    boolean wholeFitsFresh = headerNeed + remainingNeed <= body;
                    if (drawn == 0 && !wholeFitsHere && wholeFitsFresh && (!atTop || headerOnPage)) {
                        nextPage();
                        headerOnPage = false;
                    }
                    if (!headerOnPage) {
                        drawGridHeader(headers, col, size, line);
                        headerOnPage = true;
                    }
                    int take = remaining;
                    if (y - MARGIN < remainingNeed) {
                        take = Math.max(1, (int) Math.floor((y - MARGIN) / line));
                        take = Math.min(take, remaining);
                    }
                    float top = y;
                    float x = MARGIN;
                    for (int c = 0; c < cols; c++) {
                        float cy = top;
                        List<String> parts = wrapped.get(c);
                        for (int i = drawn; i < drawn + take && i < parts.size(); i++) {
                            stream.beginText();
                            stream.setFont(regular, size);
                            stream.newLineAtOffset(x + 2f, cy - size);
                            stream.showText(encodable(regular, parts.get(i)));
                            stream.endText();
                            cy -= line;
                        }
                        x += col[c];
                    }
                    y = top - (take * line + 4f);
                    drawn += take;
                    if (drawn < lines) {
                        nextPage();
                        headerOnPage = false;
                    }
                }
            }
        }

        private void drawGridHeader(String[] headers, float[] col, float size, float line) throws IOException {
            float x = MARGIN;
            for (int c = 0; c < headers.length; c++) {
                stream.beginText();
                stream.setFont(bold, size);
                stream.newLineAtOffset(x + 2f, y - size);
                stream.showText(encodable(bold, headers[c]));
                stream.endText();
                x += col[c];
            }
            y -= line + 4f;
        }

        private void write(PDFont font, float size, String value, float gap) throws IOException {
            for (String line : wrap(font, size, value)) {
                ensure(size + gap);
                String shown = encodable(font, line);
                stream.beginText();
                stream.setFont(font, size);
                stream.newLineAtOffset(MARGIN, y - size);
                stream.showText(shown);
                stream.endText();
                y -= size + gap;
            }
        }

        private void keep(float needed) throws IOException {
            ensure(needed);
        }

        private void ensure(float needed) throws IOException {
            if (y - needed < MARGIN) {
                nextPage();
            }
        }

        private void nextPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = PDRectangle.A4.getHeight() - MARGIN;
        }

        private List<String> wrap(PDFont font, float size, String value) throws IOException {
            return wrap(font, size, value, PDRectangle.A4.getWidth() - (2 * MARGIN));
        }

        private List<String> wrap(PDFont font, float size, String value, float width) throws IOException {
            String cleaned = value == null ? "" : value.replace("\r", "");
            List<String> lines = new ArrayList<>();
            for (String paragraph : cleaned.split("\n", -1)) {
                if (paragraph.isEmpty()) {
                    lines.add("");
                    continue;
                }
                StringBuilder line = new StringBuilder();
                for (String word : paragraph.split(" ", -1)) {
                    String candidate = line.isEmpty() ? word : line + " " + word;
                    if (measure(font, size, candidate) <= width) {
                        line.setLength(0);
                        line.append(candidate);
                    } else {
                        if (!line.isEmpty()) {
                            lines.add(line.toString());
                            line.setLength(0);
                        }
                        if (measure(font, size, word) <= width) {
                            line.append(word);
                        } else {
                            lines.addAll(breakWord(font, size, word, width));
                        }
                    }
                }
                lines.add(line.toString());
            }
            return lines;
        }

        private List<String> breakWord(PDFont font, float size, String word, float width) throws IOException {
            if (word.indexOf('_') >= 0 || word.indexOf('·') >= 0) {
                List<String> parts = new ArrayList<>();
                StringBuilder current = new StringBuilder();
                for (String piece : word.split("(?<=[_·])", -1)) {
                    if (piece.isEmpty()) {
                        continue;
                    }
                    String next = current + piece;
                    if (current.length() > 0 && measure(font, size, next) > width) {
                        parts.add(current.toString());
                        current.setLength(0);
                    }
                    if (measure(font, size, piece) > width) {
                        if (!current.isEmpty()) {
                            parts.add(current.toString());
                            current.setLength(0);
                        }
                        parts.addAll(breakCharacters(font, size, piece, width));
                    } else {
                        current.append(piece);
                    }
                }
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                }
                return parts;
            }
            return breakCharacters(font, size, word, width);
        }

        private List<String> breakCharacters(PDFont font, float size, String word, float width) throws IOException {
            List<String> parts = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < word.length(); ) {
                int point = word.codePointAt(i);
                String next = current.toString() + new String(Character.toChars(point));
                if (measure(font, size, next) > width && !current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                } else {
                    current.appendCodePoint(point);
                    i += Character.charCount(point);
                }
            }
            if (!current.isEmpty()) {
                parts.add(current.toString());
            }
            return parts;
        }

        private float measure(PDFont font, float size, String text) throws IOException {
            String safe = encodable(font, text);
            return font.getStringWidth(safe) / 1000f * size;
        }

        private String encodable(PDFont font, String text) {
            StringBuilder out = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); ) {
                int point = text.codePointAt(i);
                i += Character.charCount(point);
                String glyph = new String(Character.toChars(point));
                try {
                    font.encode(glyph);
                    out.append(glyph);
                } catch (IllegalArgumentException | IOException ex) {
                    out.append('?');
                }
            }
            return out.toString();
        }

        @Override
        public void close() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }
    }
}
