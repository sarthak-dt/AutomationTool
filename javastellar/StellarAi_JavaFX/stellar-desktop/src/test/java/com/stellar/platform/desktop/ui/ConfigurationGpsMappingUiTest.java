package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.processing.catalog.CompositeSignalCatalogStore;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.map.GpsMappingDraft;
import com.stellar.platform.processing.map.GpsMappingScope;
import com.stellar.platform.processing.map.GpsSignalMapping;
import com.stellar.platform.processing.map.GpsSignalRole;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.ScrollPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GPS &amp; Map section of Configuration is the canonical GPS mapping editor: it must resolve
 * from the already-processed catalog, persist into the existing project metadata, and behave
 * sensibly when no project is open.
 */
class ConfigurationGpsMappingUiTest {

    private static final String SOURCE = "run.mf4";

    @TempDir Path tempDir;
    private DesktopAppService service;
    private SqliteProjectMetadataStore store;

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private DesktopAppService openService() {
        System.setProperty("stellarai.home", tempDir.resolve("home").toString());
        Path db = tempDir.resolve("meta.db");
        System.setProperty("stellarai.metadata.db", db.toString());
        store = new SqliteProjectMetadataStore(db);
        service = new DesktopAppService(new DesktopSession(), store);
        return service;
    }

    /** Processed signals only: Configuration must never need the measurement file itself. */
    private static InMemorySignalCatalogStore processedGpsCatalog() {
        InMemorySignalCatalogStore catalog = new InMemorySignalCatalogStore(SOURCE, "veh.dbc", 1_000);
        List<CanonicalSignal> signals = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long t = 1_000L * (i + 1);
            signals.add(signal("Vehicle_Position_Latitude", t, 51.0 + i * 0.01, "deg"));
            signals.add(signal("Vehicle_Position_Longitude", t, -0.1 + i * 0.01, "deg"));
            signals.add(signal("Vehicle_Position_Altitude", t, 40.0 + i, "m"));
            signals.add(signal("GPS_Speed", t, 30.0 + i, "km/h"));
            signals.add(signal("GPS_Course", t, 90.0 + i, "deg"));
            signals.add(signal("EngineRPM", t, 2000.0 + i * 10, "rpm"));
            signals.add(signal("Pos01", t, 51.0 + i * 0.01, "deg"));
            signals.add(signal("Pos02", t, -0.1 + i * 0.01, "deg"));
        }
        catalog.accept(signals);
        return catalog;
    }

    private static CanonicalSignal signal(String name, long timestamp, double value, String unit) {
        return new CanonicalSignal(name, "GPS", timestamp, 0x100, 1, value, (long) value, unit, null,
                SourceReference.ofLine(1));
    }

    @Test
    @Timeout(60)
    void withoutAProjectTheSectionExplainsItselfInsteadOfPretendingToWork() throws Exception {
        DesktopAppService svc = openService();
        ConfigurationController controller = load(svc);
        onFx(controller::refresh);

        assertFalse(valueOf(controller::gpsEditorShownForTest),
                "an empty picker must not pretend configuration is possible");
        assertNull(valueOf(controller::gpsDraftForTest));
        assertEquals(GpsMappingDraft.Status.NO_CATALOG.label(), valueOf(controller::gpsStatusBadgeForTest));
        // Analysis Storage is reachable without a project and must be unaffected.
        assertTrue(valueOf(() -> controller.analysisStorageShownForTest()));
        assertTrue(valueOf(controller::mapSettingsCardShownForTest));
        assertTrue(valueOf(controller::mapTestSourceShownForTest),
                "Test Map Source must work without an open project");
        List<String> sources = valueOf(controller::mapSourceLabelsForTest);
        assertTrue(sources.contains("Auto"));
        assertTrue(sources.contains("Self-hosted English"));
        assertFalse(sources.stream().anyMatch(label -> label.contains("OSM_CARTO")));
        assertFalse(sources.stream().anyMatch(label -> label.equals("OSM Carto")));
    }

    @Test
    @Timeout(60)
    void openingTheSectionProposesDetectedCoordinatesForTheProcessedSource() throws Exception {
        DesktopAppService svc = openService();
        svc.newProject("GPS Config");
        svc.signalsModel().replaceCatalog(processedGpsCatalog());

        ConfigurationController controller = load(svc);
        onFx(controller::refresh);

        assertTrue(valueOf(controller::gpsEditorShownForTest));
        assertEquals(List.of(SOURCE), valueOf(controller::gpsSourcesForTest));
        GpsMappingDraft draft = valueOf(controller::gpsDraftForTest);
        assertNotNull(draft);
        assertEquals("Vehicle_Position_Latitude", draft.assigned(GpsSignalRole.LATITUDE).signalName());
        assertEquals("Vehicle_Position_Longitude", draft.assigned(GpsSignalRole.LONGITUDE).signalName());
        assertEquals(GpsMappingDraft.Status.AUTO_DETECTED.label(), valueOf(controller::gpsStatusBadgeForTest));
        assertEquals(GpsMappingDraft.RoleOrigin.AUTO_DETECTED.label(),
                valueOf(() -> controller.gpsRoleStatusForTest(GpsSignalRole.LATITUDE)));
        // Reliable detection must not force the user to configure anything.
        assertTrue(svc.loadAllGpsSignalMappings().isEmpty());
    }

    @Test
    @Timeout(60)
    void detectedGpsShowsACompactSummaryRatherThanFiveDropdowns() throws Exception {
        DesktopAppService svc = openService();
        svc.newProject("GPS Summary");
        svc.signalsModel().replaceCatalog(processedGpsCatalog());
        ConfigurationController controller = load(svc);
        onFx(controller::refresh);

        assertTrue(valueOf(controller::gpsSummaryShownForTest));
        assertFalse(valueOf(controller::gpsPickersShownForTest),
                "signal pickers are for the minority who need them");
        assertFalse(valueOf(controller::gpsSetupRequiredShownForTest));
        assertEquals("Vehicle_Position_Latitude", valueOf(controller::gpsSummaryLatitudeForTest));

        onFx(controller::changeGpsMappingForTest);

        assertTrue(valueOf(controller::gpsPickersShownForTest));
        assertFalse(valueOf(controller::gpsSummaryShownForTest));
        for (GpsSignalRole optional : GpsMappingDraft.OPTIONAL_ROLES) {
            assertTrue(valueOf(() -> controller.gpsOptionalRoleShownForTest(optional)),
                    optional.displayName() + " must be listed with the coordinate roles");
        }
    }

    @Test
    @Timeout(60)
    void cancellingAChangeReturnsToTheSummaryAndKeepsTheStoredMapping() throws Exception {
        DesktopAppService svc = openService();
        svc.newProject("GPS Cancel");
        svc.signalsModel().replaceCatalog(processedGpsCatalog());
        ConfigurationController controller = load(svc);
        onFx(controller::refresh);

        onFx(controller::changeGpsMappingForTest);
        onFx(() -> controller.pickGpsRoleForTest(GpsSignalRole.LATITUDE, "Pos01"));
        assertEquals("Pos01", valueOf(controller::gpsDraftForTest).assigned(GpsSignalRole.LATITUDE).signalName());

        onFx(controller::cancelGpsEditingForTest);

        assertTrue(valueOf(controller::gpsSummaryShownForTest));
        assertEquals("Vehicle_Position_Latitude",
                valueOf(controller::gpsDraftForTest).assigned(GpsSignalRole.LATITUDE).signalName());
        assertTrue(svc.loadAllGpsSignalMappings().isEmpty(), "an abandoned edit must persist nothing");
    }

    @Test
    @Timeout(60)
    void anUndetectableCatalogAsksToConfigureInsteadOfShowingAForm() throws Exception {
        DesktopAppService svc = openService();
        svc.newProject("GPS Undetected");
        InMemorySignalCatalogStore noGps = new InMemorySignalCatalogStore(SOURCE, "veh.dbc", 1_000);
        noGps.accept(List.of(
                signal("EngineRPM", 1_000L, 2_000.0, "rpm"),
                signal("Pos01", 1_000L, 51.0, "deg"),
                signal("Pos02", 1_000L, -0.1, "deg")));
        svc.signalsModel().replaceCatalog(noGps);
        ConfigurationController controller = load(svc);
        onFx(controller::refresh);

        assertTrue(valueOf(controller::gpsSetupRequiredShownForTest));
        assertFalse(valueOf(controller::gpsPickersShownForTest));
        assertEquals(GpsMappingDraft.Status.MAPPING_REQUIRED.label(),
                valueOf(controller::gpsStatusBadgeForTest));

        // Map Route's "Configure GPS" action lands the user straight on the role list.
        onFx(controller::focusGpsMappingSection);
        assertTrue(valueOf(controller::gpsPickersShownForTest));
    }

    @Test
    @Timeout(60)
    void manualCoordinatesAndOptionalRolesArePersistedWithTheProject() throws Exception {
        DesktopAppService svc = openService();
        svc.newProject("GPS Manual");
        svc.signalsModel().replaceCatalog(processedGpsCatalog());
        ConfigurationController controller = load(svc);
        onFx(controller::refresh);

        onFx(() -> {
            controller.changeGpsMappingForTest();
            controller.pickGpsRoleForTest(GpsSignalRole.LATITUDE, "Pos01");
            controller.pickGpsRoleForTest(GpsSignalRole.LONGITUDE, "Pos02");
            controller.pickGpsRoleForTest(GpsSignalRole.ALTITUDE, "Vehicle_Position_Altitude");
            controller.pickGpsRoleForTest(GpsSignalRole.GPS_SPEED, "GPS_Speed");
            controller.pickGpsRoleForTest(GpsSignalRole.COURSE, "GPS_Course");
        });
        assertEquals(GpsMappingDraft.Status.USER_CONFIGURED.label(), valueOf(controller::gpsStatusBadgeForTest));

        int revisionBefore = svc.gpsMappingRevision();
        onFx(controller::saveGpsMappingForTest);

        List<GpsSignalMapping> saved = svc.loadAllGpsSignalMappings();
        assertEquals(5, saved.size());
        assertEquals("Pos01", roleSignal(saved, GpsSignalRole.LATITUDE));
        assertEquals("Pos02", roleSignal(saved, GpsSignalRole.LONGITUDE));
        assertEquals("GPS_Speed", roleSignal(saved, GpsSignalRole.GPS_SPEED));
        assertEquals("GPS_Course", roleSignal(saved, GpsSignalRole.COURSE));
        assertTrue(saved.stream().allMatch(m -> SOURCE.equals(m.sourceId())));
        assertTrue(svc.gpsMappingRevision() > revisionBefore,
                "Map Route needs to know the mapping changed");
        // The semantic role is metadata: the original signal names are untouched.
        assertTrue(svc.signalsModel().mapRouteCatalog().stream()
                .anyMatch(e -> e.key().signalName().equals("Pos01")));
    }

    @Test
    @Timeout(60)
    void reopeningTheProjectRestoresTheSavedMapping() throws Exception {
        DesktopAppService svc = openService();
        svc.newProject("GPS Reload");
        svc.signalsModel().replaceCatalog(processedGpsCatalog());
        String projectId = svc.currentProjectId();

        ConfigurationController first = load(svc);
        onFx(first::refresh);
        onFx(() -> {
            first.changeGpsMappingForTest();
            first.pickGpsRoleForTest(GpsSignalRole.LATITUDE, "Pos01");
            first.pickGpsRoleForTest(GpsSignalRole.LONGITUDE, "Pos02");
            first.saveGpsMappingForTest();
        });

        svc.closeProject();
        assertTrue(svc.openSavedProject(projectId).opened());
        svc.signalsModel().replaceCatalog(processedGpsCatalog());

        ConfigurationController reopened = load(svc);
        onFx(reopened::refresh);
        GpsMappingDraft draft = valueOf(reopened::gpsDraftForTest);
        assertNotNull(draft);
        assertEquals("Pos01", draft.assigned(GpsSignalRole.LATITUDE).signalName());
        assertEquals("Pos02", draft.assigned(GpsSignalRole.LONGITUDE).signalName());
        assertEquals(GpsMappingDraft.Status.USER_CONFIGURED.label(),
                valueOf(reopened::gpsStatusBadgeForTest));
    }

    @Test
    @Timeout(60)
    void useAutomaticDetectionRemovesTheStoredMapping() throws Exception {
        DesktopAppService svc = openService();
        svc.newProject("GPS Reset");
        svc.signalsModel().replaceCatalog(processedGpsCatalog());
        ConfigurationController controller = load(svc);
        onFx(controller::refresh);
        onFx(() -> {
            controller.changeGpsMappingForTest();
            controller.pickGpsRoleForTest(GpsSignalRole.LATITUDE, "Pos01");
            controller.pickGpsRoleForTest(GpsSignalRole.LONGITUDE, "Pos02");
            controller.saveGpsMappingForTest();
        });
        assertFalse(svc.loadAllGpsSignalMappings().isEmpty());

        onFx(controller::useAutomaticGpsDetectionForTest);
        assertTrue(svc.loadAllGpsSignalMappings().isEmpty());
        GpsMappingDraft draft = valueOf(controller::gpsDraftForTest);
        assertEquals("Vehicle_Position_Latitude", draft.assigned(GpsSignalRole.LATITUDE).signalName());
        assertEquals(GpsMappingDraft.Status.AUTO_DETECTED.label(), valueOf(controller::gpsStatusBadgeForTest));
        assertTrue(valueOf(controller::gpsSummaryShownForTest),
                "handing control back to detection returns the section to its compact state");
    }

    @Test
    @Timeout(60)
    void aSavedMappingSurvivesTheChangeMappingRoundTripAndOnlyTheChosenRolesArePersisted() throws Exception {
        DesktopAppService svc = openService();
        svc.newProject("GPS Roles");
        svc.signalsModel().replaceCatalog(processedGpsCatalog());
        ConfigurationController controller = load(svc);
        onFx(controller::refresh);

        onFx(() -> {
            controller.changeGpsMappingForTest();
            controller.pickGpsRoleForTest(GpsSignalRole.LATITUDE, "Pos01");
            controller.pickGpsRoleForTest(GpsSignalRole.LONGITUDE, "Pos02");
            controller.saveGpsMappingForTest();
        });

        List<GpsSignalMapping> saved = svc.loadAllGpsSignalMappings();
        assertEquals(2, saved.size(),
                "auto-detected optional roles must not be frozen into the project as user choices");
        assertEquals("Pos01", roleSignal(saved, GpsSignalRole.LATITUDE));
        assertTrue(saved.stream().allMatch(GpsSignalMapping::userConfirmed));
    }

    @Test
    @Timeout(60)
    void aSuspiciousCoordinateIsFlaggedInlineButStillApplied() throws Exception {
        DesktopAppService svc = openService();
        svc.newProject("GPS Suspicious");
        svc.signalsModel().replaceCatalog(processedGpsCatalog());
        ConfigurationController controller = load(svc);
        onFx(controller::refresh);

        onFx(() -> {
            controller.changeGpsMappingForTest();
            controller.pickGpsRoleForTest(GpsSignalRole.LATITUDE, "EngineRPM");
        });

        GpsMappingDraft draft = valueOf(controller::gpsDraftForTest);
        assertEquals("EngineRPM", draft.assigned(GpsSignalRole.LATITUDE).signalName());
        assertTrue(valueOf(() -> controller.gpsRoleStatusForTest(GpsSignalRole.LATITUDE)).contains("Latitude"),
                "the advisory must stay visible next to the role");
        assertTrue(valueOf(controller::gpsMessageForTest).contains("Latitude"));
        assertTrue(valueOf(() -> controller.gpsRoleStatusWarnedForTest(GpsSignalRole.LATITUDE)));
    }

    @Test
    @Timeout(60)
    void anOptionalRoleCanBeClearedAgain() throws Exception {
        DesktopAppService svc = openService();
        svc.newProject("GPS Clear");
        svc.signalsModel().replaceCatalog(processedGpsCatalog());
        ConfigurationController controller = load(svc);
        onFx(controller::refresh);
        assertNotNull(valueOf(controller::gpsDraftForTest).assigned(GpsSignalRole.GPS_SPEED));

        onFx(() -> {
            controller.changeGpsMappingForTest();
            controller.clearGpsRoleForTest(GpsSignalRole.GPS_SPEED);
        });
        assertNull(valueOf(controller::gpsDraftForTest).assigned(GpsSignalRole.GPS_SPEED));
        assertEquals(GpsMappingDraft.RoleOrigin.NOT_MAPPED.label(),
                valueOf(() -> controller.gpsRoleStatusForTest(GpsSignalRole.GPS_SPEED)));
        // Clearing an optional role must not invalidate the route.
        assertTrue(valueOf(controller::gpsDraftForTest).hasRoute());
    }

    @Test
    @Timeout(60)
    void aMappingWhoseSignalDisappearedIsNotClaimedValid() throws Exception {
        DesktopAppService svc = openService();
        svc.newProject("GPS Missing");
        svc.signalsModel().replaceCatalog(processedGpsCatalog());
        ConfigurationController controller = load(svc);
        onFx(controller::refresh);
        onFx(() -> {
            controller.changeGpsMappingForTest();
            controller.pickGpsRoleForTest(GpsSignalRole.LATITUDE, "Pos01");
            controller.pickGpsRoleForTest(GpsSignalRole.LONGITUDE, "Pos02");
            controller.saveGpsMappingForTest();
        });

        // A later processing run no longer produces the mapped signals.
        InMemorySignalCatalogStore reduced = new InMemorySignalCatalogStore(SOURCE, "veh.dbc", 1_000);
        reduced.accept(List.of(signal("EngineRPM", 1_000L, 2000.0, "rpm")));
        svc.signalsModel().replaceCatalog(reduced);

        ConfigurationController reopened = load(svc);
        onFx(reopened::refresh);
        assertEquals(GpsMappingDraft.Status.MAPPING_SIGNAL_MISSING.label(),
                valueOf(reopened::gpsStatusBadgeForTest));
        assertFalse(valueOf(reopened::gpsMessageForTest).isBlank());
    }

    @Test
    @Timeout(60)
    void theSectionIsScrolledIntoViewWhenMapRouteAsksForIt() throws Exception {
        DesktopAppService svc = openService();
        svc.newProject("GPS Focus");
        svc.signalsModel().replaceCatalog(processedGpsCatalog());
        AtomicReference<ScrollPane> rootRef = new AtomicReference<>();
        ConfigurationController controller = load(svc, rootRef);
        onFx(controller::refresh);

        onFx(() -> {
            ScrollPane scroll = rootRef.get();
            scroll.setPrefSize(700, 320);
            new javafx.scene.Scene(scroll, 700, 320);
            scroll.applyCss();
            scroll.layout();
            controller.focusGpsMappingSection();
        });
        // The scroll position is applied on the next pulse.
        onFx(() -> { });
        assertTrue(valueOf(() -> rootRef.get().getVvalue()) > 0.0,
                "the GPS card must be brought into view");
        assertTrue(valueOf(controller::gpsEditorShownForTest));
    }

    @Test
    @Timeout(60)
    void switchingMeasurementIsRefusedWhileAnEditIsUnsaved() throws Exception {
        DesktopAppService svc = openService();
        svc.newProject("GPS Two Sources");
        svc.signalsModel().replaceCatalog(twoSourceCatalog());
        ConfigurationController controller = load(svc);
        onFx(controller::refresh);
        assertTrue(valueOf(controller::gpsSourcesForTest).containsAll(List.of(SOURCE, SECOND_SOURCE)),
                "every measurement in the project must be configurable");

        onFx(() -> controller.selectGpsSourceForTest(SOURCE));
        onFx(() -> {
            controller.changeGpsMappingForTest();
            controller.pickGpsRoleForTest(GpsSignalRole.LATITUDE, "Pos01");
        });
        assertTrue(valueOf(controller::gpsSourceSelectorDisabledForTest),
                "the selector locks rather than silently discarding the edit");

        onFx(() -> controller.selectGpsSourceForTest(SECOND_SOURCE));

        assertEquals(SOURCE, valueOf(controller::gpsDraftForTest).sourceId());
        assertEquals("Pos01", valueOf(controller::gpsDraftForTest).assigned(GpsSignalRole.LATITUDE).signalName());
        assertTrue(valueOf(controller::gpsMessageForTest).contains("Save or discard"));

        // Discarding releases the lock and the other measurement becomes reachable again.
        onFx(controller::cancelGpsEditingForTest);
        assertFalse(valueOf(controller::gpsSourceSelectorDisabledForTest));
        onFx(() -> controller.selectGpsSourceForTest(SECOND_SOURCE));
        assertEquals(SECOND_SOURCE, valueOf(controller::gpsDraftForTest).sourceId());
    }

    @Test
    @Timeout(60)
    void eachMeasurementKeepsItsOwnMappingAndReturningRestoresIt() throws Exception {
        DesktopAppService svc = openService();
        svc.newProject("GPS Per Source");
        svc.signalsModel().replaceCatalog(twoSourceCatalog());
        ConfigurationController controller = load(svc);
        onFx(controller::refresh);

        onFx(() -> controller.selectGpsSourceForTest(SOURCE));
        onFx(() -> {
            controller.changeGpsMappingForTest();
            controller.pickGpsRoleForTest(GpsSignalRole.LATITUDE, "Pos01");
            controller.pickGpsRoleForTest(GpsSignalRole.LONGITUDE, "Pos02");
            controller.saveGpsMappingForTest();
        });
        onFx(() -> controller.selectGpsSourceForTest(SECOND_SOURCE));
        onFx(() -> {
            controller.changeGpsMappingForTest();
            controller.pickGpsRoleForTest(GpsSignalRole.LATITUDE, "Alt01");
            controller.pickGpsRoleForTest(GpsSignalRole.LONGITUDE, "Alt02");
            controller.saveGpsMappingForTest();
        });

        List<GpsSignalMapping> saved = svc.loadAllGpsSignalMappings();
        assertEquals("Pos01", saved.stream()
                .filter(m -> GpsMappingScope.mappingBelongsToSource(m.sourceId(), "source-A", SOURCE)
                        && m.role() == GpsSignalRole.LATITUDE)
                .map(m -> m.key().signalName()).findFirst().orElse(""));
        assertEquals("Alt01", saved.stream()
                .filter(m -> GpsMappingScope.mappingBelongsToSource(m.sourceId(), "source-B", SECOND_SOURCE)
                        && m.role() == GpsSignalRole.LATITUDE)
                .map(m -> m.key().signalName()).findFirst().orElse(""));

        onFx(() -> controller.selectGpsSourceForTest(SOURCE));
        assertEquals("Pos01", valueOf(controller::gpsDraftForTest).assigned(GpsSignalRole.LATITUDE).signalName());
        onFx(() -> controller.selectGpsSourceForTest(SECOND_SOURCE));
        assertEquals("Alt01", valueOf(controller::gpsDraftForTest).assigned(GpsSignalRole.LATITUDE).signalName());
    }

    @Test
    @Timeout(60)
    void aReusedMappingIsNotPretendedToBeDeletedFromTheBorrowingMeasurement() throws Exception {
        DesktopAppService svc = openService();
        svc.newProject("GPS Reuse");
        svc.signalsModel().replaceCatalog(identicalSchemaCatalog());
        ConfigurationController controller = load(svc);
        onFx(controller::refresh);

        onFx(() -> controller.selectGpsSourceForTest(SOURCE));
        onFx(() -> {
            controller.changeGpsMappingForTest();
            controller.pickGpsRoleForTest(GpsSignalRole.LATITUDE, "Pos01");
            controller.pickGpsRoleForTest(GpsSignalRole.LONGITUDE, "Pos02");
            controller.saveGpsMappingForTest();
        });
        onFx(() -> controller.selectGpsSourceForTest(SECOND_SOURCE));
        assertEquals(GpsMappingDraft.Status.SCHEMA_REUSED.label(), valueOf(controller::gpsStatusBadgeForTest));
        assertEquals("Pos01", valueOf(controller::gpsDraftForTest).assigned(GpsSignalRole.LATITUDE).signalName());

        onFx(controller::useAutomaticGpsDetectionForTest);

        // The rows belong to the first measurement, so deleting nothing here and claiming success
        // would revert on reopen. The user is taken to where the mapping actually lives.
        assertFalse(svc.loadAllGpsSignalMappings().isEmpty(), "a borrowed mapping is not ours to delete");
        assertEquals(SOURCE, valueOf(controller::gpsDraftForTest).sourceId());
        assertTrue(valueOf(controller::gpsMessageForTest).contains("reused"),
                valueOf(controller::gpsMessageForTest));
    }

    @Test
    @Timeout(60)
    void oneMappingCoveringSeveralIdenticalRecordingsIsStatedPlainly() throws Exception {
        DesktopAppService svc = openService();
        svc.newProject("GPS Compatible Count");
        svc.signalsModel().replaceCatalog(identicalSchemaCatalog());
        ConfigurationController controller = load(svc);
        onFx(controller::refresh);

        String note = valueOf(controller::gpsSourceNoteForTest);
        assertTrue(note.contains("2") && note.toLowerCase().contains("compatible"), note);
    }

    @Test
    @Timeout(60)
    void aManuallyTypedSignalNameIsPersistedAndRestored() throws Exception {
        DesktopAppService svc = openService();
        svc.newProject("GPS Manual Mapping");
        svc.signalsModel().replaceCatalog(processedGpsCatalog());
        ConfigurationController controller = load(svc);
        onFx(controller::refresh);
        onFx(controller::changeGpsMappingForTest);
        onFx(() -> controller.typeGpsRoleForTest(GpsSignalRole.LATITUDE, "Pos01"));
        onFx(() -> controller.typeGpsRoleForTest(GpsSignalRole.LONGITUDE, "Pos02"));
        assertFalse(valueOf(controller::gpsSaveDisabledForTest));
        onFx(controller::saveGpsMappingForTest);

        List<GpsSignalMapping> saved = svc.loadAllGpsSignalMappings();
        assertTrue(saved.stream().allMatch(GpsSignalMapping::manual), saved.toString());
        assertEquals("Pos01", saved.stream()
                .filter(m -> m.role() == GpsSignalRole.LATITUDE)
                .findFirst().orElseThrow()
                .requestedSignalName());

        ConfigurationController reopened = load(svc);
        onFx(reopened::refresh);
        assertEquals("Pos01", valueOf(reopened::gpsDraftForTest).manualName(GpsSignalRole.LATITUDE));
        assertEquals("Project configuration", valueOf(reopened::gpsScopeBadgeForTest));
    }

    @Test
    @Timeout(60)
    void aProductDefaultIsInheritedUntilTheProjectOverridesIt() throws Exception {
        DesktopAppService svc = openService();
        svc.newProject("GPS Product Default");
        svc.signalsModel().replaceCatalog(processedGpsCatalog());
        ConfigurationController controller = load(svc);
        onFx(controller::refresh);
        onFx(controller::changeGpsMappingForTest);
        onFx(() -> controller.typeGpsRoleForTest(GpsSignalRole.LATITUDE, "Pos01"));
        onFx(() -> controller.typeGpsRoleForTest(GpsSignalRole.LONGITUDE, "Pos02"));
        onFx(controller::saveGpsAsDefaultForTest);

        assertTrue(svc.loadAllGpsSignalMappings().isEmpty(), "a product default is not a project row");
        assertEquals(GpsMappingDraft.Status.PRODUCT_DEFAULT.label(), valueOf(controller::gpsStatusBadgeForTest));
        assertEquals("Product default", valueOf(controller::gpsScopeBadgeForTest));

        onFx(controller::useProjectOverrideForTest);
        onFx(controller::saveGpsMappingForTest);
        assertFalse(svc.loadAllGpsSignalMappings().isEmpty());
        assertEquals("Project configuration", valueOf(controller::gpsScopeBadgeForTest));

        onFx(controller::revertGpsToDefaultForTest);
        assertTrue(svc.loadAllGpsSignalMappings().isEmpty());
        assertEquals(GpsMappingDraft.Status.PRODUCT_DEFAULT.label(), valueOf(controller::gpsStatusBadgeForTest));
    }

    @Test
    @Timeout(60)
    void saveStaysDisabledUntilBothCoordinatesAreNamed() throws Exception {
        DesktopAppService svc = openService();
        svc.newProject("GPS Required Roles");
        svc.signalsModel().replaceCatalog(processedGpsCatalog());
        ConfigurationController controller = load(svc);
        onFx(controller::refresh);
        onFx(controller::changeGpsMappingForTest);
        onFx(() -> controller.clearGpsRoleForTest(GpsSignalRole.LATITUDE));
        onFx(() -> controller.clearGpsRoleForTest(GpsSignalRole.LONGITUDE));
        assertTrue(valueOf(controller::gpsSaveDisabledForTest));
        onFx(() -> controller.pickGpsRoleForTest(GpsSignalRole.LATITUDE, "Pos01"));
        assertTrue(valueOf(controller::gpsSaveDisabledForTest));
        onFx(() -> controller.pickGpsRoleForTest(GpsSignalRole.LONGITUDE, "Pos02"));
        assertFalse(valueOf(controller::gpsSaveDisabledForTest));
    }

    // ------------------------------------------------------------------

    private static final String SECOND_SOURCE = "run2.mf4";

    /** Two measurements whose schemas differ, so neither can reuse the other's mapping. */
    private static CompositeSignalCatalogStore twoSourceCatalog() {
        return composite(measurement(SOURCE, "Pos01", "Pos02"), measurement(SECOND_SOURCE, "Alt01", "Alt02"));
    }

    /** Two measurements with the same signal names, which is what makes reuse legitimate. */
    private static CompositeSignalCatalogStore identicalSchemaCatalog() {
        return composite(measurement(SOURCE, "Pos01", "Pos02"), measurement(SECOND_SOURCE, "Pos01", "Pos02"));
    }

    private static CompositeSignalCatalogStore composite(
            InMemorySignalCatalogStore first, InMemorySignalCatalogStore second) {
        return CompositeSignalCatalogStore.fromMembers(List.of(
                new CompositeSignalCatalogStore.Member(first, "source-A", "def-A", SOURCE, "veh.dbc"),
                new CompositeSignalCatalogStore.Member(second, "source-B", "def-A", SECOND_SOURCE, "veh.dbc")),
                "Project", "veh.dbc");
    }

    private static InMemorySignalCatalogStore measurement(String file, String latName, String lonName) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(file, "veh.dbc", 1_000);
        List<CanonicalSignal> signals = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long t = 1_000L * (i + 1);
            signals.add(signal(latName, t, 51.0 + i * 0.01, "deg"));
            signals.add(signal(lonName, t, -0.1 + i * 0.01, "deg"));
            signals.add(signal("EngineRPM", t, 2_000.0 + i * 10, "rpm"));
        }
        store.accept(signals);
        return store;
    }

    private static String roleSignal(List<GpsSignalMapping> mappings, GpsSignalRole role) {
        return mappings.stream()
                .filter(m -> m.role() == role)
                .map(m -> m.key().signalName())
                .findFirst()
                .orElse("");
    }

    private ConfigurationController load(DesktopAppService svc) throws Exception {
        return load(svc, new AtomicReference<>());
    }

    private ConfigurationController load(DesktopAppService svc, AtomicReference<ScrollPane> rootRef)
            throws Exception {
        AtomicReference<ConfigurationController> ref = new AtomicReference<>();
        onFx(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                        ConfigurationController.class.getResource("/fxml/configuration-view.fxml"));
                ScrollPane root = loader.load();
                rootRef.set(root);
                ConfigurationController controller = loader.getController();
                assertNotNull(controller, "configuration-view.fxml must instantiate its controller");
                controller.setAppService(svc, null);
                ref.set(controller);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        return ref.get();
    }

    private static void onFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "FX action timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static <T> T valueOf(Supplier<T> supplier) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        onFx(() -> out.set(supplier.get()));
        return out.get();
    }
}
