# Phase 18 实施计划：开源发布治理与静态官网

> **实施目标：** 按照 PRD 与技术设计方案规约，完整交付 Phase 18：
> 1. 打造具备顶级审美（遵循 `/impeccable` 准则，参考 `qoder.cn`）的可直接在 Vercel 部署的现代化静态官方网站；
> 2. 构建生产级开源发布治理脚手架与跨平台一键安装脚本；
> 3. 发布正式生产版本 `v1.0.0`。
> **前置门禁：** Human Approval Gate（技术设计与任务边界获用户批准）  
> **目标发布：** `v1.0.0`

---

## 1. 任务分解清单 (WBS)

### Task 1: 静态官网工程骨架与设计系统 Tokens (`website/` + CSS Tokens)
- [ ] 创建 `website/` 目录结构（`css/`, `js/`, `assets/`）；
- [ ] 编写 `css/main.css`：定义暗黑工程师调色板、字阶排版、Bento 异构网格、统一 1.5px 笔触矢量 SVG 图标样式；
- [ ] 编写 `css/terminal.css`：定义 JLine 4 风格终端视窗、状态栏、Unified Diff 着色与光标脉冲动效。

### Task 2: 交互式终端模拟器与预置场景引擎 (`terminal-data.js` + `app.js`)
- [ ] 编写 `website/js/terminal-data.js`：准备 4 大真实场景数据（Multi-Agent 团队协同、快照自愈回滚、多模态白底合成降级、后台任务与 SSE 流）；
- [ ] 编写 `website/js/app.js`：实现终端视窗场景切换、逐行彩色输出模拟、命令一键复制、操作系统安装指令切换与速查表实时搜索过滤。

### Task 3: 官网页面编排与内容打磨 (`website/index.html`)
- [ ] 编写语义化 `website/index.html`：
  - Top Nav（品牌 Logo、导航锚点、GitHub 徽标）；
  - Hero 英雄区（大标题、精炼产品定位、一键安装框、双行动 CTA）；
  - Interactive Terminal（可交互点击体验的真实终端模拟器）；
  - Bento 异构架构矩阵（6 大核心技术壁垒卡片，带源码切片与架构亮点）；
  - Interactive Command Cheat Sheet（全量斜杠命令交互式分类速查表）；
  - Benchmark & Test Proofs（522 项全量自动化测试 100% 绿灯公证）；
  - Footer（MIT License、文档导航、版本信息）；
- [ ] 遵循 `/impeccable` Craft Floor 规范：无 kicker、无渐变字、无廉价卡片、无系统 Emoji；
- [ ] 制作小火龙高精 Favicon 矢量图 `assets/favicon.svg`。

### Task 4: Vercel 极速部署配置与本地验证
- [ ] 创建 `vercel.json` 配置文件（支持 Clean URLs 与长效静态资产缓存）；
- [ ] 验证静态网站双击打开与静态托管零依赖、控制台 0 错误。

### Task 5: 开源一键安装脚本与治理脚手架 (`install.sh` + `.github/`)
- [ ] 编写根目录跨平台安装脚本 `install.sh`（自动检测架构、Java 21 环境校验、软链配置）；
- [ ] 配置 `.github/ISSUE_TEMPLATE/`（Bug Report, Feature Request）；
- [ ] 编写安装脚本与模板的自动化校验测试。

### Task 6: 全量质量回归、Living Docs 同步与 v1.0.0 正式发布
- [ ] 升级 `pom.xml` 至生产版本 `1.0.0`，构建正式 Fat JAR；
- [ ] 运行全量自动化测试保持 100% 绿灯（522+ 项测试）；
- [ ] 产出评测报告 `docs/engineering/release-and-website-evaluation.md`；
- [ ] 更新全部 Living Docs (`PROJECT.md`, `ROADMAP.md`, `TECH_DESIGN.md`, `CHANGELOG.md`, `AGENTS.md`, `README.md`)；
- [ ] 执行 Git 提交、打生产 Tag `v1.0.0` 并 Push。

---

## 2. 交付准则 (Definition of Done)
1. **视觉水准出类拔萃**：严格执行 `/impeccable` 与 `craft-floor.md` 规约，具备如同 Qoder 的工业级暗黑终端美学，杜绝所有 AI 俗套模板感；
2. **零配置 Vercel 部署**：推送到代码库后，在 Vercel 导入即可直接秒级上线，且本地双击 `index.html` 即可完整体验；
3. **真实可信**：终端模拟器与架构介绍完全源自真实系统实现（522 个单测、JGit 隔离快照、Alpha Flatten 多模态预处理）；
4. **全量回归 100% 绿灯**：所有 522 项测试无任何回退。
