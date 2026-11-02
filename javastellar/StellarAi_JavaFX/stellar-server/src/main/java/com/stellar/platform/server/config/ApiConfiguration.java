package com.stellar.platform.server.config;

import com.stellar.platform.api.StellarPlatformClient;
import com.stellar.platform.api.local.LocalStellarPlatformClient;
import com.stellar.platform.api.support.InMemoryJobTracker;
import com.stellar.platform.api.support.MetadataStoreFactory;
import com.stellar.platform.api.support.ProcessSessionRegistry;
import com.stellar.platform.processing.ParserRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class ApiConfiguration {

    @Bean
    MetadataStoreFactory metadataStoreFactory(
            @Value("${stellar.server.data-root:./stellar-server-data}") String dataRoot
    ) {
        return new MetadataStoreFactory(Path.of(dataRoot));
    }

    @Bean
    InMemoryJobTracker jobTracker() {
        return new InMemoryJobTracker();
    }

    @Bean
    ProcessSessionRegistry processSessionRegistry() {
        return new ProcessSessionRegistry();
    }

    @Bean
    StellarPlatformClient stellarPlatformClient(
            MetadataStoreFactory metadataStoreFactory,
            ParserRegistry parserRegistry,
            InMemoryJobTracker jobTracker,
            ProcessSessionRegistry processSessionRegistry,
            @Value("${stellar.server.max-upload-bytes:1073741824}") long maxUploadBytes
    ) {
        return new LocalStellarPlatformClient(
                metadataStoreFactory,
                parserRegistry,
                jobTracker,
                processSessionRegistry,
                maxUploadBytes
        );
    }
}
