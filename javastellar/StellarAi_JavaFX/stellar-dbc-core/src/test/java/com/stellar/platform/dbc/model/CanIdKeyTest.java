package com.stellar.platform.dbc.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanIdKeyTest {

    @Test
    void normalizesStandardId() {
        CanIdKey key = CanIdKey.fromDbcRawId(0x100);
        assertEquals(0x100L, key.canId());
        assertFalse(key.extended());
    }

    @Test
    void normalizesExtendedIdWithVectorFlag() {
        CanIdKey key = CanIdKey.fromDbcRawId(0x80000100L);
        assertEquals(0x100L, key.canId());
        assertTrue(key.extended());
        assertEquals(0x80000100L, key.toDbcRawId());
    }

    @Test
    void rejectsOutOfRangeStandardId() {
        assertThrows(IllegalArgumentException.class, () -> new CanIdKey(0x800L, false));
    }

    @Test
    void frameKeyMatchesDbcExtendedKey() {
        long raw = CanIdKey.DBC_EXTENDED_FLAG | 0x18FAF110L;
        CanIdKey key = CanIdKey.fromDbcRawId(raw);
        assertEquals(CanIdKey.fromFrame(0x18FAF110L, true), key);
    }
}
