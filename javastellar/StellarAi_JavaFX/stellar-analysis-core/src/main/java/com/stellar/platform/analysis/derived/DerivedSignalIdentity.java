package com.stellar.platform.analysis.derived;

import com.stellar.platform.analysis.model.AlignmentPolicy;
import com.stellar.platform.analysis.model.AnalysisVersion;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class DerivedSignalIdentity {

    private DerivedSignalIdentity() {
    }

    public static String fingerprint(
            DerivedExpression expression,
            AlignmentPolicy policy,
            String analysisVersion
    ) {
        String payload = expression.canonical()
                + "|" + (policy == null ? AlignmentPolicy.EXACT_TIMESTAMP : policy)
                + "|" + (analysisVersion == null || analysisVersion.isBlank()
                ? AnalysisVersion.CURRENT : analysisVersion);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 required", e);
        }
    }

    public static SignalCatalogKey catalogKey(DerivedExpression expression, String fingerprint) {
        String shortId = fingerprint.length() > 12 ? fingerprint.substring(0, 12) : fingerprint;
        return new SignalCatalogKey(
                "derived:" + shortId,
                SignalSourceType.ANALYSIS_DERIVED,
                "ANALYSIS",
                0L,
                0,
                expression.canonical(),
                fingerprint
        );
    }
}
