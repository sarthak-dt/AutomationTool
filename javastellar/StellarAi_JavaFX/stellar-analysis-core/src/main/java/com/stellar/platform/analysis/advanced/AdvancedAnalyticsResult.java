package com.stellar.platform.analysis.advanced;

import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class AdvancedAnalyticsResult {

    private final String analysisId;
    private final AdvancedAnalyticsType type;
    private final AnalysisStatus status;
    private final Instant startedAt;
    private final Instant completedAt;
    private final String message;
    private final String cacheKey;
    private final TimeWindow window;
    private final SamplingQuality sampling;
    private final boolean resampled;
    private final ResampleMethod resampleMethod;
    private final double effectiveSampleRateHz;
    private final SpectrumResult spectrum;
    private final SpectrumResult comparisonSpectrum;
    private final PsdResult psd;
    private final List<SpectralPeak> peaks;
    private final FftLagAnalyzer.Result lag;
    private final ZeroCrossingAnalyzer.Result zeroCrossings;
    private final CycleAnalyzer.Result cycles;
    private final CoherenceAnalyzer.Result coherence;
    private final DerivedSeries derived;
    private final List<String> warnings;
    private final List<String> stages;
    private final String reproducibility;
    private final String artifactPath;

    private AdvancedAnalyticsResult(Builder b) {
        this.analysisId = b.analysisId;
        this.type = b.type;
        this.status = b.status;
        this.startedAt = b.startedAt;
        this.completedAt = b.completedAt;
        this.message = b.message == null ? "" : b.message;
        this.cacheKey = b.cacheKey == null ? "" : b.cacheKey;
        this.window = b.window;
        this.sampling = b.sampling;
        this.resampled = b.resampled;
        this.resampleMethod = b.resampleMethod;
        this.effectiveSampleRateHz = b.effectiveSampleRateHz;
        this.spectrum = b.spectrum;
        this.comparisonSpectrum = b.comparisonSpectrum;
        this.psd = b.psd;
        this.peaks = List.copyOf(b.peaks);
        this.lag = b.lag;
        this.zeroCrossings = b.zeroCrossings;
        this.cycles = b.cycles;
        this.coherence = b.coherence;
        this.derived = b.derived;
        this.warnings = List.copyOf(b.warnings);
        this.stages = List.copyOf(b.stages);
        this.reproducibility = b.reproducibility == null ? "" : b.reproducibility;
        this.artifactPath = b.artifactPath == null ? "" : b.artifactPath;
    }

    public static Builder builder(String analysisId, AdvancedAnalyticsType type) {
        return new Builder(analysisId, type);
    }

    public String analysisId() { return analysisId; }
    public AdvancedAnalyticsType type() { return type; }
    public AnalysisStatus status() { return status; }
    public Instant startedAt() { return startedAt; }
    public Instant completedAt() { return completedAt; }
    public String message() { return message; }
    public String cacheKey() { return cacheKey; }
    public TimeWindow window() { return window; }
    public SamplingQuality sampling() { return sampling; }
    public boolean resampled() { return resampled; }
    public ResampleMethod resampleMethod() { return resampleMethod; }
    public double effectiveSampleRateHz() { return effectiveSampleRateHz; }
    public SpectrumResult spectrum() { return spectrum; }
    public SpectrumResult comparisonSpectrum() { return comparisonSpectrum; }
    public PsdResult psd() { return psd; }
    public List<SpectralPeak> peaks() { return peaks; }
    public FftLagAnalyzer.Result lag() { return lag; }
    public ZeroCrossingAnalyzer.Result zeroCrossings() { return zeroCrossings; }
    public CycleAnalyzer.Result cycles() { return cycles; }
    public CoherenceAnalyzer.Result coherence() { return coherence; }
    public DerivedSeries derived() { return derived; }
    public List<String> warnings() { return warnings; }
    public List<String> stages() { return stages; }
    public String reproducibility() { return reproducibility; }
    public String artifactPath() { return artifactPath; }
    public List<SignalCatalogKey> signalKeys() {
        return derived == null ? List.of() : List.of(derived.identity());
    }

    public Builder toBuilder() {
        Builder b = new Builder(analysisId, type);
        b.status = status;
        b.startedAt = startedAt;
        b.completedAt = completedAt;
        b.message = message;
        b.cacheKey = cacheKey;
        b.window = window;
        b.sampling = sampling;
        b.resampled = resampled;
        b.resampleMethod = resampleMethod;
        b.effectiveSampleRateHz = effectiveSampleRateHz;
        b.spectrum = spectrum;
        b.comparisonSpectrum = comparisonSpectrum;
        b.psd = psd;
        b.peaks.addAll(peaks);
        b.lag = lag;
        b.zeroCrossings = zeroCrossings;
        b.cycles = cycles;
        b.coherence = coherence;
        b.derived = derived;
        b.warnings.addAll(warnings);
        b.stages.addAll(stages);
        b.reproducibility = reproducibility;
        b.artifactPath = artifactPath;
        return b;
    }

    public static final class Builder {
        private final String analysisId;
        private final AdvancedAnalyticsType type;
        private AnalysisStatus status = AnalysisStatus.RUNNING;
        private Instant startedAt = Instant.now();
        private Instant completedAt;
        private String message = "";
        private String cacheKey = "";
        private TimeWindow window;
        private SamplingQuality sampling;
        private boolean resampled;
        private ResampleMethod resampleMethod;
        private double effectiveSampleRateHz;
        private SpectrumResult spectrum;
        private SpectrumResult comparisonSpectrum;
        private PsdResult psd;
        private final List<SpectralPeak> peaks = new ArrayList<>();
        private FftLagAnalyzer.Result lag;
        private ZeroCrossingAnalyzer.Result zeroCrossings;
        private CycleAnalyzer.Result cycles;
        private CoherenceAnalyzer.Result coherence;
        private DerivedSeries derived;
        private final List<String> warnings = new ArrayList<>();
        private final List<String> stages = new ArrayList<>();
        private String reproducibility = "";
        private String artifactPath = "";

        private Builder(String analysisId, AdvancedAnalyticsType type) {
            this.analysisId = analysisId;
            this.type = type;
        }

        public Builder status(AnalysisStatus v) { this.status = v; return this; }
        public Builder completedAt(Instant v) { this.completedAt = v; return this; }
        public Builder message(String v) { this.message = v; return this; }
        public Builder cacheKey(String v) { this.cacheKey = v; return this; }
        public Builder window(TimeWindow v) { this.window = v; return this; }
        public Builder sampling(SamplingQuality v) { this.sampling = v; return this; }
        public Builder resampled(boolean v, ResampleMethod method, double rateHz) {
            this.resampled = v;
            this.resampleMethod = method;
            this.effectiveSampleRateHz = rateHz;
            return this;
        }
        public Builder spectrum(SpectrumResult v) { this.spectrum = v; return this; }
        public Builder comparisonSpectrum(SpectrumResult v) { this.comparisonSpectrum = v; return this; }
        public Builder psd(PsdResult v) { this.psd = v; return this; }
        public Builder peaks(List<SpectralPeak> v) {
            this.peaks.clear();
            if (v != null) {
                this.peaks.addAll(v);
            }
            return this;
        }
        public Builder lag(FftLagAnalyzer.Result v) { this.lag = v; return this; }
        public Builder zeroCrossings(ZeroCrossingAnalyzer.Result v) { this.zeroCrossings = v; return this; }
        public Builder cycles(CycleAnalyzer.Result v) { this.cycles = v; return this; }
        public Builder coherence(CoherenceAnalyzer.Result v) { this.coherence = v; return this; }
        public Builder derived(DerivedSeries v) { this.derived = v; return this; }
        public Builder warn(String v) {
            if (v != null && !v.isBlank()) {
                this.warnings.add(v);
            }
            return this;
        }
        public Builder stage(String v) {
            if (v != null && !v.isBlank()) {
                this.stages.add(v);
            }
            return this;
        }
        public Builder reproducibility(String v) { this.reproducibility = v; return this; }
        public Builder artifactPath(String v) { this.artifactPath = v; return this; }

        public AdvancedAnalyticsResult build() {
            return new AdvancedAnalyticsResult(this);
        }
    }
}
