package com.xhlcli.parallel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.hitl.ApprovalPolicy;
import com.xhlcli.model.ToolCall;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolMetadata;
import com.xhlcli.tool.Tool;
import com.xhlcli.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 决定工具调用是否具备并行执行资格，以及判断调用之间的资源冲突
 */
public final class ParallelEligibilityDecider {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ParallelEligibilityDecider() {
    }

    /**
     * 判断单个工具调用是否具备并行资格
     */
    public static boolean isEligible(ToolCall call, ToolRegistry registry) {
        return isEligible(call, registry != null ? registry.definitions() : null);
    }

    /**
     * 判断单个工具调用是否具备并行资格（基于工具定义列表）
     */
    public static boolean isEligible(ToolCall call, List<ToolDefinition> definitions) {
        if (call == null || call.name() == null || call.name().isBlank() || definitions == null) {
            return false;
        }

        // 需要人工审批的工具必须串行交互，避免多个审批提示在控制台竞争穿插
        if (ApprovalPolicy.requiresApproval(call.name())) {
            return false;
        }

        for (ToolDefinition def : definitions) {
            if (def != null && call.name().equals(def.name())) {
                ToolMetadata metadata = def.metadata();
                if (metadata != null) {
                    return metadata.allowsParallel() && metadata.readOnly();
                }
            }
        }
        return false;
    }

    /**
     * 提取工具调用的资源访问描述
     */
    public static ResourceAccess extractAccess(ToolCall call, ToolRegistry registry) {
        return extractAccess(call, registry != null ? registry.definitions() : null);
    }

    /**
     * 提取工具调用的资源访问描述（基于工具定义列表）
     */
    public static ResourceAccess extractAccess(ToolCall call, List<ToolDefinition> definitions) {
        if (call == null || call.name() == null) {
            return ResourceAccess.exclusive("unknown");
        }

        String toolName = call.name();
        boolean isReadOnly = false;
        boolean allowsParallel = false;

        if (definitions != null) {
            for (ToolDefinition def : definitions) {
                if (def != null && toolName.equals(def.name()) && def.metadata() != null) {
                    ToolMetadata meta = def.metadata();
                    isReadOnly = meta.readOnly();
                    allowsParallel = meta.allowsParallel();
                    break;
                }
            }
        }

        // 文件路径相关工具检测
        if (toolName.equals("read_file") || toolName.equals("write_file") || toolName.equals("apply_patch")) {
            String pathArg = extractPathArgument(call.argumentsJson());
            if (pathArg != null && !pathArg.isBlank()) {
                String normalizedPath = normalizePath(pathArg);
                return isReadOnly
                        ? ResourceAccess.read("file:" + normalizedPath)
                        : ResourceAccess.write("file:" + normalizedPath);
            }
        }

        if (toolName.equals("execute_command")) {
            return ResourceAccess.exclusive("command:shell");
        }

        if (isReadOnly && allowsParallel) {
            return ResourceAccess.read("tool:" + toolName);
        }

        return ResourceAccess.exclusive("tool:" + toolName);
    }

    /**
     * 判断两个工具调用是否存在资源冲突
     */
    public static boolean hasConflict(ToolCall callA, ToolCall callB, ToolRegistry registry) {
        return hasConflict(callA, callB, registry != null ? registry.definitions() : null);
    }

    public static boolean hasConflict(ToolCall callA, ToolCall callB, List<ToolDefinition> definitions) {
        if (callA == null || callB == null) {
            return false;
        }

        ResourceAccess accessA = extractAccess(callA, definitions);
        ResourceAccess accessB = extractAccess(callB, definitions);

        return accessA.conflictsWith(accessB);
    }

    private static String extractPathArgument(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(argumentsJson);
            JsonNode pathNode = node.get("path");
            return pathNode != null && pathNode.isTextual() ? pathNode.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizePath(String rawPath) {
        try {
            return Path.of(rawPath.trim()).normalize().toString().replace('\\', '/');
        } catch (Exception e) {
            return rawPath.trim().replace('\\', '/');
        }
    }
}
