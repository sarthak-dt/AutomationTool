package com.stellar.platform.desktop.presentation;

/**
 * Presentation-only coverage quality classification.
 * Separate from mapping workflow state ({@code RECOMMENDED}/{@code CONFIRMED}/…).
 * <p>
 * Default thresholds (ratio 0..1):
 * <ul>
 *   <li>{@link CoverageQuality#NONE} — no overlap ({@code <= 0})</li>
 *   <li>{@link CoverageQuality#LOW} — {@code < 0.25}</li>
 *   <li>{@link CoverageQuality#MEDIUM} — {@code < 0.70}</li>
 *   <li>{@link CoverageQuality#HIGH} — {@code >= 0.70}</li>
 * </ul>
 * Thresholds are constructor-configurable; do not duplicate them in UI cells.
 */
public final class CoverageClassificationPolicy {

    public enum CoverageQuality {
        NONE,
        LOW,
        MEDIUM,
        HIGH;

        public String badgeLabel() {
            return switch (this) {
                case NONE -> "NO COVERAGE";
                case LOW -> "LOW COVERAGE";
                case MEDIUM -> "MEDIUM COVERAGE";
                case HIGH -> "HIGH COVERAGE";
            };
        }

        public String styleClass() {
            return switch (this) {
                case NONE -> "coverage-none";
                case LOW -> "coverage-low";
                case MEDIUM -> "coverage-medium";
                case HIGH -> "coverage-high";
            };
        }
    }

    public static final double DEFAULT_LOW_EXCLUSIVE_MAX = 0.25;
    public static final double DEFAULT_MEDIUM_EXCLUSIVE_MAX = 0.70;

    private final double lowExclusiveMax;
    private final double mediumExclusiveMax;

    public CoverageClassificationPolicy() {
        this(DEFAULT_LOW_EXCLUSIVE_MAX, DEFAULT_MEDIUM_EXCLUSIVE_MAX);
    }

    public CoverageClassificationPolicy(double lowExclusiveMax, double mediumExclusiveMax) {
        if (lowExclusiveMax < 0.0 || mediumExclusiveMax <= lowExclusiveMax || mediumExclusiveMax > 1.0) {
            throw new IllegalArgumentException(
                    "Invalid coverage thresholds: lowExclusiveMax=" + lowExclusiveMax
                            + " mediumExclusiveMax=" + mediumExclusiveMax);
        }
        this.lowExclusiveMax = lowExclusiveMax;
        this.mediumExclusiveMax = mediumExclusiveMax;
    }

    public static CoverageClassificationPolicy defaults() {
        return new CoverageClassificationPolicy();
    }

    public CoverageQuality classify(double coverageRatio) {
        if (coverageRatio <= 0.0 || Double.isNaN(coverageRatio)) {
            return CoverageQuality.NONE;
        }
        if (coverageRatio < lowExclusiveMax) {
            return CoverageQuality.LOW;
        }
        if (coverageRatio < mediumExclusiveMax) {
            return CoverageQuality.MEDIUM;
        }
        return CoverageQuality.HIGH;
    }

    public double lowExclusiveMax() {
        return lowExclusiveMax;
    }

    public double mediumExclusiveMax() {
        return mediumExclusiveMax;
    }
}
