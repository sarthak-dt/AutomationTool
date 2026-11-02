package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.CategoricalValue;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.catalog.SignalSeriesStore;

import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * One forward pass over a series. Samples are not copied into one list.
 * The first sample is the earliest categorical timestamp, not the first file visited.
 */
public final class CategoricalSeriesSummary {

    private static final int MAX_LABELS = 4;

    public record Result(
            String reportLabel,
            String labels,
            String rawText,
            String validityText,
            long firstTimestampNanos,
            long categoricalSamples,
            boolean changes
    ) {
    }

    private CategoricalSeriesSummary() {
    }

    public static Result scan(SignalSeriesStore store, SignalCatalogKey key) {
        if (store == null || key == null) {
            return null;
        }
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        boolean[] moreLabels = {false};
        Long[] raw = {null};
        boolean[] rawVaries = {false};
        SignalValidity[] validity = {null};
        boolean[] validityVaries = {false};
        long[] firstTimestamp = {Long.MIN_VALUE};
        long[] categorical = {0};
        store.forEachSample(key, sample -> acceptCategorical(
                sample, labels, moreLabels, raw, rawVaries, validity, validityVaries, firstTimestamp, categorical));
        if (categorical[0] == 0) {
            return null;
        }
        boolean more = moreLabels[0];
        long first = firstTimestamp[0];
        long count = categorical[0];
        Long firstRaw = raw[0];
        SignalValidity firstValidity = validity[0];
        boolean rawChanged = rawVaries[0];
        boolean validityChanged = validityVaries[0];
        boolean changes = labels.size() > 1 || more;
        String joined = String.join(", ", labels);
        if (more) {
            joined = joined + ", …";
        }
        String report = changes
                ? joined + " (changes, " + count + " samples)"
                : joined + " (" + count + " samples)";
        String rawText = firstRaw == null ? "" : (rawChanged ? "varies" : Long.toString(firstRaw));
        String validityText = firstValidity == null ? "" : (validityChanged ? "varies" : firstValidity.name());
        return new Result(report, joined, rawText, validityText, first, count, changes);
    }

    private static void acceptCategorical(
            SignalSamplePoint sample,
            LinkedHashSet<String> labels,
            boolean[] moreLabels,
            Long[] raw,
            boolean[] rawVaries,
            SignalValidity[] validity,
            boolean[] validityVaries,
            long[] firstTimestamp,
            long[] categorical
    ) {
        if (sample == null) {
            return;
        }
        String label = CategoricalValue.label(sample.validity(), sample.physicalValue(), sample.textualValue());
        if (label == null) {
            return;
        }
        categorical[0]++;
        if (firstTimestamp[0] == Long.MIN_VALUE || sample.timestampNanos() < firstTimestamp[0]) {
            if (firstTimestamp[0] != Long.MIN_VALUE) {
                noteDifference(raw[0], sample.rawValue(), rawVaries, validity[0], sample.validity(), validityVaries);
            }
            firstTimestamp[0] = sample.timestampNanos();
            raw[0] = sample.rawValue();
            validity[0] = sample.validity();
        } else {
            noteDifference(raw[0], sample.rawValue(), rawVaries, validity[0], sample.validity(), validityVaries);
        }
        if (!labels.contains(label)) {
            if (labels.size() < MAX_LABELS) {
                labels.add(label);
            } else {
                moreLabels[0] = true;
            }
        }
    }

    private static void noteDifference(
            Long firstRaw,
            Long sampleRaw,
            boolean[] rawVaries,
            SignalValidity firstValidity,
            SignalValidity sampleValidity,
            boolean[] validityVaries
    ) {
        if (firstRaw != null && sampleRaw != null && firstRaw.longValue() != sampleRaw) {
            rawVaries[0] = true;
        } else if ((firstRaw == null) != (sampleRaw == null)) {
            rawVaries[0] = true;
        }
        if (firstValidity != sampleValidity) {
            validityVaries[0] = true;
        }
    }

    public static String chipText(Iterable<String> labels) {
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        if (labels != null) {
            for (String label : labels) {
                if (label != null && !label.isBlank()) {
                    distinct.add(label.trim());
                }
            }
        }
        if (distinct.isEmpty()) {
            return "";
        }
        if (distinct.size() == 1) {
            return distinct.iterator().next();
        }
        return String.join(", ", distinct);
    }

    public static String countText(long samples) {
        return String.format(Locale.ROOT, "%d samples", Math.max(0, samples));
    }
}
