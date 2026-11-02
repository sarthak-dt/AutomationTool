package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.mf4.analysis.MdfConsistencyAnalyser;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDynamicSchemaBuilder;
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

class MdfCaCompositionTest {

    @TempDir
    Path temp;

    @Test
    void simpleCaComposition_expandsChildrenAndDecodes() throws Exception {
        Path file = temp.resolve("ca-simple.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("MDF     ", "4.10", 410, 0);
            b.writeDt(new byte[128]);
            long payload = b.writeCn(0L, 0L, b.writeTx("Arr.DataBytes"), 0, 0, 0, 0, 14, 0, 16, 0, 0);
            long dlc = b.writeCn(payload, 0L, b.writeTx("Arr.DLC"), 0, 0, 0, 0, 13, 0, 4, 0, 0);
            long dir = b.writeCn(dlc, 0L, b.writeTx("Arr.Dir"), 0, 0, 0, 0, 12, 0, 1, 0, 0);
            long id = b.writeCn(dir, 0L, b.writeTx("Arr.ID"), 0, 0, 0, 0, 9, 0, 11, 0, 0);
            long bus = b.writeCn(id, 0L, b.writeTx("Arr.BusChannel"), 0, 0, 0, 0, 8, 0, 8, 0, 0);
            long ca = b.writeCa(bus, 0, 0, 1, 0, 0, new long[]{1});
            long composite = b.writeCn(0L, ca, b.writeTx("Parent"), 0, 0, 0, 10, 8, 0, 64, 0, 0x400);
            long time = b.writeCn(composite, 0L, b.writeTx("Timestamp"), 0, 2, 1, 4, 0, 0, 64, 0, 0);
            long si = b.writeSi(b.writeTx("CAN"), b.writeTx("CAN"), 1, 2);
            long cg = b.writeCg(0, time, 0, si, 1, 1, 0x6, 16, 0);
            long dt = b.writeDt(canRecord());
            long dg = b.writeDg(0, cg, dt, 0);
            b.writeHd(dg, 0, 0);
        }

        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            var id = MdfIdentificationReader.read(access);
            var graph = MdfBlockGraphDiscovery.discover(access, CancellationToken.NONE);
            var meta = MdfSchemaExtractor.extract(access, graph, CancellationToken.NONE);
            assertFalse(meta.channelArrays().isEmpty());
            assertTrue(meta.channels().stream().filter(c -> c.compositionMember()).count() >= 4);
            var schemas = new MdfDynamicSchemaBuilder().build(
                    graph, meta, MdfConsistencyAnalyser.analyse(id, graph, meta));
            assertTrue(schemas.recordSchemas().getFirst().channelFields().stream()
                    .anyMatch(f -> f.compositionMember()));
        }

        var decoded = new MdfDynamicRecordDecoder().decodeAll(file, ReaderContext.ofBatchSize(4));
        assertEquals(1, decoded.frames().size(), String.valueOf(decoded.diagnostics()));
        assertEquals(0x123L, decoded.frames().getFirst().canId());
    }

    @Test
    void multipleCaElements_andNestedCa_areWalked() throws Exception {
        Path file = temp.resolve("ca-nested.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("MDF     ", "4.10", 410, 0);
            b.writeDt(new byte[128]);
            long el1 = b.writeCn(0L, 0L, b.writeTx("A0"), 0, 0, 0, 0, 8, 0, 8, 0, 0);
            long el0 = b.writeCn(el1, 0L, b.writeTx("A1"), 0, 0, 0, 0, 9, 0, 8, 0, 0);
            long inner = b.writeCa(el0, 0, 0, 1, 0, 0, new long[]{2});
            long outer = b.writeCa(inner, 0, 0, 1, 0, 0, new long[]{1});
            long parent = b.writeCn(0L, outer, b.writeTx("Parent"), 0, 0, 0, 0, 8, 0, 16, 0, 0);
            long time = b.writeCn(parent, 0L, b.writeTx("Timestamp"), 0, 2, 1, 4, 0, 0, 64, 0, 0);
            long cg = b.writeCg(0, time, 1, 1, 0, 16, 0);
            long dt = b.writeDt(new byte[16]);
            long dg = b.writeDg(0, cg, dt, 0);
            b.writeHd(dg, 0, 0);
        }
        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            var graph = MdfBlockGraphDiscovery.discover(access, CancellationToken.NONE);
            var meta = MdfSchemaExtractor.extract(access, graph, CancellationToken.NONE);
            assertTrue(meta.channelArrays().size() >= 2);
            assertTrue(meta.channels().stream().anyMatch(c -> c.name().equals("A0")));
            assertTrue(meta.channels().stream().anyMatch(c -> c.name().equals("A1")));
        }
    }

    @Test
    void invalidCaDimensions_emitUnsupportedDiagnostic() throws Exception {
        Path file = temp.resolve("ca-bad-dim.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("MDF     ", "4.10", 410, 0);
            b.writeDt(new byte[64]);
            long child = b.writeCn(0L, 0L, b.writeTx("Child"), 0, 0, 0, 0, 0, 0, 8, 0, 0);
            long ca = b.writeCa(child, 0, 0, 1, 0, 0, new long[]{0});
            long parent = b.writeCn(0L, ca, b.writeTx("Parent"), 0, 0, 0, 0, 0, 0, 8, 0, 0);
            long cg = b.writeCg(0, parent, 1, 1, 0, 8, 0);
            long dt = b.writeDt(new byte[8]);
            long dg = b.writeDg(0, cg, dt, 0);
            b.writeHd(dg, 0, 0);
        }
        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            var graph = MdfBlockGraphDiscovery.discover(access, CancellationToken.NONE);
            var meta = MdfSchemaExtractor.extract(access, graph, CancellationToken.NONE);
            assertTrue(meta.unsupportedStructures().stream().anyMatch(s -> s.contains("invalid dimensions")));
        }
    }

    private static byte[] canRecord() {
        ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buf.putDouble(1.5d);
        buf.put((byte) 1);
        buf.putShort((short) 0x123);
        buf.put((byte) 0);
        buf.put((byte) 1);
        buf.put((byte) 2);
        buf.put((byte) 0x11);
        buf.put((byte) 0x22);
        return buf.array();
    }
}
