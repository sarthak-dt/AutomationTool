package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only classification of which catalog signals describe vehicle location.
 *
 * <p>Names alone are not enough: a real recording exposes its GPS fix quality as a bare
 * {@code QUALITY} channel, which no name rule can safely claim. So detection also uses the
 * signal's own provenance. Signals that share a catalog identity — the same MF4 channel group,
 * or the same CAN message on the same bus and file — form a cohort, and a cohort that is
 * demonstrably a GPS cohort lends its identity to its remaining members.
 *
 * <p>The index holds metadata only. It never reads samples, renames a signal, or creates a
 * second copy of a series: {@link #roleOf} is an annotation beside the original signal name.
 */
public final class GpsLocationSignalIndex {

    /** A cohort only vouches for unnamed members when GPS signals actually dominate it. */
    private static final double COHORT_DOMINANCE = 0.5;

    public static final GpsLocationSignalIndex EMPTY =
            new GpsLocationSignalIndex(Map.of(), Set.of());

    private final Map<SignalCatalogKey, GpsSignalRole> rolesByKey;
    private final Set<SignalCatalogKey> locationKeys;

    private GpsLocationSignalIndex(
            Map<SignalCatalogKey, GpsSignalRole> rolesByKey, Set<SignalCatalogKey> locationKeys) {
        this.rolesByKey = rolesByKey;
        this.locationKeys = locationKeys;
    }

    public static GpsLocationSignalIndex build(Collection<SignalCatalogEntry> catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return EMPTY;
        }

        Map<SignalCatalogKey, GpsSignalRole> roles = new LinkedHashMap<>();
        Map<CohortId, Cohort> cohorts = new HashMap<>();
        for (SignalCatalogEntry entry : catalog) {
            if (entry == null) {
                continue;
            }
            Optional<GpsSignalRole> role = GpsSignalRoleResolver.roleOf(entry);
            role.ifPresent(value -> roles.put(entry.key(), value));
            Cohort cohort = cohorts.computeIfAbsent(CohortId.of(entry.key()), ignored -> new Cohort());
            cohort.members.add(entry.key());
            role.ifPresent(cohort.roles::add);
        }
        if (roles.isEmpty()) {
            return EMPTY;
        }

        Set<SignalCatalogKey> located = new HashSet<>(roles.keySet());
        for (Cohort cohort : cohorts.values()) {
            if (cohort.vouchesForItsMembers()) {
                located.addAll(cohort.members);
            }
        }
        return new GpsLocationSignalIndex(Map.copyOf(roles), Set.copyOf(located));
    }

    /** True when the signal belongs to the recording's location/GPS set. */
    public boolean isLocationSignal(SignalCatalogEntry entry) {
        return entry != null && locationKeys.contains(entry.key());
    }

    /**
     * @return the role this signal plays, when its own name identifies one. A cohort member
     *         without an identifiable name is a location signal with no specific role.
     */
    public Optional<GpsSignalRole> roleOf(SignalCatalogEntry entry) {
        return entry == null ? Optional.empty() : Optional.ofNullable(rolesByKey.get(entry.key()));
    }

    /** Both coordinates were found, so a route can be built from this catalog. */
    public boolean hasRoute() {
        return rolesByKey.containsValue(GpsSignalRole.LATITUDE)
                && rolesByKey.containsValue(GpsSignalRole.LONGITUDE);
    }

    public boolean isEmpty() {
        return locationKeys.isEmpty();
    }

    public int size() {
        return locationKeys.size();
    }

    private static final class Cohort {
        private final List<SignalCatalogKey> members = new ArrayList<>();
        private final Set<GpsSignalRole> roles = EnumSet.noneOf(GpsSignalRole.class);

        private boolean vouchesForItsMembers() {
            if (!roles.contains(GpsSignalRole.LATITUDE) || !roles.contains(GpsSignalRole.LONGITUDE)) {
                return false;
            }
            return roles.size() > members.size() * COHORT_DOMINANCE;
        }
    }

    /**
     * Provenance shared by signals recorded together: one MF4 channel group, or one CAN message
     * on one bus. For native measurements {@code channel} is the channel-group record id.
     */
    private record CohortId(
            SignalSourceType sourceType, String messageName, long canId, int channel, String sourceFile) {

        private static CohortId of(SignalCatalogKey key) {
            Objects.requireNonNull(key, "key");
            return new CohortId(
                    key.sourceType(), key.messageName(), key.canId(), key.channel(), key.sourceFile());
        }
    }
}
