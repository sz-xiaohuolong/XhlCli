package com.xhlcli.cli.terminal;

import com.xhlcli.config.SecretRedactor;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.impl.history.DefaultHistory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 具备敏感过滤、防超长膨胀与容错降级的安全持久化输入历史。
 */
public final class SafeHistory extends DefaultHistory {

    public static final int MAX_INPUT_CHARS = 4000;
    private static final Pattern SENSITIVE_TOKEN_PATTERN =
            Pattern.compile("(?i)(sk-[a-zA-Z0-9]{20,}|ghp_[a-zA-Z0-9]{36}|bearer\\s+[a-zA-Z0-9_\\-\\.]+|(api[_-]?key|password|secret)\\s*[:=]\\s*['\"]?[a-zA-Z0-9_\\-]{16,})");
    private static final Pattern BASE64_PATTERN =
            Pattern.compile("data:image/[a-zA-Z]+;base64,[a-zA-Z0-9+/=]{100,}");

    private final Path historyPath;
    private boolean memoryOnly;

    public SafeHistory(Path historyPath) {
        super();
        this.historyPath = historyPath;
        initializeFileStorage();
    }

    private void initializeFileStorage() {
        if (historyPath == null) {
            this.memoryOnly = true;
            return;
        }
        try {
            Path parent = historyPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            if (Files.exists(historyPath) && Files.isDirectory(historyPath)) {
                this.memoryOnly = true;
                return;
            }
            if (!Files.exists(historyPath)) {
                Files.createFile(historyPath);
            }
            read(historyPath, false);
        } catch (Exception e) {
            // 文件不可写或损坏时优雅降级为内存历史
            this.memoryOnly = true;
        }
    }

    @Override
    public void add(Instant time, String line) {
        if (shouldFilter(line)) {
            return;
        }
        super.add(time, line);
        if (!memoryOnly && historyPath != null) {
            try {
                append(historyPath, false);
            } catch (Exception ignored) {
                // 写入磁盘异常时不中断主流程
            }
        }
    }

    /**
     * 判断当前输入行是否应当被安全过滤。
     */
    public boolean shouldFilter(String line) {
        if (line == null || line.isBlank()) {
            return true;
        }

        String trimmed = line.trim();

        // 1. 过滤连续重复行
        if (size() > 0 && trimmed.equals(get(last()))) {
            return true;
        }

        // 2. 过滤超长文本 (> 4000 字符)
        if (trimmed.length() > MAX_INPUT_CHARS) {
            return true;
        }

        // 3. 过滤 Base64 图片数据
        if (BASE64_PATTERN.matcher(trimmed).find()) {
            return true;
        }

        // 4. 过滤疑似 API Key / Token 敏感凭据
        if (SENSITIVE_TOKEN_PATTERN.matcher(trimmed).find()) {
            return true;
        }

        return false;
    }

    /**
     * 获取最近 N 条安全历史。
     */
    public List<String> listRecent(int limit) {
        int n = Math.max(1, limit);
        List<String> list = new ArrayList<>();
        var it = iterator(Math.max(0, size() - n));
        while (it.hasNext()) {
            list.add(it.next().line());
        }
        return list;
    }

    /**
     * 清空本地历史记录（含内存与磁盘）。
     */
    public synchronized void clearAll() {
        try {
            purge();
            if (historyPath != null && Files.exists(historyPath)) {
                Files.writeString(historyPath, "");
            }
        } catch (Exception ignored) {}
    }

    public boolean isMemoryOnly() {
        return memoryOnly;
    }

    public Path getHistoryPath() {
        return historyPath;
    }
}
