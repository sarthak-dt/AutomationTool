package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineeringEvidenceItem;
import com.stellar.platform.analysis.investigation.EvidenceKind;
import com.stellar.platform.analysis.source.AnalysisCancelledException;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.processing.graph.GraphQueryProvider;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds report waveforms from persisted investigation signals, or from the current
 * analysis selection when a new report is generated and nothing was persisted.
 * Re-export never invents a selection from the open graph.
 */
public final class SignalReportLoader {

    private static final int MAX_POINTS = 480;

    public List<SignalReportImage> load(
            DesktopAppService app,
            com.stellar.platform.analysis.investigation.EngineeringInvestigation investigation,
            boolean persistedOnly,
            CancellationToken token
    ) {
        List<SignalCatalogKey> keys = keys(app, investigation, persistedOnly);
        if (keys.isEmpty()) {
            return List.of();
        }
        GraphSeriesQuery query = query(app);
        if (query == null) {
            return List.of(new SignalReportImage(
                    "signals-waveform.png", null, "Signal Analysis", true));
        }
        AnalysisMeasurementContext live = app == null ? null : app.activeAnalysisMeasurement();
        String projectId = investigation != null && investigation.projectId() != null
                && !investigation.projectId().isBlank()
                ? investigation.projectId()
                : (app == null ? "" : app.currentProjectId());
        ReportMapScope.Decision scope = ReportMapScope.decide(
                investigation,
                app == null ? null : ReportMapScope.durableScope(app.metadataStore(), projectId),
                app != null && app.isProjectWideAnalysis(),
                live == null ? "" : live.sourceId(),
                live == null ? "" : live.fileName(),
                persistedOnly);
        long[] window = analysisWindow(investigation);
        long start = window[0];
        long end = window[1];
        boolean bounded = window[2] == 1L;
        List<SignalReportRenderer.Track> tracks = new ArrayList<>();
        int failed = 0;
        for (SignalCatalogKey key : keys) {
            if (token != null && token.isCancellationRequested()) {
                throw new AnalysisCancelledException();
            }
            try {
                Series series = samples(
                        query, key, start, end, bounded, ReportMapScope.seriesScope(scope), token);
                if (series.values().length == 0) {
                    continue;
                }
                String unit = "";
                try {
                    unit = query.unit(key);
                } catch (RuntimeException ignored) {
                    unit = "";
                }
                tracks.add(new SignalReportRenderer.Track(
                        key.signalName(), unit, series.timeSeconds(), series.values(),
                        bounded ? start / 1_000_000_000d : Double.NaN,
                        bounded ? end / 1_000_000_000d : Double.NaN,
                        series.validity()));
            } catch (AnalysisCancelledException cancelled) {
                throw cancelled;
            } catch (RuntimeException ex) {
                failed++;
            }
        }
        if (tracks.isEmpty()) {
            if (failed > 0) {
                return List.of(new SignalReportImage(
                        "signals-waveform.png", null, "Signal Analysis", true));
            }
            return List.of();
        }
        return SignalReportRenderer.render(tracks, includedEventSpans(app, investigation), token);
    }

    /**
     * Marks for bookmarks the investigation has included. A bookmark that is only saved
     * stays off the waveform. The bookmark timestamp is not rewritten.
     */
    public static List<SignalReportRenderer.EventSpan> includedEventSpans(
            DesktopAppService app,
            com.stellar.platform.analysis.investigation.EngineeringInvestigation investigation
    ) {
        if (app == null || investigation == null || app.metadataStore() == null) {
            return List.of();
        }
        return includedEventSpans(
                app.metadataStore().listBookmarks(investigation.projectId()),
                investigation.evidence());
    }

    public static List<SignalReportRenderer.EventSpan> includedEventSpans(
            List<EngineeringBookmarkMetadata> bookmarks,
            List<EngineeringEvidenceItem> evidence
    ) {
        if (bookmarks == null || evidence == null || bookmarks.isEmpty() || evidence.isEmpty()) {
            return List.of();
        }
        Map<String, EngineeringBookmarkMetadata> byId = new LinkedHashMap<>();
        for (EngineeringBookmarkMetadata bookmark : bookmarks) {
            if (bookmark != null && bookmark.bookmarkId() != null && !bookmark.bookmarkId().isBlank()) {
                byId.put(bookmark.bookmarkId(), bookmark);
            }
        }
        List<SignalReportRenderer.EventSpan> spans = new ArrayList<>();
        for (EngineeringEvidenceItem item : evidence) {
            if (item == null || item.kind() != EvidenceKind.ANNOTATION) {
                continue;
            }
            EngineeringBookmarkMetadata bookmark = byId.get(item.referenceId());
            if (bookmark == null) {
                continue;
            }
            Long end = EngineerEventDocuments.endNanos(bookmark);
            spans.add(new SignalReportRenderer.EventSpan(
                    InvestigationEventCodec.titleOf(bookmark.observation()),
                    bookmark.timestampNanos() / 1_000_000_000d,
                    end == null ? Double.NaN : end / 1_000_000_000d));
        }
        return List.copyOf(spans);
    }

    private static List<SignalCatalogKey> keys(
            DesktopAppService app,
            com.stellar.platform.analysis.investigation.EngineeringInvestigation investigation,
            boolean persistedOnly
    ) {
        List<SignalCatalogKey> keys = new ArrayList<>();
        if (investigation != null) {
            List<GraphInvestigationSnapshot.Trace> traces =
                    GraphInvestigationCodec.decodeTraces(investigation.graphTracesBlob());
            if (!traces.isEmpty()) {
                for (GraphInvestigationSnapshot.Trace trace : traces) {
                    if (trace != null && !trace.hidden() && trace.key() != null) {
                        keys.add(trace.key());
                    }
                }
            } else {
                for (String blob : investigation.signalKeyBlobs()) {
                    SignalCatalogKey key = GraphInvestigationCodec.decodeKey(blob);
                    if (key != null) {
                        keys.add(key);
                    }
                }
            }
        }
        if (keys.isEmpty() && !persistedOnly && app != null && app.signalsModel() != null) {
            for (SignalCatalogKey key : app.signalsModel().workspaceKeys()) {
                if (key != null) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    private static GraphSeriesQuery query(DesktopAppService app) {
        if (app == null || app.signalsModel() == null
                || !(app.signalsModel().catalogStore() instanceof GraphQueryProvider graphs)) {
            return null;
        }
        return graphs.graphQuery();
    }

    /**
     * Selected analysis window in nanoseconds. Index 2 is 1 when a real window was stored.
     * The tighter overlap of the investigation range and the graph viewport is used, so a
     * zoomed graph is not replaced by the full project and a saved range is not replaced by
     * an unzoomed viewport.
     */
    static long[] analysisWindow(
            com.stellar.platform.analysis.investigation.EngineeringInvestigation investigation) {
        long start = 0L;
        long end = Long.MAX_VALUE / 4;
        boolean bounded = false;
        if (investigation != null && investigation.rangeStartNanos() != null
                && investigation.rangeEndNanos() != null
                && investigation.rangeEndNanos() > investigation.rangeStartNanos()) {
            start = investigation.rangeStartNanos();
            end = investigation.rangeEndNanos();
            bounded = true;
        }
        if (investigation != null && investigation.viewportStartNanos() != null
                && investigation.viewportEndNanos() != null
                && investigation.viewportEndNanos() > investigation.viewportStartNanos()) {
            if (!bounded) {
                start = investigation.viewportStartNanos();
                end = investigation.viewportEndNanos();
                bounded = true;
            } else {
                long overlapStart = Math.max(start, investigation.viewportStartNanos());
                long overlapEnd = Math.min(end, investigation.viewportEndNanos());
                if (overlapEnd > overlapStart) {
                    start = overlapStart;
                    end = overlapEnd;
                }
            }
        }
        return new long[]{start, end, bounded ? 1L : 0L};
    }

    record Series(double[] timeSeconds, double[] values, SignalValidity[] validity) {
    }

    private static Series samples(
            GraphSeriesQuery query,
            SignalCatalogKey key,
            long start,
            long end,
            boolean bounded,
            String scope,
            CancellationToken token
    ) {
        List<SignalSamplePoint> kept = new ArrayList<>();
        if (scope != null && !scope.isBlank()) {
            List<SignalSamplePoint> all = query.queryRange(key, start, end, scope);
            if (all == null) {
                return new Series(new double[0], new double[0], new SignalValidity[0]);
            }
            kept.addAll(decimate(all, token));
        } else if (bounded) {
            List<SignalSamplePoint> raw = new ArrayList<>();
            query.forEachSample(key, start, end, point -> {
                raw.add(point);
                return raw.size() < 250_000;
            }, () -> token == null || !token.isCancellationRequested());
            if (token != null && token.isCancellationRequested()) {
                throw new AnalysisCancelledException();
            }
            kept.addAll(decimate(raw, token));
        } else {
            long count = 0L;
            try {
                count = query.sampleCount(key);
            } catch (RuntimeException ignored) {
                count = 0L;
            }
            int stride = (int) Math.max(1L, count / MAX_POINTS);
            int[] seen = {0};
            query.forEachSample(key, start, end, point -> {
                if (seen[0]++ % stride == 0) {
                    kept.add(point);
                }
                return kept.size() < MAX_POINTS;
            }, () -> token == null || !token.isCancellationRequested());
            if (token != null && token.isCancellationRequested()) {
                throw new AnalysisCancelledException();
            }
        }
        return series(kept);
    }

    static Series series(List<SignalSamplePoint> points) {
        if (points == null || points.isEmpty()) {
            return new Series(new double[0], new double[0], new SignalValidity[0]);
        }
        double[] time = new double[points.size()];
        double[] values = new double[points.size()];
        SignalValidity[] validity = new SignalValidity[points.size()];
        for (int i = 0; i < points.size(); i++) {
            time[i] = points.get(i).timestampNanos() / 1_000_000_000d;
            values[i] = points.get(i).physicalValue();
            validity[i] = points.get(i).validity();
        }
        return new Series(time, values, validity);
    }

    private static List<SignalSamplePoint> decimate(List<SignalSamplePoint> raw, CancellationToken token) {
        if (raw.size() <= MAX_POINTS) {
            return raw;
        }
        List<SignalSamplePoint> kept = new ArrayList<>(MAX_POINTS);
        double step = (raw.size() - 1) / (double) (MAX_POINTS - 1);
        for (int i = 0; i < MAX_POINTS; i++) {
            if (token != null && token.isCancellationRequested()) {
                throw new AnalysisCancelledException();
            }
            kept.add(raw.get((int) Math.round(i * step)));
        }
        return kept;
    }
}
