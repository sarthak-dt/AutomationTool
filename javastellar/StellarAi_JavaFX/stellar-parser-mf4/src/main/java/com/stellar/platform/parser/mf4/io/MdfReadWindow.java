package com.stellar.platform.parser.mf4.io;

import com.stellar.platform.parser.spi.ParserException;

/**
 * Reusable sequential file window. Physical {@link java.nio.channels.FileChannel} reads
 * are proportional to window refills, not to individual records.
 * <p>
 * Window size is a runtime I/O tuning parameter, not an MDF format assumption.
 */
public final class MdfReadWindow {

    public static final String WINDOW_BYTES_PROPERTY = "stellar.mf4.readWindowBytes";
    public static final int DEFAULT_WINDOW_BYTES = 4 * 1024 * 1024;

    private final MdfFileAccess access;
    private final byte[] window;
    private long windowStart;
    private int windowValid;

    public MdfReadWindow(MdfFileAccess access) {
        this(access, configuredWindowBytes());
    }

    public MdfReadWindow(MdfFileAccess access, int windowBytes) {
        this.access = access;
        int size = windowBytes < 512 ? 512 : windowBytes;
        this.window = new byte[size];
        this.windowStart = Long.MIN_VALUE;
        this.windowValid = 0;
    }

    public static int configuredWindowBytes() {
        String raw = System.getProperty(WINDOW_BYTES_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_WINDOW_BYTES;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return Math.max(512, parsed);
        } catch (NumberFormatException e) {
            return DEFAULT_WINDOW_BYTES;
        }
    }

    public int capacity() {
        return window.length;
    }

    /**
     * Ensures {@code [absoluteOffset, absoluteOffset+need)} is in the window, refilling if required.
     * Returns the index of {@code absoluteOffset} in {@link #array()}.
     */
    public int ensure(long absoluteOffset, int need, long limitExclusive, String context)
            throws ParserException {
        if (need < 0) {
            throw new ParserException(context + ": negative length");
        }
        if (need > window.length) {
            throw new ParserException(context + ": request " + need + " exceeds window " + window.length);
        }
        long end = absoluteOffset + (long) need;
        if (absoluteOffset >= windowStart && end <= windowStart + windowValid) {
            return (int) (absoluteOffset - windowStart);
        }
        long remaining = limitExclusive - absoluteOffset;
        if (remaining < need) {
            throw new ParserException(context + ": read past payload limit");
        }
        int toRead = (int) Math.min(window.length, remaining);
        access.readInto(absoluteOffset, window, 0, toRead, context);
        windowStart = absoluteOffset;
        windowValid = toRead;
        return 0;
    }

    public boolean contains(long absoluteOffset, int need) {
        return absoluteOffset >= windowStart && absoluteOffset + (long) need <= windowStart + windowValid;
    }

    public byte[] array() {
        return window;
    }

    public long windowStart() {
        return windowStart;
    }

    public int windowValid() {
        return windowValid;
    }
}
