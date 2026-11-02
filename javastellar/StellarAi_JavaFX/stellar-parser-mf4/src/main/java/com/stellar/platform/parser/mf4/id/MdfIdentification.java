package com.stellar.platform.parser.mf4.id;

/**
 * Parsed MDF identification block (IDBLOCK) plus physical file facts.
 */
public record MdfIdentification(
        String fileSignature,
        String formatIdentifier,
        String programIdentifier,
        MdfVersion version,
        int unfinalizedFlags,
        int customUnfinalizedFlags,
        long physicalFileSize,
        boolean littleEndian,
        boolean headerConsistent
) {

    public static final long ID_BLOCK_SIZE = 64L;
    public static final long HD_BLOCK_OFFSET = 64L;
    public static final String EXPECTED_SIGNATURE = "MDF";
    /**
     * Vector / tool convention for unfinalized MDF4 files written before finalization.
     * Still a valid MDF4 container; must not be rejected as non-MDF.
     */
    public static final String UNFINALIZED_SIGNATURE = "UNFINMF";

    public boolean isMdf4() {
        return version != null && version.isMdf4();
    }

    public boolean hasUnfinalizedFlags() {
        return unfinalizedFlags != 0 || customUnfinalizedFlags != 0 || hasUnfinalizedFileSignature();
    }

    /**
     * {@code true} when IDBLOCK file id uses the UnFinMF signature variant.
     */
    public boolean hasUnfinalizedFileSignature() {
        return fileSignature != null
                && fileSignature.toUpperCase(java.util.Locale.ROOT).startsWith(UNFINALIZED_SIGNATURE);
    }
}
