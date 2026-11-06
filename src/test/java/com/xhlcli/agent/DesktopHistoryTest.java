package com.xhlcli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.llm.*;
import com.xhlcli.model.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopHistoryTest {
    @Test void restoredConversationKeepsToolPairsAndUsesCurrentSystemRules() throws Exception {
        var method = Arrays.stream(ReactAgent.class.getMethods()).filter(m -> m.getName().equals("restoreHistory")).findFirst();
        assertTrue(method.isPresent(), "桌面重启续聊需要历史恢复接缝");
        try (var scheduler = new ScheduledTimeoutScheduler()) {
            ReactAgent agent = new ReactAgent(ChatMessage.system("当前规则"), (m,t,l,c) -> null,
                    (call,token) -> null, List.of(), new RunLimits(3, Duration.ofSeconds(10)), scheduler,
                    new ObjectMapper(), Clock.systemUTC(), () -> "test");
            var history = List.of(ChatMessage.system("旧规则"), ChatMessage.user("标记是 maple"),
                    ChatMessage.assistant("调用工具", List.of(new ToolCall("t1", "read_file", "{}"))),
                    ChatMessage.tool("t1", "文件结果"), ChatMessage.assistant("完成"));
            method.get().invoke(agent, history);
            assertEquals("当前规则", agent.history().getFirst().content());
            assertEquals(history.subList(1, 5), agent.history().subList(1, 5));
        }
    }
}
