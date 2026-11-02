package com.stellar.platform.analysis.investigation;

/** Fixed section order for Engineering Report V2. Ordinal is the visibility bit. */
public enum EngineeringReportSection {
    COVER("Cover Page"),
    EXECUTIVE_SUMMARY("Executive Summary"),
    MEASUREMENT_OVERVIEW("Measurement and Session Overview"),
    SIGNAL_ANALYSIS("Signal Analysis"),
    GRAPH_EVIDENCE("Graph Evidence"),
    GPS_ROUTE("GPS Route Evidence"),
    OBSERVATIONS("Observations and Findings"),
    CONCLUSIONS("Engineering Conclusions"),
    ASSUMPTIONS("Assumptions and Limitations"),
    SOURCE_APPENDIX("Source File Appendix"),
    SIGNAL_APPENDIX("Signal / DBC Appendix"),
    ENGINEERING_KPIS("Engineering KPIs");

    private final String title;

    EngineeringReportSection(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }

    public static int allMask() {
        return (1 << values().length) - 1;
    }
}
