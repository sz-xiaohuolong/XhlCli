package com.xhlcli.rag;

import java.util.logging.Level;
import java.util.logging.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 代码索引管理器：负责将代码库分块、向量化并持久化到 VectorStore，支持基于 SHA-256 的确定性增量索引
 */
public class CodeIndex {
    private static final Logger log = Logger.getLogger(CodeIndex.class.getName());
    private final EmbeddingClient embeddingClient;
    private final CodeChunker chunker;
    private final CodeAnalyzer analyzer;
    private final ProgressListener progressListener;

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String message);

        static ProgressListener noop() {
            return message -> {};
        }
    }

    public CodeIndex() {
        this(new EmbeddingClient(), ProgressListener.noop());
    }

    public CodeIndex(EmbeddingClient embeddingClient) {
        this(embeddingClient, ProgressListener.noop());
    }

    public CodeIndex(ProgressListener progressListener) {
        this(new EmbeddingClient(), progressListener);
    }

    public CodeIndex(EmbeddingClient embeddingClient, ProgressListener progressListener) {
        this.embeddingClient = embeddingClient != null ? embeddingClient : new EmbeddingClient();
        this.chunker = new CodeChunker();
        this.analyzer = new CodeAnalyzer();
        this.progressListener = progressListener == null ? ProgressListener.noop() : progressListener;
    }

    /**
     * 增量索引指定路径的代码库
     */
    public IndexResult index(String projectPath) {
        return index(projectPath, false);
    }

    /**
     * 索引指定路径的代码库
     *
     * @param projectPath 项目根目录
     * @param forceFull   是否强制全量重建
     */
    public IndexResult index(String projectPath, boolean forceFull) {
        Path root = Paths.get(projectPath).toAbsolutePath().normalize();
        if (!Files.exists(root)) {
            String message = "路径不存在: " + projectPath;
            emit("❌ " + message);
            return new IndexResult(0, 0, message, 0, 0, 0);
        }

        emit("🔍 开始索引: " + root);

        List<Path> filesFound = new ArrayList<>();
        collectFiles(root, filesFound);
        emit("📁 发现 " + filesFound.size() + " 个文件待检查");

        try (VectorStore store = new VectorStore(root.toString())) {
            if (forceFull) {
                emit("🧹 强制全量重建，正在清空旧索引...");
                store.clearProject();
            }

            Set<String> existingIndexedFiles = store.getAllIndexedFiles();
            Map<String, Path> currentFilesMap = new HashMap<>();
            for (Path file : filesFound) {
                currentFilesMap.put(root.relativize(file).toString(), file);
            }

            // 1. 清理已删除的文件
            int deletedCount = 0;
            for (String indexedRelPath : existingIndexedFiles) {
                if (!currentFilesMap.containsKey(indexedRelPath)) {
                    store.deleteFile(indexedRelPath);
                    deletedCount++;
                }
            }
            if (deletedCount > 0) {
                emit("🗑️ 清理已删除文件索引: " + deletedCount + " 个");
            }

            // 2. 增量检查和索引
            int updatedFiles = 0;
            int skippedFiles = 0;
            int total = currentFilesMap.size();
            int current = 0;

            for (Map.Entry<String, Path> entry : currentFilesMap.entrySet()) {
                current++;
                String relPath = entry.getKey();
                Path file = entry.getValue();

                if (current % 10 == 0 || current == total) {
                    emit(String.format("   进度: %d/%d (%s)", current, total, file.getFileName()));
                }

                try {
                    String currentHash = calculateSha256(file);
                    String recordedHash = store.getFileHash(relPath);

                    if (!forceFull && Objects.equals(currentHash, recordedHash)) {
                        skippedFiles++;
                        continue;
                    }

                    // 哈希不一致或新文件：先删除旧数据
                    store.deleteFile(relPath);

                    // 分块
                    List<CodeChunk> chunks = chunker.chunkFile(file);
                    List<VectorStore.CodeChunkEntry> entries = new ArrayList<>();
                    for (CodeChunk chunk : chunks) {
                        float[] embedding = embeddingClient.embed(chunk.toEmbeddingText());
                        // 将相对路径规范化保存
                        CodeChunk normalizedChunk = new CodeChunk(
                                relPath, chunk.chunkType(), chunk.name(),
                                chunk.content(), chunk.startLine(), chunk.endLine()
                        );
                        entries.add(new VectorStore.CodeChunkEntry(normalizedChunk, embedding));
                    }

                    // 批量插入 chunks
                    store.insertChunks(entries);

                    // 关系分析
                    if (file.toString().endsWith(".java")) {
                        List<CodeRelation> relations = analyzer.analyzeFile(file);
                        List<CodeRelation> normalizedRelations = new ArrayList<>();
                        for (CodeRelation rel : relations) {
                            normalizedRelations.add(new CodeRelation(
                                    relPath, rel.fromName(), rel.toFile(), rel.toName(), rel.relationType()
                            ));
                        }
                        store.insertRelations(normalizedRelations);
                    }

                    // 更新文件哈希
                    store.updateFileHash(relPath, currentHash);
                    updatedFiles++;
                } catch (Exception e) {
                    String msg = "   ⚠️ 索引失败: " + file.getFileName() + " - " + e.getMessage();
                    emit(msg);
                    log.log(Level.WARNING, "code index failed for file " + file, e);
                }
            }

            VectorStore.IndexStats stats = store.getStats();
            String msg = String.format("索引完成：更新 %d 个文件，跳过 %d 个未改动文件，删除 %d 个历史文件。总计 %d 个代码块，%d 条关系",
                    updatedFiles, skippedFiles, deletedCount, stats.chunkCount(), stats.relationCount());
            emit("✅ " + msg);
            return new IndexResult(stats.chunkCount(), stats.relationCount(), msg, updatedFiles, skippedFiles, deletedCount);
        } catch (Exception e) {
            String error = "持久化失败: " + e.getMessage();
            emit("❌ " + error);
            log.log(Level.WARNING, "code index persistence failed for root " + root, e);
            return new IndexResult(0, 0, error, 0, 0, 0);
        }
    }

    /**
     * 清理指定项目的索引
     */
    public boolean clean(String projectPath) {
        Path root = Paths.get(projectPath).toAbsolutePath().normalize();
        try (VectorStore store = new VectorStore(root.toString())) {
            store.clearProject();
            emit("✅ 已清空项目索引: " + root);
            return true;
        } catch (Exception e) {
            emit("❌ 清空项目索引失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 查看项目索引统计状态
     */
    public VectorStore.IndexStats getStatus(String projectPath) {
        Path root = Paths.get(projectPath).toAbsolutePath().normalize();
        try (VectorStore store = new VectorStore(root.toString())) {
            return store.getStats();
        } catch (Exception e) {
            log.log(Level.WARNING, "failed to get index stats for root " + root, e);
            return new VectorStore.IndexStats(0, 0, 0);
        }
    }

    private void emit(String message) {
        progressListener.onProgress(message);
    }

    private String calculateSha256(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(path);
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private void collectFiles(Path root, List<Path> files) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    if (dirName.equals("node_modules") || dirName.equals("target")
                            || dirName.equals("build") || dirName.equals(".git")
                            || dirName.equals(".idea") || dirName.equals(".vscode")
                            || dirName.equals("dist") || dirName.equals("out")
                            || dirName.equals(".xhlcli") || dirName.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".java") || name.endsWith(".py")
                            || name.endsWith(".js") || name.endsWith(".ts")
                            || name.endsWith(".go") || name.endsWith(".rs")
                            || name.endsWith(".c") || name.endsWith(".cpp")
                            || name.endsWith(".h") || name.endsWith(".md")
                            || name.endsWith(".xml") || name.endsWith(".properties")
                            || name.endsWith(".yaml") || name.endsWith(".yml")
                            || name.endsWith(".json") || name.endsWith(".sh")
                            || name.endsWith(".gradle") || name.endsWith(".kt")) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            String message = "遍历文件失败: " + e.getMessage();
            emit("❌ " + message);
            log.log(Level.WARNING, "code index file traversal failed for root " + root, e);
        }
    }

    public record IndexResult(int chunkCount, int relationCount, String message,
                              int updatedFiles, int skippedFiles, int deletedFiles) {}
}
