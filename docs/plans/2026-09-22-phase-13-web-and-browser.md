# Phase 13：Web 与浏览器实施路线与计划 (Implementation Plan)

> **文档性质：** 阶段实施任务清单与执行蓝图  
> **计划日期：** 2026-09-22  
> **目标版本：** `v0.11.0`  
> **对应 PRD：** [`docs/prd/phase-13-web-and-browser.md`](../prd/phase-13-web-and-browser.md)  
> **对应 Spec：** [`docs/specs/2026-09-22-phase-13-web-and-browser-design.md`](../specs/2026-09-22-phase-13-web-and-browser-design.md)  
> **执行准则：** 必须等待用户明确确认批准（Human Approval Gate）后方可开始编码！

---

## 1. 阶段目标与交付范围

实现本地优先、安全分层的联网与浏览器访问子系统：
1. **依赖扩充**：在 `pom.xml` 中引入 `org.jsoup:jsoup:1.18.1` 用于 HTML 正文提取与 Markdown 转换；
2. **网络安全围栏 (`com.xhlcli.web`)**：实现 `NetworkPolicy`，强制执行 SSRF 防御（拦截私网、Loopback、Link-Local、非 HTTP(S) 协议、重定向重新校验）与 Token Bucket 速率限制；
3. **搜索引擎抽象与实现**：实现领域模型 `SearchResult`、`SearchProvider` 接口、`SearchProviderFactory` 工厂及 SerpAPI / SearXNG / 智谱 / DuckDuckGo 实现；
4. **正文获取与 Readability**：实现 `WebFetcher`（受限流式读取、编码嗅探、重定向追踪）、`HtmlExtractor`（广告过滤、语义打分、Markdown 转换）与领域模型 `FetchResult`；
5. **本地联网工具注入**：实现并向 `ToolRegistry` 注册 `web_search` 与 `web_fetch` 工具，纳入上下文预算与敏感审计；
6. **浏览器沙箱与安全守护 (`com.xhlcli.browser`)**：实现 `BrowserMode`、`BrowserSession`、`SensitivePagePolicy`（敏感页面规则库）、`BrowserGuard`（写操作单步硬审批、防止误关宿主标签页）与 `BrowserConnectivityCheck`；
7. **浏览器辅助工具与终端命令**：实现 `browser_connect`、`browser_disconnect`、`browser_status` 工具，并在 `ChatLoop` 中挂载 `/browser` 指令族；
8. **自动化测试与评测物证**：新增不少于 20 项专项测试，确保全量测试 100% 绿灯，产出评测报告 `docs/engineering/web-and-browser-evaluation.md`，同步更新全套 Living Docs，发布 Tag `v0.11.0`。

---

## 2. 详细任务分解 (Task Breakdown)

### Task 1: 依赖更新与网络安全策略层 (`com.xhlcli.web`)
- [x] **1.1** 在 `pom.xml` 中引入 `org.jsoup:jsoup:1.18.1` 并验证编译；
- [x] **1.2** 创建 `com.xhlcli.web.NetworkPolicy`：
  - 校验 URL scheme（仅允许 http, https）；
  - 拦截 `localhost`, `0.0.0.0`；
  - DNS 解析并拦截 loopback, any-local, link-local, site-local IP；
  - 简易 Token Bucket 限流算法（每 60 秒 30 次）；
- [x] **1.3** 编写 `NetworkPolicyTest`：覆盖合法公网 URL、各类私网 IP 变体、localhost 与频率限制超额场景。

### Task 2: 搜索引擎抽象与多 Provider 实现 (`com.xhlcli.web`)
- [x] **2.1** 创建 `com.xhlcli.web.SearchResult` 不可变 Record (`title`, `url`, `snippet`, `publishedDate`)；
- [x] **2.2** 创建 `com.xhlcli.web.SearchProvider` 接口 (`name()`, `isReady()`, `unavailableHint()`, `search(query, topK)`)；
- [x] **2.3** 实现各引擎 Provider：
  - `SerpApiSearchProvider`（基于 OkHttp 请求 Google 搜索聚合）；
  - `SearxngSearchProvider`（支持自建开源元搜索引擎）；
  - `ZhipuSearchProvider`（支持智谱搜索接口）；
  - `DuckDuckGoSearchProvider`（免 Key 降级支持）；
- [x] **2.4** 创建 `SearchProviderFactory`：根据环境变量 `SEARCH_PROVIDER` 及相关 Key 自动构建最佳可用 Provider；
- [x] **2.5** 编写 `SearchProviderTest` 与 `SearchProviderFactoryTest`。

### Task 3: 网页抓取与 HTML 转 Markdown 抽取器 (`com.xhlcli.web`)
- [x] **3.1** 创建 `com.xhlcli.web.FetchResult` 不可变 Record (`url`, `finalUrl`, `statusCode`, `title`, `markdown`, `truncated`, `fetchDate`)；
- [x] **3.2** 创建 `com.xhlcli.web.WebFetcher`：
  - 基于 OkHttpClient 封装 GET 请求；
  - 严格通过 `NetworkPolicy` 校验初始 URL 与每次重定向的 URL；
  - 5MB 受限流式读取防 OOM；
  - 字符集智能嗅探（Content-Type header、HTML meta 标签、UTF-8 兜底）；
- [x] **3.3** 创建 `com.xhlcli.web.HtmlExtractor`：
  - 使用 Jsoup 解析 HTML DOM；
  - 清理 `<script>`, `<style>`, `<nav>`, `<footer>` 等噪声标签与常见广告容器；
  - 寻找 `<article>`, `<main>` 语义节点或根据文本/链接比率打分算法选取主体；
  - 递归渲染为排版优良的 Markdown（标题、段落、列表、表格、代码块、超链接）；
  - 平滑空白行折叠；
- [x] **3.4** 编写 `WebFetcherTest`（配合 MockWebServer）与 `HtmlExtractorTest`（典型文章/列表/表格及空页面解析）。

### Task 4: Web 本地工具封装与 Agent 集成 (`com.xhlcli.web.tool`)
- [x] **4.1** 创建 `com.xhlcli.web.tool.WebSearchTool`：实现 `com.xhlcli.tool.Tool` 接口，入参 `query`, `top_k`（默认 5），格式化输出搜索结果列表；
- [x] **4.2** 创建 `com.xhlcli.web.tool.WebFetchTool`：实现 `com.xhlcli.tool.Tool` 接口，入参 `url`, `max_chars`（默认 8000），格式化输出 Markdown 及获取时间与来源元信息，静态抓取失败或空正文时给出浏览器降级建议；
- [x] **4.3** 更新 `ToolRegistry` 与 `ChatBootstrap`：将 Web 工具注入 Agent 上下文，并在 System Prompt 中声明使用指南（仅在需要最新互联网信息或已知公开 URL 时调用）；
- [x] **4.4** 编写 `WebToolIntegrationTest`。

### Task 5: 浏览器会话与安全沙箱 (`com.xhlcli.browser`)
- [x] **5.1** 创建 `com.xhlcli.browser.BrowserMode` 枚举（`ISOLATED`, `SHARED`）；
- [x] **5.2** 创建 `com.xhlcli.browser.BrowserSession`：跟踪当前模式、已连接调试地址、最近导航 URL、Agent 创建的标签页 ID 集合；
- [x] **5.3** 创建 `com.xhlcli.browser.SensitivePagePolicy`：加载银行、支付、身份认证、云控制台规则，支持用户自定义 `~/.xhlcli/sensitive_patterns.txt` 扩展；
- [x] **5.4** 创建 `com.xhlcli.browser.BrowserGuard`：
  - 拦截 Chrome DevTools MCP 工具调用；
  - 敏感页面写操作（`click`, `fill`, `evaluate_script` 等）强制触发 HITL 单步审批，严禁批量放行；
  - 在 `SHARED` 模式下拦截 `close_page` 关闭非 Agent 自建的标签页；
- [x] **5.5** 创建 `com.xhlcli.browser.BrowserConnectivityCheck`：探测本地 9222 等 CDP 调试端口可用性与 Chrome 版本信息；
- [x] **5.6** 编写 `BrowserSessionTest`、`SensitivePagePolicyTest` 与 `BrowserGuardTest`。

### Task 6: 浏览器工具与终端 `/browser` 指令扩展
- [x] **6.1** 创建 `BrowserConnectTool`、`BrowserDisconnectTool`、`BrowserStatusTool`；
- [x] **6.2** 扩展 `ChatCommand` 与 `ChatCommandParser` 支持 `/browser`；
- [x] **6.3** 在 `ChatLoop` 中实现 `handleBrowser()`，支持子命令 `status`, `connect`, `disconnect`, `tabs`；
- [x] **6.4** 在 `PlainRunRenderer` 的 `/help` 菜单中增加 `/browser` 说明；
- [x] **6.5** 编写 `BrowserCliIntegrationTest`。

### Task 7: 全量验证、评测物证、Living Docs 同步与 Tag 发布 (DoD 铁律)
- [x] **7.1** 运行 `./mvnw clean verify` 确保全部自动化测试（350+ 项）100% 绿灯；
- [x] **7.2** 产出客观评测报告 `docs/engineering/web-and-browser-evaluation.md`；
- [x] **7.3** 更新 Living Docs：
  - `docs/PROJECT.md`：更新 Release 状态为 Phase 13 `v0.11.0`；
  - `docs/ROADMAP.md`：标记 Phase 13 已交付；
  - `docs/TECH_DESIGN.md`：更新技术基线与真实架构；
  - `docs/specs/README.md` 与 `docs/plans/README.md`：归档封存 Phase 13；
  - `CHANGELOG.md`：增加 `[0.11.0] - 2026-09-22` 变更项；
  - `AGENTS.md`：更新当前阶段与已交付能力清单；
  - `README.md`：更新文档导航与功能描述；
- [x] **7.4** 升级 `pom.xml` 为 `0.11.0-SNAPSHOT`，执行 Git 提交，打 Tag `v0.11.0` 并推送到 GitHub。

---

## 3. 验收标准与完成定义 (DoD)

1. **编译构建**：Java 21 纯净编译，无警告，Shade Uber JAR 正常打包；
2. **网络安全**：私网 IP、Loopback、Link-Local、非 HTTP 协议与重定向穿透测试均 100% 被拦截并给出明确警告；
3. **内容正文提取**：对常见文章、列表、表格能够准确提取为结构化 Markdown，超大页面平滑截断；
4. **浏览器安全隔离**：默认处于 `ISOLATED` 模式；连接 `SHARED` 需用户确认；敏感页面写操作单步审批绝不穿透；
5. **测试覆盖**：全量测试 100% 绿灯（预估 350+ 项）；
6. **文档与版本**：所有 Living Docs 严格同步，Git Tag `v0.11.0` 成功推送。
