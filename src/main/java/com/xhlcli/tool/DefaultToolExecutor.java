package com.xhlcli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.hitl.ApprovalPolicy;
import com.xhlcli.hitl.ApprovalRequest;
import com.xhlcli.hitl.ApprovalResult;
import com.xhlcli.hitl.HitlHandler;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolCall;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.model.ToolResult;
import com.xhlcli.model.ToolResultStatus;
import com.xhlcli.policy.AuditLog;
import com.xhlcli.policy.CommandGuard;
import com.xhlcli.policy.PathGuard;
import com.xhlcli.policy.PolicyException;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Executes one validated tool call and converts every expected failure into a ToolResult. */
public final class DefaultToolExecutor implements ToolExecutor {
    private final ToolRegistry registry;
    private final ToolSchemaValidator schemaValidator;
    private final ToolResultBudget resultBudget;
    private final ObjectMapper mapper;
    private final LongSupplier nanoTime;
    private final PathGuard pathGuard;
    private final HitlHandler hitlHandler;
    private final AuditLog auditLog;

    public DefaultToolExecutor(
            ToolRegistry registry,
            ToolSchemaValidator schemaValidator,
            ToolResultBudget resultBudget,
            ObjectMapper mapper,
            LongSupplier nanoTime) {
        this(registry, schemaValidator, resultBudget, mapper, nanoTime, null, null, null);
    }

    public DefaultToolExecutor(
            ToolRegistry registry,
            ToolSchemaValidator schemaValidator,
            ToolResultBudget resultBudget,
            ObjectMapper mapper,
            LongSupplier nanoTime,
            PathGuard pathGuard,
            HitlHandler hitlHandler,
            AuditLog auditLog) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.schemaValidator = Objects.requireNonNull(schemaValidator, "schemaValidator");
        this.resultBudget = Objects.requireNonNull(resultBudget, "resultBudget");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.pathGuard = pathGuard;
        this.hitlHandler = hitlHandler;
        this.auditLog = auditLog;
    }

    @Override
    public ToolResult execute(ToolCall call, CancellationToken cancellationToken) {
        long startedAt = nanoTime.getAsLong();
        String callId = call == null || call.id() == null ? "" : call.id();
        String toolName = call == null || call.name() == null ? "" : call.name();
        String argsJson = call == null || call.argumentsJson() == null ? "{}" : call.argumentsJson();

        try {
            if (isCancelled(cancellationToken)) {
                return finish(startedAt, callId, toolName, ToolResultStatus.CANCELLED, "Tool execution was cancelled.");
            }
            if (callId.isBlank()) {
                return finish(startedAt, callId, toolName, ToolResultStatus.VALIDATION_ERROR, "Tool call ID must not be blank.");
            }
            if (toolName.isBlank()) {
                return finish(startedAt, callId, toolName, ToolResultStatus.VALIDATION_ERROR, "Tool name must not be blank.");
            }
            Tool tool = registry.find(toolName).orElse(null);
            if (tool == null) {
                return finish(startedAt, callId, toolName, ToolResultStatus.UNKNOWN_TOOL, "Unknown tool: " + toolName + ".");
            }

            // 1. Schema 校验
            ToolSchemaValidator.ValidationResult validation = schemaValidator.parseAndValidate(
                    argsJson, tool.definition().parameters());
            if (!validation.isValid()) {
                return finish(startedAt, callId, toolName, ToolResultStatus.VALIDATION_ERROR, validation.error());
            }

            JsonNode parsedArguments = validation.arguments();

            // 2. 系统硬策略检查 (Hard Policy)
            String policyViolation = checkHardPolicies(toolName, parsedArguments);
            if (policyViolation != null) {
                recordAudit(AuditLog.AuditEntry.denyByPolicy(toolName, argsJson, policyViolation, elapsedMillis(startedAt)));
                return finish(startedAt, callId, toolName, ToolResultStatus.EXECUTION_ERROR, "[POLICY] 策略拒绝: " + policyViolation);
            }

            // 3. 人工审批 (HITL)
            if (hitlHandler != null && hitlHandler.isEnabled() && ApprovalPolicy.requiresApproval(toolName)) {
                if (!hitlHandler.isApprovedAllByTool(toolName)) {
                    ApprovalRequest request = ApprovalRequest.of(toolName, argsJson);
                    ApprovalResult approval = hitlHandler.requestApproval(request);

                    if (approval.isRejected()) {
                        String reason = approval.reason() != null && !approval.reason().isBlank()
                                ? approval.reason()
                                : "用户拒绝了此操作";
                        recordAudit(AuditLog.AuditEntry.denyByHitl(toolName, argsJson, reason, elapsedMillis(startedAt)));
                        return finish(startedAt, callId, toolName, ToolResultStatus.EXECUTION_ERROR, "[HITL] 操作已被用户拒绝: " + reason);
                    }

                    if (approval.isSkipped()) {
                        recordAudit(AuditLog.AuditEntry.denyByHitl(toolName, argsJson, "用户跳过", elapsedMillis(startedAt)));
                        return finish(startedAt, callId, toolName, ToolResultStatus.EXECUTION_ERROR, "[HITL] 操作已被跳过");
                    }

                    if (approval.isModified()) {
                        String modifiedArgs = approval.effectiveArguments(argsJson);
                        ToolSchemaValidator.ValidationResult modifiedValidation = schemaValidator.parseAndValidate(
                                modifiedArgs, tool.definition().parameters());
                        if (!modifiedValidation.isValid()) {
                            return finish(startedAt, callId, toolName, ToolResultStatus.VALIDATION_ERROR,
                                    "用户修改后的参数不合法: " + modifiedValidation.error());
                        }
                        String modifiedViolation = checkHardPolicies(toolName, modifiedValidation.arguments());
                        if (modifiedViolation != null) {
                            recordAudit(AuditLog.AuditEntry.denyByPolicy(toolName, modifiedArgs, modifiedViolation, elapsedMillis(startedAt)));
                            return finish(startedAt, callId, toolName, ToolResultStatus.EXECUTION_ERROR,
                                    "[POLICY] 修改后的参数违反策略: " + modifiedViolation);
                        }
                        parsedArguments = modifiedValidation.arguments();
                        argsJson = modifiedArgs;
                    }
                }
            }

            if (isCancelled(cancellationToken)) {
                return finish(startedAt, callId, toolName, ToolResultStatus.CANCELLED, "Tool execution was cancelled.");
            }

            // 4. 执行工具
            ToolOutput output = tool.execute(parsedArguments, cancellationToken);
            recordAudit(AuditLog.AuditEntry.allow(toolName, argsJson, elapsedMillis(startedAt)));

            return finish(startedAt, callId, toolName, ToolResultStatus.SUCCESS, output.summary(), output.data(), output.continueHint());
        } catch (PolicyException policyEx) {
            recordAudit(AuditLog.AuditEntry.denyByPolicy(toolName, argsJson, policyEx.getMessage(), elapsedMillis(startedAt)));
            return finish(startedAt, callId, toolName, ToolResultStatus.EXECUTION_ERROR, "[POLICY] 策略拒绝: " + policyEx.getMessage());
        } catch (Exception failure) {
            recordAudit(AuditLog.AuditEntry.error(toolName, argsJson, failure.getMessage(), elapsedMillis(startedAt)));
            return finish(startedAt, callId, toolName, ToolResultStatus.EXECUTION_ERROR, "Tool execution failed.");
        }
    }

    private String checkHardPolicies(String toolName, JsonNode arguments) {
        if ("execute_command".equals(toolName) && arguments.has("command")) {
            String command = arguments.get("command").asText();
            String denyReason = CommandGuard.check(command);
            if (denyReason != null) {
                return denyReason;
            }
        }

        if (pathGuard != null && arguments.has("path")) {
            String path = arguments.get("path").asText();
            String denyReason = pathGuard.checkSafe(path);
            if (denyReason != null) {
                return denyReason;
            }
        }

        return null;
    }

    private void recordAudit(AuditLog.AuditEntry entry) {
        if (auditLog != null) {
            auditLog.record(entry);
        }
    }

    private ToolResult finish(long startedAt, String callId, String toolName, ToolResultStatus status, String summary) {
        return finish(startedAt, callId, toolName, status, summary, mapper.createObjectNode(), "");
    }

    private ToolResult finish(
            long startedAt, String callId, String toolName, ToolResultStatus status, String summary,
            com.fasterxml.jackson.databind.JsonNode data, String continueHint) {
        long elapsedMillis = elapsedMillis(startedAt);
        ToolResult result = new ToolResult(
                callId, toolName, status, summary, data, elapsedMillis, false, 0, continueHint);
        return resultBudget.apply(result);
    }

    private long elapsedMillis(long startedAt) {
        long endedAt = nanoTime.getAsLong();
        if (endedAt < startedAt) {
            return 0;
        }
        try {
            return Math.subtractExact(endedAt, startedAt) / 1_000_000L;
        } catch (ArithmeticException overflow) {
            return 0;
        }
    }

    private static boolean isCancelled(CancellationToken cancellationToken) {
        return cancellationToken != null && cancellationToken.isCancelled();
    }
}
