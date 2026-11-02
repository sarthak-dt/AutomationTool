package com.stellar.platform.desktop.validation;

/**
 * Stable codes for Project Files validation messaging.
 */
public enum ValidationIssueCode {
    NO_MEASUREMENT_FILE_SELECTED,
    MEASUREMENT_FILE_NOT_PROCESSABLE,
    MISSING_DBC,
    DBC_INVALID,
    /** Defined Windows-1252 bytes were accepted and the DBC was imported. */
    DBC_WINDOWS_1252_ENCODING,
    /** A VAL_ description spans lines and was not attached. Numeric decoding is unchanged. */
    DBC_MULTILINE_VAL,
    /** SIG_VALTYPE_ could not be applied. Integer decoding was kept. */
    DBC_SIG_VALTYPE,
    /** Other DBC records were ignored and the detailed diagnostics were kept. */
    DBC_IGNORED_RECORDS,
    MF4_INVALID,
    MF4_RECOVERY_REQUIRED,
    ASC_INVALID,
    UNSUPPORTED_FORMAT,
    FILE_MISSING,
    FILE_UNREADABLE,
    NO_PARSER,
    ASSOCIATION_REQUIRED,
    GENERIC
}
