package com.xhlcli.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipboardImageTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldDescribeExistingImageFile() throws IOException {
        BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, 320, 240);
        g.dispose();

        Path path = tempDir.resolve("sample.png");
        ImageIO.write(img, "png", path.toFile());

        String description = ClipboardImage.describe(path);
        assertTrue(description.contains("sample.png"));
        assertTrue(description.contains("320x240"));

        assertEquals("", ClipboardImage.describe(null));
    }

    @Test
    void shouldSafelyHandleGrabExecution() {
        ClipboardImage.GrabResult result = ClipboardImage.grab(tempDir);
        assertNotNull(result);
        if (result.ok()) {
            assertNotNull(result.path());
            assertTrue(Files.exists(result.path()));
        } else {
            assertNotNull(result.error());
            assertFalse(result.error().isBlank());
        }
    }
}
