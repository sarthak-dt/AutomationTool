package com.stellar.platform.dbc.decode;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.parse.DbcDiagnosticCode;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.SourceReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBD2 multiplexor signal {@code S} often carries service+0x40 (e.g. 0x41=65) which exceeds
 * the DBC-declared [0|15] range. Extraction must remain correct; multiplexor range is not warned
 * (selector, not a physical measurement).
 */
class Obd2SignalSRangeCompatibilityTest {

    @TempDir
    Path temp;

    @Test
    void signalS_motorolaUnsigned_physicalEqualsRaw_whenOutsideDbcMax() throws Exception {
        Path dbc = temp.resolve("obd2-s.dbc");
        Files.writeString(dbc, """
                VERSION ""
                NS_ :
                BS_:
                BU_:
                BO_ 2024 OBD2: 8 Vector__XXX
                 SG_ S M : 15|8@0+ (1,0) [0|15] "" Vector__XXX
                 SG_ S01PID m65M : 23|8@0+ (1,0) [0|255] "" Vector__XXX
                """);

        DbcDatabase database = new DbcParser().parse(dbc);
        DbcMessage message = database.findMessage(0x7E8L, false).orElseThrow();
        DbcSignal signal = message.signals().stream()
                .filter(s -> s.name().equals("S"))
                .findFirst()
                .orElseThrow();

        assertEquals(15, signal.startBit());
        assertEquals(8, signal.bitLength());
        assertEquals(com.stellar.platform.dbc.model.DbcByteOrder.MOTOROLA, signal.byteOrder());
        assertEquals(com.stellar.platform.dbc.model.DbcValueType.UNSIGNED, signal.valueType());
        assertEquals(1.0, signal.factor());
        assertEquals(0.0, signal.offset());
        assertEquals(0.0, signal.minimum());
        assertEquals(15.0, signal.maximum());

        // Vector Motorola startBit=15 is MSB of payload byte 1 (bit 15).
        // OBD positive response service 0x01 → 0x41 often sits in that byte.
        byte[] payload = new byte[]{0x03, (byte) 0x41, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        CanonicalCanFrame frame = new CanonicalCanFrame(
                0L, 1_000L, 0, 0x7E8L, false, FrameDirection.RX, 8, payload,
                FrameQuality.OK, new SourceReference(1L, 0L)
        );

        DecodingResult result = new DefaultDbcDecoder().decode(frame, database);
        assertEquals(DecodeStatus.DECODED, result.status(),
                "status=" + result.status() + " diags=" + result.diagnostics());
        assertFalse(result.diagnostics().stream()
                        .anyMatch(d -> d.code() == DbcDiagnosticCode.OUT_OF_RANGE_PHYSICAL_VALUE),
                String.valueOf(result.diagnostics()));

        var decodedS = result.signals().stream().filter(s -> s.signalName().equals("S")).findFirst().orElseThrow();
        assertEquals(65L, decodedS.rawValue());
        assertEquals(65.0, decodedS.physicalValue());
        assertEquals(65.0, decodedS.rawValue() * signal.factor() + signal.offset());
        assertTrue(PhysicalValueConverter.isOutsideBounds(
                decodedS.physicalValue(), signal.minimum(), signal.maximum()));
        assertFalse(Double.isNaN(decodedS.physicalValue()));
        assertFalse(Double.isInfinite(decodedS.physicalValue()));
    }
}
