package com.xhlcli.runtime.task;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 定时层只决定何时入队；运行、取消和结果仍由 DurableTaskManager 管理。 */
public final class ScheduledTaskService implements AutoCloseable {
    @FunctionalInterface public interface Runner { TaskOutcome run(TaskExecution execution,String model) throws Exception; }
    public record Schedule(String id,String title,String prompt,String workspace,String model,String time,String zone,
                           boolean enabled,String nextRun,boolean active) {}
    public record Run(String taskId,String scheduledAt,boolean manual,String status,String result,String error,
                      String createdAt,String startedAt,String finishedAt) {}
    private final Path db;
    private final Clock clock;
    private final Runner runner;
    private final Connection connection;
    private final Map<String,DurableTaskManager> managers=new HashMap<>();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"xhlcli-schedules");t.setDaemon(true);return t;});
    private volatile boolean closed;
    private volatile String error="";
    private Instant lastTick;

    public ScheduledTaskService(Path db,Path home,Clock clock,Runner runner,boolean start) throws Exception {
        this.db=db.toAbsolutePath();this.clock=clock;this.runner=runner;
        // 沿用原 task 表及兼容检查；桌面使用独立数据库，不接管 CLI 的队列。
        try(var bootstrap=new DurableTaskManager(this.db,home,e->new TaskOutcome(TaskStatus.FAILED,"","无执行器"),1)) {}
        connection=DriverManager.getConnection("jdbc:sqlite:"+this.db);
        try(var s=connection.createStatement()) {
            s.execute("PRAGMA busy_timeout=3000");
            s.execute("""
                CREATE TABLE IF NOT EXISTS task_schedules(
                  id TEXT PRIMARY KEY,title TEXT NOT NULL,prompt TEXT NOT NULL,workspace TEXT NOT NULL,
                  model TEXT NOT NULL,time TEXT NOT NULL,zone TEXT NOT NULL,enabled INTEGER NOT NULL,next_run TEXT NOT NULL)
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS task_schedule_runs(
                  task_id TEXT PRIMARY KEY,schedule_id TEXT NOT NULL,scheduled_at TEXT NOT NULL,manual INTEGER NOT NULL,model TEXT NOT NULL)
                """);
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS schedule_occurrence ON task_schedule_runs(schedule_id,scheduled_at) WHERE manual=0");
        }catch(Exception failure){connection.close();timer.shutdownNow();throw failure;}
        // 启动前跳过停机期间错过的计划，不补跑。
        skipOverdue(clock.instant());
        lastTick=clock.instant();
        if(start)timer.scheduleWithFixedDelay(()->{try{tick();error="";}catch(Exception failure){error="定时任务调度失败："+failure.getClass().getSimpleName()+"；将自动重试";}},0,1,TimeUnit.SECONDS);
    }
    public String error(){return error;}
    public synchronized List<Schedule> list() throws SQLException {
        var list=new ArrayList<Schedule>();
        try(var s=connection.prepareStatement("SELECT * FROM task_schedules ORDER BY rowid" );var rows=s.executeQuery()) {
            while(rows.next())list.add(new Schedule(rows.getString("id"),rows.getString("title"),rows.getString("prompt"),rows.getString("workspace"),
                    rows.getString("model"),rows.getString("time"),rows.getString("zone"),rows.getBoolean("enabled"),rows.getString("next_run"),active(rows.getString("id"))));
        }
        return list;
    }
    public synchronized Schedule save(String id,String title,String prompt,Path workspace,String model,String time,String zone) throws Exception {
        checkOpen();
        if(title==null||title.isBlank()||title.length()>160)throw new IllegalArgumentException("任务名称需要 1～160 个字符");
        if(prompt==null||prompt.isBlank()||prompt.length()>20000)throw new IllegalArgumentException("任务内容需要 1～20000 个字符");
        if(model==null||!model.matches("[^: ]+:.+")||model.length()>240)throw new IllegalArgumentException("请选择有效模型");
        Path canonical=workspace.toRealPath();if(!Files.isDirectory(canonical)||!Files.isReadable(canonical))throw new IllegalArgumentException("项目目录不可读");
        String next=DailySchedule.next(time,zone,clock.instant()).toString();
        boolean enabled=true;
        if(id!=null&&!id.isBlank()){var old=require(id);if(active(id))throw new IllegalStateException("请等待当前执行结束再编辑任务");enabled=old.enabled();}
        else id=UUID.randomUUID().toString();
        try(var p=connection.prepareStatement("""
            INSERT INTO task_schedules VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET
            title=excluded.title,prompt=excluded.prompt,workspace=excluded.workspace,model=excluded.model,
            time=excluded.time,zone=excluded.zone,next_run=excluded.next_run
            """)) {
            p.setString(1,id);p.setString(2,title.strip());p.setString(3,prompt.strip());p.setString(4,canonical.toString());p.setString(5,model);
            p.setString(6,time);p.setString(7,zone);p.setBoolean(8,enabled);p.setString(9,next);p.executeUpdate();
        }
        return require(id);
    }
    public synchronized void setEnabled(String id,boolean enabled) throws Exception {
        checkOpen();var item=require(id);
        try(var p=connection.prepareStatement("UPDATE task_schedules SET enabled=?,next_run=? WHERE id=?")) {
            p.setBoolean(1,enabled);p.setString(2,enabled&&!item.enabled()?DailySchedule.next(item.time(),item.zone(),clock.instant()).toString():item.nextRun());
            p.setString(3,id);p.executeUpdate();
        }
    }
    public synchronized void delete(String id) throws Exception {
        checkOpen();require(id);if(active(id))throw new IllegalStateException("请先停止当前执行，结束后再删除任务");
        connection.setAutoCommit(false);
        try {
            execute("DELETE FROM runtime_tasks WHERE id IN(SELECT task_id FROM task_schedule_runs WHERE schedule_id=?)",id);
            execute("DELETE FROM task_schedule_runs WHERE schedule_id=?",id);execute("DELETE FROM task_schedules WHERE id=?",id);connection.commit();
        }catch(Exception e){connection.rollback();throw e;}finally{connection.setAutoCommit(true);}
    }
    public synchronized List<Run> runs(String id) throws Exception {
        require(id);var result=new ArrayList<Run>();
        try(var p=connection.prepareStatement("""
            SELECT r.*,t.status,t.result,t.error,t.created_at,t.started_at,t.finished_at FROM task_schedule_runs r
            JOIN runtime_tasks t ON t.id=r.task_id WHERE r.schedule_id=? ORDER BY t.created_at DESC,t.rowid DESC LIMIT 100
            """)) {
            p.setString(1,id);try(var rows=p.executeQuery()){while(rows.next())result.add(new Run(rows.getString("task_id"),rows.getString("scheduled_at"),rows.getBoolean("manual"),
                    rows.getString("status"),Objects.toString(rows.getString("result"),""),Objects.toString(rows.getString("error"),""),rows.getString("created_at"),rows.getString("started_at"),rows.getString("finished_at")));}
        }
        return result;
    }
    public synchronized void runNow(String id) throws Exception {
        checkOpen();var item=require(id);if(active(id))throw new IllegalStateException("该任务已有执行，请等待完成或先停止");
        enqueue(item,true,clock.instant());
    }
    public synchronized void cancel(String id,String taskId) throws Exception {
        checkOpen();require(id);
        String workspace;
        try(var p=connection.prepareStatement("SELECT t.workspace FROM task_schedule_runs r JOIN runtime_tasks t ON t.id=r.task_id WHERE r.schedule_id=? AND r.task_id=?")) {
            p.setString(1,id);p.setString(2,taskId);try(var rows=p.executeQuery()) {
                if(!rows.next())throw new IllegalArgumentException("执行记录不存在");
                workspace=rows.getString(1);
            }
        }
        // 关闭读游标后再调用另一个连接写库，避免 SQLite 读锁阻止取消提交。
        var manager=managers.get(workspace);
        if(manager!=null)manager.cancel(taskId);
        else execute("UPDATE runtime_tasks SET status='canceled',error='用户取消，尚未执行' WHERE id=? AND status='enqueued'",taskId);
    }
    public synchronized void tick() throws Exception {
        if(closed)return;
        // 重启时先恢复已有队列。失联的运行任务交由原 task 机制标失败，不自动重做。
        var pendingWorkspaces=new ArrayList<String>();
        try(var p=connection.prepareStatement("SELECT DISTINCT workspace FROM runtime_tasks WHERE status IN('enqueued','running','cancel_requested')");var rows=p.executeQuery()) {
            while(rows.next())pendingWorkspaces.add(rows.getString(1));
        }
        for(String workspace:pendingWorkspaces) {
            // 已有执行器持有任务所有权；目录失联也必须保留真实运行状态与取消入口。
            if(managers.containsKey(workspace))continue;
            if(accessible(workspace))manager(workspace).start();else failUnavailable(workspace);
        }
        Instant now=clock.instant();
        boolean interrupted=Duration.between(lastTick,now).compareTo(Duration.ofSeconds(5))>0;
        lastTick=now;
        if(interrupted){skipOverdue(now);return;}
        for(var item:list()) {
            Instant due=Instant.parse(item.nextRun());
            if(!item.enabled()||due.isAfter(now))continue;
            // 一秒轮询允许少量调度延迟；迟到超过一分钟或上一轮未结束时跳过本次。
            if(Duration.between(due,now).compareTo(Duration.ofMinutes(1))>=0||active(item.id()))advance(item,now);
            else enqueue(item,false,now);
        }
    }
    private void skipOverdue(Instant now) throws SQLException {
        for(var item:list())if(!Instant.parse(item.nextRun()).isAfter(now))advance(item,now);
    }
    private void advance(Schedule item,Instant now) throws SQLException {
        try(var p=connection.prepareStatement("UPDATE task_schedules SET next_run=? WHERE id=?")) {
            p.setString(1,DailySchedule.next(item.time(),item.zone(),now).toString());p.setString(2,item.id());p.executeUpdate();
        }
    }
    private void enqueue(Schedule item,boolean manual,Instant now) throws Exception {
        DurableTaskManager manager=accessible(item.workspace())?manager(item.workspace()):null;
        String id="scheduled_"+UUID.randomUUID();
        String at=manual?now.toString():DailySchedule.latest(item.time(),item.zone(),now).toString();
        connection.setAutoCommit(false);
        try {
            try(var p=connection.prepareStatement("INSERT INTO task_schedule_runs VALUES(?,?,?,?,?)")) {
                p.setString(1,id);p.setString(2,item.id());p.setString(3,at);p.setBoolean(4,manual);p.setString(5,item.model());p.executeUpdate();
            }
            try(var p=connection.prepareStatement("INSERT INTO runtime_tasks(id,status,prompt,workspace,result,error,created_at,updated_at,finished_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
                p.setString(1,id);p.setString(2,manager==null?"failed":"enqueued");p.setString(3,item.prompt());p.setString(4,item.workspace());p.setString(5,"");
                p.setString(6,manager==null?"项目不可访问或任务队列不可用，请检查目录后重试":"");p.setString(7,now.toString());p.setString(8,now.toString());p.setString(9,manager==null?now.toString():null);p.executeUpdate();
            }
            if(!manual)try(var p=connection.prepareStatement("UPDATE task_schedules SET next_run=? WHERE id=?")) {
                p.setString(1,DailySchedule.next(item.time(),item.zone(),now).toString());p.setString(2,item.id());p.executeUpdate();
            }
            connection.commit();
        }catch(Exception e){connection.rollback();throw e;}finally{connection.setAutoCommit(true);}
        if(manager!=null)manager.start();
    }
    private boolean accessible(String workspace) {return Files.isDirectory(Path.of(workspace))&&Files.isReadable(Path.of(workspace));}
    private DurableTaskManager manager(String workspace) throws Exception {
        var existing=managers.get(workspace);if(existing!=null)return existing;
        var manager=new DurableTaskManager(db,Path.of(workspace),execution->runner.run(execution,model(execution.id())),1);
        managers.put(workspace,manager);return manager;
    }
    private synchronized String model(String taskId) throws SQLException {
        try(var p=connection.prepareStatement("SELECT model FROM task_schedule_runs WHERE task_id=?")) {
            p.setString(1,taskId);try(var rows=p.executeQuery()){if(!rows.next())throw new IllegalArgumentException("定时执行记录不存在");return rows.getString(1);}
        }
    }
    private void failUnavailable(String workspace) throws SQLException {
        try(var p=connection.prepareStatement("UPDATE runtime_tasks SET status='failed',error='项目目录不可访问，未自动重试',finished_at=? WHERE workspace=? AND status IN('enqueued','running','cancel_requested')")) {
            p.setString(1,clock.instant().toString());p.setString(2,workspace);p.executeUpdate();
        }
    }
    private boolean active(String id) throws SQLException {
        try(var p=connection.prepareStatement("SELECT 1 FROM task_schedule_runs r JOIN runtime_tasks t ON r.task_id=t.id WHERE r.schedule_id=? AND t.status IN('enqueued','running','cancel_requested','waiting_for_approval') LIMIT 1")) {
            p.setString(1,id);try(var rows=p.executeQuery()){return rows.next();}
        }
    }
    private Schedule require(String id) throws SQLException {
        return list().stream().filter(s->s.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("定时任务不存在"));
    }
    private void execute(String sql,String value) throws SQLException {try(var p=connection.prepareStatement(sql)){p.setString(1,value);p.executeUpdate();}}
    private void checkOpen(){if(closed)throw new IllegalStateException("定时任务服务正在退出");}
    @Override public void close() throws Exception {
        List<DurableTaskManager> all;
        synchronized(this){closed=true;all=List.copyOf(managers.values());}
        timer.shutdownNow();for(var manager:all)manager.close();
        synchronized(this){connection.close();}
    }
}
