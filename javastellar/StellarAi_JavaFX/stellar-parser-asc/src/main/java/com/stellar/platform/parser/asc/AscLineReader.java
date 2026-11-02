package com.stellar.platform.parser.asc;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;

/**
 * Bounded line reader. A single corrupt line cannot grow without limit.
 */
final class AscLineReader implements Closeable {

    static final int MAX_LINE_CHARS = 8_192;

    private final Reader in;
    private final StringBuilder buffer = new StringBuilder(256);
    private boolean skipRemainder;
    private boolean finished;

    AscLineReader(Reader in) {
        this.in = in;
    }

    Line next() throws IOException {
        if (finished) {
            return null;
        }
        buffer.setLength(0);
        boolean overflow = skipRemainder;
        skipRemainder = false;
        while (true) {
            int ch = in.read();
            if (ch < 0) {
                finished = true;
                if (buffer.isEmpty() && !overflow) {
                    return null;
                }
                return new Line(buffer.toString(), overflow);
            }
            if (ch == '\n') {
                return new Line(buffer.toString(), overflow);
            }
            if (ch == '\r') {
                continue;
            }
            if (overflow) {
                continue;
            }
            if (buffer.length() >= MAX_LINE_CHARS) {
                overflow = true;
                skipRemainder = false;
                continue;
            }
            buffer.append((char) ch);
        }
    }

    record Line(String text, boolean overflow) {
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
