package com.stellar.platform.analysis.advanced;

import com.stellar.platform.analysis.source.AnalysisCancelledException;
import com.stellar.platform.parser.spi.CancellationToken;

/**
 * In-place radix-2 Cooley–Tukey FFT. Not a naïve O(N²) DFT.
 */
public final class RealFft {

    private RealFft() {
    }

    public static void fft(double[] re, double[] im, CancellationToken token) {
        transform(re, im, false, token);
    }

    public static void ifft(double[] re, double[] im, CancellationToken token) {
        transform(re, im, true, token);
        int n = re.length;
        double scale = 1d / n;
        for (int i = 0; i < n; i++) {
            re[i] *= scale;
            im[i] *= scale;
        }
    }

    private static void transform(double[] re, double[] im, boolean inverse, CancellationToken token) {
        if (re == null || im == null || re.length != im.length) {
            throw new IllegalArgumentException("FFT buffers must be equal non-null length");
        }
        int n = re.length;
        if (n == 0 || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException("FFT length must be a power of two");
        }
        bitReverse(re, im);
        for (int len = 2; len <= n; len <<= 1) {
            if (token != null && token.isCancellationRequested()) {
                throw new AnalysisCancelledException();
            }
            double angle = (inverse ? 2d : -2d) * Math.PI / len;
            double wlr = Math.cos(angle);
            double wli = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double wr = 1d;
                double wi = 0d;
                int half = len >> 1;
                for (int j = 0; j < half; j++) {
                    int u = i + j;
                    int v = u + half;
                    double tr = wr * re[v] - wi * im[v];
                    double ti = wr * im[v] + wi * re[v];
                    re[v] = re[u] - tr;
                    im[v] = im[u] - ti;
                    re[u] += tr;
                    im[u] += ti;
                    double nwr = wr * wlr - wi * wli;
                    wi = wr * wli + wi * wlr;
                    wr = nwr;
                }
            }
        }
    }

    private static void bitReverse(double[] re, double[] im) {
        int n = re.length;
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>= 1;
            }
            j ^= bit;
            if (i < j) {
                double tr = re[i];
                re[i] = re[j];
                re[j] = tr;
                double ti = im[i];
                im[i] = im[j];
                im[j] = ti;
            }
        }
    }
}
