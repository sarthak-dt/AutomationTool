package com.stellar.platform.dbc;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParseException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DbcCoreSmokeTest {

    @Test
    void fileCatalogLoadsCommonDbc() throws DbcParseException {
        FileDbcCatalog catalog = new FileDbcCatalog();
        Path path = Path.of("src/test/resources/dbc/common_decoder.dbc");
        if (!path.toFile().exists()) {
            path = resource("dbc/common_decoder.dbc");
        }
        DbcDatabase db = catalog.load(path);
        assertNotNull(db);
        assertFalse(db.messages().isEmpty());
    }

    private static Path resource(String name) {
        try {
            var url = DbcCoreSmokeTest.class.getClassLoader().getResource(name);
            return Path.of(url.toURI());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
