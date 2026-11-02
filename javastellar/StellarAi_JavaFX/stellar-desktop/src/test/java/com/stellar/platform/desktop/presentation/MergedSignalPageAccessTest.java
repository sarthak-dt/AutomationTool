package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * loadWorkspaceAlignedAsync must be able to page a merged logical signal.
 * This executes SignalsScreenModel.querySinglePage on the production classes.
 */
class MergedSignalPageAccessTest {

    private final SignalsScreenModel model = new SignalsScreenModel();

    @AfterEach
    void tearDown() {
        model.shutdown();
    }

    @Test
    void allMeasurementsAndOneMeasurementPageTheSameLogicalSignal() throws Exception {
        InMemorySignalCatalogStore fileA = store("file-a.dat", 1_000L, 11.0);
        InMemorySignalCatalogStore fileB = store("file-b.dat", 2_000L, 22.0);
        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(fileA, "src-A", "dbc", "A.dat", "veh.dbc"),
                new ProjectMergedSignalStore.Member(fileB, "src-B", "dbc", "B.dat", "veh.dbc")
        ), "project", "dbc");
        assertEquals(1, merged.snapshot().size());
        SignalCatalogKey logical = merged.snapshot().entries().getFirst().key();
        assertEquals(ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE, logical.sourceFile());
        assertEquals(2, merged.physicalRouteCount(logical));

        model.replaceCatalog(merged, "Explore",
                SignalsProcessingContext.quickDecode("2 Sources", "veh.dbc", "2 Channels"));
        assertTrue(model.addToWorkspace(logical));

        model.setBrowserFilter(SignalBrowserFilter.State.none());
        SignalSamplePageSnapshot all = pageThroughWorkspaceLoad(merged, logical);
        assertEquals(List.of(11.0, 22.0), values(all));

        model.setBrowserFilter(new SignalBrowserFilter.State(
                SignalBrowserFilter.SourceFilter.ALL, "", "", "", false, false, "src-A", ""));
        SignalSamplePageSnapshot one = pageThroughWorkspaceLoad(merged, logical);
        assertEquals(List.of(11.0), values(one));
    }

    private SignalSamplePageSnapshot pageThroughWorkspaceLoad(
            SignalSeriesStore store, SignalCatalogKey logical) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        model.loadWorkspaceAlignedAsync(true, snap -> latch.countDown());
        assertTrue(latch.await(5, TimeUnit.SECONDS), "workspace page did not complete");
        SignalSamplePageSnapshot reflected = invokeQuerySinglePage(store, logical);
        SignalSamplePageSnapshot displayed = model.displayedPage();
        assertTrue(displayed != null && !displayed.samples().isEmpty(), "displayed page is empty");
        assertEquals(values(reflected), values(displayed));
        return displayed;
    }

    private SignalSamplePageSnapshot invokeQuerySinglePage(
            SignalSeriesStore store, SignalCatalogKey logical) throws Exception {
        Method method = SignalsScreenModel.class.getDeclaredMethod(
                "querySinglePage",
                SignalSeriesStore.class,
                SignalCatalogKey.class,
                int.class,
                int.class);
        method.setAccessible(true);
        try {
            return (SignalSamplePageSnapshot) method.invoke(model, store, logical, 0, 50);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            cause.printStackTrace(System.out);
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new AssertionError(cause);
        }
    }

    private static List<Double> values(SignalSamplePageSnapshot page) {
        return page.samples().stream().map(SignalSamplePoint::physicalValue).toList();
    }

    private static InMemorySignalCatalogStore store(String file, long timestamp, double value) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(file, "veh.dbc", 1_000);
        store.accept(List.of(new CanonicalSignal(
                "Accuracy", "DIRECT_MEASUREMENT", timestamp, 0L, 0, value, 1L, "",
                null, SourceReference.ofLine(1))));
        return store;
    }
}
