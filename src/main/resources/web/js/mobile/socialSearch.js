// mobile/socialSearch.js — 消息/联系人页右上浮搜索钮 + 展开式搜索栏
// （悬浮钮统一+搜索批 2026-10-31 作者令 16:03 ③④；规格书
//  docs/20261031_160300_mobile-fab-unify-search.md §2.2/§2.3）。
//
// 两件：
//   · 共用交互件：48px g2-fab 搜索钮（top:16+safe right:16，与 chrome 搜索键
//     同几何）→ 页面 section 挂 .mh-search-open → 搜索区从钮处展开（max-height
//     spring + 栏 scaleX origin right），下方内容整体下拉让位；再点/Esc 收合。
//   · 消息页 = 类微信跨会话聊天记录检索：范围 chips（全部/好友/设备/群聊）+
//     按会话分组结果 + <mark> 高亮 + 点行跳转定位（messages.js revealMessage
//     缝）。纯客户端渐进检索：getConversations 快照 + 每会话 getMessages 前向
//     翻页（20 页 × 50 = 1000 条上限，脚注声明）；300ms debounce + requestId
//     竞态丢弃 + AbortController 中止（chatSearch 同族纪律）。
//   · 联系人页 = 既有 .fm-search-block 桌面逻辑零改动承载（CSS 收合/展开，
//     结果卡 in-flow 推挤好友列表）。
//
// 桌面零改动边界：本模块只追加自有节点 + 读桌面导出 API；封存态
// （features.friends OFF）不建钮（ensureSocialPage 同口径）。

import { t } from '../i18n.js';
import { createIconsIn } from '../utils.js';
import { friendsEnabled } from '../featureFlags.js';
import * as api from '../friendsApi.js';
import {
  openConversation, revealMessage, personLabel, deviceLabel, fmtTime, conversationSnapshot,
} from '../messages.js';
import { groupTitleOf } from '../friendGroups.js';
import { logEvent } from './navController.js';

/** 检索纪律（规格书 §2.3）：每会话前向翻页上限 / 页大小 / 单会话命中上限。 */
const PAGE_CAP = 20;
const PAGE_SIZE = 50;
const MATCH_CAP_PER_CONV = 30;
const DEBOUNCE_MS = 300;

/** 范围档位（值 ↔ conversation.kind：friend = 缺省非 group/device 行）。 */
const SCOPES = [
  { value: 'all', labelKey: 'mhsearch.scopeAll' },
  { value: 'friend', labelKey: 'mhsearch.scopeFriend' },
  { value: 'device', labelKey: 'mhsearch.scopeDevice' },
  { value: 'group', labelKey: 'mhsearch.scopeGroup' },
];

/** @type {Map<string, HTMLButtonElement>} page id → 搜索钮。 */
const fabs = new Map();
/** @type {HTMLElement|null} 消息页展开区（.mh-search-dock）。 */
let msgDock = null;
/** @type {HTMLInputElement|null} */
let msgInput = null;
/** @type {HTMLElement|null} 状态行。 */
let msgStatus = null;
/** @type {HTMLElement|null} 结果流。 */
let msgResults = null;
/** @type {string} 当前范围档。 */
let scope = 'all';
/** @type {number} requestId 竞态丢弃判据。 */
let seq = 0;
/** @type {AbortController|null} */
let abortCtl = null;
/** @type {ReturnType<typeof setTimeout>|null} */
let debTimer = null;
/** @type {Array<() => void>} */
let unsubs = [];

function pageSection(pageId) {
  return document.querySelector(`#mobile-pages .mobile-page[data-page="${pageId}"]`);
}

/** 搜索钮（48px g2-fab；几何 = mobileLayer.css .mh-fab-search 块）。 */
function buildFab(pageId) {
  const section = pageSection(pageId);
  if (!section || fabs.has(pageId)) return;
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'mh-fab-search g2-fab';
  btn.dataset.page = pageId;
  btn.title = t('mhsearch.aria');
  btn.setAttribute('aria-label', t('mhsearch.aria'));
  btn.setAttribute('aria-expanded', 'false');
  btn.innerHTML = '<i data-lucide="search"></i>';
  btn.addEventListener('click', (e) => {
    // 桌面面 outside-close 监听 document 冒泡 —— 入口键停冒泡防误关（hub 行同款）。
    e.stopPropagation();
    toggleSearch(pageId);
  });
  section.appendChild(btn);
  if (typeof lucide !== 'undefined') createIconsIn(btn);
  fabs.set(pageId, btn);
}

/** 钮图标 search↔x 两态 + aria-expanded。 */
function paintFab(pageId, open) {
  const btn = fabs.get(pageId);
  if (!btn) return;
  btn.setAttribute('aria-expanded', String(open));
  const icon = open ? 'x' : 'search';
  btn.innerHTML = `<i data-lucide="${icon}"></i>`;
  if (typeof lucide !== 'undefined') createIconsIn(btn);
}

function toggleSearch(pageId, force) {
  const section = pageSection(pageId);
  const btn = fabs.get(pageId);
  if (!section || !btn) return;
  const open = force === undefined ? !section.classList.contains('mh-search-open') : !!force;
  const wasOpen = section.classList.contains('mh-search-open');
  if (open && pageId === 'messages' && !msgDock) ensureMsgDock();
  section.classList.toggle('mh-search-open', open);
  paintFab(pageId, open);
  logEvent('mh-search-toggle', { page: pageId, open });
  if (open) {
    // 焦点进输入框：消息页 = 自有输入；联系人页 = 桌面块输入（重渲后重查）。
    const input = pageId === 'messages'
      ? msgInput
      : (/** @type {HTMLInputElement|null} */ (section.querySelector('.fm-search-block .fm-search-input')));
    setTimeout(() => input?.focus?.(), 120); // 等展开动效首段落位再聚焦
  } else if (wasOpen) {
    if (pageId === 'messages') resetMsgSearch();
    btn.focus?.();
  }
}

// ── 消息页检索面 ────────────────────────────────────────────────────

/** 展开区一次构建（幂等）：输入栏 + 范围 chips + 状态行 + 结果流 + 脚注。 */
function ensureMsgDock() {
  if (msgDock) return;
  const body = pageSection('messages')?.querySelector('.mobile-page-body');
  if (!body) return;
  const dock = document.createElement('div');
  dock.className = 'mh-search-dock';
  dock.setAttribute('data-mh', 'msgsearch');

  const bar = document.createElement('div');
  bar.className = 'mh-search-bar';
  const ic = document.createElement('i');
  ic.setAttribute('data-lucide', 'search');
  ic.setAttribute('aria-hidden', 'true');
  msgInput = document.createElement('input');
  msgInput.className = 'mh-search-input';
  msgInput.type = 'text';
  msgInput.autocomplete = 'off';
  msgInput.placeholder = t('mhsearch.placeholder');
  msgInput.setAttribute('aria-label', t('mhsearch.placeholder'));
  const clear = document.createElement('button');
  clear.type = 'button';
  clear.className = 'mh-search-clear';
  clear.title = t('mhsearch.clear');
  clear.setAttribute('aria-label', t('mhsearch.clear'));
  clear.innerHTML = '<i data-lucide="x"></i>';
  clear.addEventListener('click', () => {
    if (!msgInput) return;
    msgInput.value = '';
    bar.classList.remove('has-text');
    onQuery();
    msgInput.focus();
  });
  msgInput.addEventListener('input', () => {
    bar.classList.toggle('has-text', !!msgInput && msgInput.value.length > 0);
    onQuery();
  });
  msgInput.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); toggleSearch('messages', false); }
    else if (e.key === 'Enter') { e.preventDefault(); scheduleSearchNow(); }
  });
  bar.append(ic, msgInput, clear);

  const scopeRow = document.createElement('div');
  scopeRow.className = 'mh-scope-row';
  scopeRow.setAttribute('role', 'group');
  scopeRow.setAttribute('aria-label', t('mhsearch.aria'));
  for (const s of SCOPES) {
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'mh-scope-chip' + (s.value === scope ? ' on' : '');
    chip.dataset.scope = s.value;
    chip.textContent = t(s.labelKey);
    chip.setAttribute('aria-pressed', String(s.value === scope));
    chip.addEventListener('click', () => {
      if (scope === s.value) return;
      scope = s.value;
      scopeRow.querySelectorAll('.mh-scope-chip').forEach((c) => {
        const on = (/** @type {HTMLElement} */ (c)).dataset.scope === scope;
        c.classList.toggle('on', on);
        c.setAttribute('aria-pressed', String(on));
      });
      logEvent('mh-scope', { scope });
      scheduleSearchNow();
    });
    scopeRow.appendChild(chip);
  }

  msgStatus = document.createElement('div');
  msgStatus.className = 'mh-search-status';
  msgStatus.setAttribute('role', 'status');
  msgStatus.setAttribute('aria-live', 'polite');
  msgStatus.hidden = true;

  msgResults = document.createElement('div');
  msgResults.className = 'mh-search-results';

  const note = document.createElement('div');
  note.className = 'mh-search-note';
  note.textContent = t('mhsearch.capNote');

  dock.append(bar, scopeRow, msgStatus, msgResults, note);
  body.insertBefore(dock, body.firstChild);
  if (typeof lucide !== 'undefined') createIconsIn(dock);
  msgDock = dock;
}

function setStatus(text) {
  if (!msgStatus) return;
  msgStatus.hidden = !text;
  msgStatus.textContent = text || '';
}

/** 输入去抖（chatSearch scheduleSearch 同族纪律）。 */
function onQuery() {
  if (debTimer) clearTimeout(debTimer);
  debTimer = setTimeout(() => { debTimer = null; runSearch(); }, DEBOUNCE_MS);
}

function scheduleSearchNow() {
  if (debTimer) { clearTimeout(debTimer); debTimer = null; }
  runSearch();
}

/** 收合即复位（结果/状态清场；范围档保留——重开续用）。 */
function resetMsgSearch() {
  seq++;
  abortCtl?.abort();
  abortCtl = null;
  if (msgResults) msgResults.replaceChildren();
  setStatus('');
  if (msgInput) msgInput.value = '';
  msgDock?.querySelector('.mh-search-bar')?.classList.remove('has-text');
}

/** 会话标签（与 convRow 渲染同源：好友 personLabel / 设备 deviceLabel / 群群名）。 */
function convLabel(conv) {
  if (conv.kind === 'device') return deviceLabel(conv.device);
  if (conv.kind === 'group') return groupTitleOf(conv);
  return personLabel(conv.friend);
}

/** 发送者标签：direct/设备 = 对方 id 命中会话标签，否则「我」；群 = 省略（组头已载）。 */
function senderLabel(conv, m) {
  if (conv.kind === 'group') return '';
  const peerId = conv.friend?.userId ?? conv.device?.ownerUserId ?? null;
  if (peerId != null && String(m.senderId) === String(peerId)) return convLabel(conv);
  return t('mhsearch.me');
}

/** 命中片段：首命中 ±28 字，mark 高亮片段内全部命中（DOM 构建，textContent 防 XSS）。 */
function buildSnippet(body, q) {
  const lower = body.toLowerCase();
  const ql = q.toLowerCase();
  const idx = lower.indexOf(ql);
  const start = Math.max(0, idx - 28);
  const end = Math.min(body.length, idx + ql.length + 28);
  const frag = document.createDocumentFragment();
  if (start > 0) frag.appendChild(document.createTextNode('…'));
  let pos = start;
  while (pos < end) {
    const hit = lower.indexOf(ql, pos);
    if (hit === -1 || hit >= end) {
      frag.appendChild(document.createTextNode(body.slice(pos, end)));
      break;
    }
    if (hit > pos) frag.appendChild(document.createTextNode(body.slice(pos, hit)));
    const mark = document.createElement('mark');
    mark.textContent = body.slice(hit, hit + ql.length);
    frag.appendChild(mark);
    pos = hit + ql.length;
  }
  if (end < body.length) frag.appendChild(document.createTextNode('…'));
  return frag;
}

/** 单会话翻页检索（前向 keyset；上限 PAGE_CAP 页）。@returns 命中数组（升序原序） */
async function scanConversation(conv, q, mySeq, signal) {
  const ql = q.toLowerCase();
  const matches = [];
  let after = 0;
  for (let page = 0; page < PAGE_CAP; page++) {
    if (mySeq !== seq || signal.aborted) return null;
    let msgs = [];
    try { msgs = await api.getMessages(conv.conversationId, { after, limit: PAGE_SIZE }); }
    catch { return matches.length ? matches : null; } // 单会话取数失败不拖垮全局
    if (!Array.isArray(msgs) || !msgs.length) break;
    for (const m of msgs) {
      const body = typeof m.body === 'string' ? m.body : '';
      if (body && body.toLowerCase().includes(ql)) {
        matches.push(m);
        if (matches.length >= MATCH_CAP_PER_CONV) return matches;
      }
    }
    if (msgs.length < PAGE_SIZE) break;
    const lastId = Number(msgs[msgs.length - 1].id);
    if (!Number.isFinite(lastId) || lastId <= after) break;
    after = lastId;
  }
  return matches;
}

/** 范围过滤（friend = 缺省非 group/device 行，convRow 判据同源）。 */
function scopeFilter(conv) {
  if (scope === 'all') return true;
  if (scope === 'device') return conv.kind === 'device';
  if (scope === 'group') return conv.kind === 'group';
  return conv.kind !== 'device' && conv.kind !== 'group';
}

/** 结果行：meta（发送者 · 时间）+ 命中片段；点击跳转定位（规格书 §2.3）。 */
function buildResultRow(conv, m, q) {
  const row = document.createElement('button');
  row.type = 'button';
  row.className = 'mh-sr-row';
  const meta = document.createElement('div');
  meta.className = 'mh-sr-meta';
  const sender = senderLabel(conv, m);
  if (sender) {
    const senderEl = document.createElement('span');
    senderEl.className = 'mh-sr-sender';
    senderEl.textContent = sender;
    meta.appendChild(senderEl);
  }
  const time = document.createElement('span');
  time.className = 'mh-sr-time';
  time.textContent = fmtTime(m.createdAt);
  meta.appendChild(time);
  const snippet = document.createElement('div');
  snippet.className = 'mh-sr-snippet';
  snippet.appendChild(buildSnippet(typeof m.body === 'string' ? m.body : '', q));
  row.append(meta, snippet);
  row.addEventListener('click', async () => {
    logEvent('mh-result-jump', { conv: String(conv.conversationId), msg: String(m.id) });
    try {
      await openConversation(conv.conversationId, null);
      const ok = await revealMessage(m.id);
      if (!ok) window.__showToast?.(t('mhsearch.jumpFail'), 'error');
    } catch { window.__showToast?.(t('mhsearch.jumpFail'), 'error'); }
  });
  return row;
}

/**
 * 渐进主检索：快照会话表 → 并发 2 逐会话翻页扫描 → 命中即增量上屏（按会话分组）。
 * 竞态纪律：seq 换代丢弃 + AbortController 中止在飞取数。
 */
async function runSearch() {
  const q = (msgInput?.value || '').trim();
  const mySeq = ++seq;
  abortCtl?.abort();
  abortCtl = new AbortController();
  const signal = abortCtl.signal;
  if (msgResults) msgResults.replaceChildren();
  if (!q) { setStatus(''); return; }
  setStatus(t('mhsearch.searching'));
  let convs = conversationSnapshot();
  if (!convs.length) {
    // 名册未初始化（极早首次进入）——回落直连快照；此后随列表刷新自然有值。
    try { convs = (await api.getConversations()) || []; } catch { convs = []; }
  }
  if (mySeq !== seq) return;
  convs = convs.filter(scopeFilter);

  let total = 0;
  let cursor = 0;
  const workers = [0, 1].map(async () => {
    while (cursor < convs.length) {
      if (mySeq !== seq || signal.aborted) return;
      const conv = convs[cursor++];
      const matches = await scanConversation(conv, q, mySeq, signal);
      if (matches === null || mySeq !== seq || signal.aborted) return;
      if (matches.length && msgResults) {
        total += matches.length;
        const group = document.createElement('div');
        group.className = 'mh-sr-group';
        group.textContent = `${convLabel(conv)} · ${t('mhsearch.resultCount', { n: matches.length })}`;
        msgResults.appendChild(group);
        for (const m of matches) msgResults.appendChild(buildResultRow(conv, m, q));
        setStatus(t('mhsearch.resultCount', { n: total }));
      }
    }
  });
  await Promise.all(workers);
  if (mySeq !== seq || signal.aborted) return;
  setStatus(total
    ? t('mhsearch.resultCount', { n: total })
    : `${t('mhsearch.noResults')} · ${t('mhsearch.noResultsHint')}`);
}

// ── 生命周期 ─────────────────────────────────────────────────────────

export function activateSocialSearch() {
  // 门控随 config 到达重对账（nav 事件汇合点）：封存态不建钮；缺席默认开放
  // （friendsEnabled 口径）⇒ 激活即建，显式封存 config 到达后下一拍 nav 摘除
  // （激活早于首帧 configData 的时序与 syncHubGate 同病同药）。
  const syncGate = () => {
    const on = friendsEnabled();
    for (const pageId of ['messages', 'contacts']) {
      if (on) buildFab(pageId);
      else { fabs.get(pageId)?.remove(); fabs.delete(pageId); }
    }
  };
  syncGate();
  document.addEventListener('nf:mobile-nav', syncGate);

  // Esc 收合任一展开页（返回钮契约同族）；焦点归还对应钮在 toggleSearch 内。
  const onKey = (e) => {
    if (e.key !== 'Escape') return;
    for (const [pageId] of fabs) {
      if (pageSection(pageId)?.classList.contains('mh-search-open')) {
        toggleSearch(pageId, false);
      }
    }
  };
  document.addEventListener('keydown', onKey);

  const onLocale = () => {
    for (const [pageId, btn] of fabs) {
      btn.title = t('mhsearch.aria');
      btn.setAttribute('aria-label', t('mhsearch.aria'));
      paintFab(pageId, pageSection(pageId)?.classList.contains('mh-search-open'));
    }
    if (msgInput) msgInput.placeholder = t('mhsearch.placeholder');
    if (msgDock) {
      msgDock.querySelectorAll('.mh-scope-chip').forEach((c) => {
        const def = SCOPES.find((s) => s.value === (/** @type {HTMLElement} */ (c)).dataset.scope);
        if (def) c.textContent = t(def.labelKey);
      });
      const note = msgDock.querySelector('.mh-search-note');
      if (note) note.textContent = t('mhsearch.capNote');
    }
  };
  window.addEventListener('locale-changed', onLocale);
  unsubs.push(() => window.removeEventListener('locale-changed', onLocale));

  return function teardownSocialSearch() {
    for (const fn of unsubs.splice(0)) { try { fn(); } catch { /* ignore */ } }
    document.removeEventListener('keydown', onKey);
    document.removeEventListener('nf:mobile-nav', syncGate);
    if (debTimer) { clearTimeout(debTimer); debTimer = null; }
    seq++;
    abortCtl?.abort();
    abortCtl = null;
    for (const [, btn] of fabs) btn.remove();
    fabs.clear();
    msgDock?.remove();
    msgDock = null;
    msgInput = null;
    msgStatus = null;
    msgResults = null;
    scope = 'all';
  };
}
