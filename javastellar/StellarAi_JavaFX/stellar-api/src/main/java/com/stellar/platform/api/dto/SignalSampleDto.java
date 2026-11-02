package com.stellar.platform.api.dto;

public record SignalSampleDto(long timestampNanos, double physicalValue, Long rawValue, String textualValue) {
    public SignalSampleDto {
        textualValue = textualValue == null ? "" : textualValue;
    }
}
