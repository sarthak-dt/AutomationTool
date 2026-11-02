package com.stellar.platform.parser.mf4.dynamicschema;

import java.util.List;
import java.util.Objects;

/**
 * Classification of a channel as a Canonical CAN Frame field candidate.
 */
public record MdfCanRoleCandidate(
        MdfCanRole role,
        double confidence,
        List<String> evidence
) {

    public MdfCanRoleCandidate {
        Objects.requireNonNull(role, "role");
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in 0..1");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    }

    public static MdfCanRoleCandidate none() {
        return new MdfCanRoleCandidate(MdfCanRole.NONE, 0.0, List.of());
    }
}
