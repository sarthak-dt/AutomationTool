package com.stellar.platform.domain.inventory;

import java.util.Objects;

/** One inventory diagnostic entry. */
public record CanInventoryDiagnostic(CanInventoryDiagnosticCode code, String message) {
    public CanInventoryDiagnostic {
        Objects.requireNonNull(code, "code");
        message = message == null ? "" : message;
    }
}
