package com.stellar.platform.parser.spi;

/**
 * Thrown when a measurement file cannot be parsed.
 */
public class ParserException extends Exception {

    public ParserException(String message) {
        super(message);
    }

    public ParserException(String message, Throwable cause) {
        super(message, cause);
    }
}
