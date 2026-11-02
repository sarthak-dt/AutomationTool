package com.stellar.platform.server.decoder;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.processing.graph.LosslessGraphSignalStore;

import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable decoded snapshot. Pages are addressed by an opaque cursor so a replay returns the
 * same samples and a later page does not decode again.
 */
final class DecoderSnapshots {

    private final ConcurrentHashMap<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    Snapshot create(
            String id,
            Instant expiresAt,
            String status,
            String sourceType,
            String resultOrigin,
            Map<String, Object> identity,
            List<Map<String, Object>> sourceSummaries,
            List<String> diagnostics,
            String decoderVersion,
            int catalogSignalCount,
            long catalogSampleCount,
            List<OpenedStore> stores,
            List<SignalSlot> signals,
            Map<String, SourceClock> sourceClocks,
            Map<String, String> datetimeReasons
    ) {
        Snapshot snapshot = new Snapshot(
                id, expiresAt, status, sourceType, resultOrigin, identity, sourceSummaries,
                diagnostics, decoderVersion, catalogSignalCount, catalogSampleCount, stores, signals,
                sourceClocks, datetimeReasons);
        snapshots.put(id, snapshot);
        return snapshot;
    }

    Snapshot require(String id, Instant now) {
        Snapshot snapshot = snapshots.get(id);
        if (snapshot == null || snapshot.expiresAt.isBefore(now) || snapshot.closed) {
            if (snapshot != null) {
                expire(id);
            }
            throw new DecoderValidationException(
                    "CURSOR_EXPIRED", HttpStatus.GONE, "The validation cursor has expired.");
        }
        return snapshot;
    }

    void expire(String id) {
        Snapshot snapshot = snapshots.remove(id);
        if (snapshot != null) {
            snapshot.close();
        }
    }

    void expireDue(Instant now) {
        for (String id : List.copyOf(snapshots.keySet())) {
            Snapshot snapshot = snapshots.get(id);
            if (snapshot != null && snapshot.expiresAt.isBefore(now)) {
                expire(id);
            }
        }
    }

    void closeAll() {
        for (String id : List.copyOf(snapshots.keySet())) {
            expire(id);
        }
    }

    int size() {
        return snapshots.size();
    }

    record SignalSlot(String sourceId, SignalCatalogKey key, String unit, long sampleCount, LosslessGraphSignalStore store) {
    }

    static final class OpenedStore implements AutoCloseable {
        private final GraphSignalSessionStore session;
        private final Path root;
        private final boolean deleteRoot;

        OpenedStore(GraphSignalSessionStore session, Path root, boolean deleteRoot) {
            this.session = session;
            this.root = root;
            this.deleteRoot = deleteRoot;
        }

        LosslessGraphSignalStore lossless() {
            return session.losslessStore();
        }

        @Override
        public void close() {
            try {
                session.close();
            } catch (RuntimeException ignored) {
                // A read-only reopen persists nothing when the index is clean.
            }
            if (deleteRoot) {
                deleteTree(root);
            }
        }
    }

    static final class Snapshot {
        final String id;
        final Instant expiresAt;
        final String status;
        final String sourceType;
        final String resultOrigin;
        final Map<String, Object> identity;
        final List<Map<String, Object>> sourceSummaries;
        final List<String> diagnostics;
        final String decoderVersion;
        final int catalogSignalCount;
        final long catalogSampleCount;
        final List<OpenedStore> stores;
        final List<SignalSlot> signals;
        final List<String> jsonKeys;
        final long totalSamples;
        final Map<String, SourceClock> sourceClocks;
        final String datetimeAvailability;
        final String datetimeAvailabilityReason;
        private boolean closed;

        Snapshot(
                String id,
                Instant expiresAt,
                String status,
                String sourceType,
                String resultOrigin,
                Map<String, Object> identity,
                List<Map<String, Object>> sourceSummaries,
                List<String> diagnostics,
                String decoderVersion,
                int catalogSignalCount,
                long catalogSampleCount,
                List<OpenedStore> stores,
                List<SignalSlot> signals,
                Map<String, SourceClock> sourceClocks,
                Map<String, String> datetimeReasons
        ) {
            this.id = id;
            this.expiresAt = expiresAt;
            this.status = status;
            this.sourceType = sourceType;
            this.resultOrigin = resultOrigin;
            this.identity = identity;
            this.sourceSummaries = sourceSummaries;
            this.diagnostics = diagnostics;
            this.decoderVersion = decoderVersion;
            this.catalogSignalCount = catalogSignalCount;
            this.catalogSampleCount = catalogSampleCount;
            this.stores = stores;
            this.signals = List.copyOf(signals);
            this.jsonKeys = jsonKeys(this.signals);
            this.sourceClocks = sourceClocks == null ? Map.of() : Map.copyOf(sourceClocks);
            this.datetimeAvailabilityReason = DecoderRecordingClock.describe(datetimeReasons);
            long total = 0L;
            int withSamples = 0;
            int withClock = 0;
            for (SignalSlot signal : this.signals) {
                total += signal.sampleCount();
                if (signal.sampleCount() == 0L) {
                    continue;
                }
                withSamples++;
                SourceClock clock = this.sourceClocks.get(signal.sourceId());
                if (clock != null && clock.available()) {
                    withClock++;
                }
            }
            this.totalSamples = total;
            this.datetimeAvailability = DecoderDateTimes.availability(withSamples, withClock);
        }

        Map<String, Object> page(int signalOrdinal, int sampleOffset, int pageSize) {
            if (signalOrdinal < 0 || sampleOffset < 0) {
                throw new DecoderValidationException(
                        "INVALID_REQUEST", HttpStatus.BAD_REQUEST, "cursor is not valid.");
            }
            Map<String, Object> pageSignals = new LinkedHashMap<>();
            int ordinal = signalOrdinal;
            int offset = sampleOffset;
            int samplesLeft = pageSize;
            int signalsLeft = pageSize;
            int returned = 0;
            while (ordinal < signals.size() && samplesLeft > 0 && signalsLeft > 0) {
                SignalSlot slot = signals.get(ordinal);
                if (offset >= slot.sampleCount()) {
                    if (slot.sampleCount() == 0 && offset == 0) {
                        putSignal(pageSignals, jsonKeys.get(ordinal), signalJson(slot, List.of(), 0, sourceClocks.get(slot.sourceId())));
                        signalsLeft--;
                    }
                    ordinal++;
                    offset = 0;
                    continue;
                }
                int take = (int) Math.min(samplesLeft, slot.sampleCount() - offset);
                List<SignalSamplePoint> points = slot.store().querySamples(slot.key(), offset, take);
                if (points.size() != take) {
                    throw new DecoderValidationException(
                            "INTERNAL_ERROR",
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "Stored samples could not be read completely.");
                }
                putSignal(pageSignals, jsonKeys.get(ordinal), signalJson(slot, points, offset, sourceClocks.get(slot.sourceId())));
                returned += points.size();
                samplesLeft -= points.size();
                signalsLeft--;
                offset += points.size();
                if (offset >= slot.sampleCount()) {
                    ordinal++;
                    offset = 0;
                }
                if (samplesLeft == 0) {
                    break;
                }
            }
            boolean done = ordinal >= signals.size();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("signals", pageSignals);
            body.put("returnedSampleCount", returned);
            body.put("nextSignalOrdinal", done ? null : ordinal);
            body.put("nextSampleOffset", done ? null : offset);
            body.put("paginationComplete", done);
            return body;
        }

        synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            for (OpenedStore store : stores) {
                store.close();
            }
        }
    }

    private static void putSignal(Map<String, Object> pageSignals, String key, Map<String, Object> signal) {
        if (pageSignals.containsKey(key)) {
            throw new DecoderValidationException(
                    "INTERNAL_ERROR",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Two signals resolved to the same response key.");
        }
        pageSignals.put(key, signal);
    }

    /**
     * Plain signal name when that name occurs once in the complete selected dataset.
     * Otherwise {@code name|sourceFile|messageName|canId|channel|sourceDatabase}. A still-colliding
     * key gains {@code |sourceId} and, if needed, {@code #n}. Keys are fixed for the snapshot,
     * so later pages use the same key even when the other colliding signal is not on that page.
     */
    static List<String> jsonKeys(List<SignalSlot> signals) {
        Map<String, Integer> nameCounts = new LinkedHashMap<>();
        for (SignalSlot slot : signals) {
            nameCounts.merge(slot.key().signalName(), 1, Integer::sum);
        }
        List<String> assigned = new ArrayList<>(signals.size());
        Map<String, Integer> used = new LinkedHashMap<>();
        for (SignalSlot slot : signals) {
            String name = slot.key().signalName();
            String key = nameCounts.getOrDefault(name, 0) == 1 ? name : qualifiedKey(slot);
            if (used.containsKey(key)) {
                key = key + "|" + slot.sourceId();
            }
            while (used.containsKey(key)) {
                key = key + "#" + (used.size() + 1);
            }
            used.put(key, 1);
            assigned.add(key);
        }
        return List.copyOf(assigned);
    }

    private static String qualifiedKey(SignalSlot slot) {
        SignalCatalogKey key = slot.key();
        return key.signalName()
                + "|" + fileName(key.sourceFile())
                + "|" + key.messageName()
                + "|" + key.canId()
                + "|" + key.channel()
                + "|" + fileName(key.sourceDatabase());
    }

    static String fileName(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            Path name = Path.of(path).getFileName();
            return name == null ? path : name.toString();
        } catch (RuntimeException ex) {
            return path;
        }
    }

    private static Map<String, Object> signalJson(
            SignalSlot slot,
            List<SignalSamplePoint> points,
            int firstIndex,
            SourceClock clock
    ) {
        SignalCatalogKey key = slot.key();
        Map<String, List<?>> columns = DecoderSampleJson.columns(points, firstIndex, clock);
        requireAligned(columns);
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("name", key.signalName());
        signal.put("signalIdentity", DecoderCanonicalIds.signalIdentity(key));
        signal.put("canonicalId", DecoderCanonicalIds.canonical(slot.sourceId(), key));
        signal.put("sourceId", slot.sourceId());
        signal.put("sourceFile", fileName(key.sourceFile()));
        signal.put("sourceDatabase", fileName(key.sourceDatabase()));
        signal.put("unit", slot.unit() == null ? "" : slot.unit());
        signal.put("messageName", key.messageName());
        signal.put("canId", Long.toString(key.canId()));
        signal.put("canIdHex", "0x" + Long.toHexString(key.canId()));
        signal.put("channel", key.channel());
        signal.put("sourceType", key.sourceType().name());
        signal.put("sampleCount", slot.sampleCount());
        signal.put("returnedSampleCount", points.size());
        signal.put("availability", slot.sampleCount() > 0 ? "AVAILABLE" : "EMPTY");
        signal.putAll(columns);
        return signal;
    }

    private static void requireAligned(Map<String, List<?>> columns) {
        int expected = -1;
        for (List<?> column : columns.values()) {
            if (expected < 0) {
                expected = column.size();
            } else if (column.size() != expected) {
                throw new DecoderValidationException(
                        "INTERNAL_ERROR",
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Sample columns for a signal do not have the same length.");
            }
        }
    }

    static List<SignalSlot> slots(List<OpenedSource> sources) {
        List<SignalSlot> slots = new ArrayList<>();
        for (OpenedSource source : sources) {
            LosslessGraphSignalStore store = source.opened.lossless();
            for (SignalCatalogKey key : store.keys()) {
                long count = store.sampleCount(key);
                if (count > Integer.MAX_VALUE) {
                    throw new DecoderValidationException(
                            "RESOURCE_LIMIT",
                            HttpStatus.TOO_MANY_REQUESTS,
                            "A decoded signal exceeds the indexable sample count.");
                }
                slots.add(new SignalSlot(source.sourceId, key, store.unit(key), count, store));
            }
        }
        slots.sort(Comparator
                .comparingInt((SignalSlot slot) -> sourceOrder(sources, slot.sourceId()))
                .thenComparing(slot -> slot.key().signalName())
                .thenComparing(slot -> slot.key().sourceType().name())
                .thenComparing(slot -> slot.key().messageName())
                .thenComparingLong(slot -> slot.key().canId())
                .thenComparingInt(slot -> slot.key().channel())
                .thenComparing(slot -> slot.key().sourceFile())
                .thenComparing(slot -> slot.key().sourceDatabase()));
        return List.copyOf(slots);
    }

    private static int sourceOrder(List<OpenedSource> sources, String sourceId) {
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).sourceId.equals(sourceId)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    static boolean deleteTree(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        if (Files.isSymbolicLink(root)) {
            return false;
        }
        try (var paths = Files.walk(root)) {
            List<Path> all = paths.toList();
            for (Path path : all) {
                if (Files.isSymbolicLink(path)) {
                    return false;
                }
            }
            for (Path path : all.stream().sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            return !Files.exists(root, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ignored) {
            return false;
        }
    }

    record OpenedSource(String sourceId, OpenedStore opened) {
    }
}
