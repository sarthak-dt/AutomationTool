package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionCandidateKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelDefinitionCandidate;
import com.stellar.platform.desktop.project.SourceChannelDefinitionComparison;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.inventory.CanIdMatchDiagnostic;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stable Source A/B/C presentation identity for grouped DBC Mapping. */
class DbcMappingSourceAliasOrderTest {

    @BeforeAll
    static void initJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(45)
    void confirmChooseCompareDoNotReorderAliases() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            DbcMappingTablePane pane = fx.bindPane();

            List<SourceChannelMappingEntry> seeded = new ArrayList<>();
            for (ProjectSourceId id : List.of(fx.cId, fx.bId, fx.aId)) {
                seeded.add(recommended(id, fx.defA));
            }
            seeded.sort(Comparator.comparing((SourceChannelMappingEntry e) ->
                    e.sourceChannelKey().sourceId().value()).reversed());
            fx.service.definitionMatchingModel().replaceMappings(seeded);
            fx.service.definitionMatchingModel().replaceCandidates(List.of(
                    candidate(fx.aId, fx.defA),
                    candidate(fx.aId, fx.defB),
                    candidate(fx.bId, fx.defA),
                    candidate(fx.cId, fx.defA)
            ));
            fx.service.definitionMatchingModel().replaceComparisons(List.of(
                    comparison(fx.aId, fx.defA),
                    comparison(fx.aId, fx.defB)
            ));
            pane.refreshFromModel();
            assertAliases(pane, "Source A", "Source B", "Source C");
            assertSourceOrder(pane, fx.aId, fx.bId, fx.cId);

            fx.service.confirmChannelRecommendation(SourceChannelKey.of(fx.aId, 1));
            pane.refreshFromModel();
            assertAliases(pane, "Source A", "Source B", "Source C");
            assertSourceOrder(pane, fx.aId, fx.bId, fx.cId);

            fx.service.chooseChannelDefinition(SourceChannelKey.of(fx.aId, 1), fx.defB);
            pane.refreshFromModel();
            assertAliases(pane, "Source A", "Source B", "Source C");
            assertSourceOrder(pane, fx.aId, fx.bId, fx.cId);

            pane.refreshFromModel();
            assertAliases(pane, "Source A", "Source B", "Source C");
            assertSourceOrder(pane, fx.aId, fx.bId, fx.cId);
            assertEquals(2, fx.service.definitionMatchingModel()
                    .comparisonsFor(SourceChannelKey.of(fx.aId, 1)).size());
        });
    }

    @Test
    @Timeout(45)
    void filterClearAndCollapsePreserveAliasesAndOrder() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            DbcMappingTablePane pane = fx.bindPane();
            fx.seedAbcMappings();
            pane.refreshFromModel();
            assertAliases(pane, "Source A", "Source B", "Source C");

            pane.setSearchTextForTest(fx.bFile.fileName());
            assertEquals(1, pane.visibleGroupsForTest().size());
            assertEquals("Source B", pane.visibleGroupsForTest().getFirst().label().alias());

            pane.setSearchTextForTest("");
            assertAliases(pane, "Source A", "Source B", "Source C");
            assertSourceOrder(pane, fx.aId, fx.bId, fx.cId);

            pane.toggleExpandedForTest(fx.aId);
            pane.toggleExpandedForTest(fx.bId);
            assertAliases(pane, "Source A", "Source B", "Source C");
            assertSourceOrder(pane, fx.aId, fx.bId, fx.cId);
        });
    }

    @Test
    @Timeout(45)
    void removeBDoesNotRenameCAndNewSourceGetsD() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            DbcMappingTablePane pane = fx.bindPane();
            fx.seedAbcMappings();
            pane.refreshFromModel();
            assertEquals("Source B", fx.service.sourceAliasRegistry().aliasFor(fx.bId));
            assertEquals("Source C", fx.service.sourceAliasRegistry().aliasFor(fx.cId));

            fx.service.workspace().removeMeasurement(fx.bId);
            fx.service.definitionMatchingModel().removeSource(fx.bId);
            pane.refreshFromModel();

            assertAliases(pane, "Source A", "Source C");
            assertSourceOrder(pane, fx.aId, fx.cId);
            assertEquals("Source C", fx.service.sourceAliasRegistry().aliasFor(fx.cId));

            WorkspaceFile dFile = fx.service.workspace().addFile(
                    new WorkspaceFile(Path.of("d_late.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 10));
            ProjectSourceId dId = dFile.projectSourceId();
            fx.service.definitionMatchingModel().putMapping(recommended(dId, fx.defA));
            pane.refreshFromModel();

            assertAliases(pane, "Source A", "Source C", "Source D");
            assertSourceOrder(pane, fx.aId, fx.cId, dId);
        });
    }

    @Test
    @Timeout(45)
    void newProjectResetsAliasAllocationToSourceA() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            DbcMappingTablePane pane = fx.bindPane();
            fx.seedAbcMappings();
            pane.refreshFromModel();
            assertEquals("Source C", fx.service.sourceAliasRegistry().aliasFor(fx.cId));

            fx.service.newProject("Fresh Bench");
            WorkspaceFile next = fx.service.workspace().addFile(
                    new WorkspaceFile(Path.of("fresh.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 10));
            fx.service.definitionMatchingModel().putMapping(recommended(next.projectSourceId(), fx.defA));
            pane.refreshFromModel();

            assertEquals(1, pane.allGroupsForTest().size());
            assertEquals("Source A", pane.allGroupsForTest().getFirst().label().alias());
            assertEquals("Source A", fx.service.sourceAliasRegistry().aliasFor(next.projectSourceId()));
        });
    }

    private static void assertAliases(DbcMappingTablePane pane, String... aliases) {
        List<DbcMappingTablePane.SourceMappingGroup> groups = pane.visibleGroupsForTest();
        assertEquals(aliases.length, groups.size(), "visible group count");
        for (int i = 0; i < aliases.length; i++) {
            assertEquals(aliases[i], groups.get(i).label().alias(), "alias at index " + i);
        }
        List<DbcMappingTablePane.SourceMappingGroup> all = pane.allGroupsForTest();
        assertEquals(aliases.length, all.size(), "all group count");
        for (int i = 0; i < aliases.length; i++) {
            assertEquals(aliases[i], all.get(i).label().alias(), "all-groups alias at index " + i);
        }
    }

    private static void assertSourceOrder(DbcMappingTablePane pane, ProjectSourceId... ids) {
        List<DbcMappingTablePane.SourceMappingGroup> groups = pane.visibleGroupsForTest();
        assertEquals(ids.length, groups.size());
        for (int i = 0; i < ids.length; i++) {
            assertEquals(ids[i], groups.get(i).sourceId(), "source order at index " + i);
        }
    }

    private static SourceChannelMappingEntry recommended(ProjectSourceId sourceId, DefinitionSourceId def) {
        return new SourceChannelMappingEntry(
                SourceChannelKey.of(sourceId, 1),
                ChannelMappingStatus.RECOMMENDED,
                ChannelRecommendationKind.CLEAR_BEST_MATCH,
                def,
                "demo.dbc",
                null,
                "",
                10,
                8,
                0.8
        );
    }

    private static SourceChannelDefinitionCandidate candidate(
            ProjectSourceId sourceId,
            DefinitionSourceId def
    ) {
        return new SourceChannelDefinitionCandidate(
                SourceChannelKey.of(sourceId, 1),
                def,
                "demo.dbc",
                12,
                DefinitionCandidateKind.HAS_OVERLAP,
                10,
                8,
                2,
                0.8
        );
    }

    private static SourceChannelDefinitionComparison comparison(
            ProjectSourceId sourceId,
            DefinitionSourceId def
    ) {
        return new SourceChannelDefinitionComparison(
                SourceChannelKey.of(sourceId, 1),
                def,
                "demo.dbc",
                12,
                DefinitionCandidateKind.HAS_OVERLAP,
                10,
                8,
                0.8,
                List.<CanIdMatchDiagnostic>of()
        );
    }

    private static void runOnFx(FxBody body) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(40, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @FunctionalInterface
    private interface FxBody {
        void run() throws Exception;
    }

    private static final class Fixture {
        private final DesktopAppService service;
        private final WorkspaceFile aFile;
        private final WorkspaceFile bFile;
        private final WorkspaceFile cFile;
        private final ProjectSourceId aId;
        private final ProjectSourceId bId;
        private final ProjectSourceId cId;
        private final DefinitionSourceId defA = DefinitionSourceId.generate();
        private final DefinitionSourceId defB = DefinitionSourceId.generate();

        private Fixture(DesktopAppService service, WorkspaceFile a, WorkspaceFile b, WorkspaceFile c) {
            this.service = service;
            this.aFile = a;
            this.bFile = b;
            this.cFile = c;
            this.aId = a.projectSourceId();
            this.bId = b.projectSourceId();
            this.cId = c.projectSourceId();
        }

        static Fixture create() {
            try {
                System.setProperty("stellarai.home",
                        java.nio.file.Files.createTempDirectory("stellar-home-").toAbsolutePath().toString());
            } catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            WorkspaceFile a = service.workspace().addFile(
                    new WorkspaceFile(Path.of("aaa_source.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 10));
            WorkspaceFile b = service.workspace().addFile(
                    new WorkspaceFile(Path.of("bbb_source.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 10));
            WorkspaceFile c = service.workspace().addFile(
                    new WorkspaceFile(Path.of("ccc_source.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 10));
            return new Fixture(service, a, b, c);
        }

        DbcMappingTablePane bindPane() {
            DbcMappingTablePane pane = new DbcMappingTablePane();
            pane.bind(service, () -> {
            });
            return pane;
        }

        void seedAbcMappings() {
            List<SourceChannelMappingEntry> entries = new ArrayList<>(List.of(
                    recommended(aId, defA),
                    recommended(bId, defA),
                    recommended(cId, defA)
            ));
            entries.sort(Comparator.comparing((SourceChannelMappingEntry e) ->
                    e.sourceChannelKey().sourceId().value()).reversed());
            service.definitionMatchingModel().replaceMappings(entries);
        }
    }
}
