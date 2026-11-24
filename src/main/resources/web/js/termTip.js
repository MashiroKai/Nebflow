// termTip.js — shared glossary-term preview popover (wiki viewer + human cards).
// Click previews the definition on desktop and touch; preview never navigates.
//
// Term content is read from the resolved graph payload, with the term API as fallback.
// 数据面两条腿（§4.4 定案）：
//   ① 主路径 = graph 载荷零请求 —— 消解 pass 已把注册表命中连同 definition
//     缓存进 dataset.wikiResolved，气泡直读（docs viewer 面）；
//   ② 兜底 = 词条不在载荷（30s TTL 内的陈旧图谱 / 服务端未重扫 / 无图谱面
//     如聊天气泡、flow/report 卡片的 ```human 卡）⇒ 点击时打
//     `GET /api/wiki/term?name=` 单查，命中同样出泡。
// Popover is body-mounted, viewport-clamped, and has no full-screen mobile state.

import { t } from './i18n.js';

let termTipEl = null;
let termTipLink = null;
let termTipHost = null;
let termTipTimer = 0;
let termTipDocBound = false;
let termTipGeneration = 0;
let termTipAbort = null;
/** 深链导航回调（docs viewer 注入 onWikilinkClick；无导航面留空 = 收泡即止）。 */
let termTipNav = null;

/** 注入气泡内点击的深链导航（docs viewer = onWikilinkClick；chat 等面不注入）。 */
export function setTermTipNav(fn) {
  termTipNav = fn;
}

function ensureTermTipEl() {
  if (termTipEl) return termTipEl;
  const el = document.createElement('div');
  el.className = 'nf-term-tip';
  el.style.display = 'none';
  el.innerHTML = '<button type="button" class="term-tip-close glass-control"></button><div class="tt"></div><div class="ts" aria-live="polite"></div><div class="td"></div><button type="button" class="term-tip-source glass-control" hidden></button><button type="button" class="term-tip-retry glass-control" hidden></button>';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-label', t('termPreview.openSource'));
  const close = el.querySelector('.term-tip-close');
  close.textContent = '×';
  close.setAttribute('aria-label', t('termPreview.close'));
  close.addEventListener('click', () => {
    const link = termTipLink;
    hideTermTip();
    if (link && link.isConnected) link.focus();
  });
  const source = el.querySelector('.term-tip-source');
  source.textContent = t('termPreview.openSource');
  source.addEventListener('click', () => {
    if (termTipNav && termTipLink && termTipHost) termTipNav(termTipLink, termTipHost);
  });
  const retry = el.querySelector('.term-tip-retry');
  retry.textContent = t('termPreview.retry');
  retry.addEventListener('click', () => {
    if (termTipLink) requestTermTip(termTipLink, termTipHost, 0);
  });
  // Interacting with the preview must never invoke the document link handler.
  el.addEventListener('click', (e) => e.stopPropagation());
  el.addEventListener('pointerdown', (e) => {
    e.stopPropagation();
    el.classList.add('interactive');
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && termTipLink) {
      e.preventDefault();
      const link = termTipLink;
      hideTermTip();
      if (link && link.isConnected) link.focus();
    }
  });
  document.body.appendChild(el);
  termTipEl = el;
  if (!termTipDocBound) {
    termTipDocBound = true;
    // 点击别处收泡（触屏首击开泡后的第二次交互面）。
    document.addEventListener('pointerdown', (e) => {
      if (!termTipLink) return;
      const t = /** @type {Node|null} */ (e.target);
      if (t && termTipEl.contains(t)) return;
      if (termTipLink && termTipLink.contains(/** @type {Node|null} */ (t))) return;
      hideTermTip();
    }, true);
    // 换页签收泡（hover 的 pane 隐藏后气泡位置失效）。
    document.addEventListener('canvas-tab-switched', hideTermTip);
  }
  return el;
}

export function hideTermTip() {
  termTipGeneration++;
  if (termTipAbort) { termTipAbort.abort(); termTipAbort = null; }
  if (termTipTimer) { clearTimeout(termTipTimer); termTipTimer = 0; }
  termTipLink = null;
  termTipHost = null;
  if (termTipEl) {
    termTipEl.style.display = 'none';
    termTipEl.classList.remove('interactive');
  }
}

// The term database is derived from the vault; keep successful lookups only briefly.
const termIndexCache = new Map();
let termIndexVersion = null;
let termIndexStatsAt = 0;
let termIndexStatsPending = false;
let termIndexEpoch = 0;

async function checkTermIndexVersion(headers) {
  const now = Date.now();
  if (termIndexStatsPending || (termIndexStatsAt && now - termIndexStatsAt < 60000)) return;
  termIndexStatsAt = now;
  termIndexStatsPending = true;
  try {
    const res = await fetch('/api/wiki/terms/stats', { headers });
    const data = res.ok ? await res.json() : null;
    if (data && typeof data.last_rebuild === 'string') {
      if (termIndexVersion !== null && termIndexVersion !== data.last_rebuild) {
        termIndexEpoch++;
        termIndexCache.clear();
      }
      termIndexVersion = data.last_rebuild;
    }
  } catch (e) { /* TTL remains the fallback when stats is unavailable. */ }
  finally { termIndexStatsPending = false; }
}

async function termInfoFor(a, signal) {
  let resolved = null;
  try { resolved = JSON.parse(a.dataset.wikiResolved || 'null'); } catch (e) { /* fallback below */ }
  const name = a.dataset.wikiTermFallback || a.dataset.wikiTarget || resolved?.term?.name || '';
  const project = a.dataset.wikiProject || '';
  const rel = resolved?.term?.rel || '';
  if (name) {
    try {
      const { authHeaders } = await import('./flowHelpers.js');
      const headers = { ...authHeaders() };
      void checkTermIndexVersion(headers);
      const key = JSON.stringify([name.toLowerCase(), project, rel]);
      const cached = termIndexCache.get(key);
      if (cached && Date.now() - cached.time < 300000) return cached.info;
      termIndexCache.delete(key);
      const epoch = termIndexEpoch;
      const res = await fetch('/api/wiki/terms/lookup?name=' + encodeURIComponent(name), {
        headers, signal: AbortSignal.any([signal, AbortSignal.timeout(5000)]),
      });
      const body = res.ok ? await res.json() : null;
      if (body?.available === true && Array.isArray(body.results)) {
        const matches = body.results.filter((item) => item && typeof item.name === 'string' &&
          (item.name.toLowerCase() === name.toLowerCase() ||
            (Array.isArray(item.aliases) && item.aliases.some((alias) => typeof alias === 'string' && alias.toLowerCase() === name.toLowerCase()))));
        const primary = matches.filter((item) => item.name.toLowerCase() === name.toLowerCase());
        const candidates = primary.length ? primary : matches;
        const scoped = candidates.length > 1 && project && rel
          ? candidates.filter((item) => item.doc?.project === project && item.doc?.rel === rel)
          : candidates;
        if (scoped.length === 1) {
          const hit = scoped[0];
          const info = { name: hit.name, definition: hit.definition || '', rel: hit.doc?.rel || '', doc: hit.source || '' };
          if (epoch === termIndexEpoch && !signal.aborted) termIndexCache.set(key, { time: Date.now(), info });
          return info;
        }
      }
    } catch (e) { /* unavailable, malformed, or timed out: use existing path */ }
  }
  return signal.aborted ? null : legacyTermInfoFor(a, signal);
}

/** Existing resolved payload, then registry lookup. */
async function legacyTermInfoFor(a, signal) {
  if (a.dataset.wikiResolved) {
    try {
      const hit = JSON.parse(a.dataset.wikiResolved);
      if (hit && hit.term) {
        return { name: hit.term.name, definition: hit.term.definition || '', rel: hit.term.rel || '', doc: hit.term.doc || '' };
      }
    } catch (e) { /* fall through to the API leg */ }
  }
  const name = a.dataset.wikiTermFallback || a.dataset.wikiTarget || '';
  if (!name) return null;
  const project = a.dataset.wikiProject || '';
  let info = null;
  try {
    const { authHeaders } = await import('./flowHelpers.js');
    const query = '?name=' + encodeURIComponent(name) + (project ? '&project=' + encodeURIComponent(project) : '');
    const res = await fetch('/api/wiki/term' + query, { headers: { ...authHeaders() }, signal });
    if (res.ok) {
      const body = await res.json();
      if (body && body.resolved && body.term) {
        info = { name: body.term.name, definition: body.term.definition || '', rel: body.term.rel || '', doc: '' };
      }
    }
  } catch (e) { info = null; }
  return info;
}

function paintTermTip(a, info) {
  const el = ensureTermTipEl();
  el.querySelector('.term-tip-retry').hidden = true;
  el.style.maxHeight = '';
  el.querySelector('.ts').style.maxHeight = '';
  el.querySelector('.tt').textContent = info.name || '';
  el.querySelector('.ts').textContent = info.definition || '';
  // 气泡的显示字符串 = 词条名 + 定义（term-system-v2 词条视觉面批 2026-11-05，
  // 作者令「不需要显示文档名」）：第三行不再拼「来源：<文档>」——文档名统一落进
  // 正文尾部自动生成的「词条索引」块（viewers/markdown.js renderTermIndex），
  // 气泡内不再作为显示文本出现。剩下的跳转提示只在真有导航承载时给（docs
  // viewer 注入了 termTipNav；聊天气泡 / human 卡没有，那里说「点击跳转」是假话）。
  el.querySelector('.td').textContent = info.rel && !/^(?:[\\/]|[a-z]:)/i.test(info.rel) ? info.rel : '';
  el.querySelector('.term-tip-source').hidden = !(info.name && termTipNav && termTipHost && a.dataset.wikiResolved);
  el.style.display = 'block';
  // Viewport-clamped placement: above the link when there is room, else below.
  const r = a.getBoundingClientRect();
  const viewport = window.visualViewport;
  const left = viewport?.offsetLeft || 0;
  const top = viewport?.offsetTop || 0;
  const vw = viewport?.width || document.documentElement.clientWidth || window.innerWidth;
  const vh = viewport?.height || document.documentElement.clientHeight || window.innerHeight;
  const below = Math.max(0, top + vh - 16 - r.bottom - 8);
  const above = Math.max(0, r.top - top - 24);
  const useBelow = below >= el.offsetHeight || below >= above;
  const available = useBelow ? below : above;
  el.style.maxHeight = Math.max(0, available) + 'px';
  const body = el.querySelector('.ts');
  body.style.maxHeight = Math.max(0, available - (el.offsetHeight - body.offsetHeight) - 4) + 'px';
  const bw = el.offsetWidth;
  const bh = el.offsetHeight;
  const x = Math.min(Math.max(left + 16, r.left + r.width / 2 - bw / 2), Math.max(left + 16, left + vw - bw - 16));
  const y = useBelow ? r.bottom + 8 : r.top - bh - 8;
  el.style.left = Math.round(x) + 'px';
  el.style.top = Math.round(y) + 'px';
}

function requestTermTip(a, host, delay) {
  hideTermTip();
  ensureTermTipEl(); // bind outside-click cancellation before the request starts
  termTipLink = a;
  termTipHost = host || null;
  const el = ensureTermTipEl();
  el.classList.add('interactive');
  paintTermTip(a, { name: '', definition: t('termPreview.loading') });
  el.querySelector('.term-tip-close').focus();
  const generation = termTipGeneration;
  const controller = new AbortController();
  termTipAbort = controller;
  termTipTimer = setTimeout(async () => {
    const info = await termInfoFor(a, controller.signal);
    if (generation !== termTipGeneration || termTipLink !== a) return;   // superseded/hidden while fetching
    if (!info) {
      paintTermTip(a, { name: '', definition: t('termPreview.unavailable') });
      el.querySelector('.term-tip-retry').hidden = false;
      return;
    }
    paintTermTip(a, info);
  }, delay);
}

/** 自定义内容形态（additive；选区解释等非词条来源复用同一弹窗本体）。
 *  不走词条名解析：直接展示 {title, content}；定位/收泡/样式与词条弹窗同一套。 */
export function showTermTipCustom(a, data) {
  hideTermTip();
  const el = ensureTermTipEl();
  termTipLink = a;
  termTipHost = null;
  el.querySelector('.tt').textContent = (data && data.title) || '';
  el.querySelector('.ts').textContent = (data && data.content) || '';
  el.querySelector('.td').textContent = '';
  el.style.display = 'block';
  const r = a.getBoundingClientRect();
  const vw = document.documentElement.clientWidth || window.innerWidth;
  const vh = document.documentElement.clientHeight || window.innerHeight;
  const bw = el.offsetWidth;
  const bh = el.offsetHeight;
  const x = Math.min(Math.max(8, r.left + r.width / 2 - bw / 2), Math.max(8, vw - bw - 8));
  let y = r.top - bh - 8;
  if (y < 8) y = Math.min(r.bottom + 8, Math.max(8, vh - bh - 8));
  el.style.left = Math.round(x) + 'px';
  el.style.top = Math.round(y) + 'px';
}

/** Click toggles the preview on both desktop and touch, without navigation. */
export function termTapExpand(a, host) {
  const kind = a.dataset.wikiTermKind;
  if (!kind) return false;
  if (termTipLink === a) { hideTermTip(); return true; }
  requestTermTip(a, host, 0);
  return true;
}

/** Mark existing term and fallback links for the shared click handler. */
export function attachTermFace(a, host, opts) {
  a.classList.add('nf-wikilink-term');
  if (opts && opts.term) a.dataset.wikiTermKind = 'term';
  else a.dataset.wikiTermKind = 'fallback';
  if (opts && !opts.term && opts.fallbackName) a.dataset.wikiTermFallback = opts.fallbackName;
}
