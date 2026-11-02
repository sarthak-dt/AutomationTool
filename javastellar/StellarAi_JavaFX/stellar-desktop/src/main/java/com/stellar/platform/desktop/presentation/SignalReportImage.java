package com.stellar.platform.desktop.presentation;

/** One rendered signal-analysis page. {@code png} is empty when rendering failed. */
public record SignalReportImage(String fileName, byte[] png, String title, boolean failed) {

    public SignalReportImage {
        fileName = fileName == null || fileName.isBlank() ? "signals-waveform.png" : fileName;
        title = title == null || title.isBlank() ? "Signal Analysis" : title;
    }

    public boolean hasImage() {
        return !failed && png != null && png.length > 0;
    }
}
