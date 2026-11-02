package com.stellar.platform.processing.catalog;

import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.graph.CancellableLodGraphSeriesQuery;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphQueryProvider;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.graph.LodGraphSeriesQuery;
import com.stellar.platform.processing.graph.MinMaxLodReducer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.BooleanSupplier;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Project-wide zero-copy signal catalog that collapses equivalent engineering signals
 * across measurement files into one logical catalog row.
 *
 * <p><b>Logical identity</b> (measurement source/file is intentionally excluded):
 * {@code sourceType + signalName + messageName + canId + channel + definitionIdentity}.
 * Same EngineRPM / OBD2 / 0x7E8 from File A and File B appears once; samples are merged
 * by ascending timestamp at query time.</p>
 *
 * <p>Physical samples remain in member stores. This class only indexes routes and performs
 * bounded k-way merges — it never copies entire series into heap.</p>
 */
public final class ProjectMergedSignalStore implements SignalSeriesStore, GraphQueryProvider {

    /** Stable sourceFile placeholder for project-scoped logical keys (not a real path). */
    public static final String PROJECT_SCOPE_SOURCE = "PROJECT";

    public record Member(
            SignalSeriesStore store,
            String measurementIdentity,
            String definitionIdentity,
            String measurementDisplayName,
            String definitionDisplayName
    ) {
        public Member {
            Objects.requireNonNull(store, "store");
            measurementIdentity = require(measurementIdentity, "measurementIdentity");
            definitionIdentity = require(definitionIdentity, "definitionIdentity");
            measurementDisplayName = blankTo(measurementDisplayName, measurementIdentity);
            definitionDisplayName = blankTo(definitionDisplayName, definitionIdentity);
        }

        private static String require(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value.trim();
        }

        private static String blankTo(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    private record Route(
            SignalSeriesStore store,
            SignalCatalogKey delegateKey,
            String measurementIdentity,
            String measurementDisplayName
    ) {
    }

    private final SignalCatalog snapshot;
    private final Map<SignalCatalogKey, List<Route>> routesByLogicalKey;
    private final int memberStoreCount;

    private ProjectMergedSignalStore(
            List<Member> members,
            String measurementLabel,
            String definitionLabel
    ) {
        Objects.requireNonNull(members, "members");
        Map<SignalCatalogKey, List<Route>> routes = new LinkedHashMap<>();
        Map<SignalCatalogKey, AggregatedMeta> meta = new LinkedHashMap<>();
        IdentityHashMap<SignalSeriesStore, Boolean> seen = new IdentityHashMap<>();
        int accepted = 0;

        for (Member member : members) {
            if (member == null || seen.put(member.store(), Boolean.TRUE) != null) {
                continue;
            }
            accepted++;
            SignalCatalog catalog = Objects.requireNonNull(member.store().snapshot(), "store.snapshot()");
            for (SignalCatalogEntry entry : catalog.entries()) {
                SignalCatalogKey original = entry.key();
                SignalCatalogKey logical = logicalKey(original, member.definitionIdentity(), entry.unit());
                routes.computeIfAbsent(logical, ignored -> new ArrayList<>())
                        .add(new Route(member.store(), original, member.measurementIdentity(),
                                member.measurementDisplayName()));
                meta.merge(logical, AggregatedMeta.from(entry, member.definitionDisplayName()), AggregatedMeta::combine);
            }
        }

        List<SignalCatalogEntry> entries = new ArrayList<>(meta.size());
        for (Map.Entry<SignalCatalogKey, AggregatedMeta> e : meta.entrySet()) {
            AggregatedMeta m = e.getValue();
            entries.add(new SignalCatalogEntry(
                    e.getKey(),
                    m.unit,
                    m.sampleCount,
                    m.firstTimestampNanos,
                    m.lastTimestampNanos,
                    m.minPhysical,
                    m.maxPhysical,
                    measurementLabel == null || measurementLabel.isBlank() ? "Project" : measurementLabel,
                    m.definitionDisplayName
            ));
        }

        this.routesByLogicalKey = freezeRoutes(routes);
        this.memberStoreCount = accepted;
        this.snapshot = new SignalCatalog(
                entries,
                measurementLabel == null ? "" : measurementLabel,
                definitionLabel == null ? "" : definitionLabel
        ).sortedByName();
    }

    public static ProjectMergedSignalStore fromMembers(
            List<Member> members,
            String measurementLabel,
            String definitionLabel
    ) {
        return new ProjectMergedSignalStore(members, measurementLabel, definitionLabel);
    }

    /**
     * Engineering logical identity: excludes measurement file identity so the same signal
     * across files collapses to one catalog row.
     */
    public static SignalCatalogKey logicalKey(SignalCatalogKey physical, String definitionIdentity) {
        return logicalKey(physical, definitionIdentity, "");
    }

    /**
     * Engineering logical identity. Measurement/source id is excluded so the same signal
     * across files is one row. Unit stays in the signature so same-named signals with
     * different units are not combined.
     */
    public static SignalCatalogKey logicalKey(
            SignalCatalogKey physical,
            String definitionIdentity,
            String unit
    ) {
        Objects.requireNonNull(physical, "physical");
        String definition = definitionIdentity == null || definitionIdentity.isBlank()
                ? physical.sourceDatabase()
                : definitionIdentity.trim();
        String normalizedUnit = unit == null ? "" : unit.trim();
        if (!normalizedUnit.isEmpty()) {
            definition = definition + "\u001f" + normalizedUnit;
        }
        return new SignalCatalogKey(
                physical.signalName(),
                physical.sourceType(),
                physical.messageName(),
                physical.canId(),
                physical.channel(),
                PROJECT_SCOPE_SOURCE,
                definition
        );
    }

    /** True when two physical keys describe the same engineering signal ignoring source file. */
    public static boolean sameLogicalEngineeringSignal(SignalCatalogKey a, SignalCatalogKey b) {
        if (a == null || b == null) {
            return false;
        }
        return a.signalName().equals(b.signalName())
                && a.sourceType() == b.sourceType()
                && a.messageName().equals(b.messageName())
                && a.canId() == b.canId()
                && a.channel() == b.channel()
                && Objects.equals(
                a.sourceDatabase() == null ? "" : a.sourceDatabase(),
                b.sourceDatabase() == null ? "" : b.sourceDatabase());
    }

    private static Map<SignalCatalogKey, List<Route>> freezeRoutes(Map<SignalCatalogKey, List<Route>> routes) {
        Map<SignalCatalogKey, List<Route>> out = new LinkedHashMap<>();
        for (Map.Entry<SignalCatalogKey, List<Route>> e : routes.entrySet()) {
            out.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Map.copyOf(out);
    }

    @Override
    public SignalCatalog snapshot() {
        return snapshot;
    }

    /**
     * One forward pass per member file. {@link #querySamples} restarts a merge from the first
     * sample on every page, so a full-series walk must not be built from those pages.
     */
    @Override
    public void forEachSample(SignalCatalogKey key, java.util.function.Consumer<SignalSamplePoint> consumer) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(consumer, "consumer");
        for (Route route : routesForQuery(key)) {
            route.store().forEachSample(route.delegateKey(), consumer);
        }
    }

    @Override
    public List<SignalSamplePoint> querySamples(SignalCatalogKey key, int offset, int limit) {
        Objects.requireNonNull(key, "key");
        List<Route> routes = routesForQuery(key);
        if (routes.isEmpty()) {
            return List.of();
        }
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, limit);
        if (routes.size() == 1) {
            Route only = routes.getFirst();
            return only.store().querySamples(only.delegateKey(), safeOffset, safeLimit);
        }
        return TimestampOrderedSampleMerge.mergeFromRoutes(routes, safeOffset, safeLimit);
    }

    @Override
    public int retainedSampleCount(SignalCatalogKey key) {
        Objects.requireNonNull(key, "key");
        List<Route> routes = routesForQuery(key);
        if (routes.isEmpty()) {
            return 0;
        }
        long total = 0L;
        for (Route route : routes) {
            total += Math.max(0, route.store().retainedSampleCount(route.delegateKey()));
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * Cheap route health check: catalog {@code sampleCount > 0} must resolve to at least one
     * live member series ({@code retainedSampleCount > 0}). Does not load sample payloads.
     */
    public boolean hasResolvableSamples(SignalCatalogKey key) {
        if (key == null) {
            return false;
        }
        List<Route> routes = resolveRoutes(key);
        if (routes.isEmpty()) {
            return false;
        }
        for (Route route : routes) {
            try {
                if (route.store().retainedSampleCount(route.delegateKey()) > 0) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Closed/corrupt member — treat as unresolved for this route.
            }
        }
        return false;
    }

    /**
     * Resolves project-logical keys and leftover physical keys (View Signals → Explore)
     * to the same route list so sample queries never miss a catalog row that still exists.
     */
    private List<Route> resolveRoutes(SignalCatalogKey key) {
        List<Route> direct = routesByLogicalKey.get(key);
        if (direct != null && !direct.isEmpty()) {
            return direct;
        }
        if (restrictsToOneMeasurement(key.sourceFile())) {
            for (List<Route> routes : routesByLogicalKey.values()) {
                if (routes == null || routes.isEmpty()) {
                    continue;
                }
                boolean ownsKey = false;
                for (Route route : routes) {
                    if (!routeBelongsToSource(route, key.sourceFile())) {
                        continue;
                    }
                    if (key.equals(route.delegateKey())
                            || sameLogicalEngineeringSignal(key, route.delegateKey())
                            || sameLogicalEngineeringSignal(key, physicalKey(route))) {
                        ownsKey = true;
                        break;
                    }
                }
                if (ownsKey) {
                    return routes;
                }
            }
        }
        if (!PROJECT_SCOPE_SOURCE.equals(key.sourceFile())) {
            SignalCatalogKey asLogical = logicalKey(key, key.sourceDatabase());
            List<Route> byLogical = routesByLogicalKey.get(asLogical);
            if (byLogical != null && !byLogical.isEmpty()) {
                return byLogical;
            }
        }
        for (Map.Entry<SignalCatalogKey, List<Route>> entry : routesByLogicalKey.entrySet()) {
            SignalCatalogKey logical = entry.getKey();
            List<Route> routes = entry.getValue();
            if (routes == null || routes.isEmpty()) {
                continue;
            }
            if (sameLogicalEngineeringSignal(key, logical)
                    || (PROJECT_SCOPE_SOURCE.equals(logical.sourceFile())
                    && key.signalName().equals(logical.signalName())
                    && key.sourceType() == logical.sourceType()
                    && key.messageName().equals(logical.messageName())
                    && key.canId() == logical.canId()
                    && key.channel() == logical.channel())) {
                return routes;
            }
            for (Route route : routes) {
                if (key.equals(route.delegateKey())
                        || sameLogicalEngineeringSignal(key, route.delegateKey())) {
                    return routes;
                }
            }
        }
        return List.of();
    }

    /** Logical keys merge every member. A measurement-scoped key reads only that member. */
    private List<Route> routesForQuery(SignalCatalogKey key) {
        List<Route> routes = resolveRoutes(key);
        if (!restrictsToOneMeasurement(key.sourceFile())) {
            return routes;
        }
        return filterRoutesBySource(routes, key.sourceFile());
    }

    @Override
    public long retainedWindowStartIndex(SignalCatalogKey key) {
        return 0L;
    }

    @Override
    public GraphSeriesQuery graphQuery() {
        return new MergedGraphQuery();
    }

    /**
     * Bucket queries stay on the member store. A physical key reads one file.
     * An unscoped key queries every member at the full bucket budget and does not drop a file.
     */
    private final class MergedGraphQuery implements CancellableLodGraphSeriesQuery {
        @Override
        public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
            return queryRange(key, startNanos, endNanos, Integer.MAX_VALUE);
        }

        @Override
        public List<SignalSamplePoint> queryRange(
                SignalCatalogKey key, long startNanos, long endNanos, int maxSamples) {
            return queryMergedRange(key, startNanos, endNanos, maxSamples, null);
        }

        @Override
        public List<SignalSamplePoint> queryRange(
                SignalCatalogKey key, long startNanos, long endNanos, String sourceScope) {
            return queryMergedRange(key, startNanos, endNanos, Integer.MAX_VALUE, sourceScope);
        }

        @Override
        public List<SignalSamplePoint> queryRange(
                SignalCatalogKey key, long startNanos, long endNanos, BooleanSupplier continueQuery) {
            if (continueQuery != null && !continueQuery.getAsBoolean()) {
                return List.of();
            }
            return queryRange(key, startNanos, endNanos);
        }

        @Override
        public List<GraphBucket> queryBuckets(
                SignalCatalogKey key, long startNanos, long endNanos, int targetBuckets) {
            return queryBuckets(key, startNanos, endNanos, targetBuckets, () -> true);
        }

        @Override
        public List<GraphBucket> queryBuckets(
                SignalCatalogKey key, long startNanos, long endNanos, int targetBuckets, BooleanSupplier continueQuery) {
            List<Route> routes = routesForQuery(key);
            if (routes.isEmpty()) {
                return List.of();
            }
            int budget = Math.max(1, targetBuckets);
            BooleanSupplier cont = continueQuery == null ? () -> true : continueQuery;
            List<GraphBucket> all = new ArrayList<>();
            for (Route route : routes) {
                if (!cont.getAsBoolean()) {
                    return List.of();
                }
                all.addAll(bucketsOf(route, startNanos, endNanos, budget, cont));
            }
            return List.copyOf(all);
        }

        @Override
        public String unit(SignalCatalogKey key) {
            return snapshot.find(key).map(SignalCatalogEntry::unit).orElseGet(() -> unitFromRoutes(key));
        }

        @Override
        public long sampleCount(SignalCatalogKey key) {
            return snapshot.find(key).map(SignalCatalogEntry::sampleCount).orElseGet(() -> sampleCountFromRoutes(key));
        }
    }

    private static List<GraphBucket> bucketsOf(
            Route route, long startNanos, long endNanos, int budget, BooleanSupplier continueQuery) {
        if (route.store() instanceof GraphQueryProvider provider) {
            GraphSeriesQuery member = provider.graphQuery();
            if (member instanceof CancellableLodGraphSeriesQuery lod) {
                return lod.queryBuckets(route.delegateKey(), startNanos, endNanos, budget, continueQuery);
            }
            if (member instanceof LodGraphSeriesQuery lod) {
                return lod.queryBuckets(route.delegateKey(), startNanos, endNanos, budget);
            }
            return MinMaxLodReducer.reduce(member.queryRange(route.delegateKey(), startNanos, endNanos), budget);
        }
        return MinMaxLodReducer.reduce(
                route.store().queryTimestampRange(route.delegateKey(), startNanos, endNanos, Integer.MAX_VALUE),
                budget);
    }

    public int logicalSignalCount() {
        return snapshot.size();
    }

    public int memberStoreCount() {
        return memberStoreCount;
    }

    /**
     * One processed measurement as the Signals selector should show it.
     * Display name is the engineer-facing file name, never the aggregate "N measurements" label.
     */
    public record MeasurementView(String identity, String displayName) {
    }

    public List<MeasurementView> measurementViews() {
        LinkedHashMap<String, String> byIdentity = new LinkedHashMap<>();
        for (List<Route> routes : routesByLogicalKey.values()) {
            for (Route route : routes) {
                byIdentity.putIfAbsent(route.measurementIdentity(), route.measurementDisplayName());
            }
        }
        List<MeasurementView> views = new ArrayList<>(byIdentity.size());
        for (Map.Entry<String, String> entry : byIdentity.entrySet()) {
            views.add(new MeasurementView(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(views);
    }

    public int physicalRouteCount(SignalCatalogKey logicalKey) {
        List<Route> routes = routesByLogicalKey.get(logicalKey);
        return routes == null ? 0 : routes.size();
    }

    /**
     * Complete unprojected catalog for one engineering measurement.
     * Logical {@code PROJECT} snapshot rows are presentation-only; analysis keys use
     * {@link Member#measurementIdentity()} as {@link SignalCatalogKey#sourceFile()}.
     */
    public List<SignalCatalogEntry> physicalCatalog(String identity, String displayName) {
        LinkedHashMap<SignalCatalogKey, SignalCatalogEntry> out = new LinkedHashMap<>();
        for (List<Route> routes : routesByLogicalKey.values()) {
            for (Route route : routes) {
                if (!matchesMeasurement(route, identity, displayName)) {
                    continue;
                }
                SignalCatalogEntry memberEntry = route.store().snapshot().find(route.delegateKey()).orElse(null);
                if (memberEntry == null) {
                    continue;
                }
                SignalCatalogKey physical = physicalKey(route);
                out.putIfAbsent(physical, new SignalCatalogEntry(
                        physical,
                        memberEntry.unit(),
                        memberEntry.sampleCount(),
                        memberEntry.firstTimestampNanos(),
                        memberEntry.lastTimestampNanos(),
                        memberEntry.minPhysical(),
                        memberEntry.maxPhysical(),
                        route.measurementDisplayName(),
                        memberEntry.sourceDatabase()
                ));
            }
        }
        return List.copyOf(out.values());
    }

    /**
     * Unprojected physical catalog for every member of this project store.
     * Same display names stay separate {@link SignalCatalogKey}s.
     */
    public List<SignalCatalogEntry> physicalCatalogAll() {
        LinkedHashMap<SignalCatalogKey, SignalCatalogEntry> out = new LinkedHashMap<>();
        for (List<Route> routes : routesByLogicalKey.values()) {
            for (Route route : routes) {
                SignalCatalogEntry memberEntry = route.store().snapshot().find(route.delegateKey()).orElse(null);
                if (memberEntry == null) {
                    continue;
                }
                SignalCatalogKey physical = physicalKey(route);
                out.putIfAbsent(physical, new SignalCatalogEntry(
                        physical,
                        memberEntry.unit(),
                        memberEntry.sampleCount(),
                        memberEntry.firstTimestampNanos(),
                        memberEntry.lastTimestampNanos(),
                        memberEntry.minPhysical(),
                        memberEntry.maxPhysical(),
                        route.measurementDisplayName(),
                        memberEntry.sourceDatabase()
                ));
            }
        }
        return List.copyOf(out.values());
    }

    /**
     * Canonical measurement identities that still own samples for {@code key}.
     * A persisted physical key stays bound to that one member; a project-logical key
     * reports every member that contributes the same engineering signal.
     */
    /**
     * The member key for one recording. File Focus analysis uses this so overlapping
     * stored clocks from other recordings stay out of the calculation.
     */
    public java.util.Optional<SignalCatalogKey> physicalKeyFor(SignalCatalogKey key, String measurementId) {
        if (key == null || !restrictsToOneMeasurement(measurementId)) {
            return java.util.Optional.empty();
        }
        for (Route route : resolveRoutes(key)) {
            if (routeBelongsToSource(route, measurementId)) {
                return java.util.Optional.of(physicalKey(route));
            }
        }
        return java.util.Optional.empty();
    }

    public Set<String> physicalIdentities(SignalCatalogKey key) {
        if (key == null) {
            return Set.of();
        }
        String source = key.sourceFile() == null ? "" : key.sourceFile().trim();
        List<Route> routes = resolveRoutes(key);
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (!source.isEmpty() && !PROJECT_SCOPE_SOURCE.equalsIgnoreCase(source)) {
            for (Route route : routes) {
                if (routeBelongsToSource(route, source)) {
                    ids.add(route.measurementIdentity());
                }
            }
            if (ids.isEmpty()) {
                ids.add(source);
            }
            return Set.copyOf(ids);
        }
        for (Route route : routes) {
            ids.add(route.measurementIdentity());
        }
        return Set.copyOf(ids);
    }

    public List<String> memberIdentities() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (List<Route> routes : routesByLogicalKey.values()) {
            for (Route route : routes) {
                ids.add(route.measurementIdentity());
            }
        }
        return List.copyOf(ids);
    }

    private static SignalCatalogKey physicalKey(Route route) {
        SignalCatalogKey delegate = route.delegateKey();
        return new SignalCatalogKey(
                delegate.signalName(),
                delegate.sourceType(),
                delegate.messageName(),
                delegate.canId(),
                delegate.channel(),
                route.measurementIdentity(),
                delegate.sourceDatabase()
        );
    }

    private static boolean matchesMeasurement(Route route, String identity, String displayName) {
        return routeBelongsToSource(route, identity) || routeBelongsToSource(route, displayName);
    }

    private String unitFromRoutes(SignalCatalogKey key) {
        List<Route> routes = routesForQuery(key);
        if (routes.isEmpty()) {
            return "";
        }
        Route first = routes.getFirst();
        return first.store().snapshot().find(first.delegateKey()).map(SignalCatalogEntry::unit).orElse("");
    }

    private long sampleCountFromRoutes(SignalCatalogKey key) {
        long total = 0L;
        for (Route route : routesForQuery(key)) {
            SignalCatalogEntry entry = route.store().snapshot().find(route.delegateKey()).orElse(null);
            if (entry != null) {
                total += entry.sampleCount();
            }
        }
        return total;
    }

    /**
     * Project-wide timestamp merge, optionally restricted to one measurement.
     *
     * <p>Unscoped queries keep overlaying equivalent signals on a shared time axis (graphs).
     * A restricting {@code sourceScope} is required for GPS: independent MF4 files reset their
     * timebase, and merging them produces a geographically impossible track.
     */
    List<SignalSamplePoint> queryMergedRange(
            SignalCatalogKey key,
            long startNanos,
            long endNanos,
            int maxSamples,
            String sourceScope
    ) {
        List<Route> routes = resolveRoutes(Objects.requireNonNull(key, "key"));
        if (routes.isEmpty()) {
            return List.of();
        }
        String effectiveScope = restrictsToOneMeasurement(sourceScope)
                ? sourceScope
                : (sourceScope == null || sourceScope.isBlank()) && restrictsToOneMeasurement(key.sourceFile())
                ? key.sourceFile()
                : null;
        if (effectiveScope != null) {
            routes = filterRoutesBySource(routes, effectiveScope);
            if (routes.isEmpty()) {
                return List.of();
            }
        }
        int limit = Math.max(1, maxSamples);
        List<List<SignalSamplePoint>> pages = new ArrayList<>(routes.size());
        for (Route route : routes) {
            if (route.store() instanceof GraphQueryProvider provider) {
                pages.add(provider.graphQuery().queryRange(
                        route.delegateKey(), startNanos, endNanos, limit));
            } else {
                pages.add(route.store().queryTimestampRange(
                        route.delegateKey(), startNanos, endNanos, limit));
            }
        }
        return TimestampOrderedSampleMerge.mergeOrderedPages(pages, limit);
    }

    public static boolean restrictsToOneMeasurement(String sourceScope) {
        if (sourceScope == null || sourceScope.isBlank()) {
            return false;
        }
        String scope = sourceScope.trim();
        return !PROJECT_SCOPE_SOURCE.equalsIgnoreCase(scope)
                && !"project".equalsIgnoreCase(scope)
                && !"Measurement loaded".equalsIgnoreCase(scope);
    }

    static List<Route> filterRoutesBySource(List<Route> routes, String sourceScope) {
        if (routes == null || routes.isEmpty()) {
            return List.of();
        }
        List<Route> matched = new ArrayList<>();
        for (Route route : routes) {
            if (routeBelongsToSource(route, sourceScope)) {
                matched.add(route);
            }
        }
        return matched;
    }

    static boolean routeBelongsToSource(Route route, String sourceScope) {
        if (route == null || sourceScope == null || sourceScope.isBlank()) {
            return false;
        }
        String scope = sourceScope.trim();
        if (equalsIgnoreCase(route.measurementIdentity(), scope)
                || equalsIgnoreCase(route.measurementDisplayName(), scope)
                || equalsIgnoreCase(route.delegateKey().sourceFile(), scope)) {
            return true;
        }
        return fileNameEquals(route.measurementDisplayName(), scope)
                || fileNameEquals(route.delegateKey().sourceFile(), scope);
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }

    private static boolean fileNameEquals(String pathOrName, String scope) {
        String name = fileName(pathOrName);
        String scopeName = fileName(scope);
        return !name.isEmpty() && name.equalsIgnoreCase(scopeName);
    }

    private static String fileName(String pathOrName) {
        if (pathOrName == null || pathOrName.isBlank()) {
            return "";
        }
        int slash = Math.max(pathOrName.lastIndexOf('/'), pathOrName.lastIndexOf('\\'));
        return slash >= 0 ? pathOrName.substring(slash + 1) : pathOrName;
    }

    /**
     * Bounded k-way merge of timestamp-ordered sample pages from independent stores.
     * Complexity: O(N log K) where N is emitted samples and K is route count.
     */
    public static final class TimestampOrderedSampleMerge {

        private TimestampOrderedSampleMerge() {
        }

        static List<SignalSamplePoint> mergeFromRoutes(List<Route> routes, int offset, int limit) {
            // Bounded k-way merge over timestamp-ordered delegates.
            // Complexity O((offset+limit) log K); page size stays capped — never loads full series.
            int safeOffset = Math.max(0, offset);
            int safeLimit = Math.max(1, limit);
            int pageChunk = Math.max(256, safeLimit);
            PriorityQueue<StoreCursor> heap = new PriorityQueue<>(Comparator
                    .comparingLong((StoreCursor c) -> c.point().timestampNanos())
                    .thenComparing(c -> c.route().measurementIdentity() == null
                            ? "" : c.route().measurementIdentity())
                    .thenComparingInt(StoreCursor::routeIndex));
            for (int i = 0; i < routes.size(); i++) {
                StoreCursor cursor = StoreCursor.open(i, routes.get(i), pageChunk);
                if (cursor != null) {
                    heap.offer(cursor);
                }
            }
            List<SignalSamplePoint> out = new ArrayList<>(safeLimit);
            SignalSamplePoint previous = null;
            int seen = 0;
            while (!heap.isEmpty() && out.size() < safeLimit) {
                StoreCursor cursor = heap.poll();
                SignalSamplePoint point = cursor.point();
                boolean emit = previous == null || !exactDuplicate(previous, point);
                if (emit) {
                    if (seen >= safeOffset) {
                        out.add(point);
                    }
                    seen++;
                    previous = point;
                }
                StoreCursor next = cursor.advance(pageChunk);
                if (next != null) {
                    heap.offer(next);
                }
            }
            return List.copyOf(out);
        }

        private record StoreCursor(
                int routeIndex,
                Route route,
                List<SignalSamplePoint> page,
                int pageIndex,
                int storeOffset
        ) {
            static StoreCursor open(int routeIndex, Route route, int pageChunk) {
                List<SignalSamplePoint> page = route.store().querySamples(route.delegateKey(), 0, pageChunk);
                if (page.isEmpty()) {
                    return null;
                }
                return new StoreCursor(routeIndex, route, page, 0, page.size());
            }

            SignalSamplePoint point() {
                return page.get(pageIndex);
            }

            StoreCursor advance(int pageChunk) {
                int nextIdx = pageIndex + 1;
                if (nextIdx < page.size()) {
                    return new StoreCursor(routeIndex, route, page, nextIdx, storeOffset);
                }
                List<SignalSamplePoint> nextPage =
                        route.store().querySamples(route.delegateKey(), storeOffset, pageChunk);
                if (nextPage.isEmpty()) {
                    return null;
                }
                return new StoreCursor(routeIndex, route, nextPage, 0, storeOffset + nextPage.size());
            }
        }

        /**
         * K-way merge. Collapses exact duplicates (same timestamp + same physical value).
         * Same timestamp with different values are both retained (data integrity).
         */
        public static List<SignalSamplePoint> mergeOrderedPages(List<List<SignalSamplePoint>> pages, int limit) {
            if (pages == null || pages.isEmpty() || limit <= 0) {
                return List.of();
            }
            PriorityQueue<Cursor> heap = new PriorityQueue<>(Comparator
                    .comparingLong((Cursor c) -> c.point().timestampNanos())
                    .thenComparingDouble(c -> c.point().physicalValue())
                    .thenComparingInt(Cursor::pageIndex));
            for (int i = 0; i < pages.size(); i++) {
                List<SignalSamplePoint> page = pages.get(i);
                if (page != null && !page.isEmpty()) {
                    heap.offer(new Cursor(i, 0, page));
                }
            }
            List<SignalSamplePoint> out = new ArrayList<>(Math.min(limit, 1024));
            SignalSamplePoint previous = null;
            while (!heap.isEmpty() && out.size() < limit) {
                Cursor cursor = heap.poll();
                SignalSamplePoint point = cursor.point();
                if (previous == null || !exactDuplicate(previous, point)) {
                    out.add(point);
                    previous = point;
                }
                Cursor next = cursor.advance();
                if (next != null) {
                    heap.offer(next);
                }
            }
            return List.copyOf(out);
        }

        private static boolean exactDuplicate(SignalSamplePoint a, SignalSamplePoint b) {
            return a.timestampNanos() == b.timestampNanos()
                    && Double.compare(a.physicalValue(), b.physicalValue()) == 0
                    && Objects.equals(a.rawValue(), b.rawValue())
                    && Objects.equals(a.textualValue(), b.textualValue());
        }

        private record Cursor(int pageIndex, int index, List<SignalSamplePoint> page) {
            SignalSamplePoint point() {
                return page.get(index);
            }

            Cursor advance() {
                int next = index + 1;
                return next < page.size() ? new Cursor(pageIndex, next, page) : null;
            }
        }
    }

    private static final class AggregatedMeta {
        private final String unit;
        private final long sampleCount;
        private final Long firstTimestampNanos;
        private final Long lastTimestampNanos;
        private final Double minPhysical;
        private final Double maxPhysical;
        private final String definitionDisplayName;

        private AggregatedMeta(
                String unit,
                long sampleCount,
                Long firstTimestampNanos,
                Long lastTimestampNanos,
                Double minPhysical,
                Double maxPhysical,
                String definitionDisplayName
        ) {
            this.unit = unit == null ? "" : unit;
            this.sampleCount = sampleCount;
            this.firstTimestampNanos = firstTimestampNanos;
            this.lastTimestampNanos = lastTimestampNanos;
            this.minPhysical = minPhysical;
            this.maxPhysical = maxPhysical;
            this.definitionDisplayName = definitionDisplayName == null ? "" : definitionDisplayName;
        }

        static AggregatedMeta from(SignalCatalogEntry entry, String definitionDisplayName) {
            return new AggregatedMeta(
                    entry.unit(),
                    entry.sampleCount(),
                    entry.firstTimestampNanos(),
                    entry.lastTimestampNanos(),
                    entry.minPhysical(),
                    entry.maxPhysical(),
                    definitionDisplayName
            );
        }

        AggregatedMeta combine(AggregatedMeta other) {
            return new AggregatedMeta(
                    unit.isBlank() ? other.unit : unit,
                    sampleCount + other.sampleCount,
                    minNullable(firstTimestampNanos, other.firstTimestampNanos),
                    maxNullable(lastTimestampNanos, other.lastTimestampNanos),
                    minNullable(minPhysical, other.minPhysical),
                    maxNullable(maxPhysical, other.maxPhysical),
                    definitionDisplayName.isBlank() ? other.definitionDisplayName : definitionDisplayName
            );
        }

        private static Long minNullable(Long a, Long b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            return Math.min(a, b);
        }

        private static Long maxNullable(Long a, Long b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            return Math.max(a, b);
        }

        private static Double minNullable(Double a, Double b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            return Math.min(a, b);
        }

        private static Double maxNullable(Double a, Double b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            return Math.max(a, b);
        }
    }
}
