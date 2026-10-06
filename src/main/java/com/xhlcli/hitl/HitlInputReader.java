package com.xhlcli.hitl;

/**
 * HITL 交互输入抽象契约，用于解耦具体输入源（如 JLine TerminalSession 或流式输入）。
 */
@FunctionalInterface
public interface HitlInputReader {

    /**
     * 读取一行用户输入。
     *
     * @param prompt 提示符
     * @return 用户输入的文本
     * @throws Exception 当底层输入发生异常或中断时抛出
     */
    String readLine(String prompt) throws Exception;
}
