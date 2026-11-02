package com.stellar.platform.analysis.advanced;

/**
 * Explicit window functions. Coherent gain {@code cg = mean(w)} is used for amplitude
 * normalization. Equivalent noise bandwidth uses {@code sum(w²)}.
 */
public final class WindowFunctions {

    private WindowFunctions() {
    }

    public static double[] weights(WindowFunction function, int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("window length must be > 0");
        }
        double[] w = new double[n];
        WindowFunction fn = function == null ? WindowFunction.HANN : function;
        if (n == 1) {
            w[0] = 1d;
            return w;
        }
        double denom = n - 1;
        for (int i = 0; i < n; i++) {
            double x = i / denom;
            w[i] = switch (fn) {
                case RECTANGULAR -> 1d;
                case HANN -> 0.5d * (1d - Math.cos(2d * Math.PI * x));
                case HAMMING -> 0.54d - 0.46d * Math.cos(2d * Math.PI * x);
                case BLACKMAN -> 0.42d - 0.5d * Math.cos(2d * Math.PI * x)
                        + 0.08d * Math.cos(4d * Math.PI * x);
            };
        }
        return w;
    }

    public static double coherentGain(double[] w) {
        double s = 0d;
        for (double v : w) {
            s += v;
        }
        return w.length == 0 ? 1d : s / w.length;
    }

    public static double sumSquares(double[] w) {
        double s = 0d;
        for (double v : w) {
            s += v * v;
        }
        return s;
    }
}
