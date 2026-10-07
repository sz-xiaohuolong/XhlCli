# Release v1.0 Proposed Design

<!-- Source: specs/2026-10-07-phase-18-release-and-website-design.md -->

# Phase 18: 开源发布治理与静态官网架构设计

> 文档状态：已确认，准备实施  
> 设计版本：v1.0  
> Java 基线：21  
> 目标发布版本：`v1.0.0`  
> 对应 PRD：[`docs/prd/phase-18-open-source-release.md`](../prd/phase-18-open-source-release.md)  
> 前置门禁：Phase 00~17 核心系统已 100% 交付并通过 522 项全量自动化回归测试

---

## 1. 设计概述

本阶段作为 XhlCLI 从内部工程化沉淀走向全球开源产品化的决胜里程碑，聚焦两大核心交付目标：
1. **现代化静态官方网站与 Vercel 部署矩阵 (`website/` + `vercel.json`)**：
   - 严格遵循 `/impeccable` 设计规约（Persuade 模式、暗色工程师沉浸美学、Craft Floor 零容忍禁忌）；
   - 深度借鉴 Qoder (`https://qoder.cn/`) 与顶级 Agent CLI（Claude Code, Aider, Ghostty）的设计语言；
   - 包含一键安装器切换、可交互终端模拟器（Interactive Terminal Simulator）、Bento 异构技术壁垒解构、全量指令交互速查表与真实测试物证公示；
   - 具备开箱即用的静态部署能力，单项目直接映射 Vercel 静态主机，零后端依赖，毫秒级全球 CDN 触达。
2. **开源发布治理与生产级分发工程 (`install.sh` + Release Pipeline)**：
   - 跨平台一键安装脚本（`install.sh`，智能探测平台架构、Java 21 环境检测、版本下载、二进制快捷调用软链配置）；
   - GitHub 开源治理脚手架（Bug Report、Feature Request、PR 模板）；
   - 生产级语义化版本 `v1.0.0` 构建与全量发布。

---

## 2. 官网设计原则与规约 (`/impeccable` Craft Floor)

### 2.1 视觉世界与调色板定义 (Visual World & Palette)

| 视觉变量 | 真实取值 | 设计意图 |
| :--- | :--- | :--- |
| **Canvas Background** | `#090a0f` (Deep Obsidian) | 极度深邃沉浸的工程师黑，消除纯黑 `#000000` 的生硬对比 |
| **Surface Elevate 1** | `#11131a` (Elevated Zinc) | 卡片与容器主底色，提供 12% 亮度阶梯 |
| **Surface Elevate 2** | `#181b24` (Interactive Zinc) | 悬浮、活动状态与控制面板高亮底色 |
| **Border / Divider** | `#1f2430` / `#2e3444` | 单次声明细分界，绝不同时叠加 1px 边框与大投影 |
| **Primary Text** | `#f8fafc` (Slate 50) | 标题与高亮字阶，最高对比度 |
| **Secondary Text** | `#94a3b8` (Slate 400) | 说明文本与辅助信息，保持 ≥4.5:1 对比度 |
| **Accent Dragon Flame** | `#f97316` (Amber Flame) / `#ea580c` | 小火龙品牌龙焰橘，用于核心 CTA、关键状态点 |
| **Accent Terminal Green** | `#10b981` (Matrix Emerald) | 终端执行成功状态、测试绿灯与终端光标 |
| **Code & Terminal Font** | `JetBrains Mono, Fira Code, monospace` | 用于终端交互模拟器、命令与参数规范 |
| **UI & Display Font** | `Inter, -apple-system, sans-serif` | 现代高辨识度无衬线，字距严格控制在 `-0.02em` |

### 2.2 Craft Floor 绝对禁忌与质量底线

根据 `/impeccable` 规则：
- **禁止**：
  - 严禁滥用渐变字体（Gradient Text）；标题纯靠字阶、排版与对比度发声；
  - 严禁在标题上方加无意义的小分类胶囊（No Eyebrow / Kicker）；
  - 严禁使用 Emoji 代替图标，所有图标均采用手绘等宽 1.5px 笔触矢量 SVG；
  - 严禁生硬平铺的千篇一律卡片网格，采用异构 Bento 布局与层次化信息切片；
  - 严禁假大空的口号与大数字填充，所有特性均对应 15~30 行真实架构代码或真实命令。
- **细节打磨 (Browser Surfaces)**：
  - `::selection` 全局定制龙焰色彩；
  - 自定义精细滚动条（透明轨道，`#2e3444` 滑块）；
  - 代码一键复制气泡与动态成功反馈。

---

## 3. 官网架构与页面切片

```
website/
├── index.html            # 现代化自包含单页官网（语义化 HTML5，SEO 优化）
├── css/
│   ├── main.css          # 设计系统 Tokens、排版、Bento 布局与响应式断点
│   └── terminal.css      # JLine 风格交互终端模拟器专用样式
├── js/
│   ├── app.js            # 终端模拟器状态机、Tab 切换、一键复制与交互动效
│   └── terminal-data.js  # 预置 4 大真实场景的交互输入与彩色终端输出流
└── assets/
    ├── favicon.svg       # 小火龙高精矢量 Favicon
    └── og-image.png      # OpenGraph 社交分享封面图
```

### 3.1 核心板块规划

1. **Top Nav**：
   - 品牌标识：小火龙矢量焰标 + `XhlCLI`，当前版本徽标 `v1.0.0`；
   - 锚点导航：特性、架构、终端演示、指令速查、基准评测、GitHub。
2. **Hero Section**：
   - 核心大标题：“本地优先的终端 Coding Agent”；
   - 产品定位精述：“受控 ReAct · 隔离快照恢复 · DAG 并发规划 · 零 400 视觉防御 · 纯原生 Java 21”；
   - 一键安装组件：支持系统切换（macOS / Linux / Windows WSL），一键复制 `curl -fsSL https://raw.githubusercontent.com/sz-xiaohuolong/XhlCli/main/install.sh | bash`；
   - 双主要行动点：立即安装体验 vs 查看 GitHub 源码。
3. **Interactive Terminal Simulator (交互式终端模拟器)**：
   - 深度还原 JLine 4 双模终端真实体验，包含顶部状态栏、macOS 窗口控制三色点；
   - 提供 4 个可点击交互的典型工程场景：
     - **Scene 1: Multi-Agent 团队协同 (`/team`)**：Planner 拆解 DAG、Worker 1/2 并发编码、Reviewer 审查把关；
     - **Scene 2: 隔离快照原地自愈 (`/restore`)**：语法被破坏后秒级无损回滚，保护快照与零宿主 Git 污染；
     - **Scene 3: 图片多模态与视觉防御 (`@image`)**：输入半透明 PNG，白底平铺合成 + DeepSeek 纯文本安全降级；
     - **Scene 4: 后台持久任务与 Runtime API (`/task`)**：SQLite 事务原子认领、孤儿租约恢复与 SSE 游标拉取。
4. **Bento Technical Architecture (异构架构矩阵)**：
   - 6 大核心模块差异化排布：
     - 卡片 A (大跨度)：JGit 物理隔离 Side-History 架构；
     - 卡片 B：Bounded Parallelism 有界受控并发与 Kahn DAG 拓扑；
     - 卡片 C：PathGuard + CommandGuard + HITL 严格沙箱防护；
     - 卡片 D：多模态图像预处理与非视觉模型防御护栏；
     - 卡片 E：8 层确定性不可变 Prompt 与渐进式 Skill 索引；
     - 卡片 F：纯 Java 21 高性能工程（启动 < 300ms，内存低占用）。
5. **Interactive Command Cheat Sheet (全量命令速查表)**：
   - 支持全量斜杠指令筛选（全部 / 执行 / 治理 / 扩展 / 检索）；
   - `/plan`, `/team`, `/task`, `/snapshot`, `/restore`, `/mcp`, `/browser`, `/model`, `/skill`, `/prompt` 等用法与说明。
6. **Benchmark & Test Proofs (质量实测公证书)**：
   - 522/522 自动化测试 100% 绿灯；
   - 零死锁、零内存泄漏、零外部 Python 依赖。
7. **Footer**：
   - MIT License，GitHub 链接，文档导航，团队与鸣谢。

---

## 4. Vercel 部署规约 (`vercel.json`)

根目录与 `website/` 提供 `vercel.json`：
```json
{
  "version": 2,
  "cleanUrls": true,
  "headers": [
    {
      "source": "/(.*)",
      "headers": [
        { "key": "X-Content-Type-Options", "value": "nosniff" },
        { "key": "X-Frame-Options", "value": "DENY" },
        { "key": "X-XSS-Protection", "value": "1; mode=block" }
      ]
    },
    {
      "source": "/(css|js|assets)/(.*)",
      "headers": [
        { "key": "Cache-Control", "value": "public, max-age=31536000, immutable" }
      ]
    }
  ]
}
```

---

## 5. 开源分发安装脚本 (`install.sh`)

编写 `install.sh`：
1. 自动检测系统环境（Darwin / Linux / MINGW）；
2. 校验 Java 21+ 是否已安装，未安装时给出清晰安装指引（`brew install openjdk@21` 或 `apt install openjdk-21-jdk`）；
3. 下载或编译最新可执行 Fat JAR 至 `~/.xhlcli/bin/xhlcli.jar`；
4. 创建可执行启动脚本 `~/.xhlcli/bin/xhlcli` 并引导加入 `PATH`（`.zshrc` / `.bashrc`）；
5. 验证执行 `xhlcli --version`。
