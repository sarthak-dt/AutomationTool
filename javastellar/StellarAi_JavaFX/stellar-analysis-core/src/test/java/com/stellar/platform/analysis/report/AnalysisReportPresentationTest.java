package com.stellar.platform.analysis.report;

import com.stellar.platform.analysis.model.AnalysisResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisReportPresentationTest {

    @Test
    void cursorMetricsStayNumericAndReadAsPlainValues() {
        double timestampA = 54_365_300_000d;
        double timestampB = 55_507_800_000d;
        AnalysisResult result = AnalysisResult.builder("cursor")
                .metric("IN_Boost_pressure.timestampA", timestampA)
                .metric("IN_Boost_pressure.timestampB", timestampB)
                .metric("IN_Boost_pressure.timeDeltaSeconds", 1.14252d)
                .metric("ACM_Vgt_boost_pressure_dmnd.timeDeltaSeconds", 1.308649123d)
                .build();

        String text = AnalysisReportFormatter.format(result);

        assertTrue(text.contains("IN_Boost_pressure"));
        assertTrue(text.contains("Timestamp A"));
        assertTrue(text.contains("54,365,300,000"));
        assertTrue(text.contains("55,507,800,000"));
        assertTrue(text.contains("Time delta"));
        assertTrue(text.contains("1.14252 s"));
        assertTrue(text.contains("1.308649 s"));
        assertTrue(text.contains("ACM_Vgt_boost_pressure_dmnd"));
        assertFalse(text.toLowerCase().contains("e+"));
        assertFalse(text.contains("timestampA"));
        assertEquals(timestampA, result.metrics().get("IN_Boost_pressure.timestampA"));
        assertEquals(timestampB, result.metrics().get("IN_Boost_pressure.timestampB"));
    }
}
