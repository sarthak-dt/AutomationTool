package com.stellar.platform.parser.blf;

import com.stellar.platform.parser.spi.ParserException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class BlfHeaderParser {

    private BlfHeaderParser() {
    }

    static BlfFileHeader read(BlfInput in, long fileSize) throws IOException, ParserException {
        if (fileSize < BlfConstants.MIN_FILE_HEADER_SIZE) {
            throw new ParserException("BLF header is truncated");
        }
        byte[] prefix = new byte[BlfConstants.MIN_FILE_HEADER_SIZE];
        in.readFully(prefix, 0, prefix.length);
        if (prefix[0] != 'L' || prefix[1] != 'O' || prefix[2] != 'G' || prefix[3] != 'G') {
            throw new ParserException("Not a BLF file: missing LOGG signature");
        }
        ByteBuffer buf = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(4);
        long headerSizeLong = Integer.toUnsignedLong(buf.getInt());
        if (headerSizeLong < BlfConstants.MIN_FILE_HEADER_SIZE || headerSizeLong > BlfConstants.MAX_FILE_HEADER_SIZE) {
            throw new ParserException("Impossible BLF header size: " + headerSizeLong);
        }
        int headerSize = (int) headerSizeLong;
        if (headerSize > fileSize) {
            throw new ParserException("BLF header size exceeds file length");
        }
        int applicationId = Byte.toUnsignedInt(buf.get());
        int applicationMajor = Byte.toUnsignedInt(buf.get());
        int applicationMinor = Byte.toUnsignedInt(buf.get());
        int applicationBuild = Byte.toUnsignedInt(buf.get());
        int binLogMajor = Byte.toUnsignedInt(buf.get());
        int binLogMinor = Byte.toUnsignedInt(buf.get());
        int binLogBuild = Byte.toUnsignedInt(buf.get());
        int binLogPatch = Byte.toUnsignedInt(buf.get());
        long declaredFileSize = buf.getLong();
        long uncompressedFileSize = buf.getLong();
        long objectCount = Integer.toUnsignedLong(buf.getInt());
        buf.getInt(); // objectsRead
        int startYear = Short.toUnsignedInt(buf.getShort());
        int startMonth = Short.toUnsignedInt(buf.getShort());
        buf.getShort(); // dayOfWeek
        int startDay = Short.toUnsignedInt(buf.getShort());
        int startHour = Short.toUnsignedInt(buf.getShort());
        int startMinute = Short.toUnsignedInt(buf.getShort());
        int startSecond = Short.toUnsignedInt(buf.getShort());
        int startMillis = Short.toUnsignedInt(buf.getShort());
        int already = BlfConstants.MIN_FILE_HEADER_SIZE;
        if (headerSize > already) {
            in.skip(headerSize - already);
        }
        return new BlfFileHeader(
                headerSize,
                applicationId,
                applicationId,
                applicationMajor,
                applicationMinor,
                applicationBuild,
                binLogMajor,
                binLogMinor,
                binLogBuild,
                binLogPatch,
                declaredFileSize,
                uncompressedFileSize,
                objectCount,
                startYear,
                startMonth,
                startDay,
                startHour,
                startMinute,
                startSecond,
                startMillis
        );
    }
}
