# Phase 13：Web 与浏览器架构设计规范 (Technical Design Specification)

> **文档状态：** 提案中 (Proposed)  
> **设计日期：** 2026-09-22  
> **目标版本：** `v0.11.0`  
> **对应 PRD：** [`docs/prd/phase-13-web-and-browser.md`](../prd/phase-13-web-and-browser.md)  
> **实现状态：** 待用户批准实施

---

## 1. 架构目标与分层体系

为解决 Coding Agent 缺乏实时互联网信息、官方最新文档、版本发布资讯以及 SPA/动态网页交互能力的问题，Phase 13 构建本地优先、安全分层的联网与浏览器子系统。

### 1.1 分层能力路径 (Tool Hierarchy)

```text
+-------------------------------------------------------------------------+
|                              用户任务目标                                |
+-------------------------------------------------------------------------+
                                     |
               +---------------------+---------------------+
               |                                           |
      【本地代码 / 项目问题】                       【互联网信息需求】
               |                                           |
     优先使用本地工具                                       |
  (search_code / grep / ast)               +---------------+---------------+
                                           |                               |
                                    【需发现 / 泛查询】              【已知公开 URL】
                                           |                               |
                                      web_search                       web_fetch
                                   (多引擎 Provider)              (HTTP + Readability)
                                           |                               |
                                           +---------------+---------------+
                                                           |
                                               【静态抓取失败 / JS 渲染 /
                                                需要点击输入 / 需登录态】
                                                           |
                                                   browser 工具族
                                              (通过 MCP chrome-devtools)
                                                           |
                                            +--------------+--------------+
                                            |                             |
                                      【isolated 模式】              【shared 模式】
                                   (默认临时 profile 隔离)        (用户授权复用宿主 Chrome)
```

---

## 2. 核心架构与模块划分

系统将划分为两个核心子包：`com.xhlcli.web`（轻量网络与搜索）和 `com.xhlcli.browser`（浏览器会话与安全沙箱）：

```
src/main/java/com/xhlcli/
├── web/
│   ├── NetworkPolicy.java          # SSRF 防护、私网/Loopback 拦截、重定向安全与限流
│   ├── SearchResult.java           # 搜索结果领域模型 (title, url, snippet, date)
│   ├── SearchProvider.java         # 搜索引擎抽象接口 (name, isReady, search, hint)
│   ├── SearchProviderFactory.java  # 引擎工厂 (按 env/Key 探测 SerpApi/SearXNG/Zhipu/DuckDuckGo)
│   ├── SerpApiSearchProvider.java  # SerpAPI 商业引擎实现
│   ├── SearxngSearchProvider.java  # SearXNG 自托管引擎实现
│   ├── DuckDuckGoSearchProvider.java# 公开轻量降级引擎实现
│   ├── ZhipuSearchProvider.java    # 智谱 Web Search 实现
│   ├── FetchResult.java            # 抓取结果模型 (url, finalUrl, title, markdown, truncated)
│   ├── WebFetcher.java             # OkHttp 客户端封装、受限流式读取、编码嗅探
│   ├── HtmlExtractor.java          # 噪声过滤 (广告/导航)、语义标签打分、HTML 转 Markdown
│   ├── tool/
│   │   ├── WebSearchTool.java      # web_search 工具实现
│   │   └── WebFetchTool.java       # web_fetch 工具实现
├── browser/
│   ├── BrowserMode.java            # 会话模式枚举 (ISOLATED, SHARED)
│   ├── BrowserSession.java         # 浏览器状态持有 (模式、lastUrl、agent 开启的 tab 集合)
│   ├── SensitivePagePolicy.java    # 敏感页面模式匹配 (银行、支付、云控制台、账户设置)
│   ├── BrowserGuard.java           # 浏览器操作拦截 (敏感页面写操作硬审批、防关宿主 tab)
│   ├── BrowserConnectivityCheck.java# Chrome CDP 远程调试端口探活 (如 9222 端口 /json/version)
│   ├── BrowserConnector.java       # 连接器接口 (status, connectDefault, disconnect)
│   └── tool/
│       ├── BrowserConnectTool.java # browser_connect 工具
│       ├── BrowserDisconnectTool.java # browser_disconnect 工具
│       └── BrowserStatusTool.java  # browser_status 工具
└── cli/
    └── ChatLoop.java               # 扩展 /browser 命令族 (status, connect, disconnect, tabs)
```

---

## 3. 关键设计规范

### 3.1 网络安全围栏与 SSRF 阻断 (NetworkPolicy)

严格遵循 `FR-13-04`，杜绝一切服务端请求伪造（SSRF）与私网逃逸：
1. **协议白名单**：仅允许 `http` 与 `https`，拒绝 `file://`、`ftp://`、`gopher://` 等；
2. **IP 与主机黑名单**：
   - 拒绝 `localhost`、`*.localhost`、`0.0.0.0`；
   - DNS 解析获取所有 `InetAddress`，若包含 Loopback (`127.0.0.0/8`, `::1`)、Site-Local (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`)、Link-Local (`169.254.0.0/16`, `fe80::/10`) 或 Any-Local 地址，直接拒绝访问；
3. **重定向穿透防护**：HTTP 301/302/307/308 重定向时，目标 URL 必须重新经过完整的 `NetworkPolicy.checkUrl()` 校验，防止先访问公网再跳向内网；
4. **Token Bucket 频率限制**：滑动窗口限流（默认 60 秒内最多 30 次请求），超出返回友好限流等待提示；
5. **流式截断与 OOM 防御**：单次响应体上限硬性限制为 5MB，超出自动截断。

### 3.2 搜索引擎 Provider 体系 (SearchProvider)

1. **接口定义**：
   ```java
   public interface SearchProvider {
       String name();
       boolean isReady();
       String unavailableHint();
       List<SearchResult> search(String query, int topK) throws IOException;
   }
   ```
2. **多 Provider 支持与自动探测**：
   - `SerpApiSearchProvider`：检测 `SERPAPI_KEY`；
   - `SearxngSearchProvider`：检测 `SEARXNG_URL`；
   - `ZhipuSearchProvider`：检测 `ZHIPU_API_KEY` 或 `GLM_API_KEY`；
   - `DuckDuckGoSearchProvider`：公共免 Key 基础搜索；
   - 显式通过环境变量 `SEARCH_PROVIDER` 进行强指定。

### 3.3 网页提取与轻量 Readability (HtmlExtractor)

引入轻量级 `org.jsoup:jsoup:1.18.1`：
1. **噪声清理**：移除 `script`, `style`, `noscript`, `iframe`, `nav`, `aside`, `header`, `footer`, `form`, `svg`, `canvas`, `button` 等标签，并通过关键词过滤常见广告容器；
2. **正文定位**：优先查找 `<article>`, `<main>`, `[role=main]` 语义节点；若未找到，根据文本长度及非链接字符比例计算权重评分，定位最佳正文 Block；
3. **格式转换**：递归渲染为干净的 GitHub Flavored Markdown（保留标题、段落、列表、表格、代码块与链接）；
4. **预算截断**：`web_fetch` 默认参数 `max_chars = 8000`，超出按段落平滑截断，并附带 `[Content truncated, length: X chars]` 标识。

### 3.4 浏览器会话与安全沙箱 (BrowserSession & BrowserGuard)

1. **会话模式 (BrowserMode)**：
   - `ISOLATED` (默认)：MCP 启动无状态、临时数据目录的 Chrome 实例，不包含用户 Cookie 与登录态；
   - `SHARED`：经用户显式确认或执行 `/browser connect` 后，连接本地开启了远程调试（`--remote-debugging-port=9222` 或 Chrome 144+ remote debugging）的宿主 Chrome，复用用户现有登录凭据。
2. **敏感页面硬审批 (SensitivePagePolicy)**：
   - 默认模式集合：银行 (`*://*.bank.*/*`)、支付 (`*://*.alipay.com/*`, `*://*.paypal.com/*`, `*://*.stripe.com/*`)、设置页 (`*://github.com/settings/*`)、云控制台 (`*://*.console.aws.amazon.com/*`, `*://*.console.cloud.google.com/*`, `*://*.portal.azure.com/*`, `*://*.aliyun.com/*`) 等；
   - 支持从 `~/.xhlcli/sensitive_patterns.txt` 扩展用户自定义规则；
   - **拦截规则**：若当前页面或目标 URL 命中敏感规则，且调用的浏览器工具为写操作（`click`, `fill`, `fill_form`, `press_key`, `evaluate_script` 等），强制触发 HITL 单步审批，**严禁复用 "Always Approve" 自动放行**。
3. **标签页保护**：
   - 在 `SHARED` 模式下，Agent 仅允许关闭自身创建的标签页（通过 `session.isAgentOpenedTab(pageId)` 判定），严禁关闭用户自身正在浏览的工作标签页。

### 3.5 终端 `/browser` 命令集成

在 JLine 终端交互中扩展 `/browser` 子命令族：
- `/browser status`：查看当前模式（isolated/shared）、chrome-devtools MCP 服务运行状态与 9222 端口探活；
- `/browser connect [port]`：安全确认并切换至 shared 模式；
- `/browser disconnect`：断开共享连接并复位至 isolated 模式；
- `/browser tabs`：列出当前由 Agent 跟踪与打开的浏览器标签页。

---

## 4. 依赖升级方案

在 `pom.xml` 中引入成熟稳健的 HTML 解析库：
```xml
<dependency>
    <groupId>org.jsoup</groupId>
    <artifactId>jsoup</artifactId>
    <version>1.18.1</version>
</dependency>
```

---

## 5. 测试与质量保证策略

1. **网络策略测试 (`NetworkPolicyTest`)**：
   - 验证 IPv4/IPv6 私网地址、Loopback、Link-local 拦截；
   - 验证 `localhost`、`0.0.0.0` 拦截；
   - 验证 Token Bucket 频率限制与拒绝提示。
2. **抓取与正文抽取测试 (`WebFetcherTest`, `HtmlExtractorTest`)**：
   - MockWebServer 模拟静态博客、文章、列表、表格、空页面、超大页面截断、404/500 处理；
   - 验证 HTML 转换为 Markdown 的格式保真度。
3. **搜索引擎测试 (`SearchProviderTest`)**：
   - Mock 各 Provider 返回报文，验证解析与容错；
   - 验证无 Key 时的友好占位与报错指引。
4. **浏览器安全测试 (`BrowserGuardTest`, `SensitivePagePolicyTest`, `BrowserSessionTest`)**：
   - 验证敏感页面对写操作的单步审批强制拦截；
   - 验证非 Agent 标签页的关闭保护；
   - 验证模式切换与重置。
5. **集成与 CLI 交互测试 (`WebCliIntegrationTest`)**：
   - 验证 `/browser` 命令与 ReAct Agent 端到端调用 Web 工具。
