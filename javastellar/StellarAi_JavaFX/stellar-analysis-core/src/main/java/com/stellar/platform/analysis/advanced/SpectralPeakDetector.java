package com.stellar.platform.analysis.advanced;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SpectralPeakDetector {

    private SpectralPeakDetector() {
    }

    public static List<SpectralPeak> detect(
            double[] frequencyHz,
            double[] magnitude,
            double minMagnitude,
            double minSeparationHz,
            double freqMinHz,
            double freqMaxHz,
            int maxPeaks
    ) {
        if (frequencyHz == null || magnitude == null || frequencyHz.length != magnitude.length) {
            return List.of();
        }
        int n = frequencyHz.length;
        List<int[]> candidates = new ArrayList<>();
        for (int i = 1; i < n - 1; i++) {
            double f = frequencyHz[i];
            double m = magnitude[i];
            if (!Double.isFinite(f) || !Double.isFinite(m)) {
                continue;
            }
            if (f < freqMinHz || f > freqMaxHz) {
                continue;
            }
            if (m < minMagnitude) {
                continue;
            }
            if (m > magnitude[i - 1] && m >= magnitude[i + 1]) {
                candidates.add(new int[] {i});
            }
        }
        candidates.sort(Comparator.comparingDouble((int[] a) -> magnitude[a[0]]).reversed());
        List<SpectralPeak> kept = new ArrayList<>();
        for (int[] c : candidates) {
            int i = c[0];
            double f = frequencyHz[i];
            boolean far = true;
            for (SpectralPeak p : kept) {
                if (Math.abs(p.frequencyHz() - f) < minSeparationHz) {
                    far = false;
                    break;
                }
            }
            if (!far) {
                continue;
            }
            Double bw = halfPowerBandwidth(frequencyHz, magnitude, i);
            kept.add(new SpectralPeak(kept.size() + 1, f, magnitude[i], f == 0 ? Double.NaN : 1d / f, bw, ""));
            if (kept.size() >= maxPeaks) {
                break;
            }
        }
        return List.copyOf(kept);
    }

    public static List<SpectralPeak> withHarmonics(List<SpectralPeak> peaks, int maxHarmonic) {
        if (peaks == null || peaks.isEmpty() || maxHarmonic < 2) {
            return peaks == null ? List.of() : peaks;
        }
        double fundamental = peaks.getFirst().frequencyHz();
        if (!(fundamental > 0)) {
            return peaks;
        }
        List<SpectralPeak> out = new ArrayList<>();
        for (SpectralPeak p : peaks) {
            String label = "";
            double ratio = p.frequencyHz() / fundamental;
            int nearest = (int) Math.round(ratio);
            if (nearest >= 1 && nearest <= maxHarmonic && Math.abs(ratio - nearest) <= 0.08d) {
                label = nearest == 1 ? "Fundamental candidate" : nearest + "× harmonic";
            }
            out.add(new SpectralPeak(p.rank(), p.frequencyHz(), p.magnitude(), p.periodSeconds(),
                    p.bandwidthHz(), label));
        }
        return List.copyOf(out);
    }

    private static Double halfPowerBandwidth(double[] f, double[] m, int i) {
        double half = m[i] / Math.sqrt(2d);
        int L = i;
        while (L > 0 && m[L] >= half) {
            L--;
        }
        int R = i;
        while (R < m.length - 1 && m[R] >= half) {
            R++;
        }
        if (R <= L) {
            return null;
        }
        return f[R] - f[L];
    }
}
