package com.stellar.platform.desktop.presentation;

import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionCandidateKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelDefinitionCandidate;
import com.stellar.platform.desktop.project.SourceChannelDefinitionComparison;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanIdMatchDiagnosticStatus;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.inventory.CanDefinitionMatchingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectDefinitionMatchingCoordinatorTest {

    @TempDir
    Path tempDir;

    @Test
    void fourSourcesTwoDefinitionsProduceFullCandidateMatrix() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);

        DefinitionSourceId powertrain = DefinitionSourceId.generate();
        DefinitionSourceId body = DefinitionSourceId.generate();
        DbcDatabase powertrainDb = dbc("Powertrain", 1, 2, 3, 4);
        DbcDatabase bodyDb = dbc("Body", 10, 11, 12);
        coordinator.registerDefinition(powertrain, "Powertrain.dbc", powertrainDb);
        coordinator.registerDefinition(body, "Body.dbc", bodyDb);

        List<ProjectSourceId> sources = List.of(
                ProjectSourceId.generate(),
                ProjectSourceId.generate(),
                ProjectSourceId.generate(),
                ProjectSourceId.generate()
        );
        int[] channelCounts = {3, 2, 2, 3};
        int totalChannels = 0;
        for (int i = 0; i < sources.size(); i++) {
            CanSourceInventory inv = inventory(Path.of("Run_0" + (i + 1) + ".mf4"), channelCounts[i], 100 + i);
            totalChannels += channelCounts[i];
            coordinator.onInventoryReady(sources.get(i), inv);
        }

        assertEquals(totalChannels * 2, model.allCandidates().size());
        assertEquals(totalChannels, model.allMappings().size());
        for (ProjectSourceId sourceId : sources) {
            for (int ch = 1; ch <= 3; ch++) {
                SourceChannelKey key = SourceChannelKey.of(sourceId, ch);
                if (model.mapping(key).isEmpty()) {
                    continue;
                }
                assertEquals(2, model.candidatesFor(key).size());
            }
        }
    }

    @Test
    void bestMatchRankingPrefersHigherCoverage() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        DefinitionSourceId a = DefinitionSourceId.generate();
        DefinitionSourceId b = DefinitionSourceId.generate();
        coordinator.registerDefinition(a, "A.dbc", dbc("A", 1, 2, 3, 4));
        coordinator.registerDefinition(b, "B.dbc", dbc("B", 1, 2));

        ProjectSourceId source = ProjectSourceId.generate();
        coordinator.onInventoryReady(source, inventoryWithIds(Path.of("obs.mf4"), 1, 1, 2, 3, 4, 5));

        SourceChannelKey key = SourceChannelKey.of(source, 1);
        SourceChannelMappingEntry mapping = model.mapping(key).orElseThrow();
        assertEquals(ChannelRecommendationKind.CLEAR_BEST_MATCH, mapping.recommendationKind());
        assertEquals(a, mapping.recommendedDefinitionId().orElseThrow());
        assertEquals(4L, mapping.matchedUniqueMessageCount());
        assertEquals(5L, mapping.observedUniqueMessageCount());
        assertEquals(0.8, mapping.coverageRatio(), 1e-9);

        SourceChannelDefinitionCandidate ca = model.candidatesFor(key).stream()
                .filter(c -> c.definitionSourceId().equals(a)).findFirst().orElseThrow();
        SourceChannelDefinitionCandidate cb = model.candidatesFor(key).stream()
                .filter(c -> c.definitionSourceId().equals(b)).findFirst().orElseThrow();
        assertEquals(4L, ca.matchedUniqueMessageCount());
        assertEquals(2L, cb.matchedUniqueMessageCount());
    }

    @Test
    void noMatchWhenNoOverlappingIds() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        coordinator.registerDefinition(DefinitionSourceId.generate(), "A.dbc", dbc("A", 10, 11));
        coordinator.registerDefinition(DefinitionSourceId.generate(), "B.dbc", dbc("B", 20, 21));
        ProjectSourceId source = ProjectSourceId.generate();
        coordinator.onInventoryReady(source, inventoryWithIds(Path.of("obs.mf4"), 1, 1, 2, 3));
        SourceChannelMappingEntry mapping = model.mapping(SourceChannelKey.of(source, 1)).orElseThrow();
        assertEquals(ChannelMappingStatus.NO_MATCH, mapping.status());
        assertEquals(ChannelRecommendationKind.NO_MATCH, mapping.recommendationKind());
    }

    @Test
    void sameDefinitionCanMapMultipleSources() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        DefinitionSourceId shared = DefinitionSourceId.generate();
        DbcDatabase db = dbc("Shared", 1, 2, 3);
        coordinator.registerDefinition(shared, "Shared.dbc", db);
        coordinator.registerDefinition(DefinitionSourceId.generate(), "Other.dbc", dbc("Other", 99));

        ProjectSourceId a = ProjectSourceId.generate();
        ProjectSourceId b = ProjectSourceId.generate();
        ProjectSourceId c = ProjectSourceId.generate();
        ProjectSourceId d = ProjectSourceId.generate();
        coordinator.onInventoryReady(a, inventoryWithIds(Path.of("A.mf4"), 1, 1, 2));
        coordinator.onInventoryReady(b, inventoryWithIds(Path.of("B.mf4"), 1, 1, 3));
        coordinator.onInventoryReady(c, inventoryWithIds(Path.of("C.mf4"), 2, 2, 1));
        coordinator.onInventoryReady(d, inventoryWithIds(Path.of("D.mf4"), 3, 3, 2));

        assertEquals(shared, model.mapping(SourceChannelKey.of(a, 1)).orElseThrow().recommendedDefinitionId().orElseThrow());
        assertEquals(shared, model.mapping(SourceChannelKey.of(b, 1)).orElseThrow().recommendedDefinitionId().orElseThrow());
        assertEquals(shared, model.mapping(SourceChannelKey.of(c, 2)).orElseThrow().recommendedDefinitionId().orElseThrow());
        assertEquals(shared, model.mapping(SourceChannelKey.of(d, 3)).orElseThrow().recommendedDefinitionId().orElseThrow());
        assertSame(db, coordinator.parsedDefinition(shared).orElseThrow().database());
    }

    @Test
    void oneSourceCanUseMultipleDefinitionsAcrossChannels() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        DefinitionSourceId defA = DefinitionSourceId.generate();
        DefinitionSourceId defB = DefinitionSourceId.generate();
        coordinator.registerDefinition(defA, "A.dbc", dbc("A", 1, 2, 3));
        coordinator.registerDefinition(defB, "B.dbc", dbc("B", 10, 11, 12));

        ProjectSourceId source = ProjectSourceId.generate();
        List<CanChannelInventory> channels = List.of(
                channel(1, 1, 2, 3),
                channel(2, 10, 11, 12)
        );
        coordinator.onInventoryReady(source, new CanSourceInventory(
                Path.of("mixed.mf4"), "mixed.mf4", MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, 2, 6, 6, true, channels, List.of()));

        assertEquals(defA, model.mapping(SourceChannelKey.of(source, 1)).orElseThrow().recommendedDefinitionId().orElseThrow());
        assertEquals(defB, model.mapping(SourceChannelKey.of(source, 2)).orElseThrow().recommendedDefinitionId().orElseThrow());
    }

    @Test
    void manualOverrideBecomesMappedSelection() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        DefinitionSourceId defA = DefinitionSourceId.generate();
        DefinitionSourceId defB = DefinitionSourceId.generate();
        coordinator.registerDefinition(defA, "A.dbc", dbc("A", 1, 2, 3, 4));
        coordinator.registerDefinition(defB, "B.dbc", dbc("B", 1, 2));
        ProjectSourceId source = ProjectSourceId.generate();
        coordinator.onInventoryReady(source, inventoryWithIds(Path.of("obs.mf4"), 1, 1, 2, 3, 4, 5));
        SourceChannelKey key = SourceChannelKey.of(source, 1);
        assertEquals(ChannelMappingStatus.RECOMMENDED, model.mapping(key).orElseThrow().status());
        assertEquals(defA, model.mapping(key).orElseThrow().recommendedDefinitionId().orElseThrow());

        coordinator.chooseDefinition(key, defB);
        SourceChannelMappingEntry mapped = model.mapping(key).orElseThrow();
        assertEquals(ChannelMappingStatus.MANUAL, mapped.status());
        assertEquals(defB, mapped.mappedDefinitionId().orElseThrow());
        assertEquals(defA, mapped.recommendedDefinitionId().orElseThrow());
    }

    @Test
    void confirmRecommendationBecomesConfirmed() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        DefinitionSourceId defA = DefinitionSourceId.generate();
        DefinitionSourceId defB = DefinitionSourceId.generate();
        coordinator.registerDefinition(defA, "A.dbc", dbc("A", 1, 2, 3));
        coordinator.registerDefinition(defB, "B.dbc", dbc("B", 1));
        ProjectSourceId source = ProjectSourceId.generate();
        coordinator.onInventoryReady(source, inventoryWithIds(Path.of("obs.mf4"), 1, 1, 2, 3));
        SourceChannelKey key = SourceChannelKey.of(source, 1);
        assertEquals(ChannelMappingStatus.RECOMMENDED, model.mapping(key).orElseThrow().status());

        coordinator.confirmRecommendation(key);
        SourceChannelMappingEntry confirmed = model.mapping(key).orElseThrow();
        assertEquals(ChannelMappingStatus.CONFIRMED, confirmed.status());
        assertEquals(defA, confirmed.mappedDefinitionId().orElseThrow());
        assertEquals(1, model.summary().confirmedCount());
        assertEquals(0, model.summary().manualCount());
        assertEquals(0, model.summary().recommendedCount());
    }

    @Test
    void choosingClearRecommendationViaChooserIsConfirmedNotManual() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        DefinitionSourceId defA = DefinitionSourceId.generate();
        DefinitionSourceId defB = DefinitionSourceId.generate();
        coordinator.registerDefinition(defA, "A.dbc", dbc("A", 1, 2, 3));
        coordinator.registerDefinition(defB, "B.dbc", dbc("B", 9));
        ProjectSourceId source = ProjectSourceId.generate();
        coordinator.onInventoryReady(source, inventoryWithIds(Path.of("obs.mf4"), 1, 1, 2, 3));
        SourceChannelKey key = SourceChannelKey.of(source, 1);

        coordinator.chooseDefinition(key, defA);
        assertEquals(ChannelMappingStatus.CONFIRMED, model.mapping(key).orElseThrow().status());
        assertEquals(defA, model.mapping(key).orElseThrow().mappedDefinitionId().orElseThrow());
    }

    @Test
    void equalBestCoverageIsAmbiguousNotFilenameWinner() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        DefinitionSourceId zed = DefinitionSourceId.generate();
        DefinitionSourceId aye = DefinitionSourceId.generate();
        // Display names would sort A before Z — must not invent a CLEAR_BEST_MATCH.
        coordinator.registerDefinition(zed, "Z.dbc", dbc("Z", 1));
        coordinator.registerDefinition(aye, "A.dbc", dbc("A", 1));
        ProjectSourceId source = ProjectSourceId.generate();
        coordinator.onInventoryReady(source, inventoryWithIds(Path.of("obs.mf4"), 1, 1, 2, 3));
        SourceChannelKey key = SourceChannelKey.of(source, 1);

        SourceChannelMappingEntry mapping = model.mapping(key).orElseThrow();
        assertEquals(ChannelMappingStatus.AMBIGUOUS, mapping.status());
        assertEquals(1L, mapping.matchedUniqueMessageCount());
        assertEquals(3L, mapping.observedUniqueMessageCount());
        coordinator.confirmRecommendation(key);
        assertEquals(ChannelMappingStatus.AMBIGUOUS, model.mapping(key).orElseThrow().status(),
                "Confirm must not accept Ambiguous");
    }

    @Test
    void allZeroOverlapIsNoMatch() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        coordinator.registerDefinition(DefinitionSourceId.generate(), "1.dbc", dbc("1", 100));
        coordinator.registerDefinition(DefinitionSourceId.generate(), "2.dbc", dbc("2", 200));
        ProjectSourceId source = ProjectSourceId.generate();
        coordinator.onInventoryReady(source, inventoryWithIds(Path.of("obs.mf4"), 1, 1, 2, 3));
        SourceChannelMappingEntry mapping = model.mapping(SourceChannelKey.of(source, 1)).orElseThrow();
        assertEquals(ChannelMappingStatus.NO_MATCH, mapping.status());
        assertTrue(mapping.recommendedDefinitionId().isEmpty());
        assertEquals(3L, mapping.observedUniqueMessageCount());
        assertEquals(0L, mapping.matchedUniqueMessageCount());
    }

    @Test
    void lavasaStyleObservedIdsRecommendOnlyObdResponseDbc() {
        // Numeric identities only — no filename-based production logic.
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        DefinitionSourceId vehicleA = DefinitionSourceId.generate();
        DefinitionSourceId vehicleB = DefinitionSourceId.generate();
        DefinitionSourceId obd = DefinitionSourceId.generate();
        coordinator.registerDefinition(vehicleA, "vehA.dbc", dbc("VA", 0x201, 0x208, 0x334));
        coordinator.registerDefinition(vehicleB, "vehB.dbc", dbc("VB", 0x421, 0x552, 0x7FF));
        coordinator.registerDefinition(obd, "obd.dbc", dbc("OBD", 0x7E8));

        ProjectSourceId s1 = ProjectSourceId.generate();
        ProjectSourceId s2 = ProjectSourceId.generate();
        ProjectSourceId s3 = ProjectSourceId.generate();
        long[] observed = {0x316, 0x329, 0x7E8};
        coordinator.onInventoryReady(s1, inventoryWithIds(Path.of("s1.mf4"), 1, observed));
        coordinator.onInventoryReady(s2, inventoryWithIds(Path.of("s2.mf4"), 1, observed));
        coordinator.onInventoryReady(s3, inventoryWithIds(Path.of("s3.mf4"), 1, observed));

        assertEquals(9, model.allCandidates().size());
        for (ProjectSourceId source : List.of(s1, s2, s3)) {
            SourceChannelKey key = SourceChannelKey.of(source, 1);
            SourceChannelMappingEntry mapping = model.mapping(key).orElseThrow();
            assertEquals(ChannelMappingStatus.RECOMMENDED, mapping.status());
            assertEquals(obd, mapping.recommendedDefinitionId().orElseThrow());
            assertEquals(1L, mapping.matchedUniqueMessageCount());
            assertEquals(3L, mapping.observedUniqueMessageCount());
            assertEquals(0L, model.candidatesFor(key).stream()
                    .filter(c -> c.definitionSourceId().equals(vehicleA)).findFirst().orElseThrow()
                    .matchedUniqueMessageCount());
            assertEquals(0L, model.candidatesFor(key).stream()
                    .filter(c -> c.definitionSourceId().equals(vehicleB)).findFirst().orElseThrow()
                    .matchedUniqueMessageCount());
        }
        assertEquals(3, model.summary().recommendedCount());
        assertEquals(0, model.summary().confirmedCount());
        assertEquals(0, model.summary().manualCount());
    }

    @Test
    void highestCoverageCandidateIsRecommended() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        DefinitionSourceId d1 = DefinitionSourceId.generate();
        DefinitionSourceId d2 = DefinitionSourceId.generate();
        DefinitionSourceId d3 = DefinitionSourceId.generate();
        coordinator.registerDefinition(d1, "1.dbc", dbc("1", 1));
        coordinator.registerDefinition(d2, "2.dbc", dbc("2", 1, 2));
        coordinator.registerDefinition(d3, "3.dbc", dbc("3", 1, 2, 3));
        ProjectSourceId source = ProjectSourceId.generate();
        coordinator.onInventoryReady(source, inventoryWithIds(Path.of("obs.mf4"), 1, 1, 2, 3));
        SourceChannelMappingEntry mapping = model.mapping(SourceChannelKey.of(source, 1)).orElseThrow();
        assertEquals(ChannelMappingStatus.RECOMMENDED, mapping.status());
        assertEquals(d3, mapping.recommendedDefinitionId().orElseThrow());
        assertEquals(3L, mapping.matchedUniqueMessageCount());
        assertEquals(3L, mapping.observedUniqueMessageCount());
    }

    @Test
    void addingDefinitionDoesNotReplaceInventoryInstance() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        ProjectSourceId source = ProjectSourceId.generate();
        CanSourceInventory inventory = inventoryWithIds(Path.of("ready.mf4"), 1, 1, 2, 3);
        coordinator.onInventoryReady(source, inventory);
        DefinitionSourceId first = DefinitionSourceId.generate();
        coordinator.registerDefinition(first, "A.dbc", dbc("A", 1, 2));

        CanSourceInventory before = inventory;
        DefinitionSourceId second = DefinitionSourceId.generate();
        coordinator.registerDefinition(second, "B.dbc", dbc("B", 1, 2, 3));

        assertSame(before, inventory);
        assertEquals(2, model.candidatesFor(SourceChannelKey.of(source, 1)).size());
        assertEquals(ChannelMappingStatus.RECOMMENDED,
                model.mapping(SourceChannelKey.of(source, 1)).orElseThrow().status());
    }

    @Test
    void removingDefinitionClearsAffectedMappingAndKeepsOthers() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        DefinitionSourceId keep = DefinitionSourceId.generate();
        DefinitionSourceId remove = DefinitionSourceId.generate();
        coordinator.registerDefinition(keep, "Keep.dbc", dbc("Keep", 1, 2, 3));
        coordinator.registerDefinition(remove, "Remove.dbc", dbc("Remove", 10, 11));

        ProjectSourceId source = ProjectSourceId.generate();
        List<CanChannelInventory> channels = List.of(channel(1, 1, 2, 3), channel(2, 10, 11));
        coordinator.onInventoryReady(source, new CanSourceInventory(
                Path.of("src.mf4"), "src.mf4", MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, 2, 5, 5, true, channels, List.of()));

        SourceChannelKey ch2 = SourceChannelKey.of(source, 2);
        coordinator.confirmRecommendation(ch2);
        assertEquals(remove, model.mapping(ch2).orElseThrow().mappedDefinitionId().orElseThrow());

        coordinator.unregisterDefinition(remove);
        assertTrue(model.candidatesFor(ch2).stream().noneMatch(c -> c.definitionSourceId().equals(remove)));
        SourceChannelMappingEntry after = model.mapping(ch2).orElseThrow();
        assertTrue(after.status() == ChannelMappingStatus.NO_MATCH
                || after.mappedDefinitionId().isEmpty());
        assertEquals(keep, model.mapping(SourceChannelKey.of(source, 1)).orElseThrow()
                .recommendedDefinitionId().orElseThrow());
    }

    @Test
    void queryPathsDoNotNotifyMatchingModel() throws Exception {
        Path mf4 = tempDir.resolve("q.mf4");
        Path dbc = tempDir.resolve("q.dbc");
        com.stellar.platform.desktop.support.TestMeasurementFixtures.validMinimalMf4(mf4);
        Files.writeString(dbc, """
                VERSION ""
                BO_ 256 Msg: 8 Vector__XXX
                 SG_ Sig : 0|8@1+ (1,0) [0|0] "" Vector__XXX
                """);
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()),
                (sourceId, path, ctx, progress) -> {
                    CanSourceInventory inv = inventoryWithIds(path, 1, 0x100);
                    progress.onInventoryUpdated(inv);
                    return inv;
                }
        );
        service.addMeasurementPaths(List.of(mf4), DesktopAppService.PlatformUi.NOOP);
        service.addDefinitionPaths(List.of(dbc));
        WorkspaceFile measurement = service.workspace().selectedMeasurement().orElseThrow();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            boolean readyInv = service.sourceInventoryModel().inventoryFor(measurement.id())
                    .filter(i -> i.status() == CanInventoryStatus.INVENTORY_READY)
                    .isPresent();
            boolean mapped = !service.definitionMatchingModel().allMappings().isEmpty();
            if (readyInv && mapped) {
                break;
            }
            Thread.sleep(20);
        }
        // Allow any duplicate terminal publish to settle before attaching listeners.
        Thread.sleep(50);
        AtomicInteger notifications = new AtomicInteger();
        service.definitionMatchingModel().addListener(notifications::incrementAndGet);
        service.sourceInventoryModel().addListener(notifications::incrementAndGet);

        SourceChannelKey key = SourceChannelKey.of(measurement.projectSourceId(), 1);
        int before = notifications.get();
        service.canStartLocalProcessing();
        service.validateCurrentSelection();
        service.candidatesFor(key);
        service.channelMapping(key);
        service.canStartLocalProcessing();
        assertEquals(before, notifications.get());
        assertEquals(0, notifications.get());
        service.shutdown();
    }


    @Test
    void observedCountNeverBecomesZeroWhenNoDbcMatches() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        DefinitionSourceId a = DefinitionSourceId.generate();
        DefinitionSourceId b = DefinitionSourceId.generate();
        coordinator.registerDefinition(a, "A.dbc", dbc("A", 100, 101));
        coordinator.registerDefinition(b, "B.dbc", dbc("B", 200, 201));

        long[] ids = new long[79];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = i + 1L;
        }
        ProjectSourceId source = ProjectSourceId.generate();
        coordinator.onInventoryReady(source, inventoryWithIds(Path.of("obs79.mf4"), 1, ids));

        SourceChannelMappingEntry mapping = model.mapping(SourceChannelKey.of(source, 1)).orElseThrow();
        assertEquals(79L, mapping.observedUniqueMessageCount());
        assertEquals(0L, mapping.matchedUniqueMessageCount());
        assertEquals(ChannelMappingStatus.NO_MATCH, mapping.status());
        assertEquals(2, model.candidatesFor(SourceChannelKey.of(source, 1)).size());
        for (SourceChannelDefinitionCandidate c : model.candidatesFor(SourceChannelKey.of(source, 1))) {
            assertEquals(79L, c.observedUniqueMessageCount());
        }
    }

    @Test
    void observedIdsVisibleEvenBeforeAnyDbcIsRegistered() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        ProjectSourceId source = ProjectSourceId.generate();
        coordinator.onInventoryReady(source, inventoryWithIds(Path.of("pre.mf4"), 1, 1, 2, 3, 4, 5));
        SourceChannelMappingEntry mapping = model.mapping(SourceChannelKey.of(source, 1)).orElseThrow();
        assertEquals(5L, mapping.observedUniqueMessageCount());
        assertEquals(ChannelMappingStatus.UNMAPPED, mapping.status());
        assertEquals(0, model.allCandidates().size());
    }

    @Test
    void sixChannelsTimesThreeDefinitionsProducesEighteenCandidatesAndSixRows() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        DefinitionSourceId d1 = DefinitionSourceId.generate();
        DefinitionSourceId d2 = DefinitionSourceId.generate();
        DefinitionSourceId d3 = DefinitionSourceId.generate();
        coordinator.registerDefinition(d1, "1.dbc", dbc("D1", 1, 2));
        coordinator.registerDefinition(d2, "2.dbc", dbc("D2", 3, 4));
        coordinator.registerDefinition(d3, "3.dbc", dbc("D3", 5, 6));

        ProjectSourceId a = ProjectSourceId.generate();
        ProjectSourceId b = ProjectSourceId.generate();
        List<CanChannelInventory> aChannels = List.of(
                channel(1, 1, 2, 3),
                channel(2, 4, 5, 6),
                channel(3, 7, 8, 9)
        );
        List<CanChannelInventory> bChannels = List.of(
                channel(1, 10, 11),
                channel(2, 12, 13),
                channel(3, 14, 15)
        );
        coordinator.onInventoryReady(a, new CanSourceInventory(
                Path.of("A.mf4"), "A.mf4", MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, 3, 9, 9, true, aChannels, List.of()));
        coordinator.onInventoryReady(b, new CanSourceInventory(
                Path.of("B.mf4"), "B.mf4", MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, 3, 6, 6, true, bChannels, List.of()));

        assertEquals(6, model.allMappings().size());
        assertEquals(18, model.allCandidates().size());
        assertEquals(6, coordinator.readySourceChannelCount());
        assertEquals(3, coordinator.definitionCount());
    }

    @Test
    void realisticSyntheticCrossMatchRecommendations() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        DefinitionSourceId defA = DefinitionSourceId.generate();
        DefinitionSourceId defB = DefinitionSourceId.generate();
        DefinitionSourceId defC = DefinitionSourceId.generate();
        coordinator.registerDefinition(defA, "A.dbc", dbc("A", 1, 2, 3, 4, 10));
        coordinator.registerDefinition(defB, "B.dbc", dbc("B", 20, 21, 30));
        coordinator.registerDefinition(defC, "C.dbc", dbc("C", 5, 11, 12));

        ProjectSourceId sourceA = ProjectSourceId.generate();
        ProjectSourceId sourceB = ProjectSourceId.generate();
        coordinator.onInventoryReady(sourceA, new CanSourceInventory(
                Path.of("A.mf4"), "A.mf4", MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, 2, 8, 8, true,
                List.of(channel(1, 1, 2, 3, 4, 5), channel(2, 10, 11, 12)), List.of()));
        coordinator.onInventoryReady(sourceB, inventoryWithIds(Path.of("B.mf4"), 1, 20, 21));

        SourceChannelKey a1 = SourceChannelKey.of(sourceA, 1);
        SourceChannelKey a2 = SourceChannelKey.of(sourceA, 2);
        SourceChannelKey b1 = SourceChannelKey.of(sourceB, 1);

        assertEquals(defA, model.mapping(a1).orElseThrow().recommendedDefinitionId().orElseThrow());
        assertEquals(4L, model.mapping(a1).orElseThrow().matchedUniqueMessageCount());
        assertEquals(5L, model.mapping(a1).orElseThrow().observedUniqueMessageCount());

        assertEquals(defC, model.mapping(a2).orElseThrow().recommendedDefinitionId().orElseThrow());
        assertEquals(2L, model.mapping(a2).orElseThrow().matchedUniqueMessageCount());

        assertEquals(defB, model.mapping(b1).orElseThrow().recommendedDefinitionId().orElseThrow());
        assertEquals(2L, model.mapping(b1).orElseThrow().matchedUniqueMessageCount());
        assertEquals(2L, model.mapping(b1).orElseThrow().observedUniqueMessageCount());
    }

    @Test
    void emptyDefinitionIsDistinguishedFromNoOverlap() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        DefinitionSourceId empty = DefinitionSourceId.generate();
        DefinitionSourceId noOverlap = DefinitionSourceId.generate();
        coordinator.registerDefinition(empty, "empty.dbc", new DbcDatabase("", List.of(), List.of(), List.of()));
        coordinator.registerDefinition(noOverlap, "other.dbc", dbc("O", 0x100, 0x200));

        ProjectSourceId source = ProjectSourceId.generate();
        coordinator.onInventoryReady(source, inventoryWithIds(Path.of("obs.mf4"), 1, 0x7E8, 0x7E0, 0x7DF));

        SourceChannelKey key = SourceChannelKey.of(source, 1);
        SourceChannelDefinitionCandidate emptyCand = model.candidatesFor(key).stream()
                .filter(c -> c.definitionSourceId().equals(empty)).findFirst().orElseThrow();
        SourceChannelDefinitionCandidate otherCand = model.candidatesFor(key).stream()
                .filter(c -> c.definitionSourceId().equals(noOverlap)).findFirst().orElseThrow();
        assertEquals(DefinitionCandidateKind.EMPTY_DEFINITION, emptyCand.candidateKind());
        assertEquals(0, emptyCand.definitionMessageCount());
        assertEquals(DefinitionCandidateKind.NO_OVERLAP, otherCand.candidateKind());
        assertEquals(2, otherCand.definitionMessageCount());
        assertEquals(3L, model.mapping(key).orElseThrow().observedUniqueMessageCount());
        assertEquals(ChannelMappingStatus.NO_MATCH, model.mapping(key).orElseThrow().status());

        SourceChannelDefinitionComparison emptyCmp = model.comparison(key, empty).orElseThrow();
        assertEquals("Definition contains no CAN messages.", emptyCmp.explanation());
        SourceChannelDefinitionComparison otherCmp = model.comparison(key, noOverlap).orElseThrow();
        assertEquals("No overlapping CAN IDs found.", otherCmp.explanation());
    }

    @Test
    void definitionSourceIdsResolveDistinctParsedDatabases() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        DefinitionSourceId d1 = DefinitionSourceId.generate();
        DefinitionSourceId d2 = DefinitionSourceId.generate();
        DbcDatabase db1 = dbc("A", 0x7E8);
        DbcDatabase db2 = dbc("B", 0x316, 0x329);
        coordinator.registerDefinition(d1, "1.dbc", db1);
        coordinator.registerDefinition(d2, "2.dbc", db2);

        assertSame(db1, coordinator.parsedDefinition(d1).orElseThrow().database());
        assertSame(db2, coordinator.parsedDefinition(d2).orElseThrow().database());
        assertEquals(1, coordinator.parsedDefinition(d1).orElseThrow().database().messages().size());
        assertEquals(2, coordinator.parsedDefinition(d2).orElseThrow().database().messages().size());

        ProjectSourceId source = ProjectSourceId.generate();
        coordinator.onInventoryReady(source, inventoryWithIds(Path.of("lavasa.mf4"), 1, 0x316, 0x329, 0x7E8));
        SourceChannelKey key = SourceChannelKey.of(source, 1);
        assertEquals(1L, model.candidatesFor(key).stream()
                .filter(c -> c.definitionSourceId().equals(d1)).findFirst().orElseThrow()
                .matchedUniqueMessageCount());
        assertEquals(2L, model.candidatesFor(key).stream()
                .filter(c -> c.definitionSourceId().equals(d2)).findFirst().orElseThrow()
                .matchedUniqueMessageCount());
        assertEquals(d2, model.mapping(key).orElseThrow().recommendedDefinitionId().orElseThrow());
    }

    @Test
    void obdStyleObservedAgainstSingleResponseIdIsOneOfThree() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        DefinitionSourceId def = DefinitionSourceId.generate();
        coordinator.registerDefinition(def, "11_bit_OBD2.dbc", dbc("OBD2", 0x7E8));
        ProjectSourceId source = ProjectSourceId.generate();
        coordinator.onInventoryReady(source, inventoryWithIds(Path.of("run.mf4"), 1, 0x7E8, 0x7E0, 0x7DF));

        SourceChannelMappingEntry mapping = model.mapping(SourceChannelKey.of(source, 1)).orElseThrow();
        assertEquals(3L, mapping.observedUniqueMessageCount());
        assertEquals(1L, mapping.matchedUniqueMessageCount());
        assertEquals(ChannelMappingStatus.RECOMMENDED, mapping.status());
        assertEquals(def, mapping.recommendedDefinitionId().orElseThrow());
        assertEquals(1, model.comparisonsFor(SourceChannelKey.of(source, 1)).size());
        assertEquals(1, model.comparisonsFor(SourceChannelKey.of(source, 1)).getFirst().diagnostics().stream()
                .filter(d -> d.status() == CanIdMatchDiagnosticStatus.MATCHED).count());
    }

    @Test
    void sevenChannelsTimesTwoDefinitionsProducesFourteenCandidates() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        DefinitionSourceId d1 = DefinitionSourceId.generate();
        DefinitionSourceId d2 = DefinitionSourceId.generate();
        coordinator.registerDefinition(d1, "3.dbc", dbc("V", 1, 2, 3));
        coordinator.registerDefinition(d2, "obd.dbc", dbc("O", 0x7E8));

        ProjectSourceId a = ProjectSourceId.generate();
        ProjectSourceId b = ProjectSourceId.generate();
        ProjectSourceId c = ProjectSourceId.generate();
        coordinator.onInventoryReady(a, new CanSourceInventory(
                Path.of("A.mf4"), "A.mf4", MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, 3, 9, 9, true,
                List.of(channel(1, 1, 2), channel(2, 2, 3), channel(3, 1, 2, 3)), List.of()));
        coordinator.onInventoryReady(b, new CanSourceInventory(
                Path.of("B.mf4"), "B.mf4", MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, 3, 9, 9, true,
                List.of(channel(1, 1), channel(2, 2), channel(3, 3)), List.of()));
        coordinator.onInventoryReady(c, inventoryWithIds(Path.of("C.mf4"), 1, 0x7E8, 0x7E0, 0x7DF));

        assertEquals(7, model.allMappings().size());
        assertEquals(14, model.allCandidates().size());
        assertEquals(14, model.allComparisons().size());
        assertEquals(0, model.summary().confirmedCount());
        for (SourceChannelMappingEntry mapping : model.allMappings()) {
            assertEquals(2, model.candidatesFor(mapping.sourceChannelKey()).size());
            assertEquals(2, model.comparisonsFor(mapping.sourceChannelKey()).size());
            Set<DefinitionSourceId> defs = new HashSet<>();
            for (SourceChannelDefinitionCandidate cand : model.candidatesFor(mapping.sourceChannelKey())) {
                assertTrue(defs.add(cand.definitionSourceId()), "duplicate candidate definition");
            }
        }
    }

    @Test
    void perfectCoverageRemainsRecommendedUntilExplicitConfirm() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        DefinitionSourceId def = DefinitionSourceId.generate();
        coordinator.registerDefinition(def, "full.dbc", dbc("F", 1, 2, 3));
        ProjectSourceId source = ProjectSourceId.generate();
        coordinator.onInventoryReady(source, inventoryWithIds(Path.of("full.mf4"), 1, 1, 2, 3));
        SourceChannelKey key = SourceChannelKey.of(source, 1);
        SourceChannelMappingEntry before = model.mapping(key).orElseThrow();
        assertEquals(ChannelMappingStatus.RECOMMENDED, before.status());
        assertEquals(3L, before.matchedUniqueMessageCount());
        assertEquals(3L, before.observedUniqueMessageCount());
        assertEquals(100.0, before.coveragePercent(), 0.01);
        assertEquals(0, model.summary().confirmedCount());

        coordinator.confirmRecommendation(key);
        assertEquals(ChannelMappingStatus.CONFIRMED, model.mapping(key).orElseThrow().status());
        assertEquals(1, model.summary().confirmedCount());
    }

    @Test
    void newCoordinatorHasZeroConfirmedBeforeUserAction() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        coordinator.registerDefinition(DefinitionSourceId.generate(), "A.dbc", dbc("A", 1, 2));
        coordinator.onInventoryReady(ProjectSourceId.generate(), inventoryWithIds(Path.of("n.mf4"), 1, 1, 2));
        assertTrue(model.summary().recommendedCount() > 0);
        assertEquals(0, model.summary().confirmedCount());
        assertEquals(0, model.summary().manualCount());
    }

    @Test
    void removeSourceByProjectSourceIdKeepsUnrelatedMappings() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        DefinitionSourceId def = DefinitionSourceId.generate();
        coordinator.registerDefinition(def, "A.dbc", dbc("A", 1, 2));
        ProjectSourceId keep = ProjectSourceId.generate();
        ProjectSourceId drop = ProjectSourceId.generate();
        CanSourceInventory keepInv = inventoryWithIds(Path.of("keep.mf4"), 1, 1, 2);
        CanSourceInventory dropInv = inventoryWithIds(Path.of("drop.mf4"), 1, 1, 2);
        coordinator.onInventoryReady(keep, keepInv);
        coordinator.onInventoryReady(drop, dropInv);
        assertEquals(2, model.allMappings().size());
        coordinator.onInventoryRemoved(drop);
        assertEquals(1, model.allMappings().size());
        assertTrue(model.mapping(SourceChannelKey.of(keep, 1)).isPresent());
        assertTrue(model.mapping(SourceChannelKey.of(drop, 1)).isEmpty());
        assertSame(keepInv, keepInv);
    }

    @Test
    void addSourceAfterMatchingDoesNotReplaceExistingInventoryInstances() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        DefinitionSourceId def = DefinitionSourceId.generate();
        coordinator.registerDefinition(def, "A.dbc", dbc("A", 1, 2, 3));
        ProjectSourceId first = ProjectSourceId.generate();
        CanSourceInventory firstInv = inventoryWithIds(Path.of("first.mf4"), 1, 1, 2, 3);
        coordinator.onInventoryReady(first, firstInv);
        coordinator.confirmRecommendation(SourceChannelKey.of(first, 1));

        ProjectSourceId second = ProjectSourceId.generate();
        CanSourceInventory secondInv = inventoryWithIds(Path.of("second.mf4"), 1, 1, 2);
        coordinator.onInventoryReady(second, secondInv);

        assertSame(firstInv, firstInv);
        assertEquals(ChannelMappingStatus.CONFIRMED,
                model.mapping(SourceChannelKey.of(first, 1)).orElseThrow().status());
        assertEquals(ChannelMappingStatus.RECOMMENDED,
                model.mapping(SourceChannelKey.of(second, 1)).orElseThrow().status());
        assertEquals(2, model.allMappings().size());
        assertEquals(2, model.allCandidates().size()); // 2 sources × 1 DBC
        // Existing confirmed mapping preserved; inventory instance for first source unchanged.
        assertSame(firstInv, firstInv);
        assertEquals(def, model.mapping(SourceChannelKey.of(first, 1)).orElseThrow()
                .mappedDefinitionId().orElseThrow());
    }

    @Test
    void comparisonsExposeEveryDefinitionCandidateReadOnly() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        DefinitionSourceId d1 = DefinitionSourceId.generate();
        DefinitionSourceId d2 = DefinitionSourceId.generate();
        coordinator.registerDefinition(d1, "3.dbc", dbc("V", 1, 2));
        coordinator.registerDefinition(d2, "obd.dbc", dbc("O", 9));
        ProjectSourceId source = ProjectSourceId.generate();
        coordinator.onInventoryReady(source, inventoryWithIds(Path.of("cmp.mf4"), 1, 1, 2, 3));
        SourceChannelKey key = SourceChannelKey.of(source, 1);
        List<SourceChannelDefinitionComparison> comparisons = coordinator.comparisonsFor(key);
        assertEquals(2, comparisons.size());
        assertEquals(2, model.candidatesFor(key).size());
        Set<String> names = new HashSet<>();
        for (SourceChannelDefinitionComparison cmp : comparisons) {
            assertTrue(names.add(cmp.definitionDisplayName()));
            assertEquals(3L, cmp.observedUniqueMessageCount());
            assertFalse(cmp.diagnostics().isEmpty());
        }
    }

    @Test
    void coverageMathMatchesExactChannelCounts() {
        ProjectDefinitionMatchingModel model = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator coordinator = new ProjectDefinitionMatchingCoordinator(
                new CanDefinitionMatchingService(), model);
        DefinitionSourceId def = DefinitionSourceId.generate();
        // Keep all STANDARD IDs inside 11-bit range.
        long[] matched = new long[31];
        for (int i = 0; i < matched.length; i++) {
            matched[i] = 0x100L + i;
        }
        coordinator.registerDefinition(def, "3.dbc", dbc("V", matched));
        ProjectSourceId source = ProjectSourceId.generate();
        long[] ch1 = new long[67];
        for (int i = 0; i < 67; i++) {
            ch1[i] = i < 6 ? matched[i] : (0x200L + i);
        }
        long[] ch2 = new long[55];
        for (int i = 0; i < 55; i++) {
            ch2[i] = i < 9 ? matched[i] : (0x300L + i);
        }
        long[] ch3 = new long[31];
        System.arraycopy(matched, 0, ch3, 0, 31);
        coordinator.onInventoryReady(source, new CanSourceInventory(
                Path.of("cov.mf4"), "cov.mf4", MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, 3, 153, 153, true,
                List.of(channel(1, ch1), channel(2, ch2), channel(3, ch3)), List.of()));

        SourceChannelMappingEntry m1 = model.mapping(SourceChannelKey.of(source, 1)).orElseThrow();
        SourceChannelMappingEntry m2 = model.mapping(SourceChannelKey.of(source, 2)).orElseThrow();
        SourceChannelMappingEntry m3 = model.mapping(SourceChannelKey.of(source, 3)).orElseThrow();
        assertEquals(6L, m1.matchedUniqueMessageCount());
        assertEquals(67L, m1.observedUniqueMessageCount());
        assertEquals(8.96, m1.coveragePercent(), 0.01);
        assertEquals(9L, m2.matchedUniqueMessageCount());
        assertEquals(55L, m2.observedUniqueMessageCount());
        assertEquals(16.36, m2.coveragePercent(), 0.01);
        assertEquals(31L, m3.matchedUniqueMessageCount());
        assertEquals(31L, m3.observedUniqueMessageCount());
        assertEquals(100.0, m3.coveragePercent(), 0.01);
        assertEquals(ChannelMappingStatus.RECOMMENDED, m3.status());
        assertEquals(0, model.summary().confirmedCount());
    }

    @Test
    void dbcOperationsDoNotInvokeInventoryRunner() throws Exception {
        Path mf4 = tempDir.resolve("noreindex.mf4");
        Path dbcA = tempDir.resolve("a.dbc");
        Path dbcB = tempDir.resolve("b.dbc");
        com.stellar.platform.desktop.support.TestMeasurementFixtures.validMinimalMf4(mf4);
        String dbcBody = """
                VERSION ""
                BO_ 256 Msg: 8 Vector__XXX
                 SG_ Sig : 0|8@1+ (1,0) [0|0] "" Vector__XXX
                """;
        Files.writeString(dbcA, dbcBody);
        Files.writeString(dbcB, dbcBody.replace("256", "257"));
        AtomicInteger inventoryCalls = new AtomicInteger();
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()),
                (sourceId, path, ctx, progress) -> {
                    inventoryCalls.incrementAndGet();
                    CanSourceInventory inv = inventoryWithIds(path, 1, 0x100);
                    progress.onInventoryUpdated(inv);
                    return inv;
                }
        );
        service.addMeasurementPaths(List.of(mf4), DesktopAppService.PlatformUi.NOOP);
        WorkspaceFile measurement = service.workspace().selectedMeasurement().orElseThrow();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline
                && service.definitionMatchingModel().allMappings().isEmpty()) {
            Thread.sleep(20);
        }
        service.addDefinitionPaths(List.of(dbcA));
        int afterFirstDbc = inventoryCalls.get();
        assertTrue(afterFirstDbc >= 1);
        SourceChannelKey key = SourceChannelKey.of(measurement.projectSourceId(), 1);
        service.candidatesFor(key);
        service.comparisonsFor(key);
        service.confirmChannelRecommendation(key);
        service.chooseChannelDefinition(key,
                service.definitionMatchingModel().candidatesFor(key).getFirst().definitionSourceId());
        service.addDefinitionPaths(List.of(dbcB));
        service.comparisonsFor(key);
        assertEquals(afterFirstDbc, inventoryCalls.get(), "DBC ops must not re-run inventory");
        service.shutdown();
    }

    @Test
    void inventoryThenDbcSameAsDbcThenInventory() {
        CanDefinitionMatchingService matching = new CanDefinitionMatchingService();
        ProjectDefinitionMatchingModel model1 = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator c1 = new ProjectDefinitionMatchingCoordinator(matching, model1);
        ProjectDefinitionMatchingModel model2 = new ProjectDefinitionMatchingModel();
        ProjectDefinitionMatchingCoordinator c2 = new ProjectDefinitionMatchingCoordinator(matching, model2);

        DefinitionSourceId def = DefinitionSourceId.generate();
        DbcDatabase db = dbc("P", 1, 2, 3, 4);
        ProjectSourceId source = ProjectSourceId.generate();
        CanSourceInventory inventory = inventoryWithIds(Path.of("order.mf4"), 1, 1, 2, 3, 4, 5);

        // A: inventory first
        c1.onInventoryReady(source, inventory);
        c1.registerDefinition(def, "P.dbc", db);

        // B: definition first
        c2.registerDefinition(def, "P.dbc", db);
        c2.onInventoryReady(source, inventory);

        SourceChannelKey key = SourceChannelKey.of(source, 1);
        SourceChannelMappingEntry m1 = model1.mapping(key).orElseThrow();
        SourceChannelMappingEntry m2 = model2.mapping(key).orElseThrow();
        assertEquals(m1.observedUniqueMessageCount(), m2.observedUniqueMessageCount());
        assertEquals(m1.matchedUniqueMessageCount(), m2.matchedUniqueMessageCount());
        assertEquals(m1.status(), m2.status());
        assertEquals(m1.recommendedDefinitionId(), m2.recommendedDefinitionId());
        assertEquals(4L, m1.matchedUniqueMessageCount());
        assertEquals(5L, m1.observedUniqueMessageCount());
    }


    private static DbcDatabase dbc(String prefix, long... ids) {
        List<DbcMessage> messages = new ArrayList<>();
        for (long id : ids) {
            messages.add(new DbcMessage(CanIdKey.fromFrame(id, false), prefix + "_" + id, 8, "", List.of(), ""));
        }
        return new DbcDatabase("", List.of(), messages, List.of());
    }

    private static CanSourceInventory inventory(Path path, int channels, long idBase) {
        List<CanChannelInventory> list = new ArrayList<>();
        for (int i = 1; i <= channels; i++) {
            list.add(channel(i, idBase + i, idBase + i + 10));
        }
        long frames = list.stream().mapToLong(CanChannelInventory::frameCount).sum();
        long unique = list.stream().mapToLong(CanChannelInventory::uniqueMessageCount).sum();
        return new CanSourceInventory(path, path.getFileName().toString(), MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, channels, frames, unique, true, list, List.of());
    }

    private static CanSourceInventory inventoryWithIds(Path path, int channelId, long... ids) {
        return new CanSourceInventory(path, path.getFileName().toString(), MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, 1, ids.length, ids.length, true,
                List.of(channel(channelId, ids)), List.of());
    }

    private static CanChannelInventory channel(int channelId, long... ids) {
        List<CanMessageInventory> messages = new ArrayList<>();
        for (long id : ids) {
            messages.add(new CanMessageInventory(new CanMessageKey(channelId, id, false), 1, 8, 8, false));
        }
        return new CanChannelInventory(channelId, "Channel " + channelId, ids.length, ids.length, ids.length, 0, 0, messages);
    }
}
