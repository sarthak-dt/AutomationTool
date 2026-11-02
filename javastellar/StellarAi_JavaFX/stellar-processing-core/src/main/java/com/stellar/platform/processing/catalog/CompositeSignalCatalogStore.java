package com.stellar.platform.processing.catalog;

import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.stellar.platform.processing.graph.GraphQueryProvider;
import com.stellar.platform.processing.graph.GraphSeriesQuery;

/**
 * Zero-copy read view over independent signal stores produced by a multi-channel plan.
 * <p>
 * Physical sample windows remain owned by each member store. The composite only builds a
 * bounded metadata index and key-to-delegate routing. A member may provide stable source and
 * definition identities so two different project files with the same display filename can never
 * collide in the combined Signals workspace.
 */
public final class CompositeSignalCatalogStore implements SignalSeriesStore, GraphQueryProvider {

    /**
     * One independent processing result participating in the combined view.
     * Identity values are machine-stable; display values are user-facing metadata.
     */
    public record Member(
            SignalSeriesStore store,
            String measurementIdentity,
            String definitionIdentity,
            String measurementDisplayName,
            String definitionDisplayName
    ) {
        public Member {
            Objects.requireNonNull(store, "store");
            measurementIdentity = normalizeIdentity(measurementIdentity, "measurementIdentity");
            definitionIdentity = normalizeIdentity(definitionIdentity, "definitionIdentity");
            measurementDisplayName = safeDisplay(measurementDisplayName, measurementIdentity);
            definitionDisplayName = safeDisplay(definitionDisplayName, definitionIdentity);
        }

        private static String normalizeIdentity(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value.trim();
        }

        private static String safeDisplay(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    private record Route(SignalSeriesStore store, SignalCatalogKey delegateKey) {
    }

    private final SignalCatalog snapshot;
    private final Map<SignalCatalogKey, Route> routeByKey;
    private final int memberStoreCount;

    /**
     * Backward-compatible composition retaining each store's own key namespace.
     * Prefer {@link #fromMembers(List, String, String)} for project-level combined views.
     */
    public CompositeSignalCatalogStore(List<? extends SignalSeriesStore> stores) {
        this(stores, "", "");
    }

    /**
     * Backward-compatible composition retaining each store's own key namespace.
     */
    public CompositeSignalCatalogStore(
            List<? extends SignalSeriesStore> stores,
            String measurementLabel,
            String definitionLabel
    ) {
        Objects.requireNonNull(stores, "stores");
        List<SignalCatalogEntry> entries = new ArrayList<>();
        Map<SignalCatalogKey, Route> routes = new LinkedHashMap<>();
        IdentityHashMap<SignalSeriesStore, Boolean> seenStores = new IdentityHashMap<>();
        int members = 0;
        for (SignalSeriesStore store : stores) {
            if (store == null || seenStores.put(store, Boolean.TRUE) != null) {
                continue;
            }
            members++;
            appendOriginalNamespace(entries, routes, store);
        }
        this.routeByKey = Map.copyOf(routes);
        this.memberStoreCount = members;
        this.snapshot = new SignalCatalog(
                entries,
                measurementLabel == null ? "" : measurementLabel,
                definitionLabel == null ? "" : definitionLabel
        ).sortedByName();
    }

    private CompositeSignalCatalogStore(
            List<Member> members,
            String measurementLabel,
            String definitionLabel,
            boolean namespaceMembers
    ) {
        Objects.requireNonNull(members, "members");
        List<SignalCatalogEntry> entries = new ArrayList<>();
        Map<SignalCatalogKey, Route> routes = new LinkedHashMap<>();
        IdentityHashMap<SignalSeriesStore, Boolean> seenStoreObjects = new IdentityHashMap<>();
        int acceptedMembers = 0;

        for (Member member : members) {
            if (member == null) {
                continue;
            }
            // Repeating the exact same store object indicates duplicate result wiring; ignore it once.
            if (seenStoreObjects.put(member.store(), Boolean.TRUE) != null) {
                continue;
            }
            acceptedMembers++;
            SignalCatalog memberCatalog = Objects.requireNonNull(member.store().snapshot(), "store.snapshot()");
            for (SignalCatalogEntry entry : memberCatalog.entries()) {
                SignalCatalogKey originalKey = entry.key();
                SignalCatalogKey exposedKey = new SignalCatalogKey(
                        originalKey.signalName(),
                        originalKey.sourceType(),
                        originalKey.messageName(),
                        originalKey.canId(),
                        originalKey.channel(),
                        member.measurementIdentity(),
                        member.definitionIdentity()
                );
                Route existing = routes.putIfAbsent(exposedKey, new Route(member.store(), originalKey));
                if (existing != null) {
                    throw new IllegalArgumentException(
                            "Duplicate physical signal identity in combined processing results: " + exposedKey);
                }
                entries.add(new SignalCatalogEntry(
                        exposedKey,
                        entry.unit(),
                        entry.sampleCount(),
                        entry.firstTimestampNanos(),
                        entry.lastTimestampNanos(),
                        entry.minPhysical(),
                        entry.maxPhysical(),
                        member.measurementDisplayName(),
                        member.definitionDisplayName()
                ));
            }
        }

        this.routeByKey = Map.copyOf(routes);
        this.memberStoreCount = acceptedMembers;
        this.snapshot = new SignalCatalog(
                entries,
                measurementLabel == null ? "" : measurementLabel,
                definitionLabel == null ? "" : definitionLabel
        ).sortedByName();
    }

    public static CompositeSignalCatalogStore fromMembers(
            List<Member> members,
            String measurementLabel,
            String definitionLabel
    ) {
        return new CompositeSignalCatalogStore(members, measurementLabel, definitionLabel, true);
    }

    private static void appendOriginalNamespace(
            List<SignalCatalogEntry> entries,
            Map<SignalCatalogKey, Route> routes,
            SignalSeriesStore store
    ) {
        SignalCatalog catalog = Objects.requireNonNull(store.snapshot(), "store.snapshot()");
        for (SignalCatalogEntry entry : catalog.entries()) {
            Route existing = routes.putIfAbsent(entry.key(), new Route(store, entry.key()));
            if (existing != null && existing.store() != store) {
                throw new IllegalArgumentException(
                        "Duplicate physical signal key across independent stores: " + entry.key());
            }
            if (existing == null) {
                entries.add(entry);
            }
        }
    }

    @Override
    public SignalCatalog snapshot() {
        return snapshot;
    }

    @Override
    public List<SignalSamplePoint> querySamples(SignalCatalogKey key, int offset, int limit) {
        Objects.requireNonNull(key, "key");
        Route route = routeByKey.get(key);
        return route == null ? List.of() : route.store().querySamples(route.delegateKey(), offset, limit);
    }

    @Override
    public int retainedSampleCount(SignalCatalogKey key) {
        Objects.requireNonNull(key, "key");
        Route route = routeByKey.get(key);
        return route == null ? 0 : route.store().retainedSampleCount(route.delegateKey());
    }

    @Override
    public long retainedWindowStartIndex(SignalCatalogKey key) {
        Objects.requireNonNull(key, "key");
        Route route = routeByKey.get(key);
        return route == null ? 0L : route.store().retainedWindowStartIndex(route.delegateKey());
    }

    @Override
    public GraphSeriesQuery graphQuery() {
        return new GraphSeriesQuery() {
            @Override public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
                return queryRange(key, startNanos, endNanos, Integer.MAX_VALUE);
            }

            @Override public List<SignalSamplePoint> queryRange(
                    SignalCatalogKey key, long startNanos, long endNanos, int maxSamples) {
                Route route = routeByKey.get(Objects.requireNonNull(key, "key"));
                if (route == null || !(route.store() instanceof GraphQueryProvider provider)) return List.of();
                return provider.graphQuery().queryRange(route.delegateKey(), startNanos, endNanos, maxSamples);
            }
            @Override public String unit(SignalCatalogKey key) {
                Route route = routeByKey.get(Objects.requireNonNull(key, "key"));
                if (route == null || !(route.store() instanceof GraphQueryProvider provider)) return "";
                return provider.graphQuery().unit(route.delegateKey());
            }
            @Override public long sampleCount(SignalCatalogKey key) {
                Route route = routeByKey.get(Objects.requireNonNull(key, "key"));
                if (route == null || !(route.store() instanceof GraphQueryProvider provider)) return 0L;
                return provider.graphQuery().sampleCount(route.delegateKey());
            }
        };
    }

    public int physicalSignalCount() {
        return snapshot.size();
    }

    public int memberStoreCount() {
        return memberStoreCount;
    }
}
