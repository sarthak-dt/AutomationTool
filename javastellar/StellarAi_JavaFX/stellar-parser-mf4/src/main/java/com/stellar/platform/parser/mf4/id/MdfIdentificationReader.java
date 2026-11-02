package com.stellar.platform.parser.mf4.id;

import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.spi.ParserException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Reads and validates the MDF IDBLOCK from file contents (not extension alone).
 */
public final class MdfIdentificationReader {

    private MdfIdentificationReader() {
    }

    public static boolean looksLikeMdf(MdfFileAccess access) {
        if (access.fileSize() < MdfIdentification.ID_BLOCK_SIZE) {
            return false;
        }
        try {
            byte[] sig = access.readFully(0, 8, "ID signature probe");
            String text = new String(sig, StandardCharsets.US_ASCII).trim();
            return isRecognizedMdfSignature(text);
        } catch (ParserException e) {
            return false;
        }
    }

    /**
     * Accepts finalized {@code MDF} and unfinalized {@code UnFinMF} IDBLOCK signatures.
     */
    public static boolean isRecognizedMdfSignature(String fileSignature) {
        if (fileSignature == null || fileSignature.isBlank()) {
            return false;
        }
        String upper = fileSignature.trim().toUpperCase(Locale.ROOT);
        return upper.startsWith(MdfIdentification.EXPECTED_SIGNATURE)
                || upper.startsWith(MdfIdentification.UNFINALIZED_SIGNATURE);
    }

    public static MdfIdentification read(MdfFileAccess access) throws ParserException {
        if (access.fileSize() < MdfIdentification.ID_BLOCK_SIZE) {
            throw new ParserException(
                    "File too small for MDF IDBLOCK (" + access.fileSize() + " bytes): " + access.path());
        }

        ByteBuffer buf = access.readOrdered(0, (int) MdfIdentification.ID_BLOCK_SIZE, "IDBLOCK");
        byte[] all = new byte[(int) MdfIdentification.ID_BLOCK_SIZE];
        buf.get(all);

        String fileSignature = asciiField(all, 0, 8).trim();
        String formatIdentifier = asciiField(all, 8, 8).trim();
        String programIdentifier = asciiField(all, 16, 8).trim();
        // bytes 24-27 reserved
        int versionCode = Short.toUnsignedInt(ByteBuffer.wrap(all, 28, 2).order(MdfFileAccess.MDF4_BYTE_ORDER).getShort());
        int unfinalizedFlags = 0;
        int customUnfinalizedFlags = 0;
        if (access.fileSize() >= MdfIdentification.ID_BLOCK_SIZE) {
            unfinalizedFlags = Short.toUnsignedInt(
                    ByteBuffer.wrap(all, 60, 2).order(MdfFileAccess.MDF4_BYTE_ORDER).getShort());
            customUnfinalizedFlags = Short.toUnsignedInt(
                    ByteBuffer.wrap(all, 62, 2).order(MdfFileAccess.MDF4_BYTE_ORDER).getShort());
        }

        if (!isRecognizedMdfSignature(fileSignature)) {
            throw new ParserException(
                    "Not an MDF file: expected signature '" + MdfIdentification.EXPECTED_SIGNATURE
                            + "' or '" + MdfIdentification.UNFINALIZED_SIGNATURE
                            + "', found '" + fileSignature + "' in " + access.path());
        }

        MdfVersion version = MdfVersion.parse(formatIdentifier, versionCode)
                .orElseGet(() -> versionCode > 0 ? MdfVersion.ofCode(versionCode) : null);
        if (version == null || !version.isMdf4()) {
            throw new ParserException(
                    "Unsupported or non-MDF4 version '" + formatIdentifier + "' (code=" + versionCode + ") in "
                            + access.path());
        }

        boolean versionConsistent = version.versionCode() == versionCode
                || Math.abs(version.versionCode() - versionCode) == 0
                || versionCode == 0
                || formatIdentifier.startsWith("4.");
        // Soft consistency: string and numeric should both indicate 4.x
        boolean headerConsistent = version.isMdf4()
                && formatIdentifier.startsWith("4.")
                && (versionCode == 0 || versionCode / 100 == 4);

        if (!headerConsistent) {
            throw new ParserException(
                    "MDF identification/header inconsistency: format='" + formatIdentifier
                            + "', versionCode=" + versionCode + " in " + access.path());
        }

        return new MdfIdentification(
                fileSignature,
                formatIdentifier,
                programIdentifier,
                version,
                unfinalizedFlags,
                customUnfinalizedFlags,
                access.fileSize(),
                true,
                versionConsistent && headerConsistent
        );
    }

    private static String asciiField(byte[] all, int offset, int length) {
        return new String(all, offset, length, StandardCharsets.US_ASCII);
    }
}
