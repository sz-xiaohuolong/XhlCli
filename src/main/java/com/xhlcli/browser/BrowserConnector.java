package com.xhlcli.browser;

import java.util.List;

/**
 * 浏览器会话连接器接口。
 */
public interface BrowserConnector {

    /**
     * 获取当前浏览器模式与状态描述
     */
    String status();

    /**
     * 连接默认/探测到的宿主调试端口 (9222)
     */
    String connectDefault();

    /**
     * 连接指定调试端口
     */
    String connect(int port);

    /**
     * 断开共享会话并切回隔离模式
     */
    String disconnect();

    /**
     * 获取持有的浏览器会话状态
     */
    BrowserSession session();

    /**
     * 获取当前打开的标签页列表（仅在 SHARED 模式有效）
     */
    List<BrowserTabInfo> listTabs();
}
