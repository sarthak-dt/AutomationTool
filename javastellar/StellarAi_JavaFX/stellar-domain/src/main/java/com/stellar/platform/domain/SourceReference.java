package com.stellar.platform.domain;

/**
 * Points back to the origin of a parsed item in the source measurement file.
 * Use {@code sourceByteOffset = -1} when a byte offset is unavailable.
 */
public record SourceReference(long sourceLineNumber, long sourceByteOffset) {

    public static SourceReference ofLine(long sourceLineNumber) {
        return new SourceReference(sourceLineNumber, -1L);
    }
}
