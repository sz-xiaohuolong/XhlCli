package com.xhlcli.policy;

/**
 * 策略硬性拒绝异常。
 * 当工具调用违反系统硬策略（如路径越界、高危禁止命令等）时抛出，不可被用户批准绕过。
 */
public class PolicyException extends RuntimeException {
    public PolicyException(String message) {
        super(message);
    }

    public PolicyException(String message, Throwable cause) {
        super(message, cause);
    }
}
