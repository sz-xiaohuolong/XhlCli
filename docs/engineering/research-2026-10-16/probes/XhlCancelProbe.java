import com.xhlcli.agent.*;
import com.xhlcli.runtime.task.*;
import com.xhlcli.llm.*;
import com.xhlcli.model.*;
import com.xhlcli.tool.local.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class XhlCancelProbe {
  public static void main(String[] args) throws Exception {
    var foregroundStarted = new CountDownLatch(1);
    var releaseForeground = new CountDownLatch(1);
    var backgroundEnteredRunner = new CountDownLatch(1);
    var canceledBackgroundModelStarted = new CountDownLatch(1);
    LlmClient fake = (messages, tools, listener, token) -> {
      String last = messages.get(messages.size()-1).content();
      if (last.equals("前台")) {
        foregroundStarted.countDown();
        try { releaseForeground.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
      } else if (last.equals("后台")) {
        System.out.println("取消后后台仍发模型请求=true; 模型令牌已取消=" + token.isCancelled());
        canceledBackgroundModelStarted.countDown();
      }
      return new ChatResponse("完成", TokenUsage.unknown());
    };
    try (var scheduler = new ScheduledTimeoutScheduler()) {
      var agent = new ReactAgent(ChatMessage.system("测试"), fake,
        (call, token) -> { throw new AssertionError("无需工具"); }, List.of(),
        new RunLimits(3, Duration.ofSeconds(5)), scheduler, new ObjectMapper(), Clock.systemUTC(), () -> UUID.randomUUID().toString());
      var foreground = new Thread(() -> agent.run("前台", e -> {}, new CancellationToken()));
      foreground.start();
      if (!foregroundStarted.await(2, TimeUnit.SECONDS)) throw new AssertionError("前台未进入");
      Path db = Files.createTempDirectory("xhl-cancel-").resolve("tasks.db");
      try (var manager = new DurableTaskManager(db, prompt -> {
        backgroundEnteredRunner.countDown();
        return agent.run(prompt, e -> {}, new CancellationToken()).finalAnswer();
      }, 1)) {
        manager.start();
        var task = manager.enqueue("后台");
        if (!backgroundEnteredRunner.await(2, TimeUnit.SECONDS)) throw new AssertionError("后台未进入 runner");
        manager.cancel(task.id());
        System.out.println("取消已返回，持久状态=" + manager.find(task.id()).orElseThrow().status());
        releaseForeground.countDown();
        System.out.println("取消任务仍执行模型=" + canceledBackgroundModelStarted.await(2, TimeUnit.SECONDS));
      } finally { releaseForeground.countDown(); foreground.join(1000); }
    }
  }
}
