package com.stellar.platform.parser.mf4;

import com.stellar.platform.domain.CanonicalMeasurementSample;
import com.stellar.platform.domain.CategoricalValue;
import com.stellar.platform.domain.ProcessingWarning;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mdf3SignalReaderTest {

    private static final Path SAMPLE_DAT = Path.of(
            "C:\\Users\\ADMIN\\Desktop\\DriveTech\\KALPESH\\.dat\\Xenon new script C910P41 2020-02-03 12-39-52-680142 Partition 0.dat");
    private static final Path SAMPLE_LOG = Path.of(
            "C:\\Users\\ADMIN\\Desktop\\DriveTech\\KALPESH\\.log\\Xenon new script C910P41 2020-02-05 12-03-25-688233 Partition 0.log");
    private static final Path XENON_DAT = Path.of(
            "C:\\Users\\DT - User\\Downloads\\dat files 3.1\\.dat files 3.1\\Xenon new script C910P41 2020-02-07 16-22-19-999140 Partition 0.dat");

    @TempDir
    Path temp;

    @Test
    void mdf320FamilyStreamsPhysicalChannelsWithoutHardcoding330() throws Exception {
        Path file = temp.resolve("family.dat");
        Files.write(file, sample("3.20", 320));
        List<CanonicalMeasurementSample> samples = read(file);
        List<CanonicalMeasurementSample> latitude = samples.stream()
                .filter(sample -> sample.signalName().equals("Latitude"))
                .toList();
        assertEquals(2, latitude.size());
        assertEquals(18.5, latitude.get(0).physicalValue(), 1e-9);
        assertEquals("degrees", latitude.get(0).unit());
        assertEquals(100_000_000L, latitude.get(0).timestampNanos());
        assertEquals(200_000_000L, latitude.get(1).timestampNanos());
        assertTrue(samples.stream().noneMatch(sample -> sample.signalName().equals("time")));
    }

    @Test
    void truncatedMdf3IsCorruptRatherThanUnknown() throws Exception {
        Path file = temp.resolve("broken.dat");
        byte[] header = new byte[64];
        writeAscii(header, 0, "MDF     ");
        writeAscii(header, 8, "3.30");
        Files.write(file, header);
        ParserException error = assertThrows(ParserException.class, () -> read(file));
        assertTrue(error.getMessage().contains("MDF 3.30"));
        assertTrue(error.getMessage().toLowerCase().contains("corrupt"));
    }

    @Test
    void suppliedDatAndLogExposeMdf330LatitudeWhenPresent() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(SAMPLE_DAT), "supplied DAT is not on this machine");
        Assumptions.assumeTrue(Files.isRegularFile(SAMPLE_LOG), "supplied LOG is not on this machine");
        for (Path file : List.of(SAMPLE_DAT, SAMPLE_LOG)) {
            var match = MdfContentProbe.probe(file).orElseThrow();
            assertEquals("3.30", match.version());
            assertTrue(match.parserSupported());
            List<CanonicalMeasurementSample> samples = read(file);
            List<CanonicalMeasurementSample> latitude = samples.stream()
                    .filter(sample -> sample.signalName().equals("Latitude"))
                    .toList();
            assertFalse(latitude.isEmpty());
            assertEquals("degrees", latitude.get(0).unit());
            double max = latitude.stream().mapToDouble(CanonicalMeasurementSample::physicalValue).max().orElse(0);
            double min = latitude.stream().mapToDouble(CanonicalMeasurementSample::physicalValue).min().orElse(0);
            assertTrue(max > 10 && max < 90, () -> "min=" + min + " max=" + max + " first=" + latitude.get(0).physicalValue());
            assertTrue(samples.stream().map(CanonicalMeasurementSample::signalName).distinct().count() >= 2);
        }
    }

    @Test
    void linearChannelsKeepRawTimestampUnitAndScaledPhysicalValue() throws Exception {
        Path file = temp.resolve("scaled.dat");
        Files.write(file, scaledFile());
        List<CanonicalMeasurementSample> samples = read(file);
        CanonicalMeasurementSample speed = named(samples, "IN_Vehicle_speed").getFirst();
        assertEquals(72.96875, speed.physicalValue(), 0.0);
        assertEquals(9340L, speed.rawValue());
        assertEquals("km/h", speed.unit());
        assertEquals(SignalValidity.VALID, speed.validity());
        assertEquals(100_000_000L, speed.timestampNanos());
        CanonicalMeasurementSample second = named(samples, "IN_Vehicle_speed").get(1);
        assertEquals(73.265625, second.physicalValue(), 0.0);
        assertEquals(9378L, second.rawValue());
        assertEquals(200_000_000L, second.timestampNanos());
        CanonicalMeasurementSample coolant = named(samples, "IN_Coolant_temperature").getFirst();
        assertEquals(80.0, coolant.physicalValue(), 0.0);
        assertEquals(320L, coolant.rawValue());
        assertEquals("degC", coolant.unit());
        CanonicalMeasurementSample signed = named(samples, "SignedOffset").getFirst();
        assertEquals(-36.0, signed.physicalValue(), 0.0);
        assertEquals(-8L, signed.rawValue());
        assertEquals(SignalValidity.VALID, signed.validity());
        CanonicalMeasurementSample floating = named(samples, "FloatScaled").getFirst();
        assertEquals(6.0, floating.physicalValue(), 0.0);
        assertEquals(null, floating.rawValue());
    }

    @Test
    void sameBytesProduceTheSamePhysicalValuesForDatLogAndMdf() throws Exception {
        byte[] bytes = scaledFile();
        List<CanonicalMeasurementSample> dat = read(writeExtension(bytes, "copy.dat"));
        List<CanonicalMeasurementSample> log = read(writeExtension(bytes, "copy.log"));
        List<CanonicalMeasurementSample> mdf = read(writeExtension(bytes, "copy.mdf"));
        assertEquals(dat.size(), log.size());
        assertEquals(dat.size(), mdf.size());
        for (int i = 0; i < dat.size(); i++) {
            assertEquals(dat.get(i).signalName(), log.get(i).signalName());
            assertEquals(dat.get(i).signalName(), mdf.get(i).signalName());
            assertEquals(dat.get(i).physicalValue(), log.get(i).physicalValue(), 0.0);
            assertEquals(dat.get(i).physicalValue(), mdf.get(i).physicalValue(), 0.0);
            assertEquals(dat.get(i).rawValue(), mdf.get(i).rawValue());
            assertEquals(dat.get(i).timestampNanos(), log.get(i).timestampNanos());
            assertEquals(dat.get(i).unit(), mdf.get(i).unit());
            assertEquals(dat.get(i).validity(), log.get(i).validity());
        }
    }

    @Test
    void failedConversionKeepsTheRawSampleAndDoesNotCorruptTheNextChannel() throws Exception {
        Path file = temp.resolve("mixed.dat");
        Files.write(file, mixedFile());
        List<ProcessingWarning> warnings = new ArrayList<>();
        List<CanonicalMeasurementSample> samples = read(file, warnings);
        CanonicalMeasurementSample bad = named(samples, "Broken").getFirst();
        assertEquals(SignalValidity.CONVERSION_FAILED, bad.validity());
        assertTrue(Double.isNaN(bad.physicalValue()));
        assertEquals(5L, bad.rawValue());
        CanonicalMeasurementSample good = named(samples, "Kept").getFirst();
        assertEquals(201.0, good.physicalValue(), 0.0);
        assertEquals(100L, good.rawValue());
        assertEquals(SignalValidity.VALID, good.validity());
        assertEquals(1, warnings.stream().filter(warning -> "CONVERSION_FAILED".equals(warning.code())).count());
        CanonicalMeasurementSample text = named(samples, "ETC_Fan2_output").getFirst();
        assertEquals("FALSE", text.textualValue());
        assertTrue(Double.isNaN(text.physicalValue()));
        assertEquals(SignalValidity.VALID, text.validity());
        assertEquals(0L, text.rawValue());
        assertTrue(CategoricalValue.isCategorical(text.validity(), text.physicalValue(), text.textualValue()));
    }

    @Test
    void xenonDatAppliesEveryScaledChannelFromItsOwnConversionBlock() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(XENON_DAT), "Xenon DAT is not on this machine");
        List<CanonicalMeasurementSample> samples = read(XENON_DAT);
        List<CanonicalMeasurementSample> speed = named(samples, "IN_Vehicle_speed");
        assertEquals(72.96875, speed.get(0).physicalValue(), 0.0);
        assertEquals(73.265625, speed.get(1).physicalValue(), 0.0);
        assertEquals(73.640625, speed.get(2).physicalValue(), 0.0);
        assertEquals(73.5703125, speed.get(3).physicalValue(), 0.0);
        assertEquals(List.of(9340L, 9378L, 9426L, 9417L),
                speed.stream().limit(4).map(CanonicalMeasurementSample::rawValue).toList());
        assertEquals("km/h", speed.get(0).unit());
        assertEquals(80.0, named(samples, "IN_Coolant_temperature").getFirst().physicalValue(), 0.0);
        assertEquals(320L, named(samples, "IN_Coolant_temperature").getFirst().rawValue());
        assertEquals(1743.0, named(samples, "IN_Engine_cycle_speed").getFirst().physicalValue(), 0.0);
        assertEquals(6972L, named(samples, "IN_Engine_cycle_speed").getFirst().rawValue());
        assertEquals(69.6015625, named(samples, "IN_Pedal_position").getFirst().physicalValue(), 0.0);
        assertEquals(8909L, named(samples, "IN_Pedal_position").getFirst().rawValue());

        java.util.Map<String, List<CanonicalMeasurementSample>> byName = new java.util.LinkedHashMap<>();
        for (CanonicalMeasurementSample sample : samples) {
            byName.computeIfAbsent(sample.signalName(), key -> new ArrayList<>()).add(sample);
        }
        int scaled = 0;
        for (IndependentChannel channel : independentChannels(XENON_DAT)) {
            List<CanonicalMeasurementSample> actual = byName.get(channel.name);
            assertTrue(actual != null && !actual.isEmpty(), channel.name);
            if (channel.text) {
                assertEquals(channel.textValue, actual.getFirst().textualValue(), channel.name);
                assertEquals(SignalValidity.VALID, actual.getFirst().validity());
                continue;
            }
            assertEquals(channel.expectedPhysical, actual.getFirst().physicalValue(), 0.0, channel.name);
            assertEquals(channel.expectedTimestamp, actual.getFirst().timestampNanos(), channel.name);
            if (channel.raw != null) {
                assertEquals(channel.raw, actual.getFirst().rawValue(), channel.name);
            }
            if (channel.scaled && channel.raw != null) {
                scaled++;
                assertNotEquals(channel.raw.doubleValue(), actual.getFirst().physicalValue(), channel.name);
            }
        }
        assertTrue(scaled >= 19, "scaled channels=" + scaled);
    }

    private static List<CanonicalMeasurementSample> named(List<CanonicalMeasurementSample> samples, String name) {
        return samples.stream().filter(sample -> sample.signalName().equals(name)).toList();
    }

    private Path writeExtension(byte[] bytes, String name) throws Exception {
        Path file = temp.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    private static List<CanonicalMeasurementSample> read(Path file) throws Exception {
        return read(file, new ArrayList<>());
    }

    private static List<CanonicalMeasurementSample> read(Path file, List<ProcessingWarning> warnings) throws Exception {
        List<CanonicalMeasurementSample> samples = new ArrayList<>();
        new Mdf3SignalReader().read(file, ReaderContext.ofBatchSize(32), samples::addAll, warnings::add);
        return samples;
    }

    private static byte[] sample(String version, int code) {
        ByteBuffer buf = ByteBuffer.allocate(832).order(ByteOrder.LITTLE_ENDIAN);
        byte[] id = new byte[64];
        writeAscii(id, 0, "MDF     ");
        writeAscii(id, 8, version);
        ByteBuffer.wrap(id).order(ByteOrder.LITTLE_ENDIAN).putShort(28, (short) code);
        buf.put(0, id);
        buf.put(64, (byte) 'H').put(65, (byte) 'D');
        buf.putInt(68, 100);
        buf.put(100, (byte) 'D').put(101, (byte) 'G');
        buf.putInt(108, 140);
        buf.putInt(116, 800);
        buf.put(140, (byte) 'C').put(141, (byte) 'G');
        buf.putInt(148, 180);
        buf.putShort(160, (short) 16);
        buf.putInt(162, 2);
        writeChannel(buf, 180, 400, 620, 1, "time", 0);
        writeChannel(buf, 400, 0, 700, 0, "Latitude", 64);
        writeConversion(buf, 620, "s");
        writeConversion(buf, 700, "degrees");
        buf.putDouble(800, 0.1);
        buf.putDouble(808, 18.5);
        buf.putDouble(816, 0.2);
        buf.putDouble(824, 18.6);
        return buf.array();
    }

    private static void writeChannel(
            ByteBuffer buf,
            int offset,
            int next,
            int cc,
            int type,
            String name,
            int bitOffset
    ) {
        buf.put(offset, (byte) 'C');
        buf.put(offset + 1, (byte) 'N');
        buf.putShort(offset + 2, (short) 192);
        buf.putInt(offset + 4, next);
        buf.putInt(offset + 8, cc);
        buf.putShort(offset + 24, (short) type);
        byte[] raw = name.getBytes(StandardCharsets.US_ASCII);
        buf.put(offset + 26, raw);
        buf.putShort(offset + 186, (short) bitOffset);
        buf.putShort(offset + 188, (short) 64);
        buf.putShort(offset + 190, (short) 3);
    }

    private static void writeConversion(ByteBuffer buf, int offset, String unit) {
        buf.put(offset, (byte) 'C');
        buf.put(offset + 1, (byte) 'C');
        buf.putShort(offset + 2, (short) 64);
        buf.put(offset + 22, unit.getBytes(StandardCharsets.US_ASCII));
        buf.putShort(offset + 42, (short) 0);
        buf.putShort(offset + 44, (short) 2);
        buf.putDouble(offset + 46, 0.0);
        buf.putDouble(offset + 54, 1.0);
    }

    private static void writeAscii(byte[] raw, int offset, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, raw, offset, bytes.length);
    }

    private record Field(String name, int bitOffset, int bitCount, int dataType, int ccType,
                         String unit, double p1, double p2, String text) {
    }

    private static byte[] scaledFile() {
        return file(18, 2, List.of(
                new Field("time", 0, 64, 3, 0, "s", 0, 1, null),
                new Field("IN_Vehicle_speed", 64, 16, 0, 0, "km/h", 0, 0.0078125, null),
                new Field("IN_Coolant_temperature", 80, 16, 1, 0, "degC", 0, 0.25, null),
                new Field("SignedOffset", 96, 16, 1, 0, "V", -40, -0.5, null),
                new Field("FloatScaled", 112, 32, 2, 0, "m", 1, 2, null)
        ), (buf, at) -> {
            buf.putDouble(at, 0.1);
            buf.putShort(at + 8, (short) 9340);
            buf.putShort(at + 10, (short) 320);
            buf.putShort(at + 12, (short) -8);
            buf.putFloat(at + 14, 2.5f);
            buf.putDouble(at + 18, 0.2);
            buf.putShort(at + 26, (short) 9378);
            buf.putShort(at + 28, (short) 321);
            buf.putShort(at + 30, (short) -8);
            buf.putFloat(at + 32, 2.5f);
        });
    }

    private static byte[] mixedFile() {
        return file(13, 1, List.of(
                new Field("time", 0, 64, 3, 0, "s", 0, 1, null),
                new Field("Broken", 64, 16, 0, 99, "", 0, 1, null),
                new Field("Kept", 80, 16, 0, 0, "x", 1, 2, null),
                new Field("ETC_Fan2_output", 96, 8, 0, 11, "-", 0, 1, "FALSE")
        ), (buf, at) -> {
            buf.putDouble(at, 0.1);
            buf.putShort(at + 8, (short) 5);
            buf.putShort(at + 10, (short) 100);
            buf.put(at + 12, (byte) 0);
        });
    }

    private static byte[] file(int recordSize, int cycles, List<Field> fields, java.util.function.BiConsumer<ByteBuffer, Integer> data) {
        int cnBase = 240;
        int ccBase = cnBase + fields.size() * 240;
        int dataAt = ccBase + fields.size() * 96;
        int bytes = dataAt + recordSize * cycles;
        ByteBuffer buf = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        byte[] id = new byte[64];
        writeAscii(id, 0, "MDF     ");
        writeAscii(id, 8, "3.30");
        ByteBuffer.wrap(id).order(ByteOrder.LITTLE_ENDIAN).putShort(28, (short) 330);
        buf.put(0, id);
        buf.put(64, (byte) 'H').put(65, (byte) 'D');
        buf.putInt(68, 100);
        buf.put(100, (byte) 'D').put(101, (byte) 'G');
        buf.putInt(108, 200);
        buf.putInt(116, dataAt);
        buf.put(200, (byte) 'C').put(201, (byte) 'G');
        buf.putInt(208, cnBase);
        buf.putShort(220, (short) recordSize);
        buf.putInt(222, cycles);
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            int cn = cnBase + i * 240;
            int next = i + 1 < fields.size() ? cn + 240 : 0;
            int cc = ccBase + i * 96;
            buf.put(cn, (byte) 'C');
            buf.put(cn + 1, (byte) 'N');
            buf.putShort(cn + 2, (short) 228);
            buf.putInt(cn + 4, next);
            buf.putInt(cn + 8, cc);
            buf.putShort(cn + 24, (short) (i == 0 ? 1 : 0));
            buf.put(cn + 26, field.name.getBytes(StandardCharsets.US_ASCII));
            buf.putShort(cn + 186, (short) field.bitOffset);
            buf.putShort(cn + 188, (short) field.bitCount);
            buf.putShort(cn + 190, (short) field.dataType);
            buf.put(cc, (byte) 'C');
            buf.put(cc + 1, (byte) 'C');
            if (field.ccType == 11) {
                buf.putShort(cc + 2, (short) 86);
                buf.putShort(cc + 42, (short) 11);
                buf.putShort(cc + 44, (short) 1);
                buf.putDouble(cc + 46, 0.0);
                buf.put(cc + 54, field.text.getBytes(StandardCharsets.US_ASCII));
            } else if (field.ccType == 99) {
                buf.putShort(cc + 2, (short) 46);
                buf.putShort(cc + 42, (short) 99);
            } else {
                buf.putShort(cc + 2, (short) 62);
                buf.put(cc + 22, field.unit.getBytes(StandardCharsets.US_ASCII));
                buf.putShort(cc + 42, (short) 0);
                buf.putShort(cc + 44, (short) 2);
                buf.putDouble(cc + 46, field.p1);
                buf.putDouble(cc + 54, field.p2);
            }
        }
        data.accept(buf, dataAt);
        return buf.array();
    }

    private record IndependentChannel(String name, boolean text, String textValue, double expectedPhysical,
                                      long expectedTimestamp, Long raw, boolean scaled) {
    }

    private static List<IndependentChannel> independentChannels(Path file) throws Exception {
        byte[] raw = Files.readAllBytes(file);
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        List<IndependentChannel> channels = new ArrayList<>();
        long dg = Integer.toUnsignedLong(buf.getInt(68));
        while (dg > 0) {
            long cg = Integer.toUnsignedLong(buf.getInt((int) dg + 8));
            long data = Integer.toUnsignedLong(buf.getInt((int) dg + 16));
            int recordId = Short.toUnsignedInt(buf.getShort((int) dg + 22));
            while (cg > 0) {
                long cn = Integer.toUnsignedLong(buf.getInt((int) cg + 8));
                int recordSize = Short.toUnsignedInt(buf.getShort((int) cg + 20));
                List<long[]> numeric = new ArrayList<>();
                while (cn > 0) {
                    int length = Short.toUnsignedInt(buf.getShort((int) cn + 2));
                    int channelType = Short.toUnsignedInt(buf.getShort((int) cn + 24));
                    String name = text(buf, (int) cn + 26, 32);
                    int bitOffset = Short.toUnsignedInt(buf.getShort((int) cn + 186));
                    int bitCount = Short.toUnsignedInt(buf.getShort((int) cn + 188));
                    int dataType = Short.toUnsignedInt(buf.getShort((int) cn + 190));
                    int extra = length >= 228 ? Short.toUnsignedInt(buf.getShort((int) cn + 226)) : 0;
                    long cc = Integer.toUnsignedLong(buf.getInt((int) cn + 8));
                    int ccType = -1;
                    double p1 = 0;
                    double p2 = 1;
                    String label = null;
                    if (cc > 0) {
                        ccType = Short.toUnsignedInt(buf.getShort((int) cc + 42));
                        if (ccType == 0) {
                            p1 = buf.getDouble((int) cc + 46);
                            p2 = buf.getDouble((int) cc + 54);
                        } else if (ccType == 11) {
                            label = text(buf, (int) cc + 54, 32);
                        }
                    }
                    if (bitOffset % 8 == 0 && bitCount % 8 == 0 && bitCount >= 8 && bitCount <= 64) {
                        numeric.add(new long[] {channelType, bitOffset, bitCount, dataType, extra, ccType,
                                Double.doubleToRawLongBits(p1), Double.doubleToRawLongBits(p2)});
                        numeric.get(numeric.size() - 1);
                        if (channelType != 1 && recordSize > 0) {
                            byte[] record = new byte[recordSize];
                            System.arraycopy(raw, (int) data + recordId, record, 0, recordSize);
                            Double time = null;
                            for (long[] item : numeric) {
                                if (item[0] == 1) {
                                    time = decodeIndependent(record, item);
                                }
                            }
                            Double value = decodeIndependent(record, numeric.get(numeric.size() - 1));
                            if (time != null && value != null) {
                                double physical = ccType == 0 || ccType < 0 ? value * p2 + p1 : value;
                                boolean scaled = ccType == 0 && !(p1 == 0.0 && p2 == 1.0);
                                Long bits = integerBits(record, extra + bitOffset / 8, bitCount / 8, dataType);
                                long nanos = Math.round(time * 1_000_000_000.0);
                                if (ccType == 11) {
                                    channels.add(new IndependentChannel(name, true, label, Double.NaN, nanos, bits, false));
                                } else if (ccType < 0 || ccType == 0) {
                                    channels.add(new IndependentChannel(name, false, null, physical, nanos, bits, scaled));
                                }
                            }
                        }
                    }
                    cn = Integer.toUnsignedLong(buf.getInt((int) cn + 4));
                }
                cg = Integer.toUnsignedLong(buf.getInt((int) cg + 4));
            }
            dg = Integer.toUnsignedLong(buf.getInt((int) dg + 4));
        }
        return channels;
    }

    private static Double decodeIndependent(byte[] record, long[] item) {
        int start = (int) item[4] + (int) item[1] / 8;
        int count = (int) item[2] / 8;
        int dataType = (int) item[3];
        if (start < 0 || start + count > record.length) {
            return null;
        }
        ByteBuffer value = ByteBuffer.wrap(record, start, count).order(
                dataType >= 9 && dataType <= 12 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        return switch (dataType) {
            case 0, 9, 13 -> unsignedIndependent(value, count);
            case 1, 10, 14 -> signedIndependent(value, count);
            case 2, 11, 15 -> count == 4 ? (double) value.getFloat() : null;
            case 3, 12, 16 -> count == 8 ? value.getDouble() : null;
            default -> null;
        };
    }

    private static Long integerBits(byte[] record, int start, int count, int dataType) {
        if (dataType > 1 && dataType != 9 && dataType != 10 && dataType != 13 && dataType != 14) {
            return null;
        }
        if (start < 0 || start + count > record.length || count > 8) {
            return null;
        }
        boolean big = dataType >= 9 && dataType <= 12;
        long bits = 0;
        for (int i = 0; i < count; i++) {
            int source = big ? count - 1 - i : i;
            bits |= (record[start + source] & 0xFFL) << (8 * i);
        }
        boolean signed = dataType == 1 || dataType == 10 || dataType == 14;
        if (signed && count < 8) {
            long sign = 1L << (count * 8 - 1);
            if ((bits & sign) != 0) {
                bits |= -1L << (count * 8);
            }
        }
        return bits;
    }

    private static Double unsignedIndependent(ByteBuffer value, int count) {
        long bits = 0;
        for (int i = 0; i < count; i++) {
            bits |= (value.get() & 0xFFL) << (8 * i);
        }
        return (double) bits;
    }

    private static Double signedIndependent(ByteBuffer value, int count) {
        long bits = unsignedIndependent(value, count).longValue();
        if (count < 8) {
            long sign = 1L << (count * 8 - 1);
            if ((bits & sign) != 0) {
                bits |= -1L << (count * 8);
            }
        }
        return (double) bits;
    }

    private static String text(ByteBuffer buf, int offset, int length) {
        byte[] raw = new byte[length];
        for (int i = 0; i < length; i++) {
            raw[i] = buf.get(offset + i);
        }
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
}
