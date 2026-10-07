package com.xhlcli.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPassThroughSmallRgbImageWithoutReencoding() throws IOException {
        BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 400, 300);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        byte[] bytes = baos.toByteArray();

        ImageProcessor.ProcessedImage processed = ImageProcessor.process(bytes, "image/png", null);
        assertNotNull(processed.base64());
        assertEquals("image/png", processed.mimeType());
        assertFalse(processed.reencoded());
        assertEquals(400, processed.dimensions().originalWidth());
        assertEquals(300, processed.dimensions().originalHeight());
        assertEquals(400, processed.dimensions().displayWidth());
        assertEquals(300, processed.dimensions().displayHeight());
        assertNull(ImageProcessor.createMetadataText(processed));
    }

    @Test
    void shouldFlattenAlphaChannelForTransparentPng() throws IOException {
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        // 绘制带半透明和全透明区域
        g.setColor(new Color(0, 0, 255, 128));
        g.fillRect(50, 50, 100, 100);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        byte[] bytes = baos.toByteArray();

        ImageProcessor.ProcessedImage processed = ImageProcessor.process(bytes, "image/png", null);
        assertNotNull(processed.base64());
        assertEquals("image/png", processed.mimeType());
        assertTrue(processed.reencoded(), "有 alpha 通道应当被白底 flatten 重编码");
        assertEquals(200, processed.dimensions().originalWidth());
        assertEquals(200, processed.dimensions().originalHeight());

        String meta = ImageProcessor.createMetadataText(processed);
        assertNotNull(meta);
        assertTrue(meta.contains("re-encoded for API size limit"));
    }

    @Test
    void shouldResizeOversizedImageAndGenerateCoordinateMetadata() throws IOException {
        // 创建超过 2000 宽度的超大图
        BufferedImage img = new BufferedImage(4000, 2000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.GREEN);
        g.fillRect(0, 0, 4000, 2000);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        byte[] bytes = baos.toByteArray();

        Path path = tempDir.resolve("oversized.png");
        Files.write(path, bytes);

        ImageProcessor.ProcessedImage processed = ImageProcessor.fromPath(path, "image/png");
        assertNotNull(processed.base64());
        // 宽度应缩放到 2000，高度等比缩放到 1000
        assertEquals(4000, processed.dimensions().originalWidth());
        assertEquals(2000, processed.dimensions().originalHeight());
        assertEquals(2000, processed.dimensions().displayWidth());
        assertEquals(1000, processed.dimensions().displayHeight());

        String meta = ImageProcessor.createMetadataText(processed);
        assertNotNull(meta);
        assertTrue(meta.contains("original 4000x2000"));
        assertTrue(meta.contains("displayed at 2000x1000"));
        assertTrue(meta.contains("Multiply coordinates by 2.00"));
    }

    @Test
    void shouldRejectEmptyOrExcessiveSourceBytes() {
        assertThrows(IOException.class, () -> ImageProcessor.process(new byte[0], "image/png", null));
        assertThrows(IOException.class, () -> ImageProcessor.fromBase64("", "image/png"));
    }
}
