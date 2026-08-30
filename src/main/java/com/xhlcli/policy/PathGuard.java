package com.xhlcli.policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * 路径安全围栏：所有涉及文件系统路径的工具调用在执行前必须经过 PathGuard 校验。
 * 
 * 校验规则：
 * 1. 阻断以绝对路径逃逸出项目根目录；
 * 2. 阻断以 .. 相对路径穿越逃逸；
 * 3. 阻断符号链接指向外部路径逃逸（包含不存在文件但父级路径是外部软链的场景）。
 */
public class PathGuard {
    private final Path rootPath;

    public PathGuard(Path root) {
        Objects.requireNonNull(root, "项目根路径不能为 null");
        Path candidate = root.toAbsolutePath().normalize();
        Path real = candidate;
        try {
            if (Files.exists(candidate)) {
                real = candidate.toRealPath();
            }
        } catch (IOException ignored) {
        }
        this.rootPath = real;
    }

    public PathGuard(String root) {
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException("项目根路径不能为空");
        }
        Path candidate = Paths.get(root).toAbsolutePath().normalize();
        Path real = candidate;
        try {
            if (Files.exists(candidate)) {
                real = candidate.toRealPath();
            }
        } catch (IOException ignored) {
        }
        this.rootPath = real;
    }

    public Path getRootPath() {
        return rootPath;
    }

    /**
     * 校验路径是否在项目根之内，返回安全的绝对路径。若越界则抛出 PolicyException。
     */
    public Path resolveSafe(String input) {
        if (input == null || input.isBlank()) {
            throw new PolicyException("路径不能为空");
        }

        Path raw = Paths.get(input);
        Path resolved = raw.isAbsolute()
                ? raw.normalize()
                : rootPath.resolve(raw).normalize();

        Path realResolved = resolveRealPath(resolved);

        if (!realResolved.startsWith(rootPath)) {
            throw new PolicyException("路径越界: " + input + " 不在项目根 " + rootPath + " 之内");
        }
        return realResolved;
    }

    /**
     * 校验路径是否安全。
     * @return null 表示安全；非 null 表示拒绝原因
     */
    public String checkSafe(String input) {
        try {
            resolveSafe(input);
            return null;
        } catch (PolicyException e) {
            return e.getMessage();
        } catch (Exception e) {
            return "非法路径: " + e.getMessage();
        }
    }

    /**
     * 向上找到最近的存在祖先，调用 toRealPath 解析其中的符号链接，再把剩余段接回。
     */
    private Path resolveRealPath(Path target) {
        Path existing = target;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return target.toAbsolutePath().normalize();
        }
        try {
            Path realExisting = existing.toRealPath();
            Path remainder = existing.relativize(target);
            return realExisting.resolve(remainder).normalize();
        } catch (IOException e) {
            return target.toAbsolutePath().normalize();
        }
    }
}
