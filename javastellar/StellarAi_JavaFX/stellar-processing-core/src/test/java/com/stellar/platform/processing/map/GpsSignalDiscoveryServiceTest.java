package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpsSignalDiscoveryServiceTest {

    private static SignalCatalogEntry entry(String name, String unit, String file) {
        return entry(name, unit, file, "GPS", 0, 100, 0L, 1_000L, null, null);
    }

    private static SignalCatalogEntry entry(
            String name, String unit, String file, String message, int channel,
            long samples, Long first, Long last, Double min, Double max) {
        SignalCatalogKey key = new SignalCatalogKey(
                name, SignalSourceType.MF4_NATIVE, message, 0, channel, file, "");
        return new SignalCatalogEntry(key, unit, samples, first, last, min, max, file, "");
    }

    @Test
    void detectsCanBusGpsLatInfoPairWithoutFilenameOrExactNameSpecialCases() {
        SignalCatalogKey lat = new SignalCatalogKey(
                "GPS_LAT_INFO", SignalSourceType.DBC_CAN, "GPS_COORDINATE_22C", 556, 3,
                "bengaluru.mf4", "3.dbc");
        SignalCatalogKey lon = new SignalCatalogKey(
                "GPS_LONG_INFO", SignalSourceType.DBC_CAN, "GPS_COORDINATE_22C", 556, 3,
                "bengaluru.mf4", "3.dbc");
        SignalCatalogKey alt = new SignalCatalogKey(
                "GPS_ALTITUDE", SignalSourceType.DBC_CAN, "GPS_COORDINATE_22D", 557, 3,
                "bengaluru.mf4", "3.dbc");
        GpsDetectionResult result = new GpsSignalDiscoveryService().discover(List.of(
                new SignalCatalogEntry(lat, "Decimal Degrees", 1631, 881_980_000L, 1_662_955_100_000L,
                        13.042, 13.209, "bengaluru.mf4", "3.dbc"),
                new SignalCatalogEntry(lon, "", 1631, 881_980_000L, 1_662_955_100_000L,
                        77.591, 77.646, "bengaluru.mf4", "3.dbc"),
                new SignalCatalogEntry(alt, "metres", 1631, 882_080_000L, 1_662_955_320_000L,
                        805.0, 854.0, "bengaluru.mf4", "3.dbc")));
        assertTrue(result.hasReliableRoute(), result.summary());
        assertEquals("GPS_LAT_INFO", result.pair().orElseThrow().latitude().signalName());
        assertEquals("GPS_LONG_INFO", result.pair().orElseThrow().longitude().signalName());
        assertEquals("GPS_ALTITUDE", result.pair().orElseThrow().altitudeOptional().orElseThrow().signalName());
    }

    @Test
    void accuracyLatLongIsNotChosenWhenCoordinateSignalsExist() {
        SignalCatalogKey lat = new SignalCatalogKey(
                "GPS_LAT_INFO", SignalSourceType.DBC_CAN, "GPS_COORDINATE_22C", 556, 3,
                "bengaluru.mf4", "3.dbc");
        SignalCatalogKey lon = new SignalCatalogKey(
                "GPS_LONG_INFO", SignalSourceType.DBC_CAN, "GPS_COORDINATE_22C", 556, 3,
                "bengaluru.mf4", "3.dbc");
        SignalCatalogKey accuracy = new SignalCatalogKey(
                "GPS_ACCURACY_LAT_LONG", SignalSourceType.DBC_CAN, "GPS_STATUS", 100, 3,
                "bengaluru.mf4", "3.dbc");
        GpsDetectionResult result = new GpsSignalDiscoveryService().discover(List.of(
                new SignalCatalogEntry(accuracy, "m", 1631, 881_980_000L, 1_662_955_100_000L,
                        0.4, 1.8, "bengaluru.mf4", "3.dbc"),
                new SignalCatalogEntry(lat, "Decimal Degrees", 1631, 881_980_000L, 1_662_955_100_000L,
                        13.042, 13.209, "bengaluru.mf4", "3.dbc"),
                new SignalCatalogEntry(lon, "", 1631, 881_980_000L, 1_662_955_100_000L,
                        77.591, 77.646, "bengaluru.mf4", "3.dbc"),
                entry("GPS_DOP", "", "bengaluru.mf4"),
                entry("GPS_FIX_TYPE", "", "bengaluru.mf4"),
                entry("GPS_ACCURACY_SPEED", "m/s", "bengaluru.mf4")));
        assertTrue(result.hasReliableRoute(), result.summary());
        assertEquals("GPS_LAT_INFO", result.pair().orElseThrow().latitude().signalName());
        assertEquals("GPS_LONG_INFO", result.pair().orElseThrow().longitude().signalName());
    }

    @Test
    void detectsGpsLatLon() {
        GpsDetectionResult result = new GpsSignalDiscoveryService().discover(List.of(
                entry("GPS_LAT", "deg", "a.mf4"),
                entry("GPS_LON", "deg", "a.mf4"),
                entry("EngineRPM", "rpm", "a.mf4")));
        assertTrue(result.hasReliableRoute());
        assertEquals("GPS_LAT", result.pair().orElseThrow().latitude().signalName());
        assertEquals("GPS_LON", result.pair().orElseThrow().longitude().signalName());
    }

    @Test
    void detectsGnssPositionNames() {
        assertTrue(new GpsSignalDiscoveryService().discover(List.of(
                entry("GNSS_Position_Latitude", "deg", "b.mf4"),
                entry("GNSS_Position_Longitude", "deg", "b.mf4"))).hasReliableRoute());
    }

    @Test
    void detectsVehiclePositionLatLng() {
        GpsDetectionResult result = new GpsSignalDiscoveryService().discover(List.of(
                entry("VehiclePositionLat", "deg", "c.mf4"),
                entry("VehiclePositionLng", "deg", "c.mf4")));
        assertTrue(result.hasReliableRoute());
    }

    @Test
    void detectsNavLatNavLong() {
        assertTrue(new GpsSignalDiscoveryService().discover(List.of(
                entry("Nav_Lat", "deg", "d.mf4"),
                entry("Nav_Long", "deg", "d.mf4"))).hasReliableRoute());
    }

    @Test
    void selectedGraphSignalsAreIrrelevant() {
        // Catalog has GPS + engine signals; "selected" set is not passed — discovery uses full catalog.
        GpsDetectionResult result = new GpsSignalDiscoveryService().discover(List.of(
                entry("EngineRPM", "rpm", "drive.mf4"),
                entry("EngineLoad", "%", "drive.mf4"),
                entry("GPS_LAT", "deg", "drive.mf4", "GPS", 9, 200, 0L, 200L, 18.5, 18.6),
                entry("GPS_LON", "deg", "drive.mf4", "GPS", 9, 200, 0L, 200L, 73.7, 73.8)));
        assertTrue(result.hasReliableRoute(), "Map Route must find GPS without Graph checkboxes");
        assertEquals("GPS_LAT", result.pair().orElseThrow().latitude().signalName());
        assertEquals("GPS_LON", result.pair().orElseThrow().longitude().signalName());
    }

    @Test
    void rejectsFalsePositiveAnglesAndAccuracy() {
        GpsDetectionResult result = new GpsSignalDiscoveryService().discover(List.of(
                entry("SteeringAngle", "deg", "car.mf4", "Chassis", 1, 100, 0L, 100L, -30.0, 30.0),
                entry("GPSAccuracy", "m", "car.mf4", "GPS", 9, 100, 0L, 100L, 0.0, 10.0),
                entry("VehicleSpeed", "km/h", "car.mf4"),
                entry("YawRate", "deg/s", "car.mf4")));
        assertFalse(result.hasReliableRoute());
        assertFalse(result.hasRoute());
    }

    @Test
    void sourceScopePairsWithinSameFileOnly() {
        GpsDetectionResult onlyA = new GpsSignalDiscoveryService().discover(List.of(
                entry("GPS_LAT_A", "deg", "sourceA.mf4"),
                entry("GPS_LON_A", "deg", "sourceA.mf4")));
        assertEquals("sourceA.mf4", onlyA.pair().orElseThrow().latitude().sourceFile());
        assertEquals("sourceA.mf4", onlyA.pair().orElseThrow().longitude().sourceFile());
    }

    @Test
    void prefersSameSourceWhenMultipleFilesPresent() {
        GpsDetectionResult result = new GpsSignalDiscoveryService().discover(List.of(
                entry("GPS_LAT", "deg", "sourceA.mf4", "GPS", 9, 1_000, 0L, 1_000L, 18.0, 19.0),
                entry("GPS_LON", "deg", "sourceA.mf4", "GPS", 9, 1_000, 0L, 1_000L, 73.0, 74.0),
                entry("GPS_LAT", "deg", "sourceB.mf4", "GPS", 9, 50, 0L, 50L, 12.0, 13.0),
                entry("GPS_LON", "deg", "sourceB.mf4", "GPS", 9, 50, 0L, 50L, 77.0, 78.0)));
        assertTrue(result.hasReliableRoute());
        assertEquals("sourceA.mf4", result.pair().orElseThrow().latitude().sourceFile());
        assertEquals("sourceA.mf4", result.pair().orElseThrow().longitude().sourceFile());
    }

    @Test
    void rejectsOutOfRangeCatalogExtrema() {
        GpsDetectionResult result = new GpsSignalDiscoveryService().discover(List.of(
                entry("Latitude", "deg", "x.mf4", "GPS", 0, 10, 0L, 10L, 100.0, 120.0),
                entry("Longitude", "deg", "x.mf4", "GPS", 0, 10, 0L, 10L, 73.0, 74.0)));
        assertFalse(result.hasReliableRoute());
    }

    @Test
    void cachesByGeneration() {
        GpsSignalDiscoveryService service = new GpsSignalDiscoveryService();
        List<SignalCatalogEntry> catalog = List.of(
                entry("Latitude", "deg", "a.mf4"),
                entry("Longitude", "deg", "a.mf4"));
        GpsDetectionResult first = service.discover(catalog, "gen-1");
        GpsDetectionResult second = service.discover(catalog, "gen-1");
        assertSame(first, second);

        service.invalidate("gen-1");
        GpsDetectionResult third = service.discover(catalog, "gen-2");
        assertTrue(third.hasReliableRoute());
        assertFalse(third == first);
    }

    @Test
    void unknownPosSignalsAreNotAutoMapped() {
        GpsDetectionResult result = new GpsSignalDiscoveryService().discover(List.of(
                entry("Pos01", "", "odd.mf4"),
                entry("Pos02", "", "odd.mf4"),
                entry("Pos03", "", "odd.mf4")));
        assertFalse(result.hasReliableRoute());
        assertFalse(result.hasRoute());
    }

    @Test
    void manualOverrideWinsOverAutoNames() {
        SignalCatalogKey lat = new SignalCatalogKey("Pos01", SignalSourceType.MF4_NATIVE, "GPS", 0, 0, "odd.mf4", "");
        SignalCatalogKey lon = new SignalCatalogKey("Pos02", SignalSourceType.MF4_NATIVE, "GPS", 0, 0, "odd.mf4", "");
        GpsDetectionResult result = new GpsSignalDiscoveryService().discover(List.of(
                entry("Latitude", "deg", "odd.mf4"),
                entry("Longitude", "deg", "odd.mf4"),
                entry("Pos01", "", "odd.mf4"),
                entry("Pos02", "", "odd.mf4")), "gen-manual",
                java.util.Map.of(GpsSignalRole.LATITUDE, lat, GpsSignalRole.LONGITUDE, lon));
        assertTrue(result.hasReliableRoute());
        assertEquals("Pos01", result.pair().orElseThrow().latitude().signalName());
        assertEquals("Pos02", result.pair().orElseThrow().longitude().signalName());
    }

    @Test
    void manualCoordinatesStillPickUpAutomaticallyDetectedOptionalRoles() {
        // Mapping Latitude/Longitude by hand overrides those two roles and nothing else: altitude,
        // GPS speed and course are still recorded and must keep reaching the route detail panels.
        SignalCatalogKey lat = new SignalCatalogKey("Pos01", SignalSourceType.MF4_NATIVE, "GPS", 0, 0, "odd.mf4", "");
        SignalCatalogKey lon = new SignalCatalogKey("Pos02", SignalSourceType.MF4_NATIVE, "GPS", 0, 0, "odd.mf4", "");

        GpsCoordinatePair pair = new GpsSignalDiscoveryService().discover(List.of(
                entry("Pos01", "", "odd.mf4"),
                entry("Pos02", "", "odd.mf4"),
                entry("GPS_Altitude", "m", "odd.mf4"),
                entry("GPS_Speed", "km/h", "odd.mf4"),
                entry("GPS_Course", "deg", "odd.mf4")), "gen-manual-optional",
                java.util.Map.of(GpsSignalRole.LATITUDE, lat, GpsSignalRole.LONGITUDE, lon))
                .pair().orElseThrow();

        assertEquals("Pos01", pair.latitude().signalName());
        assertEquals("Pos02", pair.longitude().signalName());
        assertEquals("GPS_Altitude", pair.altitudeOptional().orElseThrow().signalName());
        assertEquals("GPS_Speed", pair.speedOptional().orElseThrow().signalName());
        assertEquals("GPS_Course", pair.headingOptional().orElseThrow().signalName());
    }

    @Test
    void manualOptionalRoleStillOverridesTheDetectedOne() {
        SignalCatalogKey lat = new SignalCatalogKey("Pos01", SignalSourceType.MF4_NATIVE, "GPS", 0, 0, "odd.mf4", "");
        SignalCatalogKey lon = new SignalCatalogKey("Pos02", SignalSourceType.MF4_NATIVE, "GPS", 0, 0, "odd.mf4", "");
        SignalCatalogKey speed = new SignalCatalogKey(
                "Wheel_Speed", SignalSourceType.MF4_NATIVE, "GPS", 0, 0, "odd.mf4", "");

        GpsCoordinatePair pair = new GpsSignalDiscoveryService().discover(List.of(
                entry("Pos01", "", "odd.mf4"),
                entry("Pos02", "", "odd.mf4"),
                entry("GPS_Speed", "km/h", "odd.mf4"),
                entry("Wheel_Speed", "km/h", "odd.mf4")), "gen-manual-speed",
                java.util.Map.of(
                        GpsSignalRole.LATITUDE, lat,
                        GpsSignalRole.LONGITUDE, lon,
                        GpsSignalRole.GPS_SPEED, speed))
                .pair().orElseThrow();

        assertEquals("Wheel_Speed", pair.speedOptional().orElseThrow().signalName(),
                "an explicit optional choice must win over detection");
    }

    @Test
    void manualCoordinatesWithNoOptionalSignalsPresentStillProduceARoute() {
        SignalCatalogKey lat = new SignalCatalogKey("Pos01", SignalSourceType.MF4_NATIVE, "GPS", 0, 0, "odd.mf4", "");
        SignalCatalogKey lon = new SignalCatalogKey("Pos02", SignalSourceType.MF4_NATIVE, "GPS", 0, 0, "odd.mf4", "");

        GpsDetectionResult result = new GpsSignalDiscoveryService().discover(List.of(
                entry("Pos01", "", "odd.mf4"),
                entry("Pos02", "", "odd.mf4")), "gen-manual-bare",
                java.util.Map.of(GpsSignalRole.LATITUDE, lat, GpsSignalRole.LONGITUDE, lon));

        assertTrue(result.hasReliableRoute());
        assertTrue(result.pair().orElseThrow().altitudeOptional().isEmpty());
        assertTrue(result.pair().orElseThrow().speedOptional().isEmpty());
    }

    @Test
    void optionalRolesAreTakenFromTheSameRecordingAsTheManualCoordinates() {
        SignalCatalogKey lat = new SignalCatalogKey("Pos01", SignalSourceType.MF4_NATIVE, "GPS", 0, 0, "a.mf4", "");
        SignalCatalogKey lon = new SignalCatalogKey("Pos02", SignalSourceType.MF4_NATIVE, "GPS", 0, 0, "a.mf4", "");

        GpsCoordinatePair pair = new GpsSignalDiscoveryService().discover(List.of(
                entry("Pos01", "", "a.mf4"),
                entry("Pos02", "", "a.mf4"),
                entry("GPS_Altitude", "m", "b.mf4")), "gen-manual-cross-source",
                java.util.Map.of(GpsSignalRole.LATITUDE, lat, GpsSignalRole.LONGITUDE, lon))
                .pair().orElseThrow();

        assertTrue(pair.altitudeOptional().isEmpty(),
                "an optional role must not be borrowed from a different measurement");
    }

    @Test
    void pairsAcrossDifferentCanMessagesOnSameBus() {
        // Real DBC GPS often splits lat/lon across messages; messageName must not block pairing.
        GpsDetectionResult result = new GpsSignalDiscoveryService().discover(List.of(
                entry("Latitude", "deg", "bus.mf4", "GPS_Status", 1, 100, 0L, 100L, 51.0, 52.0),
                entry("Longitude", "deg", "bus.mf4", "GPS_Position", 1, 100, 0L, 100L, -0.2, -0.1)));
        assertTrue(result.hasReliableRoute(),
                "Same file + same channel must pair even when CAN messages differ");
    }
}
