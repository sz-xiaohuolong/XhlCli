import com.xhlcli.agent.*;
import com.xhlcli.parallel.*;
import com.xhlcli.tool.*;
import com.xhlcli.tool.local.*;
import com.xhlcli.policy.*;
import com.xhlcli.hitl.*;
import com.xhlcli.llm.*;
import com.xhlcli.model.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public class XhlAgentTimeoutProbe {
 public static void main(String[] args) throws Exception {
  Path base=Files.createTempDirectory("xhl-agent-timeout-");
  Path release=base.resolve("release"), marker=base.resolve("late-write");
  var tool=new ExecuteCommandTool(new WorkspacePathResolver(base));
  var mapper=new ObjectMapper();
  String first="while [ ! -f '"+release+"' ]; do sleep 0.01; done; printf '第一条超时命令在第二条执行时仍写入' > '"+marker+"'";
  String second="printf '放行' > '"+release+"'; while [ ! -f '"+marker+"' ]; do sleep 0.01; done";
  var firstCall=new ToolCall("one","execute_command",mapper.createObjectNode().put("command",first).put("timeout_seconds",5).toString());
  var secondCall=new ToolCall("two","execute_command",mapper.createObjectNode().put("command",second).put("timeout_seconds",5).toString());
  var requests=new AtomicInteger();
  LlmClient fake=(messages, tools, listener, token) -> {
   if(requests.getAndIncrement()==0)return new ChatResponse("",List.of(firstCall,secondCall),TokenUsage.unknown());
   for(var msg: messages)if(msg.role()==ChatMessage.Role.TOOL)System.out.println("模型收到工具观察="+msg.content());
   return new ChatResponse("完成",TokenUsage.unknown());
  };
  var registry=new ToolRegistry(List.of(tool));
  var executor=new DefaultToolExecutor(registry,new ToolSchemaValidator(mapper),new ToolResultBudget(8000,mapper),mapper,System::nanoTime,new PathGuard(base),new TerminalHitlHandler(true,p -> "y",s -> {}),null);
  try(var scheduler=new ScheduledTimeoutScheduler()) {
   var agent=new ReactAgent(ChatMessage.system("测试"),fake,executor,registry.definitions(),new RunLimits(3,Duration.ofSeconds(5)),scheduler,mapper,Clock.systemUTC(),()->UUID.randomUUID().toString());
   agent.setConcurrencyLimits(4,Duration.ofMillis(100));
   var result=agent.run("安全超时探针",event->{
    if(event instanceof RunEvent.ToolCompleted done)System.out.println("真实工具完成事件="+done.status());
   },new CancellationToken());
   System.out.println("Agent结果="+result.status()+"; 超时命令后续仍写入="+Files.readString(marker));
  }
 }
}
