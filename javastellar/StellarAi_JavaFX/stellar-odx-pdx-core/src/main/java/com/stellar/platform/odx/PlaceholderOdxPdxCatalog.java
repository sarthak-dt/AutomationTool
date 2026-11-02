package com.stellar.platform.odx;

import java.nio.file.Path;

/**
 * Skeleton ODX/PDX catalog that records intent only.
 */
public final class PlaceholderOdxPdxCatalog implements OdxPdxCatalog {

    private Path loadedPath;

    @Override
    public void load(Path odxOrPdxPath) {
        this.loadedPath = odxOrPdxPath;
    }

    public Path loadedPath() {
        return loadedPath;
    }
}
