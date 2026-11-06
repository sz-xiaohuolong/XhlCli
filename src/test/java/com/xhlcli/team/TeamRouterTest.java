package com.xhlcli.team;

import com.xhlcli.llm.*;
import com.xhlcli.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TeamRouterTest {
    @Test void decisionUsesContextWithoutGivingTheClassifierTools() {
        var seen = new ArrayList<ChatMessage>();
        LlmClient client = (messages, tools, listener, token) -> {
            assertTrue(tools.isEmpty()); seen.addAll(messages);
            return new ChatResponse("{\"mode\":\"TEAM\",\"reason\":\"跨模块分工\"}", TokenUsage.unknown());
        };
        var context = TeamRouter.context(List.of(ChatMessage.system("不得修改数据库"),
                ChatMessage.user("项目使用 Java"), ChatMessage.assistant("了解")), java.nio.file.Path.of("."));
        var result = new TeamRouter().select(client, "继续实现", context, new CancellationToken());
        assertTrue(result.team());
        assertTrue(seen.getLast().content().contains("不得修改数据库"));
        assertTrue(seen.getLast().content().contains("项目使用 Java"));
    }
    @Test void malformedResponsesFallbackWithoutLeakingModelOutput() {
        for (String content : List.of("不是 JSON", "null", "{\"mode\":\"TEAM\"}",
                "{\"mode\":\"TEAM\",\"reason\":\"原因\"} {}")) {
            LlmClient client = (messages, tools, listener, token) -> new ChatResponse(content, TokenUsage.unknown());
            var decision = new TeamRouter().select(client, "任务", "", new CancellationToken());
            assertFalse(decision.team());
        }
    }
    @Test void networkFailureAndCancellationDoNotBecomeTeamDecisions() {
        LlmClient failure = (messages, tools, listener, token) -> { throw new LlmException(LlmErrorType.NETWORK, "断网", false, false); };
        assertFalse(new TeamRouter().select(failure, "任务", "", new CancellationToken()).team());
        var parent = new CancellationToken();
        LlmClient canceled = (messages, tools, listener, token) -> {
            parent.cancel(); assertTrue(token.isCancelled());
            return new ChatResponse("{\"mode\":\"TEAM\",\"reason\":\"不能执行\"}", TokenUsage.unknown());
        };
        assertFalse(new TeamRouter().select(canceled, "任务", "", parent).team());
    }
}
