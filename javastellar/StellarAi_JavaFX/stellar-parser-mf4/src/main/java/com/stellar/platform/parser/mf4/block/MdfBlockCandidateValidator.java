package com.stellar.platform.parser.mf4.block;

import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.spi.ParserException;

import java.util.Optional;

/**
 * Structural MDF block-boundary validator.
 * <p>
 * A raw {@code ##} byte pair is never sufficient: candidates must survive
 * {@link MdfBlockHeaderReader} plus additional structural checks.
 */
public final class MdfBlockCandidateValidator {

    /** ASAM MDF 4 requires block addresses to be multiples of eight. */
    public static final int BLOCK_ALIGNMENT = 8;

    private MdfBlockCandidateValidator() {
    }

    /**
     * Validates whether {@code candidateOffset} is the start of a structurally sound MDF block.
     *
     * @return parsed header when valid; empty when the candidate must be ignored
     */
    public static Optional<MdfBlockHeader> validateBlockCandidate(
            MdfFileAccess access,
            long candidateOffset
    ) {
        if (access == null || candidateOffset < 0L) {
            return Optional.empty();
        }
        if ((candidateOffset % BLOCK_ALIGNMENT) != 0L) {
            return Optional.empty();
        }
        if (!access.canRead(candidateOffset, MdfBlockHeader.COMMON_HEADER_SIZE)) {
            return Optional.empty();
        }

        final MdfBlockHeader header;
        try {
            header = MdfBlockHeaderReader.read(access, candidateOffset);
        } catch (ParserException e) {
            return Optional.empty();
        }

        if (!isValidBlockIdForm(header.rawId())) {
            return Optional.empty();
        }
        if (header.isUnknownType()) {
            return Optional.empty();
        }
        // ASAM reserved field shall be zero for a well-formed common header.
        if (header.reserved() != 0) {
            return Optional.empty();
        }
        if (header.declaredLength() < MdfBlockHeader.COMMON_HEADER_SIZE) {
            return Optional.empty();
        }
        if (header.blockEnd() > access.fileSize()) {
            return Optional.empty();
        }
        if (header.linkCount() < 0L) {
            return Optional.empty();
        }

        long linksBytes = header.linkCount() * 8L;
        long minLength = MdfBlockHeader.COMMON_HEADER_SIZE + linksBytes;
        if (header.declaredLength() < minLength) {
            return Optional.empty();
        }
        if (header.dataSectionOffset() != candidateOffset + minLength) {
            return Optional.empty();
        }

        for (long link : header.links()) {
            if (link == 0L) {
                continue;
            }
            if (link < 0L || link >= access.fileSize()) {
                return Optional.empty();
            }
            if ((link % BLOCK_ALIGNMENT) != 0L) {
                return Optional.empty();
            }
            if (!access.canRead(link, MdfBlockHeader.COMMON_HEADER_SIZE)) {
                return Optional.empty();
            }
        }
        return Optional.of(header);
    }

    /**
     * MDF common-block id is four ASCII bytes starting with {@code ##} followed by two
     * printable characters (typically uppercase letters / space-padded type).
     */
    static boolean isValidBlockIdForm(String rawId) {
        if (rawId == null || rawId.length() != 4) {
            return false;
        }
        if (rawId.charAt(0) != '#' || rawId.charAt(1) != '#') {
            return false;
        }
        char c2 = rawId.charAt(2);
        char c3 = rawId.charAt(3);
        return isTypeChar(c2) && isTypeChar(c3);
    }

    private static boolean isTypeChar(char c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == ' ';
    }
}
