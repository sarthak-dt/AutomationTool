package com.stellar.platform.domain.processing.plan;

import java.util.Objects;
import java.util.Optional;

/** Structured plan diagnostic; not a UI string owner. */
public final class ProcessingPlanDiagnostic {

    private final ProcessingPlanDiagnosticCode code;
    private final SourceChannelRef channel;
    private final String detail;

    public ProcessingPlanDiagnostic(
            ProcessingPlanDiagnosticCode code,
            SourceChannelRef channel,
            String detail
    ) {
        this.code = Objects.requireNonNull(code, "code");
        this.channel = channel;
        this.detail = detail == null ? "" : detail;
    }

    public static ProcessingPlanDiagnostic of(ProcessingPlanDiagnosticCode code) {
        return new ProcessingPlanDiagnostic(code, null, "");
    }

    public static ProcessingPlanDiagnostic of(
            ProcessingPlanDiagnosticCode code,
            SourceChannelRef channel,
            String detail
    ) {
        return new ProcessingPlanDiagnostic(code, channel, detail);
    }

    public ProcessingPlanDiagnosticCode code() {
        return code;
    }

    public Optional<SourceChannelRef> channel() {
        return Optional.ofNullable(channel);
    }

    public String detail() {
        return detail;
    }
}
