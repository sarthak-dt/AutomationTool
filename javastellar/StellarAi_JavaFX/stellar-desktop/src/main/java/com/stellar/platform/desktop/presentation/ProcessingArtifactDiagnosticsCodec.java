package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnostic;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnosticCode;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compact line codec for persisting {@link ProcessingPlanDiagnostic} lists on artifact rows.
 * Format per line: {@code CODE|sourceId|channelId|detail} (detail may contain '|').
 */
final class ProcessingArtifactDiagnosticsCodec {

    private ProcessingArtifactDiagnosticsCodec() {
    }

    static String encode(List<ProcessingPlanDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ProcessingPlanDiagnostic diagnostic : diagnostics) {
            if (diagnostic == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            String sourceId = diagnostic.channel()
                    .map(SourceChannelRef::projectSourceId)
                    .orElse("");
            int channelId = diagnostic.channel().map(SourceChannelRef::channelId).orElse(-1);
            sb.append(diagnostic.code().name())
                    .append('|')
                    .append(sourceId)
                    .append('|')
                    .append(channelId)
                    .append('|')
                    .append(sanitize(diagnostic.detail()));
        }
        return sb.toString();
    }

    static List<ProcessingPlanDiagnostic> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        List<ProcessingPlanDiagnostic> out = new ArrayList<>();
        for (String line : encoded.split("\n")) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|", 4);
            if (parts.length < 1) {
                continue;
            }
            ProcessingPlanDiagnosticCode code;
            try {
                code = ProcessingPlanDiagnosticCode.valueOf(parts[0].trim());
            } catch (RuntimeException ex) {
                code = ProcessingPlanDiagnosticCode.PIPELINE_DIAGNOSTIC;
            }
            String sourceId = parts.length > 1 ? parts[1].trim() : "";
            int channelId = -1;
            if (parts.length > 2) {
                try {
                    channelId = Integer.parseInt(parts[2].trim());
                } catch (NumberFormatException ignored) {
                    channelId = -1;
                }
            }
            String detail = parts.length > 3 ? parts[3] : "";
            SourceChannelRef channel = null;
            if (!sourceId.isBlank() && channelId >= 0) {
                try {
                    channel = SourceChannelRef.of(sourceId, channelId);
                } catch (RuntimeException ignored) {
                    channel = null;
                }
            }
            out.add(ProcessingPlanDiagnostic.of(code, channel, detail));
        }
        return List.copyOf(out);
    }

    static JobExecutionStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return JobExecutionStatus.SUCCESS;
        }
        try {
            return JobExecutionStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            return JobExecutionStatus.SUCCESS;
        }
    }

    private static String sanitize(String detail) {
        if (detail == null) {
            return "";
        }
        return detail.replace('\n', ' ').replace('\r', ' ');
    }
}
