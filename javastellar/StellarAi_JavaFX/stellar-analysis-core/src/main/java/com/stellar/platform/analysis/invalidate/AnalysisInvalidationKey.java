package com.stellar.platform.analysis.invalidate;

import com.stellar.platform.analysis.model.AlignmentPolicy;
import com.stellar.platform.analysis.model.AnalysisRequest;
import com.stellar.platform.analysis.model.AnalysisType;

import java.util.Objects;

/**
 * An analysis is stale only when a data or parameter dependency changes.
 * Theme, inspector collapse and grouping UI are not dependencies.
 */
public record AnalysisInvalidationKey(
        long artifactGeneration,
        String sourceFingerprint,
        String dbcFingerprint,
        AnalysisType type,
        String expression,
        AlignmentPolicy alignment,
        String parameterDigest,
        String analysisVersion
) {
    public static AnalysisInvalidationKey of(AnalysisRequest request) {
        Objects.requireNonNull(request, "request");
        return new AnalysisInvalidationKey(
                request.artifactGeneration(),
                request.sourceFingerprint(),
                request.dbcFingerprint(),
                request.analysisType(),
                request.parameters().derivedOp(),
                request.alignmentPolicy(),
                digest(request),
                request.analysisVersion()
        );
    }

    public boolean matches(AnalysisInvalidationKey other) {
        return other != null && equals(other);
    }

    private static String digest(AnalysisRequest request) {
        return request.parameters().thresholdOperator()
                + ":" + request.parameters().threshold()
                + ":" + request.parameters().hysteresisExit()
                + ":" + request.parameters().bandMin()
                + ":" + request.parameters().bandMax()
                + ":" + request.parameters().scaleConstant()
                + ":" + request.parameters().expectedState()
                + ":" + request.resolveWindow().startNanos()
                + ":" + request.resolveWindow().endNanos();
    }
}
