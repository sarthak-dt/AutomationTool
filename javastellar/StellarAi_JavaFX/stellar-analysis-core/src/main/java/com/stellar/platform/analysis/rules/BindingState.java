package com.stellar.platform.analysis.rules;

public enum BindingState {
    BOUND,
    MISSING,
    AMBIGUOUS,
    INCOMPATIBLE_UNIT,
    STALE
}
