package com.stellar.platform.parser.blf;

record BlfObjectHeader(
        int headerSize,
        int headerVersion,
        int objectSize,
        int objectType,
        int flags,
        long objectTimeStamp,
        long fileOffset
) {

    long timestampNanos() {
        if ((flags & BlfConstants.FLAG_TIME_ONE_NANS) != 0) {
            return objectTimeStamp;
        }
        if (objectTimeStamp > Long.MAX_VALUE / BlfConstants.TEN_MICROS_TO_NANOS) {
            throw new ArithmeticException("timestamp overflow");
        }
        return objectTimeStamp * BlfConstants.TEN_MICROS_TO_NANOS;
    }

    int payloadLength() {
        return objectSize - headerSize;
    }
}
