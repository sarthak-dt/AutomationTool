package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineeringEvidenceItem;
import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.EvidenceKind;
import com.stellar.platform.analysis.investigation.GraphSnapshotMetadata;
import com.stellar.platform.analysis.investigation.report.EvidenceQuality;
import com.stellar.platform.analysis.investigation.report.QualityFinding;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.processing.map.time.RecordingClock;
import com.stellar.platform.processing.map.time.RecordingClockReader;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.ChannelMappingMetadata;
import com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadataStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the report's quality findings from samples and saved evidence that already exist.
 * The scan keeps one signal page at a time and stops at a fixed sample count.
 */
final class ReportQualityFindings {

    private static final int SAMPLE_CAP = 250_000;
    private static final int PAGE = 4_096;
    private static final int SIGNAL_CAP = 8;

    private ReportQualityFindings() {
    }

    static List<QualityFinding> collect(
            EngineeringInvestigation investigation,
            SignalSeriesStore samples,
            ProjectMetadataStore metadata,
            Set<String> sourceNames,
            int definitionCount,
            int unprocessed,
            Map<String, Path> measurementPaths,
            List<ChannelMappingMetadata> mappings
    ) {
        List<QualityFinding> out = new ArrayList<>();
        if (investigation == null) {
            return out;
        }
        out.addAll(EvidenceQuality.dbcCoverage(definitionCount, coverage(mappings, measurementPaths)));
        QualityFinding clocks = EvidenceQuality.clocks(clocks(measurementPaths));
        if (clocks != null) {
            out.add(clocks);
        }
        Scan scan = scan(investigation, samples);
        if (unprocessed > 0 && scan.observations().isEmpty()) {
            out.add(EvidenceQuality.unprocessed(""));
        }
        if (scan.scope() != null) {
            out.add(scan.scope());
        }
        out.addAll(EvidenceQuality.fromSamples(scan.observations()));
        out.addAll(events(investigation, metadata, sourceNames));
        return List.copyOf(out);
    }

    private record Scan(List<EvidenceQuality.SampleObservation> observations, QualityFinding scope) {
    }

    private static Scan scan(EngineeringInvestigation investigation, SignalSeriesStore samples) {
        List<SignalCatalogKey> keys = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String blob : investigation.signalKeyBlobs()) {
            if (blob == null || !seen.add(blob)) {
                continue;
            }
            SignalCatalogKey key = GraphInvestigationCodec.decodeKey(blob);
            if (key != null) {
                keys.add(key);
            }
        }
        keys.sort(Comparator
                .comparing(SignalCatalogKey::sourceFile)
                .thenComparingInt(SignalCatalogKey::channel)
                .thenComparingLong(SignalCatalogKey::canId)
                .thenComparing(SignalCatalogKey::signalName)
                .thenComparing(SignalCatalogKey::messageName));
        List<String> omitted = new ArrayList<>();
        for (int i = SIGNAL_CAP; i < keys.size(); i++) {
            omitted.add(keys.get(i).signalName());
        }
        List<SignalCatalogKey> selected = keys.subList(0, Math.min(SIGNAL_CAP, keys.size()));
        if (selected.isEmpty()) {
            return new Scan(List.of(), null);
        }
        if (samples == null) {
            List<String> names = new ArrayList<>();
            for (SignalCatalogKey key : keys) {
                names.add(key.signalName());
            }
            return new Scan(List.of(), EvidenceQuality.scanScope(List.of(), names, SAMPLE_CAP, true, true));
        }
        List<EvidenceQuality.SampleObservation> observations = new ArrayList<>();
        List<String> examined = new ArrayList<>();
        boolean partial = !omitted.isEmpty();
        for (SignalCatalogKey key : selected) {
            EvidenceQuality.SampleObservation observation = observe(samples, key);
            examined.add(key.signalName());
            if (!observation.scanComplete()) {
                partial = true;
            }
            if (observation.examined() > 0) {
                observations.add(observation);
            }
        }
        return new Scan(observations, EvidenceQuality.scanScope(examined, omitted, SAMPLE_CAP, partial, false));
    }

    /**
     * Project-logical catalog rows and the physical key saved on an investigation
     * describe one engineering signal. Unit stays on the catalog row.
     */
    private static boolean sameEngineeringSignal(SignalCatalogKey a, SignalCatalogKey b) {
        return a.signalName().equals(b.signalName())
                && a.sourceType() == b.sourceType()
                && a.messageName().equals(b.messageName())
                && a.canId() == b.canId()
                && a.channel() == b.channel();
    }

    private static EvidenceQuality.SampleObservation observe(SignalSeriesStore samples, SignalCatalogKey key) {
        int capacity = PAGE;
        long[] times = new long[capacity];
        double[] values = new double[capacity];
        boolean[] usable = new boolean[capacity];
        int count = 0;
        int offset = 0;
        boolean ended = false;
        while (count < SAMPLE_CAP) {
            List<SignalSamplePoint> page = samples.querySamples(key, offset, PAGE);
            if (page == null || page.isEmpty()) {
                ended = true;
                break;
            }
            for (SignalSamplePoint sample : page) {
                if (sample == null) {
                    continue;
                }
                if (count == times.length) {
                    int next = Math.min(SAMPLE_CAP, times.length * 2);
                    times = java.util.Arrays.copyOf(times, next);
                    values = java.util.Arrays.copyOf(values, next);
                    usable = java.util.Arrays.copyOf(usable, next);
                }
                if (count >= SAMPLE_CAP) {
                    break;
                }
                times[count] = sample.timestampNanos();
                values[count] = sample.physicalValue();
                usable[count] = sample.usableMeasurement();
                count++;
            }
            if (count >= SAMPLE_CAP || page.size() < PAGE) {
                ended = page.size() < PAGE && count < SAMPLE_CAP;
                break;
            }
            int next = offset + page.size();
            if (next <= offset) {
                break;
            }
            offset = next;
        }
        long catalogSamples = count;
        String unit = "";
        try {
            if (samples.snapshot() != null) {
                String sameSignalUnit = "";
                long sameSignalCount = -1L;
                for (var entry : samples.snapshot().entries()) {
                    if (entry == null || entry.key() == null) {
                        continue;
                    }
                    if (key.equals(entry.key())) {
                        unit = entry.unit();
                        catalogSamples = entry.sampleCount();
                        sameSignalUnit = "";
                        break;
                    }
                    if (sameEngineeringSignal(key, entry.key())
                            && entry.unit() != null
                            && !entry.unit().isBlank()) {
                        sameSignalUnit = entry.unit();
                        sameSignalCount = entry.sampleCount();
                    }
                }
                if ((unit == null || unit.isBlank()) && !sameSignalUnit.isBlank()) {
                    unit = sameSignalUnit;
                    if (sameSignalCount >= 0) {
                        catalogSamples = sameSignalCount;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            unit = "";
        }
        boolean complete = ended && (catalogSamples <= 0 || count >= catalogSamples);
        return EvidenceQuality.observe(
                key.signalName(), unit, key.sourceFile(), Integer.toString(key.channel()), "",
                java.util.Arrays.copyOf(times, count),
                java.util.Arrays.copyOf(values, count),
                java.util.Arrays.copyOf(usable, count),
                catalogSamples, complete);
    }

    private static List<EvidenceQuality.ClockFact> clocks(Map<String, Path> measurementPaths) {
        if (measurementPaths == null || measurementPaths.size() < 2) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(measurementPaths.keySet());
        ids.sort(String::compareTo);
        Map<String, RecordingClock> read = RecordingClockReader.readAll(ids, measurementPaths);
        List<EvidenceQuality.ClockFact> facts = new ArrayList<>();
        for (String id : ids) {
            Path path = measurementPaths.get(id);
            String name = path == null || path.getFileName() == null ? id : path.getFileName().toString();
            RecordingClock clock = read.get(id);
            if (clock == null) {
                facts.add(new EvidenceQuality.ClockFact(name, false, false, "clock evidence was not returned", ""));
                continue;
            }
            facts.add(new EvidenceQuality.ClockFact(
                    name, true, clock.comparable(), clock.reason(),
                    clock.utcStartNanos() == null ? "" : Long.toString(clock.utcStartNanos())));
        }
        return facts;
    }

    private static List<EvidenceQuality.StoredChannelCoverage> coverage(
            List<ChannelMappingMetadata> mappings, Map<String, Path> measurementPaths
    ) {
        if (mappings == null || mappings.isEmpty()) {
            return List.of();
        }
        List<ChannelMappingMetadata> ordered = new ArrayList<>(mappings);
        ordered.sort(Comparator
                .comparing(ChannelMappingMetadata::sourceId)
                .thenComparingInt(ChannelMappingMetadata::channelId));
        List<EvidenceQuality.StoredChannelCoverage> rows = new ArrayList<>();
        for (ChannelMappingMetadata mapping : ordered) {
            Path path = measurementPaths == null ? null : measurementPaths.get(mapping.sourceId());
            String file = path == null || path.getFileName() == null
                    ? mapping.sourceId() : path.getFileName().toString();
            rows.add(new EvidenceQuality.StoredChannelCoverage(
                    file, mapping.channelId(),
                    mapping.observedUniqueMessageCount(), mapping.matchedUniqueMessageCount(),
                    mapping.status()));
        }
        return rows;
    }

    private static List<QualityFinding> events(
            EngineeringInvestigation investigation,
            ProjectMetadataStore metadata,
            Set<String> sourceNames
    ) {
        List<QualityFinding> out = new ArrayList<>();
        if (metadata == null) {
            return out;
        }
        Set<String> included = new LinkedHashSet<>();
        for (EngineeringEvidenceItem item : investigation.evidence()) {
            if (item != null && item.kind() == EvidenceKind.ANNOTATION && item.referenceId() != null) {
                included.add(item.referenceId());
            }
        }
        Path root = AnalysisStorageRoots.projectArtifactsRoot(investigation.projectId());
        for (EngineeringBookmarkMetadata bookmark : metadata.listBookmarks(investigation.projectId())) {
            if (bookmark == null || !included.contains(bookmark.bookmarkId())) {
                continue;
            }
            var defined = EngineerEventDocuments.decode(bookmark);
            String file = defined == null ? "" : defined.sourceFile();
            if (!file.isBlank() && sourceNames != null && !sourceNames.contains(file)) {
                out.add(EvidenceQuality.missingSource(
                        bookmark.bookmarkId(), InvestigationEventCodec.titleOf(bookmark.observation()), file));
            }
            for (String signalKey : EngineerEventDocuments.signalKeys(bookmark)) {
                if (GraphInvestigationCodec.decodeKey(signalKey) == null) {
                    out.add(EvidenceQuality.staleSignal(bookmark.bookmarkId(), signalKey));
                    break;
                }
            }
            GraphEventGps.Evidence gps = GraphEventGps.read(bookmark);
            if (gps != null && gps.status() != null
                    && !gps.status().isBlank()
                    && !"GPS sample matched".equals(gps.status())
                    && !"GPS not requested".equals(gps.status())) {
                out.add(EvidenceQuality.gnss(
                        bookmark.bookmarkId(), gps.status(), file, Long.toString(bookmark.timestampNanos())));
            }
            if (defined == null) {
                continue;
            }
            for (String snapshotId : defined.snapshotIds()) {
                Path png = GraphSnapshotLinks.png(root, investigation.investigationId(), snapshotId);
                GraphSnapshotMetadata meta = GraphSnapshotLinks.read(png);
                if (png == null || meta == null) {
                    out.add(EvidenceQuality.missingSnapshot(snapshotId, snapshotId));
                    continue;
                }
                String disclosure = EventSnapshotReport.signalDisclosure(bookmark, meta);
                if (!disclosure.isBlank()) {
                    out.add(EvidenceQuality.snapshotMismatch(snapshotId, disclosure));
                }
            }
        }
        return out;
    }
}
