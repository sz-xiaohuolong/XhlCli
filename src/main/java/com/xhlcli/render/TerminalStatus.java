package com.xhlcli.render;

import java.util.Objects;

/**
 * 终端底部状态栏快照数据模型。
 */
public record TerminalStatus(
        String mode,
        String phase,
        String model,
        int currentTokens,
        int maxTokens,
        int activeMcpServers,
        int activeSkills,
        String workspacePath
) {
    public TerminalStatus {
        mode = mode == null ? "REAct" : mode;
        phase = phase == null ? "Idle" : phase;
        model = model == null ? "unknown" : model;
        workspacePath = workspacePath == null ? "." : workspacePath;
    }

    public TerminalStatus(String mode, String phase, String model) {
        this(mode, phase, model, 0, 0, 0, 0, ".");
    }

    public static TerminalStatus initial(String model, String workspacePath) {
        return new TerminalStatus("REAct", "Idle", model, 0, 0, 0, 0, workspacePath);
    }

    public TerminalStatus withPhase(String newPhase) {
        return new TerminalStatus(mode, newPhase, model, currentTokens, maxTokens, activeMcpServers, activeSkills, workspacePath);
    }

    public TerminalStatus withMode(String newMode) {
        return new TerminalStatus(newMode, phase, model, currentTokens, maxTokens, activeMcpServers, activeSkills, workspacePath);
    }

    public TerminalStatus withTokens(int current, int max) {
        return new TerminalStatus(mode, phase, model, current, max, activeMcpServers, activeSkills, workspacePath);
    }

    public TerminalStatus withModel(String newModel) {
        return new TerminalStatus(mode, phase, newModel, currentTokens, maxTokens, activeMcpServers, activeSkills, workspacePath);
    }

    public TerminalStatus withExtensions(int mcp, int skills) {
        return new TerminalStatus(mode, phase, model, currentTokens, maxTokens, mcp, skills, workspacePath);
    }
}
