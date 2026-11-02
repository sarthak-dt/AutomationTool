package com.stellar.platform.a2l;

import java.nio.file.Path;

/**
 * Skeleton A2L catalog that records intent only.
 */
public final class PlaceholderA2lCatalog implements A2lCatalog {

    private Path loadedPath;

    @Override
    public void load(Path a2lPath) {
        this.loadedPath = a2lPath;
    }

    public Path loadedPath() {
        return loadedPath;
    }
}
