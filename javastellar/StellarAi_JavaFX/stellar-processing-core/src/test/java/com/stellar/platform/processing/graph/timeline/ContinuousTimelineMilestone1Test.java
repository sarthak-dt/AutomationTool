package com.stellar.platform.processing.graph.timeline;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.cache.GraphQueryCache;
import com.stellar.platform.processing.graph.GraphViewportEngine;
import com.stellar.platform.processing.graph.GraphViewportRequest;
import com.stellar.platform.processing.graph.LodGraphSeriesQuery;
import com.stellar.platform.processing.graph.SharedDisplayTimeline;
import com.stellar.platform.processing.map.time.ClockKind;
import com.stellar.platform.processing.map.time.ClockStatus;
import com.stellar.platform.processing.map.time.RecordingClock;
import com.stellar.platform.processing.map.time.TimeUnitKind;
import com.stellar.platform.processing.map.time.TimestampSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContinuousTimelineMilestone1Test {

    private static final long S = 1_000_000_000L;
    private static final long ORIGIN = 1_674_000_000_000_000_000L;
    private static final TimelineViewportPlanner.LogicalSignalSelection SPEED =
            new TimelineViewportPlanner.LogicalSignalSelection("VehicleSpeed", "km/h");

    @Test
    void oneVerifiedUtcRecordingKeepsItsStoredRange() {
        RecordingGroupIndex index = RecordingGroupIndex.of(List.of(
                utc("only", "later-name.dat", ORIGIN, 5_998_194L, 20 * S)));
        assertEquals(1, index.verifiedUtc().size());
        assertTrue(index.unknownOrder().isEmpty());
        SharedDisplayTimeline timeline = index.utcTimeline();
        assertEquals(5_998_194L, timeline.displayStart());
        assertEquals(ORIGIN + 5_998_194L, index.verifiedUtcNanos(TimelineGroup.VERIFIED_UTC, timeline.displayStart()));
        TimelineViewportPlanner.Plan plan = plan(index, TimelineGroup.VERIFIED_UTC, timeline.displayStart(), timeline.displayEnd());
        assertEquals(1, plan.queriedRecordings());
        assertEquals(0, plan.excludedOffScreen());
        assertEquals(5_998_194L, plan.visible().getFirst().storedStartNanos());
        assertEquals(20 * S, plan.visible().getFirst().storedEndNanos());
        assertEquals("later-name.dat", plan.visible().getFirst().sourceFile());
        assertFalse(plan.connectsAcrossRecordings());
    }

    @Test
    void multipleUtcRecordingsKeepOverlapAndGapWithoutRewritingSamples() {
        RecordingGroupIndex index = utcTrioShuffled();
        SharedDisplayTimeline timeline = index.utcTimeline();
        assertEquals(0L, timeline.displayStart());
        assertEquals(60 * S, timeline.displayEnd());
        assertEquals(10 * S, timeline.displayOffsetNanos(timeline.member("utc-c")));
        assertEquals(40 * S, timeline.displayOffsetNanos(timeline.member("utc-b")));

        TimelineViewportPlanner.Plan overlap = plan(index, TimelineGroup.VERIFIED_UTC, 12 * S, 18 * S);
        assertEquals(2, overlap.queriedRecordings());
        assertEquals(1, overlap.excludedOffScreen());
        Map<String, TimelineViewportPlanner.SourceQueryWindow> byId = byId(overlap.visible());
        assertEquals(12 * S, byId.get("utc-a").storedStartNanos());
        assertEquals(2 * S, byId.get("utc-c").storedStartNanos());
        assertFalse(byId.containsKey("utc-b"));

        TimelineViewportPlanner.Plan gap = plan(index, TimelineGroup.VERIFIED_UTC, 33 * S, 34 * S);
        assertEquals(0, gap.queriedRecordings());
        assertEquals(3, gap.excludedOffScreen());
        assertTrue(gap.prefetch().isEmpty());
        assertNull(index.verifiedUtcNanos(TimelineGroup.UNKNOWN_ORDER, 0L));
    }

    @Test
    void oneUnknownClockDoesNotDisableUtcForTheOthers() {
        List<RecordingDescriptor> recordings = new ArrayList<>(utcTrio());
        recordings.add(unknown("relative", "2020-01-01-first.dat", 0L, 999 * S));
        Collections.reverse(recordings);
        RecordingGroupIndex index = RecordingGroupIndex.of(recordings);
        assertEquals(3, index.verifiedUtc().size());
        assertEquals(1, index.unknownOrder().size());
        assertEquals(60 * S, index.utcTimeline().displayEnd());
        assertEquals(UnknownOrderLayout.EQUAL_SLOT_SPAN, index.unknownLayout().layoutEnd());
        assertEquals("relative", index.unknownLayout().slot(0).measurementId());
        assertNull(index.verifiedUtcNanos(TimelineGroup.UNKNOWN_ORDER, 0L));

        TimelineViewportPlanner.Plan utc = plan(index, TimelineGroup.VERIFIED_UTC, 0L, 60 * S);
        assertEquals(3, utc.queriedRecordings());
        assertTrue(utc.visible().stream().noneMatch(window -> window.measurementId().equals("relative")));
        TimelineViewportPlanner.Plan unordered = plan(index, TimelineGroup.UNKNOWN_ORDER, 0L, UnknownOrderLayout.EQUAL_SLOT_SPAN);
        assertEquals(List.of("relative"), ids(unordered.visible()));
        assertTrue(unordered.visible().stream().noneMatch(window -> window.measurementId().startsWith("utc")));
    }

    @Test
    void unverifiedUtcFieldDoesNotJoinTheUtcGroup() {
        RecordingClock clock = new RecordingClock(
                "hidden", TimestampSource.NONE, ClockKind.RELATIVE_ONLY, TimeUnitKind.NANOSECONDS,
                null, ORIGIN, ClockStatus.UNORDERED, "not a verified clock");
        RecordingGroupIndex index = RecordingGroupIndex.of(List.of(
                new RecordingDescriptor("hidden", "looks-early.dat", clock, 0L, S),
                utc("real", "looks-late.dat", ORIGIN + 5 * S, 0L, S)));
        assertEquals(List.of("real"), index.verifiedUtc().stream().map(RecordingDescriptor::measurementId).toList());
        assertEquals(List.of("hidden"), index.unknownOrder().stream().map(RecordingDescriptor::measurementId).toList());
        assertEquals(clock, index.unknownOrder().getFirst().clock());
    }

    @Test
    void unknownSlotsAreEqualStableAndNotAChronology() {
        List<RecordingDescriptor> recordings = List.of(
                unknown("m-b", "file-01-early.dat", 0L, 5 * S),
                unknown("m-a", "file-99-late.dat", 50 * S, 80 * S),
                unknown("m-c", "file-50.dat", 3L, 9L));
        RecordingGroupIndex first = RecordingGroupIndex.of(recordings);
        RecordingGroupIndex reversed = RecordingGroupIndex.of(List.of(recordings.get(2), recordings.get(1), recordings.get(0)));
        assertEquals(List.of("m-a", "m-b", "m-c"), slotIds(first));
        assertEquals(slotIds(first), slotIds(reversed));
        assertNotEquals("file-01-early.dat", first.unknownLayout().slot(0).sourceFile());
        long span = UnknownOrderLayout.EQUAL_SLOT_SPAN;
        for (UnknownOrderLayout.Slot slot : first.unknownLayout().slots()) {
            assertEquals(span, slot.layoutEnd() - slot.layoutStart());
        }
        assertFalse(first.unknownLayout().connectsAcrossSlots());
        UnknownOrderLayout.Slot middle = first.unknownLayout().slot(1);
        assertEquals(0L, middle.storedStartNanos());
        assertEquals(5 * S, middle.storedEndNanos());
        assertNotEquals(middle.layoutStart(), middle.storedStartNanos());

        long[] leftHalf = first.unknownLayout().storedQuery(middle, middle.layoutStart(), middle.layoutStart() + span / 2);
        assertArrayEquals(new long[] {0L, 2_500_000_000L}, leftHalf);
        TimelineViewportPlanner.Plan seam = plan(
                first, TimelineGroup.UNKNOWN_ORDER, span, 2 * span);
        assertEquals(List.of("m-b"), ids(seam.visible()));
        assertEquals(0L, seam.visible().getFirst().storedStartNanos());
        assertEquals(5 * S, seam.visible().getFirst().storedEndNanos());
        assertEquals(List.of("m-a", "m-c"), ids(seam.prefetch()));
        assertTrue(seam.prefetch().stream().allMatch(TimelineViewportPlanner.SourceQueryWindow::prefetch));
        assertFalse(seam.connectsAcrossRecordings());
    }

    @Test
    void missingAndIncompatibleSignalsAreNotQueried() {
        RecordingGroupIndex index = RecordingGroupIndex.of(List.of(
                unknown("m-a", "a.dat", 0L, S),
                unknown("m-b", "b.dat", 0L, S),
                unknown("m-c", "c.dat", 0L, S)));
        Map<String, String> units = Map.of("m-a", "km/h", "m-c", "rpm");
        TimelineViewportPlanner.Plan plan = TimelineViewportPlanner.plan(
                index, TimelineGroup.UNKNOWN_ORDER, 0L, 3 * UnknownOrderLayout.EQUAL_SLOT_SPAN, SPEED, units);
        assertEquals(List.of("m-a"), ids(plan.visible()));
        assertEquals(1, plan.missingSignal());
        assertEquals(1, plan.incompatibleUnit());
        assertEquals(0, plan.excludedOffScreen());
        assertEquals(0, LogicalSignalTrack.colorIndex(List.of("VehicleSpeed", "Coolant"), "VehicleSpeed"));
        assertEquals(1, LogicalSignalTrack.colorIndex(List.of("VehicleSpeed", "Coolant"), "Coolant"));
        assertEquals(-1, LogicalSignalTrack.colorIndex(List.of("Coolant"), "VehicleSpeed"));
        assertTrue(LogicalSignalTrack.sameTrack("VehicleSpeed", "km/h", "VehicleSpeed", "km/h"));
        assertFalse(LogicalSignalTrack.sameTrack("VehicleSpeed", "km/h", "VehicleSpeed", "rpm"));
    }

    @Test
    void seventyThreeAndThreeHundredUnknownRecordingsKeepIndependentWindows() {
        assertOverview(73);
        assertOverview(300);
    }

    @Test
    void lodRequestsUseStoredWindowsAndTheExistingEngineCache() {
        RecordingGroupIndex index = utcTrioShuffled();
        TimelineViewportPlanner.Plan overlap = plan(index, TimelineGroup.VERIFIED_UTC, 12 * S, 18 * S);
        Map<String, List<SignalCatalogKey>> keys = new LinkedHashMap<>();
        for (TimelineViewportPlanner.SourceQueryWindow window : overlap.visible()) {
            keys.put(window.measurementId(), List.of(key(window.measurementId())));
        }
        List<GraphViewportRequest> requests = TimelineViewportPlanner.lodRequests(overlap, keys, 64);
        assertEquals(2, requests.size());
        AtomicInteger bucketCalls = new AtomicInteger();
        LodGraphSeriesQuery query = new LodGraphSeriesQuery() {
            @Override
            public List<GraphBucket> queryBuckets(SignalCatalogKey key, long startNanos, long endNanos, int targetBuckets) {
                bucketCalls.incrementAndGet();
                assertTrue(startNanos < ORIGIN && endNanos < ORIGIN, "stored query must not be an epoch timestamp");
                return List.of(new GraphBucket(startNanos, 1, startNanos, 1, endNanos, 1, endNanos, 1, 1));
            }

            @Override
            public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
                return List.of();
            }

            @Override
            public String unit(SignalCatalogKey key) {
                return "km/h";
            }

            @Override
            public long sampleCount(SignalCatalogKey key) {
                return 100_000L;
            }
        };
        GraphViewportEngine engine = new GraphViewportEngine(query, 2, new GraphQueryCache(8));
        engine.render(requests.getFirst());
        engine.render(requests.getFirst());
        assertEquals(1, bucketCalls.get());
        assertEquals(0L, requests.getFirst().timeOriginNanos());
    }

    @Test
    void planningLatencyStaysBoundedForOneToThreeHundredRecordings() {
        int[] sizes = {1, 73, 100, 300};
        for (int size : sizes) {
            List<RecordingDescriptor> recordings = unknownSet(size);
            long indexStart = System.nanoTime();
            RecordingGroupIndex index = RecordingGroupIndex.of(recordings);
            long indexNanos = System.nanoTime() - indexStart;
            long slot = UnknownOrderLayout.EQUAL_SLOT_SPAN;
            int target = size / 2;
            long planStart = System.nanoTime();
            TimelineViewportPlanner.Plan plan = plan(
                    index, TimelineGroup.UNKNOWN_ORDER, target * slot, (target + 1) * slot);
            long planNanos = System.nanoTime() - planStart;
            long fullStart = System.nanoTime();
            TimelineViewportPlanner.Plan full = plan(index, TimelineGroup.UNKNOWN_ORDER, 0L, size * slot);
            long fullNanos = System.nanoTime() - fullStart;
            assertEquals(1, plan.queriedRecordings());
            assertEquals(size - 1, plan.excludedOffScreen());
            assertTrue(plan.prefetch().size() <= TimelineViewportPlanner.MAX_PREFETCH);
            assertEquals(size, full.queriedRecordings());
            assertEquals(0, full.excludedOffScreen());
            assertEquals(0, full.prefetch().size());
            assertTrue(indexNanos < 200_000_000L, "index " + size + " took " + indexNanos);
            assertTrue(planNanos < 50_000_000L, "plan " + size + " took " + planNanos);
            assertTrue(fullNanos < 50_000_000L, "full " + size + " took " + fullNanos);
            System.out.println("TIMELINE size=" + size
                    + " indexMs=" + (indexNanos / 1_000_000.0)
                    + " oneSlotMs=" + (planNanos / 1_000_000.0)
                    + " fullOverviewMs=" + (fullNanos / 1_000_000.0)
                    + " queried=" + plan.queriedRecordings()
                    + " excludedOffScreen=" + plan.excludedOffScreen()
                    + " prefetch=" + plan.prefetch().size());
        }
    }

    private static void assertOverview(int count) {
        List<RecordingDescriptor> shuffled = new ArrayList<>(unknownSet(count));
        Collections.shuffle(shuffled);
        RecordingGroupIndex index = RecordingGroupIndex.of(shuffled);
        assertEquals(count, index.unknownLayout().size());
        assertEquals(0, index.verifiedUtc().size());
        for (int i = 0; i < count; i++) {
            UnknownOrderLayout.Slot slot = index.unknownLayout().slot(i);
            assertEquals(id(i), slot.measurementId());
            assertEquals(i * UnknownOrderLayout.EQUAL_SLOT_SPAN, slot.layoutStart());
            assertEquals(4_000L + i, slot.storedStartNanos());
            assertNotEquals(slot.layoutStart(), slot.storedStartNanos());
        }
        int target = Math.min(10, count - 1);
        long slot = UnknownOrderLayout.EQUAL_SLOT_SPAN;
        TimelineViewportPlanner.Plan plan = plan(index, TimelineGroup.UNKNOWN_ORDER, target * slot, (target + 1) * slot);
        assertEquals(List.of(id(target)), ids(plan.visible()));
        assertEquals(4_000L + target, plan.visible().getFirst().storedStartNanos());
        assertEquals(count - 1, plan.excludedOffScreen());
        assertTrue(plan.prefetch().size() <= TimelineViewportPlanner.MAX_PREFETCH);
        assertNull(index.verifiedUtcNanos(TimelineGroup.UNKNOWN_ORDER, target * slot));
        assertFalse(plan.connectsAcrossRecordings());
    }

    private static List<RecordingDescriptor> unknownSet(int count) {
        List<RecordingDescriptor> recordings = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            recordings.add(unknown(id(i), "import-" + (count - i) + ".dat", 4_000L + i, 9_000L + i));
        }
        return recordings;
    }

    private static String id(int index) {
        return String.format("m-%04d", index);
    }

    private static RecordingGroupIndex utcTrioShuffled() {
        List<RecordingDescriptor> recordings = new ArrayList<>(utcTrio());
        Collections.reverse(recordings);
        return RecordingGroupIndex.of(recordings);
    }

    private static List<RecordingDescriptor> utcTrio() {
        return List.of(
                utc("utc-a", "c.dat", ORIGIN, 0L, 20 * S),
                utc("utc-c", "a.dat", ORIGIN + 10 * S, 0L, 20 * S),
                utc("utc-b", "b.dat", ORIGIN + 40 * S, 0L, 20 * S));
    }

    private static TimelineViewportPlanner.Plan plan(
            RecordingGroupIndex index, TimelineGroup group, long start, long end) {
        return TimelineViewportPlanner.plan(index, group, start, end, SPEED, null);
    }

    private static Map<String, TimelineViewportPlanner.SourceQueryWindow> byId(
            List<TimelineViewportPlanner.SourceQueryWindow> windows) {
        Map<String, TimelineViewportPlanner.SourceQueryWindow> out = new LinkedHashMap<>();
        for (TimelineViewportPlanner.SourceQueryWindow window : windows) {
            out.put(window.measurementId(), window);
        }
        return out;
    }

    private static List<String> ids(List<TimelineViewportPlanner.SourceQueryWindow> windows) {
        return windows.stream().map(TimelineViewportPlanner.SourceQueryWindow::measurementId).toList();
    }

    private static List<String> slotIds(RecordingGroupIndex index) {
        return index.unknownLayout().slots().stream().map(UnknownOrderLayout.Slot::measurementId).toList();
    }

    private static RecordingDescriptor utc(String id, String file, long utcStart, long storedStart, long storedEnd) {
        RecordingClock clock = new RecordingClock(
                id, TimestampSource.MF4_HD, ClockKind.UTC, TimeUnitKind.NANOSECONDS,
                0, utcStart, ClockStatus.COMPARABLE, "");
        return new RecordingDescriptor(id, file, clock, storedStart, storedEnd);
    }

    private static RecordingDescriptor unknown(String id, String file, long storedStart, long storedEnd) {
        return new RecordingDescriptor(id, file, RecordingClock.unordered(
                id, TimestampSource.NONE, ClockKind.RELATIVE_ONLY, TimeUnitKind.NANOSECONDS, "no verified clock"),
                storedStart, storedEnd);
    }

    private static SignalCatalogKey key(String measurementId) {
        return new SignalCatalogKey(
                "VehicleSpeed", SignalSourceType.MF4_NATIVE, "DIRECT_MEASUREMENT", 0L, measurementId, "");
    }
}
