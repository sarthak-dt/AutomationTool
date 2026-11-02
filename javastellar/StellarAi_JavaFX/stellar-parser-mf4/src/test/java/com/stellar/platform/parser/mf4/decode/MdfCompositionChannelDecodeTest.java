package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.parser.mf4.analysis.MdfConsistencyAnalyser;
import com.stellar.platform.parser.mf4.decode.MdfDecodeDiagnosticCode;
import com.stellar.platform.parser.mf4.dynamicschema.MdfCanRole;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDynamicSchemaBuilder;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDynamicSchemaResult;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraphDiscovery;
import com.stellar.platform.parser.mf4.id.MdfIdentificationReader;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.schema.MdfSchemaExtractor;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.parser.spi.ReaderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic composite-channel fixture: CG → bus-event parent → composition children.
 */
class MdfCompositionChannelDecodeTest {

    @TempDir
    Path temp;

    @Test
    void compositionChildren_areDiscoveredClassifiedAndDecodedToCanonicalCanFrame() throws Exception {
        Path file = temp.resolve("composite-can.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("MDF     ", "4.10", 410, 0);
            // Reserve the HD region (64..168) so later writeHd does not clobber CN/TX blocks.
            b.writeDt(new byte[128]);

            // Composition siblings — use explicit composition-link overload (composition=0).
            long payload = b.writeCn(0L, 0L, b.writeTx("Event.DataBytes"), 0, 0, 0, 9, 14, 0, 16, 0, 0);
            long dlc = b.writeCn(payload, 0L, b.writeTx("Event.DLC"), 0, 0, 0, 0, 13, 0, 4, 0, 0);
            long dir = b.writeCn(dlc, 0L, b.writeTx("Event.Dir"), 0, 0, 0, 0, 12, 0, 1, 0, 0);
            long id = b.writeCn(dir, 0L, b.writeTx("Event.ID"), 0, 0, 0, 0, 9, 0, 11, 0, 0);
            long bus = b.writeCn(id, 0L, b.writeTx("Event.BusChannel"), 0, 0, 0, 0, 8, 0, 8, 0, 0);

            // Parent: MIME sample + bus-event flag + composition → first child (bus)
            long composite = b.writeCn(
                    0L, bus, b.writeTx("BusEvent"), 0,
                    0, 0, 10, 8, 0, 64, 0, 0x400
            );
            long time = b.writeCn(composite, 0L, b.writeTx("Timestamp"), 0, 2, 1, 4, 0, 0, 64, 0, 0);

            // 8 timestamp + 8 event = 16 data bytes; bus-event CG flags
            long cg = b.writeCg(0, time, 1, 1, 0x6, 16, 0);
            long dt = b.writeDt(buildRecord());
            long dg = b.writeDg(0, cg, dt, 0);
            b.writeHd(dg, 0, 0);
        }

        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            var identification = MdfIdentificationReader.read(access);
            var graph = MdfBlockGraphDiscovery.discover(access, CancellationToken.NONE);
            var meta = MdfSchemaExtractor.extract(access, graph, CancellationToken.NONE);

            assertTrue(meta.channels().stream().anyMatch(c -> c.name().equals("BusEvent")));
            var names = meta.channels().stream().map(c -> c.name() + (c.compositionMember() ? "[child]" : "[top]")).toList();
            long children = meta.channels().stream().filter(c -> c.compositionMember()).count();
            assertTrue(children >= 4, "composition children=" + children + " all=" + names);
            assertTrue(meta.channels().stream().anyMatch(c -> c.compositionMember() && c.name().endsWith(".ID")), names.toString());
            assertTrue(meta.channels().stream().anyMatch(c -> c.compositionMember()
                    && (c.name().endsWith(".DataBytes") || c.name().toLowerCase().contains("data"))), names.toString());
            assertTrue(meta.channels().stream().anyMatch(c -> c.compositionMember() && c.name().endsWith(".DLC")), names.toString());
            assertTrue(meta.channels().stream().anyMatch(c -> c.compositionMember() && c.name().endsWith(".Dir")), names.toString());
            assertTrue(meta.channels().stream().anyMatch(c -> c.compositionMember() && c.name().endsWith(".BusChannel")), names.toString());

            var analysis = MdfConsistencyAnalyser.analyse(identification, graph, meta);
            MdfDynamicSchemaResult schemas = new MdfDynamicSchemaBuilder().build(graph, meta, analysis);
            assertEquals(1, schemas.recordSchemas().size());
            var schema = schemas.recordSchemas().getFirst();
            assertTrue(schema.channelFields().stream().anyMatch(f -> f.compositionContainer()));
            assertTrue(schema.channelFields().stream().anyMatch(f ->
                    f.compositionMember() && f.canRoleCandidate().role() == MdfCanRole.CAN_ID));
            assertTrue(schema.channelFields().stream().anyMatch(f ->
                    f.compositionMember() && f.canRoleCandidate().role() == MdfCanRole.PAYLOAD));
            assertTrue(schema.channelFields().stream().anyMatch(f ->
                    f.compositionMember() && f.canRoleCandidate().role() == MdfCanRole.DLC));
            assertTrue(schema.channelFields().stream().anyMatch(f ->
                    f.compositionMember() && f.canRoleCandidate().role() == MdfCanRole.DIRECTION));
            assertTrue(schema.channelFields().stream().anyMatch(f ->
                    f.compositionMember() && f.canRoleCandidate().role() == MdfCanRole.BUS_CHANNEL));
        }

        MdfDynamicRecordDecoder.DecodeResult decoded =
                new MdfDynamicRecordDecoder().decodeAll(file, ReaderContext.ofBatchSize(8));
        assertEquals(1, decoded.frames().size(), String.valueOf(decoded.diagnostics()));
        CanonicalCanFrame frame = decoded.frames().getFirst();
        assertEquals(0x123L, frame.canId());
        assertEquals(2, frame.dlc());
        assertEquals(FrameDirection.TX, frame.direction());
        assertEquals(1, frame.channel());
        assertEquals(2, frame.data().length);
        assertEquals(0x11, frame.data()[0] & 0xFF);
        assertEquals(0x22, frame.data()[1] & 0xFF);
        assertFalse(frame.extended());
        assertTrue(
                decoded.diagnostics().stream().noneMatch(d ->
                        d.code() == MdfDecodeDiagnosticCode.UNSUPPORTED_DATA_TYPE
                                && d.reason() != null
                                && d.reason().contains("BusEvent")),
                "composition parent must not emit per-record UNSUPPORTED_DATA_TYPE: " + decoded.diagnostics()
        );
    }

    private static byte[] buildRecord() {
        ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buf.putDouble(1.5d);          // Timestamp @0
        buf.put((byte) 1);            // BusChannel @8
        buf.putShort((short) 0x123);  // ID @9 (11 bits used)
        buf.put((byte) 0);            // padding @11 (ID only uses 11 bits)
        buf.put((byte) 1);            // Dir @12 = TX
        buf.put((byte) 2);            // DLC @13 = 2
        buf.put((byte) 0x11);         // DataBytes @14
        buf.put((byte) 0x22);
        return buf.array();
    }
}
