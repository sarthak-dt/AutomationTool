package com.stellar.platform.dbc.model;

/**
 * Normalized CAN identifier shared by DBC messages and {@code CanonicalCanFrame}.
 * DBC extended IDs use Vector bit-31 flag ({@code 0x80000000}); that flag is stripped here.
 */
public record CanIdKey(long canId, boolean extended) {

    public static final long DBC_EXTENDED_FLAG = 0x8000_0000L;
    public static final long STANDARD_ID_MASK = 0x7FFL;
    public static final long EXTENDED_ID_MASK = 0x1FFF_FFFFL;

    public CanIdKey {
        if (canId < 0) {
            throw new IllegalArgumentException("canId must be >= 0");
        }
        if (extended) {
            if (canId > EXTENDED_ID_MASK) {
                throw new IllegalArgumentException("Extended CAN ID out of 29-bit range: " + canId);
            }
        } else if (canId > STANDARD_ID_MASK) {
            throw new IllegalArgumentException("Standard CAN ID out of 11-bit range: " + canId);
        }
    }

    public static CanIdKey fromDbcRawId(long dbcRawId) {
        boolean extended = (dbcRawId & DBC_EXTENDED_FLAG) != 0;
        long id = extended ? (dbcRawId & EXTENDED_ID_MASK) : (dbcRawId & STANDARD_ID_MASK);
        return new CanIdKey(id, extended);
    }

    public static CanIdKey fromFrame(long canId, boolean extended) {
        return new CanIdKey(canId, extended);
    }

    public long toDbcRawId() {
        return extended ? (canId | DBC_EXTENDED_FLAG) : canId;
    }
}
