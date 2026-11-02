package com.stellar.platform.processing.drive.speed;

/**
 * Quality-validated peak of one speed source. Absence means no defensible maximum exists.
 */
public record QualityAwarePeak(
        Double valueKph,
        Long timestampNanos,
        SpeedQualityAssessment quality,
        String reason
) {
    public QualityAwarePeak {
        quality = quality == null ? SpeedQualityAssessment.empty() : quality;
        reason = reason == null ? "" : reason;
        if (valueKph != null && !Double.isFinite(valueKph)) {
            valueKph = null;
            timestampNanos = null;
        }
    }

    public static QualityAwarePeak none(SpeedQualityAssessment quality, String reason) {
        return new QualityAwarePeak(null, null, quality, reason);
    }

    public boolean present() {
        return valueKph != null && timestampNanos != null;
    }
}
