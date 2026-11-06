package com.xhlcli.runtime.task;

/** 返回前必须完成本任务实际执行及受管理资源的清理。 */
@FunctionalInterface
public interface ManagedTaskRunner {
    TaskOutcome run(TaskExecution execution) throws Exception;
}
