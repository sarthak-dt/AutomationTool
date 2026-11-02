package com.stellar.platform.server.decoder;

import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecoderDateTimeTest {

    @Test
    void sourceClockTruncatesSubMillisecondNanosWithoutShiftingTheClock() {
        SourceClock clock = SourceClock.civil(java.time.LocalDateTime.of(2023, 1, 18, 7, 52, 45));
        assertEquals("2023-01-18 07:52:45.000", clock.format(0));
        assertEquals("2023-01-18 07:52:46.394", clock.format(1_394_950_000L));
        assertEquals("2023-01-18 07:52:45.100", clock.format(100_000_000L));
    }

    @Test
    void timestampsCrossSecondMinuteDayAndYearBoundaries() {
        SourceClock clock = SourceClock.civil(java.time.LocalDateTime.of(2023, 1, 18, 7, 52, 45));
        assertEquals("2023-01-18 07:52:46.000", clock.format(1_000_000_000L));
        assertEquals("2023-01-18 07:53:45.000", clock.format(60_000_000_000L));
        SourceClock midnight = SourceClock.civil(java.time.LocalDateTime.of(2023, 1, 18, 23, 59, 59));
        assertEquals("2023-01-19 00:00:00.000", midnight.format(1_000_000_000L));
        assertEquals("2023-01-18 23:59:59.999", midnight.format(999_000_000L));
        SourceClock newYear = SourceClock.civil(java.time.LocalDateTime.of(2023, 12, 31, 23, 59, 59));
        assertEquals("2024-01-01 00:00:00.000", newYear.format(1_000_000_000L));
    }

    @Test
    void duplicateTimestampsStayAlignedAndSeparateOriginsStayIndependent() {
        List<SignalSamplePoint> points = List.of(
                point(1_394_950_000L, 1.0),
                point(1_394_950_000L, 2.0),
                point(1_994_100_000L, 3.0));
        SourceClock firstClock = SourceClock.civil(java.time.LocalDateTime.of(2023, 1, 18, 7, 52, 45));
        SourceClock secondClock = SourceClock.civil(java.time.LocalDateTime.of(2023, 1, 19, 7, 52, 45));
        Map<String, List<?>> first = DecoderSampleJson.columns(points, 0, firstClock);
        Map<String, List<?>> second = DecoderSampleJson.columns(points, 0, secondClock);
        assertEquals(List.of("2023-01-18 07:52:46.394", "2023-01-18 07:52:46.394", "2023-01-18 07:52:46.994"),
                first.get("sourceDatetime"));
        assertEquals("2023-01-19 07:52:46.394", ((List<?>) second.get("sourceDatetime")).getFirst());
        assertEquals(first.get("timestampsNs"), second.get("timestampsNs"));
        assertEquals(first.get("samples"), second.get("samples"));
        Map<String, List<?>> page = DecoderSampleJson.columns(points.subList(1, 3), 1, firstClock);
        assertEquals(((List<?>) first.get("sourceDatetime")).subList(1, 3), page.get("sourceDatetime"));
        assertEquals(List.of("1", "2"), page.get("sampleIndex"));
    }

    @Test
    void missingOrOverflowingTimeBecomesNullWithoutChangingSamples() {
        SignalSamplePoint point = point(100L, 7.5);
        Map<String, List<?>> missing = DecoderSampleJson.columns(List.of(point), 0, null);
        assertNull(missing.get("sourceDatetime").getFirst());
        assertEquals("100", missing.get("timestampsNs").getFirst());
        assertEquals(7.5d, ((Number) missing.get("samples").getFirst()).doubleValue());
        assertNull(SourceClock.civil(java.time.LocalDateTime.MAX).format(1L));
    }

    @Test
    void availabilityDistinguishesCompletePartialAndMissingClocks() {
        assertEquals("AVAILABLE", DecoderDateTimes.availability(2, 2));
        assertEquals("PARTIAL", DecoderDateTimes.availability(2, 1));
        assertEquals("UNAVAILABLE", DecoderDateTimes.availability(2, 0));
        assertEquals("UNAVAILABLE", DecoderDateTimes.availability(0, 0));
    }

    @Test
    void configuredZoneDefaultsToKolkataAndRejectsAnUnknownId() {
        DecoderValidationProperties properties = new DecoderValidationProperties();
        assertEquals("Asia/Kolkata", properties.datetimeZoneId().getId());
        properties.setDatetimeZone("UTC");
        assertEquals("UTC", properties.datetimeZoneId().getId());
        properties.setDatetimeZone("  ");
        assertEquals("Asia/Kolkata", properties.datetimeZoneId().getId());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> properties.setDatetimeZone("Not/AZone"));
        assertTrue(failure.getMessage().contains("stellar.decoder.datetime-zone"));
    }

    @Test
    void springRejectsAnInvalidDatetimeZoneAtStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(DecoderValidationConfiguration.class)
                .withPropertyValues(
                        "stellar.decoder.validation-api=true",
                        "stellar.decoder.datetime-zone=Not/AZone")
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null);
                    Throwable cause = context.getStartupFailure();
                    boolean found = false;
                    while (cause != null) {
                        if (cause.getMessage() != null && cause.getMessage().contains("stellar.decoder.datetime-zone")) {
                            found = true;
                            break;
                        }
                        cause = cause.getCause();
                    }
                    assertTrue(found);
                });
        new ApplicationContextRunner()
                .withUserConfiguration(DecoderValidationConfiguration.class)
                .withPropertyValues(
                        "stellar.decoder.validation-api=true",
                        "stellar.decoder.datetime-zone=Europe/Berlin")
                .run(context -> assertEquals(
                        "Europe/Berlin",
                        context.getBean(DecoderValidationProperties.class).datetimeZoneId().getId()));
    }

    private static SignalSamplePoint point(long timestampNanos, double value) {
        return new SignalSamplePoint(timestampNanos, value, 1L, null, SignalValidity.VALID);
    }
}
