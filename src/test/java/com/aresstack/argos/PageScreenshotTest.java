package com.aresstack.argos;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PageScreenshotTest {
    private static final PageScreenshot.Box TOP_LEFT = new PageScreenshot.Box(0, 0, 0, 0);

    @Test
    void keepsBrowserImageWhenOneImagePixelIsOneCssPixel() throws Exception {
        byte[] raw = png(400, 300);
        PageScreenshot shot = PageScreenshot.of(raw, viewport(400, 300, 1, TOP_LEFT), List.of());
        assertSame(raw, shot.png());
        assertEquals(1.0, shot.scale());
        assertTrue(shot.legend().contains("image pixel = viewport CSS pixel"), shot.legend());
    }

    @Test
    void scalesHighDensityScreensDownToCssPixels() throws Exception {
        PageScreenshot shot = PageScreenshot.of(png(800, 600), viewport(400, 300, 2, TOP_LEFT), List.of());
        BufferedImage image = read(shot.png());
        assertEquals(400, image.getWidth());
        assertEquals(300, image.getHeight());
        assertEquals(1.0, shot.scale());
        assertArrayEquals(new double[] {120, 80}, shot.toViewport(120, 80));
        assertTrue(shot.legend().contains("device pixel ratio 2"), shot.legend());
    }

    @Test
    void fitsLongPagesAndMapsImagePixelsBackToTheViewport() throws Exception {
        // A full page 1000 x 4000 CSS pixels, scrolled down by 500.
        PageScreenshot.Geometry full = new PageScreenshot.Geometry(PageScreenshot.Mode.FULL, 1000, 700, 1,
                0, 500, new PageScreenshot.Box(0, -500, 1000, 4000), null);
        PageScreenshot shot = PageScreenshot.of(png(1000, 4000), full, List.of());
        assertEquals(PageScreenshot.MAX_SIDE, shot.height());
        assertEquals(500, shot.width());
        assertEquals(0.5, shot.scale());
        // Image (100, 300) = page (200, 600) = viewport (200, 100).
        assertArrayEquals(new double[] {200, 100}, shot.toViewport(100, 300));
        assertTrue(shot.legend().contains("viewport y = -500 + image y * 2"), shot.legend());
    }

    @Test
    void drawsMarksIntoTheImageAndListsThemWithViewportCenters() throws Exception {
        PageScreenshot.Mark inside = new PageScreenshot.Mark(3, "button \"Suchen\"", new PageScreenshot.Box(40, 50, 100, 30));
        PageScreenshot.Mark outside = new PageScreenshot.Mark(4, "link \"Weiter\"", new PageScreenshot.Box(40, 900, 100, 30));
        PageScreenshot shot = PageScreenshot.of(png(400, 300), viewport(400, 300, 1, TOP_LEFT), List.of(inside, outside));
        BufferedImage image = read(shot.png());
        // Marks: the box outline is drawn in the mark colour; the page itself stays white.
        assertNotEquals(Color.WHITE.getRGB(), image.getRGB(40, 65));
        assertEquals(Color.WHITE.getRGB(), image.getRGB(90, 65));
        assertEquals(List.of(inside), shot.marks());
        assertTrue(shot.legend().contains("[3] button \"Suchen\" center 90,65 box 40,50 100x30"), shot.legend());
        assertFalse(shot.legend().contains("Weiter"));
    }

    @Test
    void elementImagesStartAtTheElementsViewportPosition() throws Exception {
        PageScreenshot.Box element = new PageScreenshot.Box(200, 1200, 80, 20);
        PageScreenshot.Geometry geometry = new PageScreenshot.Geometry(PageScreenshot.Mode.ELEMENT, 800, 600, 1,
                0, 0, element, "button \"Senden\"");
        PageScreenshot shot = PageScreenshot.of(png(80, 20), geometry, List.of());
        assertArrayEquals(new double[] {240, 1210}, shot.toViewport(40, 10));
        assertTrue(shot.legend().contains("Screenshot (element, button \"Senden\")"), shot.legend());
        assertTrue(shot.legend().contains("must be scrolled into view first"), shot.legend());
    }

    @Test
    void parsesModesLeniently() {
        assertEquals(PageScreenshot.Mode.VIEWPORT, ArgosTools.screenshotMode(null));
        assertEquals(PageScreenshot.Mode.FULL, ArgosTools.screenshotMode(" Full "));
        assertEquals(PageScreenshot.Mode.ELEMENT, ArgosTools.screenshotMode("element"));
        assertThrows(IllegalArgumentException.class, () -> ArgosTools.screenshotMode("thumbnail"));
    }

    private static PageScreenshot.Geometry viewport(double width, double height, double ratio, PageScreenshot.Box origin) {
        return new PageScreenshot.Geometry(PageScreenshot.Mode.VIEWPORT, width, height, ratio, 0, 0, origin, null);
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage read(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }
}
