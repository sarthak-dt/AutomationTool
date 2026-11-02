package com.stellar.platform.desktop.ui.graph;

import javafx.scene.paint.Color;

import java.util.List;

/**
 * Data-visualization series colors for waveforms and the signal selector.
 * This is not the application UI token set — do not reuse these as button or badge colors.
 * Indexes 0–7 are the original palette and stay stable for saved investigations.
 * The track title is the identity. Colour and, after the palette repeats, the line pattern support it.
 */
public final class GraphSeriesPalette {
    private static final List<Color> COLORS = List.of(
            Color.web("#0f8a96"), Color.web("#2563eb"), Color.web("#7c3aed"),
            Color.web("#c2410c"), Color.web("#15803d"), Color.web("#be123c"),
            Color.web("#0369a1"), Color.web("#a16207"),
            Color.web("#1e293b"), Color.web("#f781bf"), Color.web("#80cdc1"),
            Color.web("#762a83"), Color.web("#a6d854"), Color.web("#e6ab02"),
            Color.web("#fc8d62"), Color.web("#66a61e"), Color.web("#e7298a"),
            Color.web("#7570b3"), Color.web("#44403c"), Color.web("#e5c494"),
            Color.web("#cab2d6"), Color.web("#56b4e9"), Color.web("#7f1d1d"),
            Color.web("#b2df8a"));

    private GraphSeriesPalette() { }

    public static int size() {
        return COLORS.size();
    }

    public static Color at(int index) {
        return COLORS.get(Math.floorMod(index, COLORS.size()));
    }

    /**
     * Solid for the first pass through the palette. Later passes reuse a colour with a different dash
     * so two signals are not identified by colour alone. {@code null} means a solid stroke.
     */
    public static double[] dashesAt(int index) {
        int cycle = Math.floorDiv(Math.floorMod(index, COLORS.size() * 4), COLORS.size());
        return switch (cycle) {
            case 1 -> new double[] {10, 6};
            case 2 -> new double[] {2, 4};
            case 3 -> new double[] {10, 4, 2, 4};
            default -> null;
        };
    }

    public static String cssAt(int index) { return toCss(at(index)); }
    public static String toCss(Color c) {
        return String.format("#%02x%02x%02x", (int)Math.round(c.getRed()*255), (int)Math.round(c.getGreen()*255), (int)Math.round(c.getBlue()*255));
    }
}
