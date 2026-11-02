package com.stellar.platform.desktop.diagnostics;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphInvestigationState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Repeatable Phase-7 checkpoint. Distinguishes heap used vs committed and
 * records graph/catalog counters. Never calls {@code System.gc()}.
 */
public record DesktopRuntimeCheckpoint(
        String label,
        JvmMemorySnapshot.Snapshot jvm,
        int catalogSize,
        int workspaceMembership,
        int liveTraceModels,
        int liveViewportResults,
        int hiddenTraces,
        boolean pendingAutosave
) {
    public static DesktopRuntimeCheckpoint capture(String label, DesktopAppService service) {
        GraphInvestigationState inv = service == null ? null : service.graphInvestigation();
        int catalog = service == null ? 0 : service.signalsModel().catalog().entries().size();
        int membership = service == null ? 0 : service.signalsModel().workspaceKeys().size();
        return new DesktopRuntimeCheckpoint(
                label,
                JvmMemorySnapshot.capture(),
                catalog,
                membership,
                inv == null ? 0 : inv.liveTraceModels(),
                inv == null ? 0 : inv.liveViewportResults(),
                inv == null ? 0 : inv.hiddenKeys().size(),
                service != null && service.saveCoordinatorForTest().hasPendingAutosave()
        );
    }

    public Map<String, Long> asMap() {
        Map<String, Long> m = new LinkedHashMap<>(jvm.asMap());
        m.put("catalogSize", (long) catalogSize);
        m.put("workspaceMembership", (long) workspaceMembership);
        m.put("liveTraceModels", (long) liveTraceModels);
        m.put("liveViewportResults", (long) liveViewportResults);
        m.put("hiddenTraces", (long) hiddenTraces);
        m.put("pendingAutosave", pendingAutosave ? 1L : 0L);
        return m;
    }

    @Override
    public String toString() {
        return label + " " + jvm + " catalog=" + catalogSize
                + " membership=" + workspaceMembership
                + " traces=" + liveTraceModels
                + " hidden=" + hiddenTraces;
    }
}
