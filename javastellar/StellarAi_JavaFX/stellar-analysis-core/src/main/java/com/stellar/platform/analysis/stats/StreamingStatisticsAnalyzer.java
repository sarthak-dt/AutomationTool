package com.stellar.platform.analysis.stats;

import com.stellar.platform.analysis.model.SignalStatistics;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.source.AnalysisSampleSource;
import com.stellar.platform.analysis.source.SampleStreamer;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.parser.spi.CancellationToken;

public final class StreamingStatisticsAnalyzer {

    private StreamingStatisticsAnalyzer() {
    }

    public static SignalStatistics compute(
            AnalysisSampleSource source,
            SignalCatalogKey key,
            TimeWindow window,
            CancellationToken token
    ) {
        WelfordAccumulator acc = new WelfordAccumulator();
        SampleStreamer.forEach(source, key, window, token, sample -> {
            acc.accept(sample.timestampNanos(), sample.usableMeasurement() ? sample.physicalValue() : Double.NaN);
            return true;
        });
        return new SignalStatistics(
                key,
                acc.count(),
                acc.valid(),
                acc.missing(),
                acc.min(),
                acc.max(),
                acc.mean(),
                acc.variance(),
                acc.standardDeviation(),
                acc.rms(),
                acc.range(),
                acc.first(),
                acc.last(),
                acc.delta(),
                acc.firstTimestampNanos(),
                acc.lastTimestampNanos(),
                acc.meanAbsRatePerSecond()
        );
    }
}
