package com.stellar.platform.testkit.inventory;

import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.parser.mf4.Mf4FileReader;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.inventory.SourceInventoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mf4SourceInventoryIntegrationTest {

    @TempDir
    Path temp;

    @Test
    void oneCanChannelMultipleIds() throws Exception {
        Path file = temp.resolve("one-ch.mf4");
        writeClassicMultiId(file, 1, List.of(0x100, 0x200, 0x100));

        CanSourceInventory inv = inventory(file);
        assertEquals(CanInventoryStatus.INVENTORY_READY, inv.status());
        assertEquals(1, inv.totalCanChannels());
        assertEquals(3, inv.totalCanFrames());
        assertEquals(2, inv.totalUniqueMessageIds());
        assertEquals(1, inv.channels().getFirst().channelId());
    }

    @Test
    void multipleCanChannels() throws Exception {
        Path file = temp.resolve("multi-ch.mf4");
        writeClassicMultiChannel(file);

        CanSourceInventory inv = inventory(file);
        assertEquals(CanInventoryStatus.INVENTORY_READY, inv.status());
        assertEquals(2, inv.totalCanChannels());
        assertTrue(inv.channels().stream().anyMatch(c -> c.channelId() == 1));
        assertTrue(inv.channels().stream().anyMatch(c -> c.channelId() == 2));
        assertEquals(2, inv.totalUniqueMessageIds());
    }

    @Test
    void nativeMeasurementDoesNotBecomeCanChannel() throws Exception {
        Path file = temp.resolve("native.mf4");
        writeNativeOnly(file);

        CanSourceInventory inv = inventory(file);
        assertEquals(CanInventoryStatus.INVENTORY_READY, inv.status());
        assertEquals(0, inv.totalCanChannels());
        assertTrue(inv.diagnostics().stream().anyMatch(d -> d.code().name().equals("NO_CAN_DATA")));
    }

    private static CanSourceInventory inventory(Path file) {
        SourceInventoryService service = new SourceInventoryService(new ParserRegistry(List.of()), 1);
        return service.inventory(new Mf4FileReader(), file, ReaderContext.ofBatchSize(4), inv -> { });
    }

    private static void writeClassicMultiId(Path file, int busChannel, List<Integer> canIds) throws Exception {
        List<byte[]> records = new ArrayList<>();
        long ts = 1_000L;
        for (int id : canIds) {
            records.add(classicRecord(busChannel, id, ts, (byte) 1));
            ts += 1_000L;
        }
        writeClassic(file, records);
    }

    private static void writeClassicMultiChannel(Path file) throws Exception {
        writeClassic(file, List.of(
                classicRecord(1, 0x111, 1_000L, (byte) 1),
                classicRecord(2, 0x222, 2_000L, (byte) 2)
        ));
    }

    private static void writeClassic(Path file, List<byte[]> records) throws Exception {
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long time = b.writeCn(0, b.writeTx("Timestamp"), 0, 2, 1, 0, 15, 0, 64, 0);
            long data = b.writeCn(time, b.writeTx("DataBytes"), 0, 0, 0, 9, 7, 0, 64, 0);
            long dlc = b.writeCn(data, b.writeTx("DLC"), 0, 0, 0, 0, 6, 0, 8, 0);
            long id = b.writeCn(dlc, b.writeTx("CAN_ID"), 0, 0, 0, 0, 2, 0, 11, 0);
            long dir = b.writeCn(id, b.writeTx("Direction"), 0, 0, 0, 0, 1, 0, 8, 0);
            long head = b.writeCn(dir, b.writeTx("BusChannel"), 0, 0, 0, 0, 0, 0, 8, 0);
            long cg = b.writeCg(0, head, 1, records.size(), 0, 23, 0);
            byte[] dt = concat(records);
            long dtAddr = b.writeDt(dt);
            long dg = b.writeDg(0, cg, dtAddr, 0);
            b.patchLink(hd, 0, dg);
        }
    }

    private static void writeNativeOnly(Path file) throws Exception {
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long time = b.writeCn(0, b.writeTx("t"), 0, 2, 1, 0, 0, 0, 64, 0);
            long speed = b.writeCn(time, b.writeTx("VehicleSpeed"), 0, 0, 0, 0, 8, 0, 16, 0);
            long cg = b.writeCg(0, speed, 1, 1, 0, 10, 0);
            ByteBuffer rec = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
            rec.putShort((short) 120);
            rec.putLong(1_000L);
            long dt = b.writeDt(rec.array());
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
    }

    private static byte[] classicRecord(int busChannel, int canId, long timestampNanos, byte firstPayload) {
        ByteBuffer buf = ByteBuffer.allocate(23).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) busChannel);
        buf.put((byte) 0);
        buf.putInt(canId);
        buf.put((byte) 8);
        buf.put(firstPayload);
        for (int i = 1; i < 8; i++) {
            buf.put((byte) 0);
        }
        buf.putLong(timestampNanos);
        return buf.array();
    }

    private static byte[] concat(List<byte[]> parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
