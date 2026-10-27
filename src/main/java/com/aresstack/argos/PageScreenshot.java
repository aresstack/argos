package com.aresstack.argos;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;

/**
 * Turns a browser PNG into an image a model can steer by: one image pixel is one CSS pixel (screens with a
 * device pixel ratio above 1 are scaled down), oversized images are scaled to fit, and control marks are
 * drawn into the image only, never into the page. The text {@link #legend()} explains how image pixels map to
 * the viewport coordinates that the mouse tools expect.
 */
public final class PageScreenshot {
    /** Longest side handed to a model; larger images are scaled down and the scale is reported. */
    static final int MAX_SIDE = 2000;

    public enum Mode { VIEWPORT, FULL, ELEMENT }

    /** A rectangle in CSS pixels. */
    public record Box(double x, double y, double width, double height) {
        double centerX() { return x + width / 2; }
        double centerY() { return y + height / 2; }
    }

    /** A control to mark: its number from the control list and its box in viewport CSS pixels. */
    public record Mark(int number, String label, Box box) { }

    /**
     * What the page looked like when the image was taken.
     *
     * @param origin top-left corner of the image in viewport CSS pixels (minus the scroll offset for full pages);
     *               only x and y are used
     * @param target what an element screenshot shows, or a note such as a cut-off page; null otherwise
     */
    public record Geometry(Mode mode, double viewportWidth, double viewportHeight, double devicePixelRatio,
                           double scrollX, double scrollY, Box origin, String target) { }

    private final byte[] png;
    private final int width;
    private final int height;
    private final double scale;
    private final Geometry geometry;
    private final List<Mark> marks;

    private PageScreenshot(byte[] png, int width, int height, double scale, Geometry geometry, List<Mark> marks) {
        this.png = png;
        this.width = width;
        this.height = height;
        this.scale = scale;
        this.geometry = geometry;
        this.marks = marks;
    }

    /**
     * @param raw     PNG from the browser, in device pixels
     * @param marks controls to draw; boxes in viewport CSS pixels, only those inside the image are drawn
     */
    public static PageScreenshot of(byte[] raw, Geometry geometry, List<Mark> marks) {
        BufferedImage source = decode(raw);
        double ratio = geometry.devicePixelRatio() > 0 ? geometry.devicePixelRatio() : 1;
        double cssWidth = source.getWidth() / ratio;
        double cssHeight = source.getHeight() / ratio;
        double fit = Math.min(1, MAX_SIDE / Math.max(cssWidth, cssHeight));
        int targetWidth = Math.max(1, (int) Math.round(cssWidth * fit));
        int targetHeight = Math.max(1, (int) Math.round(cssHeight * fit));
        // Image pixels per CSS pixel; 1 unless the image had to be shrunk to fit.
        double scale = targetWidth / cssWidth;
        Box covered = new Box(geometry.origin().x(), geometry.origin().y(), cssWidth, cssHeight);
        List<Mark> shown = marks.stream().filter(mark -> intersects(mark.box(), covered)).toList();
        boolean unchanged = targetWidth == source.getWidth() && targetHeight == source.getHeight() && shown.isEmpty();
        if (unchanged) return new PageScreenshot(raw, targetWidth, targetHeight, scale, geometry, shown);

        BufferedImage image = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
            for (Mark mark : shown) draw(graphics, mark, geometry.origin(), scale);
        } finally {
            graphics.dispose();
        }
        return new PageScreenshot(encode(image), targetWidth, targetHeight, scale, geometry, shown);
    }

    public byte[] png() { return png; }
    public int width() { return width; }
    public int height() { return height; }
    public double scale() { return scale; }
    public List<Mark> marks() { return marks; }

    /** Image pixel (x, y) as viewport CSS coordinates, the unit of the mouse tools. */
    public double[] toViewport(double imageX, double imageY) {
        return new double[] {geometry.origin().x() + imageX / scale, geometry.origin().y() + imageY / scale};
    }

    /** Plain lines for the model, sent next to the image. */
    public String legend() {
        Geometry g = geometry;
        StringBuilder out = new StringBuilder();
        out.append("Screenshot (").append(g.mode().name().toLowerCase(Locale.ROOT));
        if (g.target() != null) out.append(", ").append(g.target());
        out.append("): ").append(width).append('x').append(height).append(" image pixels.\n");
        out.append("Viewport: ").append(n(g.viewportWidth())).append('x').append(n(g.viewportHeight()))
                .append(" CSS pixels, device pixel ratio ").append(n(g.devicePixelRatio()))
                .append(", scrolled to x=").append(n(g.scrollX())).append(" y=").append(n(g.scrollY())).append(".\n");
        if (scale == 1 && g.origin().x() == 0 && g.origin().y() == 0) {
            out.append("Coordinates: image pixel = viewport CSS pixel; use them directly for mouse input.\n");
        } else {
            out.append("Coordinates: viewport x = ").append(n(g.origin().x())).append(" + image x");
            if (scale != 1) out.append(" * ").append(n(1 / scale));
            out.append(", viewport y = ").append(n(g.origin().y())).append(" + image y");
            if (scale != 1) out.append(" * ").append(n(1 / scale));
            out.append(". Points outside 0..").append(n(g.viewportWidth())).append(" x 0..")
                    .append(n(g.viewportHeight())).append(" must be scrolled into view first.\n");
        }
        if (!marks.isEmpty()) {
            out.append("Marked controls (number, then center and box in viewport CSS pixels):\n");
            for (Mark mark : marks) {
                Box box = mark.box();
                out.append('[').append(mark.number()).append("] ").append(mark.label())
                        .append(" center ").append(n(box.centerX())).append(',').append(n(box.centerY()))
                        .append(" box ").append(n(box.x())).append(',').append(n(box.y())).append(' ')
                        .append(n(box.width())).append('x').append(n(box.height())).append('\n');
            }
        }
        return out.toString().stripTrailing();
    }

    private static boolean intersects(Box box, Box area) {
        return box.width() > 0 && box.height() > 0
                && box.x() < area.x() + area.width() && box.x() + box.width() > area.x()
                && box.y() < area.y() + area.height() && box.y() + box.height() > area.y();
    }

    private static final Color MARK = new Color(230, 20, 120);

    private static void draw(Graphics2D graphics, Mark mark, Box origin, double scale) {
        int x = (int) Math.round((mark.box().x() - origin.x()) * scale);
        int y = (int) Math.round((mark.box().y() - origin.y()) * scale);
        int w = Math.max(1, (int) Math.round(mark.box().width() * scale));
        int h = Math.max(1, (int) Math.round(mark.box().height() * scale));
        graphics.setColor(MARK);
        graphics.setStroke(new BasicStroke(2f));
        graphics.drawRect(x, y, w, h);
        String label = String.valueOf(mark.number());
        try {
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            FontMetrics metrics = graphics.getFontMetrics();
            int labelWidth = metrics.stringWidth(label) + 6;
            int labelHeight = metrics.getAscent() + 2;
            int top = y - labelHeight >= 0 ? y - labelHeight : y;
            graphics.fillRect(x, top, labelWidth, labelHeight);
            graphics.setColor(Color.WHITE);
            graphics.drawString(label, x + 3, top + metrics.getAscent());
        } catch (Error | RuntimeException noFonts) {
            // A JVM without fonts still gets the boxes; the legend carries the numbers.
        }
    }

    private static BufferedImage decode(byte[] png) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null) throw new IllegalStateException("The browser returned no readable image.");
            return image;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static byte[] encode(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static String n(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.4f", value).replaceAll("\\.?0+$", "");
    }
}
