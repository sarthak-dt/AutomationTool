package com.stellar.platform.desktop.support;

import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Small deterministic measurement files that current content detection accepts.
 * Workflow tests use these instead of empty or truncated stubs.
 */
public final class TestMeasurementFixtures {

    private static final String MINIMAL_ASC = """
            date Thu Jan 01 12:00:00.000 am 2026
            base hex timestamps absolute
            0.001000 1 100 Rx d 2 11 22
            """;

    private static final String MINIMAL_DAT = """
            time_s,Speed (m/s)
            0.0,1.5
            0.1,1.6
            0.2,1.7
            """;

    private static final String MINIMAL_LOG = "time_ms\tSpeed (km/h)\n0\t10\n10\t11\n20\t12\n";

    private TestMeasurementFixtures() {
    }

    public static Path validMinimalMf4(Path file) throws IOException {
        return DesktopFixtures.writeFinalizedMf4(file);
    }

    public static Path validMinimalMf4(Path file, int variant) throws IOException {
        byte[] payload = DesktopFixtures.enginePayload(2000.0 + variant, 40 + (variant % 20), 1);
        return DesktopFixtures.writeMf4(file, 1, 0, List.of(
                DesktopFixtures.busRecord(1_000_000_000L + variant, 0x100L + (variant & 0xFF), payload)));
    }

    /**
     * ID block the MDF probe recognizes, with no channel groups. Validation must treat this
     * as a corrupt supported file, not as an unknown type.
     */
    public static Path recognizedButCorruptMf4(Path file) throws IOException {
        try (SyntheticMdf4Builder builder = SyntheticMdf4Builder.create(file)) {
            builder.writeIdBlock("4.10", 410, 0);
        }
        return file;
    }

    public static Path validMinimalAsc(Path file) throws IOException {
        Files.writeString(file, MINIMAL_ASC, StandardCharsets.UTF_8);
        return file;
    }

    public static Path validMinimalBlf(Path file) throws IOException {
        int headerSize = 144;
        byte[] data = new byte[headerSize];
        data[0] = 'L';
        data[1] = 'O';
        data[2] = 'G';
        data[3] = 'G';
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(4, headerSize);
        buf.putLong(20, headerSize);
        Files.write(file, data);
        return file;
    }

    public static Path truncatedLoggPrefix(Path file) throws IOException {
        Files.write(file, new byte[] {'L', 'O', 'G', 'G'});
        return file;
    }

    public static Path validMinimalDat(Path file) throws IOException {
        Files.writeString(file, MINIMAL_DAT, StandardCharsets.UTF_8);
        return file;
    }

    public static Path validMinimalLog(Path file) throws IOException {
        Files.writeString(file, MINIMAL_LOG, StandardCharsets.UTF_8);
        return file;
    }

    /** Writes a recognized measurement when the name is a measurement extension; otherwise a one-byte stub. */
    public static Path writeStubMeasurement(Path path) throws IOException {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mf4") || name.endsWith(".mdf")) {
            return validMinimalMf4(path);
        }
        if (name.endsWith(".asc")) {
            return validMinimalAsc(path);
        }
        if (name.endsWith(".blf")) {
            return validMinimalBlf(path);
        }
        if (name.endsWith(".dat")) {
            return validMinimalDat(path);
        }
        if (name.endsWith(".log")) {
            return validMinimalLog(path);
        }
        Files.writeString(path, "x");
        return path;
    }
}
