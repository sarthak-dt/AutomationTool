package com.stellar.platform.parser.mf4.block;

import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.io.MdfSafeMath;
import com.stellar.platform.parser.spi.ParserException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Safe random-access reader for MDF 4 common block headers.
 */
public final class MdfBlockHeaderReader {

    private MdfBlockHeaderReader() {
    }

    public static MdfBlockHeader read(MdfFileAccess access, long address) throws ParserException {
        if (address < 0) {
            throw new ParserException("Block address must be >= 0, got " + address);
        }
        if (!access.canRead(address, MdfBlockHeader.COMMON_HEADER_SIZE)) {
            throw new ParserException(
                    "Cannot read block header at " + address + ": exceeds file size " + access.fileSize());
        }

        ByteBuffer header = access.readOrdered(address, MdfBlockHeader.COMMON_HEADER_SIZE, "block-header@" + address);
        byte[] idBytes = new byte[4];
        header.get(idBytes);
        String rawId = new String(idBytes, StandardCharsets.US_ASCII);
        int reserved = header.getInt();
        long declaredLength = header.getLong();
        long linkCount = header.getLong();

        if (declaredLength < MdfBlockHeader.COMMON_HEADER_SIZE) {
            throw new ParserException(
                    "Impossible declared block length " + declaredLength + " at " + address
                            + " (minimum " + MdfBlockHeader.COMMON_HEADER_SIZE + ")");
        }
        if (linkCount < 0) {
            throw new ParserException("Negative link count at " + address);
        }
        if (linkCount > 1_000_000L) {
            throw new ParserException("Implausible link count " + linkCount + " at " + address);
        }

        long linksBytes;
        try {
            linksBytes = MdfSafeMath.multiplyExact(linkCount, 8L, "linksBytes@" + address);
        } catch (ArithmeticException e) {
            throw new ParserException("Link table size overflow at " + address, e);
        }

        long minLength;
        try {
            minLength = MdfSafeMath.addExact(MdfBlockHeader.COMMON_HEADER_SIZE, linksBytes, "minLength@" + address);
        } catch (ArithmeticException e) {
            throw new ParserException("Block length arithmetic overflow at " + address, e);
        }

        if (declaredLength < minLength) {
            throw new ParserException(
                    "Declared length " + declaredLength + " < header+links " + minLength + " at " + address);
        }

        long blockEnd;
        try {
            blockEnd = MdfSafeMath.addExact(address, declaredLength, "blockEnd@" + address);
        } catch (ArithmeticException e) {
            throw new ParserException("Block end overflow at " + address, e);
        }
        if (blockEnd > access.fileSize()) {
            throw new ParserException(
                    "Block end " + blockEnd + " exceeds physical file size " + access.fileSize()
                            + " at address " + address);
        }

        List<Long> links = new ArrayList<>((int) linkCount);
        if (linkCount > 0) {
            int toRead = (int) linksBytes;
            ByteBuffer linkBuf = access.readOrdered(
                    address + MdfBlockHeader.COMMON_HEADER_SIZE, toRead, "links@" + address);
            for (int i = 0; i < linkCount; i++) {
                links.add(linkBuf.getLong());
            }
        }

        long dataSectionOffset = address + minLength;
        long declaredDataLength = declaredLength - minLength;
        MdfBlockType type = MdfBlockType.fromId(rawId);

        return new MdfBlockHeader(
                address,
                type,
                rawId,
                reserved,
                declaredLength,
                linkCount,
                links,
                blockEnd,
                dataSectionOffset,
                declaredDataLength
        );
    }
}
