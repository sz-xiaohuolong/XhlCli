package com.xhlcli.runtime.task;

import com.xhlcli.llm.CancellationToken;
import java.nio.file.Path;
import java.util.function.Consumer;

/** 单项任务的执行边界；取消令牌由管理器持有，输出由任务库接收。 */
public record TaskExecution(String id, String prompt, Path workspace,
                            CancellationToken cancellation, Consumer<String> progress) {}
