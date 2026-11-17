// socialSearchCore.js — 社交消息检索「引擎 + 字段部件」共用核心
// （desktop-search-field 批 2026-11-17，作者令：「电脑端的消息顶部应该要有搜索的框
//  同时优化一下样式 联系人搜索那边也是 风格和手机端的搜索框保持一致」）。
//
// 本文件 = 手机常驻搜索条（js/mobile/socialSearch.js）与桌面面板搜索框
// （js/panelSearch.js）的**单一实现**：跨会话消息检索引擎（快照 + 分页扫描 +
// 分段揭示 + 竞态丢弃）、范围 chips、结果行/描述命中行/命中片段构建器。
// 两端各自只保留 UI 壳（DOM 挂载点 / 版面让位 / 收起形变），**禁各写一套引擎**
// —— 这是对 mobile/socialSearch.js 既有引擎的**原样上抽**（逻辑逐字保留，
// 模块级单例状态 seq/abort/debounce/scope 收进实例），不是第二实现。
//
// 检索纪律（与 mobile 版逐字同源，检索全覆盖批 2026-11-07 口径）：
//   · PAGE_SIZE = 单次取数页（服务端 keyset clamp ≤200 内）；
//   · CHUNK_PAGES = 每次揭示最多扫多少页（20×50 = 1000 条/段），扫到即停、
//     段尾「加载更多（本会话）」从游标续扫，可一路查到最早一条；
//   · 命中采集不设每会话上限；300ms 去抖 + requestId 竞态丢弃 + AbortController。
//
// 数据通路（零新后端）：conversationSnapshot()（messages.js 列表同源快照，
// 极早期回落 api.getConversations）→ 逐会话 api.getMessages 前向翻页、客户端
// 匹配 —— 与手机消息页搜索**同一条腿**（/api/search/chat 是 agent 会话历史
// 搜索，与此无关）。

import { t } from './i18n.js';
import * as api from './friendsApi.js';
import {
  openConversation, revealMessage, personLabel, deviceLabel, fmtTime, conversationSnapshot,
} from './messages.js';
import { groupTitleOf } from './friendGroups.js';
import { logEvent } from './mobile/navEvents.js';
import { descMatches, convDescribeFields } from './descSearch.js';

const PAGE_SIZE = 50;
const CHUNK_PAGES = 20;
const DEBOUNCE_MS = 300;

/** 范围档位（值 ↔ conversation.kind：friend = 缺省非 group/device 行）。 */
export const SCOPES = [
  { value: 'all', labelKey: 'mhsearch.scopeAll' },
  { value: 'friend', labelKey: 'mhsearch.scopeFriend' },
  { value: 'device', labelKey: 'mhsearch.scopeDevice' },
  { value: 'group', labelKey: 'mhsearch.scopeGroup' },
];

// ── 会话/发送者标签（与 convRow 渲染同源）──────────────────────────

export function convLabel(conv) {
  if (conv.kind === 'device') return deviceLabel(conv.device);
  if (conv.kind === 'group') return groupTitleOf(conv);
  return personLabel(conv.friend);
}

/** 发送者标签：direct/设备 = 对方 id 命中会话标签，否则「我」；群 = 省略。 */
function senderLabel(conv, m) {
  if (conv.kind === 'group') return '';
  const peerId = conv.friend?.userId ?? conv.device?.ownerUserId ?? null;
  if (peerId != null && String(m.senderId) === String(peerId)) return convLabel(conv);
  return t('mhsearch.me');
}

// ── 命中片段（mark 高亮；DOM 构建，textContent 防 XSS）────────────

export function buildSnippet(body, q) {
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

/** 结果行（.mh-sr-row 两端同 markup —— 样式应用点各归各层）：meta + 命中片段，
 *  点击跳转定位（openConversation + revealMessage 缝，失败 toast）。 */
export function buildResultRow(conv, m, q, log = logEvent) {
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
    log('mh-result-jump', { conv: String(conv.conversationId), msg: String(m.id) });
    try {
      await openConversation(conv.conversationId, null);
      const ok = await revealMessage(m.id);
      if (!ok) window.__showToast?.(t('mhsearch.jumpFail'), 'error');
    } catch { window.__showToast?.(t('mhsearch.jumpFail'), 'error'); }
  });
  return row;
}

/** 描述命中行（件一 2026-11-02「描述进检索」）：会话档案命中 = 单独成组，
 *  点击直接开会话。 */
export function buildDescHitRow(conv, log = logEvent) {
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
    log('mh-desc-hit-jump', { conv: String(conv.conversationId) });
    openConversation(conv.conversationId, null).catch(() => {
      window.__showToast?.(t('mhsearch.jumpFail'), 'error');
    });
  });
  return row;
}

// ── 引擎实例 ────────────────────────────────────────────────────────

/**
 * 建一个跨会话消息检索引擎（手机消息页 / 桌面消息面板各一实例）。
 * @param {{
 *   input: HTMLInputElement,
 *   resultsHost: HTMLElement,
 *   statusEl: HTMLElement,
 *   onHasQuery?: (has:boolean)=>void,
 *   log?: (kind:string, extra?:object)=>void,
 * }} host
 *   · input = 查询串单一源（清空 = 清场回列表）；
 *   · resultsHost/statusEl = 结果流与状态行宿主（组头/行/「加载更多」直挂）；
 *   · onHasQuery = 查询态翻转回调（宿主切版面：列表让位/回位）。
 * @returns {{
 *   onValueInput: ()=>void, submitNow: ()=>void, hardReset: ()=>void,
 *   scopeRow: ()=>HTMLElement, getScope: ()=>string, setScope: (v:string)=>void,
 *   destroy: ()=>void,
 * }}
 */
export function createSocialMessageSearch(host) {
  const { input, resultsHost, statusEl } = host;
  const log = host.log || logEvent;
  const onHasQuery = host.onHasQuery || (() => {});

  let scope = 'all';
  let seq = 0;
  let abortCtl = null;
  let debTimer = null;
  let scopeRowEl = null;

  function setStatus(text) {
    statusEl.hidden = !text;
    statusEl.textContent = text || '';
  }

  /** 输入腿：300ms 去抖；空串 = 立即清场回列表（微信式「清空即回列表」）。 */
  function onValueInput() {
    const has = input.value.trim().length > 0;
    onHasQuery(has);
    if (debTimer) { clearTimeout(debTimer); debTimer = null; }
    if (!has) { hardReset(); return; }
    debTimer = setTimeout(() => { debTimer = null; runSearch(); }, DEBOUNCE_MS);
  }

  function submitNow() {
    if (debTimer) { clearTimeout(debTimer); debTimer = null; }
    runSearch();
  }

  /** 清场（abort + 结果/状态清空）；范围档保留（下次查询续用）。 */
  function hardReset() {
    seq++;
    abortCtl?.abort();
    abortCtl = null;
    resultsHost.replaceChildren();
    setStatus('');
  }

  function scopeFilter(conv) {
    if (scope === 'all') return true;
    if (scope === 'device') return conv.kind === 'device';
    if (scope === 'group') return conv.kind === 'group';
    return conv.kind !== 'device' && conv.kind !== 'group';
  }

  /** 范围 chips 行（幂等：同实例同节点；aria-pressed 单点驱动）。 */
  function scopeRow() {
    if (scopeRowEl && scopeRowEl.isConnected) return scopeRowEl;
    const row = document.createElement('div');
    row.className = 'mh-scope-row';
    row.setAttribute('role', 'group');
    row.setAttribute('aria-label', t('mhsearch.aria'));
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
        row.querySelectorAll('.mh-scope-chip').forEach((c) => {
          const on = (/** @type {HTMLElement} */ (c)).dataset.scope === scope;
          c.classList.toggle('on', on);
          c.setAttribute('aria-pressed', String(on));
        });
        log('mh-scope', { scope });
        submitNow();
      });
      row.appendChild(chip);
    }
    scopeRowEl = row;
    return row;
  }

  /** 单会话检索段：从 cur.after 起扫最多 CHUNK_PAGES 页，命中全收；
   *  @returns {Promise<boolean>} false = 竞态丢弃（弃画，UI 归新查询）。 */
  async function scanConversationChunk(cur, mySeq, signal) {
    const ql = cur.q.toLowerCase();
    cur.scanning = true;
    try {
      for (let page = 0; page < CHUNK_PAGES; page++) {
        if (mySeq !== seq || signal.aborted) return false;
        let msgs = [];
        try { msgs = await api.getMessages(cur.conv.conversationId, { after: cur.after, limit: PAGE_SIZE }); }
        catch { cur.done = true; return true; } // 单会话取数失败 = 本会话收口，不拖垮全局
        if (!Array.isArray(msgs) || !msgs.length) { cur.done = true; break; }
        for (const m of msgs) {
          const body = typeof m.body === 'string' ? m.body : '';
          if (body && body.toLowerCase().includes(ql)) cur.matches.push(m);
        }
        if (msgs.length < PAGE_SIZE) { cur.done = true; break; }
        const lastId = Number(msgs[msgs.length - 1].id);
        if (!Number.isFinite(lastId) || lastId <= cur.after) { cur.done = true; break; } // id 不前进 ⇒ 防死循环
        cur.after = lastId;
      }
      return true;
    } finally {
      cur.scanning = false;
    }
  }

  function convGroupLabel(conv, n) {
    return `${convLabel(conv)} · ${t('mhsearch.resultCount', { n })}`;
  }

  /** 揭示本会话新增命中行（分段揭示：已画过的 painted 部分不重画）。 */
  function paintConvMatches(cur, q) {
    let added = 0;
    for (let i = cur.painted; i < cur.matches.length; i++) {
      resultsHost.appendChild(buildResultRow(cur.conv, cur.matches[i], q, log));
      added++;
    }
    cur.painted = cur.matches.length;
    if (cur.headerEl) cur.headerEl.textContent = convGroupLabel(cur.conv, cur.matches.length);
    return added;
  }

  /** 段尾「加载更多（本会话）」：从游标续扫下一段并揭示新增命中；扫尽摘按钮。 */
  function syncConvMore(cur, onMore) {
    cur.moreBtn?.remove();
    cur.moreBtn = null;
    if (cur.done) return;
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'mh-sr-more';
    btn.textContent = t('mhsearch.loadMore');
    btn.addEventListener('click', async () => {
      if (cur.scanning) return;
      btn.disabled = true;
      const mySeq = seq;
      const signal = abortCtl?.signal || new AbortController().signal;
      const ok = await scanConversationChunk(cur, mySeq, signal);
      if (!ok || mySeq !== seq) return;
      const added = paintConvMatches(cur, cur.q);
      btn.remove();
      cur.moreBtn = null;
      if (!cur.done) syncConvMore(cur, onMore);
      onMore(added);
    });
    resultsHost.appendChild(btn);
    cur.moreBtn = btn;
  }

  /** 渐进主检索：快照会话表 → 并发 2 逐会话翻页扫描 → 命中即增量上屏（按会话分组）。 */
  async function runSearch(fullScan = false) {
    const q = (input.value || '').trim();
    const mySeq = ++seq;
    abortCtl?.abort();
    abortCtl = new AbortController();
    const signal = abortCtl.signal;
    resultsHost.replaceChildren();
    if (!q) { setStatus(''); return; }
    setStatus(t('mhsearch.searching'));
    let convs = conversationSnapshot();
    if (!convs.length) {
      // 名册未初始化（极早首次进入）——回落直连快照；此后随列表刷新自然有值。
      try { convs = (await api.getConversations()) || []; } catch { convs = []; }
    }
    if (mySeq !== seq) return;
    convs = convs.filter(scopeFilter);

    // 描述命中段：会话档案描述不进正文检索，作为会话级命中单独成组。
    const descHits = convs.filter((c) => descMatches(q, convDescribeFields(c)));
    if (descHits.length) {
      const g = document.createElement('div');
      g.className = 'mh-sr-group mh-sr-group-desc';
      g.textContent = `${t('mhsearch.descHits', { n: descHits.length })}`;
      resultsHost.appendChild(g);
      for (const conv of descHits) resultsHost.appendChild(buildDescHitRow(conv, log));
    }

    if (host.boundedGateway && !fullScan) {
      try {
        const result = await api.searchSocialMessages(q, convs.map(c => String(c.conversationId)));
        if (mySeq !== seq || signal.aborted) return;
        const byId = new Map(convs.map(c => [String(c.conversationId), c]));
        let total = 0;
        for (const conv of convs) {
          const hits = (result.hits || []).filter(h => String(h.conversationId) === String(conv.conversationId));
          if (!hits.length || !byId.has(String(conv.conversationId))) continue;
          const header = document.createElement('div');
          header.className = 'mh-sr-group';
          header.textContent = convGroupLabel(conv, hits.length);
          resultsHost.appendChild(header);
          for (const hit of hits) { resultsHost.appendChild(buildResultRow(conv, hit.message, q, log)); total++; }
        }
        setStatus(`${total ? t('mhsearch.resultCount', { n: total }) : t('mhsearch.noResults')} · ${t('mhsearch.scanScope', { pages: result.pages })}${result.truncated ? ' · ' + t('mhsearch.scanPartial') : ''}${result.failed ? ' · ' + t('mhsearch.scanFailed') : ''}`);
        if (result.truncated || result.failed) {
          const more = document.createElement('button');
          more.type = 'button';
          more.className = 'mh-sr-more';
          more.textContent = t('mhsearch.fullScan');
          more.addEventListener('click', () => runSearch(true));
          resultsHost.appendChild(more);
        }
      } catch {
        if (mySeq === seq) {
          setStatus(t('mhsearch.scanFailed'));
          const more = document.createElement('button');
          more.type = 'button';
          more.className = 'mh-sr-more';
          more.textContent = t('mhsearch.fullScan');
          more.addEventListener('click', () => runSearch(true));
          resultsHost.appendChild(more);
        }
      }
      return;
    }

    let total = 0;
    let cursor = 0;
    const workers = [0, 1].map(async () => {
      while (cursor < convs.length) {
        if (mySeq !== seq || signal.aborted) return;
        const conv = convs[cursor++];
        /** @type {any} */
        const cur = {
          conv, q, after: 0, matches: [], painted: 0, done: false, scanning: false,
          headerEl: null, moreBtn: null,
        };
        const ok = await scanConversationChunk(cur, mySeq, signal);
        if (!ok || mySeq !== seq || signal.aborted) return;
        if (!cur.matches.length && cur.done) continue; // 扫尽且零命中：不占行
        const header = document.createElement('div');
        header.className = 'mh-sr-group';
        header.textContent = convGroupLabel(conv, cur.matches.length);
        resultsHost.appendChild(header);
        cur.headerEl = header;
        total += paintConvMatches(cur, q);
        if (!cur.done) syncConvMore(cur, (added) => {
          total += added;
          setStatus(t('mhsearch.resultCount', { n: total }));
        });
        setStatus(t('mhsearch.resultCount', { n: total }));
      }
    });
    await Promise.all(workers);
    if (mySeq !== seq || signal.aborted) return;
    setStatus(total
      ? t('mhsearch.resultCount', { n: total })
      : `${t('mhsearch.noResults')} · ${t('mhsearch.noResultsHint')}`);
  }

  function destroy() {
    if (debTimer) { clearTimeout(debTimer); debTimer = null; }
    seq++;
    abortCtl?.abort();
    abortCtl = null;
    resultsHost.replaceChildren();
    setStatus('');
    scopeRowEl = null;
  }

  return {
    onValueInput, submitNow, hardReset, scopeRow,
    getScope: () => scope,
    setScope: (v) => { scope = v; },
    destroy,
  };
}

// ── 搜索字段部件（两端同 markup：shell 类 .sf-field + .nf-glass 材质）──────
// 几何/材质单源 = css/searchField.css（--sfs-* 令牌 + .sf-field 组件）；
// 手机侧由 css/mobileSearchFlat.css 消费同一组令牌（mobileLayer.css 本批
// 冻结不动 —— 见 searchField.css 头注「差异点」段）。

/** 居左搜索图标（lucide search，尺寸/字色归 .sf-field 图标档）。 */
export function buildSearchIcon() {
  const ic = document.createElement('i');
  ic.setAttribute('data-lucide', 'search');
  ic.setAttribute('aria-hidden', 'true');
  return ic;
}

/** 清空键：清输入 ⇒ 走与键入同一条 input 处理腿（单一驱动，零第二套复位逻辑），
 *  清完回焦输入框。shell 上的 .has-text 由 buildFieldShell 的 input 监听驱动。 */
export function buildSearchClear(input, shell) {
  const clear = document.createElement('button');
  clear.type = 'button';
  clear.className = 'sf-field-clear';
  clear.title = t('mhsearch.clear');
  clear.setAttribute('aria-label', t('mhsearch.clear'));
  const icon = document.createElement('i');
  icon.dataset.lucide = 'x';
  clear.append(icon);
  clear.addEventListener('click', () => {
    input.value = '';
    shell.classList.remove('has-text');
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.focus();
  });
  return clear;
}

/** 输入行壳（icon + input + clear 的公共骨架，两端同一个构建器）：
 *  · 手机消息/联系人常驻条：传 shellClass 'mh-search-bar'、input 自带
 *    'mh-search-input'、clear 侧调用方再补 'mh-search-clear'（收起态隐藏等
 *    手机层选择器认这个类名）—— 骨架不动，类名各端追加；
 *  · 桌面消息面板 / 联系人搜索块：直接用 .sf-field 档（几何单源
 *    css/searchField.css）；
 *  · 追加件（如联系人页的提交键）由调用方自行 append —— 本骨架不锁死；
 *  · has-text 态随输入翻转（clear 显隐判据）。 */
export function buildFieldShell(input, { placeholder, shellClass } = {}) {
  const shell = document.createElement('div');
  shell.className = 'sf-field nf-glass' + (shellClass ? ` ${shellClass}` : '');
  input.classList.add('sf-field-input');
  if (placeholder != null) {
    input.placeholder = placeholder;
    input.setAttribute('aria-label', placeholder);
  }
  input.addEventListener('input', () => shell.classList.toggle('has-text', input.value.length > 0));
  const icon = buildSearchIcon();
  const clear = buildSearchClear(input, shell);
  shell.append(icon, input, clear);
  return { shell, icon, clear };
}
