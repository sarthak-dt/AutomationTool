package com.stellar.platform.desktop.ui.graph.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PmtilesArchiveProbeTest {

    @TempDir
    Path temp;

    @Test
    void stamenFixtureIsRaster() throws Exception {
        Path pack = temp.resolve("stamen_toner_z3.pmtiles");
        try (InputStream in = PmtilesArchiveProbeTest.class.getResourceAsStream("/map/stamen_toner_z3.pmtiles")) {
            assumeTrue(in != null, "PMTiles fixture missing");
            Files.copy(in, pack, StandardCopyOption.REPLACE_EXISTING);
        }
        assumeTrue(Files.size(pack) > 1_000);
        PmtilesArchiveProbe.Snapshot snapshot = PmtilesArchiveProbe.probe(pack);
        assertEquals(OfflineMapTileFormat.RASTER, snapshot.tileFormat());
        assertFalse(snapshot.maxZoom() < 1);
    }

    @Test
    void vectorSidecarWinsOverUnknownHeader() {
        OfflineMapTileFormat format = PmtilesArchiveProbe.resolveFormat(
                OfflineMapTileFormat.VECTOR,
                PmtilesArchiveProbe.Snapshot.unknown(),
                true);
        assertEquals(OfflineMapTileFormat.VECTOR, format);
    }

    @Test
    void garbageFileIsUnknown() throws Exception {
        Path pack = temp.resolve("junk.pmtiles");
        Files.writeString(pack, "not-pmtiles");
        assertEquals(OfflineMapTileFormat.UNKNOWN, PmtilesArchiveProbe.probe(pack).tileFormat());
    }
}
