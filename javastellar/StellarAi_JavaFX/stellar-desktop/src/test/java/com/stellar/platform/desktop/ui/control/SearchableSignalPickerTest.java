package com.stellar.platform.desktop.ui.control;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.map.GpsSignalCandidate;
import com.stellar.platform.processing.map.GpsSignalRole;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared signal selector: suggestions first, catalog order preserved, search, and an explicit
 * "not mapped" row for optional roles.
 */
class SearchableSignalPickerTest {

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

    private static SignalCatalogKey key(String name) {
        return new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "GPS", 0);
    }

    private static SignalCatalogEntry entry(String name) {
        return new SignalCatalogEntry(key(name), "deg", 10, 0L, 10L, null, null, "drive.mf4", "");
    }

    /** Deliberately not alphabetical: the picker must not re-sort the measurement's own order. */
    private static List<SignalCatalogEntry> catalog() {
        return List.of(entry("Zeta_Signal"), entry("Vehicle_Position_Latitude"),
                entry("Alpha_Signal"), entry("Pos01"));
    }

    private static List<GpsSignalCandidate> suggestions() {
        return List.of(new GpsSignalCandidate(
                key("Vehicle_Position_Latitude"), GpsSignalRole.LATITUDE, 90, "name match"));
    }

    @Test
    @Timeout(30)
    void suggestionsComeFirstAndTheRestKeepsCatalogOrder() throws Exception {
        SearchableSignalPicker picker = newPicker();
        onFx(() -> picker.setCatalog(catalog(), suggestions()));

        assertEquals(List.of(
                        "Suggested Signals",
                        "Vehicle_Position_Latitude",
                        "All Signals",
                        "Zeta_Signal",
                        "Alpha_Signal",
                        "Pos01"),
                labels(picker));
    }

    @Test
    @Timeout(30)
    void searchFiltersBothSectionsWithoutReordering() throws Exception {
        SearchableSignalPicker picker = newPicker();
        onFx(() -> picker.setCatalog(catalog(), suggestions()));

        onFx(() -> picker.searchForTest("signal"));
        assertEquals(List.of("All Signals", "Zeta_Signal", "Alpha_Signal"), labels(picker));

        onFx(() -> picker.searchForTest("latitude"));
        assertEquals(List.of("Suggested Signals", "Vehicle_Position_Latitude"), labels(picker));

        onFx(() -> picker.searchForTest(""));
        assertEquals(6, valueOf(picker::rowCountForTest));
    }

    @Test
    @Timeout(30)
    void pickingASignalReportsTheMeasurementsOwnName() throws Exception {
        SearchableSignalPicker picker = newPicker();
        AtomicReference<String> picked = new AtomicReference<>();
        onFx(() -> {
            picker.setOnPicked(e -> picked.set(e.key().signalName()));
            picker.setCatalog(catalog(), suggestions());
            // Index 1 is the single suggested row.
            picker.pickForTest(1);
        });
        assertEquals("Vehicle_Position_Latitude", picked.get());
        assertEquals("Vehicle_Position_Latitude", valueOf(() -> picker.selected().signalName()));
    }

    @Test
    @Timeout(30)
    void anOptionalRoleGetsAnExplicitNotMappedRow() throws Exception {
        SearchableSignalPicker picker = newPicker();
        AtomicReference<Boolean> cleared = new AtomicReference<>(false);
        onFx(() -> {
            picker.setClearable(true);
            picker.setOnCleared(() -> cleared.set(true));
            picker.setCatalog(catalog(), suggestions());
        });
        assertEquals("— not mapped —", valueOf(() -> picker.rowLabelForTest(0)));

        onFx(() -> {
            picker.setSelected(key("Pos01"));
            picker.pickForTest(0);
        });
        assertTrue(cleared.get());
        assertNull(valueOf(picker::selected));
    }

    @Test
    @Timeout(30)
    void collapsedThePickerShowsOnlyTheChosenSignal() throws Exception {
        SearchableSignalPicker picker = newPicker();
        onFx(() -> picker.setCatalog(catalog(), suggestions()));
        assertEquals("Select a signal", valueOf(picker::displayedValueForTest));

        onFx(() -> picker.pickForTest(1));
        assertEquals("Vehicle_Position_Latitude", valueOf(picker::displayedValueForTest));
    }

    @Test
    @Timeout(30)
    void anUnmappedOptionalRoleReadsAsNotMappedRatherThanEmpty() throws Exception {
        SearchableSignalPicker picker = newPicker();
        onFx(() -> {
            picker.setClearable(true);
            picker.setCatalog(catalog(), suggestions());
        });
        assertEquals("Not mapped", valueOf(picker::displayedValueForTest));
    }

    /** Browsing candidates must not rewrite the mapping on every keystroke. */
    @Test
    @Timeout(30)
    void movingTheHighlightDoesNotChangeTheMapping() throws Exception {
        SearchableSignalPicker picker = newPicker();
        AtomicReference<String> picked = new AtomicReference<>();
        onFx(() -> {
            picker.setOnPicked(e -> picked.set(e.key().signalName()));
            picker.setCatalog(catalog(), suggestions());
            picker.highlightForTest(1);
            picker.highlightForTest(3);
        });
        assertNull(picked.get());
        assertNull(valueOf(picker::selected));
        assertEquals("Select a signal", valueOf(picker::displayedValueForTest));

        onFx(() -> picker.pickForTest(3));
        assertEquals("Zeta_Signal", picked.get());
    }

    /**
     * Typing narrows the list under a highlighted row. Leaving that stale highlight behind makes the
     * next click throw out of JavaFX's ListView behaviour (JDK-8187145).
     */
    @Test
    @Timeout(30)
    void narrowingTheListDropsTheStaleHighlightBeforeTheItemsChange() throws Exception {
        SearchableSignalPicker picker = newPicker();
        AtomicReference<String> picked = new AtomicReference<>();
        onFx(() -> {
            picker.setOnPicked(e -> picked.set(e.key().signalName()));
            picker.setCatalog(catalog(), suggestions());
            picker.highlightForTest(5);
            picker.searchForTest("alpha");
        });
        assertEquals(-1, valueOf(picker::highlightedIndexForTest));
        assertEquals(List.of("All Signals", "Alpha_Signal"), labels(picker));

        onFx(() -> picker.pickForTest(1));
        assertEquals("Alpha_Signal", picked.get());
    }

    @Test
    @Timeout(30)
    void aSectionHeadingCanNeverBecomeTheMapping() throws Exception {
        SearchableSignalPicker picker = newPicker();
        AtomicReference<String> picked = new AtomicReference<>();
        onFx(() -> {
            picker.setOnPicked(e -> picked.set(e.key().signalName()));
            picker.setCatalog(catalog(), suggestions());
            // Index 0 is the "Suggested Signals" heading.
            picker.pickForTest(0);
        });
        assertNull(picked.get());
        assertNull(valueOf(picker::selected));
    }

    @Test
    @Timeout(30)
    void arequiredRoleHasNoClearRow() throws Exception {
        SearchableSignalPicker picker = newPicker();
        onFx(() -> picker.setCatalog(catalog(), suggestions()));
        assertFalse(labels(picker).contains("— not mapped —"));
    }

    @Test
    @Timeout(30)
    void aCatalogWithoutSuggestionsStillListsEverySignal() throws Exception {
        SearchableSignalPicker picker = newPicker();
        onFx(() -> picker.setCatalog(catalog(), List.of()));
        assertEquals(List.of("All Signals", "Zeta_Signal", "Vehicle_Position_Latitude",
                "Alpha_Signal", "Pos01"), labels(picker));
    }

    @Test
    @Timeout(30)
    void anEmptyCatalogShowsAHeaderRatherThanFailing() throws Exception {
        SearchableSignalPicker picker = newPicker();
        onFx(() -> picker.setCatalog(List.of(), List.of()));
        assertEquals(List.of("All Signals"), labels(picker));
        assertNull(valueOf(picker::selected));
    }

    // ------------------------------------------------------------------

    private static SearchableSignalPicker newPicker() throws Exception {
        AtomicReference<SearchableSignalPicker> ref = new AtomicReference<>();
        onFx(() -> ref.set(new SearchableSignalPicker()));
        return ref.get();
    }

    private static List<String> labels(SearchableSignalPicker picker) throws Exception {
        return valueOf(() -> {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < picker.rowCountForTest(); i++) {
                out.add(picker.rowLabelForTest(i));
            }
            return out;
        });
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
        assertTrue(done.await(20, TimeUnit.SECONDS), "FX action timed out");
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
