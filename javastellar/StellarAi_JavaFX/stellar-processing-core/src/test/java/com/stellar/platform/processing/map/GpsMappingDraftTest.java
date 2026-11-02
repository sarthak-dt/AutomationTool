package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editing rules behind the Configuration GPS &amp; Map screen. Nothing here touches JavaFX or a
 * measurement file: the draft works from an already-processed catalog.
 */
class GpsMappingDraftTest {

    private static final String SOURCE = "drive.mf4";

    private static SignalCatalogKey key(String name) {
        return new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "GPS", 0, 0, SOURCE, "");
    }

    private static SignalCatalogEntry entry(String name, Double min, Double max) {
        return new SignalCatalogEntry(key(name), "", 100, 0L, 1L, min, max, SOURCE, "");
    }

    /** Names the semantic resolver recognises, so detection has something to find. */
    private static List<SignalCatalogEntry> gpsCatalog() {
        return List.of(
                entry("Vehicle_Position_Latitude", 51.0, 51.2),
                entry("Vehicle_Position_Longitude", -0.2, 0.1),
                entry("Vehicle_Position_Altitude", 20.0, 140.0),
                entry("GPS_Speed", 0.0, 130.0),
                entry("GPS_Course", 0.0, 359.9),
                entry("EngineRPM", 700.0, 6200.0),
                entry("Pos01", 51.0, 51.2),
                entry("Pos02", -0.2, 0.1));
    }

    private static GpsMappingDraft draft(List<SignalCatalogEntry> catalog, List<GpsSignalMapping> persisted) {
        return GpsMappingDraft.resolve("p1", SOURCE, catalog, persisted);
    }

    private static GpsSignalMapping mapping(
            String source, String fingerprint, GpsSignalRole role, String signal) {
        return new GpsSignalMapping("p1", source, fingerprint, role, key(signal), true, Instant.now());
    }

    @Test
    void emptyCatalogIsAnInformationalStateRatherThanAnError() {
        GpsMappingDraft draft = draft(List.of(), List.of());
        assertEquals(GpsMappingDraft.Status.NO_CATALOG, draft.status());
        assertFalse(draft.hasRoute());
        assertFalse(draft.saveable());
        assertTrue(draft.toMappings(Instant.now()).isEmpty());
        assertTrue(draft.suggestions(GpsSignalRole.LATITUDE).isEmpty());
    }

    @Test
    void openingConfigurationPrePopulatesDetectedCoordinates() {
        GpsMappingDraft draft = draft(gpsCatalog(), List.of());
        assertEquals("Vehicle_Position_Latitude", draft.assigned(GpsSignalRole.LATITUDE).signalName());
        assertEquals("Vehicle_Position_Longitude", draft.assigned(GpsSignalRole.LONGITUDE).signalName());
        assertEquals(GpsMappingDraft.Status.AUTO_DETECTED, draft.status());
        assertEquals(GpsMappingDraft.RoleOrigin.AUTO_DETECTED, draft.roleOrigin(GpsSignalRole.LATITUDE));
        assertTrue(draft.hasRoute());
        assertFalse(draft.userEdited());
    }

    @Test
    void reliableAutoDetectionIsNotFrozenIntoProjectData() {
        GpsMappingDraft draft = draft(gpsCatalog(), List.of());
        // Saving pure detection must remove any override rather than writing detection as if the
        // engineer had confirmed it.
        assertTrue(draft.saveable());
        assertTrue(draft.toMappings(Instant.now()).isEmpty());
    }

    @Test
    void catalogWithoutCoordinatesAsksTheUserToMap() {
        GpsMappingDraft draft = draft(List.of(entry("EngineRPM", 700.0, 6200.0)), List.of());
        assertEquals(GpsMappingDraft.Status.MAPPING_REQUIRED, draft.status());
        assertFalse(draft.hasRoute());
        assertTrue(draft.statusDetail().contains("Latitude and Longitude"));
    }

    @Test
    void manualSelectionOverridesDetectionAndIsWhatGetsSaved() {
        GpsMappingDraft draft = draft(gpsCatalog(), List.of());
        assertEquals(GpsSignalValidator.Severity.OK,
                draft.assign(GpsSignalRole.LATITUDE, key("Pos01")).severity());
        assertEquals(GpsSignalValidator.Severity.OK,
                draft.assign(GpsSignalRole.LONGITUDE, key("Pos02")).severity());
        assertEquals("Pos01", draft.assigned(GpsSignalRole.LATITUDE).signalName());
        assertEquals(GpsMappingDraft.RoleOrigin.MANUAL, draft.roleOrigin(GpsSignalRole.LATITUDE));
        assertEquals(GpsMappingDraft.Status.USER_CONFIGURED, draft.status());

        // What the engineer sees is what gets saved: the two overrides plus the detected optional
        // roles that are still displayed in the editor.
        List<GpsSignalMapping> saved = draft.toMappings(Instant.now());
        assertTrue(saved.stream().allMatch(GpsSignalMapping::userConfirmed));
        assertTrue(saved.stream().anyMatch(m -> m.role() == GpsSignalRole.LATITUDE
                && m.key().signalName().equals("Pos01")));
        assertTrue(saved.stream().anyMatch(m -> m.role() == GpsSignalRole.LONGITUDE
                && m.key().signalName().equals("Pos02")));
        assertTrue(saved.stream().noneMatch(m -> m.key().signalName().equals("Vehicle_Position_Latitude")));
    }

    @Test
    void optionalRolesAreSavedWhenMappedAndDroppedWhenCleared() {
        GpsMappingDraft draft = draft(gpsCatalog(), List.of());
        draft.assign(GpsSignalRole.LATITUDE, key("Pos01"));
        draft.assign(GpsSignalRole.LONGITUDE, key("Pos02"));
        draft.assign(GpsSignalRole.ALTITUDE, key("Vehicle_Position_Altitude"));
        draft.assign(GpsSignalRole.GPS_SPEED, key("GPS_Speed"));
        draft.assign(GpsSignalRole.COURSE, key("GPS_Course"));
        assertEquals(5, draft.toMappings(Instant.now()).size());
        assertEquals(GpsMappingDraft.RoleOrigin.MANUAL, draft.roleOrigin(GpsSignalRole.GPS_SPEED));

        draft.clear(GpsSignalRole.GPS_SPEED);
        assertNull(draft.assigned(GpsSignalRole.GPS_SPEED));
        assertEquals(GpsMappingDraft.RoleOrigin.NOT_MAPPED, draft.roleOrigin(GpsSignalRole.GPS_SPEED));
        assertEquals(4, draft.toMappings(Instant.now()).size());
        // Optional roles are optional: dropping one must not invalidate the route.
        assertTrue(draft.hasRoute());
    }

    @Test
    void clearingARequiredRoleBlocksSavingButDoesNotThrow() {
        GpsMappingDraft draft = draft(gpsCatalog(), List.of());
        draft.assign(GpsSignalRole.LATITUDE, key("Pos01"));
        draft.clear(GpsSignalRole.LONGITUDE);
        assertFalse(draft.hasRoute());
        assertFalse(draft.saveable());
        assertEquals(GpsMappingDraft.Status.MAPPING_REQUIRED, draft.status());
        assertTrue(draft.toMappings(Instant.now()).isEmpty());
    }

    @Test
    void suspiciousRangeIsReportedButStillApplied() {
        List<SignalCatalogEntry> catalog = List.of(
                entry("Vehicle_Position_Latitude", 51.0, 51.2),
                entry("Vehicle_Position_Longitude", -0.2, 0.1),
                entry("EngineRPM", 700.0, 6200.0));
        GpsMappingDraft draft = draft(catalog, List.of());
        GpsSignalValidator.Result result = draft.assign(GpsSignalRole.LATITUDE, key("EngineRPM"));
        assertEquals(GpsSignalValidator.Severity.SUSPICIOUS, result.severity());
        assertFalse(result.blocking());
        assertTrue(result.message().contains("Latitude"));
        // Unusual engineering data must not be silently rejected.
        assertEquals("EngineRPM", draft.assigned(GpsSignalRole.LATITUDE).signalName());
        assertEquals(GpsSignalValidator.Severity.SUSPICIOUS, draft.advisory(GpsSignalRole.LATITUDE).severity());
    }

    @Test
    void aSignalOutsideTheCatalogIsRefusedRatherThanStored() {
        GpsMappingDraft draft = draft(gpsCatalog(), List.of());
        GpsSignalValidator.Result result = draft.assign(GpsSignalRole.LATITUDE, key("DoesNotExist"));
        assertTrue(result.blocking());
        assertEquals("Vehicle_Position_Latitude", draft.assigned(GpsSignalRole.LATITUDE).signalName());
    }

    @Test
    void onlyUserMappableRolesCanBeAssigned() {
        GpsMappingDraft draft = draft(gpsCatalog(), List.of());
        assertThrows(IllegalArgumentException.class,
                () -> draft.assign(GpsSignalRole.SATELLITE_COUNT, key("Pos01")));
    }

    @Test
    void persistedMappingIsRestoredAsUserConfigured() {
        List<SignalCatalogEntry> catalog = gpsCatalog();
        String fingerprint = GpsSemanticResolver.schemaFingerprint(catalog);
        GpsMappingDraft draft = draft(catalog, List.of(
                mapping(SOURCE, fingerprint, GpsSignalRole.LATITUDE, "Pos01"),
                mapping(SOURCE, fingerprint, GpsSignalRole.LONGITUDE, "Pos02")));
        assertEquals(GpsMappingDraft.Status.USER_CONFIGURED, draft.status());
        assertEquals("Pos01", draft.assigned(GpsSignalRole.LATITUDE).signalName());
        assertEquals(GpsMappingDraft.RoleOrigin.RESTORED, draft.roleOrigin(GpsSignalRole.LATITUDE));
        // Reloading and re-saving must be a no-op, not a rewrite into detection.
        assertEquals(2, draft.toMappings(Instant.now()).size());
    }

    @Test
    void compatibleSchemaMappingIsReportedAsReused() {
        List<SignalCatalogEntry> catalog = gpsCatalog();
        String fingerprint = GpsSemanticResolver.schemaFingerprint(catalog);
        GpsMappingDraft draft = draft(catalog, List.of(
                mapping("other-drive.mf4", fingerprint, GpsSignalRole.LATITUDE, "Pos01"),
                mapping("other-drive.mf4", fingerprint, GpsSignalRole.LONGITUDE, "Pos02")));
        assertEquals(GpsMappingDraft.Status.SCHEMA_REUSED, draft.status());
        assertEquals("Pos01", draft.assigned(GpsSignalRole.LATITUDE).signalName());
    }

    @Test
    void persistedMappingWhoseSignalDisappearedIsNotClaimedValid() {
        List<SignalCatalogEntry> catalog = List.of(entry("EngineRPM", 700.0, 6200.0));
        String fingerprint = GpsSemanticResolver.schemaFingerprint(catalog);
        GpsMappingDraft draft = draft(catalog, List.of(
                mapping(SOURCE, fingerprint, GpsSignalRole.LATITUDE, "Pos01"),
                mapping(SOURCE, fingerprint, GpsSignalRole.LONGITUDE, "Pos02")));
        assertEquals(GpsMappingDraft.Status.MAPPING_SIGNAL_MISSING, draft.status());
        assertFalse(draft.statusDetail().isBlank());
        assertFalse(draft.hasRoute());
    }

    @Test
    void resetToAutoDiscardsTheStoredOverride() {
        List<SignalCatalogEntry> catalog = gpsCatalog();
        String fingerprint = GpsSemanticResolver.schemaFingerprint(catalog);
        GpsMappingDraft draft = draft(catalog, List.of(
                mapping(SOURCE, fingerprint, GpsSignalRole.LATITUDE, "Pos01"),
                mapping(SOURCE, fingerprint, GpsSignalRole.LONGITUDE, "Pos02")));
        assertEquals("Pos01", draft.assigned(GpsSignalRole.LATITUDE).signalName());

        draft.resetToAuto();
        assertEquals("Vehicle_Position_Latitude", draft.assigned(GpsSignalRole.LATITUDE).signalName());
        assertEquals(GpsMappingDraft.Status.AUTO_DETECTED, draft.status());
        // An empty row set is the instruction to delete the source's stored mapping.
        assertTrue(draft.toMappings(Instant.now()).isEmpty());
        assertTrue(draft.saveable());
    }

    @Test
    void autoDetectDiscardsManualEditsAndCanFreezeDetection() {
        GpsMappingDraft draft = draft(gpsCatalog(), List.of());
        draft.assign(GpsSignalRole.LATITUDE, key("Pos01"));
        assertTrue(draft.userEdited());

        draft.autoDetect();
        assertEquals("Vehicle_Position_Latitude", draft.assigned(GpsSignalRole.LATITUDE).signalName());
        assertFalse(draft.userEdited());
        assertEquals(GpsMappingDraft.Status.AUTO_DETECTED, draft.status());
        // Auto Detect differs from Reset to Auto: an explicit Save afterwards keeps the detection.
        assertFalse(draft.toMappings(Instant.now()).isEmpty());
    }

    @Test
    void suggestionsAreOfferedWithoutReorderingTheCatalog() {
        GpsMappingDraft draft = draft(gpsCatalog(), List.of());
        List<GpsSignalCandidate> suggestions = draft.suggestions(GpsSignalRole.LATITUDE);
        assertFalse(suggestions.isEmpty());
        assertEquals("Vehicle_Position_Latitude", suggestions.getFirst().key().signalName());
        assertEquals("Vehicle_Position_Latitude", draft.catalog().getFirst().key().signalName());
        assertEquals(gpsCatalog().size(), draft.catalog().size());
        assertNotNull(draft.entry(key("EngineRPM")));
        assertNull(draft.entry(key("Missing")));
    }

    @Test
    void schemaFingerprintIsDerivedFromTheCatalogNotTheSourceName() {
        List<SignalCatalogEntry> catalog = gpsCatalog();
        assertEquals(GpsSemanticResolver.schemaFingerprint(catalog),
                draft(catalog, List.of()).schemaFingerprint());
        assertEquals(SOURCE, draft(catalog, List.of()).sourceId());
        assertEquals("p1", draft(catalog, List.of()).projectId());
    }

    @Test
    void aTypedSignalNameIsStoredEvenWhenThisMeasurementDoesNotContainIt() {
        GpsMappingDraft draft = draft(gpsCatalog(), List.of());
        GpsSignalValidator.Result result = draft.assignManual(GpsSignalRole.LATITUDE, "Fleet_Lat");
        assertEquals(GpsSignalValidator.Severity.SUSPICIOUS, result.severity());
        assertTrue(draft.manualNameUnresolved(GpsSignalRole.LATITUDE));
        assertEquals("Fleet_Lat", draft.manualName(GpsSignalRole.LATITUDE));
        draft.assignManual(GpsSignalRole.LONGITUDE, "Pos02");
        assertTrue(draft.requiredRolesConfigured());
        List<GpsSignalMapping> saved = draft.toMappings(Instant.now());
        assertEquals(2, saved.size());
        assertTrue(saved.stream().anyMatch(GpsSignalMapping::manual));
    }

    @Test
    void productDefaultIsInheritedUntilTheProjectSavesItsOwnMapping() {
        List<SignalCatalogEntry> catalog = gpsCatalog();
        GpsSignalMapping lat = GpsSignalMapping.manual("", "", "", GpsSignalRole.LATITUDE,
                "Vehicle_Position_Latitude", RouteMappingScope.DEFAULT, Instant.now());
        GpsSignalMapping lon = GpsSignalMapping.manual("", "", "", GpsSignalRole.LONGITUDE,
                "Vehicle_Position_Longitude", RouteMappingScope.DEFAULT, Instant.now());
        GpsMappingDraft draft = GpsMappingDraft.resolve("p1", SOURCE, catalog, List.of(), List.of(lat, lon));
        assertEquals(GpsMappingDraft.Status.PRODUCT_DEFAULT, draft.status());
        assertTrue(draft.inheritedFromDefault());
        assertEquals(RouteMappingScope.DEFAULT, draft.resolvedScope());
        assertTrue(draft.toMappings(Instant.now()).isEmpty(),
                "merely inheriting a default must not freeze it as a project mapping");
    }

    @Test
    void saveIsDisabledUntilBothRequiredRolesNameASignal() {
        GpsMappingDraft draft = draft(gpsCatalog(), List.of());
        draft.clear(GpsSignalRole.LATITUDE);
        draft.clear(GpsSignalRole.LONGITUDE);
        assertFalse(draft.requiredRolesConfigured());
        draft.assign(GpsSignalRole.LATITUDE, key("Pos01"));
        assertFalse(draft.requiredRolesConfigured());
        draft.assign(GpsSignalRole.LONGITUDE, key("Pos02"));
        assertTrue(draft.requiredRolesConfigured());
    }
}
