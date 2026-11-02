package com.stellar.platform.parser.blf;

/**
 * Vector BLF structural constants. Header version and object-header version are distinct.
 */
final class BlfConstants {

    static final int FILE_SIGNATURE = 0x47474F4C; // "LOGG" LE
    static final byte[] FILE_MAGIC = {'L', 'O', 'G', 'G'};
    static final int OBJECT_SIGNATURE = 0x4A424F4C; // "LOBJ" LE

    static final int MIN_FILE_HEADER_SIZE = 76;
    static final int TYPICAL_FILE_HEADER_SIZE = 144;
    static final int MAX_FILE_HEADER_SIZE = 4096;

    static final int OBJECT_BASE_SIZE = 16;
    static final int OBJECT_HEADER_V1_EXTRA = 16;
    static final int OBJECT_HEADER_V2_EXTRA = 24;
    static final int MAX_OBJECT_SIZE = 16 * 1024 * 1024;
    static final int MAX_CONTAINER_UNCOMPRESSED = 64 * 1024 * 1024;
    static final int MAX_CONTAINER_DEPTH = 2;

    static final int FLAG_TIME_TEN_MICS = 0x00000001;
    static final int FLAG_TIME_ONE_NANS = 0x00000002;
    static final long TEN_MICROS_TO_NANOS = 10_000L;

    static final int CAN_ID_EXT_FLAG = 0x80000000;
    static final int CAN_ID_EXT_MASK = 0x1FFFFFFF;
    static final int CAN_ID_STD_MASK = 0x7FF;
    static final int CAN_FLAG_TX = 0x01;
    static final int CAN_FLAG_RTR = 0x80;

    static final int COMPRESSION_NONE = 0;
    static final int COMPRESSION_ZLIB = 2;

    static final int TYPE_CAN_MESSAGE = 1;
    static final int TYPE_CAN_ERROR = 2;
    static final int TYPE_CAN_OVERLOAD = 3;
    static final int TYPE_CAN_STATISTIC = 4;
    static final int TYPE_APP_TRIGGER = 5;
    static final int TYPE_ENV_INTEGER = 6;
    static final int TYPE_ENV_DOUBLE = 7;
    static final int TYPE_ENV_STRING = 8;
    static final int TYPE_ENV_DATA = 9;
    static final int TYPE_LOG_CONTAINER = 10;
    static final int TYPE_LIN_MESSAGE = 11;
    static final int TYPE_CAN_DRIVER_ERROR = 31;
    static final int TYPE_FLEXRAY_DATA = 29;
    static final int TYPE_FLEXRAY_SYNC = 30;
    static final int TYPE_GPS_EVENT = 46;
    static final int TYPE_APP_TEXT = 65;
    static final int TYPE_SYS_VARIABLE = 72;
    static final int TYPE_ETHERNET_FRAME = 71;
    static final int TYPE_CAN_ERROR_EXT = 73;
    static final int TYPE_CAN_MESSAGE2 = 86;
    static final int TYPE_GLOBAL_MARKER = 96;
    static final int TYPE_CAN_FD_MESSAGE = 100;
    static final int TYPE_CAN_FD_MESSAGE_64 = 101;
    static final int TYPE_CAN_FD_ERROR_64 = 104;

    private BlfConstants() {
    }

    static int align4(int size) {
        return (size + 3) & ~3;
    }

    static long align4(long size) {
        return (size + 3L) & ~3L;
    }

    static int canFdDlcToLength(int dlc) {
        if (dlc <= 8) {
            return Math.max(0, dlc);
        }
        return switch (dlc) {
            case 9 -> 12;
            case 10 -> 16;
            case 11 -> 20;
            case 12 -> 24;
            case 13 -> 32;
            case 14 -> 48;
            case 15 -> 64;
            default -> -1;
        };
    }
}
