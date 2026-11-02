package com.stellar.platform.parser.mf4.schema;

/**
 * CA (Channel Array) block metadata for composition / array expansion.
 */
public record MdfCaMetadata(
        long address,
        long compositionLink,
        long dataLink,
        int type,
        int storage,
        int ndim,
        int flags,
        long byteOffsetBase,
        int[] dimensions,
        long[] axisBlockOffsets
) {

    /** ASAM ca_type: 0 = array. */
    public static final int TYPE_ARRAY = 0;
    /** ASAM ca_type: 1 = scaling axis. */
    public static final int TYPE_SCALE_AXIS = 1;
    /** ASAM ca_type: 2 = look-up. */
    public static final int TYPE_LOOKUP = 2;
    /** ASAM ca_type: 3 = interval axis. */
    public static final int TYPE_INTERVAL_AXIS = 3;
    /** ASAM ca_type: 4 = classification result. */
    public static final int TYPE_CLASSIFICATION = 4;
    /** ASAM ca_storage: 0 = CN template. */
    public static final int STORAGE_CN_TEMPLATE = 0;
    /** ASAM ca_storage: 1 = CG template with CG/DG composition. */
    public static final int STORAGE_CG_TEMPLATE = 1;
    /** ASAM ca_storage: 2 = DG vector. */
    public static final int STORAGE_VECTOR = 2;

    public MdfCaMetadata {
        dimensions = dimensions == null ? new int[0] : dimensions.clone();
        axisBlockOffsets = axisBlockOffsets == null ? new long[0] : axisBlockOffsets.clone();
    }

    public long elementCount() {
        if (dimensions.length == 0) {
            return 0L;
        }
        long n = 1L;
        for (int d : dimensions) {
            if (d <= 0) {
                return 0L;
            }
            n *= d;
            if (n > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return n;
    }

    public boolean simpleCnComposition() {
        return compositionLink != 0L && (type == TYPE_ARRAY || ndim >= 0);
    }
}
