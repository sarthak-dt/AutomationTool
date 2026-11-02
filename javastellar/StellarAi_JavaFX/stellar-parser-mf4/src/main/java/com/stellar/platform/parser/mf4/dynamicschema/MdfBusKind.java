package com.stellar.platform.parser.mf4.dynamicschema;

/**
 * Bus / event classification for a Channel Group.
 */
public enum MdfBusKind {
    CAN,
    LIN,
    ERROR_STATUS,
    OTHER,
    UNSUPPORTED,
    UNKNOWN
}
