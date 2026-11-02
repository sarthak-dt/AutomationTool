package com.stellar.platform.parser.mf4.dynamicschema;

/**
 * Evidence-based CAN role candidates derived from channel metadata (not client-specific names).
 */
public enum MdfCanRole {
    TIMESTAMP,
    CAN_ID,
    BUS_CHANNEL,
    DIRECTION,
    DLC,
    PAYLOAD,
    CAN_FD_INDICATION,
    FLAGS,
    NONE
}
