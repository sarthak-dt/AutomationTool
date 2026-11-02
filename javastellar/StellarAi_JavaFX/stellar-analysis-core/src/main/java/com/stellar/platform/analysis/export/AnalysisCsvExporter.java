package com.stellar.platform.analysis.export;

import com.stellar.platform.analysis.advanced.AdvancedAnalyticsResult;
import com.stellar.platform.analysis.advanced.CycleAnalyzer;
import com.stellar.platform.analysis.advanced.PsdResult;
import com.stellar.platform.analysis.advanced.SpectralPeak;
import com.stellar.platform.analysis.advanced.SpectrumResult;
import com.stellar.platform.analysis.model.AnalysisResult;
import com.stellar.platform.analysis.model.EngineeringFinding;
import com.stellar.platform.analysis.model.SignalStatistics;
import com.stellar.platform.domain.catalog.SignalSamplePoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class AnalysisCsvExporter {

    public long writeSummary(AnalysisResult result, Path destination) throws IOException {
        StringBuilder sb = new StringBuilder("signal,sampleCount,valid,missing,min,max,mean,stddev,rms,delta\n");
        for (SignalStatistics s : result.statistics()) {
            sb.append(s.signalKey().signalName()).append(',')
                    .append(s.sampleCount()).append(',')
                    .append(s.validCount()).append(',')
                    .append(s.missingCount()).append(',')
                    .append(s.min()).append(',')
                    .append(s.max()).append(',')
                    .append(s.mean()).append(',')
                    .append(s.standardDeviation()).append(',')
                    .append(s.rms()).append(',')
                    .append(s.delta()).append('\n');
        }
        result.metrics().forEach((k, v) -> sb.append(k).append(",,,,,,,,,").append(v).append('\n'));
        return write(destination, sb.toString());
    }

    public long writeFindings(AnalysisResult result, Path destination) throws IOException {
        StringBuilder sb = new StringBuilder("findingId,type,severity,startNanos,endNanos,summary\n");
        for (EngineeringFinding f : result.findings()) {
            sb.append(f.findingId()).append(',')
                    .append(f.findingType()).append(',')
                    .append(f.severity()).append(',')
                    .append(f.startTimestampNanos()).append(',')
                    .append(f.effectiveEndNanos()).append(',')
                    .append(escape(f.summary())).append('\n');
        }
        return write(destination, sb.toString());
    }

    public long writeSpectrum(SpectrumResult spectrum, Path destination) throws IOException {
        StringBuilder sb = new StringBuilder("frequency_hz,magnitude,phase_rad\n");
        if (spectrum != null) {
            for (int i = 0; i < spectrum.binCount(); i++) {
                sb.append(spectrum.frequencyHz()[i]).append(',')
                        .append(spectrum.magnitude()[i]).append(',')
                        .append(spectrum.phaseRadians()[i]).append('\n');
            }
        }
        return write(destination, sb.toString());
    }

    public long writePsd(PsdResult psd, Path destination) throws IOException {
        StringBuilder sb = new StringBuilder("frequency_hz,power_density\n");
        if (psd != null && psd.frequencyHz() != null) {
            for (int i = 0; i < psd.frequencyHz().length; i++) {
                sb.append(psd.frequencyHz()[i]).append(',').append(psd.powerDensity()[i]).append('\n');
            }
        }
        return write(destination, sb.toString());
    }

    public long writePeaks(List<SpectralPeak> peaks, Path destination) throws IOException {
        StringBuilder sb = new StringBuilder("rank,frequency_hz,magnitude,period_s,bandwidth_hz,harmonic\n");
        if (peaks != null) {
            for (SpectralPeak p : peaks) {
                sb.append(p.rank()).append(',')
                        .append(p.frequencyHz()).append(',')
                        .append(p.magnitude()).append(',')
                        .append(p.periodSeconds()).append(',')
                        .append(p.bandwidthHz() == null ? "" : p.bandwidthHz()).append(',')
                        .append(escape(p.harmonicLabel())).append('\n');
            }
        }
        return write(destination, sb.toString());
    }

    public long writeLag(AdvancedAnalyticsResult result, Path destination) throws IOException {
        StringBuilder sb = new StringBuilder("best_lag_s,best_lag_ns,correlation,pairs,sample_rate_hz,sign_convention,quality\n");
        if (result != null && result.lag() != null) {
            var lag = result.lag();
            sb.append(lag.bestLagSeconds()).append(',')
                    .append(lag.bestLagNanos()).append(',')
                    .append(lag.correlationAtLag()).append(',')
                    .append(lag.pairCount()).append(',')
                    .append(lag.sampleRateHz()).append(',')
                    .append(escape(lag.signConvention())).append(',')
                    .append(escape(lag.quality())).append('\n');
        }
        return write(destination, sb.toString());
    }

    public long writeCycles(List<CycleAnalyzer.Cycle> cycles, Path destination) throws IOException {
        StringBuilder sb = new StringBuilder("index,start_ns,end_ns,duration_s,min,max,mean,rms,peak_to_peak\n");
        if (cycles != null) {
            for (CycleAnalyzer.Cycle c : cycles) {
                sb.append(c.index()).append(',')
                        .append(c.startNanos()).append(',')
                        .append(c.endNanos()).append(',')
                        .append(c.durationSeconds()).append(',')
                        .append(c.min()).append(',')
                        .append(c.max()).append(',')
                        .append(c.mean()).append(',')
                        .append(c.rms()).append(',')
                        .append(c.peakToPeak()).append('\n');
            }
        }
        return write(destination, sb.toString());
    }

    public long writeDerived(List<SignalSamplePoint> samples, Path destination) throws IOException {
        StringBuilder sb = new StringBuilder("timestamp_nanos,physical,validity\n");
        if (samples != null) {
            for (SignalSamplePoint p : samples) {
                sb.append(p.timestampNanos()).append(',').append(p.physicalValue())
                        .append(',').append(p.validity().name()).append('\n');
            }
        }
        return write(destination, sb.toString());
    }

    private static long write(Path destination, String text) throws IOException {
        Files.createDirectories(destination.getParent() == null ? Path.of(".") : destination.getParent());
        Files.writeString(destination, text, StandardCharsets.UTF_8);
        return Files.size(destination);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
