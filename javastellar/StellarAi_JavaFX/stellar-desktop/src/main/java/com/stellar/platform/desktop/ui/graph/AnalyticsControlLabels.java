package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.analysis.advanced.AdvancedAnalyticsType;
import com.stellar.platform.analysis.advanced.CrossingMode;
import com.stellar.platform.analysis.advanced.EnvelopeMode;
import com.stellar.platform.analysis.advanced.FilterPhaseMode;
import com.stellar.platform.analysis.advanced.ResampleMethod;
import com.stellar.platform.analysis.advanced.SampleHandling;
import com.stellar.platform.analysis.advanced.WindowFunction;
import com.stellar.platform.analysis.advanced.WindowUnit;
import com.stellar.platform.analysis.advanced.WindowedStatistic;

/** User-facing names for existing analytics enums. Internal values stay unchanged. */
public final class AnalyticsControlLabels {

    private AnalyticsControlLabels() {
    }

    public static String operation(AdvancedAnalyticsType type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case FFT_SPECTRUM -> "FFT Spectrum";
            case POWER_SPECTRAL_DENSITY -> "Power Spectral Density";
            case SPECTRAL_PEAKS -> "Spectral Peaks";
            case RESAMPLE -> "Resample";
            case LOW_PASS_FILTER -> "Low-pass Filter";
            case HIGH_PASS_FILTER -> "High-pass Filter";
            case BAND_PASS_FILTER -> "Band-pass Filter";
            case MOVING_AVERAGE -> "Moving Average";
            case EXPONENTIAL_SMOOTHING -> "Exponential Smoothing";
            case ENVELOPE -> "Envelope";
            case LAG_ANALYSIS -> "Lag Analysis";
            case WINDOWED_STATISTICS -> "Windowed Statistics";
            case CYCLE_ANALYSIS -> "Cycle Analysis";
            case ZERO_CROSSING_ANALYSIS -> "Zero-crossing Analysis";
            case COHERENCE -> "Coherence";
        };
    }

    public static String sampleHandling(SampleHandling value) {
        if (value == null) {
            return "";
        }
        return switch (value) {
            case ORIGINAL -> "Original Samples";
            case RESAMPLE -> "Resample";
        };
    }

    public static String resample(ResampleMethod value) {
        if (value == null) {
            return "";
        }
        return switch (value) {
            case LINEAR_INTERPOLATION -> "Linear Interpolation";
            case PREVIOUS_VALUE_HOLD -> "Previous Value Hold";
            case NEAREST -> "Nearest";
        };
    }

    public static String window(WindowFunction value) {
        if (value == null) {
            return "";
        }
        return switch (value) {
            case RECTANGULAR -> "Rectangular";
            case HANN -> "Hann";
            case HAMMING -> "Hamming";
            case BLACKMAN -> "Blackman";
        };
    }

    public static String phase(FilterPhaseMode value) {
        if (value == null) {
            return "";
        }
        return switch (value) {
            case ZERO_PHASE -> "Zero-phase";
            case CAUSAL -> "Causal";
        };
    }

    public static String windowUnit(WindowUnit value) {
        if (value == null) {
            return "";
        }
        return switch (value) {
            case SAMPLES -> "Samples";
            case MILLISECONDS -> "Milliseconds";
            case SECONDS -> "Seconds";
        };
    }

    public static String statistic(WindowedStatistic value) {
        if (value == null) {
            return "";
        }
        return switch (value) {
            case MEAN -> "Mean";
            case RMS -> "RMS";
            case PEAK_TO_PEAK -> "Peak to Peak";
        };
    }

    public static String envelope(EnvelopeMode value) {
        if (value == null) {
            return "";
        }
        return switch (value) {
            case ABS_PEAK -> "Absolute Peak";
            case RMS -> "RMS";
        };
    }

    public static String crossing(CrossingMode value) {
        if (value == null) {
            return "";
        }
        return switch (value) {
            case POSITIVE -> "Positive";
            case NEGATIVE -> "Negative";
            case ALL -> "All";
        };
    }

    public static String range(String value) {
        if (value == null) {
            return "";
        }
        return switch (value) {
            case "VIEWPORT" -> "Current Viewport";
            case "CURSOR_A_TO_B" -> "Cursor A to B";
            case "FULL" -> "Full Range";
            case "CUSTOM" -> "Custom";
            default -> value;
        };
    }

    public static String analysisType(String value) {
        if (value == null) {
            return "";
        }
        return switch (value) {
            case "STATISTICS" -> "Statistics";
            case "CURSOR_DELTA" -> "Cursor Delta";
            case "RATE_OF_CHANGE" -> "Rate of Change";
            case "THRESHOLD" -> "Threshold";
            case "STATE_TRANSITION" -> "State Transition";
            case "DURATION" -> "Duration";
            case "OPERATING_BAND" -> "Operating Band";
            case "COMPARISON" -> "Comparison";
            case "DERIVED" -> "Derived";
            case "CORRELATION" -> "Correlation";
            case "LAG" -> "Lag";
            default -> value;
        };
    }
}
