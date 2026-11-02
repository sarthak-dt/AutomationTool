package com.stellar.platform.analysis.advanced;

import com.stellar.platform.analysis.source.AnalysisCancelledException;
import com.stellar.platform.parser.spi.CancellationToken;

/**
 * Digital Butterworth via bilinear transform of analog poles, cascaded SOS.
 * Band-pass is a high-pass at {@code lowCutoff} cascaded with a low-pass at {@code highCutoff}.
 *
 * <p>ZERO_PHASE: forward–backward (filtfilt). Approximately linear-phase / zero phase;
 * edge transients remain because initial conditions are zeros (odd-reflection pad of
 * {@code 3·order} samples is applied).
 *
 * <p>CAUSAL: single forward pass. Phase delay is present; group delay is approximately
 * {@code order / (4 · cutoff)} seconds for a low-pass prototype (reported, not hidden).
 */
public final class ButterworthFilter {

    public record Result(double[] values, AdvancedAnalyticsType type, double lowCutoffHz, double highCutoffHz,
                         int order, double sampleRateHz, FilterPhaseMode phaseMode, double estimatedDelaySeconds) {
    }

    private ButterworthFilter() {
    }

    public static Result apply(
            double[] x,
            double sampleRateHz,
            AdvancedAnalyticsType type,
            double lowCutoffHz,
            double highCutoffHz,
            int order,
            FilterPhaseMode mode,
            CancellationToken token
    ) {
        AnalyticsLimits.requireFinite(sampleRateHz, "sampleRateHz");
        if (sampleRateHz <= 0) {
            throw new IllegalArgumentException("sample rate must be > 0");
        }
        double nyquist = sampleRateHz / 2d;
        if (order < 1 || order > 8) {
            throw new IllegalArgumentException("Butterworth order must be 1..8");
        }
        FilterPhaseMode phase = mode == null ? FilterPhaseMode.ZERO_PHASE : mode;
        double[] y = x.clone();
        if (type == AdvancedAnalyticsType.LOW_PASS_FILTER) {
            requireCutoff(lowCutoffHz, nyquist, "cutoff");
            y = sosFilter(y, designLowpass(order, lowCutoffHz, sampleRateHz), phase, token);
        } else if (type == AdvancedAnalyticsType.HIGH_PASS_FILTER) {
            requireCutoff(lowCutoffHz, nyquist, "cutoff");
            y = sosFilter(y, designHighpass(order, lowCutoffHz, sampleRateHz), phase, token);
        } else if (type == AdvancedAnalyticsType.BAND_PASS_FILTER) {
            requireCutoff(lowCutoffHz, nyquist, "lowCutoff");
            requireCutoff(highCutoffHz, nyquist, "highCutoff");
            if (!(lowCutoffHz < highCutoffHz)) {
                throw new IllegalArgumentException("lowCutoff must be < highCutoff");
            }
            y = sosFilter(y, designHighpass(order, lowCutoffHz, sampleRateHz), phase, token);
            y = sosFilter(y, designLowpass(order, highCutoffHz, sampleRateHz), phase, token);
        } else {
            throw new IllegalArgumentException("unsupported filter type " + type);
        }
        double delay = phase == FilterPhaseMode.CAUSAL
                ? order / (4d * Math.max(lowCutoffHz, 1e-9))
                : 0d;
        return new Result(y, type, lowCutoffHz, highCutoffHz, order, sampleRateHz, phase, delay);
    }

    private static void requireCutoff(double cutoff, double nyquist, String name) {
        AnalyticsLimits.requireFinite(cutoff, name);
        if (!(cutoff > 0)) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
        if (!(cutoff < nyquist)) {
            throw new IllegalArgumentException(name + " must be < Nyquist (" + nyquist + " Hz)");
        }
    }

    private static double[][] designLowpass(int order, double fc, double fs) {
        return designPrototype(order, fc, fs, false);
    }

    private static double[][] designHighpass(int order, double fc, double fs) {
        return designPrototype(order, fc, fs, true);
    }

    /**
     * Analog Butterworth poles on the unit circle, bilinear-transformed.
     * Each SOS is {@code b0,b1,b2,a1,a2} with a0=1.
     */
    private static double[][] designPrototype(int order, double fc, double fs, boolean highpass) {
        double k = Math.tan(Math.PI * fc / fs);
        int sections = (order + 1) / 2;
        double[][] sos = new double[sections][5];
        int si = 0;
        if ((order & 1) == 1) {
            double n0 = k;
            double n1 = k;
            double d0 = k + 1d;
            double d1 = k - 1d;
            if (highpass) {
                n0 = 1d;
                n1 = -1d;
            }
            sos[si++] = new double[] {n0 / d0, n1 / d0, 0d, d1 / d0, 0d};
        }
        for (int i = 0; i < order / 2; i++) {
            double theta = Math.PI * (2d * i + 1d) / (2d * order);
            double sigma = Math.sin(theta);
            double a = 1d + 2d * sigma * k + k * k;
            double b0;
            double b1;
            double b2;
            if (highpass) {
                b0 = 1d / a;
                b1 = -2d / a;
                b2 = 1d / a;
            } else {
                b0 = (k * k) / a;
                b1 = 2d * b0;
                b2 = b0;
            }
            double a1 = 2d * (k * k - 1d) / a;
            double a2 = (1d - 2d * sigma * k + k * k) / a;
            sos[si++] = new double[] {b0, b1, b2, a1, a2};
        }
        return sos;
    }

    private static double[] sosFilter(double[] x, double[][] sos, FilterPhaseMode mode, CancellationToken token) {
        int pad = Math.min(x.length - 1, 9 * sos.length);
        double[] work = reflectPad(x, pad);
        applySos(work, sos, token);
        if (mode == FilterPhaseMode.ZERO_PHASE) {
            reverse(work);
            applySos(work, sos, token);
            reverse(work);
        }
        double[] y = new double[x.length];
        System.arraycopy(work, pad, y, 0, x.length);
        return y;
    }

    private static void applySos(double[] x, double[][] sos, CancellationToken token) {
        for (double[] s : sos) {
            if (token != null && token.isCancellationRequested()) {
                throw new AnalysisCancelledException();
            }
            double b0 = s[0];
            double b1 = s[1];
            double b2 = s[2];
            double a1 = s[3];
            double a2 = s[4];
            double x1 = 0;
            double x2 = 0;
            double y1 = 0;
            double y2 = 0;
            for (int i = 0; i < x.length; i++) {
                double xn = x[i];
                double yn = b0 * xn + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
                x[i] = yn;
                x2 = x1;
                x1 = xn;
                y2 = y1;
                y1 = yn;
            }
        }
    }

    private static double[] reflectPad(double[] x, int pad) {
        if (pad <= 0 || x.length == 0) {
            return x.clone();
        }
        double[] y = new double[x.length + 2 * pad];
        for (int i = 0; i < pad; i++) {
            int src = Math.min(x.length - 1, pad - i);
            y[i] = 2d * x[0] - x[src];
        }
        System.arraycopy(x, 0, y, pad, x.length);
        for (int i = 0; i < pad; i++) {
            int src = Math.max(0, x.length - 2 - i);
            y[pad + x.length + i] = 2d * x[x.length - 1] - x[src];
        }
        return y;
    }

    private static void reverse(double[] x) {
        for (int i = 0, j = x.length - 1; i < j; i++, j--) {
            double t = x[i];
            x[i] = x[j];
            x[j] = t;
        }
    }
}
