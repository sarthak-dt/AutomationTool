package com.stellar.platform.parser.spi;

/**
 * Opaque context supplied to parsers (options, callbacks, limits).
 * Intentionally minimal at the skeleton stage.
 */
public interface ParserContext {

    /**
     * @return a named option value, or {@code null} if absent
     */
    Object getOption(String key);
}
