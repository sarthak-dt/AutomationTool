package com.stellar.platform.processing.map.time;

/** Where a recording clock was read. Not a signal sample and not a filename. */
public enum TimestampSource {
    NONE,
    MF4_HD,
    MDF3_HD,
    ASC_DATE,
    BLF_HEADER,
    TEXT_COLUMN,
    TEXT_ISO
}
