package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpsSignalRoleResolverTest {

    private static SignalCatalogEntry entry(String name, String unit) {
        return entry(name, unit, 100);
    }

    private static SignalCatalogEntry entry(String name, String unit, long sampleCount) {
        SignalCatalogKey key = new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "GPS", 0);
        return new SignalCatalogEntry(key, unit, sampleCount, 0L, 1_000L, null, null, "drive.mf4", "");
    }

    @Test
    void resolvesCanonicalNames() {
        Map<GpsSignalRole, SignalCatalogKey> roles = new GpsSignalRoleResolver().resolve(List.of(
                entry("Latitude", "deg"),
                entry("Longitude", "deg"),
                entry("Altitude", "m"),
                entry("GPS_Speed", "km/h"),
                entry("Course_Over_Ground", "deg"),
                entry("Number_Satellites", "count")));

        assertEquals("Latitude", roles.get(GpsSignalRole.LATITUDE).signalName());
        assertEquals("Longitude", roles.get(GpsSignalRole.LONGITUDE).signalName());
        assertEquals("Altitude", roles.get(GpsSignalRole.ALTITUDE).signalName());
        assertEquals("GPS_Speed", roles.get(GpsSignalRole.GPS_SPEED).signalName());
        assertEquals("Course_Over_Ground", roles.get(GpsSignalRole.COURSE).signalName());
        assertEquals("Number_Satellites", roles.get(GpsSignalRole.SATELLITE_COUNT).signalName());
        assertTrue(GpsSignalRoleResolver.hasRoute(roles));
    }

    @Test
    void resolvesVendorPrefixedAndAbbreviatedNames() {
        Map<GpsSignalRole, SignalCatalogKey> roles = new GpsSignalRoleResolver().resolve(List.of(
                entry("GPS_Lat", "deg"),
                entry("GPS_Lon", "deg")));

        assertEquals("GPS_Lat", roles.get(GpsSignalRole.LATITUDE).signalName());
        assertEquals("GPS_Lon", roles.get(GpsSignalRole.LONGITUDE).signalName());
    }

    @Test
    void spelledOutNameOutranksAbbreviation() {
        Map<GpsSignalRole, SignalCatalogKey> roles = new GpsSignalRoleResolver().resolve(List.of(
                entry("GPS_Lat", "deg"),
                entry("Latitude", "deg")));

        assertEquals("Latitude", roles.get(GpsSignalRole.LATITUDE).signalName());
    }

    @Test
    void resolvesGpsLatInfoAndGpsLongInfoFromCanMessages() {
        SignalCatalogKey lat = new SignalCatalogKey(
                "GPS_LAT_INFO", SignalSourceType.DBC_CAN, "GPS_COORDINATE_22C", 556, 3, "drive.mf4", "veh.dbc");
        SignalCatalogKey lon = new SignalCatalogKey(
                "GPS_LONG_INFO", SignalSourceType.DBC_CAN, "GPS_COORDINATE_22C", 556, 3, "drive.mf4", "veh.dbc");
        SignalCatalogKey alt = new SignalCatalogKey(
                "GPS_ALTITUDE", SignalSourceType.DBC_CAN, "GPS_COORDINATE_22D", 557, 3, "drive.mf4", "veh.dbc");
        Map<GpsSignalRole, SignalCatalogKey> roles = new GpsSignalRoleResolver().resolve(List.of(
                new SignalCatalogEntry(lat, "Decimal Degrees", 100, 0L, 1_000L, 13.0, 13.2, "drive.mf4", "veh.dbc"),
                new SignalCatalogEntry(lon, "", 100, 0L, 1_000L, 77.5, 77.7, "drive.mf4", "veh.dbc"),
                new SignalCatalogEntry(alt, "metres", 100, 0L, 1_000L, 800.0, 860.0, "drive.mf4", "veh.dbc")));

        assertEquals("GPS_LAT_INFO", roles.get(GpsSignalRole.LATITUDE).signalName());
        assertEquals("GPS_LONG_INFO", roles.get(GpsSignalRole.LONGITUDE).signalName());
        assertEquals("GPS_ALTITUDE", roles.get(GpsSignalRole.ALTITUDE).signalName());
        assertTrue(GpsSignalRoleResolver.hasRoute(roles));
    }

    @Test
    void gpsAccuracyAndStatusSignalsAreNotCoordinates() {
        Map<GpsSignalRole, SignalCatalogKey> roles = new GpsSignalRoleResolver().resolve(List.of(
                entry("GPS_ACCURACY_LAT_LONG", "m"),
                entry("GPS_ACCURACY_SPEED", "m/s"),
                entry("GPS_DOP", ""),
                entry("GPS_FIX_TYPE", "")));

        assertNull(roles.get(GpsSignalRole.LATITUDE));
        assertNull(roles.get(GpsSignalRole.LONGITUDE));
        assertEquals(0, GpsSignalRoleResolver.scoreRole(GpsSignalRole.LATITUDE, entry("GPS_ACCURACY_LAT_LONG", "m")));
        assertEquals(0, GpsSignalRoleResolver.scoreRole(GpsSignalRole.LONGITUDE, entry("GPS_ACCURACY_LAT_LONG", "m")));
        assertEquals(0, GpsSignalRoleResolver.scoreRole(GpsSignalRole.LATITUDE, entry("GPS_ACCURACY_SPEED", "m/s")));
        assertEquals(0, GpsSignalRoleResolver.scoreRole(GpsSignalRole.LONGITUDE, entry("GPS_ACCURACY_SPEED", "m/s")));
        assertEquals(0, GpsSignalRoleResolver.scoreRole(GpsSignalRole.LATITUDE, entry("GPS_DOP", "")));
        assertEquals(0, GpsSignalRoleResolver.scoreRole(GpsSignalRole.LONGITUDE, entry("GPS_DOP", "")));
        assertEquals(0, GpsSignalRoleResolver.scoreRole(GpsSignalRole.LATITUDE, entry("GPS_FIX_TYPE", "")));
        assertEquals(0, GpsSignalRoleResolver.scoreRole(GpsSignalRole.LONGITUDE, entry("GPS_FIX_TYPE", "")));
    }

    @Test
    void doesNotMatchUnrelatedSignalsContainingRoleFragments() {
        Map<GpsSignalRole, SignalCatalogKey> roles = new GpsSignalRoleResolver().resolve(List.of(
                entry("Latch_State", ""),
                entry("BCCM_DOOR_ACCESS_WAKEUP", ""),
                entry("VehicleSpeed", "km/h")));

        assertNull(roles.get(GpsSignalRole.LATITUDE));
        assertNull(roles.get(GpsSignalRole.LONGITUDE));
        assertFalse(GpsSignalRoleResolver.hasRoute(roles));
    }

    @Test
    void vehicleSpeedIsNotTreatedAsGpsSpeed() {
        Map<GpsSignalRole, SignalCatalogKey> roles = new GpsSignalRoleResolver().resolve(List.of(
                entry("VehicleSpeed", "km/h")));

        assertNull(roles.get(GpsSignalRole.GPS_SPEED));
    }

    @Test
    void explicitOverrideWinsOverDetection() {
        SignalCatalogKey manual = new SignalCatalogKey("Custom_Position_Y", SignalSourceType.MF4_NATIVE, "POS", 0);
        Map<GpsSignalRole, SignalCatalogKey> roles =
                new GpsSignalRoleResolver(Map.of(GpsSignalRole.LATITUDE, manual)).resolve(List.of(
                        entry("Latitude", "deg"),
                        entry("Longitude", "deg")));

        assertEquals(manual, roles.get(GpsSignalRole.LATITUDE));
    }

    @Test
    void prefersTheEntryWithMoreSamplesWhenScoresTie() {
        Map<GpsSignalRole, SignalCatalogKey> roles = new GpsSignalRoleResolver().resolve(List.of(
                entry("Latitude", "deg", 10),
                entry("Latitude", "deg", 5_000)));

        assertEquals("Latitude", roles.get(GpsSignalRole.LATITUDE).signalName());
    }

    @Test
    void emptyCatalogResolvesNothing() {
        assertTrue(new GpsSignalRoleResolver().resolve(List.of()).isEmpty());
        assertFalse(GpsSignalRoleResolver.hasRoute(Map.of()));
    }

    @Test
    void resolvesCommonAliasesRegardlessOfSeparatorsAndCase() {
        for (String latitude : List.of(
                "GPS_Latitude", "gps_lat", "GNSS_LAT", "NavLatitude", "VehicleLatitude",
                "GPS_LATITUDE", "gps_latitude", "Gps Latitude", "gps-latitude", "GPSLatitude")) {
            Map<GpsSignalRole, SignalCatalogKey> roles = new GpsSignalRoleResolver().resolve(List.of(
                    entry(latitude, "deg"),
                    entry("GPS_Longitude", "deg")));
            assertEquals(latitude, roles.get(GpsSignalRole.LATITUDE).signalName(), latitude);
        }
        for (String longitude : List.of("GPS_Lon", "gnss_longitude", "NavLongitude", "VehicleLongitude", "position_lng")) {
            Map<GpsSignalRole, SignalCatalogKey> roles = new GpsSignalRoleResolver().resolve(List.of(
                    entry("GPS_Latitude", "deg"),
                    entry(longitude, "deg")));
            assertEquals(longitude, roles.get(GpsSignalRole.LONGITUDE).signalName(), longitude);
        }
        Map<GpsSignalRole, SignalCatalogKey> altitude = new GpsSignalRoleResolver().resolve(List.of(
                entry("Latitude", "deg"),
                entry("Longitude", "deg"),
                entry("GNSS_HEIGHT", "m")));
        assertEquals("GNSS_HEIGHT", altitude.get(GpsSignalRole.ALTITUDE).signalName());
    }

    @Test
    void unknownNamesAreNotMappedAutomatically() {
        Map<GpsSignalRole, SignalCatalogKey> roles = new GpsSignalRoleResolver().resolve(List.of(
                entry("Pos01", ""),
                entry("Pos02", ""),
                entry("Pos03", "")));
        assertNull(roles.get(GpsSignalRole.LATITUDE));
        assertNull(roles.get(GpsSignalRole.LONGITUDE));
        assertNull(roles.get(GpsSignalRole.ALTITUDE));
    }

    @Test
    void strongMetadataScoresHigh() {
        SignalCatalogEntry lat = new SignalCatalogEntry(
                new SignalCatalogKey("GPS_Latitude", SignalSourceType.MF4_NATIVE, "GPS", 0),
                "deg", 200, 0L, 1_000L, 18.0, 19.0, "drive.mf4", "");
        assertTrue(GpsSignalRoleResolver.scoreRole(GpsSignalRole.LATITUDE, lat) >= 70);
        assertEquals(GpsDetectionConfidence.HIGH, GpsSemanticResolver.confidenceForScore(
                GpsSignalRoleResolver.scoreRole(GpsSignalRole.LATITUDE, lat)));
    }

    @Test
    void rangeAloneDoesNotClassifyAnUnknownSignal() {
        SignalCatalogEntry rpm = new SignalCatalogEntry(
                new SignalCatalogKey("EngineRPM", SignalSourceType.MF4_NATIVE, "ENG", 0),
                "rpm", 200, 0L, 1_000L, 12.0, 80.0, "drive.mf4", "");
        assertEquals(0, GpsSignalRoleResolver.scoreRole(GpsSignalRole.LATITUDE, rpm));
    }
}
