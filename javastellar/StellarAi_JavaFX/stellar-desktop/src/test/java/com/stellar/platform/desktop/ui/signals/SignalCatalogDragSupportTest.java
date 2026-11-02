package com.stellar.platform.desktop.ui.signals;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SignalCatalogDragSupportTest {

    @Test
    void encodeDecodeRoundTrip() {
        SignalCatalogKey key = new SignalCatalogKey(
                "S01PID0C_EngineRPM",
                SignalSourceType.DBC_CAN,
                "OBD2",
                0x7E8L,
                "run.mf4",
                "veh.dbc"
        );
        List<SignalCatalogKey> decoded = SignalCatalogDragSupport.decode(SignalCatalogDragSupport.encode(List.of(key)));
        assertEquals(1, decoded.size());
        assertEquals(key, decoded.getFirst());
    }

    @Test
    void multiKeyEncodeDecode() {
        SignalCatalogKey a = new SignalCatalogKey(
                "A", SignalSourceType.DBC_CAN, "M", 1L, "f.mf4", "d.dbc");
        SignalCatalogKey b = new SignalCatalogKey(
                "B", SignalSourceType.MF4_NATIVE, "MF4", 0L, "f.mf4", "");
        List<SignalCatalogKey> decoded = SignalCatalogDragSupport.decode(
                SignalCatalogDragSupport.encode(List.of(a, b)));
        assertEquals(List.of(a, b), decoded);
    }
}
