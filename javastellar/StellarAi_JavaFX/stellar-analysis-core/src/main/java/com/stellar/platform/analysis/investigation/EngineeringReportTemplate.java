package com.stellar.platform.analysis.investigation;

/** Report layout. Classic keeps the existing investigation report. */
public enum EngineeringReportTemplate {
    CLASSIC("Investigation report", "1"),
    ENGINEERING_V2("Engineering Report V2", "2.0");

    private final String displayName;
    private final String version;

    EngineeringReportTemplate(String displayName, String version) {
        this.displayName = displayName;
        this.version = version;
    }

    public String displayName() {
        return displayName;
    }

    public String version() {
        return version;
    }
}
