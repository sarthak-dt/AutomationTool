package com.stellar.platform.analysis.workflow;

public final class StaleRunReason {

    public static final String MESSAGE =
            "STALE: Source or processing artifact changed. Run diagnostics again for current data.";

    private StaleRunReason() {
    }

    public static boolean staleStatus(String status) {
        return "INVALIDATED".equals(status) || (status != null && status.startsWith("STALE"));
    }

    public static boolean staleMessage(String message) {
        return message != null && message.startsWith("STALE");
    }
}
