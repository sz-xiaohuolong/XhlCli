// Terminal Data - 4 Realistic Scenarios from Real XhlCLI Subsystems
window.TERMINAL_SCENARIOS = {
  team: {
    id: "team",
    title: "Multi-Agent Team (/team)",
    statusBadge: "TEAM",
    statusBadgeClass: "badge-team",
    statusText: "Collaborating",
    model: "claude-3-5-sonnet",
    tokens: "4,210 tokens",
    extensions: "MCP: 3 | Skills: 8",
    lines: [
      { type: "prompt", text: "/team 为订单微服务重构分库分表路由并补齐并发单测" },
      { type: "agent", text: "TeamOrchestrator", content: "已启动 1+2+1 Multi-Agent 专职协作管线（Planner / Workers / Reviewer）。" },
      { type: "meta", text: "─── [Stage 1: DAG 拓扑规划] ───" },
      { type: "info", content: "Planner 生成 3 个任务批次 (Kahn 算法拓扑排序，无环依赖检测通过):" },
      { type: "tool", header: "Planner DAG Batches", content: "Batch 1: [Task-1: OrderRoutingRule AST 分析, Task-2: ShardingStrategy 抽象]\nBatch 2: [Task-3: 分库分表规则实现 (依赖 1, 2)]\nBatch 3: [Task-4: 16 并发压力单测编写 (依赖 3)]" },
      { type: "meta", text: "─── [Stage 2: 有界并发调度 (Bounded Parallelism: 2)] ───" },
      { type: "agent", text: "Worker-1", content: "执行 Task-1: 分析 OrderRoutingRule.java... 已识别 4 处数据分片热点。" },
      { type: "agent", text: "Worker-2", content: "执行 Task-2: 抽象 ShardingStrategy 接口... 补丁生成完毕。" },
      { type: "diff", added: "+ public interface ShardingStrategy { int route(Long orderId); }", removed: "- public class LegacyOrderRouter { /* 硬编码分片逻辑 */ }" },
      { type: "meta", text: "─── [Stage 3: 专职独立审查与质量门禁] ───" },
      { type: "agent", text: "Reviewer", content: "执行审查：检查 AST 语法完整性与并发安全边界..." },
      { type: "success", content: "✓ 审查通过：100% 单测断言覆盖，零回归破坏，交接包已原子合并至主工作区！" }
    ]
  },
  restore: {
    id: "restore",
    title: "Snapshot & Revert (/restore)",
    statusBadge: "ReAct",
    statusBadgeClass: "badge-react",
    statusText: "Recovery",
    model: "deepseek-chat",
    tokens: "1,890 tokens",
    extensions: "SideGit: Active",
    lines: [
      { type: "prompt", text: "/snapshot list" },
      { type: "info", content: "=== XhlCLI 历史隔离快照 (共 3 轮) ===" },
      { type: "meta", text: "1. [pre-turn] 2026-10-07 11:20:15 (offset 1) - 重构认证拦截器与 JWT 校验" },
      { type: "meta", text: "2. [pre-turn] 2026-10-07 11:15:02 (offset 2) - 优化 SQLite 连接池配置" },
      { type: "warn", content: "⚠️ 警告：当前工作区检测到语法编译破坏，核心文件 AuthService.java 被误删！" },
      { type: "prompt", text: "/restore 1" },
      { type: "agent", text: "SnapshotService", content: "准备回滚至 offset=1 (pre-turn-react-17882910)..." },
      { type: "tool", header: "SideGitManager 隔离恢复引擎", content: "1. 建立 pre-restore 保护快照 (防止二次操作破坏)\n2. 纯 Java JGit 还原已修改文件: AuthFilter.java\n3. 物理找回被误删文件: AuthService.java\n4. 递归清理未记录的孤儿临时文件" },
      { type: "success", content: "✓ 恢复完成！耗时 22ms。工作区 100% 精确原地还原。" },
      { type: "meta", text: "🛡️ 宿主 Git 零污染断言: 用户主仓库 HEAD/暂存区未发生任何篡改。" }
    ]
  },
  vision: {
    id: "vision",
    title: "Multimodal & Vision Guardrails",
    statusBadge: "ReAct",
    statusBadgeClass: "badge-react",
    statusText: "Vision Defended",
    model: "deepseek-chat",
    tokens: "1,240 tokens",
    extensions: "Vision: Guardrails Active",
    lines: [
      { type: "prompt", text: "请基于系统架构图审查数据库层交互：@image:docs/assets/arch.png" },
      { type: "agent", text: "ImageProcessor", content: "检测到图片输入: docs/assets/arch.png (3.4MB PNG, 包含透明 Alpha 通道)" },
      { type: "tool", header: "图像预处理引擎管线", content: "1. Alpha Flatten: 检测到透明通道，预填充纯白底色合成，消除模型底色穿透\n2. Bicubic 缩放: 2800x1800 -> 1920x1234 等比双三次插值重采样\n3. 注入坐标元信息: [Image: original 2800x1800, displayed at 1920x1234, ratio 1.458]\n4. 预算核算: TokenBudget 预留 1000 tokens 图片配额" },
      { type: "warn", content: "🛡️ 触发视觉防御护栏：当前模型 [deepseek-chat] 为纯文本模型 (supportsVision = false)" },
      { type: "info", content: "客户端自动剥离大图 Base64 数据，降级为纯文本请求提示，彻底杜绝 Provider 400 Bad Request 报错崩溃！" },
      { type: "agent", text: "DeepSeek", content: "已收到您的请求。根据上文 Image source 元信息，当前模型为纯文本模式。若需直接识别图片细节，建议使用 /model use gpt-4o 切换多模态模型。" }
    ]
  },
  task: {
    id: "task",
    title: "Durable Task & Runtime API",
    statusBadge: "TASK",
    statusBadgeClass: "badge-team",
    statusText: "Daemon Active",
    model: "gpt-4o",
    tokens: "2,480 tokens",
    extensions: "API: 127.0.0.1:8765",
    lines: [
      { type: "prompt", text: "/task add \"全量扫描 120 个模块并生成 AST 增量语义索引与符号表\"" },
      { type: "success", content: "✓ 任务已持久化入队：task_9f2a (tasks.db, ENQUEUED)" },
      { type: "agent", text: "DurableTaskManager", content: "后台 Worker 池原子认领 (claimNext 事务锁) -> 状态变更为 RUNNING。" },
      { type: "meta", text: "─── [Localhost Runtime API: 127.0.0.1:8765] ───" },
      { type: "tool", header: "SSE 事件流推送 (GET /v1/threads/th_102/events?after=42)", content: "data: {\"cursor\": 43, \"type\": \"TaskProgress\", \"indexed\": 80, \"total\": 120}\ndata: {\"cursor\": 44, \"type\": \"TaskProgress\", \"indexed\": 120, \"total\": 120}\ndata: {\"cursor\": 45, \"type\": \"TaskCompleted\", \"elapsedMs\": 1280}" },
      { type: "success", content: "✓ 后台任务 task_9f2a 执行完成！即使中途断网或客户端退出，任务状态与游标事件持久不丢。" }
    ]
  }
};
