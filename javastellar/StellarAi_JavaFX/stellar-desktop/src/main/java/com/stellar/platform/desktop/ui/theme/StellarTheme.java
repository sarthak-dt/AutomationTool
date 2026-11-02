package com.stellar.platform.desktop.ui.theme;

import javafx.scene.paint.Color;

/**
 * Desktop-only chrome colors and type stacks that match {@code stellar-tokens.css}.
 * Signal-series and map-route palettes stay elsewhere.
 */
public final class StellarTheme {

    public static final Color BACKGROUND = Color.web("#F5F6F8");
    public static final Color SURFACE = Color.web("#FFFFFF");
    public static final Color SURFACE_SUBTLE = Color.web("#F0F3F7");
    public static final Color BORDER = Color.web("#DDE1E9");
    public static final Color BORDER_STRONG = Color.web("#C8CDD8");
    public static final Color TEXT = Color.web("#0F172A");
    public static final Color TEXT_SECONDARY = Color.web("#334155");
    public static final Color TEXT_MUTED = Color.web("#475569");
    public static final Color ACCENT = Color.web("#0F6CBD");
    public static final Color ACCENT_HOVER = Color.web("#0C5AA3");
    public static final Color ACCENT_SOFT = Color.web("#E8F0FB");
    public static final Color MEASUREMENT = Color.web("#0891B2");
    public static final Color DEFINITION = Color.web("#7C3AED");
    public static final Color SUCCESS = Color.web("#059669");
    public static final Color SUCCESS_BG = Color.web("#DCFCE7");
    public static final Color WARNING = Color.web("#B25A00");
    public static final Color WARNING_BG = Color.web("#FFF0DF");
    public static final Color DANGER = Color.web("#A12622");
    public static final Color DANGER_BG = Color.web("#FEE2E2");
    public static final Color INFO = Color.web("#0F6CBD");
    public static final Color INFO_BG = Color.web("#F0F7FF");

    /** Exact current nav-icon stroke; not remapped to {@link #TEXT} to keep the sidebar unchanged. */
    public static final Color ICON_INK = Color.web("#1B2A38");
    /** Exact current default icon stroke used by {@code StellarIcons}. */
    public static final Color ICON_MUTED = Color.web("#5E7184");
    /** Exact current dark icon stroke used on light toolbar controls. */
    public static final Color ICON_DARK = Color.web("#142A3A");

    public static final String FONT_UI = "\"Segoe UI\", \"Helvetica Neue\", Arial, sans-serif";
    public static final String FONT_UI_FAMILY = "Segoe UI";
    public static final String FONT_MONO = "\"Consolas\", \"Courier New\", monospace";

    private StellarTheme() {
    }

    public static String css(Color color) {
        int r = (int) Math.round(color.getRed() * 255);
        int g = (int) Math.round(color.getGreen() * 255);
        int b = (int) Math.round(color.getBlue() * 255);
        return String.format("#%02X%02X%02X", r, g, b);
    }
}
