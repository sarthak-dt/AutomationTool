package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.ui.theme.StellarBrandColors;
import com.stellar.platform.desktop.ui.theme.StellarTheme;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

/**
 * Shared stroke-only icons for Signals workstation chrome.
 * No emoji, no paid icon packs — same visual language as {@link NavIcons}.
 */
public final class StellarIcons {

    public static final double SIZE_SM = 14;
    public static final double SIZE_MD = 16;
    public static final double SIZE_LG = 18;

    private static final Color STROKE = StellarTheme.ICON_MUTED;
    private static final Color STROKE_DARK = StellarTheme.ICON_DARK;
    private static final Color STROKE_BLUE = StellarTheme.INFO;
    private static final Color STROKE_ON_PRIMARY = StellarTheme.SURFACE;

    private StellarIcons() {
    }

    /** Brand mark used by the modern desktop shell. */
    public static Node brandMark() {
        Pane box = new Pane();
        box.setMinSize(32, 32);
        box.setPrefSize(32, 32);
        box.setMaxSize(32, 32);
        box.setMouseTransparent(true);

        Rectangle bg = new Rectangle(1.5, 1.5, 29, 29);
        bg.setArcWidth(8);
        bg.setArcHeight(8);
        bg.setFill(StellarBrandColors.MARK_BACKGROUND);
        bg.setStroke(StellarBrandColors.MARK_BORDER);
        bg.setStrokeWidth(1.0);

        Polyline sparkle = new Polyline(
                16, 6.0,
                17.8, 12.2,
                24.0, 14.0,
                17.8, 15.8,
                16, 22.0,
                14.2, 15.8,
                8.0, 14.0,
                14.2, 12.2,
                16, 6.0
        );
        sparkle.setFill(StellarBrandColors.MARK_SPARKLE);
        sparkle.setStroke(StellarBrandColors.MARK_SPARKLE_STROKE);
        sparkle.setStrokeWidth(0.8);
        sparkle.setStrokeLineJoin(StrokeLineJoin.ROUND);

        Circle accent = new Circle(23.2, 8.2, 1.35, StellarBrandColors.MARK_ACCENT);
        box.getChildren().addAll(bg, sparkle, accent);
        return box;
    }

    public static Node search() {
        return search(SIZE_SM, STROKE);
    }

    public static Node search(double size, Color stroke) {
        double s = size / 18.0;
        Circle lens = new Circle(7.2 * s, 7.2 * s, 4.6 * s);
        Line handle = new Line(10.6 * s, 10.6 * s, 15.2 * s, 15.2 * s);
        return icon(size, stroke, 1.45, lens, handle);
    }

    public static Node filter() {
        return icon(SIZE_SM, STROKE, 1.45,
                new Line(2.5, 4.5, 15.5, 4.5),
                new Circle(6.5, 4.5, 1.5),
                new Line(2.5, 9.0, 15.5, 9.0),
                new Circle(11.5, 9.0, 1.5),
                new Line(2.5, 13.5, 15.5, 13.5),
                new Circle(8.5, 13.5, 1.5));
    }

    public static Node starOutline() {
        Polyline star = new Polyline(
                9, 1.8, 10.8, 6.6, 16, 6.8, 12, 10.2, 13.4, 15.2,
                9, 12.6, 4.6, 15.2, 6, 10.2, 2, 6.8, 7.2, 6.6, 9, 1.8
        );
        return icon(SIZE_SM, STROKE, 1.35, star);
    }

    public static Node starFilled() {
        Polyline star = new Polyline(
                9, 1.8, 10.8, 6.6, 16, 6.6, 12, 9.8, 13.4, 15.2,
                9, 12.2, 4.6, 15.2, 6, 9.8, 2, 6.6, 7.2, 6.6, 9, 1.8
        );
        Pane box = new Pane();
        box.setMinSize(SIZE_SM, SIZE_SM);
        box.setPrefSize(SIZE_SM, SIZE_SM);
        box.setMaxSize(SIZE_SM, SIZE_SM);
        box.setMouseTransparent(true);
        box.getStyleClass().add("stellar-icon-host");
        double scale = SIZE_SM / 18.0;
        star.getStyleClass().add("stellar-icon");
        star.setFill(StellarBrandColors.FAVORITE_FILL);
        star.setStroke(StellarBrandColors.FAVORITE_STROKE);
        star.setStrokeWidth(1.2);
        star.setStrokeLineCap(StrokeLineCap.ROUND);
        star.setStrokeLineJoin(StrokeLineJoin.ROUND);
        star.setScaleX(scale);
        star.setScaleY(scale);
        star.setTranslateX((SIZE_SM - 18) / 2.0);
        star.setTranslateY((SIZE_SM - 18) / 2.0);
        box.getChildren().add(star);
        return box;
    }

    public static Node clock() {
        Circle rim = new Circle(9, 9, 6.2);
        Line hour = new Line(9, 9, 9, 5.2);
        Line minute = new Line(9, 9, 12.4, 10.8);
        return icon(SIZE_SM, STROKE, 1.45, rim, hour, minute);
    }

    public static Node file() {
        Polyline page = new Polyline(
                5, 2.5, 11.5, 2.5, 15, 6.0, 15, 15.5, 5, 15.5, 5, 2.5
        );
        Line fold = new Line(11.5, 2.5, 11.5, 6.0);
        Line fold2 = new Line(11.5, 6.0, 15, 6.0);
        Line a = new Line(7.2, 9.0, 12.8, 9.0);
        Line b = new Line(7.2, 11.5, 12.8, 11.5);
        return icon(SIZE_MD, STROKE_BLUE, 1.4, page, fold, fold2, a, b);
    }

    public static Node database() {
        // stacked disks / definition file
        return icon(SIZE_MD, STROKE_BLUE, 1.4,
                new Line(4, 5.2, 14, 5.2),
                new Line(4, 9.0, 14, 9.0),
                new Line(4, 12.8, 14, 12.8),
                rounded(4, 3.2, 10, 3.6),
                rounded(4, 11.0, 10, 3.6));
    }

    public static Node channel() {
        // network / bus nodes
        Circle a = new Circle(4.5, 9, 1.7);
        Circle b = new Circle(13.5, 5.5, 1.7);
        Circle c = new Circle(13.5, 12.5, 1.7);
        Line ab = new Line(6.0, 8.4, 12.0, 6.0);
        Line ac = new Line(6.0, 9.6, 12.0, 12.0);
        return icon(SIZE_MD, STROKE_BLUE, 1.4, a, b, c, ab, ac);
    }

    public static Node sliders() {
        return icon(SIZE_SM, STROKE_ON_PRIMARY, 1.45,
                new Line(2.5, 4.5, 15.5, 4.5),
                new Circle(7.0, 4.5, 1.6),
                new Line(2.5, 9.0, 15.5, 9.0),
                new Circle(11.5, 9.0, 1.6),
                new Line(2.5, 13.5, 15.5, 13.5),
                new Circle(8.5, 13.5, 1.6));
    }

    public static Node slidersSecondary() {
        return icon(SIZE_SM, STROKE_DARK, 1.45,
                new Line(2.5, 4.5, 15.5, 4.5),
                new Circle(7.0, 4.5, 1.6),
                new Line(2.5, 9.0, 15.5, 9.0),
                new Circle(11.5, 9.0, 1.6),
                new Line(2.5, 13.5, 15.5, 13.5),
                new Circle(8.5, 13.5, 1.6));
    }

    public static Node activity() {
        Polyline pulse = new Polyline(
                2, 9, 5, 9, 7, 4.5, 10, 13.5, 12.5, 9, 16, 9
        );
        return icon(SIZE_SM, STROKE_DARK, 1.45, pulse);
    }

    /** Compact lightning mark for the Auto Decode presentation mode. */
    public static Node autoDecode() {
        Polyline bolt = new Polyline(
                10.2, 1.8, 4.9, 9.0, 8.7, 9.0, 7.7, 16.2, 13.2, 8.2, 9.5, 8.2, 10.2, 1.8
        );
        return icon(SIZE_MD, STROKE_BLUE, 1.55, bolt);
    }

    /** Engineer-control sliders for the Custom Decode presentation mode. */
    public static Node customDecode() {
        return icon(SIZE_MD, STROKE_BLUE, 1.45,
                new Line(3.0, 4.5, 15.0, 4.5),
                new Circle(7.0, 4.5, 1.55),
                new Line(3.0, 9.0, 15.0, 9.0),
                new Circle(11.7, 9.0, 1.55),
                new Line(3.0, 13.5, 15.0, 13.5),
                new Circle(8.8, 13.5, 1.55));
    }

    public static Node selectedSignals() {
        return icon(SIZE_MD, STROKE_BLUE, 1.4,
                rounded(3.2, 4.0, 11.6, 10.0),
                new Line(6.0, 9.0, 8.2, 11.0),
                new Line(8.2, 11.0, 12.2, 6.5));
    }

    public static Node alignment() {
        return icon(SIZE_MD, STROKE_BLUE, 1.4,
                new Line(3, 9, 15, 9),
                new Line(6, 5.5, 6, 12.5),
                new Line(12, 5.5, 12, 12.5),
                new Circle(6, 9, 1.4),
                new Circle(12, 9, 1.4));
    }

    public static Node rows() {
        return icon(SIZE_MD, STROKE_BLUE, 1.4,
                new Line(3.5, 5.0, 14.5, 5.0),
                new Line(3.5, 9.0, 14.5, 9.0),
                new Line(3.5, 13.0, 14.5, 13.0),
                new Circle(5.2, 5.0, 1.2),
                new Circle(5.2, 9.0, 1.2),
                new Circle(5.2, 13.0, 1.2));
    }

    public static Node timeStart() {
        return icon(SIZE_MD, STROKE_BLUE, 1.4,
                new Circle(9, 9, 6.0),
                new Line(9, 9, 9, 5.5),
                new Line(9, 9, 12.2, 9));
    }

    public static Node timeEnd() {
        return icon(SIZE_MD, STROKE_BLUE, 1.4,
                new Circle(9, 9, 6.0),
                new Line(9, 5.5, 9, 9),
                new Line(9, 9, 12.5, 11.5));
    }

    public static Node locate() {
        return icon(SIZE_SM, STROKE, 1.4,
                new Circle(9, 9, 4.5),
                new Circle(9, 9, 1.3),
                new Line(9, 2.5, 9, 4.5),
                new Line(9, 13.5, 9, 15.5),
                new Line(2.5, 9, 4.5, 9),
                new Line(13.5, 9, 15.5, 9));
    }

    public static Node copy() {
        return icon(SIZE_SM, STROKE, 1.35,
                rounded(3.5, 5.5, 8.0, 9.0),
                rounded(6.5, 3.0, 8.0, 9.0));
    }

    public static Node first() {
        return icon(SIZE_SM, STROKE, 1.5,
                new Line(4.5, 4.0, 4.5, 14.0),
                new Polyline(13.5, 4.0, 7.5, 9.0, 13.5, 14.0));
    }

    public static Node previous() {
        return icon(SIZE_SM, STROKE, 1.5,
                new Polyline(11.5, 4.0, 6.0, 9.0, 11.5, 14.0));
    }

    public static Node next() {
        return icon(SIZE_SM, STROKE, 1.5,
                new Polyline(6.5, 4.0, 12.0, 9.0, 6.5, 14.0));
    }

    public static Node last() {
        return icon(SIZE_SM, STROKE, 1.5,
                new Line(13.5, 4.0, 13.5, 14.0),
                new Polyline(4.5, 4.0, 10.5, 9.0, 4.5, 14.0));
    }

    /** Download arrow for CSV export. */
    public static Node download() {
        return icon(SIZE_SM, STROKE, 1.5,
                new Line(9.0, 3.0, 9.0, 10.4),
                new Polyline(5.4, 7.2, 9.0, 10.8, 12.6, 7.2),
                new Polyline(4.0, 13.2, 4.0, 15.0, 14.0, 15.0, 14.0, 13.2));
    }

    /** Plus for light surfaces. Primary buttons restyle it through {@code .primary-button .stellar-icon}. */
    public static Node plus() {
        return icon(SIZE_SM, STROKE_DARK, 1.6,
                new Line(9, 4.0, 9, 14.0),
                new Line(4.0, 9, 14.0, 9));
    }

    /** Close / dismiss mark. */
    public static Node close() {
        return icon(SIZE_SM, STROKE_DARK, 1.55,
                new Line(4.6, 4.6, 13.4, 13.4),
                new Line(13.4, 4.6, 4.6, 13.4));
    }

    /** Information glyph for field help and advisory notes. */
    public static Node info() {
        return icon(SIZE_SM, STROKE, 1.4,
                new Circle(9.0, 9.0, 6.2),
                new Line(9.0, 8.2, 9.0, 12.4),
                new Circle(9.0, 5.8, 0.55));
    }

    /** Warning triangle for low-confidence mapping. */
    public static Node warning() {
        return icon(SIZE_SM, STROKE_DARK, 1.4,
                new Polyline(9.0, 3.2, 15.4, 14.4, 2.6, 14.4, 9.0, 3.2),
                new Line(9.0, 7.2, 9.0, 10.6),
                new Circle(9.0, 12.4, 0.45));
    }

    /** Neutral plus for preview zoom. */
    public static Node plusMuted() {
        return icon(SIZE_SM, STROKE_DARK, 1.5,
                new Line(9, 4.4, 9, 13.6),
                new Line(4.4, 9, 13.6, 9));
    }

    /** Neutral minus for preview zoom. */
    public static Node minusMuted() {
        return icon(SIZE_SM, STROKE_DARK, 1.5,
                new Line(4.4, 9, 13.6, 9));
    }

    /** Check mark for Confirm actions. */
    public static Node check() {
        return icon(SIZE_SM, STROKE_ON_PRIMARY, 1.6,
                new Polyline(3.5, 9.5, 7.2, 13.0, 14.5, 5.0));
    }

    public static Node checkSecondary() {
        return icon(SIZE_SM, STROKE_DARK, 1.6,
                new Polyline(3.5, 9.5, 7.2, 13.0, 14.5, 5.0));
    }

    /** Swap / choose-another indicator. */
    public static Node swap() {
        return icon(SIZE_SM, STROKE_DARK, 1.45,
                new Polyline(4.0, 6.5, 8.0, 3.5, 8.0, 5.5, 14.0, 5.5),
                new Polyline(14.0, 11.5, 10.0, 14.5, 10.0, 12.5, 4.0, 12.5));
    }

    /** Inspect / compare indicator. */
    public static Node compare() {
        return icon(SIZE_SM, STROKE_DARK, 1.4,
                rounded(3.0, 4.0, 5.5, 10.0),
                rounded(9.5, 4.0, 5.5, 10.0),
                new Line(5.75, 7.0, 5.75, 11.0),
                new Line(12.25, 7.0, 12.25, 11.0));
    }

    public static Node collapseLeft() {
        return icon(SIZE_SM, STROKE, 1.5,
                new Polyline(11.0, 4.0, 6.5, 9.0, 11.0, 14.0));
    }

    public static Node expandRight() {
        return icon(SIZE_SM, STROKE, 1.5,
                new Polyline(7.0, 4.0, 11.5, 9.0, 7.0, 14.0));
    }

    /** Map marker for the coordinate roles. */
    public static Node mapPin() {
        Polyline body = new Polyline(
                9.0, 16.0, 4.2, 9.6, 4.2, 7.2, 5.6, 4.4, 9.0, 3.0,
                12.4, 4.4, 13.8, 7.2, 13.8, 9.6, 9.0, 16.0
        );
        return icon(SIZE_MD, STROKE_DARK, 1.4, body, new Circle(9.0, 7.6, 2.1));
    }

    /** Peaks for the Altitude role. */
    public static Node mountain() {
        return icon(SIZE_MD, STROKE_DARK, 1.4,
                new Polyline(2.4, 14.0, 7.0, 5.6, 10.4, 11.0, 12.0, 8.6, 15.6, 14.0, 2.4, 14.0),
                new Line(5.6, 8.2, 8.4, 8.2));
    }

    /** Dial and needle for the GPS Speed role. */
    public static Node speedometer() {
        return icon(SIZE_MD, STROKE_DARK, 1.4,
                new Polyline(3.0, 12.6, 3.0, 9.4, 4.8, 5.6, 9.0, 4.0, 13.2, 5.6, 15.0, 9.4, 15.0, 12.6),
                new Line(9.0, 11.4, 12.0, 7.6),
                new Circle(9.0, 12.0, 1.3));
    }

    /** Compass rose for the Heading / Course role. */
    public static Node compass() {
        return icon(SIZE_MD, STROKE_DARK, 1.4,
                new Circle(9.0, 9.0, 6.2),
                new Polyline(11.6, 6.4, 7.4, 8.0, 6.4, 11.6, 10.6, 10.0, 11.6, 6.4));
    }

    /** Sparkle for actions that fill values in automatically. */
    public static Node sparkle() {
        return icon(SIZE_SM, STROKE_DARK, 1.35,
                new Polyline(8.0, 2.6, 9.5, 6.7, 13.6, 8.2, 9.5, 9.7, 8.0, 13.8,
                        6.5, 9.7, 2.4, 8.2, 6.5, 6.7, 8.0, 2.6),
                new Polyline(14.0, 12.4, 14.7, 14.1, 16.4, 14.8, 14.7, 15.5, 14.0, 17.2,
                        13.3, 15.5, 11.6, 14.8, 13.3, 14.1, 14.0, 12.4));
    }

    public static Node refresh() {
        Arc arc = new Arc(9.0, 9.0, 5.2, 5.2, 45, 270);
        arc.setType(ArcType.OPEN);
        Polyline tip = new Polyline(12.6, 3.4, 14.8, 6.0, 11.2, 6.6);
        return icon(SIZE_SM, STROKE_DARK, 1.45, arc, tip);
    }

    public static Node settings() {
        // Gear: hub + rim (stroke-only; matches Stellar icon language).
        return icon(SIZE_MD, STROKE, 1.45,
                new Circle(9.0, 9.0, 2.6),
                new Circle(9.0, 9.0, 5.8),
                new Line(9.0, 1.8, 9.0, 3.4),
                new Line(9.0, 14.6, 9.0, 16.2),
                new Line(1.8, 9.0, 3.4, 9.0),
                new Line(14.6, 9.0, 16.2, 9.0),
                new Line(3.8, 3.8, 4.9, 4.9),
                new Line(13.1, 13.1, 14.2, 14.2),
                new Line(14.2, 3.8, 13.1, 4.9),
                new Line(4.9, 13.1, 3.8, 14.2));
    }

    public static Node tinted(Node icon, Color stroke) {
        if (!(icon instanceof Pane pane)) {
            return icon;
        }
        for (Node child : pane.getChildren()) {
            if (child instanceof Shape shape) {
                shape.setStroke(stroke);
            }
        }
        return icon;
    }

    private static Rectangle rounded(double x, double y, double w, double h) {
        Rectangle r = new Rectangle(x, y, w, h);
        r.setArcWidth(2.2);
        r.setArcHeight(2.2);
        return r;
    }

    private static Node icon(double size, Color stroke, double width, Shape... shapes) {
        Pane box = new Pane();
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setMouseTransparent(true);
        box.getStyleClass().add("stellar-icon-host");
        double scale = size / 18.0;
        for (Shape shape : shapes) {
            shape.getStyleClass().add("stellar-icon");
            shape.setFill(Color.TRANSPARENT);
            shape.setStroke(stroke);
            shape.setStrokeWidth(width);
            shape.setStrokeLineCap(StrokeLineCap.ROUND);
            shape.setStrokeLineJoin(StrokeLineJoin.ROUND);
            if (scale != 1.0) {
                shape.setScaleX(scale);
                shape.setScaleY(scale);
                shape.setTranslateX((size - 18) / 2.0);
                shape.setTranslateY((size - 18) / 2.0);
            }
            box.getChildren().add(shape);
        }
        return box;
    }
}
