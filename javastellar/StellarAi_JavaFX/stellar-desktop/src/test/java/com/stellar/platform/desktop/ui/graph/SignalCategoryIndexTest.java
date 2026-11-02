package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalCategoryIndexTest {

    private static final int GPS_GROUP = 9;
    private static final int POWERTRAIN_GROUP = 2;

    private static List<SignalCatalogEntry> gpsRecording() {
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
    void allTenGpsChannelsShareTheGpsLocationGroup() {
        SignalCategoryIndex index = SignalCategoryIndex.build(gpsRecording());

        for (SignalCatalogEntry entry : gpsRecording()) {
            assertEquals("GPS / Location", index.categoryOf(entry), entry.signalName());
        }
    }

    @Test
    void speedOverGroundIsNotScatteredIntoVehicleDynamics() {
        SignalCategoryIndex index = SignalCategoryIndex.build(gpsRecording());

        assertEquals("GPS / Location", index.categoryOf(gps("SPEED_OVER_GROUND", "km/h")));
    }

    @Test
    void gpsLocationIsListedFirst() {
        assertEquals(0, SignalCategoryIndex.rank("GPS / Location"));
        assertTrue(SignalCategoryIndex.rank("Powertrain") > SignalCategoryIndex.rank("GPS / Location"));
        assertEquals(9, SignalCategoryIndex.rank("Other"));
    }

    @Test
    void ordinaryVehicleSignalsKeepTheirExistingCategories() {
        List<SignalCatalogEntry> catalog = new ArrayList<>(gpsRecording());
        catalog.add(powertrain("EngineRPM", "rpm"));
        catalog.add(powertrain("VehicleSpeed", "km/h"));
        catalog.add(powertrain("ThrottlePosition", "%"));
        catalog.add(powertrain("CoolantTemp", "degC"));
        catalog.add(powertrain("BrakePressure", "bar"));
        SignalCategoryIndex index = SignalCategoryIndex.build(catalog);

        assertEquals("Powertrain", index.categoryOf(powertrain("EngineRPM", "rpm")));
        assertEquals("Vehicle dynamics", index.categoryOf(powertrain("VehicleSpeed", "km/h")));
        assertEquals("Powertrain", index.categoryOf(powertrain("ThrottlePosition", "%")));
        assertEquals("Thermal", index.categoryOf(powertrain("CoolantTemp", "degC")));
        assertEquals("Braking / stability", index.categoryOf(powertrain("BrakePressure", "bar")));
    }

    @Test
    void aMeasurementWithoutGpsHasNoLocationGroup() {
        SignalCategoryIndex index = SignalCategoryIndex.build(List.of(
                powertrain("EngineRPM", "rpm"), powertrain("VehicleSpeed", "km/h")));

        assertNotEquals("GPS / Location", index.categoryOf(powertrain("EngineRPM", "rpm")));
        assertTrue(index.locations().isEmpty());
    }

    @Test
    void anEmptyCatalogFallsBackToTheSharedEmptyIndex() {
        assertEquals(SignalCategoryIndex.EMPTY, SignalCategoryIndex.build(List.of()));
        assertEquals("Other", SignalCategoryIndex.EMPTY.categoryOf(null));
    }

    @Test
    void neverSeenTurboBoostPressureClassifiesWithoutAnExactNameTable() {
        SignalCatalogEntry turbo = powertrain("TurboBoostPressure", "kPa");
        SignalCategoryIndex index = SignalCategoryIndex.build(List.of(turbo));
        SignalCategoryIndex.Classification hit = index.classify(turbo);
        assertEquals("Powertrain", hit.group());
        assertEquals("semantic-token", hit.reason());
        assertTrue(hit.confidence() > 0);
    }

    @Test
    void oemSpecificNameStaysVisibleAndMayUseTokens() {
        SignalCatalogEntry oem = powertrain("OEM_XYZ_Propulsion_TorqueReq", "Nm");
        SignalCategoryIndex index = SignalCategoryIndex.build(List.of(oem));
        assertEquals("Powertrain", index.categoryOf(oem));
    }

    @Test
    void unknownNameWithNoMetadataFallsBackToOther() {
        SignalCatalogEntry unknown = entry("ZZZ_ABC_123", "", 3);
        SignalCategoryIndex index = SignalCategoryIndex.build(List.of(unknown));
        SignalCategoryIndex.Classification hit = index.classify(unknown);
        assertEquals("Other", hit.group());
        assertEquals("fallback", hit.reason());
        assertEquals("Other", index.categoryOf(unknown));
    }

    @Test
    void messageContextClassifiesWhenTheSignalNameIsUnfamiliar() {
        SignalCatalogEntry byMessage = new SignalCatalogEntry(
                new SignalCatalogKey("QQQ_UNK_42", SignalSourceType.DBC_CAN, "EngineController",
                        0x200L, 1, "run.mf4", "oem.dbc"),
                "", 10L, 1L, 2L, null, null, "run.mf4", "oem.dbc");
        SignalCategoryIndex index = SignalCategoryIndex.build(List.of(byMessage));
        assertEquals("Powertrain", index.categoryOf(byMessage));
    }

    @Test
    void nativeMf4UnknownSignalIsNotHidden() {
        SignalCatalogEntry nativeUnknown = new SignalCatalogEntry(
                new SignalCatalogKey("Ch12_RawBlob", SignalSourceType.MF4_NATIVE, "MF4_MEASUREMENT",
                        0L, 12, "drive.mf4", ""),
                "", 4L, 1L, 2L, null, null, "drive.mf4", "");
        SignalCategoryIndex index = SignalCategoryIndex.build(List.of(nativeUnknown));
        assertEquals("Other", index.categoryOf(nativeUnknown));
    }

    @Test
    void groupingDoesNotChangeCatalogIdentity() {
        SignalCatalogEntry rpm = powertrain("EngineRPM", "rpm");
        SignalCatalogKey before = rpm.key();
        SignalCategoryIndex.build(List.of(rpm)).categoryOf(rpm);
        assertEquals(before, rpm.key());
        assertEquals("EngineRPM", rpm.signalName());
    }

    private static SignalCatalogEntry gps(String name, String unit) {
        return entry(name, unit, GPS_GROUP);
    }

    private static SignalCatalogEntry powertrain(String name, String unit) {
        return entry(name, unit, POWERTRAIN_GROUP);
    }

    private static SignalCatalogEntry entry(String name, String unit, int channel) {
        return new SignalCatalogEntry(
                new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "MF4_MEASUREMENT", 0L, channel,
                        "drive.mf4", "veh.dbc"),
                unit, 6288L, 1_000_000_000L, 600_000_000_000L, null, null, "drive.mf4", "veh.dbc");
    }
}
