package com.stellar.platform.analysis.workflow;

/** Presentation readiness. Does not execute the rule. */
public enum RuleReadiness {
    READY,
    BINDING_REQUIRED,
    MISSING_INPUT,
    INVALID_CONFIGURATION,
    INCOMPATIBLE_UNIT,
    DISABLED
}
