package com.xhlcli.browser;

/**
 * 浏览器会话模式。
 */
public enum BrowserMode {
    /**
     * 默认隔离模式：启动无状态、临时数据目录的浏览器实例，不携带用户现有登录凭据。
     */
    ISOLATED,

    /**
     * 共享模式：连接用户允许远程调试的本机宿主 Chrome 实例，复用用户现有登录态。
     */
    SHARED
}
