package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpsSignalValidatorTest {

    private static SignalCatalogEntry entry(String name, double min, double max) {
        SignalCatalogKey key = new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "GPS", 0);
        return new SignalCatalogEntry(key, "deg", 10, 0L, 1L, min, max, "a.mf4", "");
    }

    @Test
    void latitudeRangeMustStayInsidePlusMinus90() {
        assertEquals(GpsSignalValidator.Severity.OK,
                GpsSignalValidator.validate(GpsSignalRole.LATITUDE, entry("Latitude", -90, 90)).severity());
        assertEquals(GpsSignalValidator.Severity.SUSPICIOUS,
                GpsSignalValidator.validate(GpsSignalRole.LATITUDE, entry("EngineRPM", 780, 6200)).severity());
        assertTrue(GpsSignalValidator.validate(GpsSignalRole.LATITUDE, entry("EngineRPM", 780, 6200))
                .message().contains("valid Latitude"));
    }

    @Test
    void longitudeRangeMustStayInsidePlusMinus180() {
        assertEquals(GpsSignalValidator.Severity.OK,
                GpsSignalValidator.validate(GpsSignalRole.LONGITUDE, entry("Longitude", -180, 180)).severity());
        assertEquals(GpsSignalValidator.Severity.SUSPICIOUS,
                GpsSignalValidator.validate(GpsSignalRole.LONGITUDE, entry("Yaw", -400, 400)).severity());
    }

    @Test
    void nonFiniteExtremaAreImpossible() {
        assertTrue(GpsSignalValidator.validate(GpsSignalRole.LATITUDE,
                entry("Broken", Double.NaN, 10)).blocking());
        assertFalse(GpsSignalValidator.validSample(GpsSignalRole.LATITUDE, Double.NaN));
        assertFalse(GpsSignalValidator.validSample(GpsSignalRole.LONGITUDE, Double.POSITIVE_INFINITY));
        assertFalse(GpsSignalValidator.validSample(GpsSignalRole.LATITUDE, 120));
    }

    @Test
    void repeatedZeroZeroIsSuspiciousButIsolatedZeroIsNot() {
        assertTrue(GpsSignalValidator.suspiciousZeroZeroRun(20, 20));
        assertTrue(GpsSignalValidator.suspiciousZeroZeroRun(40, 200));
        assertFalse(GpsSignalValidator.suspiciousZeroZeroRun(1, 200));
    }

    // --- likely radians: advise, never convert ---

    private static SignalCatalogEntry entry(String name, String unit, double min, double max) {
        SignalCatalogKey key = new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "GPS", 0);
        return new SignalCatalogEntry(key, unit, 10, 0L, 1L, min, max, "a.mf4", "");
    }

    @Test
    void aUnitOfRadiansOnACoordinateIsAdvisedNotAccepted() {
        GpsSignalValidator.Result result =
                GpsSignalValidator.validate(GpsSignalRole.LATITUDE, entry("Latitude", "rad", 0.31, 0.33));

        assertEquals(GpsSignalValidator.Severity.SUSPICIOUS, result.severity());
        assertFalse(result.blocking(), "the user must still be able to map it deliberately");
        assertTrue(result.message().contains(GpsSignalValidator.RADIAN_ADVISORY), result.message());
        assertTrue(result.message().contains("rad"), result.message());
    }

    @Test
    void coordinatesThatBothFitInsideTheRadianRangeAreAdvised() {
        GpsSignalValidator.Result result = GpsSignalValidator.coordinatePairAdvisory(
                entry("Latitude", "", 0.3229, 0.3231),
                entry("Longitude", "", 1.2864, 1.2866));

        assertEquals(GpsSignalValidator.Severity.SUSPICIOUS, result.severity());
        assertTrue(result.message().contains(GpsSignalValidator.RADIAN_ADVISORY), result.message());
    }

    @Test
    void anExplicitDegreeUnitIsTrustedEvenNearTheEquatorAndGreenwich() {
        // Accra is a real place at roughly (5.6, -0.2); it must not be flagged as radians.
        assertEquals(GpsSignalValidator.Severity.OK, GpsSignalValidator.coordinatePairAdvisory(
                entry("Latitude", "deg", 5.55, 5.62),
                entry("Longitude", "deg", -0.25, -0.15)).severity());
    }

    @Test
    void ordinaryDegreeCoordinatesAreNotFlagged() {
        assertEquals(GpsSignalValidator.Severity.OK, GpsSignalValidator.coordinatePairAdvisory(
                entry("Latitude", "", 18.50, 18.56),
                entry("Longitude", "", 73.80, 73.92)).severity());
    }

    @Test
    void aLatitudeTooLargeForRadiansRulesTheAdvisoryOut() {
        // Only one coordinate is small; a latitude of 48° cannot be radians.
        assertEquals(GpsSignalValidator.Severity.OK, GpsSignalValidator.coordinatePairAdvisory(
                entry("Latitude", "", 48.1, 48.2),
                entry("Longitude", "", 0.10, 0.12)).severity());
    }

    @Test
    void decimalDegreesUnitIsAuthoritativeEvenWhenBothRangesFitRadians() {
        GpsSignalValidator.Result result = GpsSignalValidator.coordinatePairAdvisory(
                entry("GPS_LAT_INFO", "Decimal Degrees", 0.0, 0.0),
                entry("GPS_LONG_INFO", "", 0.0, 0.0));

        assertFalse(result.message().contains(GpsSignalValidator.RADIAN_ADVISORY),
                "DBC unit Decimal Degrees must not be classified as radians: " + result.message());
    }

    @Test
    void eitherCoordinateDeclaringDegreesSuppressesTheMagnitudeHeuristic() {
        // Bengaluru as ~0.23 / ~1.35 would look like radians, but DBC says degrees.
        GpsSignalValidator.Result result = GpsSignalValidator.coordinatePairAdvisory(
                entry("GPS_LAT_INFO", "Decimal Degrees", 0.2268, 0.2271),
                entry("GPS_LONG_INFO", "", 1.3538, 1.3542));

        assertEquals(GpsSignalValidator.Severity.OK, result.severity(), result.message());
        assertFalse(result.message().contains(GpsSignalValidator.RADIAN_ADVISORY), result.message());
    }

    @Test
    void exactZeroZeroExtremaAreAMissingFixNotRadians() {
        GpsSignalValidator.Result result = GpsSignalValidator.coordinatePairAdvisory(
                entry("GPS_LAT_INFO", "", 0.0, 0.0),
                entry("GPS_LONG_INFO", "", 0.0, 0.0));

        assertEquals(GpsSignalValidator.Severity.SUSPICIOUS, result.severity());
        assertFalse(result.message().contains(GpsSignalValidator.RADIAN_ADVISORY), result.message());
        assertTrue(result.message().toLowerCase().contains("0,0")
                || result.message().toLowerCase().contains("missing"), result.message());
    }

    @Test
    void ordinaryDegreeCoordinatesRemainUnchangedForMapPresentation() {
        assertEquals(GpsSignalValidator.Severity.OK, GpsSignalValidator.coordinatePairAdvisory(
                entry("Latitude", "deg", 12.91, 13.05),
                entry("Longitude", "deg", 77.55, 77.68)).severity());
        assertTrue(GpsSignalValidator.validSample(GpsSignalRole.LATITUDE, 12.97));
        assertTrue(GpsSignalValidator.validSample(GpsSignalRole.LONGITUDE, 77.60));
    }

    @Test
    void explicitRadianUnitIsStillAdvisedAndNeverConvertedHere() {
        GpsSignalValidator.Result result = GpsSignalValidator.coordinatePairAdvisory(
                entry("Latitude", "rad", 0.2268, 0.2271),
                entry("Longitude", "rad", 1.3538, 1.3542));

        assertEquals(GpsSignalValidator.Severity.SUSPICIOUS, result.severity());
        assertTrue(result.message().contains(GpsSignalValidator.RADIAN_ADVISORY), result.message());
        assertEquals(0.2268, Math.toDegrees(0.2268) * Math.PI / 180.0, 1e-12);
    }

    @Test
    void missingExtremaCannotProduceARadianAdvisory() {
        SignalCatalogKey key = new SignalCatalogKey("Latitude", SignalSourceType.MF4_NATIVE, "GPS", 0);
        SignalCatalogEntry unknownRange = new SignalCatalogEntry(key, "", 10, 0L, 1L, null, null, "a.mf4", "");

        assertEquals(GpsSignalValidator.Severity.OK,
                GpsSignalValidator.coordinatePairAdvisory(unknownRange, unknownRange).severity());
    }

    @Test
    void impossibleDegreeValuesAreStillReportedAlongsideTheRadianCheck() {
        assertEquals(GpsSignalValidator.Severity.SUSPICIOUS,
                GpsSignalValidator.validate(GpsSignalRole.LATITUDE, entry("Latitude", "deg", -120, 130)).severity());
    }
}
