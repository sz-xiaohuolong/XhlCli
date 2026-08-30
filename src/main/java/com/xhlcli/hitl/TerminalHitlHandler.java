package com.xhlcli.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 终端人工审批交互处理器。
 * 在终端展示结构化审批框，等待用户输入 decision。
 */
public class TerminalHitlHandler implements HitlHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private volatile boolean enabled;
    private final Set<String> approvedAllByTool = ConcurrentHashMap.newKeySet();

    private final BufferedReader in;
    private final PrintStream out;

    public TerminalHitlHandler(boolean enabled) {
        this(enabled,
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                System.out);
    }

    public TerminalHitlHandler(boolean enabled, BufferedReader in, PrintStream out) {
        this.enabled = enabled;
        this.in = in;
        this.out = out;
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
            out.println("  [HITL] " + request.toolName() + " 已在本次会话中全部放行，自动通过");
            return ApprovalResult.approveAll();
        }

        out.println();
        out.println("────────── ⚠️  HITL 审批请求 ──────────");
        out.println(request.toDisplayText());

        return promptUntilDecision(request);
    }

    private ApprovalResult promptUntilDecision(ApprovalRequest request) {
        for (int attempt = 0; attempt < 5; attempt++) {
            out.println();
            out.println("请选择操作：[y/Enter] 批准  [a] 本会话全部放行  [n] 拒绝  [s] 跳过  [m] 修改参数");
            out.print("> ");
            out.flush();

            String input;
            try {
                input = in.readLine();
            } catch (IOException e) {
                out.println("  [HITL] 读取输入失败，保守处理为拒绝");
                return ApprovalResult.reject("读取输入失败: " + e.getMessage());
            }
            if (input == null) {
                out.println("  [HITL] 输入流已关闭，保守处理为拒绝");
                return ApprovalResult.reject("输入流已关闭");
            }

            String normalized = input.trim().toLowerCase();

            if (normalized.isEmpty() || normalized.equals("y")) {
                out.println("  已批准");
                return ApprovalResult.approve();
            }

            switch (normalized) {
                case "a" -> {
                    approvedAllByTool.add(request.toolName());
                    out.println("  已批准，后续本次会话中 " + request.toolName() + " 操作将自动通过");
                    return ApprovalResult.approveAll();
                }
                case "n" -> {
                    out.print("  拒绝原因（可直接回车跳过）：");
                    out.flush();
                    String reason;
                    try {
                        reason = in.readLine();
                    } catch (IOException e) {
                        reason = "";
                    }
                    String finalReason = (reason == null || reason.isBlank()) ? "用户拒绝了此操作" : reason.trim();
                    return ApprovalResult.reject(finalReason);
                }
                case "s" -> {
                    out.println("  已跳过本次操作");
                    return ApprovalResult.skip();
                }
                case "m" -> {
                    ApprovalResult modified = promptModifiedArguments(request);
                    if (modified != null) {
                        return modified;
                    }
                }
                default -> out.println("  ❓ 无法识别的选项：'" + input + "'，请输入 y/a/n/s/m 之一（Enter 等价于 y）");
            }
        }
        out.println("  [HITL] 连续多次无效输入，保守处理为拒绝");
        return ApprovalResult.reject("连续多次无效输入");
    }

    private ApprovalResult promptModifiedArguments(ApprovalRequest request) {
        out.println("  当前参数：" + request.arguments());
        out.print("  请输入修改后的参数（JSON 格式，空行则使用原始参数）：");
        out.flush();

        String modified;
        try {
            modified = in.readLine();
        } catch (IOException e) {
            out.println("  读取失败，回到主菜单");
            return null;
        }
        if (modified == null || modified.isBlank()) {
            out.println("  输入为空，改为批准原始参数");
            return ApprovalResult.approve();
        }

        String trimmed = modified.trim();
        try {
            MAPPER.readTree(trimmed);
        } catch (Exception e) {
            out.println("  ❌ 修改后的参数不是合法 JSON：" + e.getMessage());
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
