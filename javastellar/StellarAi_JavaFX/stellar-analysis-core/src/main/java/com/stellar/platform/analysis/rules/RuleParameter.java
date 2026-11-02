package com.stellar.platform.analysis.rules;

import java.util.Objects;

public record RuleParameter(
        String name,
        String type,
        String unit,
        double defaultValue,
        Double min,
        Double max,
        String description
) {
    public RuleParameter {
        name = Objects.requireNonNull(name, "name").trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("parameter name required");
        }
        type = type == null || type.isBlank() ? "NUMBER" : type.trim();
        unit = unit == null ? "" : unit;
        description = description == null ? "" : description;
    }

    public double validate(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("parameter " + name + " must be finite");
        }
        if (min != null && value < min) {
            throw new IllegalArgumentException("parameter " + name + " below min " + min);
        }
        if (max != null && value > max) {
            throw new IllegalArgumentException("parameter " + name + " above max " + max);
        }
        return value;
    }
}
