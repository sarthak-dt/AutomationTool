package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.mf4.analysis.MdfConsistencyAnalyser;
import com.stellar.platform.parser.mf4.dynamicschema.MdfBusKind;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDynamicSchemaBuilder;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraphDiscovery;
import com.stellar.platform.parser.mf4.id.MdfIdentificationReader;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.recovery.MdfRecoveryStatus;
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

class MdfMixedBusChannelGroupTest {

    @TempDir
    Path temp;

    @Test
    void mixedCanLinError_emitsOnlyCanFramesAndContinues() throws Exception {
        Path file = temp.resolve("mixed-bus.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("MDF     ", "4.10", 410, 0);
            b.writeDt(new byte[128]);

            // CAN CG recordId=1
            long payload = b.writeCn(0L, 0L, b.writeTx("CAN.DataBytes"), 0, 0, 0, 0, 14, 0, 16, 0, 0);
            long dlc = b.writeCn(payload, 0L, b.writeTx("CAN.DLC"), 0, 0, 0, 0, 13, 0, 4, 0, 0);
            long dir = b.writeCn(dlc, 0L, b.writeTx("CAN.Dir"), 0, 0, 0, 0, 12, 0, 1, 0, 0);
            long id = b.writeCn(dir, 0L, b.writeTx("CAN.ID"), 0, 0, 0, 0, 9, 0, 11, 0, 0);
            long bus = b.writeCn(id, 0L, b.writeTx("CAN.BusChannel"), 0, 0, 0, 0, 8, 0, 8, 0, 0);
            long composite = b.writeCn(0L, bus, b.writeTx("CAN_DataFrame"), 0, 0, 0, 10, 8, 0, 64, 0, 0x400);
            long canTime = b.writeCn(composite, 0L, b.writeTx("Timestamp"), 0, 2, 1, 4, 0, 0, 64, 0, 0);
            long canSi = b.writeSi(b.writeTx("CAN"), b.writeTx("CAN"), 1, 2);

            // LIN CG recordId=2
            long linId = b.writeCn(0L, 0L, b.writeTx("LIN.ID"), 0, 0, 0, 0, 8, 0, 8, 0, 0);
            long linTime = b.writeCn(linId, 0L, b.writeTx("Timestamp"), 0, 2, 1, 4, 0, 0, 64, 0, 0);
            long linSi = b.writeSi(b.writeTx("LIN"), b.writeTx("LIN"), 1, 3);

            // Error CG recordId=3
            long err = b.writeCn(0L, 0L, b.writeTx("ChecksumError"), 0, 0, 0, 0, 8, 0, 8, 0, 0);
            long errTime = b.writeCn(err, 0L, b.writeTx("Timestamp"), 0, 2, 1, 4, 0, 0, 64, 0, 0);
            long errSi = b.writeSi(b.writeTx("ERR"), b.writeTx("Error"), 1, 0);

            long errCg = b.writeCg(0, errTime, 0, errSi, 3, 1, 0x6, 16, 0);
            long linCg = b.writeCg(errCg, linTime, 0, linSi, 2, 1, 0x6, 16, 0);
            long canCg = b.writeCg(linCg, canTime, 0, canSi, 1, 1, 0x6, 16, 0);

            ByteBuffer dt = ByteBuffer.allocate(1 + 16 + 1 + 16 + 1 + 16).order(ByteOrder.LITTLE_ENDIAN);
            dt.put((byte) 1);
            dt.put(canRecord(0x111));
            dt.put((byte) 2);
            dt.put(linRecord());
            dt.put((byte) 3);
            dt.put(errorRecord());
            long data = b.writeDt(dt.array());
            long dg = b.writeDg(0, canCg, data, 1);
            b.writeHd(dg, 0, 0);
        }

        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            var identification = MdfIdentificationReader.read(access);
            var graph = MdfBlockGraphDiscovery.discover(access, CancellationToken.NONE);
            var meta = MdfSchemaExtractor.extract(access, graph, CancellationToken.NONE);
            var schemas = new MdfDynamicSchemaBuilder().build(
                    graph, meta, MdfConsistencyAnalyser.analyse(identification, graph, meta));
            assertEquals(3, schemas.recordSchemas().size());
            assertTrue(schemas.recordSchemas().stream().anyMatch(s -> s.busKind() == MdfBusKind.CAN));
            assertTrue(schemas.recordSchemas().stream().anyMatch(s -> s.busKind() == MdfBusKind.LIN));
            assertTrue(schemas.recordSchemas().stream().anyMatch(s -> s.busKind() == MdfBusKind.ERROR_STATUS));
        }

        var decoded = new MdfDynamicRecordDecoder().decodeAll(file, ReaderContext.ofBatchSize(8));
        assertEquals(1, decoded.frames().size(), String.valueOf(decoded.diagnostics()));
        assertEquals(0x111L, decoded.frames().getFirst().canId());
        assertTrue(decoded.diagnostics().stream().anyMatch(d ->
                d.code() == MdfDecodeDiagnosticCode.NON_CAN_CHANNEL_GROUP_SKIPPED));
        assertTrue(decoded.recoveryResults().stream().noneMatch(r ->
                r.status() == MdfRecoveryStatus.PARTIALLY_RECOVERED
                        && r.diagnostics().stream().anyMatch(d ->
                        d.reason().toLowerCase().contains("lin"))));
    }

    private static byte[] canRecord(int canId) {
        ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buf.putDouble(1.0d);
        buf.put((byte) 1);
        buf.putShort((short) canId);
        buf.put((byte) 0);
        buf.put((byte) 1);
        buf.put((byte) 2);
        buf.put((byte) 0xAA);
        buf.put((byte) 0xBB);
        return buf.array();
    }

    private static byte[] linRecord() {
        ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buf.putDouble(2.0d);
        buf.put((byte) 0x2A);
        buf.put(new byte[7]);
        return buf.array();
    }

    private static byte[] errorRecord() {
        ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buf.putDouble(3.0d);
        buf.put((byte) 1);
        buf.put(new byte[7]);
        return buf.array();
    }
}
