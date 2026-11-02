package com.stellar.platform.parser.mf4.dynamicschema;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MdfChannelConversionTest {

    @Test
    void identityAndLinearMatchTheAsamFormulas() {
        assertEquals(8.0, MdfChannelConversion.IDENTITY.convert(8.0).physical(), 0.0);
        MdfChannelConversion linear = MdfChannelConversion.linear(-40.0, 1.0);
        assertEquals(8.0 * 1.0 + -40.0, linear.convert(8.0).physical(), 0.0);
        assertEquals(0.0, linear.toPhysical(40L).doubleValue(), 0.0);
        assertTrue(MdfChannelConversion.fromAsam(1, new double[] {0.0, 1.0e-5}, "").isLinear());
    }

    @Test
    void rationalAlgebraicAndTabularUseIndependentResults() {
        MdfChannelConversion rational = MdfChannelConversion.fromAsam(
                2, new double[] {0, 2, 3, 0, 0, 1}, "");
        assertEquals(2.0 * 10.0 + 3.0, rational.convert(10.0).physical(), 1e-12);

        MdfChannelConversion divided = MdfChannelConversion.rational(0, 0, 1, 0, 0, 0);
        assertFalse(divided.convert(4.0).applied());
        assertTrue(divided.convert(4.0).statusDetail().contains("denominator"));

        MdfChannelConversion algebraic = MdfChannelConversion.algebraic("2*X+3");
        assertEquals(2.0 * 10.0 + 3.0, algebraic.convert(10.0).physical(), 1e-12);
        MdfChannelConversion ratio = MdfChannelConversion.algebraic("(X*X+1)/(X+2)");
        assertEquals((4.0 + 1.0) / (2.0 + 2.0), ratio.convert(2.0).physical(), 1e-12);
        assertEquals(0.0, MdfChannelConversion.algebraic("sin(X)").convert(0.0).physical(), 1e-12);
        assertEquals(8.0, MdfChannelConversion.algebraic("2^3").convert(0.0).physical(), 1e-12);

        MdfChannelConversion zeroDenom = MdfChannelConversion.algebraic("1/(X-X)");
        assertFalse(zeroDenom.convert(5.0).applied());
        assertTrue(Double.isNaN(zeroDenom.convert(5.0).physical()));

        MdfChannelConversion unknown = MdfChannelConversion.algebraic("foo(X)");
        assertFalse(unknown.applied());
        assertFalse(unknown.convert(1.0).applied());

        MdfChannelConversion interpolated = MdfChannelConversion.tabularInterpolated(new double[] {0, 0, 10, 100});
        assertEquals(50.0, interpolated.convert(5.0).physical(), 1e-12);
        assertEquals(0.0, interpolated.convert(0.0).physical(), 0.0);
        assertFalse(interpolated.convert(-1.0).applied());
        assertFalse(interpolated.convert(11.0).applied());
        assertTrue(interpolated.convert(11.0).statusDetail().contains("outside"));

        MdfChannelConversion exact = MdfChannelConversion.tabularExact(new double[] {0, 1, 10, 2});
        assertEquals(2.0, exact.convert(10.0).physical(), 0.0);
        assertFalse(exact.convert(5.0).applied());

        MdfChannelConversion ranges = MdfChannelConversion.rangeTabular(new double[] {0, 10, 1.5, 20, 30, 4});
        assertEquals(1.5, ranges.convert(10.0).physical(), 0.0);
        assertEquals(4.0, ranges.convert(25.0).physical(), 0.0);
        assertFalse(ranges.convert(15.0).applied());
    }

    @Test
    void unsupportedTypeDoesNotReturnTheRawValue() {
        MdfChannelConversion text = MdfChannelConversion.fromAsam(9, new double[0], "");
        assertFalse(text.applied());
        assertTrue(text.statusDetail().contains("cc_type=9"));
        MdfChannelConversion.ConversionResult result = text.convert(7.0);
        assertFalse(result.applied());
        assertTrue(Double.isNaN(result.physical()));
        assertThrows(IllegalStateException.class, () -> text.toPhysical(7L));
        MdfChannelConversion truncated = MdfChannelConversion.fromAsam(1, new double[] {1.0}, "");
        assertFalse(truncated.applied());
        assertFalse(truncated.isIdentity());
    }

    @Test
    void valueToTextMatchesExactKeysAndDoesNotInventLabels() {
        MdfChannelConversion conversion = MdfChannelConversion.fromAsam(
                7, new double[] {0, 1}, "",
                new String[] {"No reset", "Reset"}, "", null);
        MdfChannelConversion.ConversionResult reset = conversion.convert(1.0);
        assertTrue(reset.applied());
        assertEquals("Reset", reset.text());
        assertTrue(Double.isNaN(reset.physical()));
        assertEquals("No reset", conversion.convert(0.0).text());
        MdfChannelConversion.ConversionResult missing = conversion.convert(2.0);
        assertFalse(missing.applied());
        assertTrue(missing.text().isBlank());
        assertTrue(missing.statusDetail().contains("no exact entry"));
        assertTrue(Double.isNaN(missing.physical()));
        assertThrows(IllegalStateException.class, () -> conversion.toPhysical(1L));

        MdfChannelConversion first = MdfChannelConversion.fromAsam(
                7, new double[] {1, 1}, "",
                new String[] {"First", "Second"}, "", null);
        assertEquals("First", first.convert(1.0).text());

        MdfChannelConversion blank = MdfChannelConversion.fromAsam(
                7, new double[] {0, 4}, "",
                new String[] {"Known", ""}, "", null);
        assertEquals("Known", blank.convert(0.0).text());
        assertFalse(blank.convert(4.0).applied());
        assertTrue(blank.convert(4.0).statusDetail().contains("reference is missing"));
        assertTrue(blank.convert(4.0).text().isBlank());

        MdfChannelConversion unlabeled = MdfChannelConversion.fromAsam(7, new double[] {0, 1}, "");
        assertFalse(unlabeled.applied());
        assertTrue(unlabeled.statusDetail().contains("one label per key"));
        assertTrue(unlabeled.convert(0.0).text().isBlank());

        MdfChannelConversion empty = MdfChannelConversion.fromAsam(
                7, new double[] {0, 1}, "", new String[] {"", ""}, "", null);
        assertFalse(empty.applied());
        assertTrue(empty.statusDetail().contains("references are missing"));
        assertTrue(empty.convert(0.0).text().isBlank());
    }

    @Test
    void valueRangeToTextUsesInclusiveFileOrderRanges() {
        MdfChannelConversion conversion = MdfChannelConversion.fromAsam(
                8, new double[] {0, 0, 1, 3, 10, 10}, "",
                new String[] {"Off", "On", "Fault"}, "", null);
        assertEquals("Off", conversion.convert(0.0).text());
        assertEquals("On", conversion.convert(1.0).text());
        assertEquals("On", conversion.convert(2.0).text());
        assertEquals("On", conversion.convert(3.0).text());
        assertEquals("Fault", conversion.convert(10.0).text());
        assertTrue(Double.isNaN(conversion.convert(1.0).physical()));
        MdfChannelConversion.ConversionResult gap = conversion.convert(4.0);
        assertFalse(gap.applied());
        assertTrue(gap.statusDetail().contains("outside"));
        assertTrue(gap.text().isBlank());

        MdfChannelConversion overlap = MdfChannelConversion.fromAsam(
                8, new double[] {0, 10, 5, 5}, "",
                new String[] {"Wide", "Narrow"}, "", null);
        assertEquals("Wide", overlap.convert(5.0).text());

        MdfChannelConversion missing = MdfChannelConversion.fromAsam(
                8, new double[] {0, 1, 2, 3}, "",
                new String[] {"", "Later"}, "", null);
        assertFalse(missing.convert(0.0).applied());
        assertEquals("Later", missing.convert(2.0).text());
        assertFalse(missing.convert(1.5).applied());

        MdfChannelConversion odd = MdfChannelConversion.fromAsam(
                8, new double[] {0, 1, 2}, "",
                new String[] {"Only"}, "", null);
        assertFalse(odd.applied());
        assertTrue(odd.statusDetail().contains("lower/upper"));

        MdfChannelConversion inverted = MdfChannelConversion.fromAsam(
                8, new double[] {5, 1}, "",
                new String[] {"Backwards"}, "", null);
        assertFalse(inverted.applied());
        assertTrue(inverted.statusDetail().contains("minimum exceeds maximum"));
    }

    @Test
    void unmatchedTextUsesOnlyTheSourceDefault() {
        MdfChannelConversion label = MdfChannelConversion.fromAsam(
                7, new double[] {0}, "",
                new String[] {"Zero"}, "Other", null);
        assertEquals("Zero", label.convert(0.0).text());
        assertEquals("Other", label.convert(9.0).text());
        assertTrue(Double.isNaN(label.convert(9.0).physical()));

        MdfChannelConversion linear = MdfChannelConversion.fromAsam(
                8, new double[] {1, 1}, "",
                new String[] {"On"}, "", MdfChannelConversion.linear(100.0, 2.0));
        assertEquals("On", linear.convert(1.0).text());
        MdfChannelConversion.ConversionResult scaled = linear.convert(3.0);
        assertTrue(scaled.applied());
        assertTrue(scaled.text().isBlank());
        assertEquals(106.0, scaled.physical(), 0.0);

        MdfChannelConversion failed = MdfChannelConversion.fromAsam(
                7, new double[] {0}, "",
                new String[] {"Zero"}, "", MdfChannelConversion.fromAsam(9, new double[0], ""));
        assertEquals("Zero", failed.convert(0.0).text());
        MdfChannelConversion.ConversionResult rejected = failed.convert(4.0);
        assertFalse(rejected.applied());
        assertTrue(rejected.statusDetail().contains("cc_type=9"));
        assertTrue(rejected.text().isBlank());
        assertTrue(Double.isNaN(rejected.physical()));
    }
}
