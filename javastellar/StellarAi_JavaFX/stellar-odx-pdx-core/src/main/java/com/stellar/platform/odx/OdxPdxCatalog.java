package com.stellar.platform.odx;

import java.nio.file.Path;

/**
 * Placeholder ODX/PDX catalog contract — no parsing or extraction yet.
 */
public interface OdxPdxCatalog {

    void load(Path odxOrPdxPath);
}
