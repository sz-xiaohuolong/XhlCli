package com.xhlcli.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Skill 相对路径参考资料读取器与安全沙箱。
 * 严格限制仅能访问 Skill 目录内的文件，坚决拦截路径穿越 (../) 攻击。
 */
public final class SkillReferenceResolver {
    public static final int MAX_REFERENCE_BYTES = 512 * 1024; // 512 KB

    private SkillReferenceResolver() {}

    /**
     * 安全读取 Skill 目录下的相对路径文件内容。
     *
     * @param skill        目标 Skill
     * @param relativePath 相对路径（如 "references/guide.md"）
     * @return 文件文本内容
     * @throws SecurityException 如果路径逃逸出 Skill 根目录
     * @throws IOException       如果文件不存在或读取失败
     */
    public static String readReference(SkillDefinition skill, String relativePath) throws IOException {
        Objects.requireNonNull(skill, "skill");
        Objects.requireNonNull(relativePath, "relativePath");

        Path skillDir = skill.directoryPath();
        if (skillDir == null) {
            throw new IllegalStateException("Skill '" + skill.name() + "' does not have a physical directory.");
        }

        Path normalizedBase = skillDir.toAbsolutePath().normalize();
        Path targetPath = normalizedBase.resolve(relativePath).normalize();

        // 路径逃逸安全拦截
        if (!targetPath.startsWith(normalizedBase)) {
            throw new SecurityException("Security violation: path escapes skill directory: " + relativePath);
        }

        if (!Files.exists(targetPath)) {
            throw new IOException("Reference file not found: " + relativePath);
        }
        if (!Files.isRegularFile(targetPath)) {
            throw new IOException("Reference path is not a regular file: " + relativePath);
        }

        long size = Files.size(targetPath);
        if (size > MAX_REFERENCE_BYTES) {
            throw new IOException("Reference file exceeds maximum allowed size (" + size + " > " + MAX_REFERENCE_BYTES + " bytes): " + relativePath);
        }

        return Files.readString(targetPath, StandardCharsets.UTF_8);
    }

    /**
     * 列出 Skill 目录某个子目录（如 "references" 或 "scripts"）下的所有可用相对路径。
     */
    public static List<String> listSubdirectoryFiles(SkillDefinition skill, String subDirName) {
        Objects.requireNonNull(skill, "skill");
        if (skill.directoryPath() == null || subDirName == null) {
            return List.of();
        }

        Path normalizedBase = skill.directoryPath().toAbsolutePath().normalize();
        Path targetSubDir = normalizedBase.resolve(subDirName).normalize();

        if (!targetSubDir.startsWith(normalizedBase) || !Files.isDirectory(targetSubDir)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.walk(targetSubDir, 3)) {
            List<String> results = new ArrayList<>();
            stream.filter(Files::isRegularFile).forEach(p -> {
                Path rel = normalizedBase.relativize(p);
                results.add(rel.toString().replace('\\', '/'));
            });
            Collections.sort(results);
            return results;
        } catch (IOException e) {
            return List.of();
        }
    }
}
