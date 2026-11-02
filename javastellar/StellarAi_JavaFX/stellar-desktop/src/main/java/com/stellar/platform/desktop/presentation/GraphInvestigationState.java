package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Logical reconstructable graph investigation. Never holds Canvas, LOD arrays, futures or listeners.
 *
 * <p>Four independent signal states (do not conflate):
 * <ul>
 *   <li><b>AVAILABLE</b> — catalog entry in {@link SignalsScreenModel}</li>
 *   <li><b>SELECTED</b> — analysis membership in {@link SignalsScreenModel#workspaceKeys()}</li>
 *   <li><b>VISIBLE</b> — selected and not in {@link #hidden}; drives query/render via {@link #visibleKeys}</li>
 *   <li><b>FOCUSED</b> — {@link #focusKey} for Inspector / context only; never filters drawing</li>
 * </ul>
 * This object owns graph-only attributes of membership: hide, colour, layout, viewport, cursors,
 * notes, and inspector focus. Sample payloads stay in the SignalSeriesStore / GraphQueryProvider.</p>
 */
public final class GraphInvestigationState {

    public static final String STACKED = "STACKED";
    public static final String OVERLAY = "OVERLAY";

    private final AtomicInteger sessionGeneration = new AtomicInteger();
    private final AtomicInteger liveViewportResults = new AtomicInteger();
    private final AtomicInteger liveTraceModels = new AtomicInteger();
    private final LinkedHashSet<SignalCatalogKey> hidden = new LinkedHashSet<>();
    private final LinkedHashMap<SignalCatalogKey, Integer> colors = new LinkedHashMap<>();
    private final List<SignalCatalogKey> unavailable = new ArrayList<>();
    private volatile String layoutMode = STACKED;
    private volatile long viewportStart;
    private volatile long viewportEnd;
    private volatile Long defaultStart;
    private volatile Long defaultEnd;
    private volatile Long cursorA;
    private volatile Long cursorB;
    private volatile String notes = "";
    private volatile SignalCatalogKey focusKey;
    private volatile boolean viewportInitialized;
    /** GIV2. Empty means a GIV1 restore, which must not be read as UTC. */
    private volatile String timelineGroup = "";
    private volatile String fileFocusMeasurementId = "";
    private volatile String viewportDomain = "";
    private int nextColor;
    private volatile int analysisTab;
    private volatile boolean inspectorVisible = true;
    private volatile boolean plotSettingsVisible;
    private volatile String signalsDisplayMode = "";
    private volatile String signalsPrecision = "";

    /** New project / project switch: stale async results from the previous session must die. */
    public int beginSession() {
        hidden.clear();
        colors.clear();
        unavailable.clear();
        layoutMode = STACKED;
        viewportStart = 0;
        viewportEnd = 0;
        defaultStart = null;
        defaultEnd = null;
        cursorA = null;
        cursorB = null;
        notes = "";
        focusKey = null;
        viewportInitialized = false;
        timelineGroup = "";
        fileFocusMeasurementId = "";
        viewportDomain = "";
        nextColor = 0;
        analysisTab = 0;
        inspectorVisible = true;
        plotSettingsVisible = false;
        signalsDisplayMode = "";
        signalsPrecision = "";
        liveViewportResults.set(0);
        liveTraceModels.set(0);
        return sessionGeneration.incrementAndGet();
    }

    public int sessionGeneration() {
        return sessionGeneration.get();
    }

    public boolean hide(SignalCatalogKey key) {
        return key != null && hidden.add(key);
    }

    public boolean show(SignalCatalogKey key) {
        return key != null && hidden.remove(key);
    }

    /**
     * Legend hide/show applies to the current Graph view. It does not change analysis membership.
     */
    public void clearTemporaryVisibility() {
        hidden.clear();
    }

    public boolean isHidden(SignalCatalogKey key) {
        return key != null && hidden.contains(key);
    }

    public Set<SignalCatalogKey> hiddenKeys() {
        return Set.copyOf(hidden);
    }

    /**
     * Inspector / context focus only. Changing focus must never remove selection, hide traces,
     * or shrink the render set — see {@link #visibleKeys(List)}.
     */
    public void setFocus(SignalCatalogKey key) {
        focusKey = key;
    }

    public SignalCatalogKey focusKey() {
        return focusKey;
    }

    public void clearFocus() {
        focusKey = null;
    }

    /**
     * Keys that should be queried and drawn: selected membership minus hidden.
     * Focused signal is intentionally <em>not</em> used as a filter — many signals may be
     * SELECTED+VISIBLE while exactly one is FOCUSED for the Inspector.
     */
    public List<SignalCatalogKey> visibleKeys(List<SignalCatalogKey> membership) {
        if (membership == null || membership.isEmpty()) {
            return List.of();
        }
        List<SignalCatalogKey> out = new ArrayList<>(membership.size());
        for (SignalCatalogKey key : membership) {
            if (key == null || hidden.contains(key)) {
                continue;
            }
            out.add(key);
        }
        return List.copyOf(out);
    }

    public int colorIndex(SignalCatalogKey key) {
        if (key == null) {
            return 0;
        }
        Integer existing = colors.get(key);
        if (existing != null) {
            return existing;
        }
        int assigned = nextColor++;
        colors.put(key, assigned);
        return assigned;
    }

    public Map<SignalCatalogKey, Integer> colorIndexes() {
        return Map.copyOf(colors);
    }

    public void forgetTrace(SignalCatalogKey key) {
        if (key == null) {
            return;
        }
        hidden.remove(key);
        colors.remove(key);
        if (Objects.equals(focusKey, key)) {
            focusKey = null;
        }
        unavailable.remove(key);
    }

    /** Drop graph-only attributes for a measurement that left the project. */
    public void forgetSource(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return;
        }
        hidden.removeIf(key -> sourceId.equals(key.sourceFile()));
        colors.entrySet().removeIf(entry -> sourceId.equals(entry.getKey().sourceFile()));
        unavailable.removeIf(key -> sourceId.equals(key.sourceFile()));
        if (focusKey != null && sourceId.equals(focusKey.sourceFile())) {
            focusKey = null;
        }
    }

    /** Clear View: drop graph-only attributes. Catalog / workspace membership is the caller's job. */
    public void clearAnalysis() {
        hidden.clear();
        colors.clear();
        unavailable.clear();
        focusKey = null;
        cursorA = null;
        cursorB = null;
        nextColor = 0;
        liveViewportResults.set(0);
        liveTraceModels.set(0);
    }

    public String layoutMode() {
        return layoutMode;
    }

    public void setLayoutMode(String mode) {
        layoutMode = OVERLAY.equals(mode) ? OVERLAY : STACKED;
    }

    public boolean stacked() {
        return !OVERLAY.equals(layoutMode);
    }

    public void setTimelineContext(String group, String fileFocusId, String domain) {
        timelineGroup = group == null ? "" : group;
        fileFocusMeasurementId = fileFocusId == null ? "" : fileFocusId;
        viewportDomain = domain == null ? "" : domain;
    }

    public String timelineGroup() {
        return timelineGroup;
    }

    public String fileFocusMeasurementId() {
        return fileFocusMeasurementId;
    }

    public String viewportDomain() {
        return viewportDomain;
    }

    public long viewportStart() {
        return viewportStart;
    }

    public long viewportEnd() {
        return viewportEnd;
    }

    public boolean viewportInitialized() {
        return viewportInitialized && viewportEnd > viewportStart;
    }

    public void setViewport(long start, long end) {
        if (!GraphSharedViewport.isStructurallyValid(start, end)) {
            return;
        }
        this.viewportStart = start;
        this.viewportEnd = end;
        this.viewportInitialized = true;
        if (defaultStart == null || defaultEnd == null) {
            defaultStart = start;
            defaultEnd = end;
        }
    }

    public void captureDefaultViewport(long start, long end) {
        if (!GraphSharedViewport.isStructurallyValid(start, end)) {
            return;
        }
        defaultStart = start;
        defaultEnd = end;
        if (!viewportInitialized) {
            setViewport(start, end);
        }
    }

    /**
     * Records the selected-visible full analysis range as the Reset View target.
     * Does not change the current viewport.
     */
    public void captureFullAnalysisRange(long fullStart, long fullEnd) {
        if (!GraphSharedViewport.isStructurallyValid(fullStart, fullEnd)
                && fullEnd != fullStart) {
            return;
        }
        if (fullEnd == fullStart) {
            long[] pad = GraphSharedViewport.singleTimestampDisplayRange(fullStart);
            defaultStart = pad[0];
            defaultEnd = pad[1];
            return;
        }
        defaultStart = fullStart;
        defaultEnd = fullEnd;
    }

    /** Reset View: restore the investigation's default (full) viewport. Does not touch membership. */
    public boolean resetView() {
        if (defaultStart == null || defaultEnd == null
                || !GraphSharedViewport.isStructurallyValid(defaultStart, defaultEnd)) {
            return false;
        }
        viewportStart = defaultStart;
        viewportEnd = defaultEnd;
        viewportInitialized = true;
        return true;
    }

    public Long cursorA() {
        return cursorA;
    }

    public Long cursorB() {
        return cursorB;
    }

    public void setCursorA(Long value) {
        cursorA = value;
    }

    public void setCursorB(Long value) {
        cursorB = value;
    }

    public void clearCursors() {
        cursorA = null;
        cursorB = null;
    }

    public String notes() {
        return notes == null ? "" : notes;
    }

    public void setNotes(String value) {
        notes = value == null ? "" : value;
    }

    public List<SignalCatalogKey> unavailableTraces() {
        return List.copyOf(unavailable);
    }

    public void setUnavailableTraces(List<SignalCatalogKey> keys) {
        unavailable.clear();
        if (keys != null) {
            unavailable.addAll(keys);
        }
    }

    public void setLiveViewportResults(int count) {
        liveViewportResults.set(Math.max(0, count));
    }

    public void setLiveTraceModels(int count) {
        liveTraceModels.set(Math.max(0, count));
    }

    public int liveViewportResults() {
        return liveViewportResults.get();
    }

    public int liveTraceModels() {
        return liveTraceModels.get();
    }

    public int analysisTab() {
        return analysisTab;
    }

    public void setAnalysisTab(int tab) {
        analysisTab = Math.max(0, tab);
    }

    public boolean inspectorVisible() {
        return inspectorVisible;
    }

    public void setInspectorVisible(boolean visible) {
        inspectorVisible = visible;
    }

    public boolean plotSettingsVisible() {
        return plotSettingsVisible;
    }

    public void setPlotSettingsVisible(boolean visible) {
        plotSettingsVisible = visible;
    }

    public String signalsDisplayMode() {
        return signalsDisplayMode == null ? "" : signalsDisplayMode;
    }

    public void setSignalsDisplayMode(String mode) {
        signalsDisplayMode = mode == null ? "" : mode;
    }

    public String signalsPrecision() {
        return signalsPrecision == null ? "" : signalsPrecision;
    }

    public void setSignalsPrecision(String precision) {
        signalsPrecision = precision == null ? "" : precision;
    }

    public GraphInvestigationSnapshot snapshot(String projectId, List<SignalCatalogKey> membership) {
        List<GraphInvestigationSnapshot.Trace> traces = new ArrayList<>();
        if (membership != null) {
            for (SignalCatalogKey key : membership) {
                if (key == null) {
                    continue;
                }
                traces.add(new GraphInvestigationSnapshot.Trace(key, hidden.contains(key), colorIndex(key)));
            }
        }
        return new GraphInvestigationSnapshot(
                projectId,
                layoutMode,
                viewportInitialized ? viewportStart : null,
                viewportInitialized ? viewportEnd : null,
                defaultStart,
                defaultEnd,
                cursorA,
                cursorB,
                notes(),
                traces,
                focusKey,
                analysisTab,
                inspectorVisible,
                plotSettingsVisible,
                signalsDisplayMode(),
                signalsPrecision());
    }

    /**
     * Restores logical state. {@code resolvedMembership} is already filtered to keys that still
     * exist in the catalog — unavailable keys are reported separately and never rebound by name.
     */
    public void restore(GraphInvestigationSnapshot snapshot, List<SignalCatalogKey> resolvedMembership) {
        hidden.clear();
        colors.clear();
        unavailable.clear();
        nextColor = 0;
        focusKey = null;
        if (snapshot == null) {
            return;
        }
        layoutMode = OVERLAY.equals(snapshot.layoutMode()) ? OVERLAY : STACKED;
        notes = snapshot.notes() == null ? "" : snapshot.notes();
        cursorA = snapshot.cursorANanos();
        cursorB = snapshot.cursorBNanos();
        defaultStart = snapshot.defaultStartNanos();
        defaultEnd = snapshot.defaultEndNanos();
        analysisTab = Math.max(0, snapshot.analysisTab());
        inspectorVisible = snapshot.inspectorVisible();
        plotSettingsVisible = snapshot.plotSettingsVisible();
        signalsDisplayMode = snapshot.signalsDisplayMode();
        signalsPrecision = snapshot.signalsPrecision();
        if (snapshot.viewportStartNanos() != null && snapshot.viewportEndNanos() != null
                && snapshot.viewportEndNanos() > snapshot.viewportStartNanos()) {
            viewportStart = snapshot.viewportStartNanos();
            viewportEnd = snapshot.viewportEndNanos();
            viewportInitialized = true;
        } else {
            viewportInitialized = false;
        }
        Set<SignalCatalogKey> resolved = resolvedMembership == null
                ? Set.of()
                : Set.copyOf(resolvedMembership);
        for (GraphInvestigationSnapshot.Trace trace : snapshot.traces()) {
            if (trace == null || trace.key() == null) {
                continue;
            }
            if (!resolved.contains(trace.key())) {
                unavailable.add(trace.key());
                continue;
            }
            colors.put(trace.key(), trace.colorIndex());
            nextColor = Math.max(nextColor, trace.colorIndex() + 1);
            if (trace.hidden()) {
                hidden.add(trace.key());
            }
        }
        if (snapshot.focusKey() != null && resolved.contains(snapshot.focusKey())) {
            focusKey = snapshot.focusKey();
        }
    }
}
