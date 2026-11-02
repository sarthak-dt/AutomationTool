package com.stellar.platform.parser.mf4.dynamicschema;

/**
 * Supported / unsupported MDF cn_data_type classification for schema building.
 */
public enum MdfChannelDataTypeKind {
    UNSIGNED_INTEGER_LE(0, true, MdfByteOrder.LITTLE_ENDIAN, MdfSignedness.UNSIGNED),
    UNSIGNED_INTEGER_BE(1, true, MdfByteOrder.BIG_ENDIAN, MdfSignedness.UNSIGNED),
    SIGNED_INTEGER_LE(2, true, MdfByteOrder.LITTLE_ENDIAN, MdfSignedness.SIGNED),
    SIGNED_INTEGER_BE(3, true, MdfByteOrder.BIG_ENDIAN, MdfSignedness.SIGNED),
    FLOAT_LE(4, true, MdfByteOrder.LITTLE_ENDIAN, MdfSignedness.NOT_APPLICABLE),
    FLOAT_BE(5, true, MdfByteOrder.BIG_ENDIAN, MdfSignedness.NOT_APPLICABLE),
    STRING_LATIN1(6, true, MdfByteOrder.NOT_APPLICABLE, MdfSignedness.NOT_APPLICABLE),
    STRING_UTF8(7, true, MdfByteOrder.NOT_APPLICABLE, MdfSignedness.NOT_APPLICABLE),
    STRING_UTF16_LE(8, true, MdfByteOrder.LITTLE_ENDIAN, MdfSignedness.NOT_APPLICABLE),
    BYTE_ARRAY(9, true, MdfByteOrder.NOT_APPLICABLE, MdfSignedness.NOT_APPLICABLE),
    MIME_SAMPLE(10, false, MdfByteOrder.NOT_APPLICABLE, MdfSignedness.NOT_APPLICABLE),
    MIME_STREAM(11, false, MdfByteOrder.NOT_APPLICABLE, MdfSignedness.NOT_APPLICABLE),
    CANOPEN_DATE(12, false, MdfByteOrder.NOT_APPLICABLE, MdfSignedness.NOT_APPLICABLE),
    CANOPEN_TIME(13, false, MdfByteOrder.NOT_APPLICABLE, MdfSignedness.NOT_APPLICABLE),
    COMPLEX_LE(14, false, MdfByteOrder.LITTLE_ENDIAN, MdfSignedness.NOT_APPLICABLE),
    COMPLEX_BE(15, false, MdfByteOrder.BIG_ENDIAN, MdfSignedness.NOT_APPLICABLE),
    UNKNOWN(-1, false, MdfByteOrder.NOT_APPLICABLE, MdfSignedness.NOT_APPLICABLE);

    private final int code;
    private final boolean supportedForSafeDecode;
    private final MdfByteOrder byteOrder;
    private final MdfSignedness signedness;

    MdfChannelDataTypeKind(int code, boolean supportedForSafeDecode, MdfByteOrder byteOrder, MdfSignedness signedness) {
        this.code = code;
        this.supportedForSafeDecode = supportedForSafeDecode;
        this.byteOrder = byteOrder;
        this.signedness = signedness;
    }

    public int code() {
        return code;
    }

    public boolean supportedForSafeDecode() {
        return supportedForSafeDecode;
    }

    public MdfByteOrder byteOrder() {
        return byteOrder;
    }

    public MdfSignedness signedness() {
        return signedness;
    }

    public static MdfChannelDataTypeKind fromCode(int dataType) {
        for (MdfChannelDataTypeKind kind : values()) {
            if (kind != UNKNOWN && kind.code == dataType) {
                return kind;
            }
        }
        return UNKNOWN;
    }
}
