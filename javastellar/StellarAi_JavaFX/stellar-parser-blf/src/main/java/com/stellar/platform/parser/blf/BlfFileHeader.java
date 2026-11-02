package com.stellar.platform.parser.blf;

/**
 * Parsed LOGG file header. Application id and bin-log major/minor/build/patch follow the
 * Vector file header and are not the same as the object-header version.
 */
record BlfFileHeader(
        int headerSize,
        long apiNumber,
        int applicationId,
        int applicationMajor,
        int applicationMinor,
        int applicationBuild,
        int binLogMajor,
        int binLogMinor,
        int binLogBuild,
        int binLogPatch,
        long declaredFileSize,
        long uncompressedFileSize,
        long objectCount,
        int startYear,
        int startMonth,
        int startDay,
        int startHour,
        int startMinute,
        int startSecond,
        int startMillis
) {

    String recordingStartUtcText() {
        if (startYear <= 0) {
            return "";
        }
        return String.format(
                java.util.Locale.ROOT,
                "%04d-%02d-%02dT%02d:%02d:%02d.%03d",
                startYear, startMonth, startDay, startHour, startMinute, startSecond, startMillis);
    }

    String binLogVersion() {
        return binLogMajor + "." + binLogMinor + "." + binLogBuild + "." + binLogPatch;
    }
}
