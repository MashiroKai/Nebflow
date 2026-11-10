// termTip.js — shared glossary-term preview popover (wiki viewer + human cards).
// Click previews the definition on desktop and touch; preview never navigates.
//
// Term content is read from the resolved graph payload, with the term API as fallback.
// 数据面两条腿（§4.4 定案）：
//   ① 主路径 = graph 载荷零请求 —— 消解 pass 已把注册表命中连同 definition
//     缓存进 dataset.wikiResolved，气泡直读（docs viewer 面）；
//   ② 兜底 = 词条不在载荷（30s TTL 内的陈旧图谱 / 服务端未重扫 / 无图谱面
//     如聊天气泡、flow/report 卡片的 ```human 卡）⇒ hover 时打
//     `GET /api/wiki/term?name=` 单查，命中同样出泡。
// Popover is body-mounted, viewport-clamped, and has no full-screen mobile state.

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
  el.innerHTML = '<div class="tt"></div><div class="ts"></div><div class="td"></div>';
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
  if (termTipEl) {
    termTipEl.style.display = 'none';
    termTipEl.classList.remove('interactive');
  }
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
  el.querySelector('.tt').textContent = info.name || '';
  el.querySelector('.ts').textContent = info.definition || '';
  // 气泡的显示字符串 = 词条名 + 定义（term-system-v2 词条视觉面批 2026-11-05，
  // 作者令「不需要显示文档名」）：第三行不再拼「来源：<文档>」——文档名统一落进
  // 正文尾部自动生成的「词条索引」块（viewers/markdown.js renderTermIndex），
  // 气泡内不再作为显示文本出现。剩下的跳转提示只在真有导航承载时给（docs
  // viewer 注入了 termTipNav；聊天气泡 / human 卡没有，那里说「点击跳转」是假话）。
  el.querySelector('.td').textContent = '';
  el.style.display = 'block';
  // Viewport-clamped placement: above the link when there is room, else below.
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
  const kind = a.dataset.wikiTermKind;
  if (!kind) return false;
  if (termTipLink === a) return true;
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
