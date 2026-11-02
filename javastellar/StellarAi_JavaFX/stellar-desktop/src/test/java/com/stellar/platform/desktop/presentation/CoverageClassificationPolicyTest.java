package com.stellar.platform.desktop.presentation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoverageClassificationPolicyTest {

    @Test
    void defaultThresholdsClassifyCoverageBands() {
        CoverageClassificationPolicy policy = CoverageClassificationPolicy.defaults();
        assertEquals(CoverageClassificationPolicy.CoverageQuality.NONE, policy.classify(0.0));
        assertEquals(CoverageClassificationPolicy.CoverageQuality.LOW, policy.classify(0.0896));
        assertEquals(CoverageClassificationPolicy.CoverageQuality.LOW, policy.classify(0.2499));
        assertEquals(CoverageClassificationPolicy.CoverageQuality.MEDIUM, policy.classify(0.25));
        assertEquals(CoverageClassificationPolicy.CoverageQuality.MEDIUM, policy.classify(0.6999));
        assertEquals(CoverageClassificationPolicy.CoverageQuality.HIGH, policy.classify(0.70));
        assertEquals(CoverageClassificationPolicy.CoverageQuality.HIGH, policy.classify(1.0));
    }
}
