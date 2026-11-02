package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.CompositeSignalCatalogStore;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.map.GpsMappingScope;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MeasurementCatalogScopeTest {

    @Test
    void mergedStoreSelectorListsFilesInsteadOfAggregateLabel() {
        InMemorySignalCatalogStore a = new InMemorySignalCatalogStore("MF4_A.mf4", "veh.dbc", 20);
        a.accept(List.of(can("VehicleSpeed", "Chassis", 0x200, 1, 1_000L, 10)));
        InMemorySignalCatalogStore b = new InMemorySignalCatalogStore("MF4_B.mf4", "veh.dbc", 20);
        b.accept(List.of(can("VehicleSpeed", "Chassis", 0x200, 1, 2_000L, 11)));
        ProjectMergedSignalStore store = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(a, "src-A", "veh.dbc", "MF4_A.mf4", "veh.dbc"),
                new ProjectMergedSignalStore.Member(b, "src-B", "veh.dbc", "MF4_B.mf4", "veh.dbc")
        ), "2 measurements", "veh.dbc");

        List<MeasurementCatalogScope.Ref> refs = MeasurementCatalogScope.measurements(
                store.snapshot().entries(), store);
        assertEquals(2, refs.size());
        assertEquals("All Measurements (2)", MeasurementCatalogScope.allMeasurementsLabel(refs.size()));
        assertTrue(refs.stream().anyMatch(ref -> "MF4_A.mf4".equals(ref.displayName()) && "src-A".equals(ref.identity())));
        assertTrue(refs.stream().anyMatch(ref -> "MF4_B.mf4".equals(ref.displayName())));
        assertTrue(refs.stream().noneMatch(ref -> "2 measurements".equals(ref.displayName())));
        assertEquals(1, MeasurementCatalogScope.measurements(store.snapshot().entries()).size(),
                "logical PROJECT rows still collapse; the store-aware selector must not");
    }

    @Test
    void fourMf4RecordingsRemainDistinctPhysicalKeys() {
        var store = fourFileStore();
        List<SignalCatalogEntry> catalog = store.snapshot().entries();
        assertEquals(4, MeasurementCatalogScope.measurementCount(catalog));
        assertEquals(4, MeasurementCatalogScope.measurements(catalog).size());

        List<SignalCatalogKey> speedKeys = catalog.stream()
                .filter(e -> e.signalName().equals("VehicleSpeed"))
                .map(SignalCatalogEntry::key)
                .toList();
        assertEquals(8, speedKeys.size());
        assertEquals(8, Set.copyOf(speedKeys).size());
        assertEquals(Set.of("src-A", "src-B", "src-C", "src-D"),
                Set.copyOf(speedKeys.stream().map(SignalCatalogKey::sourceFile).toList()));
    }

    @Test
    void allMeasurementsCaptionDoesNotCallPhysicalEntriesUniqueSignals() {
        String caption = MeasurementCatalogScope.countCaption(139, 205, 4, true);
        assertTrue(caption.contains("139 shown"));
        assertTrue(caption.contains("205 signals across 4 measurements"));
        assertFalse(caption.toLowerCase().contains("unique signals"));
    }

    @Test
    void filteringByMeasurementADoesNotIncludeB() {
        List<SignalCatalogEntry> catalog = fourFileStore().snapshot().entries();
        List<SignalCatalogEntry> a = MeasurementCatalogScope.filter(catalog, "src-A", "MF4_A.mf4");
        List<SignalCatalogEntry> b = MeasurementCatalogScope.filter(catalog, "src-B", "MF4_B.mf4");
        assertTrue(a.stream().allMatch(e -> "src-A".equals(e.key().sourceFile())));
        assertTrue(b.stream().allMatch(e -> "src-B".equals(e.key().sourceFile())));
        assertTrue(a.stream().noneMatch(e -> e.signalName().equals("UniqueB")));
        assertTrue(b.stream().noneMatch(e -> e.signalName().equals("UniqueA")));
    }

    @Test
    void hideDuplicatesDoesNotCollapseTheSameNameAcrossMeasurements() {
        List<SignalCatalogEntry> catalog = fourFileStore().snapshot().entries();
        int physical = catalog.size();
        int unique = SignalBrowserProjection.uniqueExactNameCount(catalog);
        assertEquals(8, catalog.stream().filter(e -> e.signalName().equals("VehicleSpeed")).count());
        assertEquals(4, SignalBrowserProjection.apply(catalog, SignalBrowserViewMode.UNIQUE_SIGNAL_NAMES)
                .stream().filter(e -> e.signalName().equals("VehicleSpeed")).count());
        assertTrue(unique < physical, "Hide Duplicates may collapse within one measurement");
        assertEquals(4, MeasurementCatalogScope.measurementCount(
                SignalBrowserProjection.apply(catalog, SignalBrowserViewMode.UNIQUE_SIGNAL_NAMES)));
    }

    @Test
    void coverageListsEachMeasurementWithoutQueryingSamples() {
        List<SignalCatalogEntry> catalog = fourFileStore().snapshot().entries();
        SignalCatalogEntry speedA = catalog.stream()
                .filter(e -> e.signalName().equals("VehicleSpeed") && "src-A".equals(e.key().sourceFile()))
                .findFirst().orElseThrow();
        List<MeasurementCatalogScope.CoverageRow> coverage = MeasurementCatalogScope.coverage(speedA, catalog);
        assertEquals(4, coverage.size());
        assertEquals(List.of("MF4_A.mf4", "MF4_B.mf4", "MF4_C.mf4", "MF4_D.mf4"),
                coverage.stream().map(MeasurementCatalogScope.CoverageRow::displayName).toList());
    }

    @Test
    void samplesStayMeasurementScopedInTheCompositeStore() {
        var store = fourFileStore();
        SignalCatalogKey speedA = store.snapshot().entries().stream()
                .filter(e -> e.signalName().equals("VehicleSpeed") && "src-A".equals(e.key().sourceFile()))
                .findFirst().orElseThrow().key();
        SignalCatalogKey speedB = store.snapshot().entries().stream()
                .filter(e -> e.signalName().equals("VehicleSpeed") && "src-B".equals(e.key().sourceFile()))
                .findFirst().orElseThrow().key();
        List<Long> a = store.querySamples(speedA, 0, 20).stream().map(SignalSamplePoint::timestampNanos).toList();
        List<Long> b = store.querySamples(speedB, 0, 20).stream().map(SignalSamplePoint::timestampNanos).toList();
        assertFalse(a.isEmpty());
        assertFalse(b.isEmpty());
        assertTrue(a.stream().noneMatch(b::contains));
    }

    @Test
    void gpsCatalogForBDoesNotIncludeACoordinates() {
        List<SignalCatalogEntry> catalog = fourFileStore().snapshot().entries();
        List<String> names = GpsMappingScope.catalogFor("src-B", catalog).stream()
                .map(SignalCatalogEntry::signalName)
                .toList();
        assertTrue(names.contains("Latitude"));
        assertTrue(names.contains("VehicleSpeed"));
        assertFalse(names.contains("UniqueA"));
        assertTrue(names.stream().noneMatch(n -> n.equals("UniqueC") || n.equals("UniqueD") || n.equals("UniqueA")));
        assertTrue(MeasurementCatalogScope.filter(catalog, "src-B", "MF4_B.mf4").stream()
                .allMatch(e -> "src-B".equals(e.key().sourceFile())));
    }

    public static CompositeSignalCatalogStore fourFileStore() {
        return CompositeSignalCatalogStore.fromMembers(List.of(
                member("src-A", "MF4_A.mf4", 1_000L, "UniqueA"),
                member("src-B", "MF4_B.mf4", 10_000L, "UniqueB"),
                member("src-C", "MF4_C.mf4", 100_000L, "UniqueC"),
                member("src-D", "MF4_D.mf4", 1_000_000L, "UniqueD")
        ), "4 measurements", "veh.dbc");
    }

    private static CompositeSignalCatalogStore.Member member(
            String identity, String display, long t0, String unique
    ) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(display, "veh.dbc", 50);
        store.accept(List.of(
                can("VehicleSpeed", "Chassis", 0x200, 1, t0, 40.0 + identity.hashCode() % 7),
                can("VehicleSpeed", "Chassis", 0x200, 2, t0 * 2, 50.0),
                can("EngineRPM", "Engine", 0x100, 1, t0, 800.0),
                nativeSig("Latitude", t0, 12.9),
                nativeSig("Longitude", t0, 77.5),
                can(unique, "Debug", 0x300, 1, t0, 1.0)
        ));
        return new CompositeSignalCatalogStore.Member(store, identity, "def-1", display, "veh.dbc");
    }

    @Test
    void mergedVisibleScopeReconcilesLogicalAndPhysicalWithoutArbitrarySource() {
        InMemorySignalCatalogStore fileA = new InMemorySignalCatalogStore("MF4_A.mf4", "veh.dbc", 20);
        fileA.accept(List.of(can("EngineSpeed", "OBD2", 0x100, 1, 1_000L, 10)));
        InMemorySignalCatalogStore fileB = new InMemorySignalCatalogStore("MF4_B.mf4", "veh.dbc", 20);
        fileB.accept(List.of(can("EngineSpeed", "OBD2", 0x100, 1, 2_000L, 11)));
        ProjectMergedSignalStore store = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(fileB, "src-B", "veh.dbc", "MF4_B.mf4", "veh.dbc"),
                new ProjectMergedSignalStore.Member(fileA, "src-A", "veh.dbc", "MF4_A.mf4", "veh.dbc")
        ), "2 measurements", "veh.dbc");

        List<SignalCatalogEntry> logical = store.snapshot().entries();
        assertEquals(1, logical.size());
        SignalCatalogKey project = logical.getFirst().key();
        assertEquals(ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE, project.sourceFile());
        SignalCatalogKey keyA = store.physicalCatalog("src-A", "MF4_A.mf4").getFirst().key();
        SignalCatalogKey keyB = store.physicalCatalog("src-B", "MF4_B.mf4").getFirst().key();
        assertEquals(2, store.physicalCatalogAll().size());
        assertEquals(project, ProjectMergedSignalStore.logicalKey(keyA, "veh.dbc", "km/h"));
        assertEquals(project, ProjectMergedSignalStore.logicalKey(keyB, "veh.dbc", "km/h"));

        assertEquals(project, MeasurementCatalogScope.reconcileVisibleScope(keyA, logical, true).key());
        assertEquals(project, MeasurementCatalogScope.reconcileVisibleScope(keyB, logical, true).key());
        java.util.ArrayList<SignalCatalogEntry> reversed = new java.util.ArrayList<>(logical);
        java.util.Collections.reverse(reversed);
        assertEquals(project, MeasurementCatalogScope.reconcileVisibleScope(keyA, reversed, true).key());

        assertEquals(keyA, MeasurementCatalogScope.reconcileVisibleScope(
                project, store.physicalCatalog("src-A", "MF4_A.mf4"), true).key());
        assertEquals(keyB, MeasurementCatalogScope.reconcileVisibleScope(
                keyA, store.physicalCatalog("src-B", "MF4_B.mf4"), true).key());
        assertEquals(keyA, MeasurementCatalogScope.reconcileVisibleScope(
                keyB, store.physicalCatalog("src-A", "MF4_A.mf4"), true).key());

        java.util.ArrayList<SignalCatalogEntry> both = new java.util.ArrayList<>(store.physicalCatalogAll());
        assertEquals(keyA, MeasurementCatalogScope.reconcileVisibleScope(keyA, both, true).key());
        assertNull(MeasurementCatalogScope.reconcileVisibleScope(project, both, true));
        java.util.Collections.reverse(both);
        assertNull(MeasurementCatalogScope.reconcileVisibleScope(project, both, true));
        assertNull(MeasurementCatalogScope.reconcileVisibleScope(
                keyA, store.physicalCatalog("src-B", "MF4_B.mf4"), false));
        assertTrue(AnalysisMeasurementResolver.remapToCatalog(
                List.of(keyA), store.physicalCatalog("src-B", "MF4_B.mf4")).isEmpty());
    }

    private static CanonicalSignal can(
            String name, String message, long canId, int channel, long ts, double value) {
        return new CanonicalSignal(
                name, message, ts, canId, channel, value, 1L, "km/h", null, SourceReference.ofLine(1));
    }

    private static CanonicalSignal nativeSig(String name, long ts, double value) {
        return new CanonicalSignal(
                name, InMemorySignalCatalogStore.MF4_MEASUREMENT_MESSAGE, ts, 0L, 0, value, 1L,
                "deg", null, SourceReference.ofLine(1));
    }
}

class AnalysisMeasurementResolverTest {

    @Test
    void aggregateMeasurementLabelDoesNotBindTheActiveFile() {
        InMemorySignalCatalogStore a = new InMemorySignalCatalogStore("MF4_A.mf4", "veh.dbc", 20);
        a.accept(List.of(new CanonicalSignal(
                "VehicleSpeed", "Chassis", 1_000L, 0x200, 1, 10, 1L, "km/h", null, SourceReference.ofLine(1))));
        ProjectMergedSignalStore store = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(a, "src-A", "veh.dbc", "MF4_A.mf4", "veh.dbc")
        ), "9 measurements", "veh.dbc");
        SignalCatalogKey logical = store.snapshot().entries().getFirst().key();
        WorkspaceFile unrelated = WorkspaceFile.restore(
                "other", Path.of("other.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 1);
        WorkspaceFile real = WorkspaceFile.restore(
                "src-A", Path.of("MF4_A.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 1);

        AnalysisMeasurementResolver.Resolution resolved = AnalysisMeasurementResolver.resolve(
                List.of(logical),
                store.snapshot().entries(),
                Optional.of(unrelated),
                List.of(unrelated, real),
                new MeasurementCatalogScope.Ref("PROJECT", "9 measurements", false, 1),
                store);

        assertFalse(resolved.canAnalyze());
        assertNotEquals("other", resolved.measurementId());
    }

    @Test
    void uniqueKeysSharedAcrossFilesAnalyzeOnAnOwningMeasurement() {
        InMemorySignalCatalogStore a = new InMemorySignalCatalogStore("MF4_A.mf4", "veh.dbc", 20);
        a.accept(List.of(
                new CanonicalSignal("VehicleSpeed", "Chassis", 1_000L, 0x200, 1, 10, 1L, "km/h",
                        null, SourceReference.ofLine(1)),
                new CanonicalSignal("EngineRPM", "Engine", 1_000L, 0x100, 1, 800, 1L, "rpm",
                        null, SourceReference.ofLine(1))));
        InMemorySignalCatalogStore b = new InMemorySignalCatalogStore("MF4_B.mf4", "veh.dbc", 20);
        b.accept(List.of(
                new CanonicalSignal("VehicleSpeed", "Chassis", 2_000L, 0x200, 1, 20, 1L, "km/h",
                        null, SourceReference.ofLine(1)),
                new CanonicalSignal("EngineRPM", "Engine", 2_000L, 0x100, 1, 900, 1L, "rpm",
                        null, SourceReference.ofLine(1))));
        ProjectMergedSignalStore store = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(a, "src-A", "veh.dbc", "MF4_A.mf4", "veh.dbc"),
                new ProjectMergedSignalStore.Member(b, "src-B", "veh.dbc", "MF4_B.mf4", "veh.dbc")
        ), "2 measurements", "veh.dbc");
        List<SignalCatalogKey> keys = store.snapshot().entries().stream()
                .filter(e -> e.signalName().equals("VehicleSpeed") || e.signalName().equals("EngineRPM"))
                .map(SignalCatalogEntry::key)
                .toList();
        WorkspaceFile empty = WorkspaceFile.restore(
                "empty", Path.of("empty.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 1);
        WorkspaceFile fileA = WorkspaceFile.restore(
                "src-A", Path.of("MF4_A.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 1);
        WorkspaceFile fileB = WorkspaceFile.restore(
                "src-B", Path.of("MF4_B.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 1);

        AnalysisMeasurementResolver.Resolution resolved = AnalysisMeasurementResolver.resolve(
                keys,
                store.snapshot().entries(),
                Optional.of(empty),
                List.of(empty, fileA, fileB),
                MeasurementCatalogScope.Ref.all(2),
                store);

        assertTrue(resolved.canAnalyze(), resolved.explanation());
        assertTrue(resolved.measurementId().equals("src-A") || resolved.measurementId().equals("src-B"),
                resolved.measurementId());
        assertFalse(resolved.explanation().isBlank());
    }

    @Test
    void mixedSelectionDoesNotSilentlyKeepBothMeasurements() {
        var store = MeasurementCatalogScopeTest.fourFileStore();
        List<SignalCatalogEntry> catalog = store.snapshot().entries();
        SignalCatalogKey speedA = catalog.stream()
                .filter(e -> e.signalName().equals("VehicleSpeed") && "src-A".equals(e.key().sourceFile()))
                .findFirst().orElseThrow().key();
        SignalCatalogKey uniqueB = catalog.stream()
                .filter(e -> e.signalName().equals("UniqueB"))
                .findFirst().orElseThrow().key();
        WorkspaceFile fileA = WorkspaceFile.restore("src-A", Path.of("MF4_A.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 1);
        WorkspaceFile fileB = WorkspaceFile.restore("src-B", Path.of("MF4_B.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 1);

        AnalysisMeasurementResolver.Resolution resolved = AnalysisMeasurementResolver.resolve(
                List.of(speedA, uniqueB), catalog, Optional.of(fileA), List.of(fileA, fileB));

        assertTrue(resolved.ambiguous());
        assertFalse(resolved.canAnalyze());
        assertTrue(resolved.measurementId().isBlank());
    }

    @Test
    void specificBrowserMeasurementKeepsThatFileAndDropsForeignKeys() {
        var store = MeasurementCatalogScopeTest.fourFileStore();
        List<SignalCatalogEntry> catalog = store.snapshot().entries();
        SignalCatalogKey speedA = catalog.stream()
                .filter(e -> e.signalName().equals("VehicleSpeed") && "src-A".equals(e.key().sourceFile()))
                .findFirst().orElseThrow().key();
        SignalCatalogKey uniqueB = catalog.stream()
                .filter(e -> e.signalName().equals("UniqueB"))
                .findFirst().orElseThrow().key();
        WorkspaceFile fileA = WorkspaceFile.restore("src-A", Path.of("MF4_A.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 1);
        WorkspaceFile fileB = WorkspaceFile.restore("src-B", Path.of("MF4_B.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 1);

        AnalysisMeasurementResolver.Resolution resolved = AnalysisMeasurementResolver.resolve(
                List.of(speedA, uniqueB),
                catalog,
                Optional.of(fileA),
                List.of(fileA, fileB),
                new MeasurementCatalogScope.Ref("src-A", "MF4_A.mf4", false, 2));

        assertFalse(resolved.ambiguous());
        assertEquals("src-A", resolved.measurementId());
        assertTrue(resolved.keys().stream().allMatch(k -> "src-A".equals(k.sourceFile())));
        assertTrue(resolved.droppedCrossMeasurementKeys());
        assertTrue(resolved.keys().stream().noneMatch(k -> "UniqueB".equals(k.signalName())));
    }

    @Test
    void overlappingDisplayNameCanBeResolvedOntoTheActiveMeasurementWithoutMixingSamples() {
        var store = MeasurementCatalogScopeTest.fourFileStore();
        List<SignalCatalogEntry> catalog = store.snapshot().entries();
        SignalCatalogKey a = catalog.stream()
                .filter(e -> e.signalName().equals("VehicleSpeed") && "src-A".equals(e.key().sourceFile()))
                .findFirst().orElseThrow().key();
        SignalCatalogKey b = catalog.stream()
                .filter(e -> e.signalName().equals("VehicleSpeed") && "src-B".equals(e.key().sourceFile())
                        && e.channel() == a.channel())
                .findFirst().orElseThrow().key();
        WorkspaceFile fileA = WorkspaceFile.restore("src-A", Path.of("MF4_A.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 1);
        WorkspaceFile fileB = WorkspaceFile.restore("src-B", Path.of("MF4_B.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 1);

        AnalysisMeasurementResolver.Resolution resolved = AnalysisMeasurementResolver.resolve(
                List.of(a, b), catalog, Optional.of(fileA), List.of(fileA, fileB));

        assertTrue(resolved.ambiguous(), "All Measurements + A+B must not silently analyze as one file");
        assertFalse(resolved.canAnalyze());
    }

    @Test
    void uuidAndFileNameAliasesOfTheSameMeasurementCanAnalyze() {
        var store = MeasurementCatalogScopeTest.fourFileStore();
        List<SignalCatalogEntry> catalog = store.snapshot().entries();
        SignalCatalogKey byId = catalog.stream()
                .filter(e -> e.signalName().equals("VehicleSpeed") && "src-A".equals(e.key().sourceFile()))
                .findFirst().orElseThrow().key();
        SignalCatalogKey byName = new SignalCatalogKey(
                byId.signalName(), byId.sourceType(), byId.messageName(),
                byId.canId(), byId.channel(), "MF4_A.mf4", byId.sourceDatabase());
        WorkspaceFile fileA = WorkspaceFile.restore("src-A", Path.of("MF4_A.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 1);
        WorkspaceFile fileB = WorkspaceFile.restore("src-B", Path.of("MF4_B.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 1);

        AnalysisMeasurementResolver.Resolution resolved = AnalysisMeasurementResolver.resolve(
                List.of(byId, byName), catalog, Optional.of(fileB), List.of(fileA, fileB));

        assertTrue(resolved.canAnalyze(), "same-file UUID + file name must not disable Analyze");
        assertEquals("src-A", resolved.measurementId());
    }

    @Test
    void projectScopeKeyWithPhysicalKeyOnOneCatalogMeasurementCanAnalyze() {
        var store = MeasurementCatalogScopeTest.fourFileStore();
        List<SignalCatalogEntry> catalog = store.snapshot().entries().stream()
                .filter(e -> "src-A".equals(e.key().sourceFile()))
                .toList();
        SignalCatalogKey physical = catalog.stream()
                .filter(e -> e.signalName().equals("VehicleSpeed"))
                .findFirst().orElseThrow().key();
        SignalCatalogKey project = new SignalCatalogKey(
                physical.signalName(), physical.sourceType(), physical.messageName(),
                physical.canId(), physical.channel(),
                com.stellar.platform.processing.catalog.ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE,
                physical.sourceDatabase());
        WorkspaceFile fileA = WorkspaceFile.restore("src-A", Path.of("MF4_A.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 1);
        WorkspaceFile fileB = WorkspaceFile.restore("src-B", Path.of("MF4_B.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 1);

        AnalysisMeasurementResolver.Resolution resolved = AnalysisMeasurementResolver.resolve(
                List.of(physical, project), catalog, Optional.of(fileB), List.of(fileA, fileB),
                MeasurementCatalogScope.Ref.all(1));

        assertTrue(resolved.canAnalyze(), "All Measurements + 1 catalog measurement must keep Analyze enabled");
        assertEquals("src-A", resolved.measurementId());
    }

    @Test
    void unambiguousSingleMeasurementIsReused() {
        var store = MeasurementCatalogScopeTest.fourFileStore();
        List<SignalCatalogEntry> catalog = store.snapshot().entries();
        SignalCatalogKey b = catalog.stream()
                .filter(e -> e.signalName().equals("VehicleSpeed") && "src-B".equals(e.key().sourceFile()))
                .findFirst().orElseThrow().key();
        WorkspaceFile fileA = WorkspaceFile.restore("src-A", Path.of("MF4_A.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 1);
        WorkspaceFile fileB = WorkspaceFile.restore("src-B", Path.of("MF4_B.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 1);

        AnalysisMeasurementResolver.Resolution resolved = AnalysisMeasurementResolver.resolve(
                List.of(b), catalog, Optional.of(fileA), List.of(fileA, fileB));

        assertEquals("src-B", resolved.measurementId());
        assertEquals(List.of(b), resolved.keys());
        assertFalse(resolved.droppedCrossMeasurementKeys());
    }

    @Test
    void remapDoesNotSubstituteSameNameAcrossMeasurements() {
        var store = MeasurementCatalogScopeTest.fourFileStore();
        List<SignalCatalogEntry> catalog = store.snapshot().entries();
        SignalCatalogKey a = catalog.stream()
                .filter(e -> e.signalName().equals("VehicleSpeed") && "src-A".equals(e.key().sourceFile()))
                .findFirst().orElseThrow().key();
        List<SignalCatalogEntry> scopedB = MeasurementCatalogScope.filter(catalog, "src-B", "MF4_B.mf4");
        List<SignalCatalogKey> remapped = AnalysisMeasurementResolver.remapToCatalog(List.of(a), scopedB);
        assertTrue(remapped.isEmpty(), "A/VehicleSpeed must not become B/VehicleSpeed");
    }

    @Test
    void remapPreservesExactPhysicalKeyInAllCatalog() {
        var store = MeasurementCatalogScopeTest.fourFileStore();
        List<SignalCatalogEntry> catalog = store.snapshot().entries();
        SignalCatalogKey a = catalog.stream()
                .filter(e -> e.signalName().equals("VehicleSpeed") && "src-A".equals(e.key().sourceFile()))
                .findFirst().orElseThrow().key();
        List<SignalCatalogKey> remapped = AnalysisMeasurementResolver.remapToCatalog(List.of(a), catalog);
        assertEquals(List.of(a), remapped);
        assertEquals(1, remapped.stream().filter(a::equals).count());
    }

    @Test
    void remapDoesNotCollapseTwoSameNameKeysInAllCatalog() {
        var store = MeasurementCatalogScopeTest.fourFileStore();
        List<SignalCatalogEntry> catalog = store.snapshot().entries();
        SignalCatalogKey physical = catalog.stream()
                .filter(e -> e.signalName().equals("VehicleSpeed") && e.channel() == 1)
                .findFirst().orElseThrow().key();
        SignalCatalogKey projectLogical = new SignalCatalogKey(
                physical.signalName(),
                physical.sourceType(),
                physical.messageName(),
                physical.canId(),
                physical.channel(),
                com.stellar.platform.processing.catalog.ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE,
                physical.sourceDatabase());
        assertTrue(AnalysisMeasurementResolver.remapToCatalog(List.of(projectLogical), catalog).isEmpty());
    }
}
