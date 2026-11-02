package com.stellar.platform.analysis.rules;

public record RuleValidationIssue(String code, String message) {
    public RuleValidationIssue {
        code = code == null ? "INVALID" : code;
        message = message == null ? "" : message;
    }
}
