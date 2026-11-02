package com.stellar.platform.analysis.report;

import com.stellar.platform.analysis.advanced.AdvancedAnalyticsResult;
import com.stellar.platform.analysis.advanced.SpectralPeak;

import java.util.Locale;

public final class AdvancedAnalyticsReport {

    private AdvancedAnalyticsReport() {
    }

    public static String format(AdvancedAnalyticsResult result) {
        if (result == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("ADVANCED SIGNAL ANALYTICS\n\n");
        sb.append("Type      ").append(result.type()).append('\n');
        sb.append("Status    ").append(result.status()).append('\n');
        if (result.window() != null) {
            sb.append(String.format(Locale.ROOT, "Window    %.3f – %.3f s%n",
                    result.window().startNanos() / 1e9, result.window().endNanos() / 1e9));
        }
        if (result.sampling() != null) {
            sb.append('\n').append(result.sampling().summary()).append('\n');
        }
        if (result.resampled()) {
            sb.append("Resampled ").append(result.resampleMethod())
                    .append(" at ").append(result.effectiveSampleRateHz()).append(" Hz\n");
        }
        if (result.spectrum() != null) {
            var s = result.spectrum();
            sb.append(String.format(Locale.ROOT,
                    "%nFFT  N=%d  size=%d  fs=%.6g Hz  Δf=%.6g Hz  Nyquist=%.6g Hz  window=%s%n",
                    s.sampleCount(), s.fftSize(), s.sampleRateHz(), s.frequencyResolutionHz(),
                    s.nyquistHz(), s.window()));
            sb.append("Norm     ").append(s.normalization()).append('\n');
            if (!s.magnitudeUnit().isBlank()) {
                sb.append("Unit     ").append(s.magnitudeUnit()).append('\n');
            }
        }
        if (result.psd() != null) {
            var p = result.psd();
            sb.append(String.format(Locale.ROOT,
                    "%nPSD  Welch  segs=%d  L=%d  overlap=%d  window=%s%n",
                    p.segmentCount(), p.segmentLength(), p.overlap(), p.window()));
            sb.append("Method   ").append(p.method()).append('\n');
            if (!p.densityUnit().isBlank()) {
                sb.append("Unit     ").append(p.densityUnit()).append('\n');
            }
        }
        if (!result.peaks().isEmpty()) {
            sb.append("\nPeaks (measurement only — not a mechanical diagnosis)\n");
            for (SpectralPeak peak : result.peaks()) {
                sb.append(String.format(Locale.ROOT, "  #%d  %.4g Hz  mag=%.4g  T=%.4g s  %s%n",
                        peak.rank(), peak.frequencyHz(), peak.magnitude(),
                        peak.periodSeconds(), peak.harmonicLabel() == null ? "" : peak.harmonicLabel()));
            }
        }
        if (result.lag() != null) {
            var lag = result.lag();
            sb.append(String.format(Locale.ROOT,
                    "%nLag  %+.6g s (%+d ns)  r=%.4g  pairs=%d  quality=%s%n",
                    lag.bestLagSeconds(), lag.bestLagNanos(), lag.correlationAtLag(),
                    lag.pairCount(), lag.quality()));
            sb.append("Sign     ").append(lag.signConvention()).append('\n');
        }
        if (result.zeroCrossings() != null) {
            var z = result.zeroCrossings();
            sb.append(String.format(Locale.ROOT,
                    "%nZero crossings  %s  count=%d  f≈%.4g Hz%n",
                    z.mode(), z.count(), z.approximateFrequencyHz()));
        }
        if (result.cycles() != null) {
            sb.append(String.format(Locale.ROOT, "%nCycles  %s  count=%d%n",
                    result.cycles().criterion(), result.cycles().cycles().size()));
        }
        if (result.coherence() != null) {
            sb.append("\nCoherence  magnitude-squared (Welch). Not Pearson correlation.\n");
        }
        if (result.derived() != null) {
            sb.append("\nDerived  ").append(result.derived().expression()).append('\n');
            sb.append("Identity ").append(result.derived().identity()).append('\n');
        }
        if (!result.warnings().isEmpty()) {
            sb.append("\nWarnings\n");
            result.warnings().forEach(w -> sb.append("  ").append(w).append('\n'));
        }
        if (!result.stages().isEmpty()) {
            sb.append("\nStages  ").append(String.join(" → ", result.stages())).append('\n');
        }
        sb.append("\n").append(result.reproducibility()).append('\n');
        return sb.toString();
    }
}
