import com.xhlcli.snapshot.*;
import com.xhlcli.runtime.task.*;
import com.xhlcli.parallel.*;
import com.xhlcli.model.*;
import com.xhlcli.llm.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public class XhlReviewProbe {
  public static void main(String[] args) throws Exception {
    Path base = Files.createTempDirectory("xhl-review-");
    System.out.println("临时证据目录=" + base);
    snapshotSymlink(base);
    concurrentRecovery(base);
    wrongWorkspace(base);
    timeoutContinuation();
  }
  static void snapshotSymlink(Path base) throws Exception {
    Path work = Files.createDirectories(base.resolve("snapshot-work"));
    Path outside = Files.writeString(base.resolve("outside.txt"), "外部原始内容");
    Path file = Files.writeString(work.resolve("safe.txt"), "快照内容");
    var manager = new SideGitManager(work, new SnapshotConfig(true, base.resolve("history"), 50, List.of(".git")));
    manager.preTurnSnapshot("one", "测试");
    Files.delete(file);
    Files.createSymbolicLink(file, outside);
    var restored = manager.restorePreTurn(1);
    System.out.println("快照恢复成功=" + restored.success() + "; 外部文件内容=" + Files.readString(outside) + "; 工作区文件仍为符号链接=" + Files.isSymbolicLink(file));
  }
  static void concurrentRecovery(Path base) throws Exception {
    Path db = base.resolve("live-tasks.db");
    var firstStarted = new CountDownLatch(1);
    var secondStarted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var first = new DurableTaskManager(db, prompt -> { firstStarted.countDown(); release.await(); return "第一实例执行"; }, 1, "工作区一")) {
      first.start();
      var task = first.enqueue("同一任务");
      if (!firstStarted.await(2, TimeUnit.SECONDS)) throw new AssertionError("第一实例未启动");
      try (var second = new DurableTaskManager(db, prompt -> { secondStarted.countDown(); release.await(); return "第二实例重复执行"; }, 1, "工作区二")) {
        System.out.println("第一实例仍在运行时，新实例构造后任务状态=" + second.find(task.id()).orElseThrow().status());
        second.start();
        System.out.println("同一任务第二实例也开始执行=" + secondStarted.await(2, TimeUnit.SECONDS));
        release.countDown();
      } finally { release.countDown(); }
    }
  }
  static void wrongWorkspace(Path base) throws Exception {
    Path db = base.resolve("workspace-tasks.db");
    try (var source = new DurableTaskManager(db, prompt -> "不会启动", 1, "A")) { source.enqueue("应在 A 执行"); }
    var ran = new CountDownLatch(1);
    try (var target = new DurableTaskManager(db, prompt -> { System.out.println("工作区 B 的 runner 执行了=" + prompt); ran.countDown(); return "B"; }, 1, "B")) {
      target.start();
      if (!ran.await(2, TimeUnit.SECONDS)) throw new AssertionError("跨工作区复现未执行");
    }
  }
  static void timeoutContinuation() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var mutated = new CountDownLatch(1);
    var token = new CancellationToken();
    try (var exec = new BoundedParallelExecutor(1)) {
      var result = exec.executeBatch(new ParallelBatch(false, List.of(new IndexedToolCall(0, new ToolCall("probe", "write_file", "{}")))), indexed -> {
        started.countDown();
        try { release.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
        mutated.countDown();
        return BoundedParallelExecutor.failureResult(indexed.call(), ToolResultStatus.SUCCESS, "超时后仍执行", 0);
      }, Duration.ofMillis(30), token);
      System.out.println("调度器返回=" + result.get(0).status() + "; 已开始=" + (started.getCount()==0) + "; token已取消=" + token.isCancelled());
      release.countDown();
      System.out.println("调度器超时返回后任务仍继续=" + mutated.await(2, TimeUnit.SECONDS));
    }
  }
}
