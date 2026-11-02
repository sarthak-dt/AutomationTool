package com.stellar.platform.server.decoder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "stellar.decoder.validation-api", havingValue = "true")
@EnableConfigurationProperties(DecoderValidationProperties.class)
public class DecoderValidationConfiguration {
}
