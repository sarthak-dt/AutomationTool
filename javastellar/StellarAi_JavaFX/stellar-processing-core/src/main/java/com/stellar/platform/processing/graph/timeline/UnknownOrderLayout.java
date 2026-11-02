package com.stellar.platform.processing.graph.timeline;

import java.util.List;

/**
 * Virtual overview for recordings that do not share a verified clock.
 *
 * <p>Every recording occupies one equal layout slot. Slot order is a stable presentation order
 * (measurement identity), not chronology, and not file-name order. The gap between slots is a
 * seam: it is not elapsed time, and waveforms are not connected across it.
 *
 * <p>Layout positions are not timestamps. They must not be passed to Engineering, Analytics, or GPS.
 * Each slot still remembers that recording's original stored-time range.
 */
public final class UnknownOrderLayout {

    /** Equal navigation width of one slot. Not a duration of the recording. */
    public static final long EQUAL_SLOT_SPAN = 1_000_000_000L;

    public record Slot(
            String measurementId,
            String sourceFile,
            int index,
            long storedStartNanos,
            long storedEndNanos,
            long layoutStart,
            long layoutEnd
    ) {
        public long storedSpanNanos() {
            return storedEndNanos - storedStartNanos;
        }
    }

    private final List<Slot> slots;

    UnknownOrderLayout(List<Slot> slots) {
        this.slots = List.copyOf(slots);
    }

    public List<Slot> slots() {
        return slots;
    }

    public int size() {
        return slots.size();
    }

    public Slot slot(int index) {
        return slots.get(index);
    }

    public long layoutStart() {
        return slots.isEmpty() ? 0L : slots.getFirst().layoutStart();
    }

    public long layoutEnd() {
        return slots.isEmpty() ? 0L : slots.getLast().layoutEnd();
    }

    /** Always false. Adjacent slots do not form one waveform. */
    public boolean connectsAcrossSlots() {
        return false;
    }

    /**
     * Stored timestamp range covered by the portion of this slot inside the layout window.
     * Returns {@code null} when the window misses the slot. The result is the recording's own
     * stored clock, never a layout position.
     */
    public long[] storedQuery(Slot slot, long windowStart, long windowEnd) {
        if (slot == null || windowEnd < windowStart) {
            return null;
        }
        if (!intersects(windowStart, windowEnd, slot.layoutStart(), slot.layoutEnd())) {
            return null;
        }
        if (slot.storedSpanNanos() == 0L) {
            return new long[] {slot.storedStartNanos(), slot.storedStartNanos()};
        }
        long localStart = Math.max(windowStart, slot.layoutStart()) - slot.layoutStart();
        long localEnd = Math.min(windowEnd, slot.layoutEnd()) - slot.layoutStart();
        if (localEnd < localStart) {
            return null;
        }
        long from = slot.storedStartNanos() + scale(slot.storedSpanNanos(), localStart, EQUAL_SLOT_SPAN);
        long to = slot.storedStartNanos() + scale(slot.storedSpanNanos(), localEnd, EQUAL_SLOT_SPAN);
        if (localEnd >= EQUAL_SLOT_SPAN) {
            to = slot.storedEndNanos();
        }
        if (localStart <= 0L) {
            from = slot.storedStartNanos();
        }
        if (to < from) {
            to = from;
        }
        return new long[] {from, to};
    }

    static boolean intersects(long windowStart, long windowEnd, long slotStart, long slotEnd) {
        if (windowEnd < windowStart) {
            return false;
        }
        if (windowStart == windowEnd) {
            return windowStart >= slotStart && windowStart <= slotEnd;
        }
        return windowStart < slotEnd && slotStart < windowEnd;
    }

    /**
     * {@code span * numerator / denominator} for a numerator in {@code 0..denominator}.
     * Avoids a long product of a multi-hour stored span and the slot width.
     */
    static long scale(long span, long numerator, long denominator) {
        if (numerator <= 0L || span <= 0L) {
            return 0L;
        }
        if (numerator >= denominator) {
            return span;
        }
        long whole = span / denominator;
        long remainder = span % denominator;
        return whole * numerator + (remainder * numerator) / denominator;
    }
}
