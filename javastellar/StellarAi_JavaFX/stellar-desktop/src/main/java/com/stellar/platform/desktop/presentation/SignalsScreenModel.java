package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.signals.SignalPreviewBuffer;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.align.AlignedSignalRow;
import com.stellar.platform.processing.align.AlignedSignalWorkspaceQuery;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.catalog.SignalSeriesStore;

import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Catalog-first Signals workspace model.
 * <p>
 * JavaFX never receives millions of {@code CanonicalSignal} rows. The catalog is searchable
 * metadata; sample values are loaded only for selected signal(s) via a bounded page query.
 * Favorites and browser filters are UI convenience state only — never parser/catalog storage.
 */
public final class SignalsScreenModel {

    public static final int SAMPLE_PAGE_LIMIT = 5_000;
    /**
     * Practical upper bound for Active Signal Workspace columns. Not a tiny hard product
     * limit — protects UI/memory. Additional selections are rejected with feedback (never silent).
     */
    public static final int DEFAULT_MAX_WORKSPACE_SIGNALS = 128;

    private final AtomicReference<SignalPreviewBuffer> buffer =
            new AtomicReference<>(new SignalPreviewBuffer(SignalPreviewBuffer.DEFAULT_CAPACITY));
    private final AtomicReference<SignalSeriesStore> catalogStore = new AtomicReference<>();
    private final AtomicReference<LogicalSignalGroupIndex> logicalSignalGroups =
            new AtomicReference<>(LogicalSignalGroupIndex.empty());
    private final AtomicReference<SignalCatalog> catalog = new AtomicReference<>(SignalCatalog.empty());
    private final AtomicReference<List<SignalCatalogEntry>> displayedCatalog =
            new AtomicReference<>(List.of());
    private final AtomicReference<List<SignalSamplePoint>> displayedSamples =
            new AtomicReference<>(List.of());
    private final AtomicReference<SignalSamplePageSnapshot> displayedPage =
            new AtomicReference<>(null);
    private final AtomicReference<String> searchQuery = new AtomicReference<>("");
    private final AtomicReference<SignalBrowserFilter.State> browserFilter =
            new AtomicReference<>(SignalBrowserFilter.State.none());
    private final AtomicReference<SignalBrowserViewMode> browserViewMode =
            new AtomicReference<>(SignalBrowserViewMode.ALL_INSTANCES);
    private final Set<SignalCatalogKey> favorites = ConcurrentHashMap.newKeySet();
    private final Object recentLock = new Object();
    private final Deque<SignalCatalogKey> recentSignals = new ArrayDeque<>();
    public static final int MAX_RECENT_SIGNALS = 20;
    private final CopyOnWriteArrayList<Runnable> favoritesListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> recentsListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SignalCatalogKey> workspaceKeys = new CopyOnWriteArrayList<>();
    private final AtomicReference<SignalCatalogKey> focusedWorkspaceKey = new AtomicReference<>(null);
    private final AtomicInteger alignedWindowStart = new AtomicInteger(0);
    private final AtomicReference<AlignedSignalWorkspaceQuery.Request> pendingWorkspaceQuery =
            new AtomicReference<>(null);
    private final AtomicReference<Long> lastPageFirstTimestamp = new AtomicReference<>(null);
    private final AtomicReference<Long> lastPageLastTimestamp = new AtomicReference<>(null);
    private final AtomicInteger lastPageRowCount = new AtomicInteger(0);
    private final AtomicReference<WorkspaceDisplayMode> displayMode =
            new AtomicReference<>(WorkspaceDisplayMode.PHYSICAL);
    private final AtomicReference<WorkspacePrecision> precision =
            new AtomicReference<>(WorkspacePrecision.AUTO);
    private final AtomicReference<Integer> pendingFocusRow = new AtomicReference<>(null);
    private final AtomicBoolean workspaceLoadBusy = new AtomicBoolean(false);
    private final AtomicInteger workspaceLoadGeneration = new AtomicInteger();
    private final AtomicInteger maxWorkspaceSignals = new AtomicInteger(DEFAULT_MAX_WORKSPACE_SIGNALS);
    private final AtomicReference<String> lastWorkspaceAddFeedback = new AtomicReference<>("");
    private final AtomicReference<String> lastWorkspaceDataFeedback = new AtomicReference<>("");
    private final AtomicReference<String> statusNote = new AtomicReference<>("No signals yet.");
    private static final System.Logger LOG = System.getLogger(SignalsScreenModel.class.getName());
    private final AtomicReference<SignalsProcessingContext> processingContext =
            new AtomicReference<>(SignalsProcessingContext.empty());
    /** Transient UI scope. Cleared when consumed or when the workspace is cleared. */
    private final AtomicReference<String> pendingMeasurementNavigation = new AtomicReference<>(null);
    private final AtomicBoolean pendingAllMeasurements = new AtomicBoolean(false);
    private final AtomicInteger browserFilterGeneration = new AtomicInteger();
    private final AtomicInteger samplePageSize = new AtomicInteger(SAMPLE_PAGE_LIMIT);
    /** Bumped when the catalog or project is replaced. Navigation does not bump it. */
    private final AtomicInteger catalogRevision = new AtomicInteger(1);

    /** Catalog generation. A Graph reopen with the same value can reuse derived categories. */
    public int catalogRevision() {
        return catalogRevision.get();
    }
    private final AtomicInteger catalogReplaceCount = new AtomicInteger();
    private final AtomicInteger workspaceAlignedQueryCount = new AtomicInteger();
    private final AtomicReference<WorkspacePageCache> workspacePageCache = new AtomicReference<>();
    private final AtomicReference<UnionCountCache> alignedUnionCount = new AtomicReference<>();
    private final AtomicInteger alignedUnionComputations = new AtomicInteger();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stellar-signal-catalog");
        t.setDaemon(true);
        return t;
    });

    public void clear() {
        buffer.set(new SignalPreviewBuffer(SignalPreviewBuffer.DEFAULT_CAPACITY));
        closeStoreQuietly(catalogStore.getAndSet(null));
        catalog.set(SignalCatalog.empty());
        logicalSignalGroups.set(LogicalSignalGroupIndex.empty());
        displayedCatalog.set(List.of());
        displayedSamples.set(List.of());
        displayedPage.set(null);
        searchQuery.set("");
        browserFilter.set(SignalBrowserFilter.State.none());
        browserViewMode.set(SignalBrowserViewMode.ALL_INSTANCES);
        workspaceKeys.clear();
        focusedWorkspaceKey.set(null);
        clearAlignedNavigationState();
        processingContext.set(SignalsProcessingContext.empty());
        lastWorkspaceDataFeedback.set("");
        statusNote.set("No signals yet.");
        pendingMeasurementNavigation.set(null);
        pendingAllMeasurements.set(false);
        invalidateWorkspacePageCache();
    }

    /**
     * One-shot Signals measurement scope requested by Processing Complete.
     * Not written to the project database.
     */
    public void requestMeasurementNavigation(String measurementId) {
        pendingAllMeasurements.set(false);
        String id = measurementId == null ? "" : measurementId.trim();
        pendingMeasurementNavigation.set(id.isEmpty() ? null : id);
    }

    /** One-shot request to restore the combined project catalog. */
    public void requestAllMeasurementsNavigation() {
        pendingMeasurementNavigation.set(null);
        pendingAllMeasurements.set(true);
    }

    public String peekMeasurementNavigation() {
        return pendingMeasurementNavigation.get();
    }

    public void clearMeasurementNavigation() {
        pendingMeasurementNavigation.set(null);
    }

    public boolean consumeAllMeasurementsNavigation() {
        return pendingAllMeasurements.getAndSet(false);
    }

    public void replaceBuffer(SignalPreviewBuffer preview) {
        buffer.set(preview == null
                ? new SignalPreviewBuffer(SignalPreviewBuffer.DEFAULT_CAPACITY)
                : preview);
    }

    public void replaceCatalog(SignalSeriesStore store) {
        replaceCatalog(store, null, null);
    }

    public void replaceCatalog(SignalSeriesStore store, String processingHint) {
        replaceCatalog(store, processingHint, null);
    }

    /**
     * Replaces the catalog and optionally binds an immutable processing-result context.
     * When {@code context} is null, the previous context is retained (except on {@link #clear()}).
     */
    public void replaceCatalog(
            SignalSeriesStore store,
            String processingHint,
            SignalsProcessingContext context
    ) {
        replaceCatalog(store, processingHint, context, false);
    }

    /**
     * Atomically replaces the completed catalog.
     * When {@code preserveWorkspace} is true, keeps Active Signal Workspace members whose
     * {@link SignalCatalogKey} still exists in the new catalog (channel-aware identity).
     */
    public void replaceCatalog(
            SignalSeriesStore store,
            String processingHint,
            SignalsProcessingContext context,
            boolean preserveWorkspace
    ) {
        invalidateWorkspacePageCache();
        List<SignalCatalogKey> previousWorkspace =
                preserveWorkspace ? List.copyOf(workspaceKeys) : List.of();
        SignalCatalogKey previousFocus = focusedWorkspaceKey.get();
        SignalSeriesStore previousStore = catalogStore.getAndSet(store);
        SignalCatalog snap = store == null ? SignalCatalog.empty() : store.snapshot();
        if (previousStore != store && shouldClosePreviousCatalogStore(previousStore, store)) {
            closeStoreQuietly(previousStore);
        }
        catalog.set(snap);
        LogicalSignalGroupIndex groups = LogicalSignalGroupIndex.build(snap.entries());
        logicalSignalGroups.set(groups);
        // A newly completed processing result always opens in the safest view: every physical
        // signal instance. Unique-name mode is an explicit presentation-only user choice.
        browserViewMode.set(SignalBrowserViewMode.ALL_INSTANCES);
        // Invalidate in-flight browser filters so stale async callbacks cannot clear rows.
        browserFilterGeneration.incrementAndGet();
        displayedCatalog.set(snap.entries());
        displayedSamples.set(List.of());
        displayedPage.set(null);
        workspaceKeys.clear();
        focusedWorkspaceKey.set(null);
        clearAlignedNavigationState();
        lastWorkspaceDataFeedback.set("");
        if (context != null) {
            processingContext.set(context);
        }
        if (preserveWorkspace && !previousWorkspace.isEmpty()) {
            SignalCatalogKey lastKept = null;
            for (SignalCatalogKey key : previousWorkspace) {
                Optional<SignalCatalogKey> remapped = resolvePreservedWorkspaceKey(key, snap);
                if (remapped.isPresent()) {
                    workspaceKeys.add(remapped.get());
                    lastKept = remapped.get();
                }
            }
            if (previousFocus != null) {
                Optional<SignalCatalogKey> remappedFocus = resolvePreservedWorkspaceKey(previousFocus, snap);
                if (remappedFocus.isPresent()) {
                    focusedWorkspaceKey.set(remappedFocus.get());
                } else if (lastKept != null) {
                    focusedWorkspaceKey.set(lastKept);
                }
            } else if (lastKept != null) {
                focusedWorkspaceKey.set(lastKept);
            }
        }
        refreshStatusNote(workspaceKeys.size(), 0, processingHint);
    }

    /**
     * Job {@link com.stellar.platform.processing.graph.GraphSignalSessionStore} handles are owned
     * by {@link com.stellar.platform.processing.execution.ProcessingResultRegistry}. Closing them
     * here would break project-wide Explore after a prior View Signals, and destroy reusable
     * artifacts still referenced by {@link ProjectMergedSignalStore}.
     */
    private static boolean shouldClosePreviousCatalogStore(
            SignalSeriesStore previous,
            SignalSeriesStore next
    ) {
        if (previous == null) {
            return false;
        }
        if (previous instanceof com.stellar.platform.processing.graph.GraphSignalSessionStore) {
            return false;
        }
        if (next instanceof ProjectMergedSignalStore) {
            return false;
        }
        return true;
    }

    /**
     * Exact key match first; otherwise remap physical ↔ project-logical engineering identity so
     * View Signals → Explore Signals does not silently drop workspace membership.
     */
    public Optional<SignalCatalogKey> resolveCatalogKey(SignalCatalogKey previous) {
        return resolvePreservedWorkspaceKey(previous, catalog.get());
    }

    /**
     * Exact key match first; otherwise remap physical ↔ project-logical engineering identity so
     * View Signals → Explore Signals does not silently drop workspace membership.
     */
    private static Optional<SignalCatalogKey> resolvePreservedWorkspaceKey(
            SignalCatalogKey previous,
            SignalCatalog snap
    ) {
        if (previous == null || snap == null) {
            return Optional.empty();
        }
        if (snap.find(previous).isPresent()) {
            return Optional.of(previous);
        }
        for (SignalCatalogEntry entry : snap.entries()) {
            SignalCatalogKey candidate = entry.key();
            if (ProjectMergedSignalStore.sameLogicalEngineeringSignal(previous, candidate)
                    || (ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE.equals(candidate.sourceFile())
                    && previous.signalName().equals(candidate.signalName())
                    && previous.sourceType() == candidate.sourceType()
                    && previous.messageName().equals(candidate.messageName())
                    && previous.canId() == candidate.canId()
                    && previous.channel() == candidate.channel())) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Add-to-workspace remapping: keep exact catalog keys; remap physical → PROJECT logical only.
     * Does not map one physical source onto another physical source (graph/workspace identity).
     */
    private static boolean isProjectLogicalAliasOf(SignalCatalogKey existing, SignalCatalogKey physical) {
        if (existing == null || physical == null) {
            return false;
        }
        if (!ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE.equalsIgnoreCase(
                existing.sourceFile() == null ? "" : existing.sourceFile())) {
            return false;
        }
        return existing.signalName().equals(physical.signalName())
                && existing.sourceType() == physical.sourceType()
                && existing.messageName().equals(physical.messageName())
                && existing.canId() == physical.canId()
                && existing.channel() == physical.channel();
    }

    private static SignalCatalogKey resolveAddWorkspaceKey(SignalCatalogKey previous, SignalCatalog snap) {
        if (previous == null) {
            return null;
        }
        if (snap != null && snap.find(previous).isPresent()) {
            return previous;
        }
        if (snap == null) {
            return previous;
        }
        for (SignalCatalogEntry entry : snap.entries()) {
            SignalCatalogKey candidate = entry.key();
            if (!ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE.equals(candidate.sourceFile())) {
                continue;
            }
            if (ProjectMergedSignalStore.sameLogicalEngineeringSignal(previous, candidate)
                    || (previous.signalName().equals(candidate.signalName())
                    && previous.sourceType() == candidate.sourceType()
                    && previous.messageName().equals(candidate.messageName())
                    && previous.canId() == candidate.canId()
                    && previous.channel() == candidate.channel())) {
                return candidate;
            }
        }
        return previous;
    }

    public String lastWorkspaceDataFeedback() {
        return lastWorkspaceDataFeedback.get();
    }

    /** Keys that were active before a catalog swap but missing afterward. */
    public List<SignalCatalogKey> missingWorkspaceKeys(List<SignalCatalogKey> candidateKeys) {
        if (candidateKeys == null || candidateKeys.isEmpty()) {
            return List.of();
        }
        SignalCatalog snap = catalog.get();
        return candidateKeys.stream().filter(k -> snap.find(k).isEmpty()).toList();
    }

    public SignalsProcessingContext processingContext() {
        return processingContext.get();
    }

    public void setSearchQuery(String query) {
        searchQuery.set(query == null ? "" : query);
    }

    public String searchQuery() {
        return searchQuery.get();
    }

    public void setBrowserFilter(SignalBrowserFilter.State state) {
        browserFilter.set(state == null ? SignalBrowserFilter.State.none() : state);
    }

    /** If the browser is scoped to a measurement that left the project, return to All Measurements. */
    public void resetMeasurementFilterIfGone(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return;
        }
        SignalBrowserFilter.State state = browserFilter.get();
        if (state == null || state.sourceFile().isBlank() || !sourceId.equals(state.sourceFile())) {
            return;
        }
        browserFilter.set(new SignalBrowserFilter.State(
                state.source(), state.message(), state.unit(), state.canId(),
                state.favoritesOnly(), state.recentOnly(), "", state.dbcFile()));
    }

    public SignalBrowserFilter.State browserFilter() {
        return browserFilter.get();
    }

    public boolean isFavorite(SignalCatalogKey key) {
        return key != null && favorites.contains(key);
    }

    public boolean toggleFavorite(SignalCatalogKey key) {
        if (key == null) {
            return false;
        }
        boolean nowFavorite;
        if (favorites.contains(key)) {
            favorites.remove(key);
            nowFavorite = false;
        } else {
            favorites.add(key);
            nowFavorite = true;
        }
        notifyFavoritesChanged();
        return nowFavorite;
    }

    public Set<SignalCatalogKey> favorites() {
        return Set.copyOf(favorites);
    }

    public void addFavoritesListener(Runnable listener) {
        if (listener != null) {
            favoritesListeners.add(listener);
        }
    }

    public void removeFavoritesListener(Runnable listener) {
        if (listener != null) {
            favoritesListeners.remove(listener);
        }
    }

    private void notifyFavoritesChanged() {
        for (Runnable listener : favoritesListeners) {
            listener.run();
        }
    }

    public void addRecentsListener(Runnable listener) {
        if (listener != null) {
            recentsListeners.add(listener);
        }
    }

    public void removeRecentsListener(Runnable listener) {
        if (listener != null) {
            recentsListeners.remove(listener);
        }
    }

    public int favoritesListenerCountForTest() {
        return favoritesListeners.size();
    }

    public int recentsListenerCountForTest() {
        return recentsListeners.size();
    }

    /** Drops in-flight page-scoped async callbacks without clearing durable workspace state. */
    public void invalidatePageScopedLoads() {
        workspaceLoadGeneration.incrementAndGet();
        browserFilterGeneration.incrementAndGet();
    }

    /**
     * Drops the one retained table page. Called when the catalog or project changes,
     * not when Signals is left and reopened.
     */
    private void invalidateWorkspacePageCache() {
        catalogRevision.incrementAndGet();
        catalogReplaceCount.incrementAndGet();
        workspacePageCache.set(null);
        alignedUnionCount.set(null);
    }

    /** Times the aligned sample store was actually queried. Navigation reuse does not increment this. */
    public int workspaceAlignedQueryCountForTest() {
        return workspaceAlignedQueryCount.get();
    }

    /** Times {@link #replaceCatalog} or {@link #clear()} replaced the catalog. */
    public int catalogReplaceCountForTest() {
        return catalogReplaceCount.get();
    }

    /**
     * The current table page when membership, window, page size, and catalog revision
     * still match the last successful load. One bounded page only.
     */
    public Optional<AlignedWorkspaceSnapshot> reusableWorkspaceSnapshot() {
        WorkspacePageCache cache = workspacePageCache.get();
        if (cache == null || cache.revision() != catalogRevision.get()) {
            return Optional.empty();
        }
        if (cache.pageSize() != samplePageSize.get() || cache.windowStart() != alignedWindowStart.get()) {
            return Optional.empty();
        }
        if (!cache.keys().equals(List.copyOf(workspaceKeys))) {
            return Optional.empty();
        }
        return Optional.of(cache.snapshot());
    }

    private void rememberWorkspacePage(
            List<SignalCatalogKey> keys,
            int windowStart,
            int pageSize,
            AlignedWorkspaceSnapshot snapshot
    ) {
        workspacePageCache.set(new WorkspacePageCache(
                catalogRevision.get(),
                List.copyOf(keys),
                Math.max(0, windowStart),
                Math.max(1, pageSize),
                snapshot));
    }

    private void notifyRecentsChanged() {
        for (Runnable listener : recentsListeners) {
            listener.run();
        }
    }

    /** Session-only recent signals (focus / workspace add). Bounded; never persisted. */
    public void touchRecent(SignalCatalogKey key) {
        if (key == null) {
            return;
        }
        synchronized (recentLock) {
            recentSignals.remove(key);
            recentSignals.addFirst(key);
            while (recentSignals.size() > MAX_RECENT_SIGNALS) {
                recentSignals.removeLast();
            }
        }
        notifyRecentsChanged();
    }

    public List<SignalCatalogKey> recentSignals() {
        synchronized (recentLock) {
            return List.copyOf(recentSignals);
        }
    }

    public Set<SignalCatalogKey> recentSignalSet() {
        synchronized (recentLock) {
            return Set.copyOf(recentSignals);
        }
    }

    /** Adds a signal to the Active Signal Workspace UI state (duplicate-safe). */
    public boolean addToWorkspace(SignalCatalogKey key) {
        return addToWorkspace(key == null ? List.of() : List.of(key)) > 0;
    }

    /** @return number of newly added signals (duplicates focused, not counted). */
    public int addToWorkspace(List<SignalCatalogKey> keys) {
        lastWorkspaceAddFeedback.set("");
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        SignalCatalogKey lastNewlyAdded = null;
        SignalCatalogKey lastPresent = null;
        int added = 0;
        int rejectedLimit = 0;
        int max = maxWorkspaceSignals.get();
        SignalCatalog snap = catalog.get();
        for (SignalCatalogKey raw : keys) {
            if (raw == null) {
                continue;
            }
            // Remap only onto project-logical keys (Explore). Never collapse distinct physical
            // source identities onto another physical catalog row during add.
            SignalCatalogKey key = resolveAddWorkspaceKey(raw, snap);
            touchRecent(key);
            if (workspaceKeys.contains(key)) {
                lastPresent = key;
                continue;
            }
            if (workspaceKeys.size() >= max) {
                rejectedLimit++;
                continue;
            }
            workspaceKeys.add(key);
            lastNewlyAdded = key;
            lastPresent = key;
            added++;
        }
        if (rejectedLimit > 0) {
            lastWorkspaceAddFeedback.set(
                    "Workspace limit reached (" + max + "). "
                            + rejectedLimit + " signal(s) not added. "
                            + "Raise the limit via setMaxWorkspaceSignals if needed.");
        }
        if (lastNewlyAdded != null) {
            focusedWorkspaceKey.set(lastNewlyAdded);
            resetAlignedWindowPreservingAppendCache();
        } else if (lastPresent != null) {
            focusedWorkspaceKey.set(lastPresent);
        }
        return added;
    }

    public String lastWorkspaceAddFeedback() {
        return lastWorkspaceAddFeedback.get();
    }

    public int maxWorkspaceSignals() {
        return maxWorkspaceSignals.get();
    }

    /** Configurable safety threshold for workspace columns (must be &gt;= 1). */
    public void setMaxWorkspaceSignals(int max) {
        maxWorkspaceSignals.set(Math.max(1, max));
    }

    /**
     * Graph analysis membership: keep the exact physical {@link SignalCatalogKey}.
     * Never remaps onto a PROJECT-logical Explore row (that unchecks ALL-mode explorer rows).
     */
    public boolean addPhysicalToWorkspace(SignalCatalogKey key) {
        if (key == null) {
            return false;
        }
        workspaceKeys.removeIf(existing -> isProjectLogicalAliasOf(existing, key));
        if (workspaceKeys.contains(key)) {
            return false;
        }
        if (workspaceKeys.size() >= maxWorkspaceSignals.get()) {
            return false;
        }
        workspaceKeys.add(key);
        focusedWorkspaceKey.set(key);
        return true;
    }

    /**
     * Adds the current filtered physical keys in one membership update.
     * Does not remap them onto project-logical keys.
     */
    public int addPhysicalKeys(List<SignalCatalogKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        int added = 0;
        for (SignalCatalogKey key : keys) {
            if (addPhysicalToWorkspace(key)) {
                added++;
            }
        }
        return added;
    }

    /**
     * Removes the given keys, including project-logical aliases, then resets the
     * aligned window once.
     */
    public int removePhysicalKeys(List<SignalCatalogKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (SignalCatalogKey key : keys) {
            if (key == null) {
                continue;
            }
            boolean hit = workspaceKeys.remove(key);
            hit |= workspaceKeys.removeIf(existing -> isProjectLogicalAliasOf(existing, key)
                    || isProjectLogicalAliasOf(key, existing));
            if (hit) {
                removed++;
            }
        }
        if (removed > 0) {
            resetAlignedWindow();
            focusedWorkspaceKey.set(workspaceKeys.isEmpty() ? null : workspaceKeys.getLast());
        }
        return removed;
    }

    /**
     * Replace workspace membership with canonical physical keys (set-semantic, order-preserving).
     */
    public void replacePhysicalWorkspace(List<SignalCatalogKey> keys) {
        workspaceKeys.clear();
        focusedWorkspaceKey.set(null);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        LinkedHashSet<SignalCatalogKey> unique = new LinkedHashSet<>();
        for (SignalCatalogKey key : keys) {
            if (key != null) {
                unique.add(key);
            }
        }
        workspaceKeys.addAll(unique);
        if (!workspaceKeys.isEmpty()) {
            focusedWorkspaceKey.set(workspaceKeys.getLast());
        }
    }

    public boolean removeFromWorkspace(SignalCatalogKey key) {
        if (key == null) {
            return false;
        }
        boolean removed = workspaceKeys.remove(key);
        removed |= workspaceKeys.removeIf(existing -> isProjectLogicalAliasOf(existing, key)
                || isProjectLogicalAliasOf(key, existing));
        if (removed) {
            resetAlignedWindow();
            if (Objects.equals(focusedWorkspaceKey.get(), key)
                    || (focusedWorkspaceKey.get() != null
                    && isProjectLogicalAliasOf(focusedWorkspaceKey.get(), key))) {
                focusedWorkspaceKey.set(workspaceKeys.isEmpty() ? null : workspaceKeys.getLast());
            }
        }
        return removed;
    }

    public void clearWorkspace() {
        workspaceKeys.clear();
        focusedWorkspaceKey.set(null);
        clearAlignedNavigationState();
    }

    /**
     * Reorders Active Signals without reloading sources or creating new identities.
     *
     * @param delta {@code -1} moves up, {@code +1} moves down
     */
    public boolean moveWorkspaceKey(SignalCatalogKey key, int delta) {
        if (key == null || delta == 0) {
            return false;
        }
        int index = workspaceKeys.indexOf(key);
        if (index < 0) {
            return false;
        }
        int target = index + delta;
        if (target < 0 || target >= workspaceKeys.size()) {
            return false;
        }
        workspaceKeys.remove(index);
        workspaceKeys.add(target, key);
        return true;
    }

    public List<SignalCatalogKey> workspaceKeys() {
        return List.copyOf(workspaceKeys);
    }

    /**
     * Focuses a signal for the Inspector. Independent of workspace membership —
     * browsing/focus must not add signals to the comparison table.
     */
    public void setFocusedSignalKey(SignalCatalogKey key) {
        focusedWorkspaceKey.set(key);
        if (key != null) {
            touchRecent(key);
        }
    }

    /** @deprecated use {@link #setFocusedSignalKey(SignalCatalogKey)} */
    public void setFocusedWorkspaceKey(SignalCatalogKey key) {
        setFocusedSignalKey(key);
    }

    public Optional<SignalCatalogKey> focusedSignalKey() {
        return Optional.ofNullable(focusedWorkspaceKey.get());
    }

    public Optional<SignalCatalogKey> focusedWorkspaceKey() {
        return focusedSignalKey();
    }

    /**
     * Resolves Active Signal Workspace membership for UI chrome.
     * One entry per {@link #workspaceKeys()} — never silently drops unresolved keys
     * (that previously produced chips/title from a different count than the status strip).
     */
    public List<SignalCatalogEntry> workspaceEntries() {
        List<SignalCatalogEntry> out = new java.util.ArrayList<>(workspaceKeys.size());
        for (SignalCatalogKey key : workspaceKeys) {
            out.add(findEntry(key).orElseGet(() -> stubWorkspaceEntry(key)));
        }
        return List.copyOf(out);
    }

    private static SignalCatalogEntry stubWorkspaceEntry(SignalCatalogKey key) {
        return new SignalCatalogEntry(
                key, "", 0L, null, null, null, null, key.sourceFile(), key.sourceDatabase());
    }

    /**
     * Loads one bounded aligned page for the workspace. Never materializes complete retained
     * series or a project-wide timestamp union.
     */
    public void loadWorkspaceAlignedAsync(Consumer<AlignedWorkspaceSnapshot> onResult) {
        loadWorkspaceAlignedAsync(false, onResult);
    }

    public void loadWorkspaceAlignedAsync(boolean forceReloadSeries, Consumer<AlignedWorkspaceSnapshot> onResult) {
        SignalSeriesStore store = catalogStore.get();
        List<SignalCatalogKey> keys = List.copyOf(workspaceKeys);
        int pageSize = samplePageSize.get();
        Integer focus = pendingFocusRow.getAndSet(null);
        AlignedSignalWorkspaceQuery.Request request = pendingWorkspaceQuery.getAndSet(null);
        if (request == null || forceReloadSeries) {
            request = AlignedSignalWorkspaceQuery.Request.first(keys, pageSize);
        } else {
            request = new AlignedSignalWorkspaceQuery.Request(
                    keys, pageSize, request.mode(), request.timestampNanos(), request.index(),
                    request.previousWindowStart(), request.previousRowCount());
        }
        final AlignedSignalWorkspaceQuery.Request query = request;
        final int generation = workspaceLoadGeneration.get();
        workspaceLoadBusy.set(true);
        worker.execute(() -> {
            try {
                if (generation != workspaceLoadGeneration.get()) {
                    return;
                }
                if (store == null || keys.isEmpty()) {
                    lastPageFirstTimestamp.set(null);
                    lastPageLastTimestamp.set(null);
                    lastPageRowCount.set(0);
                    displayedPage.set(null);
                    displayedSamples.set(List.of());
                    refreshStatusNote(0, 0);
                    if (onResult != null) {
                        onResult.accept(AlignedWorkspaceSnapshot.empty());
                    }
                    return;
                }
                workspaceAlignedQueryCount.incrementAndGet();
                AlignedSignalWorkspaceQuery.Result result = AlignedSignalWorkspaceQuery.query(store, query);
                List<SignalCatalogEntry> entries = workspaceEntries();
                List<AlignedSampleRow> rows = toPresentation(result.rows());
                alignedWindowStart.set(result.windowStartIndex());
                lastPageRowCount.set(rows.size());
                if (rows.isEmpty()) {
                    lastPageFirstTimestamp.set(null);
                    lastPageLastTimestamp.set(null);
                } else {
                    lastPageFirstTimestamp.set(rows.getFirst().timestampNanos());
                    lastPageLastTimestamp.set(rows.getLast().timestampNanos());
                }
                try {
                    displayedPage.set(querySinglePage(store, keys.getFirst(), 0, pageSize));
                } catch (RuntimeException ex) {
                    displayedPage.set(null);
                    LOG.log(System.Logger.Level.WARNING, () ->
                            "Workspace preview page unavailable for " + keys.getFirst() + ": " + ex.getMessage());
                }
                displayedSamples.set(result.pageSeries().isEmpty() ? List.of() : result.pageSeries().getFirst());
                detectUnavailableWorkspaceData(store, keys, entries, result.pageSeries());
                refreshStatusNote(keys.size(), rows.size());
                if (generation != workspaceLoadGeneration.get()) {
                    return;
                }
                int totalRows = exactAlignedUnionCount(store, keys);
                AlignedWorkspaceSnapshot snapshot = new AlignedWorkspaceSnapshot(
                        entries, rows, result.windowStartIndex(), totalRows, pageSize, focus);
                rememberWorkspacePage(keys, result.windowStartIndex(), pageSize, snapshot);
                if (onResult != null) {
                    onResult.accept(snapshot);
                }
            } catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING, () ->
                        "Workspace aligned load failed keys=" + keys + ": " + ex.getMessage());
                lastWorkspaceDataFeedback.set(
                        "Signal data unavailable — sample store could not be read. Reprocess affected sources.");
                if (generation == workspaceLoadGeneration.get() && onResult != null) {
                    onResult.accept(AlignedWorkspaceSnapshot.empty());
                }
            } finally {
                if (generation == workspaceLoadGeneration.get()) {
                    workspaceLoadBusy.set(false);
                }
            }
        });
    }

    public boolean isWorkspaceLoadBusy() {
        return workspaceLoadBusy.get();
    }

    public int alignedWindowStart() {
        return alignedWindowStart.get();
    }

    public WorkspaceDisplayMode displayMode() {
        return displayMode.get();
    }

    public void setDisplayMode(WorkspaceDisplayMode mode) {
        displayMode.set(mode == null ? WorkspaceDisplayMode.PHYSICAL : mode);
    }

    public WorkspacePrecision precision() {
        return precision.get();
    }

    public void setPrecision(WorkspacePrecision value) {
        precision.set(value == null ? WorkspacePrecision.AUTO : value);
    }

    public void resetAlignedWindow() {
        workspaceLoadGeneration.incrementAndGet();
        workspacePageCache.set(null);
        alignedWindowStart.set(0);
        pendingFocusRow.set(null);
        pendingWorkspaceQuery.set(null);
        lastPageFirstTimestamp.set(null);
        lastPageLastTimestamp.set(null);
        lastPageRowCount.set(0);
        workspaceLoadBusy.set(false);
    }

    /** Invalidates paging after an append; next load is a bounded first-page query. */
    private void resetAlignedWindowPreservingAppendCache() {
        resetAlignedWindow();
    }

    public void navigatePreviousWindowAsync(Consumer<AlignedWorkspaceSnapshot> onResult) {
        Long first = lastPageFirstTimestamp.get();
        pendingFocusRow.set(null);
        if (first == null) {
            pendingWorkspaceQuery.set(AlignedSignalWorkspaceQuery.Request.first(
                    List.copyOf(workspaceKeys), samplePageSize.get()));
        } else {
            pendingWorkspaceQuery.set(AlignedSignalWorkspaceQuery.Request.previous(
                    List.copyOf(workspaceKeys), samplePageSize.get(), first, alignedWindowStart.get()));
        }
        loadWorkspaceAlignedAsync(false, onResult);
    }

    public void navigateNextWindowAsync(Consumer<AlignedWorkspaceSnapshot> onResult) {
        Long last = lastPageLastTimestamp.get();
        pendingFocusRow.set(null);
        if (last == null) {
            pendingWorkspaceQuery.set(AlignedSignalWorkspaceQuery.Request.first(
                    List.copyOf(workspaceKeys), samplePageSize.get()));
        } else {
            pendingWorkspaceQuery.set(AlignedSignalWorkspaceQuery.Request.next(
                    List.copyOf(workspaceKeys), samplePageSize.get(), last,
                    alignedWindowStart.get(), lastPageRowCount.get()));
        }
        loadWorkspaceAlignedAsync(false, onResult);
    }

    /**
     * Jump to a row index on the aligned timeline.
     * The bound is the exact timestamp-union count. An out-of-range index does not change the current page.
     *
     * @return empty on success intent (async); message when rejected synchronously
     */
    public Optional<String> jumpToIndexAsync(long globalIndex, Consumer<AlignedWorkspaceSnapshot> onResult) {
        if (globalIndex < 0) {
            return Optional.of("Invalid index");
        }
        SignalSeriesStore store = catalogStore.get();
        List<SignalCatalogKey> keys = List.copyOf(workspaceKeys);
        if (store == null || keys.isEmpty()) {
            return Optional.of("Index out of range");
        }
        long upperBound = activeIndexCount(store, keys);
        if (upperBound <= 0L || globalIndex >= upperBound) {
            return Optional.of(indexOutOfRangeMessage(upperBound));
        }
        int pageSize = samplePageSize.get();
        pendingFocusRow.set((int) (globalIndex % pageSize));
        pendingWorkspaceQuery.set(AlignedSignalWorkspaceQuery.Request.atIndex(keys, pageSize, globalIndex));
        loadWorkspaceAlignedAsync(false, onResult);
        return Optional.empty();
    }

    /**
     * Jump to first aligned timestamp &gt;= requested seconds (exact long-nanos conversion).
     * Semantic: first timestamp at or after the request — no nearest/tolerance/interpolation.
     */
    public Optional<String> jumpToTimeSecondsAsync(double seconds, Consumer<AlignedWorkspaceSnapshot> onResult) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds < 0) {
            return Optional.of("Invalid timestamp");
        }
        Optional<String> range = validateJumpTimeResult(seconds);
        if (range.isPresent()) {
            return range;
        }
        long nanos = Math.round(seconds * 1_000_000_000L);
        pendingFocusRow.set(0);
        pendingWorkspaceQuery.set(AlignedSignalWorkspaceQuery.Request.atTimestamp(
                List.copyOf(workspaceKeys), samplePageSize.get(), nanos));
        loadWorkspaceAlignedAsync(false, onResult);
        return Optional.empty();
    }

    public Optional<String> validateJumpTimeResult(double seconds) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds < 0) {
            return Optional.of("Invalid timestamp");
        }
        SignalSeriesStore store = catalogStore.get();
        List<SignalCatalogKey> keys = List.copyOf(workspaceKeys);
        if (store == null || keys.isEmpty()) {
            return Optional.empty();
        }
        long nanos = Math.round(seconds * 1_000_000_000L);
        for (SignalCatalogKey key : keys) {
            int retained = Math.max(0, store.retainedSampleCount(key));
            if (retained > 0 && store.findSampleIndexAtOrAfter(key, nanos) < retained) {
                return Optional.empty();
            }
        }
        return Optional.of("Timestamp outside available range");
    }

    private void clearAlignedNavigationState() {
        resetAlignedWindow();
    }

    private static List<AlignedSampleRow> toPresentation(List<AlignedSignalRow> rows) {
        List<AlignedSampleRow> out = new ArrayList<>(rows.size());
        for (AlignedSignalRow row : rows) {
            Double[] physical = new Double[row.signalCount()];
            Long[] raw = new Long[row.signalCount()];
            String[] textual = new String[row.signalCount()];
            for (int i = 0; i < row.signalCount(); i++) {
                physical[i] = row.physicalAt(i);
                raw[i] = row.rawAt(i);
                textual[i] = row.textualAt(i);
            }
            out.add(new AlignedSampleRow(row.index(), row.timestampNanos(), physical, raw, textual, row.validityCopy()));
        }
        return List.copyOf(out);
    }

    private void detectUnavailableWorkspaceData(
            SignalSeriesStore store,
            List<SignalCatalogKey> keys,
            List<SignalCatalogEntry> entries,
            List<List<SignalSamplePoint>> series
    ) {
        lastWorkspaceDataFeedback.set("");
        if (keys == null || keys.isEmpty() || entries == null || series == null) {
            return;
        }
        boolean expectedSamples = false;
        boolean anyResolved = false;
        for (int i = 0; i < keys.size() && i < entries.size() && i < series.size(); i++) {
            SignalCatalogEntry entry = entries.get(i);
            long metaSamples = entry.sampleCount();
            if (metaSamples > 0
                    || entry.firstTimestampNanosOptional().isPresent()
                    || entry.lastTimestampNanosOptional().isPresent()) {
                expectedSamples = true;
            }
            if (!series.get(i).isEmpty()) {
                anyResolved = true;
            }
        }
        if (!expectedSamples || anyResolved) {
            return;
        }
        SignalsProcessingContext ctx = processingContext.get();
        String storeType = store == null ? "null" : store.getClass().getSimpleName();
        String scope = ctx == null ? "" : String.valueOf(ctx.viewScope());
        LOG.log(System.Logger.Level.WARNING, () ->
                "Signal data unavailable keys=" + keys
                        + " scope=" + scope
                        + " storeType=" + storeType
                        + " artifactHint=" + (ctx == null ? "" : ctx.sourceFile())
                        + " queryRange=workspace-aligned");
        lastWorkspaceDataFeedback.set("Signal data unavailable — catalog metadata cannot be resolved by the current store.");
        refreshStatusNote(keys.size(), 0, "Signal data unavailable");
    }

    private Set<SignalCatalogKey> browserFavoriteKeys() {
        // Browser rows are physical instances by default; favorites must therefore remain bound
        // to the exact channel-aware key selected by the engineer.
        return favorites.isEmpty() ? Set.of() : Set.copyOf(favorites);
    }

    private Set<SignalCatalogKey> browserRecentKeys() {
        Set<SignalCatalogKey> recent = recentSignalSet();
        return recent.isEmpty() ? Set.of() : recent;
    }

    /**
     * All Measurements browses the logical project catalog. One measurement browses that
     * file's physical keys so Analyze and Graphs share the same identity.
     */
    private List<SignalCatalogEntry> browserSourceEntries() {
        SignalBrowserFilter.State filter = browserFilter.get();
        String scope = filter == null || filter.sourceFile() == null ? "" : filter.sourceFile().trim();
        SignalSeriesStore store = catalogStore.get();
        if (!scope.isEmpty() && store instanceof ProjectMergedSignalStore merged) {
            return merged.physicalCatalog(scope, scope);
        }
        return catalog.get().entries();
    }

    /** Override page size (tests / diagnostics). Production default is {@link #SAMPLE_PAGE_LIMIT}. */
    public void setSamplePageSize(int pageSize) {
        samplePageSize.set(Math.max(1, pageSize));
    }

    public int samplePageSize() {
        return samplePageSize.get();
    }

    /**
     * Synchronously applies the current browser filter to the active catalog.
     * Used by Signals screen refresh so TableView rows cannot stay bound to a stale empty list.
     */
    public List<SignalCatalogEntry> applyBrowserFilterNow() {
        List<SignalCatalogEntry> filtered = SignalBrowserFilter.apply(
                browserSourceEntries(),
                searchQuery.get(),
                browserFilter.get(),
                browserFavoriteKeys(),
                browserRecentKeys()
        );
        List<SignalCatalogEntry> projected =
                SignalBrowserProjection.apply(filtered, browserViewMode.get());
        displayedCatalog.set(projected);
        refreshStatusNote();
        return projected;
    }

    public void searchAsync(Consumer<List<SignalCatalogEntry>> onResult) {
        final int generation = browserFilterGeneration.incrementAndGet();
        String q = searchQuery.get();
        List<SignalCatalogEntry> current = browserSourceEntries();
        SignalBrowserFilter.State filter = browserFilter.get();
        SignalBrowserViewMode viewMode = browserViewMode.get();
        Set<SignalCatalogKey> fav = browserFavoriteKeys();
        Set<SignalCatalogKey> recent = browserRecentKeys();
        worker.execute(() -> {
            List<SignalCatalogEntry> filtered =
                    SignalBrowserFilter.apply(current, q, filter, fav, recent);
            List<SignalCatalogEntry> projected = SignalBrowserProjection.apply(filtered, viewMode);
            if (generation != browserFilterGeneration.get()) {
                return; // superseded by catalog replacement or a newer filter request
            }
            displayedCatalog.set(projected);
            refreshStatusNote();
            if (onResult != null) {
                onResult.accept(projected);
            }
        });
    }

    /**
     * Loads the first bounded page for the selection (offset 0). Prefer
     * {@link #loadSamplesPageAsync} for explicit paging.
     */
    public void loadSamplesAsync(List<SignalCatalogKey> selectedKeys, Consumer<List<SignalSamplePoint>> onResult) {
        loadSamplesPageAsync(selectedKeys, 0, page -> {
            if (onResult != null) {
                onResult.accept(page == null ? List.of() : page.samples());
            }
        });
    }

    /**
     * Bounded page load. {@code pageOffset} is an offset into the retained window
     * (same contract as {@link SignalSeriesStore#querySamples}).
     */
    public void loadSamplesPageAsync(
            List<SignalCatalogKey> selectedKeys,
            int pageOffset,
            Consumer<SignalSamplePageSnapshot> onResult
    ) {
        SignalSeriesStore store = catalogStore.get();
        List<SignalCatalogKey> keys = selectedKeys == null ? List.of() : List.copyOf(selectedKeys);
        int pageSize = samplePageSize.get();
        int safeOffset = Math.max(0, pageOffset);
        worker.execute(() -> {
            SignalSamplePageSnapshot page;
            if (store == null || keys.isEmpty()) {
                page = null;
            } else if (keys.size() == 1) {
                page = querySinglePage(store, keys.getFirst(), safeOffset, pageSize);
            } else {
                // Multi-select: bounded chronological k-way merge (never concatenate by selection order).
                int perKey = Math.max(1, pageSize);
                List<List<SignalSamplePoint>> pages = new ArrayList<>(keys.size());
                long total = 0L;
                int retained = 0;
                for (SignalCatalogKey k : keys) {
                    SignalCatalogKey queryKey = scopedQueryKey(store, k);
                    pages.add(store.querySamples(queryKey, 0, perKey));
                    total += sampleCountFor(store, k, queryKey);
                    retained += store.retainedSampleCount(queryKey);
                }
                List<SignalSamplePoint> samples =
                        ProjectMergedSignalStore.TimestampOrderedSampleMerge.mergeOrderedPages(pages, pageSize);
                SignalCatalogKey primary = keys.getFirst();
                page = new SignalSamplePageSnapshot(
                        primary,
                        samples,
                        0,
                        pageSize,
                        total,
                        retained,
                        store.retainedWindowStartIndex(primary)
                );
            }
            displayedPage.set(page);
            displayedSamples.set(page == null ? List.of() : page.samples());
            refreshStatusNote(keys.size(), displayedSamples.get().size());
            if (onResult != null) {
                onResult.accept(page);
            }
        });
    }

    public Optional<SignalCatalogEntry> findEntry(SignalCatalogKey key) {
        if (key == null) {
            return Optional.empty();
        }
        return catalog.get().find(key);
    }

    public SignalBrowserViewMode browserViewMode() {
        return browserViewMode.get();
    }

    public void setBrowserViewMode(SignalBrowserViewMode mode) {
        SignalBrowserViewMode next = mode == null ? SignalBrowserViewMode.ALL_INSTANCES : mode;
        if (browserViewMode.getAndSet(next) != next) {
            browserFilterGeneration.incrementAndGet();
        }
    }

    /** Number of preserved physical signal instances in the current catalog. */
    public int physicalSignalCount() {
        return catalog.get().size();
    }

    /** Per-source exact-name count used only to explain the optional Hide Duplicates view. */
    public int uniqueSignalNameCount() {
        return SignalBrowserProjection.uniqueExactNameCount(catalog.get().entries());
    }

    /**
     * Legacy logical-group API retained for compatibility with callers that explicitly request
     * grouped overlays. Signal Browser no longer uses this implicitly.
     */
    public List<SignalCatalogEntry> logicalInstances(SignalCatalogKey representativeOrPhysicalKey) {
        return logicalSignalGroups.get().instances(representativeOrPhysicalKey);
    }

    public int logicalInstanceCount(SignalCatalogKey representativeOrPhysicalKey) {
        return logicalSignalGroups.get().instanceCount(representativeOrPhysicalKey);
    }

    public int logicalSignalCount() {
        return logicalSignalGroups.get().representatives().size();
    }

    public List<SignalCatalogKey> logicalInstanceKeys(SignalCatalogKey representativeOrPhysicalKey) {
        return logicalInstances(representativeOrPhysicalKey).stream().map(SignalCatalogEntry::key).toList();
    }

    /** Adds every preserved physical instance of a logical browser row to the active workspace. */
    public int addLogicalGroupToWorkspace(SignalCatalogKey representativeOrPhysicalKey) {
        return addToWorkspace(logicalInstanceKeys(representativeOrPhysicalKey));
    }

    public SignalSeriesStore catalogStore() {
        return catalogStore.get();
    }

    public SignalSamplePageSnapshot displayedPage() {
        return displayedPage.get();
    }

    public SignalCatalog catalog() {
        return catalog.get();
    }

    /**
     * Every signal in the loaded measurement or project, ignoring browser search,
     * visibility, and the workspace table page.
     */
    public List<SignalCatalogEntry> signalsForFullExport() {
        SignalSeriesStore store = catalogStore.get();
        if (store instanceof ProjectMergedSignalStore merged) {
            List<SignalCatalogEntry> physical = merged.physicalCatalogAll();
            if (!physical.isEmpty()) {
                return physical;
            }
        }
        return List.copyOf(catalog.get().entries());
    }

    public List<SignalCatalogEntry> displayedCatalog() {
        return displayedCatalog.get();
    }

    /**
     * Complete current-scope catalog for Map Route GPS discovery.
     * Intentionally ignores Graph checkbox membership and Signal Browser hide-duplicates
     * projection — Map Route must see every signal in the active project/source scope.
     */
    public List<SignalCatalogEntry> mapRouteCatalog() {
        return catalog.get().entries();
    }

    /**
     * Complete unprojected catalog for one analysis measurement. Hide Duplicates, search,
     * Selected-only, and graph checkboxes never apply.
     */
    public List<SignalCatalogEntry> engineeringCatalog(AnalysisMeasurementContext measurement) {
        if (measurement == null || !measurement.isPresent()) {
            return List.of();
        }
        SignalSeriesStore store = catalogStore.get();
        if (store instanceof ProjectMergedSignalStore merged) {
            return merged.physicalCatalog(measurement.sourceId(), measurement.fileName());
        }
        return MeasurementCatalogScope.filter(
                catalog.get().entries(),
                measurement.sourceId(),
                measurement.fileName());
    }

    /**
     * Project-wide Graphs scope. A merged store exposes one logical signal, not one row
     * per measurement file. Physical series stay addressable through that logical key.
     */
    public List<SignalCatalogEntry> engineeringCatalogAll() {
        return catalog.get().entries();
    }

    /**
     * Catalog exposed to the graph workspace. Browser search/favorites/recent filters are
     * intentionally excluded, while the explicit duplicate presentation mode is preserved.
     * This keeps graph discovery synchronized with Hide Duplicates without making a transient
     * search box silently remove engineering tracks. Raw catalog/sample data is never mutated.
     */
    public List<SignalCatalogEntry> graphCatalog() {
        return SignalBrowserProjection.apply(catalog.get().entries(), browserViewMode.get());
    }

    public List<SignalSamplePoint> displayedSamples() {
        return displayedSamples.get();
    }

    public String statusNote() {
        return statusNote.get();
    }

    /** @deprecated Prefer catalog APIs; retained for vertical-slice buffer assertions. */
    public SignalPreviewBuffer buffer() {
        return buffer.get();
    }

    public void shutdown() {
        com.stellar.platform.desktop.ui.FxExecutorRelease.shutdown(worker);
    }

    private String browserMeasurementScope() {
        SignalBrowserFilter.State filter = browserFilter.get();
        return filter == null || filter.sourceFile() == null ? "" : filter.sourceFile().trim();
    }

    /**
     * All Measurements queries the logical key. A selected measurement reads only that
     * file's physical series while the workspace chip stays the logical signal.
     */
    private SignalCatalogKey scopedQueryKey(SignalSeriesStore store, SignalCatalogKey key) {
        if (key == null || !(store instanceof ProjectMergedSignalStore merged)) {
            return key;
        }
        String scope = browserMeasurementScope();
        if (!ProjectMergedSignalStore.restrictsToOneMeasurement(scope)) {
            return key;
        }
        if (ProjectMergedSignalStore.restrictsToOneMeasurement(key.sourceFile())
                && scope.equalsIgnoreCase(key.sourceFile().trim())) {
            return key;
        }
        String unit = findEntry(key).map(SignalCatalogEntry::unit).orElse("");
        for (SignalCatalogEntry entry : merged.physicalCatalog(scope, scope)) {
            SignalCatalogKey physical = entry.key();
            if (physical == null) {
                continue;
            }
            boolean same = physical.signalName().equals(key.signalName())
                    && physical.sourceType() == key.sourceType()
                    && physical.messageName().equals(key.messageName())
                    && physical.canId() == key.canId()
                    && physical.channel() == key.channel();
            if (!same) {
                continue;
            }
            if (!unit.isBlank() && entry.unit() != null && !entry.unit().isBlank()
                    && !unit.equals(entry.unit())) {
                continue;
            }
            return physical;
        }
        return key;
    }

    private long sampleCountFor(SignalSeriesStore store, SignalCatalogKey workspaceKey, SignalCatalogKey queryKey) {
        if (queryKey != workspaceKey && store instanceof ProjectMergedSignalStore merged) {
            String scope = browserMeasurementScope();
            for (SignalCatalogEntry entry : merged.physicalCatalog(scope, scope)) {
                if (queryKey.equals(entry.key())) {
                    return entry.sampleCount();
                }
            }
        }
        return findEntry(workspaceKey).map(SignalCatalogEntry::sampleCount).orElseGet(() ->
                store.snapshot().find(queryKey).map(SignalCatalogEntry::sampleCount).orElse(0L));
    }

    private SignalSamplePageSnapshot querySinglePage(
            SignalSeriesStore store,
            SignalCatalogKey key,
            int pageOffset,
            int pageSize
    ) {
        SignalCatalogKey queryKey = scopedQueryKey(store, key);
        long total = sampleCountFor(store, key, queryKey);
        int retained = store.retainedSampleCount(queryKey);
        long windowStart = store.retainedWindowStartIndex(queryKey);
        List<SignalSamplePoint> samples = store.querySamples(queryKey, pageOffset, pageSize);
        return new SignalSamplePageSnapshot(
                key,
                samples,
                pageOffset,
                pageSize,
                total,
                retained,
                windowStart
        );
    }

    private void refreshStatusNote() {
        refreshStatusNote(0, displayedSamples.get().size(), null);
    }

    private void refreshStatusNote(int selectedCount, int sampleRows) {
        refreshStatusNote(selectedCount, sampleRows, null);
    }

    private void refreshStatusNote(int selectedCount, int sampleRows, String processingHint) {
        SignalCatalog c = catalog.get();
        if (c.size() == 0) {
            statusNote.set("No signals yet. Process one measurement + one DBC to build the catalog.");
            return;
        }
        int shown = displayedCatalog.get().size();
        String base = "Available Signals: " + shown + " of " + c.size()
                + " (DBC " + c.dbcCanSignalCount()
                + ", MF4 native " + c.nativeMf4SignalCount() + ")";
        SignalSamplePageSnapshot page = displayedPage.get();
        if (selectedCount > 0 && page != null && !page.samples().isEmpty()) {
            base += " — " + SignalsSelectionPresentation.pageStatus(page);
        } else if (selectedCount > 0) {
            base += " — showing " + sampleRows + " sample row(s) for " + selectedCount
                    + " selected signal(s) (bounded page).";
        } else {
            base += " — select signal name(s) to load timestamp/value samples.";
        }
        if (processingHint != null && !processingHint.isBlank()) {
            String upper = processingHint.toUpperCase();
            if (upper.contains("MULTIPLEX") || upper.contains("MUX_NO_ACTIVE_LEAF")
                    || upper.contains("LEAF SIGNAL")) {
                base += " " + processingHint.trim();
            }
        }
        statusNote.set(base);
    }
    private static void closeStoreQuietly(SignalSeriesStore store) {
        if (store instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Graph cache is disposable. Replacement/clear must not break the Signals UI.
            }
        }
    }

    /** Rows addressable on the aligned timeline: the exact timestamp-union count. */
    private long activeIndexCount(SignalSeriesStore store, List<SignalCatalogKey> keys) {
        return Math.max(0, exactAlignedUnionCount(store, keys));
    }

    private static String indexOutOfRangeMessage(long upperBound) {
        if (upperBound <= 0L) {
            return "Index out of range";
        }
        return "Index out of range. Valid aligned indexes are 0 to " + (upperBound - 1L) + ".";
    }

    /** Exact aligned-row count for the current selection. Cached until the catalog revision or keys change. */
    private int exactAlignedUnionCount(SignalSeriesStore store, List<SignalCatalogKey> keys) {
        int revision = catalogRevision.get();
        UnionCountCache cached = alignedUnionCount.get();
        if (cached != null && cached.revision() == revision && cached.keys().equals(keys)) {
            return cached.count();
        }
        int count = AlignedSignalWorkspaceQuery.countExactUnion(store, keys);
        alignedUnionComputations.incrementAndGet();
        alignedUnionCount.set(new UnionCountCache(revision, keys, count));
        return count;
    }

    /** Union-count calculations. A cache hit does not increment this. */
    public int alignedUnionComputationsForTest() {
        return alignedUnionComputations.get();
    }

    private record UnionCountCache(int revision, List<SignalCatalogKey> keys, int count) {
    }

    /** One bounded aligned page retained so a recreated Signals view can paint immediately. */
    private record WorkspacePageCache(
            int revision,
            List<SignalCatalogKey> keys,
            int windowStart,
            int pageSize,
            AlignedWorkspaceSnapshot snapshot
    ) {
    }

}
