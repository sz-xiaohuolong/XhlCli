import com.xhlcli.agent.*;
import com.xhlcli.cli.*;
import com.xhlcli.config.*;
import com.xhlcli.llm.*;
import com.xhlcli.memory.*;
import com.xhlcli.model.*;
import com.xhlcli.render.*;
import java.util.*;
import java.io.*;
import java.net.URI;
import java.time.Duration;

class XhlcliContextReview {
  public static void main(String[] args) {
    List<ChatMessage> source = new ArrayList<>();
    source.add(ChatMessage.user("不可丢失的约束：只修改模块甲"));
    for (int i=1;i<8;i++) source.add(i%2==0?ChatMessage.user("用户"+i):ChatMessage.assistant("回答"+i));
    LlmClient failure = (messages, tools, listener, token) -> { throw new IllegalStateException("模拟摘要服务不可用"); };
    var compacted = new ConversationHistoryCompactor(failure).compact(source,5);
    boolean retained = compacted.stream().anyMatch(m -> m.content().contains("只修改模块甲"));
    if(retained || !compacted.getFirst().content().contains("摘要生成失败")) throw new AssertionError("失败分支未按观察复现");
    System.out.println("自动压缩失败：原始消息="+source.size()+"，结果="+compacted.size()+"，原约束保留="+retained);
    class FakeAgent implements AgentRunner {
      int clears=0;
      List<ChatMessage> history=new ArrayList<>(source);
      public RunResult run(String input,RunEventSink events,CancellationToken token){ throw new AssertionError("本验证不得请求模型"); }
      public void clearHistory(){ clears++; history.clear(); }
      public List<ChatMessage> history(){ return history; }
    }
    var agent = new FakeAgent();
    var inputs = List.of("/compact","/exit").iterator();
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    var config = new ChatConfig("仅用于本地验证的占位值","fake",URI.create("http://127.0.0.1"),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(1),LogLevel.INFO,Map.of());
    var loop = new ChatLoop(prompt -> inputs.next(),new ChatCommandParser(),agent,new PlainRunRenderer(new PrintStream(out),new PrintStream(err),null),config);
    loop.setCompactor(new ConversationHistoryCompactor((messages,tools,listener,token)->{throw new AssertionError("手动路径未调用压缩器");}));
    if(loop.run()!=0 || agent.clears!=1 || !agent.history().isEmpty()) throw new AssertionError("手动压缩未按观察复现");
    System.out.println("手动 /compact：clearHistory 调用="+agent.clears+"，剩余历史="+agent.history().size()+"，摘要器未调用");
  }
}
