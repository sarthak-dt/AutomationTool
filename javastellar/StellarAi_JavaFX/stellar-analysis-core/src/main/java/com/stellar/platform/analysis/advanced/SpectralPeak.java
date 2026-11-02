package com.stellar.platform.analysis.advanced;

public record SpectralPeak(
        int rank,
        double frequencyHz,
        double magnitude,
        double periodSeconds,
        Double bandwidthHz,
        String harmonicLabel
) {
}
