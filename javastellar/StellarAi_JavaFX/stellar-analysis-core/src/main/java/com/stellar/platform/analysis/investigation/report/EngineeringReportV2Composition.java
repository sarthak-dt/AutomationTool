package com.stellar.platform.analysis.investigation.report;

import com.stellar.platform.analysis.investigation.EngineeringReportSection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides which Engineering Report V2 sections have evidence and the order they are printed.
 * The section enum order stays stable so stored visibility masks do not change.
 */
final class EngineeringReportV2Composition {

    private static final EngineeringReportSection[] DISPLAY = {
            EngineeringReportSection.COVER,
            EngineeringReportSection.EXECUTIVE_SUMMARY,
            EngineeringReportSection.MEASUREMENT_OVERVIEW,
            EngineeringReportSection.ENGINEERING_KPIS,
            EngineeringReportSection.SIGNAL_ANALYSIS,
            EngineeringReportSection.GRAPH_EVIDENCE,
            EngineeringReportSection.GPS_ROUTE,
            EngineeringReportSection.OBSERVATIONS,
            EngineeringReportSection.CONCLUSIONS,
            EngineeringReportSection.ASSUMPTIONS,
            EngineeringReportSection.SOURCE_APPENDIX,
            EngineeringReportSection.SIGNAL_APPENDIX
    };

    private EngineeringReportV2Composition() {
    }

    static List<EngineeringReportSection> visible(EngineeringReportModel model) {
        List<EngineeringReportSection> out = new ArrayList<>();
        for (EngineeringReportSection section : DISPLAY) {
            if (model.shows(section) && present(model, section)) {
                out.add(section);
            }
        }
        return List.copyOf(out);
    }

    static boolean present(EngineeringReportModel model, EngineeringReportSection section) {
        return switch (section) {
            case COVER, ASSUMPTIONS -> true;
            case EXECUTIVE_SUMMARY -> text(model.investigation() == null
                    ? "" : model.investigation().description());
            case MEASUREMENT_OVERVIEW -> text(model.timestampBasis())
                    || !model.sources().isEmpty()
                    || !model.definitions().isEmpty()
                    || (model.investigation() != null && text(model.investigation().timeRange()));
            case SIGNAL_ANALYSIS -> !analysisImages(model).isEmpty();
            case GRAPH_EVIDENCE -> !figures(model).isEmpty();
            case GPS_ROUTE -> true;
            case OBSERVATIONS -> !model.observations().isEmpty();
            case CONCLUSIONS -> text(model.conclusion());
            case SOURCE_APPENDIX -> !model.sources().isEmpty();
            case SIGNAL_APPENDIX -> !model.signals().isEmpty() || !model.definitions().isEmpty();
            case ENGINEERING_KPIS -> !model.kpis().isEmpty();
        };
    }

    static List<SignalAnalysisSection> analysisImages(EngineeringReportModel model) {
        Set<String> figurePaths = new LinkedHashSet<>();
        for (EngineeringReportModel.Figure figure : figures(model)) {
            figurePaths.add(key(figure.assetRelativePath()));
        }
        List<SignalAnalysisSection> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (model.signalAnalysis() != null) {
            for (SignalAnalysisSection section : model.signalAnalysis()) {
                String path = key(section.imageReference());
                if (path.isEmpty() || figurePaths.contains(path) || !seen.add(path)) {
                    continue;
                }
                out.add(section);
            }
        }
        return List.copyOf(out);
    }

    static List<EngineeringReportModel.Figure> figures(EngineeringReportModel model) {
        List<EngineeringReportModel.Figure> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (EngineeringReportModel.Figure figure : model.figures()) {
            String path = key(figure.assetRelativePath());
            String identity = path.isEmpty() ? "missing:" + figure.caption() : path;
            if (identity.equals("missing:") || !seen.add(identity)) {
                continue;
            }
            out.add(figure);
        }
        return List.copyOf(out);
    }

    static boolean hasRoute(EngineeringReportModel model) {
        EngineeringReportModel.RouteMapSection map = model.routeMap();
        return map != null && text(map.imageReference());
    }

    static String dbcLine(EngineeringReportModel model) {
        if (model.definitions().isEmpty()) {
            return "";
        }
        StringBuilder names = new StringBuilder();
        for (EngineeringReportModel.DefinitionRow definition : model.definitions()) {
            if (definition.filename() == null || definition.filename().isBlank()) {
                continue;
            }
            if (!names.isEmpty()) {
                names.append(", ");
            }
            names.append(definition.filename().trim());
        }
        return names.isEmpty() ? "" : "DBC: " + names;
    }

    static String signalCountLine(EngineeringReportModel model) {
        if (model.signals().isEmpty()) {
            return "";
        }
        return "Recorded signals: " + model.signals().size();
    }

    static String processingLine(EngineeringReportModel model) {
        if (model.project() == null || model.project().processingStatus() == null
                || model.project().processingStatus().isBlank()) {
            return "";
        }
        return "Processing: " + model.project().processingStatus().trim();
    }

    private static String key(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        return path.replace('\\', '/').trim();
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }
}
