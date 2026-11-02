package com.stellar.platform.server.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deployment security baseline (bind/TLS/auth/request limits). No enterprise IAM.
 */
@ConfigurationProperties(prefix = "stellar.security")
public class SecurityBaselineProperties {

    /** When true, {@link AuthHookFilter} enforces {@link AuthHook}. */
    private boolean authRequired = false;

    public boolean isAuthRequired() {
        return authRequired;
    }

    public void setAuthRequired(boolean authRequired) {
        this.authRequired = authRequired;
    }
}
