import com.xhlcli.parallel.*;
import com.xhlcli.tool.local.*;
import com.xhlcli.llm.*;
import com.xhlcli.model.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public class XhlCommandTimeoutProbe {
 public static void main(String[] args) throws Exception {
  Path base=Files.createTempDirectory("xhl-command-timeout-");
  Path release=base.resolve("release"), marker=base.resolve("late-write");
  var tool = new ExecuteCommandTool(new WorkspacePathResolver(base));
  var mapper = new ObjectMapper();
  String command = "while [ ! -f '"+release+"' ]; do sleep 0.01; done; printf '超时后真实命令仍写入' > '"+marker+"'";
  var call = new ToolCall("one", "execute_command", mapper.createObjectNode().put("command",command).put("timeout_seconds",5).toString());
  var token = new CancellationToken();
  var actuallyCompleted = new CountDownLatch(1);
  try(var exec=new BoundedParallelExecutor(2)) {
   var result=exec.executeBatch(ParallelBatch.sequential(new IndexedToolCall(0,call)), c -> {
    try { tool.execute(mapper.readTree(c.call().argumentsJson()), token); actuallyCompleted.countDown(); return BoundedParallelExecutor.failureResult(c.call(),ToolResultStatus.SUCCESS,"完成",0); }
    catch(Exception e){throw new RuntimeException(e);}
   },Duration.ofMillis(100),token);
   System.out.println("真实命令调度结果="+result.get(0).status()+"; token已取消="+token.isCancelled()+"; 此时文件存在="+Files.exists(marker));
   Files.writeString(release,"允许安全测试命令继续");
   if(!actuallyCompleted.await(2,TimeUnit.SECONDS))throw new AssertionError("命令未完成");
   System.out.println("超时后真实命令写入文件="+Files.readString(marker));
  }
 }
}
