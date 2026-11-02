package com.stellar.platform.processing.align;

import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoricalAlignmentTest {

    @Test
    void alignedTableKeepsTheLabelAndLeavesAGapForAMissingTick() {
        List<SignalSamplePoint> fan = List.of(
                new SignalSamplePoint(5_998_194L, Double.NaN, 0L, "FALSE", SignalValidity.VALID));
        List<SignalSamplePoint> speed = List.of(
                new SignalSamplePoint(99_013_388L, 72.96875, 9340L, null, SignalValidity.VALID));
        AlignedSignalPage page = AlignedSignalQuery.query(List.of(fan, speed), 10);
        assertEquals(2, page.rows().size());
        AlignedSignalRow first = page.rows().getFirst();
        assertEquals("FALSE", first.textualAt(0));
        assertTrue(Double.isNaN(first.physicalAt(0)));
        assertEquals(0L, first.rawAt(0));
        assertNull(first.physicalAt(1));
        assertNull(first.rawAt(1));
        AlignedSignalRow second = page.rows().get(1);
        assertNull(second.physicalAt(0));
        assertEquals(72.96875, second.physicalAt(1), 0.0);
    }
}
