package com.stellar.platform.dbc.decode;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.SourceReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DbcMessageDecodePlanTest {

    private static DbcDatabase database;
    private static final DefaultDbcDecoder DECODER = new DefaultDbcDecoder();

    @BeforeAll
    static void load() throws Exception {
        database = new DbcParser().parse(Path.of(
                DbcMessageDecodePlanTest.class.getClassLoader().getResource("dbc/common_decoder.dbc").toURI()));
    }

    @Test
    void planIsReusedAndOutputIsStableAcrossManyFrames() {
        var message = database.messages().iterator().next();
        DbcMessageDecodePlan a = DbcMessageDecodePlan.compile(message);
        DbcMessageDecodePlan b = DbcMessageDecodePlan.compile(message);
        assertEquals(a.useExplicitMulVal(), b.useExplicitMulVal());
        assertEquals(a.hasSecondary(), b.hasSecondary());
        assertEquals(a.selectorNames(), b.selectorNames());
        assertEquals(a.leafNames(), b.leafNames());
        assertEquals(a.graph().muxLevelCount(), b.graph().muxLevelCount());

        byte[] data = new byte[]{0x00, 42};
        DecodingResult first = DECODER.decode(frame(0x600, data), database);
        for (int i = 0; i < 64; i++) {
            DecodingResult again = DECODER.decode(frame(0x600, data), database);
            assertEquals(first.status(), again.status());
            assertEquals(first.signals().size(), again.signals().size());
            for (int s = 0; s < first.signals().size(); s++) {
                assertEquals(first.signals().get(s).signalName(), again.signals().get(s).signalName());
                assertEquals(first.signals().get(s).physicalValue(), again.signals().get(s).physicalValue(), 1e-12);
                assertEquals(first.signals().get(s).rawValue(), again.signals().get(s).rawValue());
            }
        }
    }

    @Test
    void decodeMatchedDoesNotEmitUnmatchedDiagnostic() {
        var message = database.findMessage(0x600, false).orElseThrow();
        DecodingResult result = DECODER.decodeMatched(frame(0x600, new byte[]{0x00, 42}), database, message);
        assertEquals(DecodeStatus.DECODED, result.status());
        assertEquals(0, result.diagnostics().stream()
                .filter(d -> d.code() == com.stellar.platform.dbc.parse.DbcDiagnosticCode.UNMATCHED_CAN_ID)
                .count());
    }

    private static CanonicalCanFrame frame(long canId, byte[] data) {
        return new CanonicalCanFrame(
                0, 0L, 1, canId, false, FrameDirection.RX, data.length, data,
                FrameQuality.OK, SourceReference.ofLine(1)
        );
    }
}