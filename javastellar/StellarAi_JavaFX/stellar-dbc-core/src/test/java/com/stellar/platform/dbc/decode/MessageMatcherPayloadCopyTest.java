package com.stellar.platform.dbc.decode;

import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.SourceReference;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageMatcherPayloadCopyTest {

    @Test
    void matchUsesCanIdLookupWithoutRequiringPayloadMaterializationBeyondConstructor() {
        DbcMessage message = new DbcMessage(
                CanIdKey.fromFrame(0x123, false),
                "Demo",
                8,
                "",
                List.of(),
                ""
        );
        DbcDatabase database = new DbcDatabase("", List.of(), List.of(message), List.of());
        byte[] payload = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        CanonicalCanFrame frame = new CanonicalCanFrame(
                0, 0L, 1, 0x123, false, FrameDirection.RX, 8, payload,
                FrameQuality.OK, SourceReference.ofLine(1)
        );
        Optional<DbcMessage> matched = MessageMatcher.match(frame, database);
        assertTrue(matched.isPresent());
        assertEquals("Demo", matched.get().name());
        assertEquals(8, frame.payloadLength());
        assertEquals(8, frame.dlc());
    }
}