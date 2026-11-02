package com.stellar.platform.analysis.stats;

import com.stellar.platform.analysis.model.SignalStatistics;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.source.InMemoryAnalysisSampleSource;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.parser.spi.CancellationToken;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoricalStatisticsTest {

    @Test
    void rmsAndExtremaIgnoreCategoricalInvalidAndFailedSamples() {
        SignalCatalogKey key = new SignalCatalogKey(
                "ETC_Fan2_output", SignalSourceType.MF4_NATIVE, "DIRECT_MEASUREMENT", 0L);
        InMemoryAnalysisSampleSource source = new InMemoryAnalysisSampleSource();
        source.put(key, List.of(
                new SignalSamplePoint(1L, 10.0, 10L, null, SignalValidity.VALID),
                new SignalSamplePoint(2L, Double.NaN, 0L, "FALSE", SignalValidity.VALID),
                new SignalSamplePoint(3L, Double.NaN, 1L, "TRUE", SignalValidity.VALID),
                new SignalSamplePoint(4L, 255.0, 255L, "invalid", SignalValidity.INVALID_RAW_CODE),
                new SignalSamplePoint(5L, Double.NaN, 9L, null, SignalValidity.CONVERSION_FAILED),
                new SignalSamplePoint(6L, 30.0, 30L, null, SignalValidity.VALID)));
        SignalStatistics stats = StreamingStatisticsAnalyzer.compute(
                source, key, TimeWindow.unbounded(), CancellationToken.NONE);
        assertEquals(2, stats.validCount());
        assertEquals(4, stats.missingCount());
        assertEquals(10.0, stats.min(), 0.0);
        assertEquals(30.0, stats.max(), 0.0);
        assertEquals(20.0, stats.mean(), 0.0);
        assertEquals(Math.sqrt((100.0 + 900.0) / 2.0), stats.rms(), 1e-12);
    }

    @Test
    void aCategoricalSeriesHasNoInventedNumericRange() {
        SignalCatalogKey key = new SignalCatalogKey(
                "ETC_Fan2_output", SignalSourceType.MF4_NATIVE, "DIRECT_MEASUREMENT", 0L);
        InMemoryAnalysisSampleSource source = new InMemoryAnalysisSampleSource();
        source.add(key, 5_998_194L, Double.NaN, 0L, "FALSE");
        SignalStatistics stats = StreamingStatisticsAnalyzer.compute(
                source, key, TimeWindow.unbounded(), CancellationToken.NONE);
        assertEquals(0, stats.validCount());
        assertEquals(1, stats.missingCount());
        assertTrue(Double.isNaN(stats.min()));
        assertTrue(Double.isNaN(stats.max()));
        assertTrue(Double.isNaN(stats.rms()));
    }
}
