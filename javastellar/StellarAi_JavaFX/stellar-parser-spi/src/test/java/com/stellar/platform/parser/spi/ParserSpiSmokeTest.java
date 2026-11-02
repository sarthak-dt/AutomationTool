package com.stellar.platform.parser.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserSpiSmokeTest {

    @Test
    void emptyParserContextReturnsNullOptions() {
        assertNull(EmptyParserContext.INSTANCE.getOption("any"));
    }

    @Test
    void parserExceptionCarriesMessage() {
        ParserException ex = new ParserException("boom");
        assertNotNull(ex.getMessage());
    }

    @Test
    void resumeCursorRoundTripAndRejectsCorruptPayload() {
        ParserResumeCursor cursor = ParserResumeCursor.mf4("dgIndex=1\nsegmentIndex=0");
        ParserResumeCursor restored = ParserResumeCursor.deserialize(cursor.serialize()).orElseThrow();
        assertEquals("mf4", restored.parserType());
        assertEquals(1, restored.cursorVersion());
        assertTrue(restored.isMf4V1());
        assertTrue(ParserResumeCursor.deserialize("not-a-cursor").isEmpty());
        assertTrue(ParserResumeCursor.deserialize("mf4\n0\npayload").isEmpty());
        assertTrue(ParserResumeCursor.deserialize("mf4\nnot-int\nx").isEmpty());
    }
}
