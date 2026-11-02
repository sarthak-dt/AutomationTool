package com.stellar.platform.desktop.support;

import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class DesktopFixtures {

    private DesktopFixtures() {
    }

    public static Path copyResource(String classpath, Path target) throws IOException {
        try (InputStream in = DesktopFixtures.class.getClassLoader().getResourceAsStream(classpath)) {
            Objects.requireNonNull(in, "missing resource: " + classpath);
            Files.write(target, in.readAllBytes());
        }
        return target;
    }

    public static Path writeFinalizedMf4(Path file) throws IOException {
        return writeMf4(file, 1, 0, List.of(busRecord(1_000_000_000L, 0x100, enginePayload(2500.0, 50, 1))));
    }

    public static Path writeUnfinalizedMf4(Path file) throws IOException {
        return writeMf4(file, 0, 0, List.of(
                busRecord(1_000_000_000L, 0x100, enginePayload(2500.0, 50, 1)),
                busRecord(2_000_000_000L, 0x100, enginePayload(2600.0, 51, 1))
        ));
    }

    public static byte[] enginePayload(double rpm, int tempC, int flags) {
        int speedRaw = (int) Math.round(rpm / 0.25);
        int tempRaw = tempC + 40;
        byte[] p = new byte[8];
        p[0] = (byte) (speedRaw & 0xFF);
        p[1] = (byte) ((speedRaw >> 8) & 0xFF);
        p[2] = (byte) (tempRaw & 0xFF);
        p[3] = (byte) (flags & 0xFF);
        return p;
    }

    public static byte[] busRecord(long timestampNanos, long canId, byte[] payload) {
        byte[] data = new byte[23];
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.put(0, (byte) 0);
        buf.put(1, (byte) 0);
        buf.putInt(2, (int) canId);
        int dlc = Math.min(8, payload.length);
        buf.put(6, (byte) dlc);
        System.arraycopy(payload, 0, data, 7, dlc);
        buf.putLong(15, timestampNanos);
        return data;
    }

    public static Path writeMf4(Path file, long cycles, int unfin, List<byte[]> records) throws IOException {
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, unfin);
            long hd = b.writeHd(0, 0, 0);
            long head = writeCnChain(b);
            long cg = b.writeCg(0, head, 1, cycles, 0, 23, 0);
            long dt = b.writeDt(concat(records));
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
        return file;
    }

    private static long writeCnChain(SyntheticMdf4Builder b) throws IOException {
        long time = b.writeCn(0, b.writeTx("Timestamp"), 0, 2, 1, 0, 15, 0, 64, 0);
        long data = b.writeCn(time, b.writeTx("DataBytes"), 0, 0, 0, 9, 7, 0, 64, 0);
        long dlc = b.writeCn(data, b.writeTx("DLC"), 0, 0, 0, 0, 6, 0, 8, 0);
        long id = b.writeCn(dlc, b.writeTx("CAN_ID"), 0, 0, 0, 0, 2, 0, 11, 0);
        long dir = b.writeCn(id, b.writeTx("Direction"), 0, 0, 0, 0, 1, 0, 8, 0);
        return b.writeCn(dir, b.writeTx("BusChannel"), 0, 0, 0, 0, 0, 0, 8, 0);
    }

    private static byte[] concat(List<byte[]> records) {
        int total = records.stream().mapToInt(r -> r.length).sum();
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] r : records) {
            System.arraycopy(r, 0, out, pos, r.length);
            pos += r.length;
        }
        return out;
    }

    public static String readUtf8(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
