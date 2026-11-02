package com.stellar.platform.processing.graph.timeline;

import java.util.List;

/**
 * One logical signal is one waveform track and one colour, whichever timeline group holds its samples.
 * The colour index is the signal's place in the selected list. It does not depend on the file,
 * the slot, or which clock the recording uses.
 */
public final class LogicalSignalTrack {

    private LogicalSignalTrack() {
    }

    public static int colorIndex(List<String> selectedLogicalSignals, String signalName) {
        if (selectedLogicalSignals == null || signalName == null) {
            return -1;
        }
        return selectedLogicalSignals.indexOf(signalName);
    }

    public static boolean sameTrack(String leftSignal, String leftUnit, String rightSignal, String rightUnit) {
        if (leftSignal == null || rightSignal == null || !leftSignal.equals(rightSignal)) {
            return false;
        }
        return normalize(leftUnit).equals(normalize(rightUnit));
    }

    private static String normalize(String unit) {
        return unit == null ? "" : unit.trim();
    }
}
