package com.stellar.platform.desktop.ui.control;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.map.GpsMappingDraft;
import com.stellar.platform.processing.map.GpsSignalRole;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guided GPS mapping modal: required-field gating, validation banners, Apply All and ESC.
 */
class GpsSignalMappingModalTest {

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

    @Test
    void missingCoordinatesDisableSaveAndExplainTheBlocker() throws Exception {
        GpsSignalMappingModal modal = modalOnFx();
        runOnFx(() -> {
            modal.setMeasurementLabel("drive.asc");
            modal.setDraft(GpsMappingDraft.resolve("p1", "drive.asc", engineOnly(), List.of()));
        });
        assertTrue(modal.requiredRolesRenderedForTest());
        assertTrue(modal.saveDisabledForTest());
        assertEquals("Route cannot be rendered", modal.bannerTitleForTest());
        assertTrue(modal.bannerDetailForTest().toLowerCase().contains("latitude"));
        assertTrue(modal.bannerDetailForTest().toLowerCase().contains("longitude"));
    }

    @Test
    void validDecimalDegreeMappingEnablesSaveAndShowsRouteReady() throws Exception {
        GpsSignalMappingModal modal = modalOnFx();
        runOnFx(() -> {
            modal.setMeasurementLabel("ASCII_eCC21_KA04NC7760_Bengaluru_09_08_2023_000236");
            modal.setDraft(GpsMappingDraft.resolve("p1", "drive.asc", bengaluruCatalog(), List.of()));
        });
        assertFalse(modal.saveDisabledForTest());
        assertEquals("Valid", modal.fieldPillForTest(GpsSignalRole.LATITUDE));
        assertEquals("Valid", modal.fieldPillForTest(GpsSignalRole.LONGITUDE));
        assertEquals("Route can be rendered", modal.bannerTitleForTest());
        assertTrue(modal.bannerDetailForTest().contains("34,817"));
        assertEquals("Degrees detected", modal.coordinateFormatForTest());
    }

    @Test
    void applyAllFillsStrongMatchesAndRevalidates() throws Exception {
        GpsSignalMappingModal modal = modalOnFx();
        AtomicBoolean applied = new AtomicBoolean();
        runOnFx(() -> {
            GpsMappingDraft draft = GpsMappingDraft.resolve("p1", "drive.asc", engineOnly(), List.of());
            modal.setDraft(draft);
            modal.setRecommendedHandler(() -> {
                draft.autoDetect();
                modal.applyDraft();
                applied.set(true);
            });
            modal.setDraft(GpsMappingDraft.resolve("p1", "drive.asc", bengaluruCatalog(), List.of()));
            modal.draft().clear(GpsSignalRole.LATITUDE);
            modal.draft().clear(GpsSignalRole.LONGITUDE);
            modal.applyDraft();
        });
        assertTrue(modal.saveDisabledForTest());
        runOnFx(() -> {
            modal.setRecommendedHandler(() -> {
                modal.draft().autoDetect();
                modal.applyDraft();
                applied.set(true);
            });
            modal.useRecommendedForTest();
        });
        assertTrue(applied.get());
        assertFalse(modal.saveDisabledForTest());
        assertEquals("Route can be rendered", modal.bannerTitleForTest());
        assertEquals("Valid", modal.fieldPillForTest(GpsSignalRole.ALTITUDE));
    }

    @Test
    void partialOptionalMappingStillSavesWhenCoordinatesArePresent() throws Exception {
        GpsSignalMappingModal modal = modalOnFx();
        runOnFx(() -> {
            GpsMappingDraft draft = GpsMappingDraft.resolve("p1", "drive.asc", bengaluruCatalog(), List.of());
            draft.clear(GpsSignalRole.GPS_SPEED);
            draft.clear(GpsSignalRole.COURSE);
            modal.setDraft(draft);
        });
        assertFalse(modal.saveDisabledForTest());
        assertEquals("Route can be rendered", modal.bannerTitleForTest());
        assertEquals("", modal.fieldPillForTest(GpsSignalRole.GPS_SPEED));
        assertEquals("", modal.fieldPillForTest(GpsSignalRole.COURSE));
    }

    @Test
    void swappedCoordinatesShowAClearWarning() throws Exception {
        GpsSignalMappingModal modal = modalOnFx();
        runOnFx(() -> {
            GpsMappingDraft draft = GpsMappingDraft.resolve("p1", "drive.asc", swappedCatalog(), List.of());
            modal.setDraft(draft);
            modal.pickRoleForTest(GpsSignalRole.LATITUDE, "GPS_LAT_INFO");
            modal.pickRoleForTest(GpsSignalRole.LONGITUDE, "GPS_LONG_INFO");
        });
        assertTrue(modal.bannerTitleForTest().contains("incorrect")
                || modal.bannerDetailForTest().toLowerCase().contains("swapped"));
        assertTrue(modal.bannerDetailForTest().toLowerCase().contains("swapped")
                || modal.fieldPillForTest(GpsSignalRole.LATITUDE).equals("Warning"));
    }

    @Test
    void saveStaysDisabledUntilBothRequiredRolesAreChosen() throws Exception {
        GpsSignalMappingModal modal = modalOnFx();
        runOnFx(() -> {
            GpsMappingDraft draft = GpsMappingDraft.resolve("p1", "drive.asc", bengaluruCatalog(), List.of());
            draft.clear(GpsSignalRole.LONGITUDE);
            modal.setDraft(draft);
        });
        assertTrue(modal.saveDisabledForTest());
        runOnFx(() -> modal.pickRoleForTest(GpsSignalRole.LONGITUDE, "GPS_LONG_INFO"));
        assertFalse(modal.saveDisabledForTest());
    }

    @Test
    void allZeroDecimalDegreeCatalogIsNotRadiansAndCannotRender() throws Exception {
        GpsSignalMappingModal modal = modalOnFx();
        runOnFx(() -> {
            modal.setMeasurementLabel("ASCII_eCC21_KA04NC7760_Bengaluru_09_08_2023_0002360_20230814_134817.asc");
            modal.setDraft(GpsMappingDraft.resolve("p1", "drive.asc", zeroFixCatalog(), List.of()));
        });
        assertFalse(modal.bannerTitleForTest().toLowerCase().contains("radian"));
        assertFalse(modal.bannerDetailForTest().toLowerCase().contains("radian"));
        assertEquals("Route cannot be rendered", modal.bannerTitleForTest());
        assertTrue(modal.bannerDetailForTest().contains("0,0"));
        assertEquals("Degrees detected", modal.coordinateFormatForTest());
    }

    @Test
    void escapeClosesWithoutSaving() throws Exception {
        GpsSignalMappingModal modal = modalOnFx();
        AtomicBoolean cancelled = new AtomicBoolean();
        runOnFx(() -> {
            modal.setCancelHandler(() -> cancelled.set(true));
            modal.setDraft(GpsMappingDraft.resolve("p1", "drive.asc", bengaluruCatalog(), List.of()));
            modal.escapeForTest();
        });
        assertTrue(cancelled.get());
    }

    private static GpsSignalMappingModal modalOnFx() throws Exception {
        AtomicReference<GpsSignalMappingModal> ref = new AtomicReference<>();
        runOnFx(() -> ref.set(new GpsSignalMappingModal()));
        return ref.get();
    }

    private static List<SignalCatalogEntry> bengaluruCatalog() {
        return List.of(
                entry("GPS_LAT_INFO", "deg", 34_817, 12.91, 13.05),
                entry("GPS_LONG_INFO", "deg", 34_817, 77.55, 77.68),
                entry("GPS_ALTITUDE", "m", 34_817, 820, 940),
                entry("EngineSpeed", "rpm", 34_817, 700, 4200));
    }

    private static List<SignalCatalogEntry> swappedCatalog() {
        return List.of(
                entry("GPS_LAT_INFO", "deg", 1_000, 77.55, 77.68),
                entry("GPS_LONG_INFO", "deg", 1_000, 12.91, 13.05));
    }

    private static List<SignalCatalogEntry> engineOnly() {
        return List.of(entry("EngineSpeed", "rpm", 200, 700, 4200));
    }

    private static List<SignalCatalogEntry> zeroFixCatalog() {
        return List.of(
                entry("GPS_LAT_INFO", "Decimal Degrees", 13, 0.0, 0.0),
                entry("GPS_LONG_INFO", "", 13, 0.0, 0.0));
    }

    private static SignalCatalogEntry entry(String name, String unit, int samples, double min, double max) {
        SignalCatalogKey key = new SignalCatalogKey(
                name, SignalSourceType.DBC_CAN, "GPS_COORDINATE_22C", 556, 3, "drive.asc", "veh.dbc");
        return new SignalCatalogEntry(key, unit, samples, 0L, samples * 1_000_000_000L, min, max, "drive.asc", "veh.dbc");
    }

    private static void runOnFx(Runnable action) throws Exception {
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
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }
}
