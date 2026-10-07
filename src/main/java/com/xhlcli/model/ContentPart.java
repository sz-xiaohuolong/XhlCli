package com.xhlcli.model;

import java.util.Objects;

/**
 * 多模态消息内容分片。
 * 支持文本块 (text)、Base64 编码图片 (image_base64) 与 URL 引用图片 (image_url)。
 */
public record ContentPart(
        String type,
        String text,
        String imageBase64,
        String imageUrl,
        String mimeType
) {
    public ContentPart {
        Objects.requireNonNull(type, "type");
        if (isText() && (text == null)) {
            throw new IllegalArgumentException("文本分片的 text 不能为空");
        }
        if ("image_base64".equals(type) && (imageBase64 == null || imageBase64.isBlank())) {
            throw new IllegalArgumentException("Base64 图片分片的 imageBase64 不能为空");
        }
        if ("image_url".equals(type) && (imageUrl == null || imageUrl.isBlank())) {
            throw new IllegalArgumentException("URL 图片分片的 imageUrl 不能为空");
        }
    }

    public static ContentPart text(String text) {
        return new ContentPart("text", text, null, null, null);
    }

    public static ContentPart imageBase64(String imageBase64, String mimeType) {
        String normalizedMime = (mimeType == null || mimeType.isBlank()) ? "image/png" : mimeType;
        return new ContentPart("image_base64", null, imageBase64, null, normalizedMime);
    }

    public static ContentPart imageUrl(String imageUrl) {
        return new ContentPart("image_url", null, null, imageUrl, null);
    }

    public boolean isText() {
        return "text".equals(type);
    }

    public boolean isImage() {
        return "image_base64".equals(type) || "image_url".equals(type);
    }
}
