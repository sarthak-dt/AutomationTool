package com.stellar.platform.analysis.advanced;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;

import java.util.List;

public record DerivedSeries(
        SignalCatalogKey identity,
        String expression,
        long[] timestamps,
        double[] values,
        boolean resampled,
        ResampleMethod resampleMethod,
        double sampleRateHz
) {
    public List<SignalSamplePoint> points() {
        return BoundedSampleBuffer.toPoints(new BoundedSampleBuffer.Series(timestamps, values, 0, 0));
    }
}
