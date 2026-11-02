package com.stellar.platform.processing.graph;

import java.util.List;
import java.util.Objects;

/**
 * Shared graph axis for recordings whose UTC starts are comparable.
 *
 * <p>Display time is an offset from the earliest UTC start, not an epoch nanosecond.
 * Stored samples stay measurement-relative. A visible display window is intersected with
 * each recording and converted back to that recording's stored timestamps before a store query.
 */
public final class SharedDisplayTimeline {

    public record Member(
            String measurementId,
            long utcStartNanos,
            long storedStartNanos,
            long storedEndNanos
    ) {
        public Member {
            measurementId = measurementId == null ? "" : measurementId;
            if (storedEndNanos < storedStartNanos) {
                throw new IllegalArgumentException("stored end before start");
            }
        }

        public long displayOffsetNanos(long originUtcNanos) {
            return Math.subtractExact(utcStartNanos, originUtcNanos);
        }
    }

    private final long originUtcNanos;
    private final List<Member> members;

    private SharedDisplayTimeline(long originUtcNanos, List<Member> members) {
        this.originUtcNanos = originUtcNanos;
        this.members = members;
    }

    public static SharedDisplayTimeline of(List<Member> members) {
        Objects.requireNonNull(members, "members");
        if (members.isEmpty()) {
            throw new IllegalArgumentException("members");
        }
        long origin = Long.MAX_VALUE;
        for (Member member : members) {
            origin = Math.min(origin, member.utcStartNanos());
        }
        return new SharedDisplayTimeline(origin, List.copyOf(members));
    }

    public long originUtcNanos() {
        return originUtcNanos;
    }

    public List<Member> members() {
        return members;
    }

    public Member member(String measurementId) {
        if (measurementId == null) {
            return null;
        }
        for (Member member : members) {
            if (measurementId.equalsIgnoreCase(member.measurementId())) {
                return member;
            }
        }
        return null;
    }

    public long displayOffsetNanos(Member member) {
        return member.displayOffsetNanos(originUtcNanos);
    }

    public long toDisplay(Member member, long storedNanos) {
        return Math.addExact(displayOffsetNanos(member), storedNanos);
    }

    public long toStored(Member member, long displayNanos) {
        return Math.subtractExact(displayNanos, displayOffsetNanos(member));
    }

    public long toEpoch(long displayNanos) {
        return Math.addExact(originUtcNanos, displayNanos);
    }

    public long displayStart() {
        long min = Long.MAX_VALUE;
        for (Member member : members) {
            min = Math.min(min, toDisplay(member, member.storedStartNanos()));
        }
        return min;
    }

    public long displayEnd() {
        long max = Long.MIN_VALUE;
        for (Member member : members) {
            max = Math.max(max, toDisplay(member, member.storedEndNanos()));
        }
        return max;
    }

    /**
     * Stored query window for one recording, or {@code null} when the display window misses it.
     * The returned bounds are measurement-relative timestamps, never epoch timestamps.
     */
    public long[] storedQuery(Member member, long displayWindowStart, long displayWindowEnd) {
        if (displayWindowEnd < displayWindowStart) {
            return null;
        }
        long from = Math.max(toStored(member, displayWindowStart), member.storedStartNanos());
        long to = Math.min(toStored(member, displayWindowEnd), member.storedEndNanos());
        if (to < from) {
            return null;
        }
        return new long[] {from, to};
    }
}
