package com.xhlcli.runtime.task;

import com.xhlcli.llm.CancellationToken;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** 工作区限定的本机持久任务队列；数据库互斥不覆盖模型执行和资源清理。 */
public class DurableTaskManager implements Closeable {
    private final Path dbPath;
    private final Path lockDirectory;
    private final ManagedTaskRunner runner;
    private final int workerCount;
    private final String defaultWorkspace;
    private final Connection connection;
    private final Map<String, TaskExecution> runningTasks = new ConcurrentHashMap<>();
    private final AtomicInteger remainingWorkers = new AtomicInteger();
    private ExecutorService workers;
    private ScheduledExecutorService controller;
    private volatile boolean running;
    private volatile boolean closing;
    private volatile String lastError = "";
    private volatile Consumer<DurableTask> completionListener = task -> {};

    public DurableTaskManager(Path dbPath, TaskRunner runner) throws SQLException {
        this(dbPath, runner, workerCount());
    }

    public DurableTaskManager(Path dbPath, TaskRunner runner, int workerCount) throws SQLException {
        this(dbPath, runner, workerCount, Path.of(".").toAbsolutePath().toString());
    }

    public DurableTaskManager(Path dbPath, TaskRunner runner, int workerCount, String workspace) throws SQLException {
        this(dbPath, Path.of(workspace), adapt(runner), workerCount);
    }

    public DurableTaskManager(Path dbPath, Path workspace, ManagedTaskRunner runner, int workerCount) throws SQLException {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.workerCount = Math.max(1, Math.min(workerCount, 16));
        try {
            defaultWorkspace = canonicalWorkspace(workspace);
            Path absolute = dbPath.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            this.dbPath = Files.exists(absolute) ? absolute.toRealPath()
                    : absolute.getParent().toRealPath().resolve(absolute.getFileName());
            lockDirectory = this.dbPath.resolveSibling(this.dbPath.getFileName() + ".locks");
            Files.createDirectories(lockDirectory);
        } catch (IOException e) {
            throw new SQLException("无法初始化后台任务路径: " + e.getMessage(), e);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + this.dbPath);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout = 3000");
            }
            validateExistingSchema();
            initTables();
            recoverRunningTasks();
        } catch (Exception e) {
            connection.close();
            throw new SQLException("后台任务库初始化失败: " + e.getMessage(), e);
        }
    }

    private static ManagedTaskRunner adapt(TaskRunner runner) {
        Objects.requireNonNull(runner, "runner");
        return execution -> {
            Thread thread = Thread.currentThread();
            try (var registration = execution.cancellation().onCancel(thread::interrupt)) {
                if (execution.cancellation().isCancelled()) return new TaskOutcome(TaskStatus.CANCELED, "", "用户取消");
                return new TaskOutcome(TaskStatus.COMPLETED, runner.run(execution.prompt()), "");
            }
        };
    }

    public static DurableTaskManager openDefault(TaskRunner runner) throws SQLException {
        return new DurableTaskManager(defaultDbPath(), runner, workerCount());
    }

    public static DurableTaskManager open(Path dbPath, Path workspace, ManagedTaskRunner runner) throws SQLException {
        return new DurableTaskManager(dbPath, workspace, runner, workerCount());
    }

    public static Path defaultDbPath() {
        String configured = System.getProperty("xhlcli.task.dir");
        if (configured == null || configured.isBlank()) configured = System.getenv("XHLCLI_TASK_DIR");
        if (configured == null || configured.isBlank()) configured = Path.of(System.getProperty("user.home"), ".xhlcli", "tasks").toString();
        return Path.of(configured).resolve("tasks.db");
    }

    private static int workerCount() {
        String configured = System.getProperty("xhlcli.task.workers");
        if (configured == null || configured.isBlank()) configured = System.getenv("XHLCLI_TASK_WORKERS");
        try { return configured == null ? 2 : Math.max(1, Math.min(Integer.parseInt(configured.trim()), 16)); }
        catch (NumberFormatException e) { return 2; }
    }

    private static String canonicalWorkspace(Path workspace) throws IOException {
        Path canonical = workspace.toRealPath();
        if (!Files.isDirectory(canonical) || !Files.isReadable(canonical)) throw new IOException("工作区不是可读目录: " + workspace);
        return canonical.toString();
    }

    public synchronized void start() {
        if (closing) throw new IllegalStateException("后台任务管理器已关闭");
        if (running) return;
        running = true;
        controller = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "xhlcli-task-control"));
        controller.scheduleWithFixedDelay(this::checkCancellationAndRecovery, 0, 100, TimeUnit.MILLISECONDS);
        workers = Executors.newFixedThreadPool(workerCount, r -> daemon(r, "xhlcli-task-worker"));
        remainingWorkers.set(workerCount);
        for (int i = 0; i < workerCount; i++) workers.submit(this::workerLoop);
    }

    private static Thread daemon(Runnable action, String name) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        return thread;
    }

    public synchronized DurableTask enqueue(String prompt) { return enqueue(prompt, defaultWorkspace); }

    public synchronized DurableTask enqueue(String prompt, String workspace) {
        if (closing) throw new IllegalStateException("后台任务管理器已关闭");
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("任务内容不能为空");
        try {
            String canonical = canonicalWorkspace(Path.of(workspace == null || workspace.isBlank() ? defaultWorkspace : workspace));
            if (!defaultWorkspace.equals(canonical)) throw new IllegalArgumentException("不能向其他工作区提交任务");
        } catch (IOException e) { throw new IllegalArgumentException("工作区不可用", e); }
        String id = "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String now = Instant.now().toString();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO runtime_tasks (id, status, prompt, workspace, created_at, updated_at)
                VALUES (?, 'enqueued', ?, ?, ?, ?)
                """)) {
            ps.setString(1, id);
            ps.setString(2, prompt.trim());
            ps.setString(3, defaultWorkspace);
            ps.setString(4, now);
            ps.setString(5, now);
            ps.executeUpdate();
            return find(id).orElseThrow();
        } catch (SQLException e) { throw databaseFailure("提交后台任务失败", e); }
    }

    public synchronized List<DurableTask> list(int limit) {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM runtime_tasks WHERE workspace = ? ORDER BY created_at DESC, rowid DESC LIMIT ?
                """)) {
            ps.setString(1, defaultWorkspace);
            ps.setInt(2, Math.max(1, Math.min(limit, 100)));
            return rows(ps);
        } catch (SQLException e) { throw databaseFailure("读取后台任务失败", e); }
    }

    public synchronized Optional<DurableTask> find(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM runtime_tasks WHERE id = ? AND workspace = ?")) {
            ps.setString(1, id.trim());
            ps.setString(2, defaultWorkspace);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(fromRow(rs)) : Optional.empty(); }
        } catch (SQLException e) { throw databaseFailure("读取后台任务失败", e); }
    }

    public boolean cancel(String id) {
        if (id == null || id.isBlank()) return false;
        String taskId = id.trim();
        boolean changed;
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE runtime_tasks SET
                      status = CASE WHEN status = 'enqueued' THEN 'canceled' ELSE 'cancel_requested' END,
                      finished_at = CASE WHEN status = 'enqueued' THEN ? ELSE NULL END,
                      error = CASE WHEN status = 'enqueued' THEN '用户取消，尚未执行' ELSE '已请求取消，等待执行停止' END, updated_at = ?
                    WHERE id = ? AND workspace = ? AND status IN ('enqueued', 'running', 'cancel_requested', 'waiting_for_approval')
                    """)) {
                String now = Instant.now().toString();
                ps.setString(1, now); ps.setString(2, now); ps.setString(3, taskId); ps.setString(4, defaultWorkspace);
                changed = ps.executeUpdate() == 1;
            } catch (SQLException e) { throw databaseFailure("取消后台任务失败", e); }
        }
        // 回调可能执行网络清理，不能在数据库互斥区中调用。
        TaskExecution active = runningTasks.get(taskId);
        if (changed && active != null) active.cancellation().cancel();
        return changed;
    }

    public Path dbPath() { return dbPath; }
    public boolean isRunning() { return running; }
    public String lastError() { return lastError; }
    public void setCompletionListener(Consumer<DurableTask> listener) { completionListener = Objects.requireNonNull(listener); }

    private void workerLoop() {
        try {
            while (running) {
                try {
                    Claim claim = claimNext();
                    if (claim == null) { Thread.sleep(100); continue; }
                    execute(claim);
                } catch (InterruptedException e) {
                    if (!running) break;
                    Thread.interrupted();
                } catch (Exception e) {
                    lastError = "后台调度失败: " + e.getClass().getSimpleName();
                    try { Thread.sleep(100); } catch (InterruptedException interrupted) { Thread.interrupted(); }
                }
            }
        } finally {
            if (remainingWorkers.decrementAndGet() == 0 && closing) closeConnection();
        }
    }

    private void execute(Claim claim) throws IOException {
        DurableTask task = claim.task();
        TaskExecution execution = new TaskExecution(task.id(), task.prompt(), Path.of(defaultWorkspace),
                new CancellationToken(), text -> saveProgress(task.id(), text));
        try (TaskFileLock ignored = claim.lock()) {
            runningTasks.put(task.id(), execution);
            TaskOutcome outcome;
            try {
                if (!running) cancel(task.id());
                if (find(task.id()).orElseThrow().status() == TaskStatus.CANCEL_REQUESTED) execution.cancellation().cancel();
                if (!canonicalWorkspace(execution.workspace()).equals(defaultWorkspace)) throw new IOException("工作区身份发生变化");
                outcome = execution.cancellation().isCancelled()
                        ? new TaskOutcome(TaskStatus.CANCELED, "", "用户取消")
                        : Objects.requireNonNull(runner.run(execution), "任务未返回结果");
            } catch (InterruptedException e) {
                outcome = new TaskOutcome(execution.cancellation().isCancelled() ? TaskStatus.CANCELED : TaskStatus.FAILED,
                        "", "任务执行被中断");
            } catch (Exception e) {
                outcome = new TaskOutcome(TaskStatus.FAILED, "", "任务执行失败: " + e.getClass().getSimpleName());
            }
            Thread.interrupted();
            if (execution.cancellation().isCancelled()) outcome = new TaskOutcome(TaskStatus.CANCELED, "", "已请求取消，执行已停止");
            finish(task, outcome);
        } finally { runningTasks.remove(task.id()); }
        try { find(task.id()).ifPresent(completionListener); }
        catch (RuntimeException e) { lastError = "任务已保存，完成通知发送失败"; }
    }

    private synchronized Claim claimNext() throws IOException, SQLException {
        if (!running) return null;
        List<DurableTask> candidates;
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM runtime_tasks WHERE workspace = ? AND status = 'enqueued'
                ORDER BY created_at ASC, rowid ASC LIMIT 32
                """)) {
            ps.setString(1, defaultWorkspace);
            candidates = rows(ps);
        }
        for (DurableTask task : candidates) {
            TaskFileLock lock = TaskFileLock.tryAcquire(lockPath(task.id()));
            if (lock == null) continue;
            boolean claimed = false;
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE runtime_tasks SET status = 'running', started_at = ?, updated_at = ?
                    WHERE id = ? AND workspace = ? AND status = 'enqueued'
                    """)) {
                String now = Instant.now().toString();
                ps.setString(1, now); ps.setString(2, now); ps.setString(3, task.id()); ps.setString(4, defaultWorkspace);
                if (ps.executeUpdate() == 1) {
                    DurableTask current = find(task.id()).orElseThrow();
                    claimed = true;
                    return new Claim(current, lock);
                }
            } finally { if (!claimed) lock.close(); }
        }
        return null;
    }

    private synchronized void saveProgress(String id, String text) {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_tasks SET result = ?, updated_at = ?
                WHERE id = ? AND workspace = ? AND status = 'running'
                """)) {
            String bounded = text == null ? "" : text.substring(0, Math.min(text.length(), 65536));
            ps.setString(1, bounded); ps.setString(2, Instant.now().toString()); ps.setString(3, id); ps.setString(4, defaultWorkspace);
            ps.executeUpdate();
        } catch (SQLException e) { throw databaseFailure("保存任务进度失败", e); }
    }

    private synchronized void finish(DurableTask task, TaskOutcome outcome) {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_tasks SET
                  status = CASE WHEN status = 'cancel_requested' THEN 'canceled' ELSE ? END,
                  error = CASE WHEN status = 'cancel_requested' THEN '用户取消，执行已停止' ELSE ? END,
                  result = CASE WHEN status = 'cancel_requested' OR ? = '' THEN COALESCE(result, '') ELSE ? END,
                  finished_at = ?, updated_at = ?, duration_ms = ?
                WHERE id = ? AND workspace = ? AND status IN ('running', 'cancel_requested')
                """)) {
            String now = Instant.now().toString();
            ps.setString(1, outcome.status().value()); ps.setString(2, outcome.error());
            ps.setString(3, outcome.result()); ps.setString(4, outcome.result());
            ps.setString(5, now); ps.setString(6, now);
            ps.setLong(7, task.startedAt() == null ? 0 : Math.max(0, Instant.now().toEpochMilli() - task.startedAt().toEpochMilli()));
            ps.setString(8, task.id()); ps.setString(9, defaultWorkspace);
            ps.executeUpdate();
        } catch (SQLException e) { throw databaseFailure("保存任务结果失败", e); }
    }

    private void checkCancellationAndRecovery() {
        try {
            for (TaskExecution execution : runningTasks.values()) {
                if (find(execution.id()).map(t -> t.status() == TaskStatus.CANCEL_REQUESTED).orElse(true)) execution.cancellation().cancel();
            }
            recoverRunningTasks();
        } catch (Exception e) {
            lastError = "任务控制检查失败，已请求停止本实例执行: " + e.getClass().getSimpleName();
            runningTasks.values().forEach(execution -> execution.cancellation().cancel());
        }
    }

    private synchronized void recoverRunningTasks() throws SQLException, IOException {
        List<DurableTask> tasks;
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM runtime_tasks WHERE workspace = ? AND status IN ('running', 'cancel_requested', 'waiting_for_approval')
                """)) {
            ps.setString(1, defaultWorkspace);
            tasks = rows(ps);
        }
        for (DurableTask task : tasks) {
            try (TaskFileLock lock = TaskFileLock.tryAcquire(lockPath(task.id()))) {
                if (lock == null) continue;
                try (PreparedStatement ps = connection.prepareStatement("""
                        UPDATE runtime_tasks SET status = 'failed', error = ?, finished_at = ?, updated_at = ?
                        WHERE id = ? AND workspace = ? AND status IN ('running', 'cancel_requested', 'waiting_for_approval')
                        """)) {
                    String now = Instant.now().toString();
                    ps.setString(1, "执行进程已失联，结果不确定；未自动重试，请检查后重新提交。取消/远端操作是否完成无法确认。");
                    ps.setString(2, now); ps.setString(3, now); ps.setString(4, task.id()); ps.setString(5, defaultWorkspace);
                    ps.executeUpdate();
                }
            }
        }
    }

    private Path lockPath(String id) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            return lockDirectory.resolve(HexFormat.of().formatHex(digest) + ".lock");
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private List<DurableTask> rows(PreparedStatement ps) throws SQLException {
        List<DurableTask> tasks = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) { while (rs.next()) tasks.add(fromRow(rs)); }
        return tasks;
    }

    private IllegalStateException databaseFailure(String message, SQLException cause) {
        lastError = message;
        return new IllegalStateException(message, cause);
    }

    private void validateExistingSchema() throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("PRAGMA table_info(runtime_tasks)")) {
            while (rs.next()) columns.add(rs.getString("name"));
        }
        if (!columns.isEmpty() && !columns.containsAll(Set.of("id", "status", "prompt", "workspace", "result", "error", "created_at", "started_at", "finished_at", "updated_at", "duration_ms"))) {
            throw new SQLException("任务库结构不兼容；请备份并使用独立 XHLCLI_TASK_DIR，原库未迁移");
        }
    }

    private record Claim(DurableTask task, TaskFileLock lock) {}
    private void initTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS runtime_tasks (
                        id TEXT PRIMARY KEY,
                        status TEXT NOT NULL,
                        prompt TEXT NOT NULL,
                        workspace TEXT,
                        result TEXT,
                        error TEXT,
                        created_at TEXT NOT NULL,
                        started_at TEXT,
                        finished_at TEXT,
                        updated_at TEXT,
                        duration_ms INTEGER DEFAULT 0
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_runtime_tasks_status ON runtime_tasks(status)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_runtime_tasks_created ON runtime_tasks(created_at)");
        }
    }

    private DurableTask fromRow(ResultSet rs) throws SQLException {
        return new DurableTask(
                rs.getString("id"),
                TaskStatus.from(rs.getString("status")),
                rs.getString("prompt"),
                rs.getString("workspace"),
                rs.getString("result"),
                rs.getString("error"),
                parseInstant(rs.getString("created_at")),
                parseInstant(rs.getString("started_at")),
                parseInstant(rs.getString("finished_at")),
                rs.getLong("duration_ms")
        );
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }

    @Override public void close() {
        synchronized (this) {
            if (closing) return;
            closing = true;
            running = false;
        }
        if (controller != null) controller.shutdown();
        // 先停止全部本地执行，再逐项尝试持久化；一个数据库错误不能跳过其余资源。
        List<TaskExecution> active = List.copyOf(runningTasks.values());
        active.forEach(execution -> execution.cancellation().cancel());
        try {
            for (TaskExecution execution : active) {
                try { cancel(execution.id()); }
                catch (RuntimeException e) { lastError = "关闭时持久化取消失败，已请求停止本地执行"; }
            }
        } finally {
            if (workers != null) {
                workers.shutdown();
                try { workers.awaitTermination(2, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
        // 未退出的执行保留连接与所有权锁，不能把超时等待误报为已经停止。
        if (remainingWorkers.get() == 0) closeConnection();
    }

    private synchronized void closeConnection() {
        try { if (!connection.isClosed()) connection.close(); }
        catch (SQLException e) { lastError = "关闭任务数据库失败"; }
    }
}
