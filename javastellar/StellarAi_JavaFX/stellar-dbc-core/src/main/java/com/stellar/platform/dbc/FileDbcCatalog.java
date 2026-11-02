package com.stellar.platform.dbc;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParseException;
import com.stellar.platform.dbc.parse.DbcParser;

import java.nio.file.Path;
import java.util.Objects;

/**
 * File-backed DBC catalog using the streaming {@link DbcParser}.
 */
public final class FileDbcCatalog implements DbcCatalog {

    private final DbcParser parser;
    private Path loadedPath;
    private DbcDatabase database;

    public FileDbcCatalog() {
        this(new DbcParser());
    }

    public FileDbcCatalog(DbcParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    @Override
    public DbcDatabase load(Path dbcPath) throws DbcParseException {
        this.loadedPath = Objects.requireNonNull(dbcPath, "dbcPath");
        this.database = parser.parse(dbcPath);
        return database;
    }

    public Path loadedPath() {
        return loadedPath;
    }

    public DbcDatabase database() {
        return database;
    }
}
