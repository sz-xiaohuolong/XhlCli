package com.xhlcli.prompt;

import java.util.*;

/**
 * 分层 Prompt 组装引擎，负责管理 8 层提示词的注册、三层覆盖、安全底线强保障与预算截断。
 */
public final class LayeredPromptAssembler {
    private final LayerBudgetConfig budgetConfig;
    private final Map<PromptLayer, Map<PromptSource, PromptBlock>> layerBlocks = new EnumMap<>(PromptLayer.class);

    public LayeredPromptAssembler() {
        this(LayerBudgetConfig.defaults());
    }

    public LayeredPromptAssembler(LayerBudgetConfig budgetConfig) {
        this.budgetConfig = Objects.requireNonNull(budgetConfig, "budgetConfig");
        for (PromptLayer layer : PromptLayer.values()) {
            layerBlocks.put(layer, new EnumMap<>(PromptSource.class));
        }
        initBuiltinDefaults();
    }

    /**
     * 注册或覆盖指定层级的 PromptBlock。
     */
    public synchronized LayeredPromptAssembler registerBlock(PromptBlock block) {
        Objects.requireNonNull(block, "block");
        PromptLayer layer = block.layer();
        layerBlocks.get(layer).put(block.source(), block);
        return this;
    }

    public synchronized LayeredPromptAssembler registerBlock(
            PromptLayer layer,
            PromptSource source,
            String title,
            String content) {
        return registerBlock(PromptBlock.of(layer, source, title, content));
    }

    /**
     * 清空指定层指定来源的提示块。
     */
    public synchronized void clearBlock(PromptLayer layer, PromptSource source) {
        if (layer != null && source != null) {
            layerBlocks.get(layer).remove(source);
        }
    }

    /**
     * 组装所有分层，返回带有来源和截断指标的 PromptBlock 列表。
     */
    public synchronized List<PromptBlock> assembleBlocks() {
        List<PromptBlock> resolvedBlocks = new ArrayList<>();
        PromptLayer[] layers = PromptLayer.values();
        Arrays.sort(layers, Comparator.comparingInt(PromptLayer::order));

        for (PromptLayer layer : layers) {
            Map<PromptSource, PromptBlock> sources = layerBlocks.get(layer);
            if (sources == null || sources.isEmpty()) {
                continue;
            }

            final PromptBlock selectedBlock;
            if (layer.isImmutable()) {
                // 安全底线层 (SAFETY_POLICY)：内置安全规则不可覆盖！
                // 即使存在 USER/PROJECT 规则，BUILTIN 必须始终作为基线核心保留
                PromptBlock builtinBlock = sources.get(PromptSource.BUILTIN);
                PromptBlock projectBlock = sources.get(PromptSource.PROJECT);
                PromptBlock userBlock = sources.get(PromptSource.USER);

                StringBuilder mergedSafety = new StringBuilder();
                if (builtinBlock != null && !builtinBlock.isEmpty()) {
                    mergedSafety.append(builtinBlock.content());
                }
                // 补充用户/项目级附加安全规则（但不可替代内置规则）
                if (userBlock != null && !userBlock.isEmpty()) {
                    if (!mergedSafety.isEmpty()) mergedSafety.append("\n\n");
                    mergedSafety.append("### User Security Constraints\n").append(userBlock.content());
                }
                if (projectBlock != null && !projectBlock.isEmpty()) {
                    if (!mergedSafety.isEmpty()) mergedSafety.append("\n\n");
                    mergedSafety.append("### Project Security Constraints\n").append(projectBlock.content());
                }

                String title = builtinBlock != null ? builtinBlock.title() : "## Tool & Safety Policy";
                selectedBlock = PromptBlock.of(layer, PromptSource.BUILTIN, title, mergedSafety.toString());
            } else {
                // 普通可覆盖层：优先级 PROJECT > USER > BUILTIN
                if (sources.containsKey(PromptSource.PROJECT)) {
                    selectedBlock = sources.get(PromptSource.PROJECT);
                } else if (sources.containsKey(PromptSource.USER)) {
                    selectedBlock = sources.get(PromptSource.USER);
                } else {
                    selectedBlock = sources.get(PromptSource.BUILTIN);
                }
            }

            if (selectedBlock == null || selectedBlock.isEmpty()) {
                continue;
            }

            // 执行分层预算截断
            int maxChars = budgetConfig.getBudget(layer);
            String rawContent = selectedBlock.content();
            if (rawContent.length() > maxChars) {
                String truncatedContent = rawContent.substring(0, maxChars) + "\n\n[... 内容超出分层预算已截断 ...]";
                resolvedBlocks.add(PromptBlock.truncated(
                        layer,
                        selectedBlock.source(),
                        selectedBlock.title(),
                        truncatedContent,
                        rawContent.length()));
            } else {
                resolvedBlocks.add(selectedBlock);
            }
        }

        return Collections.unmodifiableList(resolvedBlocks);
    }

    /**
     * 合成最终传给 LLM 的系统提示词字符串。
     */
    public synchronized String assembleSystemPrompt() {
        List<PromptBlock> blocks = assembleBlocks();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            PromptBlock block = blocks.get(i);
            String rendered = block.render();
            if (!rendered.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(rendered);
            }
        }
        return sb.toString().trim();
    }

    /**
     * 初始化内置的基线规则。
     */
    private void initBuiltinDefaults() {
        // Layer 1: Base Identity
        registerBlock(PromptLayer.BASE_IDENTITY, PromptSource.BUILTIN, "",
                """
                You are XhlCLI, a helpful and precise coding assistant.
                Please reply in Chinese (中文).
                """);

        // Layer 2: Safety Policy (Immutable)
        registerBlock(PromptLayer.SAFETY_POLICY, PromptSource.BUILTIN, "## Tool & Safety Policy",
                """
                ### Code Exploration Pipeline
                0. `search_code`: RAG 语义辅助检索代码库，根据自然语言意图查找可能相关的代码块与模块入口。
                1. `glob_files`: Locate candidate filenames or structural patterns (e.g. `**/*Service.java`).
                2. `grep_code`: Locate exact symbols, method declarations, configurations, or lines.
                3. `read_file`: Read bounded line ranges around matches using suggested `offset` and `limit`. Never read the whole file if nearby lines suffice.
                4. When `grep_code` indicates `partial: true`, refine your search with a more specific `path`, `glob`, or `pattern`.

                ### Local Code First Rule
                - When the user asks about the current repository, code, architecture, or configuration, ALWAYS use local exploration tools (`search_code`, `glob_files`, `grep_code`, `read_file`).
                - NEVER fabricate file paths or line numbers. Every code claim must cite real relative paths and line numbers verified from tool results.
                - NEVER invoke external web searches for questions about current local code.

                ### Modification Guidelines
                - Use `write_file` to create or overwrite files.
                - Use `apply_patch` for precise single-occurrence text replacements in existing files.
                - Use `git_diff` to check unstaged changes in the repository.
                - Use `execute_command` to run short-running build, test, and shell commands in the project directory.
                - All file operations are restricted to the project workspace.

                ### Web & Browser Guidelines
                - Use `web_search` to query the public internet for the latest technical documentation, library release notes, or error solutions when not found locally.
                - Use `web_fetch` to retrieve readable markdown content of a public URL.
                - Local exploration tools (`search_code`, `glob_files`, `grep_code`, `read_file`) must always take precedence when exploring the local repository.
                - Browser sessions can be monitored via `browser_status` or connected via `browser_connect`.
                """);

        // Layer 8: Handover & Context guidelines
        registerBlock(PromptLayer.HANDOVER, PromptSource.BUILTIN, "## Context & Output Guidelines",
                """
                - Be concise, direct, and factual.
                - Do not repeat file contents unless explicitly asked.
                - When a task requires structured guidance or expert workflow, check available skills and call `load_skill(name)`.
                """);
    }
}
