package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.catalog.SignalSeriesStore;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Presentation-only measurement identity over a processed catalog.
 * <p>
 * Never merges samples, never changes {@link SignalCatalogKey} identity, and never opens
 * measurement files. One catalog row remains one physical instance; browsing "All Measurements"
 * is a filter scope, not a concatenated waveform.
 */
public final class MeasurementCatalogScope {

    public static final String ALL_IDENTITY = "";

    /**
     * One processed MF4 recording as the Signals / Graphs selector understands it.
     *
     * @param identity   stable {@link SignalCatalogKey#sourceFile()} / workspace file id
     * @param displayName engineer-facing file name
     */
    public record Ref(String identity, String displayName, boolean allMeasurements, int measurementCount) {
        public Ref {
            identity = identity == null ? "" : identity.trim();
            displayName = displayName == null || displayName.isBlank() ? identity : displayName.trim();
            measurementCount = Math.max(0, measurementCount);
        }

        public static Ref all(int measurementCount) {
            int n = Math.max(0, measurementCount);
            return new Ref(ALL_IDENTITY, allMeasurementsLabel(n), true, n);
        }

        public String comboLabel() {
            if (allMeasurements) {
                return allMeasurementsLabel(measurementCount);
            }
            return displayName.isBlank() ? identity : displayName;
        }

        @Override
        public String toString() {
            return comboLabel();
        }
    }

    /**
     * Cheap provenance for one logical display-name signal across measurements.
     * Built from catalog metadata only — never sample rows.
     */
    public record CoverageRow(
            String identity,
            String displayName,
            int channel,
            String messageName,
            long canId,
            long sampleCount,
            SignalSourceType sourceType
    ) {
        public CoverageRow {
            identity = identity == null ? "" : identity;
            displayName = displayName == null || displayName.isBlank() ? identity : displayName;
            messageName = messageName == null ? "" : messageName;
        }

        public String channelLabel() {
            if (sourceType == SignalSourceType.DBC_CAN) {
                return "Ch " + channel;
            }
            return "Ch " + channel;
        }

        public String detailLabel() {
            StringBuilder sb = new StringBuilder(channelLabel());
            if (sourceType == SignalSourceType.DBC_CAN && messageName != null && !messageName.isBlank()) {
                sb.append("  ·  ").append(messageName);
            }
            if (sourceType == SignalSourceType.DBC_CAN && canId > 0) {
                sb.append("  ·  0x").append(Long.toHexString(canId).toUpperCase(Locale.ROOT));
            }
            if (sampleCount > 0) {
                sb.append("  ·  ")
                        .append(NumberFormat.getIntegerInstance(Locale.US).format(sampleCount))
                        .append(" samples");
            }
            return sb.toString();
        }
    }

    private MeasurementCatalogScope() {
    }

    public static String allMeasurementsLabel(int count) {
        int n = Math.max(0, count);
        return "All Measurements (" + n + ")";
    }

    public static int measurementCount(List<SignalCatalogEntry> catalog) {
        return measurements(catalog).size();
    }

    /**
     * Measurement choices for the Signals browser.
     * A merged project store keeps logical {@code PROJECT} rows whose display text is an
     * aggregate such as "9 measurements". Those rows are not measurements. The selector
     * uses each member file instead.
     */
    public static List<Ref> measurements(List<SignalCatalogEntry> catalog, SignalSeriesStore store) {
        if (store instanceof ProjectMergedSignalStore merged && !merged.measurementViews().isEmpty()) {
            List<ProjectMergedSignalStore.MeasurementView> views = merged.measurementViews();
            int n = views.size();
            List<Ref> refs = new ArrayList<>(n);
            for (ProjectMergedSignalStore.MeasurementView view : views) {
                String display = view.displayName() == null || view.displayName().isBlank()
                        ? view.identity()
                        : view.displayName();
                refs.add(new Ref(view.identity(), display, false, n));
            }
            return List.copyOf(refs);
        }
        return measurements(catalog);
    }

    public static int measurementCount(List<SignalCatalogEntry> catalog, SignalSeriesStore store) {
        return measurements(catalog, store).size();
    }

    public static List<Ref> measurements(List<SignalCatalogEntry> catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return List.of();
        }
        Map<String, Ref> byIdentity = new LinkedHashMap<>();
        for (SignalCatalogEntry entry : catalog) {
            if (entry == null) {
                continue;
            }
            String identity = identityOf(entry);
            String display = displayOf(entry);
            if (identity.isBlank() && display.isBlank()) {
                continue;
            }
            String key = !identity.isBlank() ? identity.toLowerCase(Locale.ROOT) : display.toLowerCase(Locale.ROOT);
            byIdentity.putIfAbsent(key, new Ref(identity.isBlank() ? display : identity, display, false, 0));
        }
        List<Ref> refs = new ArrayList<>(byIdentity.values());
        int n = refs.size();
        return refs.stream()
                .map(ref -> new Ref(ref.identity(), ref.displayName(), false, n))
                .toList();
    }

    public static boolean belongs(SignalCatalogEntry entry, Ref ref) {
        if (entry == null) {
            return false;
        }
        if (ref == null || ref.allMeasurements()) {
            return true;
        }
        return belongs(entry, ref.identity(), ref.displayName());
    }

    public static boolean belongs(SignalCatalogEntry entry, String identity, String displayName) {
        if (entry == null) {
            return false;
        }
        if ((identity == null || identity.isBlank()) && (displayName == null || displayName.isBlank())) {
            return true;
        }
        return matches(identityOf(entry), identity)
                || matches(displayOf(entry), identity)
                || matches(identityOf(entry), displayName)
                || matches(displayOf(entry), displayName);
    }

    public static boolean belongs(SignalCatalogKey key, String identity, String displayName) {
        if (key == null) {
            return false;
        }
        if ((identity == null || identity.isBlank()) && (displayName == null || displayName.isBlank())) {
            return true;
        }
        String keyFile = key.sourceFile() == null ? "" : key.sourceFile();
        return matches(keyFile, identity) || matches(keyFile, displayName) || matches(fileName(keyFile), displayName);
    }

    public static List<SignalCatalogEntry> filter(List<SignalCatalogEntry> catalog, Ref ref) {
        if (catalog == null) {
            return List.of();
        }
        if (ref == null || ref.allMeasurements()) {
            return List.copyOf(catalog);
        }
        return filter(catalog, ref.identity(), ref.displayName());
    }

    public static List<SignalCatalogEntry> filter(
            List<SignalCatalogEntry> catalog,
            String identity,
            String displayName
    ) {
        if (catalog == null) {
            return List.of();
        }
        if ((identity == null || identity.isBlank()) && (displayName == null || displayName.isBlank())) {
            return List.copyOf(catalog);
        }
        List<SignalCatalogEntry> out = new ArrayList<>();
        for (SignalCatalogEntry entry : catalog) {
            if (belongs(entry, identity, displayName)) {
                out.add(entry);
            }
        }
        return List.copyOf(out);
    }

    public static int physicalCount(List<SignalCatalogEntry> catalog, Ref ref) {
        return filter(catalog, ref).size();
    }

    /**
     * Honest count copy. "205" in this product is physical catalog entries (one
     * {@link SignalCatalogKey} each), not unique logical names. Hide Duplicates may
     * reduce the shown count without changing that identity.
     */
    public static String countCaption(int shown, int scopedPhysical, int measurementCount, boolean allMode) {
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        String scoped = nf.format(Math.max(0, scopedPhysical)) + " "
                + (scopedPhysical == 1 ? "signal" : "signals");
        if (allMode && measurementCount > 0) {
            scoped += " across " + measurementCount
                    + (measurementCount == 1 ? " measurement" : " measurements");
        } else if (!allMode) {
            scoped += " for 1 measurement";
        }
        if (shown == scopedPhysical) {
            return scoped;
        }
        return nf.format(Math.max(0, shown)) + " shown · " + scoped;
    }

    public static List<CoverageRow> coverage(SignalCatalogEntry selected, List<SignalCatalogEntry> catalog) {
        if (selected == null) {
            return List.of();
        }
        List<SignalCatalogEntry> entries = catalog == null ? List.of() : catalog;
        Map<String, CoverageRow> byMeasurement = new LinkedHashMap<>();
        for (SignalCatalogEntry entry : entries) {
            if (entry == null || !sameLogicalDisplay(selected, entry)) {
                continue;
            }
            String identity = identityOf(entry);
            String display = displayOf(entry);
            String key = !identity.isBlank() ? identity.toLowerCase(Locale.ROOT) : display.toLowerCase(Locale.ROOT);
            byMeasurement.putIfAbsent(key, new CoverageRow(
                    identity,
                    display,
                    entry.channel(),
                    entry.messageName(),
                    entry.canId(),
                    entry.sampleCount(),
                    entry.sourceType()
            ));
        }
        if (byMeasurement.isEmpty()) {
            return List.of(new CoverageRow(
                    identityOf(selected),
                    displayOf(selected),
                    selected.channel(),
                    selected.messageName(),
                    selected.canId(),
                    selected.sampleCount(),
                    selected.sourceType()
            ));
        }
        return List.copyOf(byMeasurement.values());
    }

    public static boolean sameLogicalDisplay(SignalCatalogEntry a, SignalCatalogEntry b) {
        if (a == null || b == null || a.key() == null || b.key() == null) {
            return false;
        }
        return a.signalName().equals(b.signalName())
                && a.sourceType() == b.sourceType()
                && a.messageName().equals(b.messageName())
                && a.canId() == b.canId();
    }

    public static String identityOf(SignalCatalogEntry entry) {
        if (entry == null) {
            return "";
        }
        if (entry.key() != null && entry.key().sourceFile() != null && !entry.key().sourceFile().isBlank()) {
            return entry.key().sourceFile();
        }
        return displayOf(entry);
    }

    public static String displayOf(SignalCatalogEntry entry) {
        if (entry == null) {
            return "";
        }
        if (entry.sourceFile() != null && !entry.sourceFile().isBlank()) {
            return entry.sourceFile();
        }
        return entry.key() == null || entry.key().sourceFile() == null ? "" : entry.key().sourceFile();
    }

    public static Set<String> identitiesOf(List<SignalCatalogKey> keys, List<SignalCatalogEntry> catalog) {
        Set<String> ids = new LinkedHashSet<>();
        if (keys == null) {
            return ids;
        }
        Map<SignalCatalogKey, SignalCatalogEntry> byKey = index(catalog);
        for (SignalCatalogKey key : keys) {
            if (key == null) {
                continue;
            }
            SignalCatalogEntry entry = byKey.get(key);
            if (entry != null) {
                String identity = identityOf(entry);
                if (!identity.isBlank()) {
                    ids.add(identity);
                }
            } else if (key.sourceFile() != null && !key.sourceFile().isBlank()) {
                ids.add(key.sourceFile());
            }
        }
        return ids;
    }

    /**
     * Resolve {@code requested} onto {@code scopedCatalog} by canonical physical identity.
     * <p>
     * Exact {@link SignalCatalogKey} match wins. A real measurement-owned key never remaps
     * onto a sibling measurement that merely shares the engineering name. Project-logical
     * {@code PROJECT} keys may take a unique engineering match only when the catalog itself
     * is unambiguous (typically a SINGLE-measurement catalog).
     */
    public static SignalCatalogEntry remapToMeasurement(
            SignalCatalogKey requested,
            List<SignalCatalogEntry> scopedCatalog
    ) {
        if (requested == null || scopedCatalog == null) {
            return null;
        }
        for (SignalCatalogEntry entry : scopedCatalog) {
            if (entry != null && requested.equals(entry.key())) {
                return entry;
            }
        }
        String requestedSource = requested.sourceFile() == null ? "" : requested.sourceFile().trim();
        boolean projectLogical = requestedSource.isEmpty()
                || ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE.equalsIgnoreCase(requestedSource);

        SignalCatalogEntry uniqueSameMeasurement = null;
        int sameMeasurementMatches = 0;
        SignalCatalogEntry uniqueEngineering = null;
        int engineeringMatches = 0;
        for (SignalCatalogEntry entry : scopedCatalog) {
            if (entry == null || entry.key() == null) {
                continue;
            }
            if (!sameEngineering(requested, entry.key())) {
                continue;
            }
            engineeringMatches++;
            uniqueEngineering = entry;
            if (!projectLogical && belongs(entry, requestedSource, requestedSource)) {
                sameMeasurementMatches++;
                uniqueSameMeasurement = entry;
            }
        }
        if (sameMeasurementMatches == 1) {
            return uniqueSameMeasurement;
        }
        if (projectLogical && engineeringMatches == 1) {
            return uniqueEngineering;
        }
        return null;
    }

    /**
     * Graph scope reconciliation for a merged project store.
     * <p>
     * {@link #remapToMeasurement} still refuses to rewrite one physical measurement onto a
     * sibling when the catalog contains distinct physical keys. This method is only for the
     * visible graph catalog: All Measurements rows are logical {@code PROJECT} keys, and a
     * single-measurement catalog exposes that measurement's physical series.
     * An ambiguous engineering match returns null so catalog order cannot choose a source.
     */
    public static SignalCatalogEntry reconcileVisibleScope(
            SignalCatalogKey requested,
            List<SignalCatalogEntry> scopedCatalog,
            boolean mergedProjectStore
    ) {
        SignalCatalogEntry exact = remapToMeasurement(requested, scopedCatalog);
        if (exact != null || !mergedProjectStore || requested == null || scopedCatalog == null) {
            return exact;
        }
        List<SignalCatalogEntry> matches = new ArrayList<>();
        for (SignalCatalogEntry entry : scopedCatalog) {
            if (entry != null && entry.key() != null && sameEngineering(requested, entry.key())) {
                matches.add(entry);
            }
        }
        if (matches.size() != 1) {
            return null;
        }
        SignalCatalogEntry only = matches.getFirst();
        String targetSource = sourceOf(only.key());
        if (isProjectLogicalSource(targetSource)) {
            return catalogIsLogical(scopedCatalog) ? only : null;
        }
        return catalogIsSinglePhysicalMeasurement(scopedCatalog, targetSource) ? only : null;
    }

    private static boolean catalogIsLogical(List<SignalCatalogEntry> catalog) {
        boolean any = false;
        for (SignalCatalogEntry entry : catalog) {
            if (entry == null || entry.key() == null) {
                continue;
            }
            any = true;
            if (!isProjectLogicalSource(sourceOf(entry.key()))) {
                return false;
            }
        }
        return any;
    }

    private static boolean catalogIsSinglePhysicalMeasurement(
            List<SignalCatalogEntry> catalog,
            String measurementSource
    ) {
        if (measurementSource.isEmpty() || isProjectLogicalSource(measurementSource)) {
            return false;
        }
        boolean any = false;
        for (SignalCatalogEntry entry : catalog) {
            if (entry == null || entry.key() == null) {
                continue;
            }
            any = true;
            if (!measurementSource.equals(sourceOf(entry.key()))) {
                return false;
            }
        }
        return any;
    }

    private static boolean isProjectLogicalSource(String source) {
        return source.isEmpty()
                || ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE.equalsIgnoreCase(source);
    }

    private static String sourceOf(SignalCatalogKey key) {
        return key == null || key.sourceFile() == null ? "" : key.sourceFile().trim();
    }

    private static boolean sameEngineering(SignalCatalogKey a, SignalCatalogKey b) {
        return a.signalName().equals(b.signalName())
                && a.sourceType() == b.sourceType()
                && a.messageName().equals(b.messageName())
                && a.canId() == b.canId()
                && a.channel() == b.channel();
    }

    private static Map<SignalCatalogKey, SignalCatalogEntry> index(List<SignalCatalogEntry> catalog) {
        Map<SignalCatalogKey, SignalCatalogEntry> map = new LinkedHashMap<>();
        if (catalog == null) {
            return map;
        }
        for (SignalCatalogEntry entry : catalog) {
            if (entry != null && entry.key() != null) {
                map.put(entry.key(), entry);
            }
        }
        return map;
    }

    private static boolean matches(String left, String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) {
            return false;
        }
        if (left.equalsIgnoreCase(right)) {
            return true;
        }
        return fileName(left).equalsIgnoreCase(fileName(right));
    }

    private static String fileName(String pathOrName) {
        if (pathOrName == null || pathOrName.isBlank()) {
            return "";
        }
        String normalized = pathOrName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 && slash < normalized.length() - 1 ? normalized.substring(slash + 1) : normalized;
    }
}
