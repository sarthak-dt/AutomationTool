package com.stellar.platform.server.security;

/**
 * Authentication abstraction/hook for deployment. Not a full IAM system.
 * Implementations may validate bearer tokens, mTLS peer identity, etc.
 */
@FunctionalInterface
public interface AuthHook {

    /**
     * @param authorizationHeader raw {@code Authorization} header value, may be null
     * @return true when the request is permitted
     */
    boolean allow(String authorizationHeader);

    /** Permissive default for local/dev: always allow. */
    AuthHook PERMISSIVE = authorizationHeader -> true;
}
