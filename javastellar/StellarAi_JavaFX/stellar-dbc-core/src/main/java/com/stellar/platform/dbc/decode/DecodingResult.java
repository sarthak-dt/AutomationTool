package com.stellar.platform.dbc.decode;

import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.parse.DbcDiagnostic;
import com.stellar.platform.domain.CanonicalSignal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of decoding one {@code CanonicalCanFrame}.
 */
public record DecodingResult(
        DecodeStatus status,
        DbcMessage matchedMessage,
        List<CanonicalSignal> signals,
        List<DbcDiagnostic> diagnostics
) {

    public DecodingResult {
        Objects.requireNonNull(status, "status");
        signals = List.copyOf(Objects.requireNonNull(signals, "signals"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public Optional<DbcMessage> matchedMessageOptional() {
        return Optional.ofNullable(matchedMessage);
    }
}
