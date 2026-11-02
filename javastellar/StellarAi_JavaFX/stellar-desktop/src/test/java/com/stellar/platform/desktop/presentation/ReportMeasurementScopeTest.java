package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.report.EngineeringHtmlReportRenderer;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.storage.StellarAiPaths;
import com.stellar.platform.storage.analysis.AnalysisStorageIsolation;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.analysis.CentralSqlitePaths;
import com.stellar.platform.storage.metadata.AnalysisScopeRecord;
import com.stellar.platform.storage.metadata.ProjectFileMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SignalCatalogMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportMeasurementScopeTest {

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
    void allMeasurementsLogicalSignalUsesProjectScopeNotTheLastCatalogSource(@TempDir Path temp) throws Exception {
        install(temp.resolve("home"), temp.resolve("operator"));
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(CentralSqlitePaths.ensureLayout())) {
            seed(store, List.of(catalogRow("src-a", "alpha.dat"), catalogRow("src-z", "zulu.dat")));
            store.upsertAnalysisScope(new AnalysisScopeRecord("p1", "ALL", "", "", Instant.now()));
            String page = report(store, projectKey());
            assertTrue(page.contains("<td>EngineSpeed</td><td>rpm</td><td>All Measurements</td>"), page);
            assertFalse(signalCell(page).contains("zulu.dat"), page);
            assertFalse(signalCell(page).contains("alpha.dat"), page);
            assertTrue(ReportMapScope.decide(investigation(projectKey()),
                    store.findAnalysisScope("p1").orElseThrow(), false, "src-z", "zulu.dat", true).projectWide());
            assertEquals("", ReportMapScope.seriesScope(ReportMapScope.decide(
                    investigation(projectKey()), store.findAnalysisScope("p1").orElseThrow(),
                    false, "src-z", "zulu.dat", true)));
        }
    }

    @Test
    void catalogOrderDoesNotChangeAllMeasurementsScope(@TempDir Path temp) throws Exception {
        install(temp.resolve("home"), temp.resolve("operator"));
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(CentralSqlitePaths.ensureLayout())) {
            seed(store, List.of(catalogRow("src-z", "zulu.dat"), catalogRow("src-a", "alpha.dat")));
            store.upsertAnalysisScope(new AnalysisScopeRecord("p1", "ALL", "", "", Instant.now()));
            String first = signalCell(report(store, projectKey()));
            store.replaceSignalCatalog("p1", "art", List.of(
                    catalogRow("src-a", "alpha.dat"), catalogRow("src-z", "zulu.dat")));
            String second = signalCell(report(store, projectKey()));
            assertEquals(first, second);
            assertTrue(first.contains(">All Measurements<"));
            assertFalse(first.contains("zulu.dat"));
            assertFalse(first.contains("alpha.dat"));
        }
    }

    @Test
    void singleMeasurementScopeAgreesAcrossTableMapAndWaveform(@TempDir Path temp) throws Exception {
        install(temp.resolve("home"), temp.resolve("operator"));
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(CentralSqlitePaths.ensureLayout())) {
            seed(store, List.of(catalogRow("src-z", "zulu.dat"), catalogRow("src-a", "alpha.dat")));
            store.upsertAnalysisScope(new AnalysisScopeRecord(
                    "p1", "SINGLE", "src-a", "alpha.dat", Instant.now()));
            String page = report(store, projectKey());
            assertTrue(page.contains("<td>EngineSpeed</td><td>rpm</td><td>alpha.dat</td>"), page);
            assertFalse(signalCell(page).contains("zulu.dat"), page);
            var decision = ReportMapScope.decide(investigation(projectKey()),
                    store.findAnalysisScope("p1").orElseThrow(), true, "", "", true);
            assertFalse(decision.projectWide());
            assertEquals("src-a", decision.sourceId());
            assertEquals("alpha.dat", decision.fileName());
            assertEquals("src-a", ReportMapScope.seriesScope(decision));
        }
    }

    @Test
    void reopenedStoreKeepsThePersistedScope(@TempDir Path temp) throws Exception {
        install(temp.resolve("home"), temp.resolve("operator"));
        Path db = CentralSqlitePaths.ensureLayout();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            seed(store, List.of(catalogRow("src-z", "zulu.dat")));
            store.upsertAnalysisScope(new AnalysisScopeRecord("p1", "ALL", "", "", Instant.now()));
        }
        try (SqliteProjectMetadataStore reopened = new SqliteProjectMetadataStore(db)) {
            String page = report(reopened, projectKey());
            assertTrue(signalCell(page).contains(">All Measurements<"), page);
            assertTrue(reopened.findAnalysisScope("p1").orElseThrow().allMeasurements());
        }
    }

    @Test
    void missingScopeDoesNotPickACatalogFilename(@TempDir Path temp) throws Exception {
        install(temp.resolve("home"), temp.resolve("operator"));
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(CentralSqlitePaths.ensureLayout())) {
            seed(store, List.of(catalogRow("src-a", "alpha.dat"), catalogRow("src-z", "zulu.dat")));
            String page = report(store, projectKey());
            assertTrue(signalCell(page).contains(">All Measurements<"), page);
            assertFalse(signalCell(page).contains("zulu.dat"), page);
            assertFalse(signalCell(page).contains("alpha.dat"), page);
            assertTrue(ReportMapScope.decide(investigation(projectKey()), null, false, "src-z", "zulu.dat", true)
                    .projectWide());
        }
    }

    private static String report(SqliteProjectMetadataStore store, SignalCatalogKey key) {
        DesktopInvestigationService service = new DesktopInvestigationService();
        EngineeringInvestigation saved = service.save(store, EngineeringInvestigation.builder("p1")
                .name("Scope")
                .signalKeyBlobs(List.of(GraphInvestigationCodec.encodeKey(key)))
                .build());
        return EngineeringHtmlReportRenderer.render(service.buildReportModel(store, saved, "engineer"));
    }

    private static String signalCell(String page) {
        int start = page.indexOf("<td>EngineSpeed</td>");
        int end = page.indexOf("</tr>", start);
        return page.substring(start, end);
    }

    private static EngineeringInvestigation investigation(SignalCatalogKey key) {
        return EngineeringInvestigation.builder("p1")
                .signalKeyBlobs(List.of(GraphInvestigationCodec.encodeKey(key)))
                .build();
    }

    private static SignalCatalogKey projectKey() {
        return new SignalCatalogKey("EngineSpeed", SignalSourceType.MF4_NATIVE, "ECU", 0, 0,
                ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE, "");
    }

    private static void seed(SqliteProjectMetadataStore store, List<SignalCatalogMetadata> catalog) {
        store.upsertProject(new ProjectMetadata("p1", "Dat", Instant.now(), Instant.now()));
        store.upsertFile(new ProjectFileMetadata(
                "p1", "src-a", "C:/data/alpha.dat", "MEASUREMENT_DAT", 10, "", "", "", "", false, true, false));
        store.upsertFile(new ProjectFileMetadata(
                "p1", "src-z", "C:/data/zulu.dat", "MEASUREMENT_DAT", 10, "", "", "", "", false, false, false));
        store.replaceSignalCatalog("p1", "art", catalog);
    }

    private static SignalCatalogMetadata catalogRow(String sourceId, String fileName) {
        return new SignalCatalogMetadata(
                sourceId + "-row", "p1", sourceId, 0, "", "art",
                sourceId + "|EngineSpeed", "EngineSpeed", "ECU", 0, 0, "MF4_NATIVE", "rpm",
                10, 0L, 1_000_000_000L, 1.0, 9.0, Instant.now(), Instant.now());
    }

    private void install(Path home, Path selected) throws Exception {
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
        AnalysisStorageRoots.setUserSelectedRoot(selected);
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
