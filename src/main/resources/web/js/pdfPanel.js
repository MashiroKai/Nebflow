// pdfPanel.js — PDF 页锚备注 + 提问侧栏（PDF note/ask 批 2026-11-18）
//
// 三腿与 /api/pdf 域一一对应，懒解析判据在本面板同样成立：
//   · 打开（POST /api/pdf/open）与备注（GET/POST /api/pdf/note）**永不解析**——
//     缓存缺席时如实显示「未解析」，不偷偷起解析；
//   · 提问（POST /api/pdf/ask）是解析的唯一触发器；首次提问可能回 202 parsing
//     （后台解析中），本面板做有界自动重试，解析完成后渲染带页码引用的答案；
//   · 「转入 Ask 会话」= WS askOpen（智能体腿）：会话绑定该 PDF，模型经
//     PdfPage 工具按需取页，对话气泡在 ask 面板（与本面板互补，不重复渲染流）。
//
// 前端 ask 可问面镜像（mdHtmlGate.js）：.pdf 已并入 isAskableFile——后端同判据
// 单点 = nebflow.core.ask.AskSessions.isAskable。
//
// 输入件（input/textarea）挂 `nf-field`：全站输入面只有一个造型单源
// （css/inputSurface.css，input-surface 门禁 I2/I3），本面板只在 pdfPanel.css
// 里排布它们，不另画一套背景/边框/圆角。
//
// DOM 写法：**零裸 innerHTML**（component-contract 门禁 R5：新文件一个字面量都
// 不许有）。全部节点走 createElement + textContent —— 服务端文本（页码引用、页
// 正文、错误消息）一律以文本节点落地，拼接 HTML 的净化面在这里根本不存在。

import { authHeaders } from './flowHelpers.js';
import { registerSidePanel } from './activityBar.js';
import { sendWs } from './ws.js';

const PANEL_ID = 'pdf';
const PANEL_ELEM_ID = 'panel-pdf';

/** 面板内单文档状态（打开后非空；path 是唯一标识输入）。 */
const doc = { path: '', sha256: '', parsed: false, pageCount: null, notePages: [], version: 0 };

async function api(path, opts = {}) {
  const res = await fetch(path, {
    ...opts,
    headers: { 'Content-Type': 'application/json', ...authHeaders(), ...(opts.headers || {}) },
  });
  const body = await res.json().catch(() => ({}));
  return { ok: res.ok, status: res.status, body };
}

// ── 小构建器（替代 innerHTML 拼接）────────────────────────────────────────

function el(tag, cls, text) {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text != null) node.textContent = String(text);
  return node;
}

function row(...kids) {
  const r = el('div', 'pdfp-row');
  for (const k of kids) if (k) r.appendChild(k);
  return r;
}

function field(tag, id, opts = {}) {
  const node = el(tag, 'nf-field');
  node.id = id;
  if (opts.type) node.type = opts.type;
  if (opts.placeholder) node.placeholder = opts.placeholder;
  if (opts.aria) node.setAttribute('aria-label', opts.aria);
  if (opts.min != null) node.min = String(opts.min);
  if (opts.value != null) node.value = String(opts.value);
  return node;
}

function button(id, label, title) {
  const b = el('button', null, label);
  b.id = id;
  if (title) b.title = title;
  return b;
}

function section(title, ...kids) {
  const s = el('div', 'pdfp-section');
  s.appendChild(el('span', 'pdfp-section-title', title));
  for (const k of kids) if (k) s.appendChild(k);
  return s;
}

/** 用给定子节点替换掉一个容器的全部内容。 */
function setChildren(container, ...kids) {
  while (container.firstChild) container.removeChild(container.firstChild);
  for (const k of kids) if (k) container.appendChild(k);
}

function setDocMeta(json) {
  doc.sha256 = String(json.sha256 || '');
  doc.parsed = !!json.parsed;
  const m = json.manifest || null;
  doc.pageCount = m && typeof m.page_count === 'number' ? m.page_count : null;
  doc.notePages = Array.isArray(json.note_pages) ? json.note_pages : [];
}

function renderIdentity(identityEl) {
  const lines = [];
  const digest = el('div');
  digest.appendChild(document.createTextNode('sha256 '));
  const b = el('b', null, doc.sha256 ? `${doc.sha256.slice(0, 12)}…` : '?');
  digest.appendChild(b);
  lines.push(digest);
  lines.push(el('div', null, doc.parsed
    ? `已解析 · ${doc.pageCount != null ? `${doc.pageCount} 页` : ''}`
    : '未解析（打开/备注不触发解析；提问才会）'));
  lines.push(el('div', null, doc.notePages.length ? `备注页 ${doc.notePages.join(', ')}` : '暂无备注'));
  setChildren(identityEl, ...lines);
}

function setStatus(statusEl, text, kind) {
  statusEl.className = kind ? `pdfp-status ${kind}` : 'pdfp-status';
  statusEl.textContent = text;
}

async function openPdf(pathInput, identityEl, statusEl) {
  const path = pathInput.value.trim();
  if (!path) { setStatus(statusEl, '请输入 PDF 绝对路径', 'err'); return; }
  setStatus(statusEl, '打开中（不解析）…', '');
  const { ok, body } = await api('/api/pdf/open', { method: 'POST', body: JSON.stringify({ path }) });
  if (!ok) { setStatus(statusEl, `打开失败：${body.error || 'unknown'}`, 'err'); return; }
  setDocMeta(body);
  doc.path = path;
  renderIdentity(identityEl);
  setStatus(statusEl, '已打开（身份/备注态读取完成；未触发解析）', 'ok');
}

async function loadNote(pageInput, snippetInput, noteArea, statusEl) {
  if (!doc.path) { setStatus(statusEl, '先打开一个 PDF', 'err'); return; }
  const page = parseInt(pageInput.value, 10);
  if (!Number.isInteger(page) || page < 1) { setStatus(statusEl, '页码须为 ≥1 的整数', 'err'); return; }
  const { ok, body } = await api(`/api/pdf/note?sha256=${encodeURIComponent(doc.sha256)}&page=${page}`);
  if (!ok) { setStatus(statusEl, `读取失败：${body.error || 'unknown'}`, 'err'); return; }
  const note = body.note || null;
  noteArea.value = note ? String(note.text || '') : '';
  snippetInput.value = note ? String(note.snippet || '') : '';
  doc.version = note ? Number(note.version || 0) : 0;
  const flags = `${body.page_verified ? '' : '，页界未验证（未解析）'}${body.out_of_range ? '，超出页数' : ''}`;
  setStatus(statusEl, body.exists
    ? `已读回第 ${page} 页备注（版本 ${doc.version}${flags}）`
    : `第 ${page} 页暂无备注`, 'ok');
}

async function saveNote(pageInput, snippetInput, noteArea, statusEl) {
  if (!doc.path) { setStatus(statusEl, '先打开一个 PDF', 'err'); return; }
  const page = parseInt(pageInput.value, 10);
  if (!Number.isInteger(page) || page < 1) { setStatus(statusEl, '页码须为 ≥1 的整数', 'err'); return; }
  const { ok, status, body } = await api('/api/pdf/note', {
    method: 'POST',
    body: JSON.stringify({
      sha256: doc.sha256,
      page,
      text: noteArea.value,
      snippet: snippetInput.value.trim(),
      expected_version: doc.version,
    }),
  });
  if (status === 409) {
    doc.version = Number(body.current_version || 0);
    setStatus(statusEl, `冲突：备注已被他处修改（当前版本 ${doc.version}）——先读回再保存`, 'err');
    return;
  }
  if (!ok) { setStatus(statusEl, `保存失败：${body.error || 'unknown'}`, 'err'); return; }
  doc.version = Number(body.version || 0);
  if (Array.isArray(body.note_pages)) doc.notePages = body.note_pages;
  setStatus(statusEl, `第 ${page} 页备注已保存（版本 ${doc.version}；未触发解析）`, 'ok');
}

function renderAnswer(answerEl, body) {
  if (body.state === 'parsing') {
    setChildren(answerEl, el('div', 'pdfp-status', '解析中（后台）…自动重试中'));
    return;
  }
  if (body.state === 'failed') {
    setChildren(answerEl, el('div', 'pdfp-status err',
      `解析失败（${body.error || '?'}）：${body.message || ''}`));
    return;
  }
  const cites = Array.isArray(body.citations) ? body.citations : [];
  const pr = body.page_reads || {};
  const excerpts = Array.isArray(body.excerpts) ? body.excerpts : [];
  const head = el('div');
  if (cites.length) {
    head.appendChild(document.createTextNode('引用页：'));
    for (const c of cites) head.appendChild(el('span', 'pdfp-citation', c));
  } else {
    head.textContent = '本问没有命中页（换个问法试试）';
  }
  const ledger = el('div', 'pdfp-status',
    `命中共 ${pr.matched_pages ?? '?'} 页 · 实读 ${pr.pages_opened ?? '?'}/${pr.total_pages ?? '?'} 页（按需，未整篇读）`);
  const blocks = excerpts.map((e) => {
    const box = el('div');
    box.appendChild(el('b', null, e.citation || `p.${e.page}`));
    box.appendChild(el('pre', null, e.markdown || ''));
    return box;
  });
  setChildren(answerEl, head, ledger, ...blocks);
}

async function askPdf(questionInput, answerEl, statusEl, attempt = 0) {
  if (!doc.path) { setStatus(statusEl, '先打开一个 PDF', 'err'); return; }
  const question = questionInput.value.trim();
  if (!question) { setStatus(statusEl, '问题不能为空', 'err'); return; }
  setStatus(statusEl, '提问中（首次提问会触发解析）…', '');
  const { ok, status, body } = await api('/api/pdf/ask', {
    method: 'POST',
    body: JSON.stringify({ path: doc.path, question }),
  });
  if (status === 202 && body.state === 'parsing') {
    setChildren(answerEl, el('div', 'pdfp-status', '解析中（后台）…自动重试中'));
    if (attempt < 5) {
      setTimeout(() => askPdf(questionInput, answerEl, statusEl, attempt + 1), 1500);
    } else {
      setStatus(statusEl, '解析仍在后台进行——稍后重发同一问题即可', 'err');
    }
    return;
  }
  if (!ok && body.state !== 'failed') {
    setStatus(statusEl, `提问失败：${body.error || 'unknown'}`, 'err');
    return;
  }
  setStatus(statusEl, body.state === 'failed' ? '失败态（可读，见下）' : '已回答',
    body.state === 'failed' ? 'err' : 'ok');
  if (body.manifest) setDocMeta({ sha256: doc.sha256, parsed: true, manifest: body.manifest, note_pages: doc.notePages });
  renderAnswer(answerEl, body);
}

/** 智能体腿：askOpen 把对话绑到该 PDF（答案在 ask 面板流式呈现；模型经 PdfPage 取页）。 */
function handoffToAsk(identityEl) {
  if (!doc.path) return;
  const name = doc.path.split('/').pop() || 'document.pdf';
  sendWs({ type: 'askOpen', file: { absPath: doc.path, name }, mainSessionId: '' });
  renderIdentity(identityEl);
}

function buildPanel(root) {
  root.className = 'pdfp';

  const pathInput = field('input', 'pdfp-path', { type: 'text', placeholder: '/absolute/path/to.pdf', aria: 'PDF path' });
  const openBtn = button('pdfp-open', '打开', '打开（只读身份与备注态）');
  const identityEl = el('div', 'pdfp-identity', '尚未打开文档');

  const pageInput = field('input', 'pdfp-page', { type: 'number', min: 1, value: 1, aria: 'page number' });
  const snippetInput = field('input', 'pdfp-snippet', { type: 'text', placeholder: '可选：原文摘录（锚定证据）', aria: 'snippet' });
  const noteArea = field('textarea', 'pdfp-text', { placeholder: '本页备注…', aria: 'note text' });
  const noteLoadBtn = button('pdfp-note-load', '读回');
  const noteSaveBtn = button('pdfp-note-save', '保存');

  const questionInput = field('textarea', 'pdfp-question', { placeholder: '问这份 PDF 的内容…', aria: 'question' });
  const askBtn = button('pdfp-ask', '提问');
  const askAgentBtn = button('pdfp-ask-agent', '转入 Ask 会话', '把对话绑定到该 PDF，交给 ask 智能体（PdfPage 按页取读）');
  const answerEl = el('div', 'pdfp-answer');
  answerEl.id = 'pdfp-answer';
  answerEl.hidden = true;

  const statusEl = el('div', 'pdfp-status', '打开 → 备注 → 提问；只有提问会触发解析');
  statusEl.id = 'pdfp-status';

  setChildren(
    root,
    section('打开（不解析）', row(pathInput, openBtn), identityEl),
    section('页锚备注', row(pageInput), snippetInput, noteArea, row(noteLoadBtn, noteSaveBtn)),
    section('提问（触发解析 · 答案带页码）', questionInput, row(askBtn, askAgentBtn), answerEl),
    statusEl,
  );

  openBtn.addEventListener('click', () => openPdf(pathInput, identityEl, statusEl));
  noteLoadBtn.addEventListener('click', () => loadNote(pageInput, snippetInput, noteArea, statusEl));
  noteSaveBtn.addEventListener('click', () => saveNote(pageInput, snippetInput, noteArea, statusEl));
  askBtn.addEventListener('click', () => {
    answerEl.hidden = false;
    askPdf(questionInput, answerEl, statusEl);
  });
  askAgentBtn.addEventListener('click', () => handoffToAsk(identityEl));
  pathInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') openPdf(pathInput, identityEl, statusEl); });
}

export function initPdfPanel() {
  const root = document.getElementById('pdf-panel-body');
  if (!root) return;
  buildPanel(root);
  registerSidePanel({ id: PANEL_ID, buttonId: 'pdf-btn', panelId: PANEL_ELEM_ID });
}
