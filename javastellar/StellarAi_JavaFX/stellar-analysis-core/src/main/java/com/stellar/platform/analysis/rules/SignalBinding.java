package com.stellar.platform.analysis.rules;

import com.stellar.platform.domain.catalog.SignalCatalogKey;

public record SignalBinding(
        String role,
        BindingState state,
        SignalCatalogKey key,
        String signalUnit,
        String reason
) {
    public SignalBinding {
        role = role == null ? "" : role;
        state = state == null ? BindingState.MISSING : state;
        signalUnit = signalUnit == null ? "" : signalUnit;
        reason = reason == null ? "" : reason;
    }

    public boolean bound() {
        return state == BindingState.BOUND && key != null;
    }

    public static SignalBinding missing(String role) {
        return new SignalBinding(role, BindingState.MISSING, null, "", "Missing required input: " + role);
    }

    public static SignalBinding ambiguous(String role, int count) {
        return new SignalBinding(role, BindingState.AMBIGUOUS, null, "",
                "Ambiguous input — binding required: " + role + " (" + count + " candidates)");
    }
}
