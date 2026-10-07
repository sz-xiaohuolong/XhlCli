/**
 * XhlCLI Official Website - Application Scripts
 * Pure vanilla JavaScript, zero runtime dependencies.
 */

(function () {
  'use strict';

  // --- 1. Terminal Simulator Engine ---
  const terminalBody = document.getElementById('terminalBody');
  const termTabs = document.querySelectorAll('.term-tab');
  const termBadge = document.getElementById('termBadge');
  const termStatus = document.getElementById('termStatus');
  const termModel = document.getElementById('termModel');
  const termTokens = document.getElementById('termTokens');
  const termExt = document.getElementById('termExt');
  const termReplayBtn = document.getElementById('termReplayBtn');

  let currentScenarioId = 'team';
  let animationTimeouts = [];

  function clearAnimation() {
    animationTimeouts.forEach(t => clearTimeout(t));
    animationTimeouts = [];
  }

  function renderTerminalScenario(scenarioId, animate = true) {
    if (!window.TERMINAL_SCENARIOS || !window.TERMINAL_SCENARIOS[scenarioId]) {
      return;
    }
    clearAnimation();
    currentScenarioId = scenarioId;
    const data = window.TERMINAL_SCENARIOS[scenarioId];

    // Update Status Bar
    if (termBadge) {
      termBadge.textContent = data.statusBadge;
      termBadge.className = 'status-badge ' + data.statusBadgeClass;
    }
    if (termStatus) termStatus.textContent = data.statusText;
    if (termModel) termModel.textContent = data.model;
    if (termTokens) termTokens.textContent = data.tokens;
    if (termExt) termExt.textContent = data.extensions;

    // Reset Terminal Content
    if (!terminalBody) return;
    terminalBody.innerHTML = '';

    if (!animate) {
      // Immediate render
      data.lines.forEach(line => {
        terminalBody.appendChild(createTerminalLineElement(line));
      });
      addCursor();
      terminalBody.scrollTop = terminalBody.scrollHeight;
      return;
    }

    // Step-by-step progressive animation
    let delay = 60;
    data.lines.forEach((line, index) => {
      const lineDelay = delay;
      const t = setTimeout(() => {
        removeCursor();
        terminalBody.appendChild(createTerminalLineElement(line));
        addCursor();
        terminalBody.scrollTop = terminalBody.scrollHeight;
      }, lineDelay);
      animationTimeouts.push(t);

      // Variable pacing for realistic terminal output
      if (line.type === 'prompt') delay += 400;
      else if (line.type === 'tool') delay += 500;
      else if (line.type === 'agent') delay += 350;
      else delay += 220;
    });
  }

  function addCursor() {
    removeCursor();
    const cursor = document.createElement('span');
    cursor.className = 'term-cursor';
    cursor.id = 'termLiveCursor';
    terminalBody.appendChild(cursor);
  }

  function removeCursor() {
    const cur = document.getElementById('termLiveCursor');
    if (cur) cur.remove();
  }

  function escapeHtml(str) {
    if (!str) return '';
    return str
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  function createTerminalLineElement(line) {
    const div = document.createElement('div');
    div.className = 'term-line';

    switch (line.type) {
      case 'prompt':
        div.className += ' term-line-prompt';
        div.innerHTML = `<span class="term-prompt-symbol">xhlcli&gt;</span> <span class="term-prompt-cmd">${escapeHtml(line.text)}</span>`;
        break;

      case 'agent':
        div.className += ' term-line-agent';
        div.innerHTML = `<span class="term-agent-name">[${escapeHtml(line.text)}]</span> <span class="term-agent-content">${escapeHtml(line.content)}</span>`;
        break;

      case 'tool':
        div.className += ' term-line-tool';
        div.innerHTML = `<div class="term-tool-box">
          <div class="term-tool-header">⚡ ${escapeHtml(line.header)}</div>
          <div class="term-tool-body">${escapeHtml(line.content)}</div>
        </div>`;
        break;

      case 'diff':
        div.className += ' term-line-diff';
        div.innerHTML = `<div class="term-diff-block">
          <div class="term-diff-rem">${escapeHtml(line.removed)}</div>
          <div class="term-diff-add">${escapeHtml(line.added)}</div>
        </div>`;
        break;

      case 'meta':
        div.className += ' term-line-meta';
        div.textContent = line.text;
        break;

      case 'info':
        div.className += ' term-line-info';
        div.textContent = line.content;
        break;

      case 'warn':
        div.className += ' term-line-warn';
        div.textContent = line.content;
        break;

      case 'success':
        div.className += ' term-line-success';
        div.textContent = line.content;
        break;

      default:
        div.textContent = line.content || line.text || '';
    }

    return div;
  }

  // Bind scenario tabs
  termTabs.forEach(tab => {
    tab.addEventListener('click', () => {
      const scenario = tab.getAttribute('data-scenario');
      if (scenario === currentScenarioId) return;

      termTabs.forEach(t => t.classList.remove('active'));
      tab.classList.add('active');
      renderTerminalScenario(scenario, true);
    });
  });

  if (termReplayBtn) {
    termReplayBtn.addEventListener('click', () => {
      renderTerminalScenario(currentScenarioId, true);
    });
  }

  // --- 2. Install Command Tabs & Copy Engine ---
  const installTabs = document.querySelectorAll('.install-tab');
  const installCmdElem = document.getElementById('installCommandText');
  const installCopyBtn = document.getElementById('installCopyBtn');

  const INSTALL_COMMANDS = {
    curl: 'curl -fsSL https://raw.githubusercontent.com/sz-xiaohuolong/XhlCli/main/install.sh | bash',
    brew: 'brew install openjdk@21 && curl -fsSL https://raw.githubusercontent.com/sz-xiaohuolong/XhlCli/main/install.sh | bash',
    powershell: 'Invoke-WebRequest -Uri https://raw.githubusercontent.com/sz-xiaohuolong/XhlCli/main/install.sh -OutFile install.sh; bash install.sh',
    source: 'git clone https://github.com/sz-xiaohuolong/XhlCli.git && cd XhlCli && ./mvnw clean package -DskipTests'
  };

  installTabs.forEach(tab => {
    tab.addEventListener('click', () => {
      installTabs.forEach(t => t.classList.remove('active'));
      tab.classList.add('active');
      const target = tab.getAttribute('data-install');
      if (INSTALL_COMMANDS[target] && installCmdElem) {
        installCmdElem.textContent = INSTALL_COMMANDS[target];
      }
    });
  });

  if (installCopyBtn && installCmdElem) {
    installCopyBtn.addEventListener('click', () => {
      const textToCopy = installCmdElem.textContent.trim();
      copyToClipboard(textToCopy, installCopyBtn);
    });
  }

  // --- 3. Global Copy To Clipboard Helper & Toast ---
  function copyToClipboard(text, triggerBtn) {
    navigator.clipboard.writeText(text).then(() => {
      showToast('已复制到剪贴板: ' + text);
      if (triggerBtn) {
        const originalHtml = triggerBtn.innerHTML;
        triggerBtn.classList.add('copied');
        triggerBtn.innerHTML = `
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="20 6 9 17 4 12"></polyline>
          </svg>
          <span>已复制</span>
        `;
        setTimeout(() => {
          triggerBtn.classList.remove('copied');
          triggerBtn.innerHTML = originalHtml;
        }, 2000);
      }
    }).catch(err => {
      console.error('Copy failed:', err);
      showToast('复制失败，请手动选择复制');
    });
  }

  // Setup generic copy buttons with [data-copy]
  document.querySelectorAll('[data-copy]').forEach(btn => {
    btn.addEventListener('click', () => {
      const text = btn.getAttribute('data-copy');
      if (text) copyToClipboard(text, btn);
    });
  });

  // Toast Notification
  let toastTimeout;
  function showToast(message) {
    let toast = document.getElementById('globalToast');
    if (!toast) {
      toast = document.createElement('div');
      toast.id = 'globalToast';
      toast.className = 'toast-notification';
      document.body.appendChild(toast);
    }
    toast.textContent = message;
    toast.classList.add('visible');

    clearTimeout(toastTimeout);
    toastTimeout = setTimeout(() => {
      toast.classList.remove('visible');
    }, 2500);
  }

  // --- 4. Interactive Cheat Sheet Engine ---
  const CHEAT_COMMANDS = [
    {
      cmd: "/team <task>",
      category: "team",
      desc: "启动 Multi-Agent 专职协作管线 (Planner / 2 Workers / Reviewer)",
      example: "/team 重构订单分库分表与压测单测",
      phase: "Phase 15"
    },
    {
      cmd: "/team status",
      category: "team",
      desc: "查看团队当前执行状态、DAG 批次进度与并发 Worker 日志",
      example: "/team status",
      phase: "Phase 15"
    },
    {
      cmd: "/team stop",
      category: "team",
      desc: "主动优雅终止运行中的团队协作管线，保留中间产物",
      example: "/team stop",
      phase: "Phase 15"
    },
    {
      cmd: "/task add <desc>",
      category: "runtime",
      desc: "向后台持久化任务队列推入异步任务，支持断网恢复与离线执行",
      example: "/task add \"全量扫描 120 个模块并生成 AST 增量索引\"",
      phase: "Phase 17A"
    },
    {
      cmd: "/task list",
      category: "runtime",
      desc: "列出后台任务队列，查看所有入队、运行中与已完成的任务状态",
      example: "/task list",
      phase: "Phase 17A"
    },
    {
      cmd: "/task status <id>",
      category: "runtime",
      desc: "查看指定后台任务的实时详情、耗时与游标执行日志",
      example: "/task status task_9f2a",
      phase: "Phase 17A"
    },
    {
      cmd: "/task cancel <id>",
      category: "runtime",
      desc: "取消指定的后台持久化任务",
      example: "/task cancel task_9f2a",
      phase: "Phase 17A"
    },
    {
      cmd: "/snapshot list",
      category: "snapshot",
      desc: "查看本地 SideGit 隔离快照历史树，列出每轮操作前的保护快照",
      example: "/snapshot list",
      phase: "Phase 16"
    },
    {
      cmd: "/snapshot diff [offset]",
      category: "snapshot",
      desc: "对比当前工作区与指定保护快照的代码差异（精准 Diff）",
      example: "/snapshot diff 1",
      phase: "Phase 16"
    },
    {
      cmd: "/restore <offset>",
      category: "snapshot",
      desc: "物理原子回滚工作区至指定快照（毫秒级，宿主 Git 零污染）",
      example: "/restore 1",
      phase: "Phase 16"
    },
    {
      cmd: "@image:<path>",
      category: "vision",
      desc: "挂载本地或绝对路径图片，自动执行 Alpha 平铺、双三次缩放与元信息注入",
      example: "@image:docs/assets/architecture.png 审查数据库层流向",
      phase: "Phase 17B"
    },
    {
      cmd: "@clipboard",
      category: "vision",
      desc: "自动抓取系统剪贴板中的最新截图，直接注入为多模态上下文",
      example: "请帮我分析这张报错截图 @clipboard",
      phase: "Phase 17B"
    },
    {
      cmd: "/mode [code|ask|architect]",
      category: "config",
      desc: "实时切换 Agent 执行风格与专业 Persona 角色预设",
      example: "/mode code",
      phase: "Phase 02"
    },
    {
      cmd: "/model use <name>",
      category: "config",
      desc: "切换对话模型 (支持 DeepSeek-V3/R1, Claude 3.5, GPT-4o, Ollama 本地)",
      example: "/model use claude-3-5-sonnet",
      phase: "Phase 08"
    },
    {
      cmd: "/model",
      category: "config",
      desc: "查看当前使用的模型、上下文窗口限制与已配置模型列表",
      example: "/model",
      phase: "Phase 08"
    },
    {
      cmd: "/mcp list",
      category: "config",
      desc: "列出已挂载的 MCP (Model Context Protocol) 外部工具与服务状态",
      example: "/mcp list",
      phase: "Phase 06"
    },
    {
      cmd: "/skills list",
      category: "config",
      desc: "查看当前工作区已启用的专业技能包 (Skills)",
      example: "/skills list",
      phase: "Phase 10"
    },
    {
      cmd: "/token",
      category: "config",
      desc: "查看当前会话的 Token 预算分布、历史压缩水位与消耗明细",
      example: "/token",
      phase: "Phase 09"
    },
    {
      cmd: "/context clear",
      category: "config",
      desc: "重置当前会话上下文并保留配置与快照存储",
      example: "/context clear",
      phase: "Phase 03"
    },
    {
      cmd: "/exit",
      category: "config",
      desc: "安全持久化所有状态与历史，退出 XhlCLI",
      example: "/exit",
      phase: "Phase 01"
    }
  ];

  const cheatTableBody = document.getElementById('cheatTableBody');
  const cheatSearchInput = document.getElementById('cheatSearch');
  const cheatFilterBtns = document.querySelectorAll('.cheat-filter-btn');
  const cheatCountBadge = document.getElementById('cheatCountBadge');

  let activeCategory = 'all';
  let activeSearchQuery = '';

  function renderCheatSheet() {
    if (!cheatTableBody) return;

    const filtered = CHEAT_COMMANDS.filter(item => {
      const matchesCategory = activeCategory === 'all' || item.category === activeCategory;
      const query = activeSearchQuery.toLowerCase().trim();
      const matchesSearch = !query ||
        item.cmd.toLowerCase().includes(query) ||
        item.desc.toLowerCase().includes(query) ||
        item.example.toLowerCase().includes(query) ||
        item.phase.toLowerCase().includes(query);
      return matchesCategory && matchesSearch;
    });

    if (cheatCountBadge) {
      cheatCountBadge.textContent = `显示 ${filtered.length} / ${CHEAT_COMMANDS.length} 个指令`;
    }

    if (filtered.length === 0) {
      cheatTableBody.innerHTML = `
        <tr>
          <td colspan="4" class="cheat-empty-cell">未找到匹配 "${escapeHtml(activeSearchQuery)}" 的指令</td>
        </tr>
      `;
      return;
    }

    cheatTableBody.innerHTML = filtered.map(item => {
      return `
        <tr>
          <td class="cheat-cmd-cell">
            <code>${escapeHtml(item.cmd)}</code>
            <button class="cheat-copy-btn" title="复制指令" data-copy-cmd="${escapeHtml(item.cmd.split(' ')[0])}">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
              </svg>
            </button>
          </td>
          <td class="cheat-desc-cell">${escapeHtml(item.desc)}</td>
          <td class="cheat-example-cell"><code>${escapeHtml(item.example)}</code></td>
          <td class="cheat-phase-cell"><span class="phase-tag">${escapeHtml(item.phase)}</span></td>
        </tr>
      `;
    }).join('');

    // Bind inline copy buttons in the table
    cheatTableBody.querySelectorAll('[data-copy-cmd]').forEach(btn => {
      btn.addEventListener('click', () => {
        const cmd = btn.getAttribute('data-copy-cmd');
        copyToClipboard(cmd, btn);
      });
    });
  }

  if (cheatSearchInput) {
    cheatSearchInput.addEventListener('input', (e) => {
      activeSearchQuery = e.target.value;
      renderCheatSheet();
    });
  }

  cheatFilterBtns.forEach(btn => {
    btn.addEventListener('click', () => {
      cheatFilterBtns.forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      activeCategory = btn.getAttribute('data-category');
      renderCheatSheet();
    });
  });

  // --- 5. Smooth Scroll for Navigation ---
  document.querySelectorAll('a[href^="#"]').forEach(anchor => {
    anchor.addEventListener('click', function (e) {
      const targetId = this.getAttribute('href');
      if (targetId === '#') return;
      const targetElem = document.querySelector(targetId);
      if (targetElem) {
        e.preventDefault();
        targetElem.scrollIntoView({
          behavior: 'smooth',
          block: 'start'
        });
      }
    });
  });

  // Initialize
  document.addEventListener('DOMContentLoaded', () => {
    renderTerminalScenario('team', true);
    renderCheatSheet();
  });

})();
