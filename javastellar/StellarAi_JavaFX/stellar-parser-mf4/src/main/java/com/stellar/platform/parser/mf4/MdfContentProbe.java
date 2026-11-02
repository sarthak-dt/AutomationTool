package com.stellar.platform.parser.mf4;

import com.stellar.platform.parser.mf4.id.MdfIdentificationReader;
import com.stellar.platform.parser.mf4.id.MdfVersion;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.spi.ContentFormatMatch;
import com.stellar.platform.parser.spi.ParserException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the MDF identification block and, for MDF 3 only, a bounded channel-metadata walk.
 * Does not decode samples and does not treat an MDF 3 file as MDF 4.
 */
public final class MdfContentProbe {

    private static final int ID_BYTES = 64;
    private static final int MAX_BLOCKS = 20_000;

    private MdfContentProbe() {
    }

    public static Optional<ContentFormatMatch> probe(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try (MdfFileAccess access = MdfFileAccess.open(path)) {
            if (!MdfIdentificationReader.looksLikeMdf(access)) {
                return Optional.empty();
            }
            if (access.fileSize() < ID_BYTES) {
                return Optional.of(recognized("", "", false, null, null, null));
            }
            byte[] id = new byte[ID_BYTES];
            ByteBuffer buf = access.readOrdered(0, ID_BYTES, "IDBLOCK probe");
            buf.get(id);
            String versionText = ascii(id, 8, 8);
            String program = ascii(id, 16, 8);
            int versionCode = Short.toUnsignedInt(ByteBuffer.wrap(id, 28, 2).order(ByteOrder.LITTLE_ENDIAN).getShort());
            MdfVersion version = MdfVersion.parse(versionText, versionCode).orElse(null);
            boolean mdf4 = version != null
                    && version.isMdf4()
                    && versionText.startsWith("4.")
                    && (versionCode == 0 || versionCode / 100 == 4);
            boolean familySupported = mdf4 || (version != null && version.major() == 3);
            Boolean rawCan = null;
            Boolean decoded = null;
            Boolean requiresDefinition = null;
            if (!mdf4 && version != null && version.major() == 3) {
                Capability capability = mdf3Capability(path);
                rawCan = capability.rawCan;
                decoded = capability.decoded;
                requiresDefinition = capability.requiresDefinition;
            }
            return Optional.of(recognized(versionText, program, familySupported, rawCan, decoded, requiresDefinition));
        } catch (ParserException e) {
            return Optional.empty();
        }
    }

    /**
     * True when an MDF family reader can decode this path.
     * MDF 3.x and MDF 4.x are supported. Other recognized majors are not.
     * A missing file may still match an {@code .mf4}/{@code .mdf} hint.
     */
    public static boolean parserCanRead(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        if (!Files.isRegularFile(path)) {
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            return name.endsWith(".mf4") || name.endsWith(".mdf");
        }
        return probe(path).map(ContentFormatMatch::parserSupported).orElse(false);
    }

    private static ContentFormatMatch recognized(
            String version,
            String program,
            boolean parserSupported,
            Boolean rawCan,
            Boolean decoded,
            Boolean requiresDefinition
    ) {
        return new ContentFormatMatch(
                Mf4MeasurementFileParser.FORMAT_KEY,
                "MDF",
                version,
                program,
                parserSupported,
                rawCan,
                decoded,
                requiresDefinition);
    }

    /**
     * MDF 3.x channel blocks are a different layout from MDF 4.
     * A failed walk leaves capability flags unknown.
     */
    private static Capability mdf3Capability(Path path) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < ID_BYTES + 4) {
                return Capability.unknown();
            }
            ByteBuffer order = read(channel, 24, 2);
            int byteOrder = Short.toUnsignedInt(order.getShort());
            if (byteOrder != 0) {
                return Capability.unknown();
            }
            ByteBuffer hdLen = read(channel, 64, 4);
            if (!"HD".equals(asciiBytes(hdLen, 2))) {
                return Capability.unknown();
            }
            int dg = read(channel, 68, 4).getInt();
            int dataChannels = 0;
            int withConversion = 0;
            Set<Integer> seen = new HashSet<>();
            int blocks = 0;
            while (dg > 0 && blocks < MAX_BLOCKS) {
                if (!seen.add(dg) || !inFile(dg, 24, size)) {
                    return Capability.unknown();
                }
                blocks++;
                ByteBuffer dgBlock = read(channel, dg, 24);
                if (!"DG".equals(asciiBytes(dgBlock, 2))) {
                    return Capability.unknown();
                }
                int nextDg = dgBlock.getInt(4);
                int cg = dgBlock.getInt(8);
                while (cg > 0 && blocks < MAX_BLOCKS) {
                    if (!seen.add(cg) || !inFile(cg, 12, size)) {
                        return Capability.unknown();
                    }
                    blocks++;
                    ByteBuffer cgBlock = read(channel, cg, 12);
                    if (!"CG".equals(asciiBytes(cgBlock, 2))) {
                        return Capability.unknown();
                    }
                    int nextCg = cgBlock.getInt(4);
                    int cn = cgBlock.getInt(8);
                    while (cn > 0 && blocks < MAX_BLOCKS) {
                        if (!seen.add(cn) || !inFile(cn, 26, size)) {
                            return Capability.unknown();
                        }
                        blocks++;
                        ByteBuffer cnBlock = read(channel, cn, 26);
                        if (!"CN".equals(asciiBytes(cnBlock, 2))) {
                            return Capability.unknown();
                        }
                        int nextCn = cnBlock.getInt(4);
                        int cc = cnBlock.getInt(8);
                        int channelType = Short.toUnsignedInt(cnBlock.getShort(24));
                        if (channelType != 1) {
                            dataChannels++;
                            if (cc > 0 && inFile(cc, 4, size) && "CC".equals(asciiAt(channel, cc))) {
                                withConversion++;
                            }
                        }
                        cn = nextCn;
                    }
                    cg = nextCg;
                }
                dg = nextDg;
            }
            if (dataChannels == 0) {
                return Capability.unknown();
            }
            boolean decoded = withConversion > 0;
            return new Capability(Boolean.FALSE, decoded, decoded ? Boolean.FALSE : null);
        } catch (IOException e) {
            return Capability.unknown();
        }
    }

    private static boolean inFile(int offset, int length, long size) {
        return offset > 0 && length > 0 && (long) offset + length <= size;
    }

    private static ByteBuffer read(FileChannel channel, int offset, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        int read = 0;
        while (read < length) {
            int n = channel.read(buf, (long) offset + read);
            if (n < 0) {
                throw new IOException("truncated MDF block");
            }
            read += n;
        }
        buf.flip();
        return buf;
    }

    private static String asciiAt(FileChannel channel, int offset) throws IOException {
        return asciiBytes(read(channel, offset, 2), 2);
    }

    private static String asciiBytes(ByteBuffer buf, int length) {
        byte[] raw = new byte[length];
        buf.get(0, raw);
        return new String(raw, StandardCharsets.US_ASCII);
    }

    private static String ascii(byte[] raw, int offset, int length) {
        String text = new String(raw, offset, length, StandardCharsets.US_ASCII);
        int end = 0;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (c == 0 || c < 0x20) {
                break;
            }
            end++;
        }
        return text.substring(0, end).trim();
    }

    private record Capability(Boolean rawCan, Boolean decoded, Boolean requiresDefinition) {
        static Capability unknown() {
            return new Capability(null, null, null);
        }
    }

}
