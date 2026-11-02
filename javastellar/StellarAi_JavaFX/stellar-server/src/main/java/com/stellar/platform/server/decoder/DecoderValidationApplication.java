package com.stellar.platform.server.decoder;

import com.stellar.platform.server.StellarServerApplication;
import org.springframework.boot.SpringApplication;

/**
 * Headless entry point for the decoder validation API.
 * It does not start JavaFX and adds the {@code decoder} profile, which binds 127.0.0.1.
 */
public final class DecoderValidationApplication {

    private DecoderValidationApplication() {
    }

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(StellarServerApplication.class);
        application.setAdditionalProfiles("decoder");
        application.setHeadless(true);
        application.run(args);
    }
}
