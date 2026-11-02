package com.stellar.platform.parser.mf4.io;

/**
 * Overflow-safe arithmetic for MF4 offsets and lengths (always {@code long}).
 */
public final class MdfSafeMath {

    private MdfSafeMath() {
    }

    public static long addExact(long a, long b, String context) {
        if (a < 0 || b < 0) {
            throw new ArithmeticException("Negative operand in " + context + ": " + a + " + " + b);
        }
        long result = a + b;
        if (result < 0 || result < a) {
            throw new ArithmeticException("Overflow in " + context + ": " + a + " + " + b);
        }
        return result;
    }

    public static long multiplyExact(long a, long b, String context) {
        if (a < 0 || b < 0) {
            throw new ArithmeticException("Negative operand in " + context);
        }
        if (a == 0 || b == 0) {
            return 0L;
        }
        if (a > Long.MAX_VALUE / b) {
            throw new ArithmeticException("Overflow in " + context + ": " + a + " * " + b);
        }
        return a * b;
    }

    public static void requireInRange(long offset, long length, long fileSize, String context) {
        if (offset < 0) {
            throw new IllegalArgumentException(context + ": offset < 0: " + offset);
        }
        if (length < 0) {
            throw new IllegalArgumentException(context + ": length < 0: " + length);
        }
        long end = addExact(offset, length, context);
        if (end > fileSize) {
            throw new IllegalArgumentException(
                    context + ": read [" + offset + "," + end + ") exceeds file size " + fileSize);
        }
    }
}
