package com.stellar.platform.api.http;

import com.stellar.platform.api.DefinitionMatchApi;
import com.stellar.platform.api.FileAssetApi;
import com.stellar.platform.api.InventoryApi;
import com.stellar.platform.api.ProcessingApi;
import com.stellar.platform.api.ProjectApi;
import com.stellar.platform.api.ProjectFileApi;
import com.stellar.platform.api.SignalQueryApi;
import com.stellar.platform.api.StellarPlatformClient;

import java.util.Objects;

/** REMOTE client that talks to stellar-server REST endpoints. */
public final class HttpStellarPlatformClient implements StellarPlatformClient {

    private final ProjectApi projects;
    private final ProjectFileApi files;
    private final FileAssetApi assets;
    private final InventoryApi inventory;
    private final DefinitionMatchApi definitions;
    private final ProcessingApi processing;
    private final SignalQueryApi signals;

    public HttpStellarPlatformClient(String baseUrl) {
        HttpTransport transport = new HttpTransport(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.projects = new HttpProjectApi(transport);
        this.files = new HttpProjectFileApi(transport);
        this.assets = new HttpFileAssetApi(transport);
        this.inventory = new HttpInventoryApi(transport);
        this.definitions = new HttpDefinitionMatchApi(transport);
        this.processing = new HttpProcessingApi(transport);
        this.signals = new HttpSignalQueryApi(transport);
    }

    @Override
    public ProjectApi projects() {
        return projects;
    }

    @Override
    public ProjectFileApi files() {
        return files;
    }

    @Override
    public FileAssetApi assets() {
        return assets;
    }

    @Override
    public InventoryApi inventory() {
        return inventory;
    }

    @Override
    public DefinitionMatchApi definitions() {
        return definitions;
    }

    @Override
    public ProcessingApi processing() {
        return processing;
    }

    @Override
    public SignalQueryApi signals() {
        return signals;
    }
}
