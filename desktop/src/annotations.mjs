export const MAX_PLAN_FEEDBACK_CHARS = 32000;
export const MAX_DIFF_COMMENT_SNIPPET_LINES = 8;
export const MAX_DIFF_COMMENT_SNIPPET_CHARS = 600;
export const MAX_STORED_DRAFTS = 100;
export const VALID_DIFF_WIDTHS = ['standard', 'wide', 'full'];

const DRAFT_PREFIX = 'xhl:draft:';
const DRAFT_INDEX_KEY = 'xhl:draft:index';
const DIFF_WIDTH_KEY = 'xhl:diff-panel-width';
const HUNK_HEADER_RE = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/;
const TRUNCATION_NOTICE = '差异过长，已截断显示。';

function defaultStorage() {
  try {
    return typeof globalThis !== 'undefined' && globalThis.localStorage ? globalThis.localStorage : null;
  } catch {
    return null;
  }
}

export function planDraftKey(sessionId, planId) {
  if (!planId) return '';
  return `plan:${sessionId || 'anon'}:${planId}`;
}

export function diffDraftKey(sessionId, turnId) {
  if (!turnId) return '';
  return `diff:${sessionId || 'anon'}:${turnId}`;
}

function isEmptyDraft(value) {
  if (!value || typeof value !== 'object') return true;
  const hasAnnotations = Array.isArray(value.annotations) && value.annotations.length > 0;
  const hasComments = Array.isArray(value.comments) && value.comments.length > 0;
  const hasGlobal = typeof value.globalFeedback === 'string' && value.globalFeedback.trim().length > 0;
  const hasGeneral = typeof value.generalNote === 'string' && value.generalNote.trim().length > 0;
  return !hasAnnotations && !hasComments && !hasGlobal && !hasGeneral;
}

function readDraftIndex(storage) {
  try {
    const raw = storage.getItem(DRAFT_INDEX_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter(item => typeof item === 'string') : [];
  } catch {
    return [];
  }
}

export function loadDraft(key, storage = defaultStorage()) {
  if (!key || !storage) return null;
  try {
    const raw = storage.getItem(DRAFT_PREFIX + key);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : null;
  } catch {
    return null;
  }
}

export function clearDraft(key, storage = defaultStorage()) {
  if (!key || !storage) return false;
  try {
    const existing = storage.getItem(DRAFT_PREFIX + key);
    const currentIndex = readDraftIndex(storage);
    const hasEntry = currentIndex.includes(key);
    if (existing == null && !hasEntry) return true;
    storage.removeItem(DRAFT_PREFIX + key);
    if (hasEntry) {
      const index = currentIndex.filter(item => item !== key);
      storage.setItem(DRAFT_INDEX_KEY, JSON.stringify(index));
    }
    return true;
  } catch {
    return false;
  }
}

export function saveDraft(key, value, storage = defaultStorage()) {
  if (!key || !storage) return false;
  if (isEmptyDraft(value)) {
    return clearDraft(key, storage);
  }
  try {
    storage.setItem(DRAFT_PREFIX + key, JSON.stringify(value));
    const nextIndex = readDraftIndex(storage).filter(item => item !== key);
    nextIndex.push(key);
    while (nextIndex.length > MAX_STORED_DRAFTS) {
      const oldest = nextIndex.shift();
      if (oldest) storage.removeItem(DRAFT_PREFIX + oldest);
    }
    storage.setItem(DRAFT_INDEX_KEY, JSON.stringify(nextIndex));
    return true;
  } catch {
    return false;
  }
}

export function loadDiffWidth(storage = defaultStorage()) {
  if (!storage) return 'standard';
  try {
    const value = storage.getItem(DIFF_WIDTH_KEY);
    return VALID_DIFF_WIDTHS.includes(value) ? value : 'standard';
  } catch {
    return 'standard';
  }
}

export function saveDiffWidth(width, storage = defaultStorage()) {
  const normalized = VALID_DIFF_WIDTHS.includes(width) ? width : 'standard';
  if (!storage) return false;
  try {
    storage.setItem(DIFF_WIDTH_KEY, normalized);
    return true;
  } catch {
    return false;
  }
}

export function parseUnifiedDiff(diffText) {
  if (typeof diffText !== 'string' || !diffText.length) return [];
  const normalized = diffText.replace(/\r\n/g, '\n');
  const rawLines = normalized.split('\n');
  const lines = [];
  let inHunk = false;
  let hunkIndex = -1;
  let oldCursor = 0;
  let newCursor = 0;
  let oldRemaining = 0;
  let newRemaining = 0;

  for (let i = 0; i < rawLines.length; i++) {
    const raw = rawLines[i];
    if (i === rawLines.length - 1 && raw === '') continue;

    if (raw === TRUNCATION_NOTICE) {
      lines.push({ index: lines.length, type: 'meta', oldLine: null, newLine: null, content: raw, raw, commentable: false, hunkIndex });
      continue;
    }

    const hunkMatch = HUNK_HEADER_RE.exec(raw);
    if (hunkMatch) {
      oldCursor = Number.parseInt(hunkMatch[1], 10);
      oldRemaining = hunkMatch[2] === undefined ? 1 : Number.parseInt(hunkMatch[2], 10);
      newCursor = Number.parseInt(hunkMatch[3], 10);
      newRemaining = hunkMatch[4] === undefined ? 1 : Number.parseInt(hunkMatch[4], 10);
      inHunk = true;
      hunkIndex += 1;
      lines.push({ index: lines.length, type: 'hunk', oldLine: null, newLine: null, content: raw, raw, commentable: false, hunkIndex });
      continue;
    }

    if (raw.startsWith('diff --git ')) {
      inHunk = false;
      oldRemaining = 0;
      newRemaining = 0;
      lines.push({ index: lines.length, type: 'header', oldLine: null, newLine: null, content: raw, raw, commentable: false, hunkIndex: -1 });
      continue;
    }

    if (!inHunk) {
      lines.push({ index: lines.length, type: 'header', oldLine: null, newLine: null, content: raw, raw, commentable: false, hunkIndex: -1 });
      continue;
    }

    if (raw.startsWith('\\')) {
      lines.push({ index: lines.length, type: 'meta', oldLine: null, newLine: null, content: raw, raw, commentable: false, hunkIndex });
      continue;
    }

    if (raw.startsWith('+')) {
      if (newRemaining > 0) newRemaining -= 1;
      lines.push({ index: lines.length, type: 'add', oldLine: null, newLine: newCursor++, content: raw.slice(1), raw, commentable: true, hunkIndex });
      continue;
    }

    if (raw.startsWith('-')) {
      if (oldRemaining > 0) oldRemaining -= 1;
      lines.push({ index: lines.length, type: 'del', oldLine: oldCursor++, newLine: null, content: raw.slice(1), raw, commentable: true, hunkIndex });
      continue;
    }

    if (raw.startsWith(' ') || (oldRemaining > 0 && newRemaining > 0 && raw === '')) {
      if (oldRemaining > 0) oldRemaining -= 1;
      if (newRemaining > 0) newRemaining -= 1;
      lines.push({ index: lines.length, type: 'context', oldLine: oldCursor++, newLine: newCursor++, content: raw.startsWith(' ') ? raw.slice(1) : raw, raw, commentable: true, hunkIndex });
      continue;
    }

    lines.push({ index: lines.length, type: 'meta', oldLine: null, newLine: null, content: raw, raw, commentable: false, hunkIndex });
  }

  return lines;
}

function buildSnippet(selectedLines) {
  const formatted = [];
  for (let i = 0; i < selectedLines.length && i < MAX_DIFF_COMMENT_SNIPPET_LINES; i++) {
    const line = selectedLines[i];
    const prefix = line.type === 'add' ? '+' : line.type === 'del' ? '-' : ' ';
    formatted.push(prefix + line.content);
  }
  let text = formatted.join('\n');
  if (selectedLines.length > MAX_DIFF_COMMENT_SNIPPET_LINES) {
    text += `\n... (共 ${selectedLines.length} 行)`;
  }
  if (text.length > MAX_DIFF_COMMENT_SNIPPET_CHARS) {
    text = text.slice(0, MAX_DIFF_COMMENT_SNIPPET_CHARS - 3) + '...';
  }
  return text;
}

export function buildDiffSelectionAnchor(filePath, parsedLines, startIdx, endIdx) {
  if (!filePath || !Array.isArray(parsedLines) || !parsedLines.length) return null;
  const lo = Math.max(0, Math.min(startIdx, endIdx));
  const hi = Math.min(parsedLines.length - 1, Math.max(startIdx, endIdx));
  const selected = parsedLines.slice(lo, hi + 1).filter(line => line?.commentable);
  if (!selected.length) return null;

  const newLines = selected.filter(line => line.newLine != null);
  const hasDeleted = selected.some(line => line.type === 'del');
  let side = 'old';
  let startLine = selected[0].oldLine;
  let endLine = selected.at(-1).oldLine;

  if (newLines.length > 0) {
    side = hasDeleted ? 'mixed' : 'new';
    startLine = newLines[0].newLine;
    endLine = newLines.at(-1).newLine;
  }

  return {
    filePath,
    side,
    startLine,
    endLine,
    anchorRowIndex: selected.at(-1).index,
    anchorLineType: selected.at(-1).type,
    selectedRowRange: [selected[0].index, selected.at(-1).index],
    snippet: buildSnippet(selected)
  };
}

export function formatDiffLineLabel(comment) {
  if (!comment || comment.startLine == null) return '';
  const same = comment.startLine === comment.endLine || comment.endLine == null;
  if (comment.side === 'old') {
    return same ? `old L${comment.startLine} (已删除)` : `old L${comment.startLine}-L${comment.endLine} (已删除)`;
  }
  return same ? `L${comment.startLine}` : `L${comment.startLine}-L${comment.endLine}`;
}

export function resolveDiffCommentRowIndex(comment, parsedLines) {
  if (!comment || !Array.isArray(parsedLines) || !parsedLines.length) return null;
  if (Number.isInteger(comment.anchorRowIndex)) {
    const direct = parsedLines[comment.anchorRowIndex];
    if (direct?.commentable) {
      if (comment.side === 'old' && direct.oldLine === comment.endLine) return direct.index;
      if (comment.side !== 'old' && direct.newLine === comment.endLine) return direct.index;
      if (comment.side === 'mixed' && direct.type === 'del') {
        for (let i = direct.index - 1; i >= 0; i--) {
          const prev = parsedLines[i];
          if (prev?.commentable && prev.newLine != null) {
            if (prev.newLine === comment.endLine) return direct.index;
            break;
          }
        }
      }
    }
  }
  const found = parsedLines.find(line => {
    if (!line.commentable) return false;
    return (comment.side === 'old' ? line.oldLine : line.newLine) === comment.endLine;
  });
  return found ? found.index : null;
}

function compactQuote(text, maxLength = 240) {
  const single = String(text || '').replace(/\s+/g, ' ').trim();
  if (single.length <= maxLength) return single;
  return single.slice(0, maxLength - 3) + '...';
}

function formatPlanTarget(ann) {
  if (ann?.targetKind === 'goal') return '计划目标';
  if (ann?.targetKind === 'summary') return '计划摘要';
  if (ann?.targetKind === 'step') {
    const num = ann.stepIndex != null ? ann.stepIndex : '?';
    return ann.taskId ? `步骤 ${num} (${ann.taskId})` : `步骤 ${num}`;
  }
  return '计划内容';
}

export function formatPlanFeedback({ annotations = [], globalFeedback = '' } = {}) {
  const sections = [];
  const trimmedGlobal = typeof globalFeedback === 'string' ? globalFeedback.trim() : '';
  if (trimmedGlobal) {
    sections.push(`### 全局意见\n${trimmedGlobal}`);
  }

  const valid = Array.isArray(annotations) ? annotations : [];
  const comments = valid.filter(item => item?.type === 'COMMENT' && typeof item.text === 'string' && item.text.trim());
  const deletions = valid.filter(item => item?.type === 'DELETION');

  if (comments.length > 0) {
    const lines = comments.map(item => {
      const target = formatPlanTarget(item);
      const quote = compactQuote(item.originalText);
      return `- **[${target}]** 引用：「${quote}」\n  批注：${item.text.trim()}`;
    });
    sections.push(`### 行内批注\n${lines.join('\n')}`);
  }

  if (deletions.length > 0) {
    const lines = deletions.map(item => {
      const target = formatPlanTarget(item);
      const quote = compactQuote(item.originalText);
      const reason = typeof item.text === 'string' && item.text.trim() ? item.text.trim() : '建议删除此片段';
      return `- **[${target}]** 删除引用：「${quote}」\n  说明：${reason}`;
    });
    sections.push(`### 建议删除内容\n${lines.join('\n')}`);
  }

  return sections.join('\n\n');
}

function fenceCode(snippet) {
  const text = String(snippet || '');
  let maxRun = 2;
  const matches = text.match(/`+/g);
  if (matches) {
    for (const m of matches) {
      if (m.length > maxRun) maxRun = m.length;
    }
  }
  const fence = '`'.repeat(maxRun + 1);
  return `${fence}\n${text}\n${fence}`;
}

export function formatDiffFeedback({ comments = [], generalNote = '' } = {}) {
  const validComments = (Array.isArray(comments) ? comments : []).filter(
    item => item && typeof item.text === 'string' && item.text.trim() && item.filePath
  );
  const trimmedNote = typeof generalNote === 'string' ? generalNote.trim() : '';
  if (!validComments.length && !trimmedNote) return '';

  const sections = ['## 代码变更修改意见'];
  if (trimmedNote) {
    sections.push(`### 总体说明\n${trimmedNote}`);
  }

  const byFile = new Map();
  for (const item of validComments) {
    if (!byFile.has(item.filePath)) byFile.set(item.filePath, []);
    byFile.get(item.filePath).push(item);
  }

  for (const [filePath, items] of byFile.entries()) {
    const lines = items.map(item => {
      const label = formatDiffLineLabel(item);
      const header = `- **行号 \`${label}\`**：${item.text.trim()}`;
      if (!item.snippet) return header;
      const indentedFence = fenceCode(item.snippet).split('\n').map(line => `  ${line}`).join('\n');
      return `${header}\n${indentedFence}`;
    });
    sections.push(`### 文件：\`${filePath}\`\n${lines.join('\n')}`);
  }

  return sections.join('\n\n');
}

export function appendDiffFeedbackToDraft(existingDraft, feedbackText) {
  const existing = typeof existingDraft === 'string' ? existingDraft : '';
  const feedback = typeof feedbackText === 'string' ? feedbackText.trim() : '';
  if (!feedback) return existing;
  if (!existing.trim()) return feedback;
  const trimmedExisting = existing.replace(/\s+$/, '');
  if (trimmedExisting === feedback || trimmedExisting.endsWith(feedback)) {
    return existing;
  }
  return `${trimmedExisting}\n\n${feedback}`;
}
