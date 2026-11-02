package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineeringEvidenceItem;
import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.EvidenceKind;
import com.stellar.platform.analysis.investigation.InvestigationStoragePaths;
import com.stellar.platform.analysis.investigation.report.RouteMapSnapshot;
import com.stellar.platform.storage.StellarAiPaths;
import com.stellar.platform.storage.analysis.AnalysisStorageIsolation;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.analysis.CentralSqlitePaths;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteMapReportStorageTest {

    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private static final String SECRET = "AIzaSyTESTKEY1234567890";

    private String previousHome;
    private String previousAuto;
    private String previousAppPath;
    private String previousIsolate;

    @AfterEach
    void restore() {
        AnalysisStorageRoots.clear();
        restore("stellarai.home", previousHome);
        restore("stellarai.analysis.storage.disable-auto", previousAuto);
        restore("jpackage.app-path", previousAppPath);
        restore(AnalysisStorageIsolation.PROPERTY, previousIsolate);
    }

    @Test
    void reportMapAssetLivesUnderSelectedStorageAndSurvivesRenameAndDelete(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path selected = temp.resolve("operator");
        install(home);
        AnalysisStorageRoots.setUserSelectedRoot(selected);
        AnalysisStorageRoots.ensureControlledDirectory();
        Path db = CentralSqlitePaths.ensureLayout();
        assertTrue(db.startsWith(selected.resolve("StellarAIAnalysis")));
        assertFalse(db.startsWith(home));

        Instant now = Instant.parse("2026-10-13T09:00:00Z");
        DesktopInvestigationService service = new DesktopInvestigationService();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Drive", now, now));
            EngineeringInvestigation draft = EngineeringInvestigation.builder("p1")
                    .name("Route investigation")
                    .build();
            EngineeringInvestigation saved = service.save(store, draft);
            String snapshotId = "route-snap";
            Path png = service.writeSnapshotPng("p1", saved.investigationId(), snapshotId, TINY_PNG);
            RouteMapSnapshot facts = new RouteMapSnapshot(0L, 4_000_000_000L, 12, 800.0, 10, 11, 20, 21, true);
            Path json = InvestigationStoragePaths.routeMapMetadata(
                    AnalysisStorageRoots.projectArtifactsRoot("p1"), saved.investigationId(), snapshotId);
            Files.writeString(json, facts.toJson(), StandardCharsets.UTF_8);
            assertFalse(Files.readString(json).contains(SECRET));
            assertTrue(png.startsWith(selected.resolve("StellarAIAnalysis")));
            assertFalse(png.startsWith(home));
            assertFalse(png.startsWith(Path.of(System.getProperty("java.io.tmpdir"), "stellar-desktop-api")));

            EngineeringInvestigation withRoute = service.save(store, saved.toBuilder()
                    .evidence(java.util.List.of(new EngineeringEvidenceItem(
                            "e1", saved.investigationId(), EvidenceKind.ROUTE_MAP, snapshotId, 0, false,
                            "", "", 0L, "Route map", "Route map", now)))
                    .build());
            Path html = service.generateHtmlReport(store, withRoute, "engineer", null);
            String page = Files.readString(html);
            assertTrue(page.contains("assets/map-route.png"));
            assertTrue(page.contains("Route Map"));
            assertFalse(page.contains(SECRET));
            assertFalse(page.contains("<iframe"));
            Path asset = html.getParent().resolve("assets").resolve("map-route.png");
            assertTrue(Files.isRegularFile(asset));
            assertTrue(asset.startsWith(selected.resolve("StellarAIAnalysis")));
            assertFalse(asset.startsWith(home));
            assertFalse(Files.exists(home.resolve("reports")));
            assertEquals(21, SqliteProjectMetadataStore.currentSchemaVersion());

            var row = store.listInvestigationReports(withRoute.investigationId()).getFirst();
            var renamed = new ReportRenameService().rename(row, "Renamed Route",
                    java.util.List.of(row.name()), store::upsertInvestigationReport);
            assertTrue(Files.isRegularFile(Path.of(renamed.destination())));
            assertTrue(Files.readString(Path.of(renamed.destination())).contains("assets/map-route.png"));
            assertTrue(Files.isRegularFile(Path.of(renamed.destination()).getParent()
                    .resolve("assets").resolve("map-route.png")));

            service.deleteReport(store, renamed);
            assertFalse(Files.exists(Path.of(renamed.destination())));
            assertFalse(Files.exists(Path.of(renamed.destination()).getParent()
                    .resolve("assets").resolve("map-route.png")));
        }
    }

    private void install(Path home) throws Exception {
        previousHome = System.getProperty("stellarai.home");
        previousAuto = System.getProperty("stellarai.analysis.storage.disable-auto");
        previousAppPath = System.getProperty("jpackage.app-path");
        previousIsolate = System.getProperty(AnalysisStorageIsolation.PROPERTY);
        Files.createDirectories(home);
        System.setProperty("stellarai.home", home.toAbsolutePath().normalize().toString());
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        System.setProperty("jpackage.app-path", "C:\\Program Files\\StellarAI\\StellarAI.exe");
        System.setProperty(AnalysisStorageIsolation.PROPERTY, "false");
        AnalysisStorageRoots.clear();
        StellarAiPaths.homeRoot();
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
