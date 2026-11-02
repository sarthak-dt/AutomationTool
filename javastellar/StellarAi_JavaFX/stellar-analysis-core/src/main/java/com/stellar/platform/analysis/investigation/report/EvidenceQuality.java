package com.stellar.platform.analysis.investigation.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns already stored evidence into quality findings.
 * It does not open files, invent units, or classify a gap as a logger fault.
 */
public final class EvidenceQuality {

    public static final String CLOCK = "Measurement-relative nanoseconds from this recording. Not merged with another file.";

    private EvidenceQuality() {
    }

    public record SampleObservation(
            String signal,
            String unit,
            String sourceFile,
            String channel,
            String dbc,
            long examined,
            long valid,
            long unusable,
            boolean constant,
            String constantValue,
            int nonMonotonic,
            long backwardFromNanos,
            long backwardToNanos,
            long intervalFromNanos,
            long intervalToNanos,
            long intervalNanos,
            long medianIntervalNanos,
            long firstNanos,
            long lastNanos,
            long catalogSamples,
            boolean scanComplete
    ) {
    }

    public record ClockFact(
            String fileName,
            boolean present,
            boolean comparable,
            String reason,
            String utcStartNanos
    ) {
    }

    public record StoredChannelCoverage(
            String sourceFile,
            int channel,
            long observedIdentities,
            long matchedIdentities,
            String status
    ) {
    }

    public static SampleObservation observe(
            String signal, String unit, String sourceFile, String channel, String dbc,
            long[] timestamps, double[] values, boolean[] usable
    ) {
        return observe(signal, unit, sourceFile, channel, dbc, timestamps, values, usable,
                timestamps == null ? 0 : timestamps.length, true);
    }

    public static SampleObservation observe(
            String signal, String unit, String sourceFile, String channel, String dbc,
            long[] timestamps, double[] values, boolean[] usable,
            long catalogSamples, boolean scanComplete
    ) {
        int n = timestamps == null ? 0 : timestamps.length;
        long valid = 0;
        long unusable = 0;
        boolean constant = true;
        boolean saw = false;
        double held = Double.NaN;
        int backward = 0;
        long backwardFrom = Long.MIN_VALUE;
        long backwardTo = Long.MIN_VALUE;
        long interval = 0;
        long intervalFrom = Long.MIN_VALUE;
        long intervalTo = Long.MIN_VALUE;
        long[] gaps = new long[Math.max(0, n - 1)];
        int gapCount = 0;
        long previous = Long.MIN_VALUE;
        boolean havePrevious = false;
        long first = Long.MIN_VALUE;
        long last = Long.MIN_VALUE;
        int limit = values == null ? 0 : Math.min(n, values.length);
        for (int i = 0; i < limit; i++) {
            boolean good = usable == null || i >= usable.length || usable[i];
            if (!good || !Double.isFinite(values[i])) {
                unusable++;
                havePrevious = false;
                continue;
            }
            valid++;
            long time = timestamps[i];
            if (first == Long.MIN_VALUE) {
                first = time;
            }
            last = time;
            if (!saw) {
                held = values[i];
                saw = true;
            } else if (Double.compare(held, values[i]) != 0) {
                constant = false;
            }
            if (havePrevious) {
                long delta = time - previous;
                if (delta < 0) {
                    if (backward == 0) {
                        backwardFrom = previous;
                        backwardTo = time;
                    }
                    backward++;
                } else if (gapCount < gaps.length) {
                    gaps[gapCount++] = delta;
                    if (delta > interval) {
                        interval = delta;
                        intervalFrom = previous;
                        intervalTo = time;
                    }
                }
            }
            previous = time;
            havePrevious = true;
        }
        long median = 0;
        if (gapCount > 0) {
            long[] sorted = Arrays.copyOf(gaps, gapCount);
            Arrays.sort(sorted);
            median = sorted[gapCount / 2];
        }
        return new SampleObservation(
                signal, unit, sourceFile, channel, dbc, limit, valid, unusable,
                saw && valid > 1 && constant, saw ? Double.toString(held) : "",
                backward, backwardFrom, backwardTo, intervalFrom, intervalTo, interval, median,
                first, last, catalogSamples, scanComplete);
    }

    public static List<QualityFinding> fromSamples(List<SampleObservation> observations) {
        List<QualityFinding> out = new ArrayList<>();
        if (observations == null) {
            return out;
        }
        for (SampleObservation sample : observations) {
            if (sample == null || sample.examined() == 0) {
                continue;
            }
            out.add(finding("samples-" + slug(sample.signal()), "sample-inventory", QualityStatus.OBSERVED,
                    sample, sample.firstNanos() + " -> " + sample.lastNanos(),
                    "Count usable and unusable samples in stored order. Timestamps are the original nanosecond values.",
                    "examined=" + sample.examined() + " valid=" + sample.valid() + " unusable=" + sample.unusable()
                            + " unit=" + sample.unit()
                            + " first=" + sample.firstNanos() + " last=" + sample.lastNanos(),
                    sample.valid() + " usable samples and " + sample.unusable()
                            + " unusable samples were stored. The first usable timestamp is "
                            + sample.firstNanos() + " ns and the last is " + sample.lastNanos() + " ns.",
                    "An unusable count of zero means no unusable sample was stored. It is not a replacement value."));
            if (sample.unusable() > 0) {
                out.add(finding("unusable-" + slug(sample.signal()), "unusable-samples", QualityStatus.OBSERVED,
                        sample, nanos(sample.intervalFromNanos()),
                        "Count stored samples whose usable flag is false or whose physical value is not finite. Missing values are not replaced.",
                        "unusable=" + sample.unusable() + " valid=" + sample.valid() + " examined=" + sample.examined(),
                        sample.unusable() + " stored samples are not usable. They were not replaced with zero.",
                        "An unusable sample is not a sensor-failure finding."));
            }
            if (sample.nonMonotonic() > 0) {
                out.add(finding("order-" + slug(sample.signal()), "non-monotonic-timestamps", QualityStatus.OBSERVED,
                        sample, sample.backwardFromNanos() + " -> " + sample.backwardToNanos(),
                        "Compare each usable sample with the previous usable sample in stored order. Timestamps are not sorted before this comparison.",
                        "backwardSteps=" + sample.nonMonotonic() + " firstFrom=" + sample.backwardFromNanos()
                                + " firstTo=" + sample.backwardToNanos(),
                        "Stored order is not timestamp order at " + sample.nonMonotonic() + " step(s).",
                        "This does not prove the recording clock is wrong. Sorted presentation is separate from stored order."));
            }
            if (sample.intervalNanos() > 0 && sample.intervalFromNanos() != Long.MIN_VALUE) {
                out.add(finding("interval-" + slug(sample.signal()), "measured-timestamp-interval", QualityStatus.OBSERVED,
                        sample, sample.intervalFromNanos() + " -> " + sample.intervalToNanos(),
                        "Largest forward difference between successive usable timestamps in stored order. Timestamps are not rounded and no sampling frequency is assumed.",
                        "intervalNanos=" + sample.intervalNanos()
                                + " from=" + sample.intervalFromNanos() + " to=" + sample.intervalToNanos(),
                        "The largest measured interval is " + sample.intervalNanos()
                                + " ns, from " + sample.intervalFromNanos() + " to " + sample.intervalToNanos() + ".",
                        "No approved policy classifies this interval as an abnormal gap or a logger failure."));
            }
            if (sample.constant()) {
                out.add(finding("constant-" + slug(sample.signal()), "constant-values", QualityStatus.OBSERVED,
                        sample, "",
                        "Compare successive usable physical values. Equality uses the stored value.",
                        "value=" + sample.constantValue() + " valid=" + sample.valid(),
                        "All " + sample.valid() + " usable values examined are " + sample.constantValue() + ".",
                        "A constant stored value is not a failed-sensor finding."));
            }
            if (sample.unit() == null || sample.unit().isBlank()) {
                out.add(finding("unit-" + slug(sample.signal()), "missing-unit", QualityStatus.UNAVAILABLE,
                        sample, "",
                        "Read the stored unit. No unit is inferred from the signal name.",
                        "unit=",
                        "No engineering unit is stored for this signal.",
                        "Statistics are not given a unit that the source did not store."));
            }
        }
        return out;
    }

    public static QualityFinding dbcNotAttached(String sourceFile) {
        return new QualityFinding("dbc-not-attached", "dbc-mapping", QualityStatus.UNAVAILABLE,
                "", sourceFile, "", "", "", "", CLOCK,
                "Count definition files attached to the project. No new CAN matching rule is applied.",
                "definitionCount=0", sourceFile, "Project definition list",
                "No DBC is attached. CAN identifiers were not classified as invalid source data.",
                "Unmatched CAN IDs are not treated as invalid measurements.");
    }

    public static QualityFinding unprocessed(String sourceFile) {
        return new QualityFinding("unprocessed-" + slug(sourceFile), "unprocessed-measurement", QualityStatus.UNAVAILABLE,
                "", sourceFile, "", "", "", "", CLOCK,
                "The project records the file as not processed. Catalog inventory is not decoded sample statistics.",
                "processed=false", sourceFile, "Processing status",
                "Measured min, max, and sample statistics are unavailable because this file is not processed.",
                "A READY inventory is not a measured signal statistic.");
    }

    public static QualityFinding missingSource(String eventId, String eventName, String sourceFile) {
        return new QualityFinding("source-" + slug(eventId), "missing-source", QualityStatus.UNAVAILABLE,
                eventId, sourceFile, "", eventName, "", "", CLOCK,
                "Compare the event source file name with the project measurement names.",
                "sourceFile=" + sourceFile, eventId, "Engineering bookmark",
                "The event names a measurement that is not in this project.",
                "The bookmark was not rewritten and no substitute file was chosen.");
    }

    public static QualityFinding staleSignal(String eventId, String signal) {
        return new QualityFinding("signal-" + slug(eventId), "stale-signal", QualityStatus.UNAVAILABLE,
                eventId, "", "", signal, "", "", CLOCK,
                "Decode the saved signal reference. A reference that does not decode is not replaced.",
                "signal=" + signal, eventId, "Engineering bookmark",
                "The saved signal reference does not resolve.",
                "The event time and bookmark were not changed.");
    }

    public static QualityFinding missingSnapshot(String snapshotId, String name) {
        return new QualityFinding("snapshot-" + slug(snapshotId), "missing-snapshot", QualityStatus.UNAVAILABLE,
                snapshotId, "", "", "", "", "", CLOCK,
                "Check that the saved snapshot file exists. The image is not regenerated.",
                "filePresent=false", snapshotId, "Snapshot evidence file",
                "Snapshot " + name + " is missing. It was not regenerated.",
                "The evidence reference remains. No replacement image is created.");
    }

    public static QualityFinding snapshotMismatch(String snapshotId, String missingSignals) {
        return new QualityFinding("snapshot-signals-" + slug(snapshotId), "snapshot-signal-mismatch", QualityStatus.OBSERVED,
                snapshotId, "", "", missingSignals, "", "", CLOCK,
                "Compare event signal names with the signal names stored on the snapshot metadata.",
                "absent=" + missingSignals, snapshotId, "Snapshot metadata",
                "The snapshot metadata does not list " + missingSignals + ".",
                "The image bytes are unchanged. The metadata is the statement of which signals the snapshot records.");
    }

    public static QualityFinding gnss(String eventId, String status, String sourceFile, String time) {
        return new QualityFinding("gnss-" + slug(eventId), "gnss-evidence", QualityStatus.UNAVAILABLE,
                eventId, sourceFile, "", "", "", time, CLOCK,
                "Read the GPS status stored on the bookmark. Coordinates are not looked up again for this finding.",
                "status=" + status, eventId, "Bookmark GPS evidence",
                status, "No coordinate is invented and no route image is created.");
    }

    public static QualityFinding incompatibleUnits() {
        return new QualityFinding("units-incompatible", "incompatible-units", QualityStatus.INDETERMINATE,
                "", "", "", "", "", "", CLOCK,
                "No approved rule compares engineering units.",
                "rule=none", "Signal units", "Stored unit text",
                "Unit compatibility cannot be decided. No unit was inferred or converted.",
                "Different unit text is not treated as an incompatible-unit defect.");
    }

    public static QualityFinding clocks(List<ClockFact> clocks) {
        if (clocks == null || clocks.size() < 2) {
            return null;
        }
        List<ClockFact> ordered = new ArrayList<>(clocks);
        ordered.sort((left, right) -> left.fileName().compareTo(right.fileName()));
        StringBuilder reasons = new StringBuilder();
        StringBuilder starts = new StringBuilder();
        boolean missing = false;
        boolean allComparable = true;
        for (ClockFact clock : ordered) {
            if (!reasons.isEmpty()) {
                reasons.append("; ");
                starts.append("; ");
            }
            reasons.append(clock.fileName()).append(": ").append(clock.present() ? clock.reason() : "clock evidence is missing");
            starts.append(clock.fileName()).append("=").append(clock.utcStartNanos());
            if (!clock.present()) {
                missing = true;
                allComparable = false;
            } else if (!clock.comparable()) {
                allComparable = false;
            }
        }
        if (missing || !allComparable) {
            return new QualityFinding("clocks-unverified", "multi-file-clock", QualityStatus.INDETERMINATE,
                    "", "", "", "", "", "", "Per-recording clocks",
                    "Read RecordingClock.comparable() for each measurement. Clocks are not merged.",
                    "files=" + ordered.size() + " " + reasons,
                    "Project measurements", "RecordingClock",
                    "Clock comparability is not established. " + reasons,
                    "Insufficient clock evidence is not proof that a timestamp is wrong. Clocks were not merged.");
        }
        return new QualityFinding("clocks-comparable", "multi-file-clock", QualityStatus.OBSERVED,
                "", "", "", "", "", "", "Per-recording clocks",
                "Read RecordingClock.comparable() for each measurement. A comparable clock has status COMPARABLE and a UTC start.",
                "files=" + ordered.size() + " utcStartNanos " + starts,
                "Project measurements", "RecordingClock.comparable()",
                "RecordingClock.comparable() is true for each measurement. UTC starts: " + starts + ".",
                "Comparable clocks were not merged onto one timeline by this finding.");
    }

    public static List<QualityFinding> dbcCoverage(int definitionCount, List<StoredChannelCoverage> rows) {
        List<QualityFinding> out = new ArrayList<>();
        if (definitionCount <= 0) {
            out.add(dbcNotAttached(""));
            return out;
        }
        if (rows == null || rows.isEmpty()) {
            out.add(new QualityFinding("dbc-coverage-unavailable", "dbc-mapping", QualityStatus.UNAVAILABLE,
                    "", "", "", "", "", "", CLOCK,
                    "Read stored channel coverage records. No new CAN matcher is run.",
                    "storedCoverageRows=0", "Channel coverage records", "Stored DBC coverage",
                    "Stored DBC coverage is not available. Per-CAN-ID unmapped identities were not reconstructed.",
                    "An absent coverage record is not an unmatched CAN ID and is not invalid source data."));
            return out;
        }
        List<StoredChannelCoverage> ordered = new ArrayList<>(rows);
        ordered.sort((left, right) -> {
            int file = left.sourceFile().compareTo(right.sourceFile());
            return file != 0 ? file : Integer.compare(left.channel(), right.channel());
        });
        int limit = Math.min(ordered.size(), 32);
        for (int i = 0; i < limit; i++) {
            StoredChannelCoverage row = ordered.get(i);
            boolean countable = row.observedIdentities() >= row.matchedIdentities();
            String unmatched = countable
                    ? Long.toString(row.observedIdentities() - row.matchedIdentities())
                    : "indeterminate";
            out.add(new QualityFinding(
                    "dbc-coverage-" + row.channel() + "-" + slug(row.sourceFile()),
                    "dbc-mapping",
                    countable ? QualityStatus.CALCULATED : QualityStatus.INDETERMINATE,
                    "", row.sourceFile(), Integer.toString(row.channel()), "", "", "", CLOCK,
                    "Use the stored observed and matched identity counts for this channel. No new CAN matcher is run.",
                    "channel=" + row.channel()
                            + " observed=" + row.observedIdentities()
                            + " matched=" + row.matchedIdentities()
                            + " unmatchedIdentities=" + unmatched
                            + " status=" + row.status(),
                    row.sourceFile() + " channel " + row.channel(),
                    "Stored DBC coverage",
                    "Stored coverage for channel " + row.channel()
                            + " records " + row.observedIdentities() + " observed identities and "
                            + row.matchedIdentities() + " matched identities.",
                    "Individual unmatched CAN IDs are not stored in the coverage record. They were not reconstructed. An unmatched count is not invalid source data."));
        }
        if (ordered.size() > limit) {
            out.add(new QualityFinding("dbc-coverage-truncated", "dbc-mapping", QualityStatus.OBSERVED,
                    "", "", "", "", "", "", CLOCK,
                    "Stored coverage rows are reported in source-file and channel order, up to 32 rows.",
                    "stored=" + ordered.size() + " reported=" + limit,
                    "Channel coverage records", "Stored DBC coverage",
                    "Additional stored coverage rows were not listed.",
                    "The omitted rows were not treated as matched or unmatched."));
        }
        return out;
    }

    public static QualityFinding scanScope(
            List<String> examined, List<String> omitted, int sampleCap, boolean anyPartial, boolean storeMissing
    ) {
        List<String> seen = examined == null ? List.of() : examined;
        List<String> left = omitted == null ? List.of() : omitted;
        if (storeMissing) {
            return new QualityFinding("scan-scope", "scan-limit", QualityStatus.UNAVAILABLE,
                    "", "", "", "", "", "", CLOCK,
                    "Included signal keys are sorted by source file, channel, CAN ID, and signal name. The sample store was not available.",
                    "examined=0 omitted=" + left.size() + " sampleCap=" + sampleCap,
                    String.join(", ", left), "Included signal selection",
                    "No samples were examined because the sample store is not available.",
                    "This is not a statement that the recording contains no samples.");
        }
        return new QualityFinding("scan-scope", "scan-limit",
                anyPartial ? QualityStatus.INDETERMINATE : QualityStatus.OBSERVED,
                "", "", "", "", "", "", CLOCK,
                "Included signal keys are sorted by source file, channel, CAN ID, and signal name. At most 8 signals are examined, and at most "
                        + sampleCap + " stored samples per signal.",
                "examined=" + seen.size() + " omitted=" + left.size() + " sampleCap=" + sampleCap
                        + " signals=" + String.join("|", seen)
                        + (left.isEmpty() ? "" : " omittedSignals=" + String.join("|", left)),
                String.join(", ", seen), "Included signal selection",
                "Signals examined: " + String.join(", ", seen) + "."
                        + (left.isEmpty() ? "" : " Signals not examined: " + String.join(", ", left) + "."),
                anyPartial
                        ? "At least one signal scan is partial. A partial scan is not a statement about the samples that were not examined."
                        : "Each examined signal was read through the samples returned for that key, within the sample cap.");
    }

    private static QualityFinding finding(
            String id, String type, QualityStatus status, SampleObservation sample, String time,
            String method, String measured, String explanation, String limitations
    ) {
        String scope = sample.scanComplete()
                ? "Scan evidence is complete for the " + sample.examined() + " stored samples examined. "
                : "Scan evidence is partial: " + sample.examined() + " of " + sample.catalogSamples()
                + " catalog samples were examined. This finding does not describe the samples that were not examined. ";
        return new QualityFinding(id, type, status, "", sample.sourceFile(), sample.channel(), sample.signal(),
                sample.dbc(), time, CLOCK, method, measured, sample.signal(), "Stored samples in original order",
                explanation, scope + limitations);
    }

    private static String nanos(long value) {
        return value == Long.MIN_VALUE ? "" : Long.toString(value);
    }

    private static String slug(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9]+", "-");
        return cleaned.length() > 48 ? cleaned.substring(0, 48) : cleaned;
    }
}
