package com.stellar.platform.analysis.diagnostics;

import com.stellar.platform.analysis.model.TimeRangeSource;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.rules.DiagnosticRulePack;
import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record DiagnosticRunRequest(
        String projectId,
        String runId,
        DiagnosticRulePack pack,
        List<String> ruleIds,
        TimeRangeSource rangeSource,
        TimeWindow window,
        Map<String, SignalCatalogKey> explicitBindings,
        Map<String, Double> parameterOverrides,
        long artifactGeneration,
        String sourceFingerprint
) {
    public DiagnosticRunRequest {
        projectId = projectId == null ? "" : projectId;
        runId = runId == null || runId.isBlank() ? UUID.randomUUID().toString() : runId;
        pack = Objects.requireNonNull(pack, "pack");
        ruleIds = List.copyOf(ruleIds == null ? List.of() : ruleIds);
        rangeSource = rangeSource == null ? TimeRangeSource.FULL : rangeSource;
        window = window == null ? TimeWindow.unbounded() : window;
        explicitBindings = Map.copyOf(explicitBindings == null ? Map.of() : explicitBindings);
        parameterOverrides = Map.copyOf(parameterOverrides == null ? Map.of() : parameterOverrides);
        sourceFingerprint = sourceFingerprint == null ? "" : sourceFingerprint;
    }

    public DiagnosticRunRequest(
            String projectId,
            String runId,
            DiagnosticRulePack pack,
            List<String> ruleIds,
            TimeRangeSource rangeSource,
            TimeWindow window,
            Map<String, SignalCatalogKey> explicitBindings,
            long artifactGeneration,
            String sourceFingerprint
    ) {
        this(projectId, runId, pack, ruleIds, rangeSource, window, explicitBindings, Map.of(),
                artifactGeneration, sourceFingerprint);
    }
}
