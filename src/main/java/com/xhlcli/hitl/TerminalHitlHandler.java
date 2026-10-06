package com.xhlcli.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 终端人工审批交互处理器。
 * 在终端展示结构化审批框，等待用户输入 decision。
 * 支持通过 HitlInputReader 与终端会话（如 JLine TerminalSession）集成，避免底层流冲突。
 */
public class TerminalHitlHandler implements HitlHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private volatile boolean enabled;
    private final Set<String> approvedAllByTool = ConcurrentHashMap.newKeySet();

    private volatile HitlInputReader in;
    private volatile Consumer<String> out;

    public TerminalHitlHandler(boolean enabled) {
        this(enabled,
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                System.out);
    }

    public TerminalHitlHandler(boolean enabled, BufferedReader inReader, PrintStream outStream) {
        this(enabled,
                prompt -> {
                    if (prompt != null && !prompt.isEmpty()) {
                        outStream.print(prompt);
                        outStream.flush();
                    }
                    return inReader.readLine();
                },
                outStream::println);
    }

    public TerminalHitlHandler(boolean enabled, HitlInputReader in, Consumer<String> out) {
        this.enabled = enabled;
        this.in = Objects.requireNonNull(in, "in");
        this.out = Objects.requireNonNull(out, "out");
    }

    public void setInputReader(HitlInputReader in) {
        this.in = Objects.requireNonNull(in, "in");
    }

    public void setOutputConsumer(Consumer<String> out) {
        this.out = Objects.requireNonNull(out, "out");
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public synchronized ApprovalResult requestApproval(ApprovalRequest request) {
        if (!enabled) {
            return ApprovalResult.approve();
        }

        if (isApprovedAllByTool(request.toolName())) {
            out.accept("  [HITL] " + request.toolName() + " 已在本次会话中全部放行，自动通过");
            return ApprovalResult.approveAll();
        }

        out.accept("");
        out.accept("────────── ⚠️  HITL 审批请求 ──────────");
        out.accept(request.toDisplayText());

        return promptUntilDecision(request);
    }

    private ApprovalResult promptUntilDecision(ApprovalRequest request) {
        for (int attempt = 0; attempt < 5; attempt++) {
            out.accept("");
            out.accept("请选择操作：[y/Enter] 批准  [a] 本会话全部放行  [n] 拒绝  [s] 跳过  [m] 修改参数");

            String input;
            try {
                input = in.readLine("> ");
            } catch (Exception e) {
                out.accept("  [HITL] 读取输入失败或被中断，保守处理为拒绝: " + e.getMessage());
                return ApprovalResult.reject("读取输入失败或被中断: " + e.getMessage());
            }
            if (input == null) {
                out.accept("  [HITL] 输入流已关闭，保守处理为拒绝");
                return ApprovalResult.reject("输入流已关闭");
            }

            String normalized = input.trim().toLowerCase();

            if (normalized.isEmpty() || normalized.equals("y")) {
                out.accept("  已批准");
                return ApprovalResult.approve();
            }

            switch (normalized) {
                case "a" -> {
                    approvedAllByTool.add(request.toolName());
                    out.accept("  已批准，后续本次会话中 " + request.toolName() + " 操作将自动通过");
                    return ApprovalResult.approveAll();
                }
                case "n" -> {
                    String reason;
                    try {
                        reason = in.readLine("  拒绝原因（可直接回车跳过）：");
                    } catch (Exception e) {
                        reason = "";
                    }
                    String finalReason = (reason == null || reason.isBlank()) ? "用户拒绝了此操作" : reason.trim();
                    return ApprovalResult.reject(finalReason);
                }
                case "s" -> {
                    out.accept("  已跳过本次操作");
                    return ApprovalResult.skip();
                }
                case "m" -> {
                    ApprovalResult modified = promptModifiedArguments(request);
                    if (modified != null) {
                        return modified;
                    }
                }
                default -> out.accept("  ❓ 无法识别的选项：'" + input + "'，请输入 y/a/n/s/m 之一（Enter 等价于 y）");
            }
        }
        out.accept("  [HITL] 连续多次无效输入，保守处理为拒绝");
        return ApprovalResult.reject("连续多次无效输入");
    }

    private ApprovalResult promptModifiedArguments(ApprovalRequest request) {
        out.accept("  当前参数：" + request.arguments());

        String modified;
        try {
            modified = in.readLine("  请输入修改后的参数（JSON 格式，空行则使用原始参数）：");
        } catch (Exception e) {
            out.accept("  读取失败，回到主菜单");
            return null;
        }
        if (modified == null || modified.isBlank()) {
            out.accept("  输入为空，改为批准原始参数");
            return ApprovalResult.approve();
        }

        String trimmed = modified.trim();
        try {
            MAPPER.readTree(trimmed);
        } catch (Exception e) {
            out.accept("  ❌ 修改后的参数不是合法 JSON：" + e.getMessage());
            return null;
        }
        return ApprovalResult.modify(trimmed);
    }

    @Override
    public boolean isApprovedAllByTool(String toolName) {
        return toolName != null && approvedAllByTool.contains(toolName);
    }

    @Override
    public void clearApprovedAll() {
        approvedAllByTool.clear();
    }
}
