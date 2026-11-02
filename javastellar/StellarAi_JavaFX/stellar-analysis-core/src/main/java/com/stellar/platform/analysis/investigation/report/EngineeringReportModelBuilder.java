package com.stellar.platform.analysis.investigation.report;

import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.EngineeringObservation;
import com.stellar.platform.analysis.investigation.EngineeringReportSection;
import com.stellar.platform.analysis.investigation.EngineeringReportTemplate;
import com.stellar.platform.analysis.investigation.EvidenceKind;
import com.stellar.platform.analysis.investigation.InvestigationReportConfig;
import com.stellar.platform.analysis.investigation.EngineeringFormats;
import com.stellar.platform.analysis.investigation.InvestigationValidity;
import com.stellar.platform.analysis.investigation.PathSanitizer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds an immutable report model from investigation data plus caller-supplied
 * project/source/result extracts. Does not query JavaFX or rewrite engines.
 */
public final class EngineeringReportModelBuilder {

    public record Context(
            String applicationVersion,
            String author,
            EngineeringReportModel.ProjectSection project,
            List<EngineeringReportModel.SourceRow> sources,
            List<EngineeringReportModel.SignalRow> signals,
            List<EngineeringReportModel.ResultBlock> analysis,
            List<EngineeringReportModel.ResultBlock> diagnostics,
            List<EngineeringReportModel.ResultBlock> frequency,
            List<EngineeringReportModel.Figure> figures,
            List<String> processingWarnings,
            EngineeringReportModel.RouteMapSection routeMap
    ) {
        public Context(
                String applicationVersion,
                String author,
                EngineeringReportModel.ProjectSection project,
                List<EngineeringReportModel.SourceRow> sources,
                List<EngineeringReportModel.SignalRow> signals,
                List<EngineeringReportModel.ResultBlock> analysis,
                List<EngineeringReportModel.ResultBlock> diagnostics,
                List<EngineeringReportModel.ResultBlock> frequency,
                List<EngineeringReportModel.Figure> figures,
                List<String> processingWarnings
        ) {
            this(applicationVersion, author, project, sources, signals, analysis, diagnostics,
                    frequency, figures, processingWarnings, null);
        }

        public Context withRouteMap(EngineeringReportModel.RouteMapSection section) {
            return new Context(applicationVersion, author, project, sources, signals, analysis,
                    diagnostics, frequency, figures, processingWarnings, section);
        }
        public Context {
            sources = sources == null ? List.of() : List.copyOf(sources);
            signals = signals == null ? List.of() : List.copyOf(signals);
            analysis = analysis == null ? List.of() : List.copyOf(analysis);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            frequency = frequency == null ? List.of() : List.copyOf(frequency);
            figures = figures == null ? List.of() : List.copyOf(figures);
            processingWarnings = processingWarnings == null ? List.of() : List.copyOf(processingWarnings);
        }
    }

    /**
     * Product rule, not a converted clock. Absolute UTC is not invented for an unknown recording clock.
     */
    public static final String TIMESTAMP_BASIS =
            "Timestamps are measurement-relative seconds from each recording's own zero. "
                    + "Files are not placed on one timeline unless their recording clocks are comparable. "
                    + "No event geofence or pass/fail rule is applied in this report.";

    private EngineeringReportModelBuilder() {
    }

    public static EngineeringReportModel build(EngineeringInvestigation investigation, Context context) {
        InvestigationReportConfig cfg = investigation.reportConfig();
        boolean v2 = cfg.template() == EngineeringReportTemplate.ENGINEERING_V2;
        EngineeringReportModel.Builder b = EngineeringReportModel.builder()
                .header(new EngineeringReportModel.Header(
                        investigation.name(),
                        context.project() == null ? "" : context.project().name(),
                        investigation.name(),
                        Instant.now(),
                        context.applicationVersion() == null ? "" : context.applicationVersion(),
                        context.author() == null ? "" : context.author()))
                .investigation(new EngineeringReportModel.InvestigationSection(
                        investigation.name(),
                        investigation.description(),
                        investigation.status(),
                        EngineeringFormats.range(investigation.rangeStartNanos(), investigation.rangeEndNanos()),
                        EngineeringFormats.duration(investigation.rangeStartNanos(), investigation.rangeEndNanos()),
                        investigation.signalKeyBlobs().size()))
                .health(investigation.health())
                .evidence(investigation.evidence())
                .conclusion(investigation.conclusion())
                .investigationTimes(investigation.createdAt(), investigation.updatedAt());
        if (v2) {
            b.template(EngineeringReportTemplate.ENGINEERING_V2);
            b.timestampBasis(TIMESTAMP_BASIS);
            java.util.ArrayList<EngineeringReportSection> visible = new java.util.ArrayList<>();
            for (EngineeringReportSection section : EngineeringReportSection.values()) {
                if (cfg.shows(section)) {
                    visible.add(section);
                }
            }
            b.sections(visible);
        }
        if ((v2 || cfg.projectSummary()) && context.project() != null) {
            b.project(context.project());
        }
        if (v2 || cfg.sources()) {
            b.sources(filenameSources(context.sources()));
        }
        if (v2 || cfg.signalSummary()) {
            b.signals(context.signals());
        }
        if (!v2 && cfg.analysis()) {
            b.analysis(context.analysis());
        }
        if (!v2 && cfg.diagnostics()) {
            b.diagnostics(context.diagnostics());
        }
        if (!v2 && cfg.frequency()) {
            b.frequency(context.frequency());
        }
        if (v2 || cfg.graphEvidence()) {
            b.figures(context.figures());
        }
        if (v2 || cfg.includeRouteMap()) {
            EngineeringReportModel.RouteMapSection section = context.routeMap();
            if (section != null) {
                b.routeMap(section);
            } else if (hasRouteEvidence(investigation)) {
                b.routeMap(EngineeringReportModel.RouteMapSection.unavailable(null));
            }
        }
        List<String> warns = new ArrayList<>();
        if (v2 || cfg.warnings()) {
            warns.addAll(context.processingWarnings());
            warns.addAll(InvestigationValidity.explanations(investigation.evidence()));
        }
        b.warnings(warns);
        if (!v2 && cfg.appendix()) {
            List<String> app = new ArrayList<>();
            app.add("Investigation id: " + investigation.investigationId());
            app.add("Status: " + investigation.status());
            app.add("Health: " + investigation.health());
            app.add("Layout: " + investigation.layoutMode());
            app.add("Generated without re-parsing measurement files.");
            if (cfg.includeAbsolutePaths()) {
                for (EngineeringReportModel.SourceRow row : context.sources()) {
                    String shown = PathSanitizer.displayName(row.displayName());
                    String sanitized = PathSanitizer.sanitize(row.displayName());
                    if (!sanitized.isBlank() && !sanitized.equals(shown)) {
                        app.add(shown + ": " + sanitized);
                    }
                }
            }
            b.appendix(app);
        }
        List<EngineeringObservation> obs = new ArrayList<>();
        for (EngineeringObservation o : investigation.observations()) {
            obs.add(o);
        }
        b.observations(obs);
        return b.build();
    }

    private static boolean hasRouteEvidence(EngineeringInvestigation investigation) {
        if (investigation == null || investigation.evidence() == null) {
            return false;
        }
        for (var item : investigation.evidence()) {
            if (item.kind() == EvidenceKind.ROUTE_MAP) {
                return true;
            }
        }
        return false;
    }

    private static List<EngineeringReportModel.SourceRow> filenameSources(
            List<EngineeringReportModel.SourceRow> rows
    ) {
        List<EngineeringReportModel.SourceRow> out = new ArrayList<>();
        for (EngineeringReportModel.SourceRow r : rows) {
            out.add(new EngineeringReportModel.SourceRow(
                    PathSanitizer.displayName(r.displayName()),
                    r.fileType(),
                    r.fingerprintSummary(),
                    r.sizeLabel(),
                    r.outcome()));
        }
        return out;
    }

}
