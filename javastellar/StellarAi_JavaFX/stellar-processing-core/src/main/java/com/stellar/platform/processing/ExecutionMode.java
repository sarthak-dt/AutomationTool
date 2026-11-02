package com.stellar.platform.processing;

/**
 * Where measurement decoding executes.
 * {@link #LOCAL} runs in-process; {@link #REMOTE} uses stellar-server HTTP APIs.
 */
public enum ExecutionMode {
    LOCAL,
    REMOTE
}
