package com.xhlcli.image;

import com.xhlcli.model.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageReferenceParserTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldFindAndStripImageReferences() {
        String input1 = "请排查这个 bug @image:./screenshot.png。这是报错日志";
        List<ImageReferenceParser.ImageRef> refs1 = ImageReferenceParser.findRefs(input1);
        assertEquals(1, refs1.size());
        assertEquals("./screenshot.png", refs1.get(0).value());
        assertEquals("请排查这个 bug 。这是报错日志", ImageReferenceParser.stripRefs(input1));

        String input2 = "对比 @image:<path with space/fig 1.jpg> 和 @clipboard";
        List<ImageReferenceParser.ImageRef> refs2 = ImageReferenceParser.findRefs(input2);
        assertEquals(2, refs2.size());
        assertEquals("path with space/fig 1.jpg", refs2.get(0).value());
        assertEquals("@clipboard", refs2.get(1).value());
        assertEquals("对比  和", ImageReferenceParser.stripRefs(input2));
    }

    @Test
    void shouldAssembleMultimodalMessageForExistingImage() throws IOException {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 100, 100);
        g.dispose();

        Path imgPath = tempDir.resolve("test.png");
        ImageIO.write(img, "png", imgPath.toFile());

        String input = "请分析这张架构设计图 @image:" + imgPath.toAbsolutePath();
        ChatMessage msg = ImageReferenceParser.userMessage(input, tempDir);

        assertTrue(msg.hasContentParts());
        assertTrue(msg.hasImages());
        assertEquals(2, msg.contentParts().size());
        assertTrue(msg.contentParts().get(0).isText());
        assertTrue(msg.contentParts().get(1).isImage());
        assertTrue(msg.content().contains("请分析这张架构设计图"));
        assertTrue(msg.content().contains("图片已作为图片附件附加"));
    }

    @Test
    void shouldGracefullyReportMissingImage() {
        String input = "请看 @image:non_existent_image.png 为什么失败";
        ChatMessage msg = ImageReferenceParser.userMessage(input, tempDir);

        assertFalse(msg.hasImages(), "不存在的文件不应生成图片分片");
        assertTrue(msg.content().contains("[图片引用无效: non_existent_image.png"));
        assertTrue(msg.content().contains("文件不存在"));
    }

    @Test
    void shouldReturnPlainMessageWhenNoImageReferences() {
        String plain = "直接告诉我如何实现冒泡排序";
        assertFalse(ImageReferenceParser.hasImageReferences(plain));
        ChatMessage msg = ImageReferenceParser.userMessage(plain, tempDir);
        assertFalse(msg.hasContentParts());
        assertEquals(plain, msg.content());
    }
}
