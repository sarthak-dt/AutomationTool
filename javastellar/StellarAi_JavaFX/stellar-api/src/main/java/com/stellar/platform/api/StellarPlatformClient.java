package com.stellar.platform.api;

/**
 * Facade over transport-stable platform contracts. Desktop and server share LOCAL
 * implementations; REMOTE desktop uses HTTP adapters of the same interfaces.
 */
public interface StellarPlatformClient {

    ProjectApi projects();

    ProjectFileApi files();

    FileAssetApi assets();

    InventoryApi inventory();

    DefinitionMatchApi definitions();

    ProcessingApi processing();

    SignalQueryApi signals();
}
