package com.stellar.platform.server;

import com.stellar.platform.processing.ParserRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires SPI-based parser discovery into the Spring context.
 */
@Configuration
public class ParserConfiguration {

    @Bean
    ParserRegistry parserRegistry() {
        return ParserRegistry.load();
    }
}
