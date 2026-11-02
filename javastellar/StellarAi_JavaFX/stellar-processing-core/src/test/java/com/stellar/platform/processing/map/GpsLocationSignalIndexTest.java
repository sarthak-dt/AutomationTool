package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpsLocationSignalIndexTest {

    private static final int GPS_GROUP = 9;
    private static final int ENGINE_GROUP = 2;

    /** The ten channels of the verified drive recording, in file order. */
    private static List<SignalCatalogEntry> verifiedGpsRecording() {
        return List.of(
                gps("LATITUDE", "deg"),
                gps("LONGITUDE", "deg"),
                gps("ALTITUDE", "m"),
                gps("SPEED_OVER_GROUND", "km/h"),
                gps("COURSE_OVER_GROUND", "deg"),
                gps("GROUND_DISTANCE", "m"),
                gps("NUMBER_SATELLITES", ""),
                gps("QUALITY", ""),
                gps("HORIZONTAL_ACCURACY", "m"),
                gps("VERTICAL_ACCURACY", "m"));
    }

    @Test
    void everyChannelOfTheVerifiedRecordingIsClassifiedAsLocation() {
        GpsLocationSignalIndex index = GpsLocationSignalIndex.build(verifiedGpsRecording());

        for (SignalCatalogEntry entry : verifiedGpsRecording()) {
            assertTrue(index.isLocationSignal(entry), entry.signalName() + " must be a location signal");
        }
        assertEquals(10, index.size());
        assertTrue(index.hasRoute());
    }

    @Test
    void rolesAreAssignedWithoutRenamingTheSourceSignal() {
        GpsLocationSignalIndex index = GpsLocationSignalIndex.build(verifiedGpsRecording());

        assertEquals(Optional.of(GpsSignalRole.LATITUDE), index.roleOf(gps("LATITUDE", "deg")));
        assertEquals(Optional.of(GpsSignalRole.LONGITUDE), index.roleOf(gps("LONGITUDE", "deg")));
        assertEquals(Optional.of(GpsSignalRole.ALTITUDE), index.roleOf(gps("ALTITUDE", "m")));
        assertEquals(Optional.of(GpsSignalRole.GPS_SPEED), index.roleOf(gps("SPEED_OVER_GROUND", "km/h")));
        assertEquals(Optional.of(GpsSignalRole.COURSE), index.roleOf(gps("COURSE_OVER_GROUND", "deg")));
        assertEquals(Optional.of(GpsSignalRole.DISTANCE), index.roleOf(gps("GROUND_DISTANCE", "m")));
        assertEquals(Optional.of(GpsSignalRole.SATELLITE_COUNT), index.roleOf(gps("NUMBER_SATELLITES", "")));
        assertEquals(Optional.of(GpsSignalRole.HORIZONTAL_ACCURACY), index.roleOf(gps("HORIZONTAL_ACCURACY", "m")));
        assertEquals(Optional.of(GpsSignalRole.VERTICAL_ACCURACY), index.roleOf(gps("VERTICAL_ACCURACY", "m")));

        // The original name is untouched; the role is metadata beside it.
        assertEquals("SPEED_OVER_GROUND", gps("SPEED_OVER_GROUND", "km/h").signalName());
    }

    @Test
    void aBareQualityChannelIsRecognisedThroughItsChannelGroupRatherThanItsName() {
        assertTrue(GpsSignalRoleResolver.roleOf(gps("QUALITY", "")).isEmpty(),
                "no name rule may safely claim a bare QUALITY channel");

        GpsLocationSignalIndex index = GpsLocationSignalIndex.build(verifiedGpsRecording());
        assertTrue(index.isLocationSignal(gps("QUALITY", "")));
        assertTrue(index.roleOf(gps("QUALITY", "")).isEmpty(), "cohort membership does not invent a role");
    }

    @Test
    void namingVariantsAndCasingAreAccepted() {
        List<SignalCatalogEntry> catalog = List.of(
                gps("GPS_Lat", "deg"), gps("gps_lon", "deg"), gps("Gps_Altitude", "m"));
        GpsLocationSignalIndex index = GpsLocationSignalIndex.build(catalog);

        assertEquals(Optional.of(GpsSignalRole.LATITUDE), index.roleOf(gps("GPS_Lat", "deg")));
        assertEquals(Optional.of(GpsSignalRole.LONGITUDE), index.roleOf(gps("gps_lon", "deg")));
        assertEquals(Optional.of(GpsSignalRole.ALTITUDE), index.roleOf(gps("Gps_Altitude", "m")));
        assertTrue(index.hasRoute());
    }

    @Test
    void aRecordingWithoutGpsProducesAnEmptyIndex() {
        GpsLocationSignalIndex index = GpsLocationSignalIndex.build(List.of(
                engine("EngineRPM", "rpm"), engine("CoolantTemp", "degC")));

        assertTrue(index.isEmpty());
        assertFalse(index.hasRoute());
        assertFalse(index.isLocationSignal(engine("EngineRPM", "rpm")));
    }

    @Test
    void unrelatedSignalsSharingAGroupWithGpsAreNotSweptIn() {
        List<SignalCatalogEntry> mixed = new ArrayList<>(List.of(
                gps("LATITUDE", "deg"), gps("LONGITUDE", "deg")));
        for (int i = 0; i < 8; i++) {
            mixed.add(gps("BodyControlFlag" + i, ""));
        }

        GpsLocationSignalIndex index = GpsLocationSignalIndex.build(mixed);

        assertTrue(index.isLocationSignal(gps("LATITUDE", "deg")));
        assertFalse(index.isLocationSignal(gps("BodyControlFlag0", "")),
                "a group dominated by unrelated channels must not vouch for them");
        assertEquals(2, index.size());
    }

    @Test
    void genericVehicleSignalsAreNeverClaimedAsGpsRoles() {
        assertTrue(GpsSignalRoleResolver.roleOf(engine("VehicleSpeed", "km/h")).isEmpty());
        assertTrue(GpsSignalRoleResolver.roleOf(engine("WheelSpeed_FL", "km/h")).isEmpty());
        assertTrue(GpsSignalRoleResolver.roleOf(engine("BrakeDistance", "m")).isEmpty());
        assertTrue(GpsSignalRoleResolver.roleOf(engine("Latch_State", "")).isEmpty());
        assertTrue(GpsSignalRoleResolver.roleOf(engine("ThrottlePosition", "%")).isEmpty());
    }

    @Test
    void twoRecordingsOfTheSameChannelsStayDistinctAndAreNotDeduplicated() {
        List<SignalCatalogEntry> catalog = List.of(
                entry("LATITUDE", "deg", SignalSourceType.MF4_NATIVE, GPS_GROUP, "morning.mf4"),
                entry("LONGITUDE", "deg", SignalSourceType.MF4_NATIVE, GPS_GROUP, "morning.mf4"),
                entry("LATITUDE", "deg", SignalSourceType.MF4_NATIVE, GPS_GROUP, "evening.mf4"),
                entry("LONGITUDE", "deg", SignalSourceType.MF4_NATIVE, GPS_GROUP, "evening.mf4"));

        GpsLocationSignalIndex index = GpsLocationSignalIndex.build(catalog);

        assertEquals(4, index.size(), "each physical series keeps its own identity");
        for (SignalCatalogEntry entry : catalog) {
            assertTrue(index.isLocationSignal(entry));
        }
    }

    @Test
    void decodedCanGpsSignalsAreClassifiedWithoutBeingNative() {
        List<SignalCatalogEntry> catalog = List.of(
                new SignalCatalogEntry(
                        new SignalCatalogKey("Latitude", SignalSourceType.DBC_CAN, "GPS_Position",
                                0x321L, 1, "bus.blf", "veh.dbc"),
                        "deg", 100L, 0L, 1L, null, null, "bus.blf", "veh.dbc"),
                new SignalCatalogEntry(
                        new SignalCatalogKey("Longitude", SignalSourceType.DBC_CAN, "GPS_Position",
                                0x321L, 1, "bus.blf", "veh.dbc"),
                        "deg", 100L, 0L, 1L, null, null, "bus.blf", "veh.dbc"));

        GpsLocationSignalIndex index = GpsLocationSignalIndex.build(catalog);

        assertTrue(index.hasRoute());
        assertTrue(index.isLocationSignal(catalog.get(0)));
    }

    /**
     * Guards the shape of the work rather than one wall-clock reading: classification must stay
     * metadata-only, so 20k signals cannot cost anything close to a second.
     *
     * <p>Timing the very first build measured class loading and interpreted bytecode, not the
     * algorithm, which is why it used to fail on a cold JVM. Warming up first and judging the median
     * of repeated samples keeps the same budget while removing the noise the budget never intended
     * to cover.
     */
    @Test
    void buildingTheIndexOverALargeCatalogIsMetadataOnlyWork() {
        List<SignalCatalogEntry> catalog = new ArrayList<>(20_010);
        catalog.addAll(verifiedGpsRecording());
        for (int i = 0; i < 20_000; i++) {
            catalog.add(engine("Signal_" + i, "u"));
        }

        for (int warmup = 0; warmup < 3; warmup++) {
            assertEquals(10, GpsLocationSignalIndex.build(catalog).size());
        }

        long[] samples = new long[5];
        for (int i = 0; i < samples.length; i++) {
            long startNanos = System.nanoTime();
            GpsLocationSignalIndex index = GpsLocationSignalIndex.build(catalog);
            samples[i] = (System.nanoTime() - startNanos) / 1_000_000L;
            assertEquals(10, index.size());
        }
        java.util.Arrays.sort(samples);
        long medianMillis = samples[samples.length / 2];

        assertTrue(medianMillis < 1_000L, "median index build took " + medianMillis
                + " ms for 20k signals, samples=" + java.util.Arrays.toString(samples));
    }

    private static SignalCatalogEntry gps(String name, String unit) {
        return entry(name, unit, SignalSourceType.MF4_NATIVE, GPS_GROUP, "drive.mf4");
    }

    private static SignalCatalogEntry engine(String name, String unit) {
        return entry(name, unit, SignalSourceType.MF4_NATIVE, ENGINE_GROUP, "drive.mf4");
    }

    private static SignalCatalogEntry entry(
            String name, String unit, SignalSourceType sourceType, int channel, String sourceFile) {
        return new SignalCatalogEntry(
                new SignalCatalogKey(name, sourceType, "MF4_MEASUREMENT", 0L, channel, sourceFile, "veh.dbc"),
                unit, 6288L, 1_000_000_000L, 600_000_000_000L, null, null, sourceFile, "veh.dbc");
    }
}
