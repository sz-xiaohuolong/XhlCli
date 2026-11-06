package com.xhlcli.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.model.ChatMessage;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReasoningOptionsTest {
    @Test void OpenAI强度使用原生参数且未知模型拒绝猜测() throws Exception {
        var registry=new LlmProviderRegistry(Map.of("OPENAI_API_KEY","fixture"));
        var model=new ModelDescriptor("openai:gpt-5","openai","gpt-5","gpt-5",ModelCapabilities.openAiDefault(),true,List.of());
        try(var client=registry.createClient(model,DiagnosticSink.NO_OP,"high")){
            var buffer=new Buffer();((OpenAiClient)client).buildRequest(List.of(ChatMessage.user("测试")),List.of()).body().writeTo(buffer);
            var body=new ObjectMapper().readTree(buffer.readUtf8());assertEquals("high",body.path("reasoning_effort").asText());assertFalse(body.has("thinking"));
        }
        assertThrows(IllegalArgumentException.class,()->ReasoningOptions.validate("openai:gpt-4o","high"));
        assertThrows(IllegalArgumentException.class,()->ReasoningOptions.validate("anthropic:custom","high"));
        assertTrue(ReasoningOptions.levels("deepseek:custom").isEmpty());
    }
    @Test void 默认不添加参数且旧版工具历史可传递空推理字段() throws Exception {
        var registry=new LlmProviderRegistry(Map.of("DEEPSEEK_API_KEY","fixture"));
        var model=new ModelDescriptor("deepseek:deepseek-flash","deepseek","deepseek-flash","deepseek-flash",ModelCapabilities.deepseekDefault(),true,List.of());
        try(var client=registry.createClient(model,DiagnosticSink.NO_OP,"default")){
            var history=List.of(ChatMessage.user("测试"),ChatMessage.assistant("",List.of(new com.xhlcli.model.ToolCall("call-1","echo","{}"))),ChatMessage.tool("call-1","完成"));
            var buffer=new Buffer();((DeepSeekClient)client).buildRequest(history,List.of()).body().writeTo(buffer);
            var body=new ObjectMapper().readTree(buffer.readUtf8());assertFalse(body.has("reasoning_effort"));assertFalse(body.has("thinking"));
            assertEquals("",body.path("messages").get(1).path("reasoning_content").asText(null));
        }
    }
    @Test void 旧聊天兼容且推理字段可往返保存() throws Exception {
        var mapper=new ObjectMapper();
        var old=mapper.readValue("{\"role\":\"ASSISTANT\",\"content\":\"答案\",\"toolCalls\":[],\"toolCallId\":null,\"contentParts\":[]}",ChatMessage.class);
        assertEquals("",old.reasoningContent());
        var reasoning=new ChatMessage(ChatMessage.Role.ASSISTANT,"答案",List.of(),null,List.of(),"模型内部上下文");
        assertEquals(reasoning,mapper.readValue(mapper.writeValueAsString(reasoning),ChatMessage.class));
    }
}
