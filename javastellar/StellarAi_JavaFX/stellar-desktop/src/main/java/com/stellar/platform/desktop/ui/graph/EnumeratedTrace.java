package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.processing.graph.GraphBucket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step/hold classification for a series whose samples already carry value-table text.
 * An integer analog series has no label and stays a continuous line.
 */
public final class EnumeratedTrace {

    /** One plotted physical level and the value-table text stored with it. */
    public record Level(double value, String label) {
    }

    private EnumeratedTrace() {
    }

    public static boolean isState(List<GraphBucket> buckets) {
        return !levels(buckets).isEmpty();
    }

    /** Distinct physical levels that have a label, low to high. */
    public static List<Level> levels(List<GraphBucket> buckets) {
        if (buckets == null || buckets.isEmpty()) {
            return List.of();
        }
        Map<Long, Level> byValue = new LinkedHashMap<>();
        for (GraphBucket bucket : buckets) {
            accept(byValue, bucket.firstUsable(), bucket.firstValue(), bucket.firstLabel());
            accept(byValue, bucket.minUsable(), bucket.minValue(), bucket.minLabel());
            accept(byValue, bucket.maxUsable(), bucket.maxValue(), bucket.maxLabel());
            accept(byValue, bucket.lastUsable(), bucket.lastValue(), bucket.lastLabel());
        }
        if (byValue.isEmpty()) {
            return List.of();
        }
        List<Level> levels = new ArrayList<>(byValue.values());
        levels.sort(Comparator.comparingDouble(Level::value));
        return List.copyOf(levels);
    }

    /** As many state ticks as the plot height can show, including the ends. */
    public static List<Level> fit(List<Level> levels, double plotHeight) {
        if (levels == null || levels.isEmpty()) {
            return List.of();
        }
        int max = Math.max(1, (int) Math.floor(plotHeight / 16.0));
        if (levels.size() <= max) {
            return List.copyOf(levels);
        }
        List<Level> shown = new ArrayList<>();
        int last = -1;
        for (int i = 0; i < max; i++) {
            int index = (int) Math.round(i * (levels.size() - 1) / (double) Math.max(1, max - 1));
            if (index == last) {
                continue;
            }
            last = index;
            shown.add(levels.get(index));
        }
        return List.copyOf(shown);
    }

    /** Left-gutter text. The full label stays on the hover. */
    public static String gutter(String label) {
        if (label == null || label.isBlank()) {
            return "";
        }
        String text = label.trim();
        int max = 12;
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1) + "…";
    }

    private static void accept(Map<Long, Level> byValue, boolean usable, double value, String label) {
        if (!usable || label == null || label.isBlank() || !Double.isFinite(value)) {
            return;
        }
        byValue.putIfAbsent(Double.doubleToLongBits(value), new Level(value, label.trim()));
    }
}
