package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProjectExecutionStatus;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickDecodeSignalsViewAssemblerTest {

    @Test
    void combinesSuccessfulChannelsWithStableSourceAndDefinitionNamespaces() {
        InMemorySignalCatalogStore ch1 = store("same-name.mf4", "veh.dbc", 1, 10L);
        InMemorySignalCatalogStore ch2 = store("same-name.mf4", "veh.dbc", 2, 20L);
        MultiSourceProcessingResult batch = new MultiSourceProcessingResult(
                "plan", Instant.now(), ProjectExecutionStatus.SUCCESS,
                List.of(
                        result("src-A", 1, "def-A", "veh.dbc", ch1),
                        result("src-A", 2, "def-A", "veh.dbc", ch2)
                ), List.of(), Duration.ofMillis(10));

        var view = new QuickDecodeSignalsViewAssembler()
                .assemble(batch, id -> "Road Test A")
                .orElseThrow();

        assertEquals(2, view.successfulChannels());
        assertEquals(2, view.store().snapshot().size());
        assertEquals("Road Test A", view.context().sourceFile());
        assertEquals("veh.dbc", view.context().dbcFile());
        assertEquals("2 Channels", view.context().channelsSummary());
        assertEquals(SignalsProcessingContext.MODE_QUICK_DECODE, view.context().mode());
        assertEquals(SignalViewScope.PROJECT, view.context().viewScope());
        assertTrue(view.hint().contains("Auto Decode"));
    }

    @Test
    void projectWideMergeCollapsesEquivalentSignalsAcrossSources() {
        InMemorySignalCatalogStore a = rpmStore("a.mf4", "veh.dbc", List.of(1L, 3L, 5L));
        InMemorySignalCatalogStore b = rpmStore("b.mf4", "veh.dbc", List.of(2L, 4L, 6L));
        MultiSourceProcessingResult batch = new MultiSourceProcessingResult(
                "plan", Instant.now(), ProjectExecutionStatus.SUCCESS,
                List.of(
                        result("src-A", 1, "def-A", "veh.dbc", a),
                        result("src-B", 1, "def-A", "veh.dbc", b)
                ), List.of(), Duration.ofMillis(10));

        var view = new QuickDecodeSignalsViewAssembler()
                .assembleResults(batch.jobResults(), id -> id.equals("src-A") ? "Source A" : "Source B")
                .orElseThrow();

        assertEquals(SignalViewScope.PROJECT, view.context().viewScope());
        assertEquals(1, view.store().snapshot().size());
        assertEquals("EngineRPM", view.store().snapshot().entries().getFirst().signalName());
        List<Long> timestamps = view.store()
                .querySamples(view.store().snapshot().entries().getFirst().key(), 0, 20)
                .stream()
                .map(p -> p.timestampNanos())
                .toList();
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), timestamps);
    }

    @Test
    void sourceViewIncludesAllChannelsAndOnlyTheRequestedSource() {
        InMemorySignalCatalogStore a1 = store("drive-a.mf4", "body.dbc", 1, 10L);
        InMemorySignalCatalogStore a2 = store("drive-a.mf4", "powertrain.dbc", 2, 20L);
        InMemorySignalCatalogStore b1 = store("drive-b.mf4", "body.dbc", 1, 30L);
        MultiSourceProcessingResult batch = new MultiSourceProcessingResult(
                "plan", Instant.now(), ProjectExecutionStatus.SUCCESS,
                List.of(
                        result("src-A", 1, "def-body", "body.dbc", a1),
                        result("src-A", 2, "def-power", "powertrain.dbc", a2),
                        result("src-B", 1, "def-body", "body.dbc", b1)
                ), List.of(), Duration.ofMillis(10));

        var view = new QuickDecodeSignalsViewAssembler()
                .assembleForSource(batch, "src-A", id -> id.equals("src-A") ? "Drive A" : "Drive B")
                .orElseThrow();

        assertEquals(2, view.successfulChannels());
        assertEquals(2, view.store().snapshot().size());
        assertEquals(SignalViewScope.SOURCE, view.context().viewScope());
        assertEquals("Drive A", view.context().sourceFile());
        assertEquals("2 DBCs", view.context().dbcFile());
        assertEquals("2 Channels", view.context().channelsSummary());
        assertTrue(view.hint().contains("Drive A"));
        assertTrue(view.hint().contains("2 DBCs"));
    }

    @Test
    void decodedChannelPlusNativeChannelReportsOnlyTheAppliedDbc() {
        InMemorySignalCatalogStore decoded = store("drive.mf4", "11_bit_OBD2 2.dbc", 1, 10L);
        InMemorySignalCatalogStore nativeOnly = new InMemorySignalCatalogStore("drive.mf4", "", 50);
        nativeOnly.accept(List.of(new CanonicalSignal(
                "Battery", InMemorySignalCatalogStore.MF4_MEASUREMENT_MESSAGE, 10L, 0L, 2, 1.0, null, "V", null,
                SourceReference.ofLine(1))));
        MultiSourceProcessingResult batch = new MultiSourceProcessingResult(
                "plan", Instant.now(), ProjectExecutionStatus.SUCCESS,
                List.of(
                        result("src-A", 1, "def-obd", "11_bit_OBD2 2.dbc", decoded),
                        result("src-A", 2, null, "", nativeOnly)
                ), List.of(), Duration.ofMillis(10));

        var view = new QuickDecodeSignalsViewAssembler()
                .assemble(batch, id -> "drive.mf4")
                .orElseThrow();

        assertEquals("11_bit_OBD2 2.dbc", view.context().dbcFile());
        assertEquals("2 Channels", view.context().channelsSummary());
    }

    @Test
    void nativeOnlySuccessfulResultDoesNotRequireADefinitionId() {
        InMemorySignalCatalogStore nativeStore = store("drive.mf4", "", 1, 10L);
        MultiSourceProcessingResult batch = new MultiSourceProcessingResult(
                "plan", Instant.now(), ProjectExecutionStatus.SUCCESS,
                List.of(result("src-A", 1, null, "", nativeStore)),
                List.of(), Duration.ofMillis(10));

        var view = new QuickDecodeSignalsViewAssembler()
                .assemble(batch, id -> "Lavasa")
                .orElseThrow();

        assertEquals(1, view.successfulChannels());
        assertEquals("Native only", view.context().dbcFile());
        assertTrue(view.hint().contains("Native only"));
        assertEquals(1, view.store().snapshot().size());
    }

    private static InMemorySignalCatalogStore store(String source, String dbc, int channel, long ts) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(source, dbc, 50);
        store.accept(List.of(new CanonicalSignal(
                "VehicleSpeed", "VehicleState", ts, 0x123, channel, 42.0, null, "km/h", null,
                SourceReference.ofLine(1))));
        return store;
    }

    private static InMemorySignalCatalogStore rpmStore(String source, String dbc, List<Long> timestamps) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(source, dbc, 50);
        store.accept(timestamps.stream()
                .map(ts -> new CanonicalSignal(
                        "EngineRPM", "OBD2", ts, 0x7E8, 1, ts.doubleValue(), ts, "rpm", null,
                        SourceReference.ofLine(1)))
                .toList());
        return store;
    }

    private static IndependentJobResult result(
            String sourceId, int channel, String definitionId, String definitionName,
            InMemorySignalCatalogStore store) {
        return new IndependentJobResult(
                ProcessingJobKey.of(sourceId, channel),
                definitionId,
                definitionName,
                MappingLifecycle.RECOMMENDED_FALLBACK,
                JobExecutionStatus.SUCCESS,
                100, 100, 100, 90, 10, 1,
                1L, 2L, null, Duration.ofMillis(5), List.of(), "", store);
    }
}
