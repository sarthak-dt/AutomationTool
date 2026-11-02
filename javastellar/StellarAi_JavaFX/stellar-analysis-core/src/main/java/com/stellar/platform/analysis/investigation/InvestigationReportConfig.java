package com.stellar.platform.analysis.investigation;

/**
 * Section flags for the classic investigation report, plus the Engineering Report V2 template.
 * Blobs written before the template suffix still decode as the classic report.
 */
public record InvestigationReportConfig(
        boolean projectSummary,
        boolean sources,
        boolean signalSummary,
        boolean analysis,
        boolean diagnostics,
        boolean frequency,
        boolean graphEvidence,
        boolean warnings,
        boolean appendix,
        boolean includeAbsolutePaths,
        boolean includeRouteMap,
        EngineeringReportTemplate template,
        int sectionMask,
        String kpiConfig
) {
    public InvestigationReportConfig {
        template = template == null ? EngineeringReportTemplate.CLASSIC : template;
        if (sectionMask < 0) {
            sectionMask = EngineeringReportSection.allMask();
        }
        kpiConfig = kpiConfig == null ? "" : kpiConfig;
    }

    /** Existing callers keep route-map inclusion on and stay on the classic template. */
    public InvestigationReportConfig(
            boolean projectSummary,
            boolean sources,
            boolean signalSummary,
            boolean analysis,
            boolean diagnostics,
            boolean frequency,
            boolean graphEvidence,
            boolean warnings,
            boolean appendix,
            boolean includeAbsolutePaths
    ) {
        this(projectSummary, sources, signalSummary, analysis, diagnostics, frequency,
                graphEvidence, warnings, appendix, includeAbsolutePaths, true,
                EngineeringReportTemplate.CLASSIC, EngineeringReportSection.allMask(), "");
    }

    public InvestigationReportConfig(
            boolean projectSummary,
            boolean sources,
            boolean signalSummary,
            boolean analysis,
            boolean diagnostics,
            boolean frequency,
            boolean graphEvidence,
            boolean warnings,
            boolean appendix,
            boolean includeAbsolutePaths,
            boolean includeRouteMap
    ) {
        this(projectSummary, sources, signalSummary, analysis, diagnostics, frequency,
                graphEvidence, warnings, appendix, includeAbsolutePaths, includeRouteMap,
                EngineeringReportTemplate.CLASSIC, EngineeringReportSection.allMask(), "");
    }

    public static InvestigationReportConfig allRelevant() {
        return new InvestigationReportConfig(true, true, true, true, true, true, true, true, true, false, true);
    }

    public static InvestigationReportConfig engineeringV2() {
        return allRelevant().withTemplate(EngineeringReportTemplate.ENGINEERING_V2, EngineeringReportSection.allMask());
    }

    public InvestigationReportConfig withTemplate(EngineeringReportTemplate next, int mask) {
        return new InvestigationReportConfig(
                projectSummary, sources, signalSummary, analysis, diagnostics, frequency,
                graphEvidence, warnings, appendix, includeAbsolutePaths, includeRouteMap,
                next, mask, kpiConfig);
    }

    public InvestigationReportConfig withKpiConfig(String config) {
        return new InvestigationReportConfig(
                projectSummary, sources, signalSummary, analysis, diagnostics, frequency,
                graphEvidence, warnings, appendix, includeAbsolutePaths, includeRouteMap,
                template, sectionMask, config);
    }

    public InvestigationReportConfig withIncludeAbsolutePaths(boolean include) {
        return new InvestigationReportConfig(
                projectSummary, sources, signalSummary, analysis, diagnostics, frequency,
                graphEvidence, warnings, appendix, include, includeRouteMap, template, sectionMask,
                kpiConfig);
    }

    public boolean shows(EngineeringReportSection section) {
        if (template != EngineeringReportTemplate.ENGINEERING_V2 || section == null) {
            return false;
        }
        return (sectionMask & (1 << section.ordinal())) != 0;
    }

    /** Compact persistence blob. Not a public file format. */
    public String encode() {
        String templateMark = template == EngineeringReportTemplate.ENGINEERING_V2 ? "V" : "C";
        return flag(projectSummary) + flag(sources) + flag(signalSummary) + flag(analysis)
                + flag(diagnostics) + flag(frequency) + flag(graphEvidence) + flag(warnings)
                + flag(appendix) + flag(includeAbsolutePaths) + flag(includeRouteMap)
                + templateMark + String.format("%03X", sectionMask & EngineeringReportSection.allMask())
                + (kpiConfig.isBlank() ? "" : "|" + kpiConfig);
    }

    public static InvestigationReportConfig decode(String blob) {
        if (blob == null || blob.length() < 10) {
            return allRelevant();
        }
        boolean routeMap = blob.length() < 11 || on(blob, 10);
        EngineeringReportTemplate template = EngineeringReportTemplate.CLASSIC;
        int mask = EngineeringReportSection.allMask();
        if (blob.length() >= 12) {
            char mark = blob.charAt(11);
            if (mark == 'V') {
                template = EngineeringReportTemplate.ENGINEERING_V2;
            }
            if (blob.length() >= 15) {
                try {
                    mask = Integer.parseInt(blob.substring(12, 15), 16);
                } catch (NumberFormatException ex) {
                    mask = EngineeringReportSection.allMask();
                }
            }
        }
        String kpi = blob.length() > 15 && blob.charAt(15) == '|' ? blob.substring(16) : "";
        return new InvestigationReportConfig(
                on(blob, 0), on(blob, 1), on(blob, 2), on(blob, 3), on(blob, 4),
                on(blob, 5), on(blob, 6), on(blob, 7), on(blob, 8), on(blob, 9), routeMap,
                template, mask, kpi);
    }

    private static String flag(boolean v) {
        return v ? "1" : "0";
    }

    private static boolean on(String blob, int i) {
        return i < blob.length() && blob.charAt(i) == '1';
    }
}
