package com.xhlcli.tool.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolMetadata;
import com.xhlcli.model.ToolMetadata.RiskLevel;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.snapshot.RestoreResult;
import com.xhlcli.snapshot.SnapshotService;
import com.xhlcli.tool.Tool;

import java.util.Objects;

/**
 * 撤销与恢复快照工具。
 * 允许 Agent 在明确判断本次任务或前几轮修改产生错误时，安全回滚至 pre-turn 状态。
 * 属于高风险操作，纳入 HITL 审批与结构化审计日志。
 */
public final class RevertTurnTool implements Tool {

    private static final ToolDefinition DEFINITION = createDefinition();
    private final SnapshotService snapshotService;

    public RevertTurnTool(SnapshotService snapshotService) {
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService");
    }

    private static ToolDefinition createDefinition() {
        ObjectNode parameters = JsonNodeFactory.instance.objectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");

        ObjectNode offsetNode = properties.putObject("offset");
        offsetNode.put("type", "integer");
        offsetNode.put("description", "要恢复的 pre-turn 快照序号，1 表示最近一次任务开始前");

        ObjectNode reasonNode = properties.putObject("reason");
        reasonNode.put("type", "string");
        reasonNode.put("description", "恢复快照的原因或理由");

        ToolMetadata metadata = new ToolMetadata(RiskLevel.HIGH, false, false, false, "local");
        return new ToolDefinition(
                "revert_turn",
                "恢复到 Side-Git 记录的最近第 N 个 pre-turn 快照。会先记录 pre-restore 保护快照；属于高危写入操作，必须经 HITL 审批。",
                parameters,
                metadata
        );
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutput execute(JsonNode arguments, CancellationToken cancellationToken) throws Exception {
        int offset = 1;
        if (arguments != null && arguments.has("offset") && arguments.get("offset").isInt()) {
            offset = Math.max(1, arguments.get("offset").asInt());
        }
        RestoreResult result = snapshotService.restorePreTurn(offset);
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("success", result.success());
        if (result.commitId() != null) {
            data.put("commitId", result.commitId());
        }
        data.put("restoredFiles", result.restoredFiles().size());
        data.put("removedFiles", result.removedFiles().size());

        if (!result.success()) {
            throw new RuntimeException(result.formatForCli());
        }
        return new ToolOutput(result.formatForCli(), data, "");
    }
}
