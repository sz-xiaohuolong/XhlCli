package com.xhlcli.runtime.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ScheduledTaskServiceTest {
    @TempDir Path dir;
    static final class MutableClock extends Clock {
        Instant now=Instant.parse("2026-11-06T07:59:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    @Test void dailyTimeHandlesDstWithoutRepeatingAnOccurrence() {
        assertEquals(Instant.parse("2026-03-08T10:30:00Z"),DailySchedule.next("02:30","America/Los_Angeles",Instant.parse("2026-03-08T08:00:00Z")));
        assertEquals(Instant.parse("2026-11-02T09:30:00Z"),DailySchedule.next("01:30","America/Los_Angeles",Instant.parse("2026-11-01T08:30:00Z")));
        assertThrows(IllegalArgumentException.class,()->DailySchedule.next("25:00","UTC",Instant.now()));
    }
    @Test void dueRunsPersistOnceAndMissedTimesAreSkipped() throws Exception {
        var clock=new MutableClock();var calls=new AtomicInteger();String id;
        try(var service=new ScheduledTaskService(dir.resolve("s.db"),dir,clock,(execution,model)->{calls.incrementAndGet();return new TaskOutcome(TaskStatus.COMPLETED,"新闻结果","");},false)) {
            var schedule=service.save(null,"早间新闻","抓取新闻",dir,"test:model","08:00","UTC");id=schedule.id();
            service.tick();assertTrue(service.runs(id).isEmpty());
            clock.now=Instant.parse("2026-11-09T08:01:00Z");service.tick();assertTrue(service.runs(id).isEmpty());
            assertEquals("2026-11-10T08:00:00Z",service.list().getFirst().nextRun());
            clock.now=Instant.parse("2026-11-10T07:59:59Z");service.tick();
            clock.now=Instant.parse("2026-11-10T08:00:01Z");service.tick();service.tick();
            awaitCompleted(service,id);assertEquals(1,service.runs(id).size());assertEquals(1,calls.get());
            assertEquals("2026-11-10T08:00:00Z",service.runs(id).getFirst().scheduledAt());
            assertEquals("2026-11-11T08:00:00Z",service.list().getFirst().nextRun());
        }
        clock.now=Instant.parse("2026-11-12T09:00:00Z");
        try(var service=new ScheduledTaskService(dir.resolve("s.db"),dir,clock,(e,m)->{fail("不得重复执行或补跑");return null;},false)) {
            assertEquals("2026-11-13T08:00:00Z",service.list().getFirst().nextRun());
            service.tick();assertEquals(1,service.runs(id).size());assertEquals("新闻结果",service.runs(id).getFirst().result());
        }
    }
    @Test void pauseResumeManualAndInvalidEditsPreserveContract() throws Exception {
        var clock=new MutableClock();
        try(var service=new ScheduledTaskService(dir.resolve("s.db"),dir,clock,(e,m)->new TaskOutcome(TaskStatus.COMPLETED,m,""),false)) {
            String id=service.save(null,"新闻","任务",dir,"test:model","08:00","UTC").id();
            service.setEnabled(id,false);clock.now=Instant.parse("2026-11-07T09:00:00Z");service.tick();assertTrue(service.runs(id).isEmpty());
            service.setEnabled(id,true);assertEquals("2026-11-08T08:00:00Z",service.list().getFirst().nextRun());
            assertThrows(IllegalArgumentException.class,()->service.save(id,"变更","任务",dir,"test:model","无效","UTC"));
            assertEquals("新闻",service.list().getFirst().title());
            service.runNow(id);awaitCompleted(service,id);assertEquals("2026-11-08T08:00:00Z",service.list().getFirst().nextRun());
            service.delete(id);assertTrue(service.list().isEmpty());
        }
    }
    @Test void cancellationStopsRealTaskWithoutSqliteReaderLock() throws Exception {
        var started=new java.util.concurrent.CountDownLatch(1);
        try(var service=new ScheduledTaskService(dir.resolve("s.db"),dir,new MutableClock(),(execution,model)->{
            started.countDown();while(!execution.cancellation().isCancelled())Thread.sleep(10);
            return new TaskOutcome(TaskStatus.CANCELED,"部分新闻","");
        },false)) {
            String id=service.save(null,"新闻","任务",dir,"test:model","08:00","UTC").id();service.runNow(id);
            assertTrue(started.await(3,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,()->service.runNow(id));
            assertThrows(IllegalStateException.class,()->service.delete(id));
            service.cancel(id,service.runs(id).getFirst().taskId());
            long end=System.nanoTime()+Duration.ofSeconds(3).toNanos();
            while(System.nanoTime()<end&&!service.runs(id).getFirst().status().equals("canceled"))Thread.sleep(10);
            assertEquals("canceled",service.runs(id).getFirst().status());
        }
    }
    @Test void restartFailsLostRunButPreservesQueuedWork() throws Exception {
        var clock=new MutableClock();Path workspace=Files.createDirectory(dir.resolve("project")).toRealPath();String lost,queued;
        try(var service=new ScheduledTaskService(dir.resolve("s.db"),dir,clock,(e,m)->new TaskOutcome(TaskStatus.COMPLETED,"已恢复排队", ""),false)) {
            lost=service.save(null,"失联任务","任务",workspace,"test:model","08:00","UTC").id();
            queued=service.save(null,"未开始任务","任务",workspace,"test:model","08:00","UTC").id();
        }
        try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("s.db"))) {
            for(String id:java.util.List.of(lost,queued)) {
                try(var p=c.prepareStatement("INSERT INTO task_schedule_runs VALUES(?,?,?,0,'test:model')")) {p.setString(1,id);p.setString(2,id);p.setString(3,clock.now.toString());p.executeUpdate();}
                try(var p=c.prepareStatement("INSERT INTO runtime_tasks(id,status,prompt,workspace,created_at) VALUES(?,?,'任务',?,?)")) {p.setString(1,id);p.setString(2,id.equals(lost)?"running":"enqueued");p.setString(3,workspace.toString());p.setString(4,clock.now.toString());p.executeUpdate();}
            }
        }
        try(var service=new ScheduledTaskService(dir.resolve("s.db"),dir,clock,(e,m)->new TaskOutcome(TaskStatus.COMPLETED,"已恢复排队", ""),false)) {
            service.tick();awaitCompleted(service,queued);
            assertEquals("failed",service.runs(lost).getFirst().status());assertTrue(service.runs(lost).getFirst().error().contains("失联"));
        }
    }
    @Test void disappearingWorkspaceDoesNotHideOrOrphanRunningTask() throws Exception {
        Path workspace=Files.createDirectory(dir.resolve("project"));
        var started=new java.util.concurrent.CountDownLatch(1);
        try(var service=new ScheduledTaskService(dir.resolve("s.db"),dir,new MutableClock(),(execution,model)->{
            started.countDown();while(!execution.cancellation().isCancelled())Thread.sleep(10);
            return new TaskOutcome(TaskStatus.CANCELED,"","");
        },false)) {
            String id=service.save(null,"新闻","任务",workspace,"test:model","08:00","UTC").id();service.runNow(id);
            assertTrue(started.await(3,java.util.concurrent.TimeUnit.SECONDS));Files.delete(workspace);service.tick();
            assertTrue(service.list().getFirst().active(),"目录失联不能把仍运行的任务伪装为已结束");
            service.cancel(id,service.runs(id).getFirst().taskId());
            long end=System.nanoTime()+Duration.ofSeconds(3).toNanos();
            while(System.nanoTime()<end&&!service.runs(id).getFirst().status().equals("canceled"))Thread.sleep(10);
            assertEquals("canceled",service.runs(id).getFirst().status());
        }
    }
    @Test void unavailableWorkspaceRecordsOneFailureAndAdvancesSchedule() throws Exception {
        Path workspace=Files.createDirectory(dir.resolve("project"));var clock=new MutableClock();
        try(var service=new ScheduledTaskService(dir.resolve("s.db"),dir,clock,(e,m)->{fail("目录失联不能调用模型");return null;},false)) {
            String id=service.save(null,"新闻","任务",workspace,"test:model","08:00","UTC").id();Files.delete(workspace);
            clock.now=Instant.parse("2026-11-06T07:59:59Z");service.tick();
            clock.now=Instant.parse("2026-11-06T08:00:01Z");service.tick();service.tick();
            assertEquals(1,service.runs(id).size());assertEquals("failed",service.runs(id).getFirst().status());
            assertEquals("2026-11-07T08:00:00Z",service.list().getFirst().nextRun());
        }
    }
    @Test void wakingShortlyAfterDueTimeSkipsInsteadOfCatchingUp() throws Exception {
        var clock=new MutableClock();
        try(var service=new ScheduledTaskService(dir.resolve("s.db"),dir,clock,(e,m)->{fail("休眠错过的计划不得补跑");return null;},false)) {
            String id=service.save(null,"新闻","任务",dir,"test:model","08:00","UTC").id();
            clock.now=Instant.parse("2026-11-06T07:59:58Z");service.tick();
            clock.now=Instant.parse("2026-11-06T08:00:10Z");service.tick();
            assertTrue(service.runs(id).isEmpty());assertEquals("2026-11-07T08:00:00Z",service.list().getFirst().nextRun());
        }
    }
    private void awaitCompleted(ScheduledTaskService service,String id) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        while(System.nanoTime()<end){if(!service.runs(id).isEmpty()&&service.runs(id).getFirst().status().equals("completed"))return;Thread.sleep(15);}
        fail("任务未完成："+service.runs(id));
    }
}
