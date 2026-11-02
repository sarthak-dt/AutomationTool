package com.stellar.platform.server.decoder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecoderSampleJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void nonFiniteValuesAreStringsAndTimestampsAreExact() throws Exception {
        String json = mapper.writeValueAsString(DecoderSampleJson.sample(
                new SignalSamplePoint(1_500_000_000L, Double.NaN, 2143289344L, null, SignalValidity.NON_FINITE_VALUE),
                0));
        assertFalse(json.matches("(?s).*[:]\\s*NaN\\b.*"));
        assertFalse(json.matches("(?s).*[:]\\s*-?Infinity\\b.*"));
        assertTrue(json.contains("\"NaN\""));
        assertTrue(json.contains("\"timestampNs\":\"1500000000\""));
        assertTrue(json.contains("\"sampleIndex\":\"0\""));
        assertTrue(json.contains("\"value\":\"2143289344\""));

        String infinity = mapper.writeValueAsString(DecoderSampleJson.physical(Double.NEGATIVE_INFINITY));
        assertTrue(infinity.contains("\"-Infinity\""));
        assertFalse(infinity.matches("(?s).*[:]\\s*-Infinity\\b.*"));

        Map<String, Object> unavailable = DecoderSampleJson.sample(
                new SignalSamplePoint(0L, 1.25, null, "ON", SignalValidity.VALID), 3);
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) unavailable.get("raw");
        assertEquals("unavailable", raw.get("representation"));
        assertNull(raw.get("value"));
        assertEquals("ON", unavailable.get("textualValue"));
        assertEquals("VALID", unavailable.get("validity"));
        @SuppressWarnings("unchecked")
        Map<String, Object> physical = (Map<String, Object>) unavailable.get("physical");
        assertEquals("java-double", physical.get("representation"));
        assertEquals("1.25", physical.get("value"));
    }

    @Test
    void columnsStayAlignedAndDoNotEmitBareNonFiniteLiterals() throws Exception {
        Map<String, List<?>> columns = DecoderSampleJson.columns(List.of(
                new SignalSamplePoint(100_000_000L, 1.5, 3L, "Ready", SignalValidity.VALID),
                new SignalSamplePoint(200_000_000L, Double.POSITIVE_INFINITY, null, null, SignalValidity.NON_FINITE_VALUE),
                new SignalSamplePoint(300_000_000L, 5.0, Long.MIN_VALUE, null, SignalValidity.VALID)), 4);
        assertEquals(3, columns.get("timestamps").size());
        assertEquals(columns.get("timestamps").size(), columns.get("timestampsNs").size());
        assertEquals(columns.get("timestamps").size(), columns.get("samples").size());
        assertEquals(columns.get("timestamps").size(), columns.get("rawSamples").size());
        assertEquals(columns.get("timestamps").size(), columns.get("textValues").size());
        assertEquals(columns.get("timestamps").size(), columns.get("validity").size());
        assertEquals(columns.get("timestamps").size(), columns.get("sampleIndex").size());
        assertEquals(columns.get("timestamps").size(), columns.get("sourceDatetime").size());
        assertNull(columns.get("sourceDatetime").get(0));
        assertEquals("100000000", columns.get("timestampsNs").get(0));
        assertEquals("4", columns.get("sampleIndex").get(0));
        assertEquals("Ready", columns.get("textValues").get(0));
        assertNull(columns.get("rawSamples").get(1));
        assertEquals(Long.toString(Long.MIN_VALUE), columns.get("rawSamples").get(2));
        String json = mapper.writeValueAsString(columns);
        assertFalse(json.matches("(?s).*[:]\\s*Infinity\\b.*"));
        assertTrue(json.contains("\"Infinity\""));
    }
}
