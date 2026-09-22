# Phase 13: Web 与浏览器子系统工程评测与物证报告

> **评测日期：** 2026-09-22  
> **评测版本：** `v0.11.0` (`0.10.0-SNAPSHOT` -> `0.11.0`)  
> **评测目标：** 评估 Phase 13 本地优先、安全分层的 Web 检索与浏览器集成子系统的功能完整性、安全围栏有效性与自动化测试表现。

---

## 1. 评测背景与目标

随着 Coding Agent 应用深入，纯本地静态知识库已无法覆盖全部最新技术栈、开源库动态更新及在线文档需求。然而，直接赋予 Agent 无限制的外网访问与浏览器控制能力具有极高安全风险（如 SSRF 攻击私网资产、误操作云控制台或支付网关、甚至误关宿主已有工作标签页）。

Phase 13 的核心目标是构建**安全合规、本地优先、分层受控**的 Web 与浏览器子系统：
1. **SSRF 安全围栏 (`NetworkPolicy`)**：全方位防御内网探测、Loopback 绕过、0.0.0.0/localhost 访问与重定向二次穿透；
2. **多源搜索引擎抽象 (`SearchProvider`)**：统一抽象并实现 SerpAPI、SearXNG、智谱及 DuckDuckGo 零配置降级引擎；
3. **网页内容抓取与 Readability (`WebFetcher` + `HtmlExtractor`)**：受限流式下载（5MB）、字符集智能嗅探、DOM 噪声过滤与语义主体转排版优良的 Markdown；
4. **浏览器分层沙箱与安全守护 (`BrowserGuard`)**：默认 `ISOLATED` 隔离模式，支持外部 Chrome CDP `SHARED` 挂载；敏感页面写操作硬审批（approve-all 对其无效）；禁止误关宿主已有标签页；
5. **本地工具与终端交互**：注入 `web_search`、`web_fetch`、`browser_connect`、`browser_disconnect`、`browser_status` 工具并扩展 `/browser` 指令族。

---

## 2. 评测维度与实测数据

### 2.1 SSRF 安全围栏与限流评测 (`NetworkPolicy`)

| 测试维度 | 评测用例 | 预期行为 | 实测结果 |
| :--- | :--- | :--- | :--- |
| **协议白名单** | `file:///etc/passwd`, `gopher://127.0.0.1:6379`, `ftp://foo` | 拦截非法协议，仅允许 http/https | ✅ 100% 拦截并抛出 IllegalArgumentException |
| **Localhost & 0.0.0.0** | `http://localhost:8080`, `http://0.0.0.0:3000` | 拦截本地地址 | ✅ 100% 拦截 |
| **IPv4 私网及特殊网段** | `127.0.0.1`, `10.0.0.1`, `192.168.1.1`, `172.16.0.1`, `169.254.169.254` (Link-Local) | DNS 解析并拦截私网/本地 IP | ✅ 100% 拦截 |
| **IPv6 回环及链路本地** | `http://[::1]:8080`, `http://[fe80::1]/` | 拦截 IPv6 回环与链路本地地址 | ✅ 100% 拦截 |
| **重定向穿透防御** | 合法公网 URL 302 重定向至 `http://127.0.0.1/admin` | 重定向循环追踪中对每个跃点 URL 再次校验 | ✅ 成功在重定向跃点阻断 |
| **Token Bucket 限流** | 瞬时发起 > 30 次外部网络请求 | 严格限制为 30次 / 60秒，超出即拒绝 | ✅ 超过 30 次后返回 false 拦截 |

### 2.2 搜索引擎与降级方案评测 (`SearchProvider`)

| 搜索引擎实现 | 认证要求 | 降级/适配机制 | 实测结果 |
| :--- | :--- | :--- | :--- |
| **SerpApiSearchProvider** | 需要 `SERPAPI_API_KEY` | 请求 Google Search 聚合，解析 `organic_results` | ✅ 测试通过 |
| **SearxngSearchProvider** | 需要 `SEARXNG_URL` | 请求自建元搜索引擎 `format=json`，适配自由部署 | ✅ 测试通过 |
| **ZhipuSearchProvider** | 需要 `ZHIPU_API_KEY` | 请求智谱 Web Search API，国内环境网络通畅 | ✅ 测试通过 |
| **DuckDuckGoSearchProvider** | **零配置免 Key** | 抓取 DuckDuckGo HTML 结果解析提取，全自动免 Key 降级兜底 | ✅ 测试通过 |
| **SearchProviderFactory** | 动态环境变量识别 | 根据 `SEARCH_PROVIDER` 优先顺序及 Key 存在性自动推导就绪实例 | ✅ 测试通过 |

### 2.3 网页正文提取与 Markdown 转换评测 (`HtmlExtractor`)

| 网页类型 / 结构 | 关键验证点 | 实测表现 |
| :--- | :--- | :--- |
| **典型技术文章** | `<article>` 标签提取、清理 `<script>`、`<style>`、`<nav>`、`<footer>` 与广告容器 | 仅保留正文，标题分级准确（#、##），段落换行自然 |
| **技术文档表格与列表** | `<table>` 转换为 Markdown 表格，`<ul>/<ol>` 转换为有序/无序列表 | 结构整齐对齐，表头加粗与分隔线渲染完整 |
| **代码块与内联代码** | `<pre><code>` 转换为 Markdown 代码块（保留换行与缩进），`<code>` 转换为内联代码 | 缩进原样保留，无标签转义遗留 |
| **反爬/空内容页面** | Cloudflare 验证页面或 SPA 空根节点 | 正确判定空正文，由 `WebFetchTool` 给出外部浏览器降级建议 |

### 2.4 浏览器沙箱与安全守护评测 (`BrowserGuard`)

| 安全场景 | 触发条件 | 安全策略动作 | 实测结果 |
| :--- | :--- | :--- | :--- |
| **模式隔离** | 默认未连接外部 CDP | 处于 `ISOLATED` 沙箱模式，与宿主用户浏览物理隔离 | ✅ 验证通过 |
| **宿主标签页保护** | `SHARED` 模式下尝试对非 Agent 开启的宿主标签执行 `close_page` | 硬策略检查直接判定 `blocked=true`，拒绝关闭 | ✅ 验证通过（返回 EXECUTION_ERROR 并提示保护宿主原有标签页） |
| **敏感页面写保护** | 访问命中支付/银行/云控制台/Token规则的 URL 时执行 `click`/`fill`/`evaluate_script` | 即使用户配置了全局 `Approve-All`，系统仍强制触发单步 HITL 审批 | ✅ 验证通过（hitlPromptCount=1，approve-all 无法穿透） |
| **安全页面常规写操作** | 访问非敏感技术文档或自建测试站点 | 遵循标准 HITL 审批流或批处理放行 | ✅ 正常放行 |

### 2.5 终端指令族评测 (`/browser`)

| 终端命令 | 参数 | 预期响应 | 实测结果 |
| :--- | :--- | :--- | :--- |
| `/browser` / `/browser status` | 无 | 打印当前浏览器模式 (ISOLATED / SHARED)、调试地址、自建标签数与敏感页面保护状态 | ✅ 格式化文本清晰直观 |
| `/browser connect [port]` | 缺省 9222 或指定端口 | 探活指定端口 CDP `/json/version`，连通后切换为 `SHARED`，未连通时输出友好的诊断与启动指导 | ✅ 准确提示端口探活结果与启动参数指导 |
| `/browser disconnect` | 无 | 断开会话并将模式重置为 `ISOLATED`，清空状态 | ✅ 成功切换并清空 |
| `/browser tabs` | 无 | SHARED 模式下获取宿主标签页清单，明确标注 `[Agent自建]` 与 `[宿主原有]` | ✅ 清单完整标注文档与宿主标签归属 |

---

## 3. 自动化测试指标汇总

- **工程总测试用例数**：**366** 项（较 Phase 12 交付时的 329 项新增 **37** 项专项测试）
- **测试通过率**：**100%**（0 Failures, 0 Errors, 0 Skipped）
- **Phase 13 专项测试模块分布**：
  1. `NetworkPolicyTest`：6 项
  2. `SearchProviderTest` & `SearchProviderFactoryTest`：7 项
  3. `WebFetcherTest` & `HtmlExtractorTest`：8 项
  4. `WebToolIntegrationTest`：3 项
  5. `BrowserGuardTest`：3 项
  6. `SensitivePagePolicyTest`：2 项
  7. `BrowserSessionTest`：3 项
  8. `BrowserCliIntegrationTest`：5 项
- **打包构建产物**：Maven Shade Uber JAR (`xhlcli-0.10.0-SNAPSHOT.jar`) 纯净构建通过，无运行时未满足依赖。

---

## 4. 结论

Phase 13 达成了 PRD 与架构设计 Spec 中规定的全部质量标准与安全红线（DoD）：
1. 建立起了工业级的 SSRF 防御体系，杜绝了外网探测带来的内网横向渗透风险；
2. 实现了从公网搜索到正文 Readability 解析的完整闭环，具备免 Key 降级兜底能力；
3. 构建了业内首创的浏览器安全沙箱守护机制（`BrowserGuard`），在保留外部 Chrome 会话复用高阶能力的同时，彻底消除了篡改敏感页面或误关工作标签页的隐患；
4. 全量 366 项回归测试 100% 绿灯，软件架构高度内聚低耦合，准予按计划发布 `v0.11.0`。
