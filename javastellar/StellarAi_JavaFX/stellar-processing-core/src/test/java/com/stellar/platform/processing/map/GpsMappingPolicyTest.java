package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpsMappingPolicyTest {

    private static SignalCatalogKey key(String name, String file) {
        return new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "GPS", 0, 0, file, "");
    }

    private static SignalCatalogEntry entry(String name, String file) {
        return new SignalCatalogEntry(key(name, file), "", 10, 0L, 1L, null, null, file, "");
    }

    private static GpsSignalMapping mapping(String source, String fingerprint, GpsSignalRole role, String name, String file) {
        return new GpsSignalMapping("p1", source, fingerprint, role, key(name, file), true, Instant.now());
    }

    @Test
    void userConfirmedMappingOverridesAutoDetection() {
        List<SignalCatalogEntry> catalog = List.of(entry("Latitude", "a.mf4"), entry("Pos01", "a.mf4"), entry("Pos02", "a.mf4"));
        String fingerprint = GpsSemanticResolver.schemaFingerprint(catalog);
        GpsMappingPolicy.Resolution resolution = GpsMappingPolicy.resolve("a.mf4", fingerprint, catalog, List.of(
                mapping("a.mf4", fingerprint, GpsSignalRole.LATITUDE, "Pos01", "a.mf4"),
                mapping("a.mf4", fingerprint, GpsSignalRole.LONGITUDE, "Pos02", "a.mf4")));
        assertEquals(GpsMappingPolicy.Origin.USER_CONFIRMED, resolution.origin());
        assertEquals("Pos01", resolution.overrides().get(GpsSignalRole.LATITUDE).signalName());
        assertFalse(resolution.persistedMissing());
    }

    @Test
    void missingPersistedSignalIsMarkedInvalidWithoutThrowing() {
        List<SignalCatalogEntry> catalog = List.of(entry("EngineRPM", "a.mf4"));
        String fingerprint = GpsSemanticResolver.schemaFingerprint(catalog);
        GpsMappingPolicy.Resolution resolution = GpsMappingPolicy.resolve("a.mf4", fingerprint, catalog, List.of(
                mapping("a.mf4", fingerprint, GpsSignalRole.LATITUDE, "Pos01", "a.mf4"),
                mapping("a.mf4", fingerprint, GpsSignalRole.LONGITUDE, "Pos02", "a.mf4")));
        assertTrue(resolution.persistedMissing());
        assertFalse(resolution.hasRouteOverrides());
        assertTrue(resolution.statusMessage().contains("no longer available"));
    }

    @Test
    void incompatibleSchemasDoNotReuseMappings() {
        List<SignalCatalogEntry> fileA = List.of(entry("Pos01", "a.mf4"), entry("Pos02", "a.mf4"));
        List<SignalCatalogEntry> fileB = List.of(entry("EngineRPM", "b.mf4"), entry("VehicleSpeed", "b.mf4"));
        String fpA = GpsSemanticResolver.schemaFingerprint(fileA);
        GpsMappingPolicy.Resolution resolution = GpsMappingPolicy.resolve("b.mf4",
                GpsSemanticResolver.schemaFingerprint(fileB), fileB, List.of(
                        mapping("a.mf4", fpA, GpsSignalRole.LATITUDE, "Pos01", "a.mf4"),
                        mapping("a.mf4", fpA, GpsSignalRole.LONGITUDE, "Pos02", "a.mf4")));
        assertEquals(GpsMappingPolicy.Origin.UNRESOLVED, resolution.origin());
        assertFalse(resolution.hasRouteOverrides());
    }

    @Test
    void compatibleSchemaCanReuseUserMapping() {
        List<SignalCatalogEntry> fileA = List.of(entry("Pos01", "a.mf4"), entry("Pos02", "a.mf4"));
        List<SignalCatalogEntry> fileB = List.of(entry("Pos01", "b.mf4"), entry("Pos02", "b.mf4"));
        String fingerprint = GpsSemanticResolver.schemaFingerprint(fileA);
        assertEquals(fingerprint, GpsSemanticResolver.schemaFingerprint(fileB));
        GpsMappingPolicy.Resolution resolution = GpsMappingPolicy.resolve("b.mf4", fingerprint, fileB, List.of(
                mapping("a.mf4", fingerprint, GpsSignalRole.LATITUDE, "Pos01", "a.mf4"),
                mapping("a.mf4", fingerprint, GpsSignalRole.LONGITUDE, "Pos02", "a.mf4")));
        assertEquals(GpsMappingPolicy.Origin.SCHEMA_REUSED, resolution.origin());
        assertEquals("Pos01", resolution.overrides().get(GpsSignalRole.LATITUDE).signalName());
    }

    // --- per-role resolution: an optional role must never cost the user their route ---

    /** Coordinates plus the three optional roles, of which only some still exist in the catalog. */
    private static GpsMappingPolicy.Resolution resolveWithOptional(String... survivingSignals) {
        List<SignalCatalogEntry> catalog = new java.util.ArrayList<>();
        for (String name : survivingSignals) {
            catalog.add(entry(name, "a.mf4"));
        }
        String fingerprint = GpsSemanticResolver.schemaFingerprint(catalog);
        return GpsMappingPolicy.resolve("a.mf4", fingerprint, catalog, List.of(
                mapping("a.mf4", fingerprint, GpsSignalRole.LATITUDE, "Pos01", "a.mf4"),
                mapping("a.mf4", fingerprint, GpsSignalRole.LONGITUDE, "Pos02", "a.mf4"),
                mapping("a.mf4", fingerprint, GpsSignalRole.ALTITUDE, "PosAlt", "a.mf4"),
                mapping("a.mf4", fingerprint, GpsSignalRole.GPS_SPEED, "PosSpeed", "a.mf4"),
                mapping("a.mf4", fingerprint, GpsSignalRole.COURSE, "PosCourse", "a.mf4")));
    }

    private static void assertCoordinatesSurvived(GpsMappingPolicy.Resolution resolution) {
        assertEquals(GpsMappingPolicy.Origin.USER_CONFIRMED, resolution.origin());
        assertTrue(resolution.hasRouteOverrides(), "coordinates must still produce a route");
        assertFalse(resolution.persistedMissing(), "a dropped optional role is not a broken mapping");
        assertEquals("Pos01", resolution.overrides().get(GpsSignalRole.LATITUDE).signalName());
        assertEquals("Pos02", resolution.overrides().get(GpsSignalRole.LONGITUDE).signalName());
    }

    @Test
    void missingAltitudeDropsOnlyAltitude() {
        GpsMappingPolicy.Resolution resolution = resolveWithOptional("Pos01", "Pos02", "PosSpeed", "PosCourse");

        assertCoordinatesSurvived(resolution);
        assertEquals(List.of(GpsSignalRole.ALTITUDE), resolution.unavailableOptionalRoles());
        assertEquals("PosSpeed", resolution.overrides().get(GpsSignalRole.GPS_SPEED).signalName());
        assertEquals("PosCourse", resolution.overrides().get(GpsSignalRole.COURSE).signalName());
        assertFalse(resolution.overrides().containsKey(GpsSignalRole.ALTITUDE));
        assertTrue(resolution.statusMessage().contains("no longer available"), resolution.statusMessage());
    }

    @Test
    void missingSpeedDropsOnlySpeed() {
        GpsMappingPolicy.Resolution resolution = resolveWithOptional("Pos01", "Pos02", "PosAlt", "PosCourse");

        assertCoordinatesSurvived(resolution);
        assertEquals(List.of(GpsSignalRole.GPS_SPEED), resolution.unavailableOptionalRoles());
        assertFalse(resolution.overrides().containsKey(GpsSignalRole.GPS_SPEED));
    }

    @Test
    void missingCourseDropsOnlyCourseAndSaysSo() {
        GpsMappingPolicy.Resolution resolution = resolveWithOptional("Pos01", "Pos02", "PosAlt", "PosSpeed");

        assertCoordinatesSurvived(resolution);
        assertEquals(List.of(GpsSignalRole.COURSE), resolution.unavailableOptionalRoles());
        assertTrue(resolution.optionalRolesDropped());
        assertTrue(resolution.statusMessage().contains(GpsSignalRole.COURSE.displayName()),
                resolution.statusMessage());
    }

    @Test
    void severalMissingOptionalRolesAreAllReportedAndTheRouteStands() {
        GpsMappingPolicy.Resolution resolution = resolveWithOptional("Pos01", "Pos02");

        assertCoordinatesSurvived(resolution);
        assertEquals(List.of(GpsSignalRole.ALTITUDE, GpsSignalRole.GPS_SPEED, GpsSignalRole.COURSE),
                resolution.unavailableOptionalRoles());
        assertEquals(2, resolution.overrides().size(), "no optional role may be substituted");
    }

    @Test
    void missingLatitudeInvalidatesThePersistedMapping() {
        GpsMappingPolicy.Resolution resolution = resolveWithOptional("Pos02", "PosAlt", "PosSpeed", "PosCourse");

        assertTrue(resolution.persistedMissing());
        assertFalse(resolution.hasRouteOverrides());
        assertEquals(GpsMappingPolicy.Origin.UNRESOLVED, resolution.origin());
        assertTrue(resolution.statusMessage().contains("Pos01"), resolution.statusMessage());
    }

    @Test
    void missingLongitudeInvalidatesThePersistedMapping() {
        GpsMappingPolicy.Resolution resolution = resolveWithOptional("Pos01", "PosAlt", "PosSpeed", "PosCourse");

        assertTrue(resolution.persistedMissing());
        assertFalse(resolution.hasRouteOverrides());
        assertEquals(GpsMappingPolicy.Origin.UNRESOLVED, resolution.origin());
    }

    @Test
    void aDroppedOptionalRoleIsNeverFilledWithAnotherSignal() {
        // A plausible-looking alternative exists; the policy must not reach for it.
        List<SignalCatalogEntry> catalog = List.of(
                entry("Pos01", "a.mf4"), entry("Pos02", "a.mf4"), entry("GPS_Course_Backup", "a.mf4"));
        String fingerprint = GpsSemanticResolver.schemaFingerprint(catalog);

        GpsMappingPolicy.Resolution resolution = GpsMappingPolicy.resolve("a.mf4", fingerprint, catalog, List.of(
                mapping("a.mf4", fingerprint, GpsSignalRole.LATITUDE, "Pos01", "a.mf4"),
                mapping("a.mf4", fingerprint, GpsSignalRole.LONGITUDE, "Pos02", "a.mf4"),
                mapping("a.mf4", fingerprint, GpsSignalRole.COURSE, "GPS_Course", "a.mf4")));

        assertFalse(resolution.overrides().containsKey(GpsSignalRole.COURSE));
        assertEquals(List.of(GpsSignalRole.COURSE), resolution.unavailableOptionalRoles());
    }

    @Test
    void schemaReusedMappingAlsoKeepsCoordinatesWhenAnOptionalRoleIsGone() {
        List<SignalCatalogEntry> fileA = List.of(
                entry("Pos01", "a.mf4"), entry("Pos02", "a.mf4"), entry("PosAlt", "a.mf4"));
        List<SignalCatalogEntry> fileB = List.of(
                entry("Pos01", "b.mf4"), entry("Pos02", "b.mf4"), entry("PosAlt", "b.mf4"));
        String fingerprint = GpsSemanticResolver.schemaFingerprint(fileA);
        List<SignalCatalogEntry> fileBWithoutAltitude = List.of(entry("Pos01", "b.mf4"), entry("Pos02", "b.mf4"));
        assertEquals(fingerprint, GpsSemanticResolver.schemaFingerprint(fileB));

        GpsMappingPolicy.Resolution resolution = GpsMappingPolicy.resolve("b.mf4", fingerprint,
                fileBWithoutAltitude, List.of(
                        mapping("a.mf4", fingerprint, GpsSignalRole.LATITUDE, "Pos01", "a.mf4"),
                        mapping("a.mf4", fingerprint, GpsSignalRole.LONGITUDE, "Pos02", "a.mf4"),
                        mapping("a.mf4", fingerprint, GpsSignalRole.ALTITUDE, "PosAlt", "a.mf4")));

        assertEquals(GpsMappingPolicy.Origin.SCHEMA_REUSED, resolution.origin());
        assertTrue(resolution.hasRouteOverrides());
        assertEquals(List.of(GpsSignalRole.ALTITUDE), resolution.unavailableOptionalRoles());
    }

    @Test
    void productDefaultIsUsedWhenTheProjectHasNoMappingOfItsOwn() {
        List<SignalCatalogEntry> catalog = List.of(entry("Fleet_Lat", "a.mf4"), entry("Fleet_Lon", "a.mf4"));
        String fingerprint = GpsSemanticResolver.schemaFingerprint(catalog);
        GpsSignalMapping lat = GpsSignalMapping.manual("", "", "", GpsSignalRole.LATITUDE,
                "Fleet_Lat", RouteMappingScope.DEFAULT, Instant.now());
        GpsSignalMapping lon = GpsSignalMapping.manual("", "", "", GpsSignalRole.LONGITUDE,
                "Fleet_Lon", RouteMappingScope.DEFAULT, Instant.now());
        GpsMappingPolicy.Resolution resolution = GpsMappingPolicy.resolve(
                "a.mf4", fingerprint, catalog, List.of(), List.of(lat, lon));
        assertEquals(GpsMappingPolicy.Origin.PRODUCT_DEFAULT, resolution.origin());
        assertEquals("Fleet_Lat", resolution.overrides().get(GpsSignalRole.LATITUDE).signalName());
        assertTrue(resolution.hasRouteOverrides());
    }

    @Test
    void aProjectMappingWinsOverTheProductDefault() {
        List<SignalCatalogEntry> catalog = List.of(
                entry("Pos01", "a.mf4"), entry("Pos02", "a.mf4"),
                entry("Fleet_Lat", "a.mf4"), entry("Fleet_Lon", "a.mf4"));
        String fingerprint = GpsSemanticResolver.schemaFingerprint(catalog);
        GpsMappingPolicy.Resolution resolution = GpsMappingPolicy.resolve(
                "a.mf4", fingerprint, catalog,
                List.of(
                        mapping("a.mf4", fingerprint, GpsSignalRole.LATITUDE, "Pos01", "a.mf4"),
                        mapping("a.mf4", fingerprint, GpsSignalRole.LONGITUDE, "Pos02", "a.mf4")),
                List.of(
                        GpsSignalMapping.manual("", "", "", GpsSignalRole.LATITUDE,
                                "Fleet_Lat", RouteMappingScope.DEFAULT, Instant.now()),
                        GpsSignalMapping.manual("", "", "", GpsSignalRole.LONGITUDE,
                                "Fleet_Lon", RouteMappingScope.DEFAULT, Instant.now())));
        assertEquals(GpsMappingPolicy.Origin.USER_CONFIRMED, resolution.origin());
        assertEquals("Pos01", resolution.overrides().get(GpsSignalRole.LATITUDE).signalName());
    }

    @Test
    void aTypedNameBindsToTheCatalogSignalOfTheSameName() {
        List<SignalCatalogEntry> catalog = List.of(entry("GNSS_Lat", "a.mf4"), entry("GNSS_Lon", "a.mf4"));
        String fingerprint = GpsSemanticResolver.schemaFingerprint(catalog);
        GpsMappingPolicy.Resolution resolution = GpsMappingPolicy.resolve("a.mf4", fingerprint, catalog, List.of(
                GpsSignalMapping.manual("p1", "a.mf4", fingerprint, GpsSignalRole.LATITUDE,
                        "GNSS_Lat", RouteMappingScope.PROJECT, Instant.now()),
                GpsSignalMapping.manual("p1", "a.mf4", fingerprint, GpsSignalRole.LONGITUDE,
                        "gnss_lon", RouteMappingScope.PROJECT, Instant.now())));
        assertEquals(GpsMappingPolicy.Origin.USER_CONFIRMED, resolution.origin());
        assertEquals("GNSS_Lat", resolution.overrides().get(GpsSignalRole.LATITUDE).signalName());
        assertEquals("GNSS_Lon", resolution.overrides().get(GpsSignalRole.LONGITUDE).signalName());
    }
}
