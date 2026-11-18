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

import { authHeaders, esc } from './flowHelpers.js';
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

function setDocMeta(json) {
  doc.sha256 = String(json.sha256 || '');
  doc.parsed = !!json.parsed;
  const m = json.manifest || null;
  doc.pageCount = m && typeof m.page_count === 'number' ? m.page_count : null;
  doc.notePages = Array.isArray(json.note_pages) ? json.note_pages : [];
}

function renderIdentity(el) {
  const bits = [];
  bits.push(`sha256 <b>${esc(doc.sha256 ? doc.sha256.slice(0, 12) + '…' : '?')}</b>`);
  bits.push(doc.parsed
    ? `已解析 · ${doc.pageCount != null ? doc.pageCount + ' 页' : ''}`
    : '未解析（打开/备注不触发解析；提问才会）');
  bits.push(doc.notePages.length ? `备注页 ${doc.notePages.join(', ')}` : '暂无备注');
  el.innerHTML = bits.join('<br>');
}

async function openPdf(pathInput, identityEl, statusEl) {
  const path = pathInput.value.trim();
  if (!path) { statusEl.textContent = '请输入 PDF 绝对路径'; statusEl.className = 'pdfp-status err'; return; }
  statusEl.textContent = '打开中（不解析）…'; statusEl.className = 'pdfp-status';
  const { ok, body } = await api('/api/pdf/open', { method: 'POST', body: JSON.stringify({ path }) });
  if (!ok) {
    statusEl.textContent = `打开失败：${esc(body.error || 'unknown')}`;
    statusEl.className = 'pdfp-status err';
    return;
  }
  setDocMeta(body);
  doc.path = path;
  renderIdentity(identityEl);
  statusEl.textContent = '已打开（身份/备注态读取完成；未触发解析）';
  statusEl.className = 'pdfp-status ok';
}

async function loadNote(pageInput, snippetInput, noteArea, statusEl) {
  if (!doc.path) { statusEl.textContent = '先打开一个 PDF'; statusEl.className = 'pdfp-status err'; return; }
  const page = parseInt(pageInput.value, 10);
  if (!Number.isInteger(page) || page < 1) { statusEl.textContent = '页码须为 ≥1 的整数'; statusEl.className = 'pdfp-status err'; return; }
  const { ok, body } = await api(`/api/pdf/note?sha256=${encodeURIComponent(doc.sha256)}&page=${page}`);
  if (!ok) { statusEl.textContent = `读取失败：${esc(body.error || 'unknown')}`; statusEl.className = 'pdfp-status err'; return; }
  const note = body.note || null;
  noteArea.value = note ? String(note.text || '') : '';
  snippetInput.value = note ? String(note.snippet || '') : '';
  doc.version = note ? Number(note.version || 0) : 0;
  statusEl.textContent = body.exists
    ? `已读回第 ${page} 页备注（版本 ${doc.version}${body.page_verified ? '' : '，页界未验证（未解析）'}${body.out_of_range ? '，超出页数' : ''}）`
    : `第 ${page} 页暂无备注`;
  statusEl.className = 'pdfp-status ok';
}

async function saveNote(pageInput, snippetInput, noteArea, statusEl) {
  if (!doc.path) { statusEl.textContent = '先打开一个 PDF'; statusEl.className = 'pdfp-status err'; return; }
  const page = parseInt(pageInput.value, 10);
  if (!Number.isInteger(page) || page < 1) { statusEl.textContent = '页码须为 ≥1 的整数'; statusEl.className = 'pdfp-status err'; return; }
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
    statusEl.textContent = `冲突：备注已被他处修改（当前版本 ${doc.version}）——先读回再保存`;
    statusEl.className = 'pdfp-status err';
    return;
  }
  if (!ok) { statusEl.textContent = `保存失败：${esc(body.error || 'unknown')}`; statusEl.className = 'pdfp-status err'; return; }
  doc.version = Number(body.version || 0);
  if (Array.isArray(body.note_pages)) doc.notePages = body.note_pages;
  statusEl.textContent = `第 ${page} 页备注已保存（版本 ${doc.version}；未触发解析）`;
  statusEl.className = 'pdfp-status ok';
}

function renderAnswer(answerEl, body) {
  if (body.state === 'parsing') {
    answerEl.innerHTML = '<div class="pdfp-status">解析中（后台）…自动重试中</div>';
    return;
  }
  if (body.state === 'failed') {
    answerEl.innerHTML = `<div class="pdfp-status err">解析失败（${esc(body.error || '?')}）：${esc(body.message || '')}</div>`;
    return;
  }
  const cites = Array.isArray(body.citations) ? body.citations : [];
  const pr = body.page_reads || {};
  const excerpts = Array.isArray(body.excerpts) ? body.excerpts : [];
  const head = cites.length
    ? `<div>引用页：${cites.map((c) => `<span class="pdfp-citation">${esc(c)}</span>`).join('')}</div>`
    : '<div>本问没有命中页（换个问法试试）</div>';
  const ledger = `<div class="pdfp-status">命中共 ${pr.matched_pages ?? '?'} 页 · 实读 ${pr.pages_opened ?? '?'}/${pr.total_pages ?? '?'} 页（按需，未整篇读）</div>`;
  const blocks = excerpts.map((e) =>
    `<div><b>${esc(e.citation || `p.${e.page}`)}</b><pre>${esc(e.markdown || '')}</pre></div>`).join('');
  answerEl.innerHTML = head + ledger + blocks;
}

async function askPdf(questionInput, answerEl, statusEl, attempt = 0) {
  if (!doc.path) { statusEl.textContent = '先打开一个 PDF'; statusEl.className = 'pdfp-status err'; return; }
  const question = questionInput.value.trim();
  if (!question) { statusEl.textContent = '问题不能为空'; statusEl.className = 'pdfp-status err'; return; }
  statusEl.textContent = '提问中（首次提问会触发解析）…'; statusEl.className = 'pdfp-status';
  const { ok, status, body } = await api('/api/pdf/ask', {
    method: 'POST',
    body: JSON.stringify({ path: doc.path, question }),
  });
  if (status === 202 && body.state === 'parsing') {
    answerEl.innerHTML = '<div class="pdfp-status">解析中（后台）…自动重试中</div>';
    if (attempt < 5) {
      setTimeout(() => askPdf(questionInput, answerEl, statusEl, attempt + 1), 1500);
    } else {
      statusEl.textContent = '解析仍在后台进行——稍后重发同一问题即可';
      statusEl.className = 'pdfp-status err';
    }
    return;
  }
  if (!ok && body.state !== 'failed') {
    statusEl.textContent = `提问失败：${esc(body.error || 'unknown')}`;
    statusEl.className = 'pdfp-status err';
    return;
  }
  statusEl.textContent = body.state === 'failed' ? '失败态（可读，见下）' : '已回答';
  statusEl.className = body.state === 'failed' ? 'pdfp-status err' : 'pdfp-status ok';
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
  root.innerHTML = `
    <div class="pdfp-section">
      <span class="pdfp-section-title">打开（不解析）</span>
      <div class="pdfp-row"><input type="text" class="nf-field" id="pdfp-path" placeholder="/absolute/path/to.pdf" aria-label="PDF path">
        <button id="pdfp-open" title="打开（只读身份与备注态）">打开</button></div>
      <div class="pdfp-identity" id="pdfp-identity">尚未打开文档</div>
    </div>
    <div class="pdfp-section">
      <span class="pdfp-section-title">页锚备注</span>
      <div class="pdfp-row"><input type="number" class="nf-field" id="pdfp-page" min="1" value="1" aria-label="page number"></div>
      <input type="text" class="nf-field" id="pdfp-snippet" placeholder="可选：原文摘录（锚定证据）" aria-label="snippet">
      <textarea class="nf-field" id="pdfp-text" placeholder="本页备注…" aria-label="note text"></textarea>
      <div class="pdfp-row"><button id="pdfp-note-load">读回</button><button id="pdfp-note-save">保存</button></div>
    </div>
    <div class="pdfp-section">
      <span class="pdfp-section-title">提问（触发解析 · 答案带页码）</span>
      <textarea class="nf-field" id="pdfp-question" placeholder="问这份 PDF 的内容…" aria-label="question"></textarea>
      <div class="pdfp-row"><button id="pdfp-ask">提问</button><button id="pdfp-ask-agent" title="把对话绑定到该 PDF，交给 ask 智能体（PdfPage 按页取读）">转入 Ask 会话</button></div>
      <div class="pdfp-answer" id="pdfp-answer" hidden></div>
    </div>
    <div class="pdfp-status" id="pdfp-status">打开 → 备注 → 提问；只有提问会触发解析</div>`;

  const pathInput = root.querySelector('#pdfp-path');
  const identityEl = root.querySelector('#pdfp-identity');
  const statusEl = root.querySelector('#pdfp-status');
  const pageInput = root.querySelector('#pdfp-page');
  const snippetInput = root.querySelector('#pdfp-snippet');
  const noteArea = root.querySelector('#pdfp-text');
  const questionInput = root.querySelector('#pdfp-question');
  const answerEl = root.querySelector('#pdfp-answer');

  root.querySelector('#pdfp-open').addEventListener('click', () => openPdf(pathInput, identityEl, statusEl));
  root.querySelector('#pdfp-note-load').addEventListener('click', () => loadNote(pageInput, snippetInput, noteArea, statusEl));
  root.querySelector('#pdfp-note-save').addEventListener('click', () => saveNote(pageInput, snippetInput, noteArea, statusEl));
  root.querySelector('#pdfp-ask').addEventListener('click', () => {
    answerEl.hidden = false;
    askPdf(questionInput, answerEl, statusEl);
  });
  root.querySelector('#pdfp-ask-agent').addEventListener('click', () => handoffToAsk(identityEl));
  pathInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') openPdf(pathInput, identityEl, statusEl); });
}

export function initPdfPanel() {
  const root = document.getElementById('pdf-panel-body');
  if (!root) return;
  buildPanel(root);
  registerSidePanel({ id: PANEL_ID, buttonId: 'pdf-btn', panelId: PANEL_ELEM_ID });
}
