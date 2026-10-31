// termTip.js — term hover bubble core（词条引用批 batch 2, F3/F4 机制原样迁出
// viewers/markdown.js，human-face 批 2026-10-30 抽为共享件）。
//
// 词条引用的悬停摘要卡：词条名 + 定义（≤280 字）+ 所在文档 + 跳转提示。
// 数据面两条腿（§4.4 定案）：
//   ① 主路径 = graph 载荷零请求 —— 消解 pass 已把注册表命中连同 definition
//     缓存进 dataset.wikiResolved，气泡直读（docs viewer 面）；
//   ② 兜底 = 词条不在载荷（30s TTL 内的陈旧图谱 / 服务端未重扫 / 无图谱面
//     如聊天气泡、flow/report 卡片的 ```human 卡）⇒ hover 时打
//     `GET /api/wiki/term?name=` 单查，命中同样出泡。
// 材质 = 图谱 wg-tip 同族毛玻璃 token（--glass-*，亮暗双主题）。桌面
// pointer-events:none 不挡点击（点击 = 深链跳转，由 setTermTipNav 注入的
// 导航回调承载）；触屏面 ((hover:none)) 翻 auto —— 移动无 hover，按移动惯例
// 「轻点即开气泡，气泡内再点跳转」（termTapExpand 在点击腿消费，首击不导航）。

import { t } from './i18n.js';
// 弹窗退场批（作者令 2026-10-31②）：手机层触屏词条泡 = 全屏词条卡 + 左上返回。
import { mobileSheetBack } from './mobile/popSheet.js';

let termTipEl = null;
let termTipLink = null;
let termTipHost = null;
let termTipTimer = 0;
let termTipDocBound = false;
/** 深链导航回调（docs viewer 注入 onWikilinkClick；无导航面留空 = 收泡即止）。 */
let termTipNav = null;
const TERM_TIP_DELAY = 150; // hover 防抖（§5.2：+150ms 才出泡）
/** @type {Map<string, any>} term-API 兜底腿的结果缓存（含 miss=null，hover 不重复打） */
const termApiCache = new Map();

/** 注入气泡内点击的深链导航（docs viewer = onWikilinkClick；chat 等面不注入）。 */
export function setTermTipNav(fn) {
  termTipNav = fn;
}

function noHoverEnv() {
  return !!(window.matchMedia && window.matchMedia('(hover: none)').matches);
}

function ensureTermTipEl() {
  if (termTipEl) return termTipEl;
  const el = document.createElement('div');
  el.className = 'nf-term-tip';
  // 返回 = 收泡（仅触屏腿挂；桌面 hover 泡的 .mfs-back 由 CSS 隐藏）。
  mobileSheetBack(el, hideTermTip);
  el.innerHTML = '<div class="tt"></div><div class="ts"></div><div class="td"></div>';
  // 气泡内点击 = 跳转（移动惯例；桌面 pointer-events:none 时不可达，无害）。
  el.addEventListener('click', (e) => {
    e.stopPropagation();
    const link = termTipLink;
    const host = termTipHost;
    const nav = termTipNav;
    hideTermTip();
    if (link && nav) nav(link, host);
  });
  document.body.appendChild(el);
  termTipEl = el;
  if (!termTipDocBound) {
    termTipDocBound = true;
    // 点击别处收泡（触屏首击开泡后的第二次交互面）。
    document.addEventListener('pointerdown', (e) => {
      if (!termTipEl || termTipEl.style.display === 'none') return;
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
  if (termTipTimer) { clearTimeout(termTipTimer); termTipTimer = 0; }
  termTipLink = null;
  termTipHost = null;
  if (termTipEl) termTipEl.style.display = 'none';
}

/** 气泡数据两条腿（①载荷直读 ②term API 兜底）的统一取数口。 */
async function termInfoFor(a) {
  if (a.dataset.wikiResolved) {
    try {
      const hit = JSON.parse(a.dataset.wikiResolved);
      if (hit && hit.term) {
        return { name: hit.term.name, definition: hit.term.definition || '', rel: hit.term.rel || '', doc: hit.term.doc || '' };
      }
    } catch (e) { /* fall through to the API leg */ }
  }
  const name = a.dataset.wikiTermFallback || '';
  if (!name) return null;
  const key = name.toLowerCase();
  if (termApiCache.has(key)) return termApiCache.get(key);
  let info = null;
  try {
    const { authHeaders } = await import('./flowHelpers.js');
    const res = await fetch('/api/wiki/term?name=' + encodeURIComponent(name), { headers: { ...authHeaders() } });
    if (res.ok) {
      const body = await res.json();
      if (body && body.resolved && body.term) {
        info = { name: body.term.name, definition: body.term.definition || '', rel: body.term.rel || '', doc: '' };
      }
    }
  } catch (e) { info = null; }
  termApiCache.set(key, info);
  return info;
}

function paintTermTip(a, info) {
  const el = ensureTermTipEl();
  el.classList.toggle('touch', noHoverEnv());
  // 弹窗退场批：触屏腿 show 时补注左上返回（幂等；非手机层不注入——
  // ensureTermTipEl 创建期那次在非手机层是空注）。
  if (el.classList.contains('touch')) mobileSheetBack(el, hideTermTip);
  el.querySelector('.tt').textContent = info.name || '';
  el.querySelector('.ts').textContent = info.definition || '';
  const doc = info.doc || (info.rel ? String(info.rel).split('/').pop().replace(/\.(md|markdown|html?)$/i, '') : '');
  el.querySelector('.td').textContent =
    (doc ? t('wiki.termSource') + doc + ' · ' : '') + t('wiki.termJumpHint');
  el.style.display = 'block';
  // Viewport-clamped placement: above the link when there is room, else below.
  const r = a.getBoundingClientRect();
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const bw = el.offsetWidth;
  const bh = el.offsetHeight;
  const x = Math.min(Math.max(8, r.left + r.width / 2 - bw / 2), Math.max(8, vw - bw - 8));
  let y = r.top - bh - 8;
  if (y < 8) y = Math.min(r.bottom + 8, Math.max(8, vh - bh - 8));
  el.style.left = Math.round(x) + 'px';
  el.style.top = Math.round(y) + 'px';
}

function requestTermTip(a, host, delay) {
  hideTermTip();
  termTipLink = a;
  termTipHost = host || null;
  termTipTimer = setTimeout(async () => {
    const info = await termInfoFor(a);
    if (termTipLink !== a) return;   // superseded/hidden while fetching
    if (!info) return;               // not a term after all — stay closed
    paintTermTip(a, info);
  }, delay);
}

/** 触屏首击开泡（返回 true = 点击已消费，点击腿不再导航）。第二次点击同一
 *  链接返回 false → 落回深链跳转；「气泡内再点」由气泡自身的 click 腿消费。 */
export function termTapExpand(a, host) {
  if (!noHoverEnv()) return false;
  const kind = a.dataset.wikiTermKind;
  if (!kind) { hideTermTip(); return false; }
  if (termTipLink === a) return false;
  requestTermTip(a, host, 0);
  return true;
}

/** 挂词条面（F3）：词条命中链接挂 hover 出泡（+150ms 防抖）；载荷未收录的
 *  短形 unresolved 链接挂兜底腿（hover 打 term API）。非词条普通链接不挂
 *  （§5.2 克制口径）。触屏不挂 mouseenter —— 移动走点击腿 termTapExpand。 */
export function attachTermFace(a, host, opts) {
  a.classList.add('nf-wikilink-term');
  if (opts && opts.term) a.dataset.wikiTermKind = 'term';
  else a.dataset.wikiTermKind = 'fallback';
  if (opts && !opts.term && opts.fallbackName) a.dataset.wikiTermFallback = opts.fallbackName;
  if (noHoverEnv()) return;
  a.addEventListener('mouseenter', () => requestTermTip(a, host, TERM_TIP_DELAY));
  a.addEventListener('mouseleave', () => { if (termTipLink === a) hideTermTip(); });
}
