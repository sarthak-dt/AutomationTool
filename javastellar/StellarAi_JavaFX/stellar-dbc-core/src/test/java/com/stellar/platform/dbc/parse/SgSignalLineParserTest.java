package com.stellar.platform.dbc.parse;

import com.stellar.platform.dbc.model.DbcByteOrder;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.model.DbcValueType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SgSignalLineParserTest {

    /**
     * Permanent regression: user-reported form (underscore in name, empty unit, Vector_XXX).
     */
    @Test
    void regression_s01PidWithUnderscoreName_emptyUnit_vectorReceiver() throws Exception {
        DbcSignal signal = SgSignalLineParser.parse(
                "SG_ S01PID_m65M : 23|8@0+ (1,0) [0|255] \"\" Vector_XXX",
                41
        );
        assertEquals("S01PID_m65M", signal.name());
        assertEquals(23, signal.startBit());
        assertEquals(8, signal.bitLength());
        assertEquals(DbcByteOrder.MOTOROLA, signal.byteOrder());
        assertEquals(DbcValueType.UNSIGNED, signal.valueType());
        assertEquals(1.0, signal.factor(), 0.0);
        assertEquals(0.0, signal.offset(), 0.0);
        assertEquals(0.0, signal.minimum(), 0.0);
        assertEquals(255.0, signal.maximum(), 0.0);
        assertEquals("", signal.unit());
        assertTrue(signal.receivers().contains("Vector_XXX"));
        assertEquals(DbcMultiplexRole.NONE, signal.multiplexRole());
    }

    /**
     * Permanent regression: exact failing line from 11_bit_OBD2 2.dbc (extended mux m65M).
     */
    @Test
    void regression_realObd2Line41_extendedMultiplexM65M() throws Exception {
        DbcSignal signal = SgSignalLineParser.parse(
                "SG_ S01PID m65M : 23|8@0+ (1,0) [0|255] \"\" Vector__XXX",
                41
        );
        assertEquals("S01PID", signal.name());
        assertEquals(DbcMultiplexRole.MULTIPLEXED, signal.multiplexRole());
        assertEquals(65, signal.multiplexValue());
        assertTrue(signal.secondaryMultiplexor());
        assertEquals(23, signal.startBit());
        assertEquals(8, signal.bitLength());
        assertEquals(DbcByteOrder.MOTOROLA, signal.byteOrder());
        assertEquals(DbcValueType.UNSIGNED, signal.valueType());
        assertEquals(1.0, signal.factor(), 0.0);
        assertEquals(0.0, signal.offset(), 0.0);
        assertEquals("", signal.unit());
        assertTrue(signal.receivers().contains("Vector__XXX"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "SG_ Speed : 0|16@1+ (0.01,0) [0|250] \"km/h\" ECU; Speed; 0; 16; INTEL; UNSIGNED; 0.01; 0; 0; 250; km/h; ECU",
            "SG_ Temp : 8|8@0- (1,-40) [-40|215] \"deg C\" ECU; Temp; 8; 8; MOTOROLA; SIGNED; 1; -40; -40; 215; deg C; ECU",
            "SG_ State : 16|8@1+ (1,0) [0|255] \"\" Vector_XXX; State; 16; 8; INTEL; UNSIGNED; 1; 0; 0; 255; ; Vector_XXX",
            "SG_ Value : 24|12@0+ (0.5, 1) [0 | 100] \"\" Node_1; Value; 24; 12; MOTOROLA; UNSIGNED; 0.5; 1; 0; 100; ; Node_1"
    })
    void realWorldVariations(
            String line,
            String name,
            int start,
            int length,
            DbcByteOrder order,
            DbcValueType type,
            double factor,
            double offset,
            double min,
            double max,
            String unit,
            String receiver
    ) throws Exception {
        DbcSignal signal = SgSignalLineParser.parse(line, 1);
        assertEquals(name, signal.name());
        assertEquals(start, signal.startBit());
        assertEquals(length, signal.bitLength());
        assertEquals(order, signal.byteOrder());
        assertEquals(type, signal.valueType());
        assertEquals(factor, signal.factor(), 1e-12);
        assertEquals(offset, signal.offset(), 1e-12);
        assertEquals(min, signal.minimum(), 1e-12);
        assertEquals(max, signal.maximum(), 1e-12);
        assertEquals(unit == null ? "" : unit, signal.unit());
        assertTrue(signal.receivers().contains(receiver));
    }

    @Test
    void multiplexedSignal_m0() throws Exception {
        DbcSignal signal = SgSignalLineParser.parse(
                "SG_ Speed m0 : 8|8@1+ (1,0) [0|255] \"km/h\" ECU",
                1
        );
        assertEquals(DbcMultiplexRole.MULTIPLEXED, signal.multiplexRole());
        assertEquals(0, signal.multiplexValue());
        assertEquals("Speed", signal.name());
    }

    @Test
    void multiplexorSignal_M() throws Exception {
        DbcSignal signal = SgSignalLineParser.parse(
                "SG_ Mode M : 0|8@1+ (1,0) [0|1] \"\" ECU",
                1
        );
        assertEquals(DbcMultiplexRole.MULTIPLEXOR, signal.multiplexRole());
        assertEquals("Mode", signal.name());
    }

    @Test
    void multipleReceivers_commaSeparated() throws Exception {
        DbcSignal signal = SgSignalLineParser.parse(
                "SG_ Flag : 0|1@1+ (1,0) [0|1] \"\" ECU1,Gateway_Node,ECM",
                1
        );
        assertEquals(3, signal.receivers().size());
        assertTrue(signal.receivers().contains("ECU1"));
        assertTrue(signal.receivers().contains("Gateway_Node"));
        assertTrue(signal.receivers().contains("ECM"));
    }

    @Test
    void scientificNotationFactor() throws Exception {
        DbcSignal signal = SgSignalLineParser.parse(
                "SG_ Tiny : 0|8@1+ (1e-3,0) [0|1] \"\" ECU",
                1
        );
        assertEquals(0.001, signal.factor(), 1e-12);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SG_ Bad : broken",
            "SG_ Bad : 0|8@2+ (1,0) [0|1] \"\" ECU",
            "SG_ Bad : 0|8@1+ (1,0) [0|1] \"unterminated ECU"
    })
    void rejectsMalformedSg(String line) {
        DbcParseException ex = assertThrows(DbcParseException.class, () -> SgSignalLineParser.parse(line, 7));
        assertTrue(ex.code().name().startsWith("DBC_SG_")
                || ex.code() == DbcDiagnosticCode.INVALID_SIGNAL_DEFINITION);
        assertEquals(7, ex.lineNumber());
    }
}
