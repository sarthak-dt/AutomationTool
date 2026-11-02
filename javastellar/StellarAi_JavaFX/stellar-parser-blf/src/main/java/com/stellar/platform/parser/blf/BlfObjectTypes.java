package com.stellar.platform.parser.blf;

final class BlfObjectTypes {

    private BlfObjectTypes() {
    }

    static BlfObjectClass classify(int objectType) {
        return switch (objectType) {
            case BlfConstants.TYPE_CAN_MESSAGE, BlfConstants.TYPE_CAN_MESSAGE2 -> BlfObjectClass.CAN_DATA;
            case BlfConstants.TYPE_CAN_FD_MESSAGE, BlfConstants.TYPE_CAN_FD_MESSAGE_64 -> BlfObjectClass.CAN_FD_DATA;
            case BlfConstants.TYPE_LOG_CONTAINER -> BlfObjectClass.CONTAINER;
            case BlfConstants.TYPE_CAN_ERROR, BlfConstants.TYPE_CAN_OVERLOAD, BlfConstants.TYPE_CAN_STATISTIC,
                    BlfConstants.TYPE_CAN_DRIVER_ERROR, BlfConstants.TYPE_CAN_ERROR_EXT,
                    BlfConstants.TYPE_CAN_FD_ERROR_64, BlfConstants.TYPE_APP_TRIGGER, BlfConstants.TYPE_ENV_INTEGER,
                    BlfConstants.TYPE_ENV_DOUBLE, BlfConstants.TYPE_ENV_STRING, BlfConstants.TYPE_ENV_DATA,
                    BlfConstants.TYPE_LIN_MESSAGE, BlfConstants.TYPE_FLEXRAY_DATA, BlfConstants.TYPE_FLEXRAY_SYNC,
                    BlfConstants.TYPE_GPS_EVENT, BlfConstants.TYPE_APP_TEXT, BlfConstants.TYPE_SYS_VARIABLE,
                    BlfConstants.TYPE_ETHERNET_FRAME, BlfConstants.TYPE_GLOBAL_MARKER -> BlfObjectClass.SKIP_NON_DATA;
            default -> BlfObjectClass.UNKNOWN;
        };
    }

    static String name(int objectType) {
        return switch (objectType) {
            case BlfConstants.TYPE_CAN_MESSAGE -> "CAN_MESSAGE";
            case BlfConstants.TYPE_CAN_MESSAGE2 -> "CAN_MESSAGE2";
            case BlfConstants.TYPE_CAN_FD_MESSAGE -> "CAN_FD_MESSAGE";
            case BlfConstants.TYPE_CAN_FD_MESSAGE_64 -> "CAN_FD_MESSAGE_64";
            case BlfConstants.TYPE_LOG_CONTAINER -> "LOG_CONTAINER";
            case BlfConstants.TYPE_CAN_ERROR -> "CAN_ERROR";
            case BlfConstants.TYPE_CAN_ERROR_EXT -> "CAN_ERROR_EXT";
            case BlfConstants.TYPE_CAN_FD_ERROR_64 -> "CAN_FD_ERROR_64";
            case BlfConstants.TYPE_GPS_EVENT -> "GPS_EVENT";
            case BlfConstants.TYPE_LIN_MESSAGE -> "LIN_MESSAGE";
            case BlfConstants.TYPE_FLEXRAY_DATA -> "FLEXRAY_DATA";
            case BlfConstants.TYPE_ETHERNET_FRAME -> "ETHERNET_FRAME";
            default -> "TYPE_" + objectType;
        };
    }
}
