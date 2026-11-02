package com.stellar.platform.processing.inventory;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanInventoryDiagnostic;
import com.stellar.platform.domain.inventory.CanInventoryDiagnosticCode;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.CanChannelDiscoveryCapable;
import com.stellar.platform.parser.spi.CanChannelDiscoveryResult;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.ParserRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Format-independent CAN source inventory:
 * reader (SPI) → CanonicalCanFrame batches → {@link CanInventoryCollector}.
 * Never invokes DBC decoding.
 */
public final class SourceInventoryService {

    public static final int DEFAULT_PROGRESS_EVERY_BATCHES = 8;
    public static final long DEFAULT_PROGRESS_MIN_INTERVAL_NANOS = 150_000_000L;

    private final ParserRegistry parserRegistry;
    private final int progressEveryBatches;
    private final long progressMinIntervalNanos;

    public SourceInventoryService(ParserRegistry parserRegistry) {
        this(parserRegistry, DEFAULT_PROGRESS_EVERY_BATCHES, DEFAULT_PROGRESS_MIN_INTERVAL_NANOS);
    }

    /**
     * Explicit cadence constructor used by tests/tuning. A zero time gate preserves the exact
     * batch-driven behavior requested by the caller.
     */
    public SourceInventoryService(ParserRegistry parserRegistry, int progressEveryBatches) {
        this(parserRegistry, progressEveryBatches, 0L);
    }

    SourceInventoryService(
            ParserRegistry parserRegistry,
            int progressEveryBatches,
            long progressMinIntervalNanos
    ) {
        this.parserRegistry = Objects.requireNonNull(parserRegistry, "parserRegistry");
        this.progressEveryBatches = Math.max(1, progressEveryBatches);
        this.progressMinIntervalNanos = Math.max(0L, progressMinIntervalNanos);
    }

    public static SourceInventoryService createDefault() {
        return new SourceInventoryService(ParserRegistry.load());
    }

    public CanSourceInventory inventory(
            Path measurementPath,
            ReaderContext readerContext,
            SourceInventoryProgressListener progressListener
    ) {
        Objects.requireNonNull(measurementPath, "measurementPath");
        ReaderContext context = readerContext == null
                ? ReaderContext.ofBatchSize(512)
                : readerContext;
        SourceInventoryProgressListener progress = progressListener == null
                ? SourceInventoryProgressListener.NOOP
                : progressListener;

        AutomotiveFileReader reader = parserRegistry.findReader(measurementPath).orElse(null);
        if (reader == null) {
            MeasurementSourceFormat guessed = MeasurementSourceFormat.fromFormatKey(
                    extensionHint(measurementPath));
            CanInventoryCollector collector = new CanInventoryCollector(measurementPath, guessed);
            collector.setStatus(CanInventoryStatus.FAILED);
            collector.markChannelDiscoveryComplete();
            collector.addDiagnostic(new CanInventoryDiagnostic(
                    CanInventoryDiagnosticCode.FORMAT_UNSUPPORTED,
                    guessed == MeasurementSourceFormat.BLF
                            ? "BLF inventory requires a production BLF AutomotiveFileReader."
                            : "No AutomotiveFileReader supports this measurement for inventory."
            ));
            CanSourceInventory snap = collector.snapshot();
            progress.onInventoryUpdated(snap);
            return snap;
        }

        return inventory(reader, measurementPath, context, progress);
    }

    public CanSourceInventory inventory(
            AutomotiveFileReader reader,
            Path measurementPath,
            ReaderContext readerContext,
            SourceInventoryProgressListener progressListener
    ) {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(measurementPath, "measurementPath");
        ReaderContext context = readerContext == null
                ? ReaderContext.ofBatchSize(512)
                : readerContext;
        SourceInventoryProgressListener progress = progressListener == null
                ? SourceInventoryProgressListener.NOOP
                : progressListener;

        MeasurementSourceFormat format = MeasurementSourceFormat.fromFormatKey(reader.formatKey());
        CanInventoryCollector collector = new CanInventoryCollector(measurementPath, format);
        collector.setStatus(CanInventoryStatus.FILE_ACCEPTED);
        progress.onInventoryUpdated(collector.snapshot());

        collector.setStatus(CanInventoryStatus.DISCOVERING_CHANNELS);
        progress.onInventoryUpdated(collector.snapshot());

        boolean earlyChannelsSeeded = false;
        boolean canStructuresPresent = false;
        if (reader instanceof CanChannelDiscoveryCapable discoveryCapable) {
            try {
                CanChannelDiscoveryResult early = discoveryCapable.discoverCanChannels(
                        measurementPath, context.cancellationToken());
                if (early != null) {
                    canStructuresPresent = early.canStructuresPresent();
                    for (Integer channelId : early.knownChannelIds()) {
                        if (channelId != null) {
                            collector.seedChannel(channelId);
                            earlyChannelsSeeded = true;
                        }
                    }
                    if (earlyChannelsSeeded) {
                        collector.setStatus(CanInventoryStatus.CHANNELS_DISCOVERED);
                        progress.onInventoryUpdated(collector.snapshot());
                    } else if (early.canStructuresPresent()) {
                        // Structures known, but channel IDs arrive with frames — keep discovering.
                        progress.onInventoryUpdated(collector.snapshot());
                    }
                }
            } catch (ParserException e) {
                collector.addDiagnostic(new CanInventoryDiagnostic(
                        CanInventoryDiagnosticCode.CHANNEL_DISCOVERY_FAILED,
                        e.getMessage() == null ? e.toString() : e.getMessage()
                ));
            }
        }

        collector.setStatus(CanInventoryStatus.INDEXING_MESSAGE_IDS);
        AtomicLong batchCounter = new AtomicLong();
        AtomicLong lastProgressNanos = new AtomicLong();
        AtomicBoolean payloadUnreadable = new AtomicBoolean();
        List<String> payloadFailureReasons = new ArrayList<>();
        AtomicLong malformedLines = new AtomicLong();
        try {
            reader.readFrames(measurementPath, context, batch -> {
                if (context.cancellationToken().isCancellationRequested()) {
                    return;
                }
                List<CanonicalCanFrame> frames = batch;
                int previousChannels = collector.channelCount();
                collector.acceptBatch(frames);
                if (collector.channelCount() > previousChannels
                        && collector.status() != CanInventoryStatus.CHANNELS_DISCOVERED
                        && collector.status() != CanInventoryStatus.INVENTORY_READY) {
                    collector.setStatus(CanInventoryStatus.CHANNELS_DISCOVERED);
                    progress.onInventoryUpdated(collector.snapshot());
                    collector.setStatus(CanInventoryStatus.INDEXING_MESSAGE_IDS);
                }
                long n = batchCounter.incrementAndGet();
                if (n == 1L || n % progressEveryBatches == 0L) {
                    long now = System.nanoTime();
                    long previous = lastProgressNanos.get();
                    if (n == 1L
                            || progressMinIntervalNanos == 0L
                            || now - previous >= progressMinIntervalNanos) {
                        lastProgressNanos.set(now);
                        progress.onInventoryUpdated(collector.snapshot());
                    }
                }
            }, warning -> {
                if (warning == null || warning.code() == null) {
                    return;
                }
                if (isPayloadUnreadableWarning(warning.code(), warning.message())) {
                    payloadUnreadable.set(true);
                    if (payloadFailureReasons.isEmpty()
                            && warning.message() != null
                            && !warning.message().isBlank()) {
                        payloadFailureReasons.add(warning.message());
                    }
                }
                if ("ASC_MALFORMED_LINE".equals(warning.code())) {
                    long seen = malformedLines.incrementAndGet();
                    if (seen <= 5) {
                        String message = warning.message() == null || warning.message().isBlank()
                                ? "Malformed ASC line"
                                : warning.message();
                        collector.addDiagnostic(new CanInventoryDiagnostic(
                                CanInventoryDiagnosticCode.MALFORMED_RECORD, message));
                    }
                }
            });
        } catch (ParserException e) {
            collector.setStatus(CanInventoryStatus.FAILED);
            collector.markChannelDiscoveryComplete();
            collector.addDiagnostic(new CanInventoryDiagnostic(
                    CanInventoryDiagnosticCode.SOURCE_READ_FAILED,
                    e.getMessage() == null ? e.toString() : e.getMessage()
            ));
            CanSourceInventory failed = collector.snapshot();
            progress.onInventoryUpdated(failed);
            return failed;
        }

        if (context.cancellationToken().isCancellationRequested()) {
            collector.setStatus(CanInventoryStatus.CANCELLED);
            collector.markChannelDiscoveryComplete();
            collector.addDiagnostic(new CanInventoryDiagnostic(
                    CanInventoryDiagnosticCode.INVENTORY_CANCELLED,
                    "CAN source inventory cancelled."
            ));
            CanSourceInventory cancelled = collector.snapshot();
            progress.onInventoryUpdated(cancelled);
            return cancelled;
        }

        collector.markChannelDiscoveryComplete();
        if (collector.totalFrames() == 0
                && (canStructuresPresent || earlyChannelsSeeded)
                && payloadUnreadable.get()) {
            String detail = payloadFailureReasons.isEmpty()
                    ? "CAN structures are present but compressed measurement payload could not be read."
                    : payloadFailureReasons.getFirst();
            collector.addDiagnostic(new CanInventoryDiagnostic(
                    CanInventoryDiagnosticCode.CAN_PAYLOAD_UNREADABLE,
                    detail
            ));
            collector.setStatus(CanInventoryStatus.FAILED);
            CanSourceInventory failed = collector.snapshot();
            progress.onInventoryUpdated(failed);
            return failed;
        }
        if (collector.channelCount() == 0) {
            collector.addDiagnostic(new CanInventoryDiagnostic(
                    CanInventoryDiagnosticCode.NO_CAN_DATA,
                    "No CAN bus-event data found."
            ));
        }
        collector.setStatus(CanInventoryStatus.INVENTORY_READY);
        CanSourceInventory ready = collector.snapshot();
        progress.onInventoryUpdated(ready);
        return ready;
    }

    static boolean isPayloadUnreadableWarning(String code, String message) {
        if (code == null) {
            return false;
        }
        String c = code.trim();
        if (c.startsWith("DZ_")
                && (c.contains("DECOMPRESSION_FAILED")
                || c.contains("INVALID_LENGTH")
                || c.contains("TRUNCATED")
                || c.contains("CORRUPT")
                || c.contains("UNSUPPORTED_COMPRESSION")
                || c.contains("LENGTH_MISMATCH"))) {
            return true;
        }
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase();
        return m.contains("spill") && (m.contains("failed") || m.contains("temp") || m.contains("deflate-only"));
    }

    private static String extensionHint(Path path) {
        if (path == null || path.getFileName() == null) {
            return "";
        }
        String name = path.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }
}
