package com.stellar.platform.desktop.ui.graph.map;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Deterministic 256×256 diagnostic PNG labelled with z/x/y. Not a production tile source. */
final class MapSyntheticTilePng {

    static final int SIZE = 256;

    private MapSyntheticTilePng() {
    }

    static byte[] png(int z, int x, int y) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            boolean even = ((x + y) & 1) == 0;
            g.setColor(even ? new Color(36, 92, 168) : new Color(28, 128, 84));
            g.fillRect(0, 0, SIZE, SIZE);
            g.setColor(new Color(255, 255, 255, 60));
            for (int i = 0; i <= SIZE; i += 32) {
                g.drawLine(i, 0, i, SIZE);
                g.drawLine(0, i, SIZE, i);
            }
            g.setStroke(new BasicStroke(6f));
            g.setColor(Color.WHITE);
            g.drawRect(3, 3, SIZE - 7, SIZE - 7);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 22));
            g.drawString(z + " / " + x + " / " + y, 16, 40);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
            g.drawString("stellar-probe", 16, SIZE - 18);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, "png", out)) {
                throw new IOException("PNG writer missing");
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }
}
