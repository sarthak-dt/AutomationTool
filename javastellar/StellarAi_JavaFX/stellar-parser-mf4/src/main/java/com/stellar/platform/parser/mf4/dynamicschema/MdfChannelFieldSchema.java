package com.stellar.platform.parser.mf4.dynamicschema;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runtime field layout for one CN within a Channel Group record.
 */
public record MdfChannelFieldSchema(
        long channelAddress,
        String name,
        int channelType,
        int syncType,
        int dataTypeCode,
        MdfChannelDataTypeKind dataTypeKind,
        boolean supported,
        long byteOffset,
        int bitOffset,
        long bitLength,
        MdfByteOrder byteOrder,
        MdfSignedness signedness,
        long invalidationBitPosition,
        int channelFlags,
        long conversionLink,
        long sourceInformationLink,
        long dataLink,
        String unit,
        MdfCanRoleCandidate canRoleCandidate,
        long parentChannelAddress,
        boolean compositionMember,
        boolean compositionContainer,
        MdfChannelConversion conversion
) {

    /** ASAM cn_flags bit 1: invalidation bit valid. */
    public boolean invalidationBitValid() {
        return (channelFlags & 0x02) != 0;
    }

    public MdfChannelFieldSchema {
        name = name == null ? "" : name;
        Objects.requireNonNull(dataTypeKind, "dataTypeKind");
        Objects.requireNonNull(byteOrder, "byteOrder");
        Objects.requireNonNull(signedness, "signedness");
        unit = unit == null ? "" : unit;
        Objects.requireNonNull(canRoleCandidate, "canRoleCandidate");
        conversion = conversion == null ? MdfChannelConversion.IDENTITY : conversion;
    }

    /**
     * Convenience for tests / identity conversion.
     */
    public MdfChannelFieldSchema(
            long channelAddress,
            String name,
            int channelType,
            int syncType,
            int dataTypeCode,
            MdfChannelDataTypeKind dataTypeKind,
            boolean supported,
            long byteOffset,
            int bitOffset,
            long bitLength,
            MdfByteOrder byteOrder,
            MdfSignedness signedness,
            long invalidationBitPosition,
            int channelFlags,
            long conversionLink,
            long sourceInformationLink,
            long dataLink,
            String unit,
            MdfCanRoleCandidate canRoleCandidate,
            long parentChannelAddress,
            boolean compositionMember,
            boolean compositionContainer
    ) {
        this(
                channelAddress, name, channelType, syncType, dataTypeCode, dataTypeKind, supported,
                byteOffset, bitOffset, bitLength, byteOrder, signedness, invalidationBitPosition,
                channelFlags, conversionLink, sourceInformationLink, dataLink, unit, canRoleCandidate,
                parentChannelAddress, compositionMember, compositionContainer,
                MdfChannelConversion.IDENTITY
        );
    }

    public Optional<String> unitOptional() {
        return unit.isBlank() ? Optional.empty() : Optional.of(unit);
    }

    public String leafName() {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot + 1 < name.length() ? name.substring(dot + 1) : name;
    }
}
