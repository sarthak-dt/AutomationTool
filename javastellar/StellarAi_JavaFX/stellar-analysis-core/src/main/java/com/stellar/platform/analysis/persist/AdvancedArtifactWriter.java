package com.stellar.platform.analysis.persist;

import com.stellar.platform.analysis.advanced.AdvancedAnalyticsResult;
import com.stellar.platform.analysis.advanced.PsdResult;
import com.stellar.platform.analysis.advanced.SpectralPeak;
import com.stellar.platform.analysis.advanced.SpectrumResult;
import com.stellar.platform.analysis.advanced.WindowFunction;
import com.stellar.platform.analysis.export.AnalysisCsvExporter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

/**
 * Commits spectrum / PSD / derived-series under Analysis Storage.
 * Cancelled runs are never marked COMPLETED.
 */
public final class AdvancedArtifactWriter {

    private final AnalysisCsvExporter csv = new AnalysisCsvExporter();

    public Path commit(Path projectArtifactsRoot, AdvancedAnalyticsResult result, boolean persistDerived) {
        if (result == null || !result.status().successful()) {
            return null;
        }
        Path staging = AnalysisStoragePaths.stagingRoot(projectArtifactsRoot, result.analysisId());
        Path dest = AnalysisStoragePaths.analysisRoot(projectArtifactsRoot, result.analysisId());
        try {
            deleteTree(staging);
            Files.createDirectories(staging);
            Files.writeString(AnalysisStoragePaths.advancedManifest(staging), manifest(result), StandardCharsets.UTF_8);
            if (result.spectrum() != null) {
                csv.writeSpectrum(result.spectrum(), AnalysisStoragePaths.spectrum(staging));
            }
            if (result.psd() != null) {
                csv.writePsd(result.psd(), AnalysisStoragePaths.psd(staging));
            }
            if (!result.peaks().isEmpty()) {
                csv.writePeaks(result.peaks(), AnalysisStoragePaths.peaks(staging));
            }
            if (result.lag() != null) {
                csv.writeLag(result, AnalysisStoragePaths.lagSummary(staging));
            }
            if (result.cycles() != null && !result.cycles().cycles().isEmpty()) {
                csv.writeCycles(result.cycles().cycles(), AnalysisStoragePaths.cycles(staging));
            }
            if (persistDerived && result.derived() != null) {
                csv.writeDerived(result.derived().points(), AnalysisStoragePaths.derivedSeries(staging));
            }
            deleteTree(dest);
            Files.createDirectories(dest.getParent());
            try {
                Files.move(staging, dest, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.FileSystemException ex) {
                Files.move(staging, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            return dest;
        } catch (IOException e) {
            deleteTree(staging);
            throw new IllegalStateException("Cannot commit advanced analysis artifact", e);
        }
    }

    public void discardStaging(Path projectArtifactsRoot, String analysisId) {
        deleteTree(AnalysisStoragePaths.stagingRoot(projectArtifactsRoot, analysisId));
    }

    public boolean hasCommitted(Path projectArtifactsRoot, String analysisId) {
        return Files.isRegularFile(AnalysisStoragePaths.advancedManifest(
                AnalysisStoragePaths.analysisRoot(projectArtifactsRoot, analysisId)));
    }

    public String loadManifest(Path projectArtifactsRoot, String analysisId) {
        Path p = AnalysisStoragePaths.advancedManifest(AnalysisStoragePaths.analysisRoot(projectArtifactsRoot, analysisId));
        try {
            return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    public SpectrumResult loadSpectrum(Path projectArtifactsRoot, String analysisId) {
        Path p = AnalysisStoragePaths.spectrum(AnalysisStoragePaths.analysisRoot(projectArtifactsRoot, analysisId));
        double[][] cols = readNumericCsv(p, 3);
        if (cols == null) {
            return null;
        }
        return new SpectrumResult(cols[0], cols[1], cols[2], 0, cols[0].length, cols[0].length,
                WindowFunction.HANN, 0, 0, 0, 0, "", "", "restored", false);
    }

    public PsdResult loadPsd(Path projectArtifactsRoot, String analysisId) {
        Path p = AnalysisStoragePaths.psd(AnalysisStoragePaths.analysisRoot(projectArtifactsRoot, analysisId));
        double[][] cols = readNumericCsv(p, 2);
        if (cols == null) {
            return null;
        }
        return new PsdResult(cols[0], cols[1], 0, cols[0].length, 0, 0, 0, WindowFunction.HANN,
                0, 0, 0, 0, "", "", "restored");
    }

    public java.util.List<SpectralPeak> loadPeaks(Path projectArtifactsRoot, String analysisId) {
        Path p = AnalysisStoragePaths.peaks(AnalysisStoragePaths.analysisRoot(projectArtifactsRoot, analysisId));
        if (!Files.isRegularFile(p)) {
            return java.util.List.of();
        }
        try {
            java.util.List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            java.util.List<SpectralPeak> peaks = new java.util.ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                String[] c = lines.get(i).split(",", -1);
                if (c.length < 4) {
                    continue;
                }
                peaks.add(new SpectralPeak(
                        Integer.parseInt(c[0]),
                        Double.parseDouble(c[1]),
                        Double.parseDouble(c[2]),
                        Double.parseDouble(c[3]),
                        c.length > 4 && !c[4].isBlank() ? Double.parseDouble(c[4]) : null,
                        c.length > 5 ? c[5] : ""));
            }
            return java.util.List.copyOf(peaks);
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    private static double[][] readNumericCsv(Path path, int columns) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            java.util.List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int n = Math.max(0, lines.size() - 1);
            double[][] cols = new double[columns][n];
            int w = 0;
            for (int i = 1; i < lines.size(); i++) {
                String[] c = lines.get(i).split(",", -1);
                if (c.length < columns) {
                    continue;
                }
                for (int k = 0; k < columns; k++) {
                    cols[k][w] = Double.parseDouble(c[k]);
                }
                w++;
            }
            if (w != n) {
                for (int k = 0; k < columns; k++) {
                    cols[k] = java.util.Arrays.copyOf(cols[k], w);
                }
            }
            return cols;
        } catch (Exception e) {
            return null;
        }
    }

    private static String manifest(AdvancedAnalyticsResult result) {
        return "{\"analysisId\":\"" + esc(result.analysisId())
                + "\",\"type\":\"" + result.type()
                + "\",\"status\":\"" + result.status()
                + "\",\"cacheKey\":\"" + esc(result.cacheKey())
                + "\",\"resampled\":" + result.resampled()
                + ",\"sampleRateHz\":" + result.effectiveSampleRateHz()
                + ",\"reproducibility\":\"" + esc(result.reproducibility())
                + "\",\"message\":\"" + esc(result.message())
                + "\"}";
    }

    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
