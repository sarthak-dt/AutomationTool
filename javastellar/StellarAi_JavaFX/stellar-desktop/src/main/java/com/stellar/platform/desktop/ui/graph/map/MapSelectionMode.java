package com.stellar.platform.desktop.ui.graph.map;

/** The three map interaction modes offered on the Map Route tab. */
public enum MapSelectionMode {
    SINGLE_POINT("Single Point"),
    A_B_RANGE("A → B Range"),
    ALL_ROUTE("All Route");

    private final String label;

    MapSelectionMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
