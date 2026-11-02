package com.stellar.platform.desktop.project;

/** Distinguishes empty parsed DBC from valid DBC with zero CAN-ID overlap. */
public enum DefinitionCandidateKind {
    /** Parsed DBC contains zero message definitions. */
    EMPTY_DEFINITION,
    /** DBC has messages but none overlap observed identities. */
    NO_OVERLAP,
    /** At least one observed identity matched a DBC message. */
    HAS_OVERLAP
}
