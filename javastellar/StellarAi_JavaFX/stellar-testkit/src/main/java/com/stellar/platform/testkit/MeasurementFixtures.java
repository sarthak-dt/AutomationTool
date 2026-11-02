package com.stellar.platform.testkit;

import com.stellar.platform.domain.MeasurementDescriptor;
import com.stellar.platform.domain.MeasurementId;

/**
 * Minimal fixture helpers for future automotive test data.
 * Production modules must not depend on this artifact.
 */
public final class MeasurementFixtures {

    private MeasurementFixtures() {
    }

    public static MeasurementDescriptor sampleDescriptor(String id, String displayName) {
        return new MeasurementDescriptor(new MeasurementId(id), displayName);
    }
}
