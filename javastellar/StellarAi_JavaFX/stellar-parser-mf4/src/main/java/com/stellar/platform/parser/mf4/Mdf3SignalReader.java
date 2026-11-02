package com.stellar.platform.parser.mf4;

import com.stellar.platform.domain.CanonicalMeasurementSample;
import com.stellar.platform.domain.ProcessingWarning;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.parser.mf4.dynamicschema.Mdf3Conversion;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.SignalBatchConsumer;
import com.stellar.platform.parser.spi.WarningConsumer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Streams MDF 3.x channel groups into {@link CanonicalMeasurementSample}.
 * The version string is read from the identification block. Behaviour follows the
 * major version family, not one hardcoded minor version.
 * <p>
 * Numeric samples are byte-aligned integers and IEEE floats. MDF 3 CC blocks are
 * applied with MDF 3 type numbers. A conversion that cannot be applied is emitted
 * as {@link SignalValidity#CONVERSION_FAILED} and is not presented as a physical
 * value. The time channel is converted to nanoseconds only when its unit is an
 * explicit second, millisecond, microsecond, or nanosecond unit.
 */
public final class Mdf3SignalReader {

    private static final int ID_BYTES = 64;
    private static final int MAX_BLOCKS = 20_000;
    private static final int MAX_RECORD_BYTES = 1_048_576;

    public void read(
            Path path,
            ReaderContext context,
            SignalBatchConsumer consumer,
            WarningConsumer warnings
    ) throws ParserException {
        if (path == null) {
            throw new ParserException("MDF path must not be null");
        }
        ReaderContext ctx = context == null ? ReaderContext.ofBatchSize(256) : context;
        SignalBatchConsumer sink = consumer == null ? SignalBatchConsumer.NOOP : consumer;
        WarningConsumer warningSink = warnings == null ? warning -> { } : warnings;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < ID_BYTES) {
                throw new ParserException("Detected MDF but the file is corrupt.");
            }
            ByteBuffer id = read(channel, 0, ID_BYTES);
            String signature = ascii(id, 0, 8);
            if (!signature.startsWith("MDF") && !signature.startsWith("UnFinMF")) {
                throw new ParserException("Detected MDF but the file is corrupt.");
            }
            String version = ascii(id, 8, 8);
            int major = major(version);
            if (major != 3) {
                throw new ParserException("Detected MDF " + version + ", but this version is not currently supported.");
            }
            int byteOrder = Short.toUnsignedInt(id.getShort(24));
            if (byteOrder != 0) {
                throw new ParserException("Detected MDF " + version + " but the file uses an unsupported byte order.");
            }
            List<Group> groups = readGroups(channel, size, version);
            if (groups.isEmpty()) {
                throw new ParserException("Detected MDF " + version + " but the file is corrupt.");
            }
            List<CanonicalMeasurementSample> batch = new ArrayList<>();
            long emitted = 0;
            boolean timeRejected = false;
            Set<String> conversionWarnings = new HashSet<>();
            for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
                cancelIfRequested(ctx);
                Group group = groups.get(groupIndex);
                if (group.time == null || group.time.nanosPerUnit == null
                        || !group.time.conversion.structurallyApplied()) {
                    timeRejected = true;
                    warningSink.onWarning(new ProcessingWarning(
                            "TIMESTAMP_UNDETERMINED",
                            "Detected MDF " + version + ", but timestamp semantics could not be determined.",
                            new SourceReference(-1, group.dataOffset)
                    ));
                    continue;
                }
                if (group.signals.isEmpty() || group.cycles == 0 || group.recordSize <= 0) {
                    continue;
                }
                long stride = (long) group.recordIdSize + group.recordSize;
                if (group.dataOffset <= 0
                        || stride <= 0
                        || group.recordSize > MAX_RECORD_BYTES
                        || group.dataOffset + stride * group.cycles > size) {
                    throw new ParserException("Detected MDF " + version + " but the file is corrupt.");
                }
                byte[] record = new byte[group.recordSize];
                for (long cycle = 0; cycle < group.cycles; cycle++) {
                    if ((cycle & 255) == 0) {
                        cancelIfRequested(ctx);
                    }
                    long offset = group.dataOffset + (long) group.recordIdSize + cycle * stride;
                    readInto(channel, offset, record);
                    Double timeRaw = decode(record, group.time);
                    if (timeRaw == null) {
                        continue;
                    }
                    Mdf3Conversion.Result timeValue = group.time.conversion.apply(timeRaw);
                    if (!timeValue.numericApplied()) {
                        continue;
                    }
                    long nanos = Math.round(timeValue.physical() * group.time.nanosPerUnit);
                    for (Channel signal : group.signals) {
                        Double raw = decode(record, signal);
                        if (raw == null) {
                            continue;
                        }
                        Mdf3Conversion.Result value = signal.conversion.apply(raw);
                        if (value.validity() == SignalValidity.CONVERSION_FAILED
                                && conversionWarnings.add(signal.name)) {
                            warningSink.onWarning(new ProcessingWarning(
                                    "CONVERSION_FAILED",
                                    "MDF3 conversion was not applied for " + signal.name + ": " + value.detail(),
                                    new SourceReference(-1, offset)
                            ));
                        }
                        batch.add(new CanonicalMeasurementSample(
                                signal.name,
                                nanos,
                                groupIndex,
                                value.physical(),
                                rawBits(record, signal),
                                signal.unit,
                                value.text(),
                                new SourceReference(-1, offset),
                                value.validity()
                        ));
                        if (batch.size() >= ctx.batchSize()) {
                            emit(sink, batch);
                            emitted += batch.size();
                            batch.clear();
                        }
                    }
                }
            }
            if (!batch.isEmpty()) {
                int count = batch.size();
                emit(sink, batch);
                emitted += count;
            }
            if (emitted == 0 && timeRejected) {
                throw new ParserException("Detected MDF " + version + ", but timestamp semantics could not be determined.");
            }
            if (emitted == 0) {
                throw new ParserException("Detected MDF " + version + " but the file is corrupt.");
            }
        } catch (ParserException e) {
            throw e;
        } catch (IOException e) {
            throw new ParserException("Detected MDF but the file is corrupt.", e);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof ParserException parserException) {
                throw parserException;
            }
            throw new ParserException("Detected MDF but the file is corrupt.", e);
        }
    }

    private static void cancelIfRequested(ReaderContext context) throws ParserException {
        if (context.cancellationToken().isCancellationRequested()) {
            throw new ParserException("Processing cancelled.");
        }
    }

    private static void emit(SignalBatchConsumer sink, List<CanonicalMeasurementSample> batch) throws ParserException {
        try {
            sink.onBatch(List.copyOf(batch));
        } catch (ParserException e) {
            throw e;
        } catch (Exception e) {
            throw new ParserException(e.getMessage() == null ? "Signal consumer failed." : e.getMessage(), e);
        }
    }

    private static List<Group> readGroups(FileChannel channel, long size, String version) throws IOException, ParserException {
        if (!inFile(64, 8, size) || !"HD".equals(asciiAt(channel, 64))) {
            throw new ParserException("Detected MDF " + version + " but the file is corrupt.");
        }
        long dg = Integer.toUnsignedLong(read(channel, 68, 4).getInt());
        List<Group> groups = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int blocks = 0;
        while (dg > 0 && blocks < MAX_BLOCKS) {
            if (!seen.add(dg) || !inFile(dg, 24, size)) {
                throw new ParserException("Detected MDF " + version + " but the file is corrupt.");
            }
            blocks++;
            ByteBuffer dgBlock = read(channel, dg, 24);
            if (!"DG".equals(ascii(dgBlock, 0, 2))) {
                throw new ParserException("Detected MDF " + version + " but the file is corrupt.");
            }
            long nextDg = Integer.toUnsignedLong(dgBlock.getInt(4));
            long cg = Integer.toUnsignedLong(dgBlock.getInt(8));
            long data = Integer.toUnsignedLong(dgBlock.getInt(16));
            int recordIdSize = Short.toUnsignedInt(dgBlock.getShort(22));
            while (cg > 0 && blocks < MAX_BLOCKS) {
                if (!seen.add(cg) || !inFile(cg, 26, size)) {
                    throw new ParserException("Detected MDF " + version + " but the file is corrupt.");
                }
                blocks++;
                ByteBuffer cgBlock = read(channel, cg, 26);
                if (!"CG".equals(ascii(cgBlock, 0, 2))) {
                    throw new ParserException("Detected MDF " + version + " but the file is corrupt.");
                }
                long nextCg = Integer.toUnsignedLong(cgBlock.getInt(4));
                long cn = Integer.toUnsignedLong(cgBlock.getInt(8));
                int recordSize = Short.toUnsignedInt(cgBlock.getShort(20));
                long cycles = Integer.toUnsignedLong(cgBlock.getInt(22));
                Group group = new Group(data, recordSize, recordIdSize, cycles);
                while (cn > 0 && blocks < MAX_BLOCKS) {
                    if (!seen.add(cn) || !inFile(cn, 26, size)) {
                        throw new ParserException("Detected MDF " + version + " but the file is corrupt.");
                    }
                    blocks++;
                    int length = Short.toUnsignedInt(read(channel, cn + 2, 2).getShort());
                    int readLength = Math.min(Math.max(length, 26), 256);
                    if (!inFile(cn, readLength, size)) {
                        throw new ParserException("Detected MDF " + version + " but the file is corrupt.");
                    }
                    ByteBuffer cnBlock = read(channel, cn, readLength);
                    if (!"CN".equals(ascii(cnBlock, 0, 2))) {
                        throw new ParserException("Detected MDF " + version + " but the file is corrupt.");
                    }
                    long nextCn = Integer.toUnsignedLong(cnBlock.getInt(4));
                    long cc = Integer.toUnsignedLong(cnBlock.getInt(8));
                    int channelType = Short.toUnsignedInt(cnBlock.getShort(24));
                    if (readLength >= 192) {
                        Channel parsed = parseChannel(channel, size, cnBlock, channelType, cc);
                        if (parsed != null) {
                            if (parsed.time) {
                                if (group.time == null) {
                                    group.time = parsed;
                                }
                            } else {
                                group.signals.add(parsed);
                            }
                        }
                    }
                    cn = nextCn;
                }
                groups.add(group);
                cg = nextCg;
            }
            dg = nextDg;
        }
        return groups;
    }

    private static Channel parseChannel(
            FileChannel channel,
            long size,
            ByteBuffer cnBlock,
            int channelType,
            long cc
    ) throws IOException {
        String name = ascii(cnBlock, 26, 32);
        if (name.isBlank()) {
            return null;
        }
        int bitOffset = Short.toUnsignedInt(cnBlock.getShort(186));
        int bitCount = Short.toUnsignedInt(cnBlock.getShort(188));
        int dataType = Short.toUnsignedInt(cnBlock.getShort(190));
        int byteOffset = cnBlock.limit() >= 228 ? Short.toUnsignedInt(cnBlock.getShort(226)) : 0;
        if (bitOffset % 8 != 0 || bitCount % 8 != 0 || bitCount < 8 || bitCount > 64 || !numericType(dataType)) {
            return null;
        }
        String unit = "";
        Mdf3Conversion conversion = Mdf3Conversion.identity();
        if (cc > 0) {
            if (!inFile(cc, 4, size) || !"CC".equals(asciiAt(channel, cc))) {
                conversion = Mdf3Conversion.failed("truncated MDF3 CC block");
            } else {
                int blockSize = Short.toUnsignedInt(read(channel, cc + 2, 2).getShort());
                int readLength = Math.min(Math.max(blockSize, 46), 4096);
                if (!inFile(cc, Math.min(readLength, 46), size)) {
                    conversion = Mdf3Conversion.failed("truncated MDF3 CC block");
                } else {
                    if (!inFile(cc, readLength, size)) {
                        readLength = (int) Math.min(readLength, size - cc);
                    }
                    ByteBuffer ccBlock = read(channel, cc, readLength);
                    unit = normalizeUnit(ascii(ccBlock, 22, 20));
                    conversion = Mdf3Conversion.parse(ccBlock);
                }
            }
        }
        boolean time = channelType == 1;
        Double scale = time ? nanosPerUnit(unit) : null;
        return new Channel(name, bitOffset, byteOffset, bitCount, dataType, unit, time, scale, conversion);
    }

    private static boolean numericType(int dataType) {
        return switch (dataType) {
            case 0, 1, 2, 3, 9, 10, 11, 12, 13, 14, 15, 16 -> true;
            default -> false;
        };
    }

    private static Double decode(byte[] record, Channel channel) {
        int start = channel.byteOffset + channel.bitOffset / 8;
        int byteCount = channel.bitCount / 8;
        if (start < 0 || byteCount <= 0 || start + byteCount > record.length) {
            return null;
        }
        ByteOrder order = bigEndian(channel.dataType) ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        ByteBuffer buf = ByteBuffer.wrap(record, start, byteCount).order(order);
        return switch (channel.dataType) {
            case 0, 9, 13 -> unsigned(buf, byteCount);
            case 1, 10, 14 -> signed(buf, byteCount);
            case 2, 11, 15 -> byteCount == 4 ? (double) buf.getFloat() : null;
            case 3, 12, 16 -> byteCount == 8 ? buf.getDouble() : null;
            default -> null;
        };
    }

    private static boolean bigEndian(int dataType) {
        return dataType >= 9 && dataType <= 12;
    }

    private static Long rawBits(byte[] record, Channel channel) {
        int byteOffset = channel.byteOffset + channel.bitOffset / 8;
        int byteCount = channel.bitCount / 8;
        if (!integerType(channel.dataType) || byteCount > 8 || byteOffset + byteCount > record.length) {
            return null;
        }
        long value = 0;
        boolean big = bigEndian(channel.dataType);
        for (int i = 0; i < byteCount; i++) {
            int source = big ? byteCount - 1 - i : i;
            value |= (record[byteOffset + source] & 0xFFL) << (8 * i);
        }
        boolean signed = switch (channel.dataType) {
            case 1, 10, 14 -> true;
            default -> false;
        };
        if (signed && byteCount < 8) {
            long sign = 1L << (byteCount * 8 - 1);
            if ((value & sign) != 0) {
                value |= -1L << (byteCount * 8);
            }
        }
        return value;
    }

    private static boolean integerType(int dataType) {
        return switch (dataType) {
            case 0, 1, 9, 10, 13, 14 -> true;
            default -> false;
        };
    }

    private static Double unsigned(ByteBuffer buf, int byteCount) {
        return switch (byteCount) {
            case 1 -> (double) Byte.toUnsignedInt(buf.get());
            case 2 -> (double) Short.toUnsignedInt(buf.getShort());
            case 4 -> (double) Integer.toUnsignedLong(buf.getInt());
            case 8 -> unsignedLong(buf.getLong());
            default -> null;
        };
    }

    private static Double signed(ByteBuffer buf, int byteCount) {
        return switch (byteCount) {
            case 1 -> (double) buf.get();
            case 2 -> (double) buf.getShort();
            case 4 -> (double) buf.getInt();
            case 8 -> (double) buf.getLong();
            default -> null;
        };
    }

    private static double unsignedLong(long bits) {
        if (bits >= 0) {
            return bits;
        }
        return (bits >>> 1) * 2.0 + (bits & 1);
    }

    static Double nanosPerUnit(String unit) {
        if (unit == null) {
            return null;
        }
        String normalized = unit.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "s", "sec", "secs", "second", "seconds" -> 1_000_000_000d;
            case "ms", "msec", "millisecond", "milliseconds" -> 1_000_000d;
            case "us", "µs", "μs", "usec", "microsecond", "microseconds" -> 1_000d;
            case "ns", "nsec", "nanosecond", "nanoseconds" -> 1d;
            default -> null;
        };
    }

    private static String normalizeUnit(String unit) {
        if (unit == null) {
            return "";
        }
        String trimmed = unit.trim();
        if (trimmed.isEmpty() || "-".equals(trimmed)) {
            return "";
        }
        return trimmed;
    }

    private static int major(String version) {
        if (version == null || version.isBlank()) {
            return -1;
        }
        int dot = version.indexOf('.');
        String text = dot < 0 ? version : version.substring(0, dot);
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean inFile(long offset, int length, long size) {
        return offset > 0 && length > 0 && offset + length <= size;
    }

    private static ByteBuffer read(FileChannel channel, long offset, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        readInto(channel, offset, buf);
        buf.flip();
        return buf;
    }

    private static void readInto(FileChannel channel, long offset, ByteBuffer buf) throws IOException {
        int read = 0;
        int length = buf.remaining();
        while (read < length) {
            int n = channel.read(buf, offset + read);
            if (n < 0) {
                throw new IOException("truncated MDF block");
            }
            read += n;
        }
    }

    private static void readInto(FileChannel channel, long offset, byte[] dest) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(dest);
        readInto(channel, offset, buf);
    }

    private static String asciiAt(FileChannel channel, long offset) throws IOException {
        return ascii(read(channel, offset, 2), 0, 2);
    }

    private static String ascii(ByteBuffer buf, int offset, int length) {
        byte[] raw = new byte[length];
        buf.get(offset, raw);
        return ascii(raw);
    }

    private static String ascii(byte[] raw) {
        int end = 0;
        while (end < raw.length) {
            int c = raw[end] & 0xFF;
            if (c == 0 || c < 0x20) {
                break;
            }
            end++;
        }
        return new String(raw, 0, end, StandardCharsets.US_ASCII).trim();
    }

    private static final class Group {
        private final long dataOffset;
        private final int recordSize;
        private final int recordIdSize;
        private final long cycles;
        private Channel time;
        private final List<Channel> signals = new ArrayList<>();

        private Group(long dataOffset, int recordSize, int recordIdSize, long cycles) {
            this.dataOffset = dataOffset;
            this.recordSize = recordSize;
            this.recordIdSize = recordIdSize;
            this.cycles = cycles;
        }
    }

    private static final class Channel {
        private final String name;
        private final int bitOffset;
        private final int byteOffset;
        private final int bitCount;
        private final int dataType;
        private final String unit;
        private final boolean time;
        private final Double nanosPerUnit;
        private final Mdf3Conversion conversion;

        private Channel(
                String name,
                int bitOffset,
                int byteOffset,
                int bitCount,
                int dataType,
                String unit,
                boolean time,
                Double nanosPerUnit,
                Mdf3Conversion conversion
        ) {
            this.name = name;
            this.bitOffset = bitOffset;
            this.byteOffset = byteOffset;
            this.bitCount = bitCount;
            this.dataType = dataType;
            this.unit = unit;
            this.time = time;
            this.nanosPerUnit = nanosPerUnit;
            this.conversion = conversion;
        }
    }
}
