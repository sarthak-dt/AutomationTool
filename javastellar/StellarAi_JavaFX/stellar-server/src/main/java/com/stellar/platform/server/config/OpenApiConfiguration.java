package com.stellar.platform.server.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * springdoc-openapi metadata for Swagger UI and {@code /v3/api-docs}.
 */
@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI stellarOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Stellar AI Platform API")
                        .description("""
                                REST API for project lifecycle, measurement file registration, \
                                inventory, definition matching, processing jobs, and signal queries. \
                                Controllers live in stellar-server; DTO contracts live in stellar-api.
                                """)
                        .version("0.1.0-SNAPSHOT")
                        .contact(new Contact().name("Stellar AI Platform"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local server (default port)")
                ));
    }
}
