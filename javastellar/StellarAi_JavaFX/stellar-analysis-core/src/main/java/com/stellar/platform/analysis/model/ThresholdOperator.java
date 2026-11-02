package com.stellar.platform.analysis.model;

public enum ThresholdOperator {
    GREATER_THAN,
    GREATER_OR_EQUAL,
    LESS_THAN,
    LESS_OR_EQUAL,
    INSIDE_RANGE,
    OUTSIDE_RANGE;

    public boolean matches(double value, Double threshold, Double high) {
        return switch (this) {
            case GREATER_THAN -> threshold != null && value > threshold;
            case GREATER_OR_EQUAL -> threshold != null && value >= threshold;
            case LESS_THAN -> threshold != null && value < threshold;
            case LESS_OR_EQUAL -> threshold != null && value <= threshold;
            case INSIDE_RANGE -> threshold != null && high != null && value >= threshold && value <= high;
            case OUTSIDE_RANGE -> threshold != null && high != null && (value < threshold || value > high);
        };
    }
}
