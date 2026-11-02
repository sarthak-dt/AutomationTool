package com.stellar.platform.parser.spi;

/**
 * Empty default context used when no options are required.
 */
public final class EmptyParserContext implements ParserContext {

    public static final EmptyParserContext INSTANCE = new EmptyParserContext();

    private EmptyParserContext() {
    }

    @Override
    public Object getOption(String key) {
        return null;
    }
}
