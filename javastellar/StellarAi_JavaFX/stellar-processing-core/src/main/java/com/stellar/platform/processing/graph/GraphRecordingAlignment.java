package com.stellar.platform.processing.graph;

/**
 * Whether two rendered series may be treated as the same recording clock.
 * Matching stored timestamps are not enough: different measurement files stay unrelated
 * until a comparable absolute clock has already placed them.
 */
public final class GraphRecordingAlignment {

    public static final String CROSS_FILE_UNAVAILABLE =
            "Cross-file comparison is unavailable because these recordings do not share a verified clock.";

    private GraphRecordingAlignment() {
    }

    public static boolean sameRecording(String leftSource, String rightSource) {
        if (leftSource == null || rightSource == null) {
            return false;
        }
        String left = leftSource.trim();
        String right = rightSource.trim();
        return !left.isEmpty() && left.equals(right);
    }

    public static boolean sameRecording(GraphRenderSeries left, GraphRenderSeries right) {
        if (left == null || right == null || left.key() == null || right.key() == null) {
            return false;
        }
        return sameRecording(left.key().sourceFile(), right.key().sourceFile());
    }
}
