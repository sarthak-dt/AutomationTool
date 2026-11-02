package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Client-side Signal Browser filters. Operates on catalog metadata only — never sample rows.
 */
public final class SignalBrowserFilter {

    public enum SourceFilter {
        ALL("All"),
        DBC("DBC"),
        MF4_NATIVE("Native"),
        DERIVED("Derived");

        private final String label;

        SourceFilter(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public record State(
            SourceFilter source,
            String message,
            String unit,
            String canId,
            boolean favoritesOnly,
            boolean recentOnly,
            String sourceFile,
            String dbcFile
    ) {
        public State {
            source = source == null ? SourceFilter.ALL : source;
            message = message == null ? "" : message;
            unit = unit == null ? "" : unit;
            canId = canId == null ? "" : canId;
            sourceFile = sourceFile == null ? "" : sourceFile;
            dbcFile = dbcFile == null ? "" : dbcFile;
        }

        /** Backward-compatible constructor used by existing call sites and tests. */
        public State(
                SourceFilter source,
                String message,
                String unit,
                String canId,
                boolean favoritesOnly,
                boolean recentOnly
        ) {
            this(source, message, unit, canId, favoritesOnly, recentOnly, "", "");
        }

        public static State none() {
            return new State(SourceFilter.ALL, "", "", "", false, false, "", "");
        }

        /** Count of structured Filters-panel criteria (excludes search / favorites / recent toggles). */
        public int structuredFilterCount() {
            int n = 0;
            if (source != SourceFilter.ALL) {
                n++;
            }
            if (!message.isBlank()) {
                n++;
            }
            if (!unit.isBlank()) {
                n++;
            }
            if (!canId.isBlank()) {
                n++;
            }
            if (!sourceFile.isBlank()) {
                n++;
            }
            if (!dbcFile.isBlank()) {
                n++;
            }
            return n;
        }

        /** Optional browser filters. Measurement scope is context, not a hidden filter. */
        public int optionalFilterCount() {
            int n = structuredFilterCount();
            if (!sourceFile.isBlank()) {
                n--;
            }
            return n;
        }
    }

    private SignalBrowserFilter() {
    }

    public static String sourceBadge(SignalSourceType type) {
        if (type == SignalSourceType.DBC_CAN) {
            return "DBC";
        }
        if (type == SignalSourceType.MF4_NATIVE) {
            return "MF4";
        }
        if (type == SignalSourceType.ANALYSIS_DERIVED) {
            return "DERIVED";
        }
        return type == null ? "" : type.name();
    }

    public static String sourceStyleClass(SignalSourceType type) {
        if (type == SignalSourceType.DBC_CAN) {
            return "source-badge-dbc";
        }
        if (type == SignalSourceType.MF4_NATIVE) {
            return "source-badge-native";
        }
        return "source-badge";
    }

    public static List<SignalCatalogEntry> apply(
            List<SignalCatalogEntry> entries,
            String query,
            State state,
            Set<SignalCatalogKey> favorites
    ) {
        return apply(entries, query, state, favorites, Set.of());
    }

    /**
     * Filters catalog entries by free-text query plus structured browser state.
     * Complexity is O(catalog size). Never touches sample rows or parsers.
     */
    public static List<SignalCatalogEntry> apply(
            List<SignalCatalogEntry> entries,
            String query,
            State state,
            Set<SignalCatalogKey> favorites,
            Set<SignalCatalogKey> recent
    ) {
        Objects.requireNonNull(entries, "entries");
        State s = state == null ? State.none() : state;
        Set<SignalCatalogKey> fav = favorites == null ? Set.of() : favorites;
        Set<SignalCatalogKey> rec = recent == null ? Set.of() : recent;
        String q = normalize(query);
        String message = normalize(s.message());
        String unit = normalize(s.unit());
        String canId = normalize(s.canId());
        List<SignalCatalogEntry> out = new ArrayList<>(Math.min(entries.size(), 256));
        for (SignalCatalogEntry e : entries) {
            if (s.favoritesOnly() && !fav.contains(e.key())) {
                continue;
            }
            if (s.recentOnly() && !rec.contains(e.key())) {
                continue;
            }
            if (s.source() == SourceFilter.DBC && e.sourceType() != SignalSourceType.DBC_CAN) {
                continue;
            }
            if (s.source() == SourceFilter.MF4_NATIVE && e.sourceType() != SignalSourceType.MF4_NATIVE) {
                continue;
            }
            if (s.source() == SourceFilter.DERIVED && e.sourceType() != SignalSourceType.ANALYSIS_DERIVED) {
                continue;
            }
            if (!normalize(s.sourceFile()).isEmpty()
                    && !MeasurementCatalogScope.belongs(e, s.sourceFile(), s.sourceFile())) {
                continue;
            }
            if (!normalize(s.dbcFile()).isEmpty()
                    && !dbcFileOf(e).toLowerCase(Locale.ROOT).contains(normalize(s.dbcFile()))) {
                continue;
            }
            if (!message.isEmpty() && !e.messageName().toLowerCase(Locale.ROOT).contains(message)) {
                continue;
            }
            if (!unit.isEmpty() && !e.unit().toLowerCase(Locale.ROOT).contains(unit)) {
                continue;
            }
            if (!canId.isEmpty() && !matchesCanId(e, canId)) {
                continue;
            }
            if (!q.isEmpty() && !matchesQuery(e, q)) {
                continue;
            }
            out.add(e);
        }
        return List.copyOf(out);
    }

    private static boolean matchesQuery(SignalCatalogEntry e, String q) {
        if (e.signalName().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        if (e.messageName().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        if (e.unit().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        if (sourceBadge(e.sourceType()).toLowerCase(Locale.ROOT).contains(q)
                || e.sourceType().name().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        return matchesCanId(e, q);
    }

    private static boolean matchesCanId(SignalCatalogEntry e, String query) {
        if (e.sourceType() == SignalSourceType.MF4_NATIVE) {
            return false;
        }
        String dec = Long.toString(e.canId());
        String hex = Long.toHexString(e.canId()).toLowerCase(Locale.ROOT);
        return dec.contains(query) || hex.contains(query) || ("0x" + hex).contains(query);
    }

    private static String sourceFileOf(SignalCatalogEntry e) {
        if (e.sourceFile() != null && !e.sourceFile().isBlank()) {
            return e.sourceFile();
        }
        return e.key() == null || e.key().sourceFile() == null ? "" : e.key().sourceFile();
    }

    private static String dbcFileOf(SignalCatalogEntry e) {
        if (e.sourceDatabase() != null && !e.sourceDatabase().isBlank()) {
            return e.sourceDatabase();
        }
        return e.key() == null || e.key().sourceDatabase() == null ? "" : e.key().sourceDatabase();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
