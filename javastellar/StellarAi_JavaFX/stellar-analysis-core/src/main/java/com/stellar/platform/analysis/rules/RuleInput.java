package com.stellar.platform.analysis.rules;

import java.util.List;
import java.util.Objects;

/** Logical role. Display names are aliases only, never identity. */
public record RuleInput(
        String role,
        List<String> aliases,
        String expectedUnit,
        boolean required
) {
    public RuleInput {
        role = Objects.requireNonNull(role, "role").trim();
        if (role.isEmpty()) {
            throw new IllegalArgumentException("role required");
        }
        aliases = List.copyOf(aliases == null ? List.of() : aliases);
        expectedUnit = expectedUnit == null ? "" : expectedUnit;
    }

    public static RuleInput required(String role, String unit, String... aliases) {
        return new RuleInput(role, List.of(aliases), unit, true);
    }
}
