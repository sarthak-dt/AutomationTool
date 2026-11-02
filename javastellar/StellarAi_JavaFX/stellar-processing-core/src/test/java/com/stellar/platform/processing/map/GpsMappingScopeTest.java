package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A GPS mapping belongs to a measurement, and both the screen that saves one and the map that
 * resolves one have to agree on which measurement that is. When they disagreed, a mapping saved for
 * the second measurement of a project silently had no effect: it was fingerprinted over that
 * measurement's signals but looked up by a fingerprint taken over the whole project.
 */
class GpsMappingScopeTest {

    private static SignalCatalogEntry entry(String name, String file) {
        SignalCatalogKey key = new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "GPS", 0, 0, file, "");
        return new SignalCatalogEntry(key, "deg", 10, 0L, 1L, null, null, file, "");
    }

    private static GpsSignalMapping mapping(String source, String fingerprint, GpsSignalRole role, String name) {
        SignalCatalogKey key = new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "GPS", 0, 0, source, "");
        return new GpsSignalMapping("p1", source, fingerprint, role, key, true, Instant.now());
    }

    /** Two measurements with deliberately different schemas and different coordinate signals. */
    private static List<SignalCatalogEntry> twoSourceProject() {
        List<SignalCatalogEntry> catalog = new ArrayList<>();
        catalog.add(entry("Lat_A", "a.mf4"));
        catalog.add(entry("Lon_A", "a.mf4"));
        catalog.add(entry("EngineRPM", "a.mf4"));
        catalog.add(entry("Lat_B", "b.mf4"));
        catalog.add(entry("Lon_B", "b.mf4"));
        catalog.add(entry("BrakePressure", "b.mf4"));
        return List.copyOf(catalog);
    }

    @Test
    void everyMeasurementInTheProjectCanBeConfigured() {
        assertEquals(List.of("a.mf4", "b.mf4"), GpsMappingScope.sourceIds(twoSourceProject()));
        assertEquals("a.mf4", GpsMappingScope.defaultSourceId(twoSourceProject()));
    }

    @Test
    void aCatalogWithoutMeasurementFileNamesIsOneMergedSource() {
        List<SignalCatalogEntry> merged = List.of(entry("Latitude", ""), entry("Longitude", ""));

        assertEquals(List.of(), GpsMappingScope.sourceIds(merged));
        assertEquals(GpsMappingScope.MERGED_SOURCE, GpsMappingScope.defaultSourceId(merged));
        assertTrue(GpsMappingScope.isMerged(GpsMappingScope.MERGED_SOURCE));
        assertEquals(2, GpsMappingScope.catalogFor(GpsMappingScope.MERGED_SOURCE, merged).size());
    }

    @Test
    void aSourceScopedCatalogHoldsOnlyThatMeasurementPlusMergedSignals() {
        List<SignalCatalogEntry> catalog = new ArrayList<>(twoSourceProject());
        catalog.add(entry("Odometer", ""));

        List<String> names = GpsMappingScope.catalogFor("b.mf4", catalog).stream()
                .map(e -> e.key().signalName()).toList();

        assertEquals(List.of("Lat_B", "Lon_B", "BrakePressure", "Odometer"), names);
    }

    @Test
    void differentMeasurementsHaveDifferentFingerprintsAndNeitherIsTheProjectFingerprint() {
        List<SignalCatalogEntry> catalog = twoSourceProject();
        String a = GpsMappingScope.fingerprintFor("a.mf4", catalog);
        String b = GpsMappingScope.fingerprintFor("b.mf4", catalog);
        String project = GpsSemanticResolver.schemaFingerprint(catalog);

        assertNotEquals(a, b);
        assertNotEquals(project, a, "a source fingerprint must never be compared against the project's");
        assertNotEquals(project, b);
    }

    @Test
    void identicalSchemasInDifferentMeasurementsShareAFingerprintSoReuseStillWorks() {
        List<SignalCatalogEntry> catalog = List.of(
                entry("Latitude", "run1.mf4"), entry("Longitude", "run1.mf4"),
                entry("Latitude", "run2.mf4"), entry("Longitude", "run2.mf4"));

        assertEquals(GpsMappingScope.fingerprintFor("run1.mf4", catalog),
                GpsMappingScope.fingerprintFor("run2.mf4", catalog));
    }

    @Test
    void theRouteResolvesTheMappingOfTheMeasurementItIsDisplaying() {
        List<SignalCatalogEntry> catalog = twoSourceProject();
        List<GpsSignalMapping> persisted = List.of(
                mapping("a.mf4", GpsMappingScope.fingerprintFor("a.mf4", catalog), GpsSignalRole.LATITUDE, "Lat_A"),
                mapping("a.mf4", GpsMappingScope.fingerprintFor("a.mf4", catalog), GpsSignalRole.LONGITUDE, "Lon_A"),
                mapping("b.mf4", GpsMappingScope.fingerprintFor("b.mf4", catalog), GpsSignalRole.LATITUDE, "Lat_B"),
                mapping("b.mf4", GpsMappingScope.fingerprintFor("b.mf4", catalog), GpsSignalRole.LONGITUDE, "Lon_B"));

        for (String source : List.of("a.mf4", "b.mf4")) {
            String resolvedSource = GpsMappingScope.resolveRouteSource(source, catalog, persisted);
            assertEquals(source, resolvedSource);

            List<SignalCatalogEntry> scoped = GpsMappingScope.catalogFor(resolvedSource, catalog);
            GpsMappingPolicy.Resolution resolution = GpsMappingPolicy.resolve(resolvedSource,
                    GpsSemanticResolver.schemaFingerprint(scoped), scoped, persisted);

            assertEquals(GpsMappingPolicy.Origin.USER_CONFIRMED, resolution.origin());
            String suffix = source.startsWith("a") ? "_A" : "_B";
            assertEquals("Lat" + suffix, resolution.overrides().get(GpsSignalRole.LATITUDE).signalName());
            assertEquals("Lon" + suffix, resolution.overrides().get(GpsSignalRole.LONGITUDE).signalName());
        }
    }

    @Test
    void aMappingSavedForTheSecondMeasurementIsFoundWhenThatMeasurementIsDisplayed() {
        // The defect: only b.mf4 was configured, and the route used the project's first source.
        List<SignalCatalogEntry> catalog = twoSourceProject();
        String fingerprintB = GpsMappingScope.fingerprintFor("b.mf4", catalog);
        List<GpsSignalMapping> persisted = List.of(
                mapping("b.mf4", fingerprintB, GpsSignalRole.LATITUDE, "Lat_B"),
                mapping("b.mf4", fingerprintB, GpsSignalRole.LONGITUDE, "Lon_B"));

        String source = GpsMappingScope.resolveRouteSource("b.mf4", catalog, persisted);
        List<SignalCatalogEntry> scoped = GpsMappingScope.catalogFor(source, catalog);
        GpsMappingPolicy.Resolution resolution = GpsMappingPolicy.resolve(source,
                GpsSemanticResolver.schemaFingerprint(scoped), scoped, persisted);

        assertEquals("b.mf4", source);
        assertEquals(fingerprintB, GpsSemanticResolver.schemaFingerprint(scoped),
                "save and resolution must fingerprint the same scope");
        assertEquals("Lat_B", resolution.overrides().get(GpsSignalRole.LATITUDE).signalName());
    }

    @Test
    void anUnconfiguredMeasurementDoesNotBorrowAnIncompatibleMeasurementsMapping() {
        List<SignalCatalogEntry> catalog = twoSourceProject();
        String fingerprintA = GpsMappingScope.fingerprintFor("a.mf4", catalog);
        List<GpsSignalMapping> persisted = List.of(
                mapping("a.mf4", fingerprintA, GpsSignalRole.LATITUDE, "Lat_A"),
                mapping("a.mf4", fingerprintA, GpsSignalRole.LONGITUDE, "Lon_A"));

        String source = GpsMappingScope.resolveRouteSource("b.mf4", catalog, persisted);
        List<SignalCatalogEntry> scoped = GpsMappingScope.catalogFor(source, catalog);
        GpsMappingPolicy.Resolution resolution = GpsMappingPolicy.resolve(source,
                GpsSemanticResolver.schemaFingerprint(scoped), scoped, persisted);

        assertEquals("b.mf4", source, "the displayed measurement must stay the mapping scope");
        assertFalse(resolution.hasRouteOverrides(),
                "a.mf4 coordinates must not be drawn while b.mf4 is displayed");
        assertEquals(GpsMappingPolicy.Origin.UNRESOLVED, resolution.origin());
    }

    @Test
    void anUnconfiguredMeasurementWithTheSameSchemaStillReusesTheMapping() {
        List<SignalCatalogEntry> catalog = List.of(
                entry("Latitude", "run1.mf4"), entry("Longitude", "run1.mf4"),
                entry("Latitude", "run2.mf4"), entry("Longitude", "run2.mf4"));
        String fingerprint = GpsMappingScope.fingerprintFor("run1.mf4", catalog);
        List<GpsSignalMapping> persisted = List.of(
                mapping("run1.mf4", fingerprint, GpsSignalRole.LATITUDE, "Latitude"),
                mapping("run1.mf4", fingerprint, GpsSignalRole.LONGITUDE, "Longitude"));

        String source = GpsMappingScope.resolveRouteSource("run2.mf4", catalog, persisted);
        List<SignalCatalogEntry> scoped = GpsMappingScope.catalogFor(source, catalog);
        GpsMappingPolicy.Resolution resolution = GpsMappingPolicy.resolve(source,
                GpsSemanticResolver.schemaFingerprint(scoped), scoped, persisted);

        assertEquals("run2.mf4", source);
        assertEquals(GpsMappingPolicy.Origin.SCHEMA_REUSED, resolution.origin());
        assertEquals("run2.mf4",
                resolution.overrides().get(GpsSignalRole.LATITUDE).sourceFile(),
                "the reused mapping must point at signals in the displayed measurement");
    }

    @Test
    void unknownOrBlankSourceFailsClosedInsteadOfBorrowingAnotherMeasurement() {
        List<SignalCatalogEntry> catalog = twoSourceProject();
        String fingerprintB = GpsMappingScope.fingerprintFor("b.mf4", catalog);
        List<GpsSignalMapping> persisted = List.of(
                mapping("b.mf4", fingerprintB, GpsSignalRole.LATITUDE, "Lat_B"),
                mapping("b.mf4", fingerprintB, GpsSignalRole.LONGITUDE, "Lon_B"));

        assertEquals("", GpsMappingScope.resolveRouteSource("", catalog, persisted));
        assertEquals("", GpsMappingScope.resolveRouteSource("missing.mf4", catalog, persisted));
        assertTrue(GpsMappingScope.catalogFor("missing.mf4", catalog).isEmpty());
        assertTrue(GpsMappingScope.catalogFor("", catalog).isEmpty());
    }

    @Test
    void resolveRouteSourcePrefersCanonicalIdentityWhenAskedForIt() {
        SignalCatalogKey key = new SignalCatalogKey(
                "Lat_B", SignalSourceType.MF4_NATIVE, "GPS", 0, 0, "src-B", "");
        SignalCatalogEntry entry = new SignalCatalogEntry(
                key, "deg", 10, 0L, 1L, null, null, "b.mf4", "");
        List<SignalCatalogEntry> catalog = List.of(entry("Lat_A", "a.mf4"), entry);

        assertEquals("src-B", GpsMappingScope.resolveRouteSource("src-B", catalog, List.of()));
        assertEquals("b.mf4", GpsMappingScope.resolveRouteSource("b.mf4", catalog, List.of()));
        assertEquals("", GpsMappingScope.resolveRouteSource("missing", catalog, List.of()));
    }

    @Test
    void aliasMappingsForLookupRewriteLegacyFilenameRowsOntoTheSelector() {
        GpsSignalMapping stored = mapping("src-A", "fp", GpsSignalRole.LATITUDE, "Lat_A");
        List<GpsSignalMapping> aliased = GpsMappingScope.aliasMappingsForLookup(
                "a.mf4", "src-A", "a.mf4", List.of(stored));
        assertEquals(1, aliased.size());
        assertEquals("a.mf4", aliased.getFirst().sourceId());
        assertEquals("Lat_A", aliased.getFirst().key().signalName());
    }

    @Test
    void catalogForMatchesStableIdentityAsWellAsDisplayName() {
        SignalCatalogKey key = new SignalCatalogKey(
                "Lat_B", SignalSourceType.MF4_NATIVE, "GPS", 0, 0, "src-B", "");
        SignalCatalogEntry entry = new SignalCatalogEntry(
                key, "deg", 10, 0L, 1L, null, null, "b.mf4", "");
        List<SignalCatalogEntry> catalog = List.of(entry("Lat_A", "a.mf4"), entry);

        List<String> byDisplay = GpsMappingScope.catalogFor("b.mf4", catalog).stream()
                .map(e -> e.key().signalName()).toList();
        List<String> byIdentity = GpsMappingScope.catalogFor("src-B", catalog).stream()
                .map(e -> e.key().signalName()).toList();

        assertEquals(List.of("Lat_B"), byDisplay);
        assertEquals(List.of("Lat_B"), byIdentity);
    }
}
