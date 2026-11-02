package com.stellar.platform.desktop.ui;

import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

/**
 * Consistent 18px line icons for application navigation.
 * Stroke-only shapes — no emoji, no paid icon packs.
 */
public final class NavIcons {

    public static final double SIZE = 18;

    private NavIcons() {
    }

    public static Node dashboard() {
        return icon(
                rounded(1.6, 1.6, 6.4, 6.4),
                rounded(10.0, 1.6, 6.4, 6.4),
                rounded(1.6, 10.0, 6.4, 6.4),
                rounded(10.0, 10.0, 6.4, 6.4)
        );
    }

    public static Node projectFiles() {
        Polyline folder = new Polyline(
                2.0, 6.2,
                2.0, 15.0,
                16.0, 15.0,
                16.0, 7.2,
                9.2, 7.2,
                7.6, 4.8,
                2.0, 4.8,
                2.0, 6.2
        );
        return icon(folder);
    }

    /** Projects workspace list: stacked cards. */
    public static Node projects() {
        return icon(
                rounded(2.2, 3.2, 13.6, 4.2),
                rounded(2.2, 8.4, 13.6, 4.2),
                rounded(2.2, 13.6, 13.6, 2.8)
        );
    }

    /** Processing: three engineering sliders. */
    public static Node processing() {
        return icon(
                new Line(2.2, 4.8, 15.8, 4.8),
                new Circle(7.0, 4.8, 1.7),
                new Line(2.2, 9.0, 15.8, 9.0),
                new Circle(12.2, 9.0, 1.7),
                new Line(2.2, 13.2, 15.8, 13.2),
                new Circle(9.2, 13.2, 1.7)
        );
    }

    public static Node signals() {
        Polyline wave = new Polyline(
                1.5, 9.0,
                4.0, 9.0,
                6.0, 4.2,
                8.6, 14.0,
                11.2, 7.0,
                13.6, 11.4,
                16.5, 9.0
        );
        return icon(wave);
    }

    public static Node diagnostics() {
        Rectangle screen = rounded(1.8, 3.2, 14.4, 11.6);
        Polyline pulse = new Polyline(
                4.0, 9.0,
                6.2, 9.0,
                7.6, 5.6,
                9.8, 12.6,
                11.4, 9.0,
                14.2, 9.0
        );
        return icon(screen, pulse);
    }

    public static Node investigations() {
        Rectangle page = rounded(3.2, 2.2, 11.6, 13.8);
        Line a = new Line(5.6, 6.2, 12.2, 6.2);
        Line b = new Line(5.6, 9.0, 12.2, 9.0);
        Line c = new Line(5.6, 11.8, 10.2, 11.8);
        return icon(page, a, b, c);
    }

    public static Node tests() {
        Rectangle box = rounded(4.0, 2.4, 10.0, 13.4);
        Polyline check = new Polyline(
                6.4, 9.2,
                8.4, 11.4,
                12.0, 6.6
        );
        return icon(box, check);
    }

    public static Node daq() {
        Circle hub = new Circle(9.0, 9.0, 1.5);
        Circle inner = new Circle(9.0, 9.0, 4.2);
        Circle outer = new Circle(9.0, 9.0, 6.8);
        return icon(hub, inner, outer);
    }

    public static Node reports() {
        Rectangle page = rounded(3.4, 2.4, 11.2, 13.4);
        Line a = new Line(5.8, 6.2, 12.0, 6.2);
        Line b = new Line(5.8, 9.0, 12.0, 9.0);
        Line c = new Line(5.8, 11.8, 10.4, 11.8);
        return icon(page, a, b, c);
    }

    public static Node automation() {
        Polyline bolt = new Polyline(
                10.0, 2.2,
                5.4, 9.2,
                8.8, 9.2,
                8.0, 15.8,
                13.0, 8.4,
                9.6, 8.4,
                10.0, 2.2
        );
        return icon(bolt);
    }

    public static Node help() {
        Circle rim = new Circle(9.0, 9.0, 6.6);
        Polyline q = new Polyline(6.8, 7.2, 6.8, 6.6, 8.0, 5.4, 10.6, 5.4, 11.4, 6.8, 9.2, 8.6, 9.2, 10.2);
        Circle dot = new Circle(9.2, 12.8, 0.7);
        return icon(rim, q, dot);
    }

    public static Node computer() {
        Rectangle screen = rounded(2.2, 3.0, 13.6, 9.2);
        Line stem = new Line(9.0, 12.2, 9.0, 14.2);
        Line base = new Line(5.0, 14.6, 13.0, 14.6);
        return icon(screen, stem, base);
    }

    public static Node bell() {
        Polyline body = new Polyline(
                4.2, 11.4,
                4.2, 8.0,
                6.0, 5.2,
                12.0, 5.2,
                13.8, 8.0,
                13.8, 11.4,
                4.2, 11.4
        );
        Line clapper = new Line(7.4, 13.2, 10.6, 13.2);
        return icon(body, clapper);
    }

    public static Node settings() {
        Circle hub = new Circle(9.0, 9.0, 2.6);
        Circle ring = new Circle(9.0, 9.0, 5.0);
        Line[] ticks = new Line[6];
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(i * 60.0);
            ticks[i] = new Line(
                    9.0 + Math.cos(a) * 5.4,
                    9.0 + Math.sin(a) * 5.4,
                    9.0 + Math.cos(a) * 7.4,
                    9.0 + Math.sin(a) * 7.4
            );
        }
        Shape[] all = new Shape[8];
        all[0] = hub;
        all[1] = ring;
        System.arraycopy(ticks, 0, all, 2, 6);
        return icon(all);
    }

    public static Node collapse() {
        Polyline chevron = new Polyline(11.2, 4.2, 6.2, 9.0, 11.2, 13.8);
        return icon(chevron);
    }

    public static Node expand() {
        Polyline chevron = new Polyline(6.8, 4.2, 11.8, 9.0, 6.8, 13.8);
        return icon(chevron);
    }

    private static Rectangle rounded(double x, double y, double w, double h) {
        Rectangle r = new Rectangle(x, y, w, h);
        r.setArcWidth(2.4);
        r.setArcHeight(2.4);
        return r;
    }

    private static Node icon(Shape... shapes) {
        Pane box = new Pane();
        box.setMinSize(SIZE, SIZE);
        box.setPrefSize(SIZE, SIZE);
        box.setMaxSize(SIZE, SIZE);
        box.setMouseTransparent(true);
        box.getStyleClass().add("nav-icon-host");
        for (Shape shape : shapes) {
            shape.getStyleClass().add("nav-icon");
            shape.setFill(Color.TRANSPARENT);
            shape.setStroke(com.stellar.platform.desktop.ui.theme.StellarTheme.ICON_INK);
            shape.setStrokeWidth(1.55);
            shape.setStrokeLineCap(StrokeLineCap.ROUND);
            shape.setStrokeLineJoin(StrokeLineJoin.ROUND);
            box.getChildren().add(shape);
        }
        return box;
    }
}
