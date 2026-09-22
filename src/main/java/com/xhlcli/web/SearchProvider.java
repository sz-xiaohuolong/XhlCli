package com.xhlcli.web;

import java.io.IOException;
import java.util.List;

/**
 * 搜索引擎 Provider 抽象契约。
 */
public interface SearchProvider {

    /**
     * @return provider 名称标识（如 "serpapi", "searxng", "zhipu", "duckduckgo"）
     */
    String name();

    /**
     * @return 是否已准备就绪（凭据或端点已配置）
     */
    boolean isReady();

    /**
     * @return 不可用时的指引说明
     */
    String unavailableHint();

    /**
     * 执行关键词检索。
     *
     * @param query 检索词
     * @param topK 期望最大返回结果数
     * @return 检索结果列表
     * @throws IOException 网络或解析异常
     */
    List<SearchResult> search(String query, int topK) throws IOException;
}
