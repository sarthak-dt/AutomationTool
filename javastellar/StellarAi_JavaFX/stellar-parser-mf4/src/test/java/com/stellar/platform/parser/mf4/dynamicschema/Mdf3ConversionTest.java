package com.stellar.platform.parser.mf4.dynamicschema;

import com.stellar.platform.domain.CategoricalValue;
import com.stellar.platform.domain.SignalValidity;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Expected numbers are calculated in the test, not by calling the converter twice.
 */
class Mdf3ConversionTest {

    @Test
    void linearUsesMdf3ParameterOrderAndNotMdf4TypeNumbers() {
        Mdf3Conversion conversion = Mdf3Conversion.parse(linear(0.0, 0.0078125));
        assertEquals(Mdf3Conversion.TYPE_LINEAR, conversion.type());
        assertEquals(0, MdfChannelConversion.TYPE_IDENTITY);
        assertEquals(1, MdfChannelConversion.TYPE_LINEAR);
        assertEquals(0, new java.math.BigDecimal("9340").compareTo(MdfChannelConversion.IDENTITY.toPhysical(9340L)));
        assertEquals(0, new java.math.BigDecimal("72.96875").compareTo(
                MdfChannelConversion.linear(0.0, 0.0078125).toPhysical(9340L)));
        assertEquals(72.96875, conversion.apply(9340).physical(), 0.0);
        assertEquals(73.265625, conversion.apply(9378).physical(), 0.0);
        assertEquals(73.640625, conversion.apply(9426).physical(), 0.0);
        assertEquals(73.5703125, conversion.apply(9417).physical(), 0.0);
        assertEquals(80.0, Mdf3Conversion.parse(linear(0.0, 0.25)).apply(320).physical(), 0.0);
        assertEquals(1743.0, Mdf3Conversion.parse(linear(0.0, 0.25)).apply(6972).physical(), 0.0);
        assertEquals(69.6015625, Mdf3Conversion.parse(linear(0.0, 0.0078125)).apply(8909).physical(), 0.0);
    }

    @Test
    void negativeFactorAndNonZeroOffsetStayExact() {
        Mdf3Conversion conversion = Mdf3Conversion.parse(linear(-40.0, -0.5));
        assertEquals(-40.0 + -0.5 * 8.0, conversion.apply(8).physical(), 0.0);
        assertEquals(-44.0, conversion.apply(8).physical(), 0.0);
        assertEquals(SignalValidity.VALID, conversion.apply(-2).validity());
        assertEquals(-39.0, conversion.apply(-2).physical(), 0.0);
    }

    @Test
    void identityLinearAndMissingBlockLeaveTheRawValue() {
        assertEquals(18.5, Mdf3Conversion.parse(linear(0.0, 1.0)).apply(18.5).physical(), 0.0);
        assertEquals(18.5, Mdf3Conversion.identity().apply(18.5).physical(), 0.0);
        ByteBuffer none = linear(0, 1);
        none.putShort(42, (short) Mdf3Conversion.TYPE_NONE);
        assertEquals(18.5, Mdf3Conversion.parse(none).apply(18.5).physical(), 0.0);
    }

    @Test
    void tabularInterpolationAndStepUseTheTableNotTheRawNumber() {
        Mdf3Conversion interpolated = Mdf3Conversion.parse(table(Mdf3Conversion.TYPE_TABULAR_INTERPOLATED,
                0, 0, 10, 100));
        assertEquals(50.0, interpolated.apply(5).physical(), 1e-12);
        assertEquals(0.0, interpolated.apply(-5).physical(), 0.0);
        assertEquals(100.0, interpolated.apply(25).physical(), 0.0);
        Mdf3Conversion step = Mdf3Conversion.parse(table(Mdf3Conversion.TYPE_TABULAR, 0, 1, 10, 2, 20, 4));
        assertEquals(1.0, step.apply(0).physical(), 0.0);
        assertEquals(2.0, step.apply(10).physical(), 0.0);
        assertEquals(2.0, step.apply(15).physical(), 0.0);
        assertEquals(4.0, step.apply(20).physical(), 0.0);
    }

    @Test
    void rationalPolynomialExponentialAndAlgebraicMatchClosedForms() {
        // (1*x^2 + 0*x + 0) / (0*x^2 + 0*x + 4) = x^2 / 4
        Mdf3Conversion rational = Mdf3Conversion.parse(six(Mdf3Conversion.TYPE_RATIONAL, 1, 0, 0, 0, 0, 4));
        assertEquals(9.0, rational.apply(6).physical(), 1e-12);
        // P1=1, P2=0, P3=1, P4=0, P5=0, P6=0 → (0 - 0) / (1*(x-0-0) - 1) = 0 / (x - 1)
        Mdf3Conversion polynomial = Mdf3Conversion.parse(six(Mdf3Conversion.TYPE_POLYNOMIAL, 1, 0, 1, 0, 0, 0));
        assertEquals(0.0, polynomial.apply(3).physical(), 0.0);
        assertEquals(SignalValidity.CONVERSION_FAILED, polynomial.apply(1).validity());
        // exp((x * 1 - 0) / 1) / 1 with P4=0, P1=1, P2=1, P3=0, P5=0, P6=1, P7=0
        Mdf3Conversion exponential = Mdf3Conversion.parse(seven(Mdf3Conversion.TYPE_EXPONENTIAL, 1, 1, 0, 0, 0, 1, 0));
        assertEquals(Math.exp(2.0), exponential.apply(2).physical(), 1e-12);
        Mdf3Conversion logarithmic = Mdf3Conversion.parse(seven(Mdf3Conversion.TYPE_LOGARITHMIC, 1, 1, 0, 0, 0, 1, 0));
        assertEquals(2.0, logarithmic.apply(Math.exp(2.0)).physical(), 1e-12);
        Mdf3Conversion algebraic = Mdf3Conversion.parse(formula("2*X+3"));
        assertEquals(7.0, algebraic.apply(2).physical(), 0.0);
    }

    @Test
    void textConversionsDoNotPublishTheRawNumber() {
        Mdf3Conversion texts = Mdf3Conversion.parse(valueText(0.0, "FALSE"));
        Mdf3Conversion.Result matched = texts.apply(0);
        assertEquals("FALSE", matched.text());
        assertTrue(Double.isNaN(matched.physical()));
        assertEquals(SignalValidity.VALID, matched.validity());
        assertTrue(CategoricalValue.isCategorical(matched.validity(), matched.physical(), matched.text()));
        Mdf3Conversion.Result truth = Mdf3Conversion.parse(valueText(1.0, "TRUE")).apply(1);
        assertEquals("TRUE", CategoricalValue.label(truth.validity(), truth.physical(), truth.text()));
        assertEquals(SignalValidity.CONVERSION_FAILED, texts.apply(1).validity());
        assertFalse(CategoricalValue.isCategorical(
                texts.apply(1).validity(), texts.apply(1).physical(), texts.apply(1).text()));
        assertTrue(Double.isNaN(texts.apply(1).physical()));

        Mdf3Conversion ranges = Mdf3Conversion.parse(rangeText(0, 10, "LOW", 11, 20, "HIGH"));
        assertEquals("LOW", ranges.apply(10).text());
        assertEquals("HIGH", ranges.apply(11).text());
        assertEquals(SignalValidity.CONVERSION_FAILED, ranges.apply(21).validity());
        ByteBuffer embedded = valueText(0.0, "LINEAR_CONV");
        assertEquals(SignalValidity.CONVERSION_FAILED, Mdf3Conversion.parse(embedded).apply(0).validity());
        assertTrue(Double.isNaN(Mdf3Conversion.parse(embedded).apply(0).physical()));
    }

    @Test
    void truncatedUnknownAndNonFiniteParametersFailWithoutReturningRaw() {
        ByteBuffer truncated = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
        truncated.put(0, (byte) 'C');
        truncated.put(1, (byte) 'C');
        truncated.putShort(2, (short) 46);
        truncated.putShort(42, (short) 0);
        Mdf3Conversion.Result shortBlock = Mdf3Conversion.parse(truncated).apply(9340);
        assertEquals(SignalValidity.CONVERSION_FAILED, shortBlock.validity());
        assertTrue(Double.isNaN(shortBlock.physical()));

        ByteBuffer unknown = linear(0, 1);
        unknown.putShort(42, (short) 99);
        Mdf3Conversion.Result unsupported = Mdf3Conversion.parse(unknown).apply(5);
        assertEquals(SignalValidity.CONVERSION_FAILED, unsupported.validity());
        assertFalse(unsupported.detail().isBlank());

        ByteBuffer bad = linear(Double.NaN, 1);
        assertEquals(SignalValidity.CONVERSION_FAILED, Mdf3Conversion.parse(bad).apply(1).validity());
    }

    private static ByteBuffer linear(double p1, double p2) {
        ByteBuffer buf = ByteBuffer.allocate(62).order(ByteOrder.LITTLE_ENDIAN);
        header(buf, 62, Mdf3Conversion.TYPE_LINEAR, 2);
        buf.putDouble(46, p1);
        buf.putDouble(54, p2);
        return buf;
    }

    private static ByteBuffer table(int type, double... pairs) {
        ByteBuffer buf = ByteBuffer.allocate(46 + pairs.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        header(buf, buf.capacity(), type, pairs.length / 2);
        for (int i = 0; i < pairs.length; i++) {
            buf.putDouble(46 + i * 8, pairs[i]);
        }
        return buf;
    }

    private static ByteBuffer six(int type, double... values) {
        ByteBuffer buf = ByteBuffer.allocate(46 + 48).order(ByteOrder.LITTLE_ENDIAN);
        header(buf, buf.capacity(), type, 6);
        for (int i = 0; i < values.length; i++) {
            buf.putDouble(46 + i * 8, values[i]);
        }
        return buf;
    }

    private static ByteBuffer seven(int type, double... values) {
        ByteBuffer buf = ByteBuffer.allocate(46 + 56).order(ByteOrder.LITTLE_ENDIAN);
        header(buf, buf.capacity(), type, 7);
        for (int i = 0; i < values.length; i++) {
            buf.putDouble(46 + i * 8, values[i]);
        }
        return buf;
    }

    private static ByteBuffer formula(String text) {
        byte[] bytes = (text + "\0").getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buf = ByteBuffer.allocate(46 + bytes.length).order(ByteOrder.LITTLE_ENDIAN);
        header(buf, buf.capacity(), Mdf3Conversion.TYPE_ALGEBRAIC, bytes.length);
        buf.put(46, bytes);
        return buf;
    }

    private static ByteBuffer valueText(double key, String text) {
        ByteBuffer buf = ByteBuffer.allocate(46 + 40).order(ByteOrder.LITTLE_ENDIAN);
        header(buf, buf.capacity(), Mdf3Conversion.TYPE_VALUE_TO_TEXT, 1);
        buf.putDouble(46, key);
        buf.put(54, text.getBytes(StandardCharsets.US_ASCII));
        return buf;
    }

    private static ByteBuffer rangeText(double low1, double high1, String text1, double low2, double high2, String text2) {
        ByteBuffer buf = ByteBuffer.allocate(46 + 48 * 2).order(ByteOrder.LITTLE_ENDIAN);
        header(buf, buf.capacity(), Mdf3Conversion.TYPE_VALUE_RANGE_TO_TEXT, 2);
        putRange(buf, 46, low1, high1, text1);
        putRange(buf, 46 + 48, low2, high2, text2);
        return buf;
    }

    private static void putRange(ByteBuffer buf, int offset, double low, double high, String text) {
        buf.putDouble(offset, low);
        buf.putDouble(offset + 8, high);
        buf.put(offset + 16, text.getBytes(StandardCharsets.US_ASCII));
    }

    private static void header(ByteBuffer buf, int size, int type, int count) {
        buf.put(0, (byte) 'C');
        buf.put(1, (byte) 'C');
        buf.putShort(2, (short) size);
        buf.putShort(42, (short) type);
        buf.putShort(44, (short) count);
    }
}
