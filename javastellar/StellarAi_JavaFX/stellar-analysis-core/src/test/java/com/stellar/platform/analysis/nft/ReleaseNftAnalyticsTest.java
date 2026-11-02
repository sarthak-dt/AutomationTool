package com.stellar.platform.analysis.nft;

import com.stellar.platform.analysis.advanced.AdvancedAnalyticsRequest;
import com.stellar.platform.analysis.advanced.AdvancedAnalyticsType;
import com.stellar.platform.analysis.advanced.AdvancedSignalAnalytics;
import com.stellar.platform.analysis.advanced.AnalyticsLimits;
import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.source.InMemoryAnalysisSampleSource;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic numeric fixture. Not MF4 parser throughput. */
@Tag("release-nft")
class ReleaseNftAnalyticsTest {

    private static final SignalCatalogKey A = key("Accel");
    private static final SignalCatalogKey B = key("AccelDelayed");
    private final AdvancedSignalAnalytics engine = new AdvancedSignalAnalytics();

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void fftPsdLagScaleOnBoundedWindows() {
        int[] sizes = {100_000, 1_000_000};
        for (int n : sizes) {
            if (n > AnalyticsLimits.MAX_ANALYSIS_POINTS) {
                System.out.println("PHASE9C_ANALYTICS n=" + n + " NOT_TESTED reason=MAX_ANALYSIS_POINTS");
                continue;
            }
            bench("FFT", AdvancedAnalyticsType.FFT_SPECTRUM, n);
            bench("PSD", AdvancedAnalyticsType.POWER_SPECTRAL_DENSITY, n);
            bench("LAG", AdvancedAnalyticsType.LAG_ANALYSIS, n);
        }
        long t100k = lagMs(100_000);
        long t200k = lagMs(200_000);
        System.out.println("PHASE9C_ANALYTICS lagScale 100kMs=" + t100k + " 200kMs=" + t200k
                + " ratio=" + (t100k <= 0 ? 0 : (double) t200k / t100k));
        assertTrue(t200k < t100k * 8 + 2_000, "lag must not explode as O(N^2); 200k=" + t200k + " 100k=" + t100k);
    }

    private void bench(String name, AdvancedAnalyticsType type, int n) {
        InMemoryAnalysisSampleSource src = type == AdvancedAnalyticsType.LAG_ANALYSIS
                ? delayedPair(n) : sine(n);
        AdvancedAnalyticsRequest.Builder b = AdvancedAnalyticsRequest.builder(type)
                .signals(type == AdvancedAnalyticsType.LAG_ANALYSIS ? List.of(A, B) : List.of(A))
                .window(new TimeWindow(0, Math.round((n / 10_000.0) * 1e9)))
                .maxPoints(AnalyticsLimits.MAX_ANALYSIS_POINTS);
        if (type == AdvancedAnalyticsType.POWER_SPECTRAL_DENSITY) {
            b.welchSegmentLength(256).welchOverlap(128);
        }
        long heap0 = heap();
        long t0 = System.nanoTime();
        var result = engine.execute(b.build(), src, null);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        long heap1 = heap();
        System.out.println("PHASE9C_ANALYTICS name=" + name + " n=" + n
                + " durationMs=" + ms + " status=" + result.status()
                + " heapBefore=" + heap0 + " heapAfter=" + heap1
                + " dataset=SYNTHETIC_NUMERIC");
        assertTrue(result.status() == AnalysisStatus.COMPLETED
                || result.status().successful(), result.message());
    }

    private long lagMs(int n) {
        AdvancedAnalyticsRequest req = AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.LAG_ANALYSIS)
                .signals(List.of(A, B))
                .window(new TimeWindow(0, Math.round((n / 10_000.0) * 1e9)))
                .maxPoints(AnalyticsLimits.MAX_ANALYSIS_POINTS)
                .build();
        long t0 = System.nanoTime();
        engine.execute(req, delayedPair(n), null);
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    private static InMemoryAnalysisSampleSource sine(int n) {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        double fs = 10_000;
        for (int i = 0; i < n; i++) {
            double t = i / fs;
            src.add(A, Math.round(t * 1e9), Math.sin(2 * Math.PI * 10 * t));
        }
        return src;
    }

    private static InMemoryAnalysisSampleSource delayedPair(int n) {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        double fs = 10_000;
        double delay = 0.01;
        for (int i = 0; i < n; i++) {
            double t = i / fs;
            src.add(A, Math.round(t * 1e9), Math.sin(2 * Math.PI * 10 * t));
            src.add(B, Math.round(t * 1e9), Math.sin(2 * Math.PI * 10 * (t - delay)));
        }
        return src;
    }

    private static SignalCatalogKey key(String name) {
        return new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "MEAS", 0, 0, "synth.mf4", "");
    }

    private static long heap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
