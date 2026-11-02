package com.stellar.platform.desktop.project;

/**
 * Classification of files that can appear in a working project.
 */
public enum FileKind {
    MEASUREMENT_MF4("MF4", true),
    MEASUREMENT_ASC("ASC", true),
    MEASUREMENT_BLF("BLF", true),
    MEASUREMENT_DAT("DAT", true),
    MEASUREMENT_LOG("LOG", true),
    DEFINITION_DBC("DBC", true),
    DEFINITION_CSV("CSV", false),
    DEFINITION_A2L("A2L", false),
    DEFINITION_ODX("ODX", false),
    DEFINITION_PDX("PDX", false),
    UNKNOWN("Unknown", false);

    private final String displayName;
    private final boolean implemented;

    FileKind(String displayName, boolean implemented) {
        this.displayName = displayName;
        this.implemented = implemented;
    }

    public String displayName() {
        return displayName;
    }

    public boolean implemented() {
        return implemented;
    }

    public boolean measurement() {
        return name().startsWith("MEASUREMENT_");
    }

    public boolean definition() {
        return name().startsWith("DEFINITION_");
    }
}
