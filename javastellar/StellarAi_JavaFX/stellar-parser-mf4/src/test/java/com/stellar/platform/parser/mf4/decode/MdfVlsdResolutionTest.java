package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.parser.mf4.dynamicschema.MdfCanRole;
import com.stellar.platform.parser.mf4.dynamicschema.MdfCanRoleCandidate;
import com.stellar.platform.parser.mf4.dynamicschema.MdfByteOrder;
import com.stellar.platform.parser.mf4.dynamicschema.MdfChannelDataTypeKind;
import com.stellar.platform.parser.mf4.dynamicschema.MdfChannelFieldSchema;
import com.stellar.platform.parser.mf4.dynamicschema.MdfSignedness;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ReaderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MdfVlsdResolutionTest {

    @TempDir
    Path temp;

    @Test
    void cgLinkedVlsd_byteArrayOffset_resolvesPayloadNotOffsetBytes() throws Exception {
        Path file = temp.resolve("vlsd-cg-bytearray.mf4");
        byte[] payloadA = new byte[]{0x03, 0x41, 0x05, 0x3F, 0x00, 0x00, 0x00, 0x00};
        byte[] payloadB = new byte[]{0x04, 0x41, 0x0C, 0x11, 0x22, 0x00, 0x00, 0x00};
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("MDF     ", "4.10", 410, 0);
            // Reserve HD region so writeHd does not clobber the VLSD CG / CN graph.
            b.writeDt(new byte[128]);

            // VLSD CG (recordId=2): length-prefixed bodies; no fixed channels.
            long vlsdCg = b.writeCg(0, 0, 2, 2, 0x1, 0, 0);

            // DataBytes: cn_type=VLSD, cn_data_type=BYTE_ARRAY/MIME, stores LE offset (not payload).
            long payloadCn = b.writeCn(
                    0L, 0L, b.writeTx("Event.DataBytes"), 0, vlsdCg,
                    1, 0, 10, 12, 0, 64, 0, 0
            );
            long dlc = b.writeCn(payloadCn, 0L, b.writeTx("Event.DLC"), 0, 0, 0, 0, 0, 11, 0, 8, 0, 0);
            long id = b.writeCn(dlc, 0L, b.writeTx("Event.ID"), 0, 0, 0, 0, 0, 9, 0, 11, 0, 0);
            long bus = b.writeCn(id, 0L, b.writeTx("Event.BusChannel"), 0, 0, 0, 0, 0, 8, 0, 8, 0, 0);
            long composite = b.writeCn(0L, bus, b.writeTx("BusEvent"), 0, 0, 0, 10, 8, 0, 64, 0, 0x400);
            long time = b.writeCn(composite, 0L, b.writeTx("Timestamp"), 0, 2, 1, 4, 0, 0, 64, 0, 0);
            long si = b.writeSi(b.writeTx("CAN"), b.writeTx("CAN"), 1, 2);
            int dataBytes = 20;
            long canCg = b.writeCg(vlsdCg, time, 0, si, 1, 2, 0x6, dataBytes, 0);

            ByteBuffer dt = ByteBuffer.allocate(2 * (1 + dataBytes) + 2 * (1 + 4 + 8)).order(ByteOrder.LITTLE_ENDIAN);
            // CAN #0 → VLSD offset 0
            dt.put((byte) 1);
            dt.putDouble(1.0d);
            dt.put((byte) 1);
            dt.putShort((short) 0x321);
            dt.put((byte) 8);
            dt.putLong(0L);
            // VLSD entry @ logical 0
            dt.put((byte) 2);
            dt.putInt(payloadA.length);
            dt.put(payloadA);
            // CAN #1 → VLSD offset 4+8=12
            dt.put((byte) 1);
            dt.putDouble(2.0d);
            dt.put((byte) 1);
            dt.putShort((short) 0x322);
            dt.put((byte) 8);
            dt.putLong(4L + payloadA.length);
            // VLSD entry @ logical 12
            dt.put((byte) 2);
            dt.putInt(payloadB.length);
            dt.put(payloadB);

            long dtAddr = b.writeDt(dt.array());
            long dg = b.writeDg(0, canCg, dtAddr, 1);
            b.writeHd(dg, 0, 0);
        }

        var decoded = new MdfDynamicRecordDecoder().decodeAll(file, ReaderContext.ofBatchSize(4));
        assertEquals(2, decoded.frames().size(), String.valueOf(decoded.diagnostics()));

        CanonicalCanFrame first = decoded.frames().get(0);
        assertEquals(0x321L, first.canId());
        assertEquals(8, first.data().length);
        assertEquals(0x03, first.data()[0] & 0xFF);
        assertEquals(0x41, first.data()[1] & 0xFF);
        assertEquals(0x05, first.data()[2] & 0xFF);
        // Must not leave the offset bytes (00 00 00 00 ...) as the CAN payload.
        assertFalse(first.data()[0] == 0 && first.data()[1] == 0);

        CanonicalCanFrame second = decoded.frames().get(1);
        assertEquals(0x322L, second.canId());
        assertEquals(0x04, second.data()[0] & 0xFF);
        assertEquals(0x41, second.data()[1] & 0xFF);
        assertEquals(0x0C, second.data()[2] & 0xFF);
    }

    @Test
    void validVlsdPayload_classicAndFd_resolveToCanonicalFrames() throws Exception {
        Path classic = buildVlsdCan(temp.resolve("vlsd-classic.mf4"), new byte[]{0x10, 0x20}, false);
        var classicFrames = new MdfDynamicRecordDecoder().decodeAll(classic, ReaderContext.ofBatchSize(4));
        assertEquals(1, classicFrames.frames().size(), String.valueOf(classicFrames.diagnostics()));
        CanonicalCanFrame f = classicFrames.frames().getFirst();
        assertEquals(0x321L, f.canId());
        assertEquals(2, f.data().length);
        assertEquals(0x10, f.data()[0] & 0xFF);

        byte[] fdPayload = new byte[64];
        fdPayload[0] = 0x55;
        fdPayload[63] = (byte) 0xAA;
        Path fd = buildVlsdCan(temp.resolve("vlsd-fd.mf4"), fdPayload, true);
        var fdFrames = new MdfDynamicRecordDecoder().decodeAll(fd, ReaderContext.ofBatchSize(4));
        assertEquals(1, fdFrames.frames().size(), String.valueOf(fdFrames.diagnostics()));
        assertEquals(64, fdFrames.frames().getFirst().data().length);
        assertEquals(0x55, fdFrames.frames().getFirst().data()[0] & 0xFF);
        assertEquals((byte) 0xAA, fdFrames.frames().getFirst().data()[63]);
    }

    @Test
    void variablePayloadSizes_resolveIndependently() throws Exception {
        assertEquals(1, new MdfDynamicRecordDecoder()
                .decodeAll(buildVlsdCan(temp.resolve("v1.mf4"), new byte[]{1}, false), ReaderContext.ofBatchSize(2))
                .frames().size());
        assertEquals(1, new MdfDynamicRecordDecoder()
                .decodeAll(buildVlsdCan(temp.resolve("v8.mf4"), new byte[8], false), ReaderContext.ofBatchSize(2))
                .frames().size());
    }

    @Test
    void invalidVlsdReference_andTruncation_andBoundary_areDiagnostic() throws Exception {
        Path file = temp.resolve("vlsd-bad.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("MDF     ", "4.10", 410, 0);
            b.writeDt(new byte[64]);
            byte[] sd = new byte[]{2, 0, 0, 0, 0x11}; // length=2 but only 1 payload byte
            long sdAddr = b.writeSd(sd);
            long payloadCn = b.writeCn(0L, 0L, b.writeTx("DataBytes"), 0, sdAddr, 1, 0, 0, 8, 0, 32, 0, 0);
            // Use resolver directly with fabricated field
            try (MdfFileAccess access = MdfFileAccess.open(file)) {
                MdfChannelFieldSchema field = field(payloadCn, sdAddr, MdfCanRole.PAYLOAD);
                var truncated = MdfVlsdResolver.resolve(access, field, 0, 1L, 2L, 0L);
                assertFalse(truncated.diagnostics().isEmpty());
                assertTrue(truncated.diagnostics().stream().anyMatch(d ->
                        d.code() == MdfDecodeDiagnosticCode.VLSD_TRUNCATED
                                || d.code() == MdfDecodeDiagnosticCode.VLSD_BOUNDARY_VIOLATION));

                var badOffset = MdfVlsdResolver.resolve(access, field, 1000, 1L, 2L, 0L);
                assertTrue(badOffset.diagnostics().stream().anyMatch(d ->
                        d.code() == MdfDecodeDiagnosticCode.VLSD_BOUNDARY_VIOLATION
                                || d.code() == MdfDecodeDiagnosticCode.VLSD_INVALID_REFERENCE));

                MdfChannelFieldSchema noLink = field(payloadCn, 0L, MdfCanRole.PAYLOAD);
                var missing = MdfVlsdResolver.resolve(access, noLink, 0, 1L, 2L, 0L);
                assertTrue(missing.diagnostics().stream().anyMatch(d ->
                        d.code() == MdfDecodeDiagnosticCode.VLSD_INVALID_REFERENCE));
            }
        }
    }

    private Path buildVlsdCan(Path file, byte[] payload, boolean fd) throws Exception {
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("MDF     ", "4.10", 410, 0);
            b.writeDt(new byte[128]);
            ByteBuffer sdBuf = ByteBuffer.allocate(4 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
            sdBuf.putInt(payload.length);
            sdBuf.put(payload);
            long sd = b.writeSd(sdBuf.array());

            long payloadCn = b.writeCn(0L, 0L, b.writeTx("Event.DataBytes"), 0, sd, 1, 0, 0, 12, 0, 32, 0, 0);
            long dlc = b.writeCn(payloadCn, 0L, b.writeTx("Event.DLC"), 0, 0, 0, 0, 0, 11, 0, 8, 0, 0);
            long id = b.writeCn(dlc, 0L, b.writeTx("Event.ID"), 0, 0, 0, 0, 0, 9, 0, 11, 0, 0);
            long bus = b.writeCn(id, 0L, b.writeTx("Event.BusChannel"), 0, 0, 0, 0, 0, 8, 0, 8, 0, 0);
            long composite = b.writeCn(0L, bus, b.writeTx("BusEvent"), 0, 0, 0, 10, 8, 0, 64, 0, 0x400);
            long time = b.writeCn(composite, 0L, b.writeTx("Timestamp"), 0, 2, 1, 4, 0, 0, 64, 0, 0);
            long si = b.writeSi(b.writeTx("CAN"), b.writeTx("CAN"), 1, 2);
            int dataBytes = 16;
            long cg = b.writeCg(0, time, 0, si, 1, 1, 0x6, dataBytes, 0);

            ByteBuffer rec = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN);
            rec.putDouble(2.0d); // @0
            rec.put((byte) 1); // bus @8
            rec.putShort((short) 0x321); // id @9
            rec.put((byte) (fd ? 64 : Math.min(8, payload.length))); // dlc @11
            rec.putInt(0); // VLSD offset @12
            long dt = b.writeDt(rec.array());
            long dg = b.writeDg(0, cg, dt, 0);
            b.writeHd(dg, 0, 0);
        }
        return file;
    }

    private static MdfChannelFieldSchema field(long address, long dataLink, MdfCanRole role) {
        return new MdfChannelFieldSchema(
                address,
                "DataBytes",
                1,
                0,
                0,
                MdfChannelDataTypeKind.UNSIGNED_INTEGER_LE,
                true,
                0,
                0,
                32,
                MdfByteOrder.LITTLE_ENDIAN,
                MdfSignedness.UNSIGNED,
                0,
                0,
                0,
                0,
                dataLink,
                "",
                new MdfCanRoleCandidate(role, 0.9, List.of("test")),
                0,
                false,
                false
        );
    }
}
