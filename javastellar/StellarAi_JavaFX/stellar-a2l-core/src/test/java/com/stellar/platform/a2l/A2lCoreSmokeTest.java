package com.stellar.platform.a2l;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class A2lCoreSmokeTest {

    @Test
    void placeholderCatalogAcceptsPath() {
        PlaceholderA2lCatalog catalog = new PlaceholderA2lCatalog();
        Path path = Path.of("example.a2l");
        catalog.load(path);
        assertEquals(path, catalog.loadedPath());
    }
}
