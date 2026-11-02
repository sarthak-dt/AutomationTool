package com.stellar.platform.odx;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OdxPdxCoreSmokeTest {

    @Test
    void placeholderCatalogAcceptsPath() {
        PlaceholderOdxPdxCatalog catalog = new PlaceholderOdxPdxCatalog();
        Path path = Path.of("example.pdx");
        catalog.load(path);
        assertEquals(path, catalog.loadedPath());
    }
}
