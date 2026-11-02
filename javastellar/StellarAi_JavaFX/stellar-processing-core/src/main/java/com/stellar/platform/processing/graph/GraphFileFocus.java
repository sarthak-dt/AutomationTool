package com.stellar.platform.processing.graph;

/**
 * Which measurement is drawn inside a logical track when recording clocks cannot be aligned.
 * The index selects a file. It does not create another track.
 */
public record GraphFileFocus(int index, int count) {

    public GraphFileFocus {
        if (count < 0) {
            throw new IllegalArgumentException("count");
        }
        if (count == 0) {
            index = 0;
        } else if (index < 0) {
            index = 0;
        } else if (index >= count) {
            index = count - 1;
        }
    }

    public int displayNumber() {
        return count == 0 ? 0 : index + 1;
    }

    public boolean hasPrevious() {
        return index > 0;
    }

    public boolean hasNext() {
        return count > 0 && index < count - 1;
    }

    public GraphFileFocus previous() {
        return new GraphFileFocus(index - 1, count);
    }

    public GraphFileFocus next() {
        return new GraphFileFocus(index + 1, count);
    }

    /** {@code File 12 of 73}. Empty when there is nothing to navigate. */
    public String position() {
        if (count <= 1) {
            return "";
        }
        return "File " + displayNumber() + " of " + count;
    }

    public String caption(String measurementLabel) {
        String position = position();
        if (position.isEmpty()) {
            return measurementLabel == null ? "" : measurementLabel;
        }
        if (measurementLabel == null || measurementLabel.isBlank()) {
            return position;
        }
        return position + " · " + measurementLabel;
    }

    /**
     * Query and axis window for the focused file. A view that already lies inside the file is kept,
     * so zoom and pan stay on that file. Anything else snaps to the file's own stored timestamps.
     */
    public static long[] queryWindow(long viewStart, long viewEnd, long fileStart, long fileEnd) {
        long end = fileEnd < fileStart ? fileStart : fileEnd;
        if (end == fileStart) {
            end = fileStart + 1;
        }
        if (viewEnd > viewStart && viewStart >= fileStart && viewEnd <= end) {
            return new long[] { viewStart, viewEnd };
        }
        return new long[] { fileStart, end };
    }
}
