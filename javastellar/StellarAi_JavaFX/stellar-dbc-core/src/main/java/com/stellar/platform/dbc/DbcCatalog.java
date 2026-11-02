package com.stellar.platform.dbc;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParseException;
import com.stellar.platform.dbc.parse.DbcParser;

import java.nio.file.Path;

/**
 * DBC catalog contract backed by {@link DbcParser}.
 */
public interface DbcCatalog {

    /**
     * Loads and parses a DBC description from the given path.
     */
    DbcDatabase load(Path dbcPath) throws DbcParseException;
}
