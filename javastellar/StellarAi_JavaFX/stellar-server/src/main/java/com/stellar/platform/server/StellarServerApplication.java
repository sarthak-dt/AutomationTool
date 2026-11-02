package com.stellar.platform.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot application shell.
 * REST controllers and business logic are intentionally not implemented yet.
 */
@SpringBootApplication
public class StellarServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(StellarServerApplication.class, args);
    }
}
