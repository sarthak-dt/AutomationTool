package com.stellar.platform.domain.inventory;

import java.util.Objects;
import java.util.Optional;

/**
 * Unique observed CAN identity vs one parsed DBC message identity.
 * Does not store frames or payloads.
 */
public final class CanIdMatchDiagnostic {
    private final long observedCanId;
    private final boolean observedExtended;
    private final CanIdMatchDiagnosticStatus status;
    private final Long matchedDbcCanId;
    private final Boolean matchedDbcExtended;
    private final String matchedDbcMessageName;

    public CanIdMatchDiagnostic(
            long observedCanId,
            boolean observedExtended,
            CanIdMatchDiagnosticStatus status,
            Long matchedDbcCanId,
            Boolean matchedDbcExtended,
            String matchedDbcMessageName
    ) {
        if (observedCanId < 0) {
            throw new IllegalArgumentException("observedCanId must be >= 0");
        }
        this.observedCanId = observedCanId;
        this.observedExtended = observedExtended;
        this.status = Objects.requireNonNull(status, "status");
        this.matchedDbcCanId = matchedDbcCanId;
        this.matchedDbcExtended = matchedDbcExtended;
        this.matchedDbcMessageName = matchedDbcMessageName == null || matchedDbcMessageName.isBlank()
                ? null
                : matchedDbcMessageName;
    }

    public static CanIdMatchDiagnostic matched(
            long observedCanId,
            boolean observedExtended,
            long dbcCanId,
            boolean dbcExtended,
            String dbcMessageName
    ) {
        return new CanIdMatchDiagnostic(
                observedCanId,
                observedExtended,
                CanIdMatchDiagnosticStatus.MATCHED,
                dbcCanId,
                dbcExtended,
                dbcMessageName
        );
    }

    public static CanIdMatchDiagnostic unmatched(long observedCanId, boolean observedExtended) {
        return new CanIdMatchDiagnostic(
                observedCanId,
                observedExtended,
                CanIdMatchDiagnosticStatus.UNMATCHED,
                null,
                null,
                null
        );
    }

    public static CanIdMatchDiagnostic typeMismatch(
            long observedCanId,
            boolean observedExtended,
            long dbcCanId,
            boolean dbcExtended,
            String dbcMessageName
    ) {
        return new CanIdMatchDiagnostic(
                observedCanId,
                observedExtended,
                CanIdMatchDiagnosticStatus.TYPE_MISMATCH,
                dbcCanId,
                dbcExtended,
                dbcMessageName
        );
    }

    public long observedCanId() {
        return observedCanId;
    }

    public boolean observedExtended() {
        return observedExtended;
    }

    public CanIdMatchDiagnosticStatus status() {
        return status;
    }

    public Optional<Long> matchedDbcCanId() {
        return Optional.ofNullable(matchedDbcCanId);
    }

    public Optional<Boolean> matchedDbcExtended() {
        return Optional.ofNullable(matchedDbcExtended);
    }

    public Optional<String> matchedDbcMessageName() {
        return Optional.ofNullable(matchedDbcMessageName);
    }

    public String observedTypeLabel() {
        return observedExtended ? "EXTENDED" : "STANDARD";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CanIdMatchDiagnostic that)) {
            return false;
        }
        return observedCanId == that.observedCanId
                && observedExtended == that.observedExtended
                && status == that.status
                && Objects.equals(matchedDbcCanId, that.matchedDbcCanId)
                && Objects.equals(matchedDbcExtended, that.matchedDbcExtended)
                && Objects.equals(matchedDbcMessageName, that.matchedDbcMessageName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                observedCanId,
                observedExtended,
                status,
                matchedDbcCanId,
                matchedDbcExtended,
                matchedDbcMessageName
        );
    }
}
