package com.stellar.platform.analysis.advanced;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;

/**
 * Cache identity. Invalid only when artifact generation, source, parameters,
 * resampling, or engine version change — not graph zoom after an explicit run,
 * theme, inspector, or category grouping.
 */
public record AdvancedCacheKey(String digest) {

    public static AdvancedCacheKey of(AdvancedAnalyticsRequest request) {
        String payload = request.type()
                + "|" + request.signalKeys().stream().map(Object::toString).collect(Collectors.joining(","))
                + "|" + request.window().startNanos() + ":" + request.window().endNanos()
                + "|" + request.sampleHandling()
                + "|" + request.resampleMethod()
                + "|" + request.targetRateHz()
                + "|" + request.maxGapNanos()
                + "|" + request.alignOriginNanos()
                + "|" + request.windowFunction()
                + "|" + request.fftSize()
                + "|" + request.welchSegmentLength()
                + "|" + request.welchOverlap()
                + "|" + request.minPeakMagnitude()
                + "|" + request.minPeakSeparationHz()
                + "|" + request.freqMinHz() + ":" + request.freqMaxHz()
                + "|" + request.maxPeaks()
                + "|" + request.harmonicCount()
                + "|" + request.lowCutoffHz() + ":" + request.highCutoffHz()
                + "|" + request.filterOrder()
                + "|" + request.filterPhaseMode()
                + "|" + request.smoothWindow() + request.smoothWindowUnit()
                + "|" + request.alpha()
                + "|" + request.timeConstantSeconds()
                + "|" + request.envelopeMode()
                + "|" + request.windowedStatistic()
                + "|" + request.crossingMode()
                + "|" + request.maxLagNanos()
                + "|" + request.discreteSignal()
                + "|" + request.artifactGeneration()
                + "|" + request.sourceFingerprint()
                + "|" + AdvancedAnalyticsVersion.CURRENT;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return new AdvancedCacheKey(HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 required", e);
        }
    }
}
