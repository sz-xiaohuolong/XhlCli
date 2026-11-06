# 桌面 MVP 适配设计

状态：设计已实现，核心离线验证完成；具体覆盖与限制见[验收记录](../engineering/desktop-mvp-verification.md)。依据为用户明确的 MVP 开发与推送授权，以及 [需求](../prd/desktop-codex-ui.md)。本设计选择桌面私有 stdio 桥接，直接复用 Java 核心；旧 Runtime HTTP 接口保持兼容。

## 技术基线

Java 21、Maven Wrapper、Jackson/JGit/JUnit 沿用 pom.xml。新增 Electron 45.2.3、React 19.3.1、esbuild 0.28.2，版本经 npm 官方注册表核验，锁文件固定。界面隔离运行，禁用 Node 集成，通过预加载脚本暴露有限操作。依据 Electron 官方 security/context-isolation 文档核对 IPC 来源、导航限制和 sandbox。

## 组件与契约

- `desktop/`：Electron 主进程管理 Java 子进程、文件选择和系统打开；React 负责显示与交互。数据来源唯一为 Java 服务。
- `com.xhlcli.desktop`：桌面会话、持久化、图形审批、模型配置及快照差异适配。运行调用 ReactAgent，工具调用 DefaultToolExecutor，复用 PathGuard、SkillRegistry、MemoryManager、MCP 和 SideGitManager。
- stdio 每行一个 JSON 请求 `{id,method,params}`，响应 `{id,result}` 或 `{id,error}`，推送 `{event,sessionId,turnId,data}`。stdout 只发送协议，诊断去 stderr；不开放监听端口。
- 方法包括 `state`、`project.add`、`session.create/update`、`turn.start/cancel`、`approval.respond`、`changes/undo`、`config.get/save/test`。原生打开文件由主进程校验目录后执行。协议属于桌面内部接口，调用说明随代码 README 维护。

## 状态与存储

桌面数据保存在 `~/.xhlcli/desktop/`，单进程文件锁，JSON 原子替换，私有文件权限。不迁移现有 task/runtime 数据库。每会话存储项目路径、模型、消息上下文和轮次事件；重启将活动状态改为中断，不自动执行。恢复历史时使用当前系统规则，不信任历史中的系统消息。

工作目录按真实路径互斥；忙碌时拒绝新任务并保留输入。模型切换下一轮生效。关闭窗口保留进程；退出时取消并清理子进程。审批只批准本次工具调用，过期请求不得复活。

快照使用现有 Side-History 存储；桌面根据轮次前后提交显示差异，撤销前核对当前内容与结束快照，一旦冲突整体拒绝。只恢复该轮变化文件，不重置宿主 Git 索引。快照失败不冒充可撤销。

## 验证与恢复

先验证历史恢复、真实流式、工具审批、目录互斥和快照冲突，再验证 Electron 桥接与图形操作。使用本地假模型驱动真实 Java 工具链，不在生产代码加入演示分支。agent-desktop 检查主界面、发送、工具、审批、停止、diff、续聊和设置。

数据损坏应明确报错并保留原文件；不以空状态覆盖。取消、退出与失败保留已发生改动，界面如实展示。打包与启动检查通过后，按用户授权提交推送 origin/main，不打 Tag 或发布新版本。
