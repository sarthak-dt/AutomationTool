package com.stellar.platform.parser.mf4.dynamicschema;

import com.stellar.platform.parser.mf4.schema.MdfCnMetadata;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EDL/FDF are CAN FD indication; BRS is a secondary flag — must not create FD ambiguity.
 */
class MdfCanFdIndicationClassifierTest {

    @Test
    void edl_isCanFdIndication_brs_isFlags() {
        MdfCanRoleCandidate edl = MdfCanChannelClassifier.classify(member("CAN_DataFrame.EDL", 1));
        MdfCanRoleCandidate brs = MdfCanChannelClassifier.classify(member("CAN_DataFrame.BRS", 1));
        MdfCanRoleCandidate fdf = MdfCanChannelClassifier.classify(member("CAN_DataFrame.FDF", 1));

        assertEquals(MdfCanRole.CAN_FD_INDICATION, edl.role(), edl.evidence().toString());
        assertEquals(MdfCanRole.CAN_FD_INDICATION, fdf.role(), fdf.evidence().toString());
        assertEquals(MdfCanRole.FLAGS, brs.role(), brs.evidence().toString());
        assertNotEquals(MdfCanRole.CAN_FD_INDICATION, brs.role());
        assertTrue(edl.evidence().stream().noneMatch(e -> e.contains("ambiguous")));
    }

    @Test
    void compositionParent_hasNoCanRole() {
        MdfCnMetadata parent = new MdfCnMetadata(
                100L, 1L, 0L, 200L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                0, 0, 10, 0, 0, 64, 0x400, 0,
                "CAN_DataFrame", "", 1, 2, "CAN", "CAN",
                com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion.IDENTITY
        );
        assertEquals(MdfCanRole.NONE, MdfCanChannelClassifier.classify(parent).role());
    }

    private static MdfCnMetadata member(String name, long bits) {
        return new MdfCnMetadata(
                10L, 1L, 0L, 0L, 100L, 0L, 0L, 0L, 0L, 0L, 0L,
                0, 0, 0, 0, 0, bits, 0x400, 0,
                name, "", 1, 2, "CAN", "CAN",
                com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion.IDENTITY
        );
    }
}
