package com.stellar.platform.desktop.ui.graph.map;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapSyntheticTilePngTest {

    @Test
    void pngIs256AndDistinctPerTile() throws IOException {
        byte[] a = MapSyntheticTilePng.png(11, 1465, 948);
        byte[] b = MapSyntheticTilePng.png(11, 1466, 948);
        assertTrue(a.length > 32);
        assertEquals((byte) 0x89, a[0]);
        assertEquals((byte) 'P', a[1]);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(a));
        assertEquals(256, image.getWidth());
        assertEquals(256, image.getHeight());
        assertTrue(a.length != b.length || !java.util.Arrays.equals(a, b));
    }
}
