package com.stellar.platform.analysis.investigation.report;

import com.stellar.platform.analysis.investigation.EngineeringEvidenceItem;
import com.stellar.platform.analysis.investigation.EngineeringObservation;
import com.stellar.platform.analysis.investigation.EngineeringReportSection;
import com.stellar.platform.analysis.investigation.EngineeringReportTemplate;
import com.stellar.platform.analysis.investigation.kpi.EngineeringKpiResult;
import com.stellar.platform.analysis.investigation.InvestigationHealth;
import com.stellar.platform.analysis.investigation.InvestigationStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Immutable engineering report DTO. Renderer must not query JavaFX. */
public final class EngineeringReportModel {

    private final Header header;
    private final ProjectSection project;
    private final InvestigationSection investigation;
    private final List<SourceRow> sources;
    private final List<SignalRow> signals;
    private final List<EngineeringObservation> observations;
    private final List<ResultBlock> analysis;
    private final List<ResultBlock> diagnostics;
    private final List<ResultBlock> frequency;
    private final List<Figure> figures;
    private final List<String> warnings;
    private final List<String> appendix;
    private final String conclusion;
    private final InvestigationHealth health;
    private final List<EngineeringEvidenceItem> evidence;
    private final List<DefinitionRow> definitions;
    private final ProcessingDetail processing;
    private final List<String> limitations;
    private final Instant investigationCreatedAt;
    private final Instant investigationUpdatedAt;
    private final RouteMapSection routeMap;
    private final List<SignalAnalysisSection> signalAnalysis;
    private final EngineeringReportTemplate template;
    private final List<EngineeringReportSection> sections;
    private final String timestampBasis;
    private final List<EngineeringKpiResult> kpis;
    private final List<EventRow> events;
    private final List<EvaluationRow> evaluations;
    private final List<QualityFinding> findings;

    private EngineeringReportModel(Builder b) {
        this.header = b.header;
        this.project = b.project;
        this.investigation = b.investigation;
        this.sources = List.copyOf(b.sources);
        this.signals = List.copyOf(b.signals);
        this.observations = List.copyOf(b.observations);
        this.analysis = List.copyOf(b.analysis);
        this.diagnostics = List.copyOf(b.diagnostics);
        this.frequency = List.copyOf(b.frequency);
        this.figures = List.copyOf(b.figures);
        this.warnings = List.copyOf(b.warnings);
        this.appendix = List.copyOf(b.appendix);
        this.conclusion = b.conclusion == null ? "" : b.conclusion;
        this.health = b.health == null ? InvestigationHealth.CURRENT : b.health;
        this.evidence = List.copyOf(b.evidence);
        this.definitions = List.copyOf(b.definitions);
        this.processing = b.processing;
        this.limitations = List.copyOf(b.limitations);
        this.investigationCreatedAt = b.investigationCreatedAt;
        this.investigationUpdatedAt = b.investigationUpdatedAt;
        this.routeMap = b.routeMap;
        this.signalAnalysis = List.copyOf(b.signalAnalysis);
        this.template = b.template == null ? EngineeringReportTemplate.CLASSIC : b.template;
        this.sections = List.copyOf(b.sections);
        this.timestampBasis = b.timestampBasis == null ? "" : b.timestampBasis;
        this.kpis = List.copyOf(b.kpis);
        this.events = List.copyOf(b.events);
        this.evaluations = List.copyOf(b.evaluations);
        this.findings = List.copyOf(b.findings);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Header header() { return header; }
    public ProjectSection project() { return project; }
    public InvestigationSection investigation() { return investigation; }
    public List<SourceRow> sources() { return sources; }
    public List<SignalRow> signals() { return signals; }
    public List<EngineeringObservation> observations() { return observations; }
    public List<ResultBlock> analysis() { return analysis; }
    public List<ResultBlock> diagnostics() { return diagnostics; }
    public List<ResultBlock> frequency() { return frequency; }
    public List<Figure> figures() { return figures; }
    public List<String> warnings() { return warnings; }
    public List<String> appendix() { return appendix; }
    public String conclusion() { return conclusion; }
    public InvestigationHealth health() { return health; }
    public List<EngineeringEvidenceItem> evidence() { return evidence; }
    public List<DefinitionRow> definitions() { return definitions; }
    public ProcessingDetail processing() { return processing; }
    public List<String> limitations() { return limitations; }
    public Instant investigationCreatedAt() { return investigationCreatedAt; }
    public Instant investigationUpdatedAt() { return investigationUpdatedAt; }
    public RouteMapSection routeMap() { return routeMap; }
    public List<SignalAnalysisSection> signalAnalysis() { return signalAnalysis; }
    public EngineeringReportTemplate template() { return template; }
    public List<EngineeringReportSection> sections() { return sections; }
    public String timestampBasis() { return timestampBasis; }
    public List<EngineeringKpiResult> kpis() { return kpis; }
    public List<EventRow> events() { return events; }
    public List<EvaluationRow> evaluations() { return evaluations; }
    public List<QualityFinding> findings() { return findings; }

    public boolean shows(EngineeringReportSection section) {
        return template == EngineeringReportTemplate.ENGINEERING_V2 && sections.contains(section);
    }

    public EngineeringReportModel withFigures(List<Figure> replacement) {
        Builder copy = copy();
        copy.figures(replacement);
        return copy.build();
    }

    public EngineeringReportModel withRouteMap(RouteMapSection section) {
        Builder copy = copy();
        copy.routeMap(section);
        return copy.build();
    }

    public EngineeringReportModel withKpis(List<EngineeringKpiResult> rows) {
        Builder copy = copy();
        copy.kpis(rows);
        return copy.build();
    }

    public EngineeringReportModel withEvents(List<EventRow> rows) {
        Builder copy = copy();
        copy.events(rows);
        return copy.build();
    }

    public EngineeringReportModel withEvaluations(List<EvaluationRow> rows) {
        Builder copy = copy();
        copy.evaluations(rows);
        return copy.build();
    }

    public EngineeringReportModel withFindings(List<QualityFinding> rows) {
        Builder copy = copy();
        copy.findings(rows);
        return copy.build();
    }

    public EngineeringReportModel withSignalAnalysis(List<SignalAnalysisSection> sections) {
        Builder copy = copy();
        copy.signalAnalysis(sections);
        return copy.build();
    }

    public EngineeringReportModel withProjectFacts(
            List<DefinitionRow> definitionRows,
            ProcessingDetail processingDetail,
            List<String> limitationLines
    ) {
        Builder copy = copy();
        copy.definitions(definitionRows);
        copy.processing(processingDetail);
        copy.limitations(limitationLines);
        return copy.build();
    }

    public EngineeringReportModel withAppendix(List<String> lines) {
        Builder copy = copy();
        copy.appendix(lines);
        return copy.build();
    }

    public EngineeringReportModel withProjectStatus(String status) {
        if (project == null || status == null || status.isBlank()) {
            return this;
        }
        return withProject(new ProjectSection(
                project.name(), project.sourceCount(), project.measurementCount(),
                project.definitionCount(), project.signalCount(), status, project.warningCount()));
    }

    private EngineeringReportModel withProject(ProjectSection replacement) {
        Builder copy = copy();
        copy.project(replacement);
        return copy.build();
    }

    private Builder copy() {
        Builder copy = builder()
                .header(header)
                .project(project)
                .investigation(investigation)
                .sources(sources)
                .signals(signals)
                .observations(observations)
                .analysis(analysis)
                .diagnostics(diagnostics)
                .frequency(frequency)
                .figures(figures)
                .warnings(warnings)
                .appendix(appendix)
                .conclusion(conclusion)
                .health(health)
                .evidence(evidence)
                .definitions(definitions)
                .processing(processing)
                .limitations(limitations)
                .routeMap(routeMap)
                .signalAnalysis(signalAnalysis)
                .template(template)
                .sections(sections)
                .timestampBasis(timestampBasis)
                .kpis(kpis)
                .events(events)
                .evaluations(evaluations)
                .findings(findings);
        copy.investigationCreatedAt = investigationCreatedAt;
        copy.investigationUpdatedAt = investigationUpdatedAt;
        return copy;
    }

    public record Header(String title, String projectName, String investigationName,
                         Instant generatedAt, String applicationVersion, String author) {
    }

    public record ProjectSection(String name, int sourceCount, int measurementCount, int definitionCount,
                                 int signalCount, String processingStatus, int warningCount) {
    }

    public record InvestigationSection(String name, String description, InvestigationStatus status,
                                       String timeRange, String duration, int evidenceSignalCount) {
    }

    public record SourceRow(String displayName, String fileType, String fingerprintSummary,
                            String sizeLabel, String outcome) {
    }

    public record SignalRow(String name, String unit, String source, String min, String max,
                            String mean, String rms, String delta, String categoricalLabel) {
        public SignalRow(String name, String unit, String source, String min, String max,
                         String mean, String rms, String delta) {
            this(name, unit, source, min, max, mean, rms, delta, "");
        }
    }

    public record ResultBlock(String heading, String body, boolean stale, String staleReason,
                              String reproducibility) {
    }

    public record Figure(int number, String caption, String assetRelativePath, String timeRange) {
    }

    /**
     * An engineer-authored event included in the report. The original annotation is not copied.
     * {@code narrative} carries the fields that do not belong in the summary table.
     */
    public record EventRow(
            String name,
            String type,
            String start,
            String end,
            String sourceFile,
            String signals,
            String description,
            String snapshot,
            String location,
            String provenance,
            EventNarrative narrative
    ) {
        public EventRow(
                String name, String type, String start, String end, String sourceFile, String signals,
                String description, String snapshot, String location, String provenance
        ) {
            this(name, type, start, end, sourceFile, signals, description, snapshot, location, provenance,
                    EventNarrative.none());
        }

        public EventRow {
            narrative = narrative == null ? EventNarrative.none() : narrative;
        }
    }

    /**
     * Frozen expected-versus-actual row. Engineer-entered event notes stay on {@link EventRow}.
     * {@code exploratory} marks a draft rule rather than a customer-approved compliance result.
     */
    public record EvaluationRow(
            String eventName,
            String procedure,
            String ruleIdentity,
            String approval,
            String expected,
            String actual,
            String unit,
            String coverage,
            String method,
            String outcome,
            String evidence,
            String timeRange,
            String reason,
            String detail,
            boolean exploratory
    ) {
    }

    /** Expected behaviour, measured notes, confirmation, and limitations for one event. */
    public record EventNarrative(String confirmation, String expected, String measured, String limitations) {
        public EventNarrative {
            confirmation = confirmation == null ? "" : confirmation;
            expected = expected == null ? "" : expected;
            measured = measured == null ? "" : measured;
            limitations = limitations == null ? "" : limitations;
        }

        public static EventNarrative none() {
            return new EventNarrative("", "", "", "");
        }
    }

    /**
     * Static route image already captured from the configured map. Blank labels are omitted.
     * {@code imageReference} is a data URI or a relative report asset, never a live map URL.
     */
    public record RouteMapSection(
            String title,
            String imageReference,
            String unavailableNote,
            String startLabel,
            String endLabel,
            String durationLabel,
            String distanceLabel,
            String sampleCountLabel,
            RouteMapSummary summary,
            List<String> additionalImages
    ) {
        public static final String UNAVAILABLE = "Map preview unavailable for this report.";

        public RouteMapSection(
                String title,
                String imageReference,
                String unavailableNote,
                String startLabel,
                String endLabel,
                String durationLabel,
                String distanceLabel,
                String sampleCountLabel,
                RouteMapSummary summary
        ) {
            this(title, imageReference, unavailableNote, startLabel, endLabel, durationLabel,
                    distanceLabel, sampleCountLabel, summary, List.of());
        }

        public RouteMapSection(
                String title,
                String imageReference,
                String unavailableNote,
                String startLabel,
                String endLabel,
                String durationLabel,
                String distanceLabel,
                String sampleCountLabel
        ) {
            this(title, imageReference, unavailableNote, startLabel, endLabel, durationLabel,
                    distanceLabel, sampleCountLabel, RouteMapSummary.none(), List.of());
        }

        public RouteMapSection {
            title = title == null || title.isBlank() ? "Route Map" : title;
            imageReference = imageReference == null ? "" : imageReference;
            unavailableNote = unavailableNote == null ? "" : unavailableNote;
            startLabel = startLabel == null ? "" : startLabel;
            endLabel = endLabel == null ? "" : endLabel;
            durationLabel = durationLabel == null ? "" : durationLabel;
            distanceLabel = distanceLabel == null ? "" : distanceLabel;
            sampleCountLabel = sampleCountLabel == null ? "" : sampleCountLabel;
            summary = summary == null ? RouteMapSummary.none() : summary;
            additionalImages = additionalImages == null ? List.of() : List.copyOf(additionalImages);
        }

        public static RouteMapSection unavailable(RouteMapSnapshot snapshot) {
            return from(snapshot, "", UNAVAILABLE);
        }

        public static RouteMapSection image(RouteMapSnapshot snapshot, String imageReference) {
            return from(snapshot, imageReference, "");
        }

        private static RouteMapSection from(RouteMapSnapshot snapshot, String imageReference, String note) {
            if (snapshot == null) {
                return new RouteMapSection("Route Map", imageReference, note, "", "", "", "", "");
            }
            String distance = snapshot.distanceMeters() == null
                    ? ""
                    : com.stellar.platform.analysis.investigation.EngineeringFormats
                    .number(snapshot.distanceMeters() / 1000.0) + " km";
            String samples = snapshot.sampleCount() > 0
                    ? String.format(java.util.Locale.US, "%,d", snapshot.sampleCount())
                    : "";
            return new RouteMapSection(
                    "Route Map",
                    imageReference,
                    note,
                    com.stellar.platform.analysis.investigation.EngineeringFormats.seconds(snapshot.startNanos()),
                    com.stellar.platform.analysis.investigation.EngineeringFormats.seconds(snapshot.endNanos()),
                    com.stellar.platform.analysis.investigation.EngineeringFormats.duration(
                            snapshot.startNanos(), snapshot.endNanos()),
                    distance,
                    samples);
        }
    }

    public record DefinitionRow(String filename, String type, String status) {
    }

    /** Processing facts already stored for the project. Counts are not recomputed from samples. */
    public record ProcessingDetail(
            String status,
            int successfulJobs,
            int failedJobs,
            int warningCount,
            String finishedAt,
            long canFrames
    ) {
    }

    public static final class Builder {
        private Header header;
        private ProjectSection project;
        private InvestigationSection investigation;
        private final List<SourceRow> sources = new ArrayList<>();
        private final List<SignalRow> signals = new ArrayList<>();
        private final List<EngineeringObservation> observations = new ArrayList<>();
        private final List<ResultBlock> analysis = new ArrayList<>();
        private final List<ResultBlock> diagnostics = new ArrayList<>();
        private final List<ResultBlock> frequency = new ArrayList<>();
        private final List<Figure> figures = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<String> appendix = new ArrayList<>();
        private String conclusion = "";
        private InvestigationHealth health = InvestigationHealth.CURRENT;
        private final List<EngineeringEvidenceItem> evidence = new ArrayList<>();
        private final List<DefinitionRow> definitions = new ArrayList<>();
        private ProcessingDetail processing;
        private final List<String> limitations = new ArrayList<>();
        private Instant investigationCreatedAt;
        private Instant investigationUpdatedAt;
        private RouteMapSection routeMap;
        private final List<SignalAnalysisSection> signalAnalysis = new ArrayList<>();
        private EngineeringReportTemplate template = EngineeringReportTemplate.CLASSIC;
        private final List<EngineeringReportSection> sections = new ArrayList<>();
        private String timestampBasis = "";
        private final List<EngineeringKpiResult> kpis = new ArrayList<>();
        private final List<EventRow> events = new ArrayList<>();
        private final List<EvaluationRow> evaluations = new ArrayList<>();
        private final List<QualityFinding> findings = new ArrayList<>();

        public Builder header(Header v) { this.header = v; return this; }
        public Builder project(ProjectSection v) { this.project = v; return this; }
        public Builder investigation(InvestigationSection v) { this.investigation = v; return this; }
        public Builder sources(List<SourceRow> v) { addAll(sources, v); return this; }
        public Builder signals(List<SignalRow> v) { addAll(signals, v); return this; }
        public Builder observations(List<EngineeringObservation> v) { addAll(observations, v); return this; }
        public Builder analysis(List<ResultBlock> v) { addAll(analysis, v); return this; }
        public Builder diagnostics(List<ResultBlock> v) { addAll(diagnostics, v); return this; }
        public Builder frequency(List<ResultBlock> v) { addAll(frequency, v); return this; }
        public Builder figures(List<Figure> v) { addAll(figures, v); return this; }
        public Builder warnings(List<String> v) { addAll(warnings, v); return this; }
        public Builder appendix(List<String> v) { addAll(appendix, v); return this; }
        public Builder conclusion(String v) { this.conclusion = v; return this; }
        public Builder health(InvestigationHealth v) { this.health = v; return this; }
        public Builder evidence(List<EngineeringEvidenceItem> v) { addAll(evidence, v); return this; }
        public Builder definitions(List<DefinitionRow> v) { addAll(definitions, v); return this; }
        public Builder processing(ProcessingDetail v) { this.processing = v; return this; }
        public Builder limitations(List<String> v) { addAll(limitations, v); return this; }
        public Builder investigationTimes(Instant createdAt, Instant updatedAt) {
            this.investigationCreatedAt = createdAt;
            this.investigationUpdatedAt = updatedAt;
            return this;
        }
        public Builder routeMap(RouteMapSection v) { this.routeMap = v; return this; }
        public Builder signalAnalysis(List<SignalAnalysisSection> v) { addAll(signalAnalysis, v); return this; }
        public Builder template(EngineeringReportTemplate v) { this.template = v; return this; }
        public Builder sections(List<EngineeringReportSection> v) { addAll(sections, v); return this; }
        public Builder timestampBasis(String v) { this.timestampBasis = v; return this; }
        public Builder kpis(List<EngineeringKpiResult> v) { addAll(kpis, v); return this; }
        public Builder events(List<EventRow> v) { addAll(events, v); return this; }
        public Builder evaluations(List<EvaluationRow> v) { addAll(evaluations, v); return this; }
        public Builder findings(List<QualityFinding> v) { addAll(findings, v); return this; }

        public EngineeringReportModel build() {
            return new EngineeringReportModel(this);
        }

        private static <T> void addAll(List<T> dest, List<T> src) {
            dest.clear();
            if (src != null) {
                dest.addAll(src);
            }
        }
    }
}
