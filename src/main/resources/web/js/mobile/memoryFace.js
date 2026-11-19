// mobile/memoryFace.js — P5「记忆」手机面（mobile-secondary-screens 实施批；
// 样张 v3.5 P5 + 开放点①②③按设计批推荐，④后端无字段不实施）。
//
// 形态（样张 P5 口径）：
//   · 读态 = 条目卡列表（.mh-memory-cards）：markdown 按「## 标题」分节切卡
//     （开放点①采纳【新增提案】：切分规则 = ## 标题行；导言行 = 以首行为题的
//     无签卡）；卡 = 标题 + 摘录（两行截断）+ 脚注（scope 文件名）。
//     卡脚「更新时间」（开放点④）不实施：后端不透出 mtime，不伪造。
//   · 编辑态（开放点③采纳：唤既有编辑器、不做移动编辑器）：头部「编辑」钮
//     ⇄ 卡读态切换 —— 编辑态 = 桌面原 textarea（#memory-content-input）+
//     Save/Cancel 原样，零新编辑器。
//   · scope seg 措辞（开放点②）：「智能体 · memory.md / 用户 · User.md」
//     （新键 memory.scopeAgent/scopeUser）；拆层还原桌面原文。
//
// 数据缝（memory.js 两处加法，桌面恒 undefined 零行为改动）：内容面值由 WS
// 异步写 textarea（handleMemoryData/loadTab 程序赋值不触发 input 事件），
// memory.js 在手机层调 window.__mhMemorySync 钩子 → 本文件重切卡。
// 桌面零改动边界：本文件只追加自有节点（卡容器/编辑钮）+ 换 tab 文案（拆层
// 还原）；textarea 显隐全走 CSS（body.mh-memory-editing 类）。

import { t } from '../i18n.js';

/** @type {HTMLElement|null} 卡读态容器。 */
let cardsEl = null;
/** @type {HTMLButtonElement|null} 头部「编辑/完成」钮。 */
let editBtn = null;
/** @type {string[]} 拆层还原用的桌面 tab 原文案。 */
const tabOriginal = [];

/** 「## 标题」分节切（开放点①切分规则）：返回 [{title, excerpt}]。
 *  文档级 H1 行（`# …`）跳过不入卡；导言（首个 ## 之前的正文行）= 以首行为
 *  题的无签卡。 */
function parseSections(md) {
  const out = [];
  let cur = null;
  for (const line of String(md || '').split(/\r?\n/)) {
    if (/^#\s+/.test(line)) continue; // H1 文档题（如「# 记忆」）
    if (/^##\s+/.test(line)) {
      if (cur) out.push(cur);
      cur = { title: line.replace(/^##\s+/, '').trim(), excerpt: '' };
    } else if (cur) {
      if (!cur.excerpt && line.trim()) cur.excerpt = line.trim();
    } else if (line.trim() && out.length === 0) {
      // 导言（首个 ## 之前）：以首行为题的无签卡。
      cur = { title: line.trim(), excerpt: '' };
    }
  }
  if (cur) out.push(cur);
  return out;
}

function scopeFileOf() {
  // 当前 scope 的落盘文件名（memory.js：agent→memory.md / user→User.md 同源）。
  const active = document.querySelector('.memory-tab.active')?.getAttribute('data-scope') || 'agent';
  return active === 'user' ? 'User.md' : 'memory.md';
}

function renderCards() {
  if (!cardsEl) return;
  const input = /** @type {HTMLTextAreaElement|null} */ (document.getElementById('memory-content-input'));
  const md = input?.value || '';
  cardsEl.replaceChildren();
  const sections = parseSections(md);
  if (!md.trim()) {
    const empty = document.createElement('div');
    empty.className = 'mh-memory-empty';
    empty.textContent = t('memory.emptyHint');
    cardsEl.appendChild(empty);
    return;
  }
  for (const s of sections) {
    const card = document.createElement('div');
    card.className = 'mh-memory-card';
    const ttl = document.createElement('div');
    ttl.className = 'mh-memory-card-title';
    ttl.textContent = s.title;
    card.appendChild(ttl);
    if (s.excerpt) {
      const ex = document.createElement('div');
      ex.className = 'mh-memory-card-excerpt';
      ex.textContent = s.excerpt;
      card.appendChild(ex);
    }
    const foot = document.createElement('div');
    foot.className = 'mh-memory-card-foot';
    foot.textContent = scopeFileOf(); // 开放点④：更新时间后端无字段，脚注仅 scope 文件名
    card.appendChild(foot);
    cardsEl.appendChild(card);
  }
}

function syncTabsWording() {
  document.querySelectorAll('.memory-tab').forEach((tab) => {
    const scope = tab.getAttribute('data-scope');
    const key = scope === 'user' ? 'memory.scopeUser' : 'memory.scopeAgent';
    tab.textContent = t(key);
  });
}

function setEditing(on) {
  document.body.classList.toggle('mh-memory-editing', on);
  if (editBtn) {
    editBtn.textContent = on ? t('memory.done') : t('memory.edit');
    editBtn.setAttribute('aria-pressed', on ? 'true' : 'false');
  }
}

export function activateMemoryFace() {
  const modal = document.getElementById('memory-modal');
  if (!modal) return () => {};

  // 头部「编辑」钮（.memory-tabs 行尾；桌面无此钮 —— 手机层自建）。
  const tabs = modal.querySelector('.memory-tabs');
  if (tabs && !modal.querySelector('.mh-memory-edit')) {
    editBtn = document.createElement('button');
    editBtn.type = 'button';
    editBtn.className = 'mh-memory-edit';
    editBtn.setAttribute('aria-pressed', 'false');
    editBtn.textContent = t('memory.edit');
    editBtn.addEventListener('click', () => {
      setEditing(!document.body.classList.contains('mh-memory-editing'));
      renderCards(); // 编辑态回读态时按最新内容重切
    });
    tabs.appendChild(editBtn);
  }

  // 卡读态容器（textarea 体之前；编辑态由 CSS 隐）。
  const body = document.getElementById('memory-modal-body');
  if (body && !document.getElementById('mh-memory-cards')) {
    cardsEl = document.createElement('div');
    cardsEl.id = 'mh-memory-cards';
    cardsEl.className = 'mh-memory-cards';
    body.parentElement.insertBefore(cardsEl, body);
  }

  // scope 措辞（开放点②；记录桌面原文供拆层还原）。
  document.querySelectorAll('.memory-tab').forEach((tab) => {
    tabOriginal.push(tab.textContent || '');
  });
  syncTabsWording();

  // 数据缝（memory.js 手机层调用）+ 语言切换。
  (/** @type {any} */ (window)).__mhMemorySync = () => {
    if (!document.body.classList.contains('mh-memory-editing')) renderCards();
  };
  const onLocale = () => {
    syncTabsWording();
    if (editBtn) editBtn.textContent = document.body.classList.contains('mh-memory-editing') ? t('memory.done') : t('memory.edit');
    renderCards();
  };
  window.addEventListener('locale-changed', onLocale);
  renderCards();
  setEditing(false);

  return function teardownMemoryFace() {
    window.removeEventListener('locale-changed', onLocale);
    (/** @type {any} */ (window)).__mhMemorySync = null;
    document.body.classList.remove('mh-memory-editing');
    const tabsNow = document.querySelectorAll('.memory-tab');
    tabsNow.forEach((tab, i) => { if (tabOriginal[i] != null) tab.textContent = tabOriginal[i]; });
    tabOriginal.length = 0;
    if (editBtn) { editBtn.remove(); editBtn = null; }
    if (cardsEl) { cardsEl.remove(); cardsEl = null; }
  };
}
