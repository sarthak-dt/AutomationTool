package com.stellar.platform.dbc.model;

/**
 * DBC signal byte order.
 * INTEL / LITTLE_ENDIAN = {@code @1}, MOTOROLA / BIG_ENDIAN = {@code @0}.
 */
public enum DbcByteOrder {
    INTEL,
    MOTOROLA;

    public static DbcByteOrder fromDbcCode(int code) {
        return code == 1 ? INTEL : MOTOROLA;
    }
}
