package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.mf4.dynamicschema.MdfByteOrder;
import com.stellar.platform.parser.mf4.dynamicschema.MdfChannelDataTypeKind;
import com.stellar.platform.parser.mf4.dynamicschema.MdfChannelFieldSchema;
import com.stellar.platform.parser.mf4.dynamicschema.MdfSignedness;
import com.stellar.platform.parser.mf4.io.MdfSafeMath;

/**
 * Immutable per-field geometry compiled once from {@link MdfChannelFieldSchema}.
 */
public record MdfFieldDecodePlan(
        MdfChannelFieldSchema field,
        int fieldIndex,
        boolean compositionContainer,
        boolean supported,
        MdfChannelDataTypeKind kind,
        int byteOffset,
        int bitOffset,
        int bitLength,
        int startBit,
        int byteCount,
        MdfByteOrder byteOrder,
        boolean signed,
        boolean invalidationBitValid,
        int invalidationByteIndex,
        int invalidationMask,
        boolean resolvableVlsd
) {

    public static MdfFieldDecodePlan compile(MdfChannelFieldSchema field, int index) {
        boolean composition = field.compositionContainer();
        int startBit = 0;
        try {
            long sb = MdfSafeMath.addExact(
                    MdfSafeMath.multiplyExact(field.byteOffset(), 8L, "byteOffset*8"),
                    field.bitOffset(),
                    "startBit"
            );
            startBit = sb > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sb;
        } catch (ArithmeticException e) {
            startBit = Integer.MAX_VALUE;
        }
        int byteCount = field.bitLength() <= 0 ? 0 : (int) ((field.bitLength() + 7) / 8);
        boolean invalValid = field.invalidationBitValid() && field.invalidationBitPosition() >= 0;
        int invalByte = -1;
        int invalMask = 0;
        if (invalValid) {
            invalByte = (int) (field.invalidationBitPosition() / 8L);
            int bitInByte = (int) (field.invalidationBitPosition() % 8L);
            invalMask = 1 << bitInByte;
        }
        boolean vlsd = field.channelType() == MdfVlsdResolver.CHANNEL_TYPE_VLSD && field.dataLink() != 0L;
        return new MdfFieldDecodePlan(
                field,
                index,
                composition,
                field.supported(),
                field.dataTypeKind(),
                (int) field.byteOffset(),
                field.bitOffset(),
                (int) field.bitLength(),
                startBit,
                byteCount,
                field.byteOrder(),
                field.signedness() == MdfSignedness.SIGNED,
                invalValid,
                invalByte,
                invalMask,
                vlsd
        );
    }
}
