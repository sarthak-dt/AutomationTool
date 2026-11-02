package com.stellar.platform.parser.blf;

import com.stellar.platform.parser.spi.ContentFormatMatch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Recognizes Vector BLF from the {@code LOGG} file signature and a plausible header size.
 */
public final class BlfContentProbe {

    private BlfContentProbe() {
    }

    public static Optional<ContentFormatMatch> probe(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            if (Files.size(path) < 8) {
                return Optional.empty();
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        byte[] prefix = new byte[8];
        try (InputStream in = Files.newInputStream(path)) {
            int read = 0;
            while (read < prefix.length) {
                int n = in.read(prefix, read, prefix.length - read);
                if (n < 0) {
                    return Optional.empty();
                }
                read += n;
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        if (prefix[0] != 'L' || prefix[1] != 'O' || prefix[2] != 'G' || prefix[3] != 'G') {
            return Optional.empty();
        }
        int headerSize = ByteBuffer.wrap(prefix, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (headerSize < BlfConstants.MIN_FILE_HEADER_SIZE || headerSize > BlfConstants.MAX_FILE_HEADER_SIZE) {
            return Optional.empty();
        }
        return Optional.of(new ContentFormatMatch(
                BlfMeasurementFileParser.FORMAT_KEY,
                "BLF",
                "",
                "",
                true,
                Boolean.TRUE,
                Boolean.FALSE,
                Boolean.TRUE));
    }

    public static boolean parserCanRead(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        if (!Files.isRegularFile(path)) {
            return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".blf");
        }
        return probe(path).isPresent();
    }
}
