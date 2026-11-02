package com.stellar.platform.processing.map.time;

import com.stellar.platform.processing.map.MapRoute;
import com.stellar.platform.processing.map.ProjectRouteAssembler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingClockAndChronologyTest {

    private static final long START_150140 = 1_692_025_300_000_000_000L;
    private static final long START_161900 = 1_692_029_940_000_000_000L;
    private static final long START_164651 = 1_692_031_611_000_000_000L;
    private static final long START_171438 = 1_692_033_278_000_000_000L;

    @TempDir
    Path temp;

    @Test
    void mf4HeaderUtcMatchesTheBengaluruEpochAndIgnoresAZeroMasterClock() throws Exception {
        RecordingClockReader.clearCacheForTests();
        Path file = temp.resolve("late.dat");
        Files.write(file, mf4(START_161900, (short) 0, (short) 0, 0x02));
        RecordingClock clock = RecordingClockReader.read(file, "file-b");
        assertEquals(ClockKind.UTC, clock.kind());
        assertEquals(ClockStatus.COMPARABLE, clock.status());
        assertEquals(START_161900, clock.utcStartNanos());
        assertEquals(TimestampSource.MF4_HD, clock.source());
    }

    @Test
    void mf4LocalOffsetConvertsAndAnInvalidOffsetDoesNot() {
        TimestampObservation valid = new TimestampObservation(
                "a", TimestampSource.MF4_HD, TimestampRawKind.EPOCH_NANOS, TimeUnitKind.NANOSECONDS,
                330, true, true, START_150140, null, "MF4 HD local start with offsets");
        RecordingClock converted = TimestampNormalizer.normalize(valid);
        assertEquals(ClockKind.LOCAL_WITH_OFFSET, converted.kind());
        assertEquals(START_150140 - 330L * 60_000_000_000L, converted.utcStartNanos());

        TimestampObservation invalid = new TimestampObservation(
                "a", TimestampSource.MF4_HD, TimestampRawKind.EPOCH_NANOS, TimeUnitKind.NANOSECONDS,
                20 * 60, true, true, START_150140, null, "bad offset");
        RecordingClock rejected = TimestampNormalizer.normalize(invalid);
        assertEquals(ClockStatus.UNORDERED, rejected.status());
        assertNull(rejected.utcStartNanos());
        assertTrue(rejected.reason().contains("±14"));
    }

    @Test
    void zeroStartAndRelativeSamplesAreNotRecordingStarts() {
        RecordingClock zero = TimestampNormalizer.normalize(new TimestampObservation(
                "a", TimestampSource.MF4_HD, TimestampRawKind.EPOCH_NANOS, TimeUnitKind.NANOSECONDS,
                0, true, false, 0L, null, "MF4 recording start is zero"));
        assertEquals(ClockKind.MISSING, zero.kind());
        assertNull(zero.utcStartNanos());

        RecordingClock elapsed = TimestampNormalizer.normalize(new TimestampObservation(
                "a", TimestampSource.ASC_DATE, TimestampRawKind.ELAPSED, TimeUnitKind.SECONDS,
                null, false, false, 400_000_000L, null, "elapsed"));
        assertEquals(ClockKind.RELATIVE_ONLY, elapsed.kind());
        assertFalse(elapsed.comparable());
    }

    @Test
    void ascDateStaysLocalUntilAnExplicitOffsetIsSupplied() throws Exception {
        RecordingClockReader.clearCacheForTests();
        Path asc = temp.resolve("drive.asc");
        Files.writeString(asc, """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                  0.100000 1 123 Rx d 8 01
                """);
        RecordingClock local = RecordingClockReader.read(asc, "asc-1");
        assertEquals(ClockKind.LOCAL_UNKNOWN_ZONE, local.kind());
        assertFalse(local.comparable());
        assertTrue(local.reason().toLowerCase().contains("timezone") || local.reason().toLowerCase().contains("local"));

        RecordingClock supplied = RecordingClockReader.read(asc, "asc-1", OptionalInt.of(0));
        assertTrue(supplied.comparable());
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
                * 1_000_000L, supplied.utcStartNanos());
    }

    @Test
    void ascNumericOffsetIsComparableWithoutGuessing() {
        TimestampObservation observation = AscRecordingStartAdapter.read("asc",
                "date Thu Jan 01 12:00:00.000 PM 2026 +05:30\nbase hex timestamps absolute\n");
        RecordingClock clock = TimestampNormalizer.normalize(observation);
        assertEquals(ClockKind.LOCAL_WITH_OFFSET, clock.kind());
        assertEquals(330, clock.offsetMinutes());
        assertTrue(clock.comparable());
    }

    @Test
    void blfSystemTimeKeepsAnUnknownZone() throws Exception {
        RecordingClockReader.clearCacheForTests();
        Path blf = temp.resolve("session.log");
        Files.write(blf, blfHeader(2023, 8, 14, 15, 1, 40, 0));
        RecordingClock clock = RecordingClockReader.read(blf, "blf-1");
        assertEquals(TimestampSource.BLF_HEADER, clock.source());
        assertEquals(ClockKind.LOCAL_UNKNOWN_ZONE, clock.kind());
        assertFalse(clock.comparable());
    }

    @Test
    void textDistinguishesZonedIsoFromUnzonedAndIgnoresCandidateNames() throws Exception {
        RecordingClockReader.clearCacheForTests();
        Path zoned = temp.resolve("zoned.log");
        Files.writeString(zoned, """
                time,latitude
                2023-08-14T15:01:40Z,13.33
                2023-08-14T15:01:41Z,13.34
                """);
        RecordingClock absolute = RecordingClockReader.read(zoned, "text-z");
        assertEquals(ClockKind.ABSOLUTE_SAMPLE, absolute.kind());
        assertEquals(START_150140, absolute.utcStartNanos());

        Path bare = temp.resolve("bare.dat");
        Files.writeString(bare, """
                timestamp,speed
                2023-08-14T15:01:40,1
                2023-08-14T15:01:41,2
                """);
        RecordingClock unzoned = RecordingClockReader.read(bare, "text-u");
        assertEquals(ClockKind.LOCAL_UNKNOWN_ZONE, unzoned.kind());
        assertFalse(unzoned.comparable());

        Path named = temp.resolve("names.dat");
        Files.writeString(named, """
                GPS_Time,UTC_Time,Epoch,DateTime,Date_Time,RecordingTime,latitude
                1692025300,1692025300,1692025300,2023-08-14T15:01:40Z,2023-08-14T15:01:40Z,15:01:40,13.3
                1692025301,1692025301,1692025301,2023-08-14T15:01:41Z,2023-08-14T15:01:41Z,15:01:41,13.4
                """);
        RecordingClock ignored = RecordingClockReader.read(named, "text-n");
        assertFalse(ignored.comparable());
        assertEquals(ClockKind.MISSING, ignored.kind());

        Path elapsed = temp.resolve("elapsed.log");
        Files.writeString(elapsed, """
                time_s,latitude
                0.397,13.33
                0.742,13.34
                """);
        RecordingClock relative = RecordingClockReader.read(elapsed, "text-r");
        assertEquals(ClockKind.RELATIVE_ONLY, relative.kind());
        assertNull(relative.utcStartNanos());
    }

    @Test
    void datExtensionUsesTheMf4HeaderInsideTheFile() throws Exception {
        RecordingClockReader.clearCacheForTests();
        Path dat = temp.resolve("wrapped.dat");
        Files.write(dat, mf4(START_150140, (short) 0, (short) 0, 0x02));
        assertEquals("mf4", RecordingClockReader.detect(Files.readAllBytes(dat)));
        RecordingClock clock = RecordingClockReader.read(dat, "dat-1");
        assertEquals(START_150140, clock.utcStartNanos());
    }

    @Test
    void reverseUploadOrderSortsByRecordingStartAndLeavesUnknownFilesAfter() {
        List<ProjectRouteAssembler.PreparedMeasurement> uploaded = List.of(
                row("c"), row("a"), row("b"), row("z"));
        Map<String, RecordingClock> clocks = Map.of(
                "c", utc("c", START_164651),
                "a", utc("a", START_150140),
                "b", utc("b", START_161900),
                "z", RecordingClock.unordered("z", TimestampSource.NONE, ClockKind.MISSING, TimeUnitKind.NONE,
                        "measurement file is not available"));
        RouteChronology.Result result = RouteChronology.order(uploaded, clocks);
        assertEquals(List.of("a", "b", "c", "z"), result.measurements().stream()
                .map(ProjectRouteAssembler.PreparedMeasurement::measurementId).toList());
        assertEquals(3, result.comparableCount());
        assertEquals(1, result.unorderedCount());
        assertTrue(result.status().contains("3 of 4"));
        assertTrue(result.status().contains("1 not ordered"));
        assertFalse(ProjectRouteAssembler.connectsAdjacentFiles(null));
    }

    @Test
    void equalStartsTieBreakByMeasurementIdAndUnknownProjectsStayPut() {
        List<ProjectRouteAssembler.PreparedMeasurement> uploaded = List.of(row("b"), row("a"));
        RouteChronology.Result tied = RouteChronology.order(uploaded, Map.of(
                "b", utc("b", START_171438),
                "a", utc("a", START_171438)));
        assertEquals(List.of("a", "b"), tied.measurements().stream()
                .map(ProjectRouteAssembler.PreparedMeasurement::measurementId).toList());
        assertTrue(tied.fullyOrdered());

        RouteChronology.Result unknown = RouteChronology.order(List.of(row("c"), row("a"), row("b")), Map.of());
        assertEquals(List.of("c", "a", "b"), unknown.measurements().stream()
                .map(ProjectRouteAssembler.PreparedMeasurement::measurementId).toList());
        assertEquals(RouteChronology.unknownStatus(), unknown.status());
    }

    @Test
    void mdf3DateIsLocalAndNotUtc() {
        byte[] prefix = new byte[100];
        System.arraycopy("MDF     ".getBytes(StandardCharsets.US_ASCII), 0, prefix, 0, 8);
        System.arraycopy("3.30    ".getBytes(StandardCharsets.US_ASCII), 0, prefix, 8, 8);
        System.arraycopy("HD".getBytes(StandardCharsets.US_ASCII), 0, prefix, 64, 2);
        System.arraycopy("14:08:2023".getBytes(StandardCharsets.US_ASCII), 0, prefix, 82, 10);
        System.arraycopy("15:01:40".getBytes(StandardCharsets.US_ASCII), 0, prefix, 92, 8);
        RecordingClock clock = TimestampNormalizer.normalize(Mf4RecordingStartAdapter.read("mdf3", prefix));
        assertEquals(ClockKind.LOCAL_UNKNOWN_ZONE, clock.kind());
        assertEquals(TimestampSource.MDF3_HD, clock.source());
        assertFalse(clock.comparable());
    }

    private static ProjectRouteAssembler.PreparedMeasurement row(String id) {
        return new ProjectRouteAssembler.PreparedMeasurement(id, id + ".mf4", MapRoute.emptyRoute());
    }

    private static RecordingClock utc(String id, long nanos) {
        return new RecordingClock(id, TimestampSource.MF4_HD, ClockKind.UTC, TimeUnitKind.NANOSECONDS,
                0, nanos, ClockStatus.COMPARABLE, "MF4 HD UTC start");
    }

    private static byte[] mf4(long startNanos, short tz, short dst, int flags) {
        ByteBuffer buffer = ByteBuffer.allocate(64 + 104).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("MDF     ".getBytes(StandardCharsets.US_ASCII));
        buffer.put("4.11    ".getBytes(StandardCharsets.US_ASCII));
        buffer.position(64);
        buffer.put("##HD".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(0);
        buffer.putLong(104);
        buffer.putLong(6);
        for (int i = 0; i < 6; i++) {
            buffer.putLong(0);
        }
        buffer.putLong(startNanos);
        buffer.putShort(tz);
        buffer.putShort(dst);
        buffer.put((byte) flags);
        return buffer.array();
    }

    private static byte[] blfHeader(int year, int month, int day, int hour, int minute, int second, int millis) {
        ByteBuffer buffer = ByteBuffer.allocate(76).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[] {'L', 'O', 'G', 'G'});
        buffer.putInt(144);
        buffer.position(40);
        buffer.putShort((short) year);
        buffer.putShort((short) month);
        buffer.putShort((short) 0);
        buffer.putShort((short) day);
        buffer.putShort((short) hour);
        buffer.putShort((short) minute);
        buffer.putShort((short) second);
        buffer.putShort((short) millis);
        return buffer.array();
    }
}
