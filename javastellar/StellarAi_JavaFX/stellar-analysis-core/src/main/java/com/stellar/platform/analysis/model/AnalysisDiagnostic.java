package com.stellar.platform.analysis.model;

public record AnalysisDiagnostic(String code, String message) {
    public AnalysisDiagnostic {
        code = code == null ? "" : code;
        message = message == null ? "" : message;
    }
}
