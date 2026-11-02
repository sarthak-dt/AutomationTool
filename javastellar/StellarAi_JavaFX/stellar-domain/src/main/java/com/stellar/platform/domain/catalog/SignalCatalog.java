package com.stellar.platform.domain.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable snapshot of distinct engineering signals after ONE measurement + ONE DBC (or more later).
 * Search operates on catalog metadata only — never on raw sample rows.
 * <p>
 * Storage of sample series is intentionally separate so SQLite/paged backends can replace
 * the in-memory series store without changing this catalog contract.
 */
public final class SignalCatalog {

    private final List<SignalCatalogEntry> entries;
    private final String measurementSource;
    private final String definitionSource;

    public SignalCatalog(
            List<SignalCatalogEntry> entries,
            String measurementSource,
            String definitionSource
    ) {
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        this.measurementSource = measurementSource == null ? "" : measurementSource;
        this.definitionSource = definitionSource == null ? "" : definitionSource;
    }

    public static SignalCatalog empty() {
        return new SignalCatalog(List.of(), "", "");
    }

    public List<SignalCatalogEntry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    public String measurementSource() {
        return measurementSource;
    }

    public String definitionSource() {
        return definitionSource;
    }

    public long dbcCanSignalCount() {
        return entries.stream().filter(e -> e.sourceType() == SignalSourceType.DBC_CAN).count();
    }

    public long nativeMf4SignalCount() {
        return entries.stream().filter(e -> e.sourceType() == SignalSourceType.MF4_NATIVE).count();
    }

    public Optional<SignalCatalogEntry> find(SignalCatalogKey key) {
        Objects.requireNonNull(key, "key");
        return entries.stream().filter(e -> e.key().equals(key)).findFirst();
    }

    /**
     * Case-insensitive metadata search by signal name, message, CAN ID, and/or unit.
     * Complexity is O(catalog size), not sample count.
     */
    public List<SignalCatalogEntry> search(String query) {
        String q = normalize(query);
        if (q.isEmpty()) {
            return entries;
        }
        List<SignalCatalogEntry> out = new ArrayList<>();
        for (SignalCatalogEntry e : entries) {
            if (matches(e, q)) {
                out.add(e);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Field-oriented filter (any empty field is ignored).
     */
    public List<SignalCatalogEntry> search(
            String signalName,
            String messageName,
            String canIdText,
            String unit
    ) {
        String s = normalize(signalName);
        String m = normalize(messageName);
        String c = normalize(canIdText);
        String u = normalize(unit);
        if (s.isEmpty() && m.isEmpty() && c.isEmpty() && u.isEmpty()) {
            return entries;
        }
        List<SignalCatalogEntry> out = new ArrayList<>();
        for (SignalCatalogEntry e : entries) {
            if (!s.isEmpty() && !e.signalName().toLowerCase(Locale.ROOT).contains(s)) {
                continue;
            }
            if (!m.isEmpty() && !e.messageName().toLowerCase(Locale.ROOT).contains(m)) {
                continue;
            }
            if (!c.isEmpty() && !matchesCanId(e.canId(), c)) {
                continue;
            }
            if (!u.isEmpty() && !e.unit().toLowerCase(Locale.ROOT).contains(u)) {
                continue;
            }
            out.add(e);
        }
        return List.copyOf(out);
    }

    public SignalCatalog sortedByName() {
        List<SignalCatalogEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(SignalCatalogEntry::signalName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(e -> e.sourceType().name())
                .thenComparingInt(SignalCatalogEntry::channel)
                .thenComparing(SignalCatalogEntry::messageName, String.CASE_INSENSITIVE_ORDER));
        return new SignalCatalog(sorted, measurementSource, definitionSource);
    }

    private static boolean matches(SignalCatalogEntry e, String q) {
        if (e.signalName().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        if (e.messageName().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        if (e.unit().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        return matchesCanId(e.canId(), q);
    }

    private static boolean matchesCanId(long canId, String query) {
        String dec = Long.toString(canId);
        String hex = Long.toHexString(canId).toLowerCase(Locale.ROOT);
        return dec.contains(query) || hex.contains(query) || ("0x" + hex).contains(query);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
