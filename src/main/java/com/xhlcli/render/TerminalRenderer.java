package com.xhlcli.render;

import com.xhlcli.config.ChatConfig;
import com.xhlcli.model.RunEvent;

/**
 * 终端渲染器统一接口契约。
 * 解耦 Agent 核心事件调度与终端视觉呈现，支持行内富文本与纯文本双模实现。
 */
public interface TerminalRenderer extends AutoCloseable {

    /** 接收并渲染 Agent 执行事件流 */
    void accept(RunEvent event);

    /** 打印基础欢迎信息（向后兼容） */
    default void printWelcome(String model) {
        printWelcome(model, "0.13.0", ".", TerminalExtSummary.empty());
    }

    /** 打印产品首屏信息（含版本、工作区和扩展状态摘要） */
    void printWelcome(String model, String version, String workspace, TerminalExtSummary summary);

    /** 打印终端帮助文档 */
    void printHelp();

    /** 打印当前配置 */
    void printConfig(ChatConfig config);

    /** 打印普通通知消息 */
    void printMessage(String message);

    /** 打印错误消息 */
    void printErrorMessage(String message);

    /** 打印未知命令提示 */
    void printUnknownCommand(String command);

    /** 打印清屏/新会话提示 */
    void printCleared();

    /** 打印退出程序提示 */
    void printGoodbye();

    /** 更新底部状态栏 */
    void updateStatus(TerminalStatus status);

    /** 关闭并清理渲染资源 */
    @Override
    default void close() {}
}
