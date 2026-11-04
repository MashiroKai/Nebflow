// mobile/socialSearch.js — 消息/联系人页「常驻顶部搜索条」
// （作者令 2026-11-02 07:13：两个 tab 的搜索框固定在页面顶部，不再用右上角
//  悬浮按钮控制搜索；右上搜索浮钮在这两页退役，其它面保持现状）。
//
// 形态换代（相对 2026-10-31 悬浮钮统一+搜索批的「浮钮呼出 + 展开式搜索区」）：
//   · 搜索条 = 页面体流内**首件常驻**（.mh-search-dock > .mh-search-bar），
//     静止态即在位、不再有呼出/收合；呼出钮 .mh-fab-search 与页面 section 的
//     .mh-search-open 收合语汇整件退役（两页之外的搜索入口零触碰：聊天根
//     chrome 搜索键 / 详情页搜索页等均不动）。
//   · 输入即搜、清空即回列表：消息页有查询时 .has-query 让 #fm-conversations
//     整块让位，结果区吃满余量自滚（条在流内占位 ⇒ 列表首条永不被遮挡）。
//   · 材质 = 既有玻璃包单源（--glass-control-*，sapphire.css 深浅双主题），
//     零新色值；CSS 见 mobileLayer.css「常驻搜索条」块。
//
// 两面内容臂（数据源 = 桌面既有面/读数，零复制零第二实现）：
//   · 消息页 = 类微信跨会话聊天记录检索：范围 chips（全部/好友/设备/群聊）+
//     按会话分组结果 + <mark> 高亮 + 点行跳转定位（messages.js revealMessage
//     缝）。纯客户端渐进检索：getConversations 快照 + 每会话 getMessages 前向
//     翻页（20 页 × 50 = 1000 条上限，脚注声明）；300ms debounce + requestId
//     竞态丢弃 + AbortController 中止（chatSearch 同族纪律）。
//   · 联系人页 = 既有桌面搜索块逻辑零改动承载：常驻条只做输入的**单入口**，
//     查询串写回 contacts.js 的 searchQ 单一源并走同一条提交流水（节流/六态
//     结果卡/渲染全复用，contacts.js#submitContactsSearch）。联系人页无「好友/
//     群/设备」切面控件（现状 = 单条流：搜索块 → 新的朋友 → 设备 → 群入口 →
//     好友列表）⇒ 本批按「同一条搜索条覆盖该页全部内容段」落地；条住在收编
//     面之外（页面体首件），面板重渲不重建输入内容（输入串跨重渲保留）。
//
// 桌面零改动边界：本模块只追加自有节点 + 读桌面导出 API；封存态
// （features.friends OFF）不建条（ensureSocialPage 同口径）。

import { t } from '../i18n.js';
import { createIconsIn } from '../utils.js';
import { friendsEnabled } from '../featureFlags.js';
import * as api from '../friendsApi.js';
import {
  openConversation, revealMessage, personLabel, deviceLabel, fmtTime, conversationSnapshot,
} from '../messages.js';
import { groupTitleOf } from '../friendGroups.js';
import { logEvent } from './navEvents.js';
// 件一（2026-11-02）「描述进检索」：谓词唯一实现 = 零依赖叶子 `descSearch.js`
// （桌面联系人本地过滤与手机常驻条共用同一份，禁各写一套 includes）。
import { descMatches, convDescribeFields } from '../descSearch.js';

/** 检索纪律（沿用旧批 §2.3）：每会话前向翻页上限 / 页大小 / 单会话命中上限。 */
const PAGE_CAP = 20;
const PAGE_SIZE = 50;
const MATCH_CAP_PER_CONV = 30;
const DEBOUNCE_MS = 300;
/** 联系人页 = 远程用户名检索，去抖略长（桌面提交键另有 1s 最小间隔，同源）。 */
const CONTACTS_DEBOUNCE_MS = 450;

/** 范围档位（值 ↔ conversation.kind：friend = 缺省非 group/device 行）。 */
const SCOPES = [
  { value: 'all', labelKey: 'mhsearch.scopeAll' },
  { value: 'friend', labelKey: 'mhsearch.scopeFriend' },
  { value: 'device', labelKey: 'mhsearch.scopeDevice' },
  { value: 'group', labelKey: 'mhsearch.scopeGroup' },
];

/** 常驻搜索条所属页（其余面的搜索入口零触碰）。 */
const PAGE_IDS = ['messages', 'contacts'];

/**
 * @typedef {{
 *   pageId: string, dock: HTMLElement, bar: HTMLElement, input: HTMLInputElement,
 *   clear: HTMLButtonElement, scopeRow: HTMLElement|null, status: HTMLElement|null,
 *   results: HTMLElement|null, note: HTMLElement|null,
 * }} SearchBar
 */

/** @type {Map<string, SearchBar>} page id → 常驻条部件表。 */
const bars = new Map();
/** @type {HTMLElement|null} 消息页结果流（检索腿只服务这一页）。 */
let msgResults = null;
/** @type {HTMLElement|null} 消息页状态行。 */
let msgStatus = null;
/** @type {string} 当前范围档。 */
let scope = 'all';
/** @type {number} requestId 竞态丢弃判据。 */
let seq = 0;
/** @type {AbortController|null} */
let abortCtl = null;
/** @type {ReturnType<typeof setTimeout>|null} 消息页去抖。 */
let debTimer = null;
/** @type {ReturnType<typeof setTimeout>|null} 联系人页去抖。 */
let contactTimer = null;
/** @type {Array<() => void>} */
let unsubs = [];

function pageSection(pageId) {
  return document.querySelector(`#mobile-pages .mobile-page[data-page="${pageId}"]`);
}

function pageBody(pageId) {
  return /** @type {HTMLElement|null} */ (
    pageSection(pageId)?.querySelector('.mobile-page-body') || null);
}

function msgBar() { return bars.get('messages') || null; }

// ── 部件构建 ────────────────────────────────────────────────────────

/** 输入行（玻璃单源 + 图标 + 清空键）；返回三件，事件由各页处理器接线。 */
function buildBarRow(placeholder) {
  const row = document.createElement('div');
  row.className = 'mh-search-bar';
  const ic = document.createElement('i');
  ic.setAttribute('data-lucide', 'search');
  ic.setAttribute('aria-hidden', 'true');
  const input = document.createElement('input');
  input.className = 'mh-search-input';
  input.type = 'text';
  input.autocomplete = 'off';
  input.placeholder = placeholder;
  input.setAttribute('aria-label', placeholder);
  const clear = document.createElement('button');
  clear.type = 'button';
  clear.className = 'mh-search-clear';
  clear.title = t('mhsearch.clear');
  clear.setAttribute('aria-label', t('mhsearch.clear'));
  clear.innerHTML = '<i data-lucide="x"></i>';
  clear.addEventListener('click', () => {
    input.value = '';
    row.classList.remove('has-text');
    // 清空 = 走与键入同一条 input 处理腿（单一驱动，零第二套复位逻辑）。
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.focus();
  });
  input.addEventListener('input', () => row.classList.toggle('has-text', input.value.length > 0));
  row.append(ic, input, clear);
  return { row, input, clear };
}

/** 消息页：输入行 + 范围 chips + 状态行 + 结果流 + 脚注（全部常驻，静止态折叠视觉由 CSS）。 */
function fillMessagesBar(dock, parts) {
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

  const status = document.createElement('div');
  status.className = 'mh-search-status';
  status.setAttribute('role', 'status');
  status.setAttribute('aria-live', 'polite');
  status.hidden = true;

  const results = document.createElement('div');
  results.className = 'mh-search-results';

  const note = document.createElement('div');
  note.className = 'mh-search-note';
  note.textContent = t('mhsearch.capNote');

  dock.append(parts.row, scopeRow, status, results, note);
  msgStatus = status;
  msgResults = results;

  // 输入即搜：查询串非空 ⇒ 条吃满余量 + 列表让位（CSS 判据 = .has-query）。
  parts.input.addEventListener('input', () => {
    dock.classList.toggle('has-query', parts.input.value.trim().length > 0);
    onQuery();
  });
  parts.input.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      // Esc = 清空回列表（常驻条的「收起」语义已无，保留清场一键）。
      e.preventDefault();
      e.stopPropagation();
      parts.clear.click();
    } else if (e.key === 'Enter') {
      e.preventDefault();
      scheduleSearchNow();
    }
  });

  return { scopeRow, status, results, note };
}

/** 联系人页：只有输入行（结果卡由桌面搜索块在收编面内自渲染，逻辑零复制）。 */
function fillContactsBar(dock, parts) {
  dock.append(parts.row);
  parts.input.addEventListener('input', () => {
    const q = parts.input.value.trim();
    dock.classList.toggle('has-query', q.length > 0);
    if (contactTimer) { clearTimeout(contactTimer); contactTimer = null; }
    // 清空 = **立即**回列表（去抖只服务远程查号：空串零请求、零反爬代价）。
    // 件一（2026-11-02）起 `searchQ` 同时是好友列表**本地过滤**的输入源（描述进
    // 检索）⇒ 清空若还等 450ms，列表会停在被过滤的旧态一瞬（微信式「清空即回列表」
    // 的语义硬要求；消息页 P4 同款）。
    if (!q) { runContactsSearch(''); return; }
    contactTimer = setTimeout(() => { contactTimer = null; runContactsSearch(q); }, CONTACTS_DEBOUNCE_MS);
  });
  parts.input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      if (contactTimer) { clearTimeout(contactTimer); contactTimer = null; }
      runContactsSearch(parts.input.value.trim());
    } else if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      parts.clear.click();
    }
  });
}

/** contacts.js 模块句柄缓存（**同步复用**的关键，件一 2026-11-02）。
 *  动态 import 是为了不造 `socialSearch → contacts → messages → socialSearch`
 *  静态环；但 `await import()` 每次都在微任务后交付 ⇒ 调用方若在同一事件回合里
 *  先探测 DOM（`mobile-pinned-search.spec.mjs` P6「清空后首行回位」就是同步读），
 *  会读到**上一拍**的列表。首次 import 后把句柄记住，之后同步调用 —— 首拍仍异步
 *  （那时 DOM 里还没有任何查询态，无人探测）。 */
let contactsMod = null;

/** 联系人页检索腿：查询串写回 contacts.js 单一源（searchQ）并走同一提交流水
 *  （节流关 = 输入已在上游去抖；空串 = 清场回列表）。
 *  🔴 空串必须**同回合**重渲（微信式「清空即回列表」；列表本地过滤的输入源就是
 *  同一个 `searchQ`，晚一拍的代价是用户看见「暂无联系人和搜索卡并存」的中间态）。 */
function runContactsSearch(q) {
  if (contactsMod) { contactsMod.submitSearch(q, { throttle: false }); return; }
  import('../contacts.js')
    .then((m) => { contactsMod = m; m.submitSearch(q, { throttle: false }); })
    .catch(() => { /* 模块未载（好友域未初始化）——静默，桌面逻辑缺席即无票可投 */ });
}

/** 建一条常驻条（幂等；页面体首件）。 */
function ensureBar(pageId) {
  const existing = bars.get(pageId);
  if (existing && existing.dock.isConnected) return existing;
  const body = pageBody(pageId);
  if (!body) return null;
  const dock = document.createElement('div');
  dock.className = 'mh-search-dock';
  dock.setAttribute('data-mh', pageId === 'messages' ? 'msgsearch' : 'contactsearch');
  dock.setAttribute('role', 'search');
  const parts = buildBarRow(pageId === 'messages'
    ? t('mhsearch.placeholder')
    : t('contacts.searchPlaceholder'));
  /** @type {SearchBar} */
  const bar = {
    pageId, dock, bar: parts.row, input: parts.input, clear: parts.clear,
    scopeRow: null, status: null, results: null, note: null,
  };
  if (pageId === 'messages') Object.assign(bar, fillMessagesBar(dock, parts));
  else fillContactsBar(dock, parts);
  body.insertBefore(dock, body.firstChild);
  if (typeof lucide !== 'undefined') createIconsIn(dock);
  bars.set(pageId, bar);
  return bar;
}

/** 摘条（封存态/拆除；消息页同时复位检索腿）。 */
function destroyBar(pageId) {
  const bar = bars.get(pageId);
  if (!bar) return;
  bars.delete(pageId);
  bar.dock.remove();
  if (pageId === 'messages') {
    resetMsgSearch();
    msgStatus = null;
    msgResults = null;
  } else if (contactTimer) {
    clearTimeout(contactTimer);
    contactTimer = null;
  }
}

// ── 消息页检索腿 ────────────────────────────────────────────────────

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

/** 清空即回列表：结果/状态清场（范围档保留——下次查询续用）。 */
function resetMsgSearch() {
  seq++;
  abortCtl?.abort();
  abortCtl = null;
  if (msgResults) msgResults.replaceChildren();
  setStatus('');
  const bar = msgBar();
  if (bar) {
    bar.input.value = '';
    bar.bar.classList.remove('has-text');
    bar.dock.classList.remove('has-query');
  }
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

/** 结果行：meta（发送者 · 时间）+ 命中片段；点击跳转定位。 */
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
  const q = (msgBar()?.input.value || '').trim();
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

  // ── 描述命中段（件一 2026-11-02）─────────────────────────────────────
  // 定案（勘察报告 §7.2）：会话的**档案描述**（好友 remark/bio、群 remark/description）
  // 不进消息正文检索，而是作为**会话级命中**单独成组（组名 = 会话标签 · 「资料命中」），
  // 点击 = 直接开会话。这样「按描述找联系人/群」与「按正文找消息」两个语义面不互相
  // 污染（不然一条描述命中的会话会显示成「0 条消息」的空组）。
  const descHits = convs.filter((c) => descMatches(q, convDescribeFields(c)));
  if (descHits.length && msgResults) {
    const g = document.createElement('div');
    g.className = 'mh-sr-group mh-sr-group-desc';
    g.textContent = `${t('mhsearch.descHits', { n: descHits.length })}`;
    msgResults.appendChild(g);
    for (const conv of descHits) {
      const row = document.createElement('button');
      row.type = 'button';
      row.className = 'mh-sr-row mh-sr-row-desc';
      row.dataset.mhDesc = '1';
      const meta = document.createElement('div');
      meta.className = 'mh-sr-meta';
      const name = document.createElement('span');
      name.className = 'mh-sr-sender';
      name.textContent = convLabel(conv);
      meta.appendChild(name);
      const kind = document.createElement('span');
      kind.className = 'mh-sr-time';
      kind.textContent = conv.kind === 'group'
        ? (conv.remark ? t('profile.groupRemarkLabel') : t('profile.groupDescriptionLabel'))
        : (conv.friend && conv.friend.remark ? t('profile.friendRemarkLabel') : t('profile.friendBioLabel'));
      meta.appendChild(kind);
      row.appendChild(meta);
      row.addEventListener('click', () => {
        logEvent('mh-desc-hit-jump', { conv: String(conv.conversationId) });
        openConversation(conv.conversationId, null).catch(() => {
          window.__showToast?.(t('mhsearch.jumpFail'), 'error');
        });
      });
      msgResults.appendChild(row);
    }
  }

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
  // 门控随 config 到达重对账（nav 事件汇合点）：封存态不建条；缺席默认开放
  // （friendsEnabled 口径）⇒ 激活即建，显式封存 config 到达后下一拍 nav 摘除。
  const syncGate = () => {
    const on = friendsEnabled();
    for (const pageId of PAGE_IDS) {
      if (on) ensureBar(pageId);
      else destroyBar(pageId);
    }
  };
  syncGate();
  document.addEventListener('nf:mobile-nav', syncGate);

  // Esc：输入框自有处理（清空回列表）；这里兜底「焦点不在条内」的同键语义 ——
  // 焦点在条外时不吞事件（页面的返回/关闭语义各自保留），故不做全局拦。
  const onLocale = () => {
    for (const bar of bars.values()) {
      bar.input.placeholder = bar.pageId === 'messages'
        ? t('mhsearch.placeholder')
        : t('contacts.searchPlaceholder');
      bar.input.setAttribute('aria-label', bar.input.placeholder);
      bar.clear.title = t('mhsearch.clear');
      bar.clear.setAttribute('aria-label', t('mhsearch.clear'));
      bar.scopeRow?.querySelectorAll('.mh-scope-chip').forEach((c) => {
        const def = SCOPES.find((s) => s.value === (/** @type {HTMLElement} */ (c)).dataset.scope);
        if (def) c.textContent = t(def.labelKey);
      });
      if (bar.note) bar.note.textContent = t('mhsearch.capNote');
    }
  };
  window.addEventListener('locale-changed', onLocale);
  unsubs.push(() => window.removeEventListener('locale-changed', onLocale));

  return function teardownSocialSearch() {
    for (const fn of unsubs.splice(0)) { try { fn(); } catch { /* ignore */ } }
    document.removeEventListener('nf:mobile-nav', syncGate);
    if (debTimer) { clearTimeout(debTimer); debTimer = null; }
    if (contactTimer) { clearTimeout(contactTimer); contactTimer = null; }
    seq++;
    abortCtl?.abort();
    abortCtl = null;
    for (const pageId of [...bars.keys()]) destroyBar(pageId);
    msgResults = null;
    msgStatus = null;
    scope = 'all';
  };
}
