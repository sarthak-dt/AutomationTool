package com.stellar.platform.analysis.advanced;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class AdvancedDerivedIdentity {

    private AdvancedDerivedIdentity() {
    }

    public static String fingerprint(String expression) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(expression.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 required", e);
        }
    }

    public static SignalCatalogKey catalogKey(String expression, String fingerprint) {
        String shortId = fingerprint.length() > 12 ? fingerprint.substring(0, 12) : fingerprint;
        return new SignalCatalogKey(
                "derived:" + shortId,
                SignalSourceType.ANALYSIS_DERIVED,
                "ANALYSIS",
                0L,
                0,
                expression,
                fingerprint
        );
    }

    public static String filterExpression(AdvancedAnalyticsRequest request, SignalCatalogKey source, double sampleRateHz) {
        return "filter:" + request.type()
                + "|src=" + source
                + "|lo=" + request.lowCutoffHz()
                + "|hi=" + request.highCutoffHz()
                + "|order=" + request.filterOrder()
                + "|fs=" + sampleRateHz
                + "|phase=" + request.filterPhaseMode()
                + "|v=" + AdvancedAnalyticsVersion.CURRENT;
    }
}
