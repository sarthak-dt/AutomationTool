package com.stellar.platform.server.config;

import com.stellar.platform.server.security.AuthHook;
import com.stellar.platform.server.security.SecurityBaselineProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SecurityBaselineProperties.class)
public class SecurityConfiguration {

    @Bean
    AuthHook authHook() {
        // Replace this bean to plug in bearer/mTLS checks without changing controllers.
        return AuthHook.PERMISSIVE;
    }
}
