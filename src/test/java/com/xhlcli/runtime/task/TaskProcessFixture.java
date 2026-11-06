package com.xhlcli.runtime.task;

import java.nio.file.*;
import java.util.concurrent.*;

/** 跨 JVM 验收入口，只访问测试显式传入的临时目录。 */
public final class TaskProcessFixture {
    public static void main(String[] args) throws Exception {
        var done = new CountDownLatch(1);
        try (var manager = new DurableTaskManager(Path.of(args[0]), Path.of(args[1]), execution -> {
            execution.progress().accept("崩溃前的结果");
            Files.writeString(Path.of(args[2]), execution.id());
            try (var registration = execution.cancellation().onCancel(done::countDown)) {
                done.await();
                return new TaskOutcome(TaskStatus.CANCELED, "", "用户取消");
            }
        }, 1)) {
            manager.enqueue("跨进程任务");
            manager.start();
            new CountDownLatch(1).await();
        }
    }
}
