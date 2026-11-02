package com.stellar.platform.api.dto;

import java.util.List;

/** Viewport LOD request — graph asks only for the required time window / resolution. */
public record SignalViewportRequestDto(
        List<String> signalKeys,
        long fromNanos,
        long toNanos,
        int pixelWidth
) {
    public static final int MAX_KEYS = 32;
    public static final int MAX_PIXEL_WIDTH = 4096;

    public SignalViewportRequestDto {
        signalKeys = signalKeys == null ? List.of() : List.copyOf(signalKeys);
        if (signalKeys.size() > MAX_KEYS) {
            signalKeys = List.copyOf(signalKeys.subList(0, MAX_KEYS));
        }
        long from = Math.min(fromNanos, toNanos);
        long to = Math.max(fromNanos, toNanos);
        fromNanos = from;
        toNanos = to;
        pixelWidth = Math.max(1, Math.min(pixelWidth <= 0 ? 800 : pixelWidth, MAX_PIXEL_WIDTH));
    }
}
