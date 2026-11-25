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
import { showDocumentTermCard, closeDocumentTermCard } from './documentTermCard.js';

let termCard = null;
let termTipLink = null;
let termTipHost = null;
let termTipTimer = 0;
let termTipGeneration = 0;
let termTipAbort = null;
/** 深链导航回调（docs viewer 注入 onWikilinkClick；无导航面留空 = 收泡即止）。 */
let termTipNav = null;

/** 注入气泡内点击的深链导航（docs viewer = onWikilinkClick；chat 等面不注入）。 */
export function setTermTipNav(fn) {
  termTipNav = fn;
}

function clearTermTip() {
  termTipGeneration++;
  if (termTipAbort) { termTipAbort.abort(); termTipAbort = null; }
  if (termTipTimer) { clearTimeout(termTipTimer); termTipTimer = 0; }
  termTipLink = null;
  termTipHost = null;
  termCard = null;
}

export function hideTermTip() {
  if (termTipLink) closeDocumentTermCard();
  clearTermTip();
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

function paintTermTip(a, info, retry = false) {
  const available = info.name && termTipNav && termTipHost && a.dataset.wikiResolved;
  const host = termTipHost;
  termCard?.update({
    title: info.name || a.dataset.wikiTarget || a.dataset.wikiTermFallback || '词条',
    summary: info.definition || '',
    metadata: `类型：词条　出处：${info.rel || info.doc || '不可用'}${available ? '' : '　来源不可用'}`,
    open: available ? () => termTipNav(a, host) : null,
    retry: retry ? () => requestTermTip(a, host, 0) : null,
  });
}

async function requestTermTip(a, host, delay) {
  hideTermTip();
  termTipLink = a;
  termTipHost = host || null;
  const generation = termTipGeneration;
  termCard = await showDocumentTermCard(a, {
    term: { title: a.dataset.wikiTarget || '词条', summary: t('termPreview.loading') },
    onClose: clearTermTip,
  });
  if (generation !== termTipGeneration || termTipLink !== a) return;
  const controller = new AbortController();
  termTipAbort = controller;
  termTipTimer = setTimeout(async () => {
    const info = await termInfoFor(a, controller.signal);
    if (generation !== termTipGeneration || termTipLink !== a) return;
    if (!info) paintTermTip(a, { definition: t('termPreview.unavailable') }, true);
    else paintTermTip(a, info);
  }, delay);
}

/** 选区解释复用同一卡片，不请求词条或显示来源动作。 */
export async function showTermTipCustom(a, data) {
  hideTermTip();
  termTipLink = a;
  const generation = termTipGeneration;
  const card = await showDocumentTermCard(a, {
    term: { title: data?.title || '', summary: data?.content || '' },
    onClose: clearTermTip,
  });
  if (generation === termTipGeneration) termCard = card;
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
