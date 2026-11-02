package com.stellar.platform.processing.inventory;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryDiagnostic;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Streaming aggregator: {@link CanonicalCanFrame} batches → {@link CanSourceInventory}.
 * Memory scales with unique channels + message keys, not frame count.
 * Format-agnostic after frames are produced.
 */
public final class CanInventoryCollector {

    private final Path sourcePath;
    private final String sourceFileName;
    private final MeasurementSourceFormat format;
    private final Map<Integer, ChannelAccumulator> channels = new LinkedHashMap<>();
    private final Set<MessageIdentity> sourceUniqueMessages = new HashSet<>();
    private final List<CanInventoryDiagnostic> diagnostics = new ArrayList<>();
    private CanInventoryStatus status = CanInventoryStatus.FILE_ACCEPTED;
    private boolean channelDiscoveryComplete;
    private long totalFrames;

    public CanInventoryCollector(Path sourcePath, MeasurementSourceFormat format) {
        this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        this.sourceFileName = sourcePath.getFileName() == null
                ? sourcePath.toString()
                : sourcePath.getFileName().toString();
        this.format = format == null ? MeasurementSourceFormat.UNKNOWN : format;
    }

    public void setStatus(CanInventoryStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    public CanInventoryStatus status() {
        return status;
    }

    public void markChannelDiscoveryComplete() {
        this.channelDiscoveryComplete = true;
    }

    public boolean channelDiscoveryComplete() {
        return channelDiscoveryComplete;
    }

    public void addDiagnostic(CanInventoryDiagnostic diagnostic) {
        if (diagnostic != null) {
            diagnostics.add(diagnostic);
        }
    }

    public void seedChannel(int channelId) {
        if (channelId < 0) {
            return;
        }
        channels.computeIfAbsent(channelId, ChannelAccumulator::new);
    }

    public void accept(CanonicalCanFrame frame) {
        if (frame == null) {
            return;
        }
        totalFrames++;
        ChannelAccumulator channel = channels.computeIfAbsent(frame.channel(), ChannelAccumulator::new);
        if (channel.accept(frame)) {
            sourceUniqueMessages.add(new MessageIdentity(frame.canId(), frame.extended()));
        }
    }

    public void acceptBatch(List<CanonicalCanFrame> frames) {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        for (CanonicalCanFrame frame : frames) {
            accept(frame);
        }
    }

    public int channelCount() {
        return channels.size();
    }

    public long totalFrames() {
        return totalFrames;
    }

    /**
     * Source-global unique CAN identities: union of {@code (canId, extended)} across channels.
     * Identical numeric IDs on multiple channels count once. Per-channel counts are unchanged.
     */
    public long totalUniqueMessageIds() {
        return sourceUniqueMessages.size();
    }

    public CanSourceInventory snapshot() {
        List<CanChannelInventory> channelSnapshots = new ArrayList<>(channels.size());
        for (ChannelAccumulator ch : channels.values()) {
            channelSnapshots.add(ch.snapshot());
        }
        return new CanSourceInventory(
                sourcePath,
                sourceFileName,
                format,
                status,
                channels.size(),
                totalFrames,
                totalUniqueMessageIds(),
                channelDiscoveryComplete,
                channelSnapshots,
                diagnostics
        );
    }

    private static boolean observeCanFd(CanonicalCanFrame frame) {
        return frame.dlc() > 8 || frame.payloadLength() > 8;
    }

    private record MessageIdentity(long canId, boolean extended) {
    }

    private static final class ChannelAccumulator {
        private final int channelId;
        private final Map<CanMessageKey, MessageAccumulator> messages = new LinkedHashMap<>();
        private long frameCount;
        private long canFdFrameCount;

        private ChannelAccumulator(int channelId) {
            this.channelId = channelId;
        }

        /** @return true when this channel observes the CAN identity for the first time. */
        private boolean accept(CanonicalCanFrame frame) {
            frameCount++;
            boolean canFd = observeCanFd(frame);
            if (canFd) {
                canFdFrameCount++;
            }
            CanMessageKey key = new CanMessageKey(frame.channel(), frame.canId(), frame.extended());
            MessageAccumulator accumulator = messages.get(key);
            boolean firstObservation = accumulator == null;
            if (firstObservation) {
                accumulator = new MessageAccumulator(key);
                messages.put(key, accumulator);
            }
            accumulator.accept(frame.dlc(), canFd);
            return firstObservation;
        }

        private CanChannelInventory snapshot() {
            List<CanMessageInventory> messageSnapshots = new ArrayList<>(messages.size());
            long standard = 0L;
            long extended = 0L;
            for (MessageAccumulator msg : messages.values()) {
                messageSnapshots.add(msg.snapshot());
                if (msg.key.extended()) {
                    extended++;
                } else {
                    standard++;
                }
            }
            return new CanChannelInventory(
                    channelId,
                    "Channel " + channelId,
                    frameCount,
                    messages.size(),
                    standard,
                    extended,
                    canFdFrameCount,
                    messageSnapshots
            );
        }
    }

    private static final class MessageAccumulator {
        private final CanMessageKey key;
        private long frameCount;
        private int minDlc = Integer.MAX_VALUE;
        private int maxDlc = Integer.MIN_VALUE;
        private boolean canFdObserved;

        private MessageAccumulator(CanMessageKey key) {
            this.key = key;
        }

        private void accept(int dlc, boolean canFd) {
            frameCount++;
            minDlc = Math.min(minDlc, dlc);
            maxDlc = Math.max(maxDlc, dlc);
            canFdObserved = canFdObserved || canFd;
        }

        private CanMessageInventory snapshot() {
            int min = frameCount == 0 ? 0 : minDlc;
            int max = frameCount == 0 ? 0 : maxDlc;
            return new CanMessageInventory(key, frameCount, min, max, canFdObserved);
        }
    }
}
