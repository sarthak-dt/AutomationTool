package com.stellar.platform.desktop.ui.graph.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TileHealthSnapshotTest {

    @Test
    void parsesTheLightweightRuntimeTuple() {
        TileHealthSnapshot snapshot = TileHealthSnapshot.parse("3|1|9|9|9|0|9|1|1|1");
        assertEquals(3, snapshot.generation());
        assertTrue(snapshot.layerExists());
        assertEquals(9, snapshot.currentTiles());
        assertEquals(9, snapshot.completeTiles());
        assertEquals(9, snapshot.validNaturalWidth());
        assertEquals(0, snapshot.failedTiles());
        assertEquals(9, snapshot.visibleTiles());
        assertTrue(snapshot.tileFirst());
        assertTrue(snapshot.tileDone());
        assertTrue(snapshot.mapUsable());
        assertTrue(snapshot.healthy());
        assertFalse(snapshot.partiallyLoaded());
        assertFalse(snapshot.providerFailed());
    }

    @Test
    void completeVisibleSetIsHealthyEvenWhenJavaMissedFirstTile() {
        TileHealthSnapshot snapshot = TileHealthSnapshot.parse("1|1|9|9|9|0|9|1|1|1");
        assertTrue(snapshot.healthy());
    }

    @Test
    void mixedTilesArePartialNotHealthy() {
        TileHealthSnapshot snapshot = TileHealthSnapshot.parse("1|1|9|6|6|1|6|1|0|1");
        assertFalse(snapshot.healthy());
        assertTrue(snapshot.partiallyLoaded());
        assertFalse(snapshot.providerFailed());
        assertFalse(snapshot.loading());
    }

    @Test
    void emptyLayerStillLoadingIsNotAFailure() {
        TileHealthSnapshot snapshot = TileHealthSnapshot.parse("1|1|0|0|0|0|0|0|0|1");
        assertFalse(snapshot.healthy());
        assertFalse(snapshot.partiallyLoaded());
        assertTrue(snapshot.loading());
        assertFalse(snapshot.providerFailed());
    }

    @Test
    void finishedLayerWithNoValidTilesIsAProviderFailure() {
        TileHealthSnapshot snapshot = TileHealthSnapshot.parse("1|1|0|0|0|4|0|0|1|1");
        assertTrue(snapshot.providerFailed());
        assertFalse(snapshot.healthy());
    }

    @Test
    void rejectsMalformedPayloads() {
        assertNull(TileHealthSnapshot.parse(""));
        assertNull(TileHealthSnapshot.parse("1|1|9"));
        assertNull(TileHealthSnapshot.parse("x|1|9|9|9|0|9|1|1|1"));
    }
}
