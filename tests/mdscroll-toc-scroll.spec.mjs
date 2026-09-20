// mdscroll-toc-scroll.spec.mjs — md 目录（TOC）跳转的滚动泄露：机制判红/判绿读数
//
// 作者令 2026-09-20 22:56（Windows 实测）逐字：「我在 windows 上点进 md 里的内容跳转的目录，
// 点了之后，不仅是内容跳转了，nebflow 客户端整体也上移了一点。等于是这个滚动有泄露」
//
// 被测真实链路（前端零替身）：
//   workspace-open-item → canvas.js openWorkspaceItem → fileViewers.js renderFile
//   → viewers/markdown.js viewMarkdown（真 slug id + 真 click handler）
//   → 真 click 命中 `.canvas-md-viewer a[href^="#"]`（首腿用真 pointer click 覆盖命中面）
// 后端替身仅两处（沿用 canvas-preview-nav-parity.spec.mjs 同一套）：
//   /api/nf-ticket + /api/nf-file（tests/nf-ticket-mock.mjs 假票）与 WS `pop.readFile` 应答。
//
// ⚠ 泄露条件（本 harness 的判据前提，显式声明、非隐藏构造）：
//   `scrollIntoView({block:'start'})` 会写**整条祖先滚动容器链**，每一层的位移量上限 = 该层自身
//   的可滚余量（scrollHeight − clientHeight，`overflow:hidden` 层同样可被程序化滚动）。
//   ⇒ 泄露可见的**必要条件** = 祖先链上存在残量 > 0 的层。
//   本机矩阵实测（R0，Chromium/WebKit × 视口 1600×1000 … 375×812 × DSF 1/1.25/1.5）：
//   画板栈各层**余量恒为 0**（flex + 裁剪，永不溢出）⇒ 本机自然态不可判红；作者 Windows 端
//   显然带了残量（DPI 缩放下 100vh 亚像素取整 / 经典滚动条重排 ⇒ 根滚动层残量「一点」）。
//   故 R2/R3/R5 以**声明式残量注入**复现同一残量族（注入 = 只加一个 absolute 溢出子元素，
//   形状与真实成因同构：`#main` 注入 ⇒ 根滚动层残量 = 整客户端可滚；`#canvas-panel` 注入 ⇒
//   画板栈残量 = 面板内容可滚），改前判红 / 改后判绿。**修法与残量来源无关**（跳转不再触碰
//   祖先链 ⇒ 残量再怎么来都不动）。
//
// 运行：node tests/mdscroll-toc-scroll.spec.mjs                # Chromium（门禁档）
//      ENGINE=webkit node tests/mdscroll-toc-scroll.spec.mjs   # WebKit（第二引擎腿）
// 可选 env：PORT=8161、SHOTS_DIR=<dir>、LEAK_EXTRA_PX=24、HEADLESS=0

import { chromium, webkit } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir, homedir } from 'node:os';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = resolve(ROOT, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8161);
const BASE = `http://127.0.0.1:${PORT}`;
const FIX_DIR = join(tmpdir(), 'mdscroll-toc-fixtures');
const SHOTS_DIR = process.env.SHOTS_DIR || '';
const LEAK_EXTRA_PX = Number(process.env.LEAK_EXTRA_PX || 24);
const ENGINE = process.env.ENGINE || 'chromium';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ─────────────── 夹具：真 md（真 TOC 锚点 + 足够长的正文） ─────────────── */
const MD_PATH = join(FIX_DIR, 'toc-doc.md');
const PROBE_HTML = join(FIX_DIR, 'probe.html');
const SECTIONS = 40;
const A_LAST = `section-${String(SECTIONS).padStart(2, '0')}-alpha`;  // 末节：只能夹到余量上限
const A_MID = 'section-20-alpha';                                    // 中段：可达「目标行对齐」
const A_FIRST = 'section-01-alpha';
function buildMd() {
  const lines = ['# Mdscroll TOC Fixture', '', 'Contents:', ''];
  for (let i = 1; i <= SECTIONS; i++) lines.push(`- [Section ${String(i).padStart(2, '0')} Alpha](#section-${String(i).padStart(2, '0')}-alpha)`);
  lines.push('');
  for (let i = 1; i <= SECTIONS; i++) {
    lines.push(`## Section ${String(i).padStart(2, '0')} Alpha`, '');
    for (let k = 0; k < 12; k++) lines.push(`Body line ${k} of section ${i} — lorem ipsum dolor sit amet, consectetur adipiscing elit.`);
    lines.push('');
  }
  return lines.join('\n');
}
function writeFixtures() {
  rmSync(FIX_DIR, { recursive: true, force: true });
  mkdirSync(FIX_DIR, { recursive: true });
  writeFileSync(MD_PATH, buildMd());
  writeFileSync(PROBE_HTML, `<!DOCTYPE html><html><head><meta charset="utf-8"><title>probe</title></head><body>
<a id="hash-link" href="#tail">hash</a>
<div style="height:2400px"></div>
<h2 id="tail">TAIL</h2>
</body></html>`);
}

/* ─────────────── 静态服务（真 web/ 树；自有端口） ─────────────── */
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.md': 'text/markdown; charset=utf-8',
};
const webServer = createServer((req, res) => {
  const u = new URL(req.url, BASE);
  let p = decodeURIComponent(u.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => webServer.listen(PORT, '127.0.0.1', r));
writeFixtures();

/* ─────────────── 应用引导（WS 替身：pop.readFile 真语义） ─────────────── */
const EXT_ITEM = { html: 'html', htm: 'html', md: 'markdown', markdown: 'markdown', json: 'json' };
function mkBoot(browser) {
  return async function boot(opts) {
    const ctx = await browser.newContext(opts || { viewport: { width: 1600, height: 1000 } });
    await ctx.addInitScript(() => {
      localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
      localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    });
    const page = await ctx.newPage();
    page.setDefaultTimeout(8000);
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
    await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
    await installTicketMock(page);
    await page.routeWebSocket(/\/ws/, (ws) => {
      ws.onMessage((raw) => {
        let m; try { m = JSON.parse(raw); } catch { return; }
        if (m.type === 'getHistory') {
          return ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
        }
        if (m.type !== 'pop.readFile') return;
        const rawPath = String(m.path || '');
        const abs = rawPath.startsWith('~') ? join(homedir(), rawPath.slice(1).replace(/^[/\\]/, '')) : rawPath;
        try {
          const buf = readFileSync(abs);
          const ext = (abs.split('.').pop() || '').toLowerCase();
          ws.send(JSON.stringify({
            type: 'fileContent', path: rawPath, absPath: abs, itemType: EXT_ITEM[ext] || 'code',
            fileName: abs.split('/').pop(), size: buf.length, content: buf.toString('utf8'),
          }));
        } catch (e) {
          ws.send(JSON.stringify({ type: 'fileContent', error: String((e && e.message) || e), path: rawPath, absPath: abs }));
        }
      });
      ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
      ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
    });
    await page.goto(BASE + '/index.html');
    await page.waitForSelector('#activity-bar', { timeout: 20000 });
    await page.evaluate(() => {
      document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove());
      document.documentElement.classList.remove('ui-init');
    });
    await sleep(500);
    return { ctx, page, errors };
  };
}

/* ─────────────── 页面助手（返回真 pane / 真 viewer 渲染） ─────────────── */
async function openMdTab(page) {
  const content = readFileSync(MD_PATH, 'utf8');
  await page.evaluate(({ absPath, content }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title: 'toc-doc.md', content, absPath, pinned: true },
    }));
  }, { absPath: MD_PATH, content });
  await page.waitForSelector('.canvas-tab-pane.active .canvas-md-scroll');
  await sleep(300);
}
async function openHtmlTab(page) {
  const content = readFileSync(PROBE_HTML, 'utf8');
  await page.evaluate(({ absPath, content }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'html', title: 'probe.html', content, absPath, pinned: true },
    }));
  }, { absPath: PROBE_HTML, content });
  await page.waitForSelector('.canvas-tab-pane.active iframe[data-nf-canvas-html]');
  await sleep(600);
}

/** 逐层读祖先链（自 `.canvas-md-scroll` 到 documentElement）：scrollTop/scrollLeft + 余量 + overflow。 */
const READ_CHAIN = () => {
  const de = document.documentElement;
  const start = document.querySelector('.canvas-md-scroll');
  const out = { layers: [], docEl: { top: de.scrollTop, left: de.scrollLeft }, winY: window.scrollY, winX: window.scrollX };
  if (!start) return out;
  let el = start;
  while (el && el !== de) {
    const sel = el.id ? '#' + el.id : (el.className ? '.' + String(el.className).split(/\s+/)[0] : el.tagName.toLowerCase());
    out.layers.push({
      sel, top: el.scrollTop, left: el.scrollLeft,
      sh: el.scrollHeight, ch: el.clientHeight, cw: el.clientWidth, sw: el.scrollWidth,
      residualY: Math.max(0, el.scrollHeight - el.clientHeight),
      residualX: Math.max(0, el.scrollWidth - el.clientWidth),
      overflowY: getComputedStyle(el).overflowY,
      overscrollY: getComputedStyle(el).overscrollBehaviorY,
    });
    el = el.parentElement;
  }
  const sc = start;
  out.scroller = { top: sc.scrollTop, residual: Math.max(0, sc.scrollHeight - sc.clientHeight) };
  return out;
};

/** 目标顶边 − 滚动口顶边（负 = 目标在滚动口之上）。 */
const ALIGN_ERR = (id) => {
  const sc = document.querySelector('.canvas-md-scroll');
  const t = sc && sc.querySelector(`[id="${id}"]`);
  if (!sc || !t) return null;
  return t.getBoundingClientRect().top - sc.getBoundingClientRect().top;
};

/** 真 click（走 viewer 的 click handler 同一条监听路径；不做命中测试）。 */
const DOM_CLICK = (href) => {
  const a = document.querySelector(`.canvas-md-scroll a[href="${href}"]`);
  if (!a) return false;
  a.click();
  return true;
};

/** 等滚动落定：smooth 动画按距离有时长，固定 sleep 会读到动画中途的假读数。
 *  轮询整条链的 scrollTop，连续 3 次不变即落定（上限 4s）。 */
async function settle(page) {
  let last = null, stable = 0;
  for (let i = 0; i < 40; i++) {
    const sig = await page.evaluate(() => {
      const de = document.documentElement, sc = document.querySelector('.canvas-md-scroll');
      const parts = [de.scrollTop, window.scrollY, sc ? sc.scrollTop : -1];
      let el = sc ? sc.parentElement : null;
      while (el && el !== de) { parts.push(el.scrollTop); el = el.parentElement; }
      return parts.join(',');
    });
    if (sig === last) { stable++; if (stable >= 3) return; } else { stable = 0; last = sig; }
    await sleep(100);
  }
}

/** 本次跳转的**精确期望**：目标内容偏移（= 当前 scrollTop + 目标相对滚动口的顶距），
 *  夹到 [0, 余量上限]。`block:'start'` 的固有语义就是这一条 —— 末节目标夹在上限是不可达，
 *  不是精度回退。 */
const EXPECTED_TOP = (id) => {
  const sc = document.querySelector('.canvas-md-scroll');
  const t = sc && sc.querySelector(`[id="${id}"]`);
  if (!sc || !t) return null;
  const max = Math.max(0, sc.scrollHeight - sc.clientHeight);
  const want = sc.scrollTop + (t.getBoundingClientRect().top - sc.getBoundingClientRect().top);
  return Math.max(0, Math.min(max, want));
};

/** 声明式残量注入：给宿主加一个 absolute 溢出子元素（宿主为定位上下文）。
 *  `#main`（position:relative, overflow:visible）⇒ 溢出上传至根滚动层 = 整客户端可滚；
 *  `#canvas-panel`（overflow:hidden）⇒ 面板自身成可编程滚动容器 = 面板栈残量。 */
const INJECT_RESIDUAL = ({ sel, extra }) => {
  document.querySelectorAll('[data-mdscroll-probe]').forEach((e) => e.remove());
  const host = document.querySelector(sel);
  if (!host) return null;
  const box = host.getBoundingClientRect();
  const d = document.createElement('div');
  d.setAttribute('data-mdscroll-probe', sel);
  d.style.cssText = `position:absolute;top:0;left:0;width:8px;height:${Math.round(box.height) + extra}px;pointer-events:none;visibility:hidden;`;
  host.appendChild(d);
  return Math.round(box.height) + extra;
};

const CLEAR_RESIDUAL = () => { document.querySelectorAll('[data-mdscroll-probe]').forEach((e) => e.remove()); };

/* ─────────────── 结果收集 ─────────────── */
const results = [];
let pass = 0, fail = 0;
function ok(name, cond, detail = '') {
  if (cond) { pass++; results.push(`  PASS  ${name}${detail ? ' — ' + detail : ''}`); }
  else { fail++; results.push(`  FAIL  ${name}${detail ? ' — ' + detail : ''}`); }
}
function info(line) { results.push('        ' + line); }
function movedLayers(before, after) {
  const out = [];
  for (const b of before.layers) {
    const a = after.layers.find((x) => x.sel === b.sel);
    if (a && (a.top !== b.top || a.left !== b.left)) out.push(`${b.sel}(top ${b.top}->${a.top}, left ${b.left}->${a.left})`);
  }
  if (before.docEl.top !== after.docEl.top || before.docEl.left !== after.docEl.left) out.push(`documentElement(top ${before.docEl.top}->${after.docEl.top}, left ${before.docEl.left}->${after.docEl.left})`);
  if (before.winY !== after.winY || before.winX !== after.winX) out.push(`window(scrollY ${before.winY}->${after.winY})`);
  return out;
}
/** 只看「外层」（除内容容器自身以外的一切滚动层 + 根滚动层）。 */
function outerMoved(before, after) {
  return movedLayers(before, after).filter((s) => !s.startsWith('.canvas-md-scroll('));
}

/* ═══════════════════════════ 主流程 ═══════════════════════════ */
const browser = await (ENGINE === 'webkit' ? webkit : chromium).launch({ headless: process.env.HEADLESS !== '0' });
const boot = mkBoot(browser);
console.log(`[mdscroll] engine=${ENGINE} port=${PORT} fixture=${MD_PATH} leakExtraPx=${LEAK_EXTRA_PX}`);

/* ── R0: 祖先链余量清册（自然态）+ 视口/DSF 矩阵 ── */
{
  const { ctx, page } = await boot();
  await openMdTab(page);
  const chain = await page.evaluate(READ_CHAIN);
  info('── R0 祖先链滚动余量清册（自然态，自内向外）──');
  for (const l of chain.layers) {
    info(`   ${l.sel}  overflowY=${l.overflowY}  clientH=${l.ch} scrollH=${l.sh} 余量Y=${l.residualY}px 余量X=${l.residualX}px  overscroll-behavior-y=${l.overscrollY}`);
  }
  info(`   documentElement scrollTop=${chain.docEl.top} / window.scrollY=${chain.winY}`);
  ok('R0-a 内容容器可滚且余量充足', chain.scroller.residual > 100, `余量 ${chain.scroller.residual}px`);
  const outerRes = chain.layers.slice(1).filter((l) => l.residualY > 0 || l.residualX > 0);
  info(`   外层（非内容容器）中可编程可滚的层：${outerRes.length ? outerRes.map((l) => `${l.sel}(Y${l.residualY}/X${l.residualX})`).join(', ') : '无'}`);
  const gaps = chain.layers.filter((l) => l.residualY > 0 && l.overflowY === 'auto' && l.overscrollY !== 'contain');
  info(`   缺失 overscroll-behavior=contain 的可滚容器：${gaps.length ? gaps.map((l) => `${l.sel}(overscroll-y=${l.overscrollY})`).join(', ') : '无'}`);
  await ctx.close();

  const matrix = [
    { w: 1600, h: 1000, dsf: 1 }, { w: 1440, h: 900, dsf: 1 }, { w: 1366, h: 768, dsf: 1.25 },
    { w: 1280, h: 800, dsf: 1.5 }, { w: 1024, h: 640, dsf: 1 }, { w: 375, h: 812, dsf: 1 },
  ];
  const rows = [];
  for (const m of matrix) {
    const { ctx: c, page: p } = await boot({ viewport: { width: m.w, height: m.h }, deviceScaleFactor: m.dsf });
    await openMdTab(p);
    const ch = await p.evaluate(READ_CHAIN);
    const outerY = ch.layers.slice(1).filter((l) => l.residualY > 0).map((l) => `${l.sel}Y${l.residualY}`);
    const outerX = ch.layers.slice(1).filter((l) => l.residualX > 0).map((l) => `${l.sel}X${l.residualX}`);
    const docY = await p.evaluate(() => Math.max(0, document.documentElement.scrollHeight - document.documentElement.clientHeight));
    rows.push(`${m.w}x${m.h}@${m.dsf}: 外层垂直残量 ${outerY.length ? outerY.join('+') + (docY ? `+documentElementY${docY}` : '') : '=0'}`);
    if (outerX.length) info(`   [${m.w}x${m.h}@${m.dsf}] 外层水平残量（非本判据轴）：${outerX.join(', ')}`);
    await c.close();
  }
  info(`   视口/DSF 矩阵外层垂直残量：${rows.join(' | ')}`);
  ok('R0-b 全矩阵外层垂直残量恒为 0（本机自然态不可判红 —— 已声明，红证走 R2/R3 残量条件腿）',
    rows.every((r) => r.endsWith('=0')), rows.join(' | '));
}

/* ── R1: 自然态跳转（真 pointer click，命中面）+ 内容容器判据 ── */
{
  const { ctx, page, errors } = await boot();
  await openMdTab(page);
  const b = await page.evaluate(READ_CHAIN);
  const expLast = await page.evaluate(EXPECTED_TOP, A_LAST);
  await page.click(`.canvas-md-scroll a[href="#${A_LAST}"]`);   // 真 pointer click（锚点在可视区，无 Playwright 预滚）
  await settle(page);
  const a = await page.evaluate(READ_CHAIN);
  const alignLast = await page.evaluate(ALIGN_ERR, A_LAST);
  const moved = movedLayers(b, a);
  info('── R1 自然态：点末节目录项（真 pointer click）──');
  info(`   内容容器 scrollTop ${b.scroller.top} -> ${a.scroller.top}（余量上限 ${a.scroller.residual}，精确期望 ${expLast}）；对齐误差 ${alignLast == null ? 'null' : alignLast.toFixed(2) + 'px'}`);
  info(`   变动层 = ${moved.join(' | ') || '(none)'}`);
  ok('R1-a 内容容器真跳转（Δ > 100px）', a.scroller.top - b.scroller.top > 100, `Δ=${a.scroller.top - b.scroller.top}px`);
  ok('R1-b 落点 = 精确期望（夹余量上限，末节不可对齐是固有语义非回退）', expLast != null && Math.abs(a.scroller.top - expLast) <= 1,
    `scrollTop=${a.scroller.top} vs 期望 ${expLast}`);
  ok('R1-c 自然态外层零变动', outerMoved(b, a).length === 0, outerMoved(b, a).join(' | ') || '无变动');

  // 中段目标：可达「目标行对齐」
  const b2 = await page.evaluate(READ_CHAIN);
  const expMid = await page.evaluate(EXPECTED_TOP, A_MID);
  await page.evaluate(DOM_CLICK, `#${A_MID}`);
  await settle(page);
  const a2 = await page.evaluate(READ_CHAIN);
  const alignMid = await page.evaluate(ALIGN_ERR, A_MID);
  info(`   中段目标（${A_MID}）：scrollTop ${b2.scroller.top} -> ${a2.scroller.top}（期望 ${expMid}）；对齐误差 ${alignMid == null ? 'null' : alignMid.toFixed(2) + 'px'}`);
  ok('R1-d 中段目标「目标行对齐」≤ 1px（跳转精度判据）', alignMid != null && Math.abs(alignMid) <= 1 && Math.abs(a2.scroller.top - expMid) <= 1,
    alignMid == null ? 'null' : `${alignMid.toFixed(2)}px`);
  ok('R1-e 中段跳转外层零变动', outerMoved(b2, a2).length === 0, outerMoved(b2, a2).join(' | ') || '无变动');

  // 反向：跳回首节
  const b3 = await page.evaluate(READ_CHAIN);
  const expFirst = await page.evaluate(EXPECTED_TOP, A_FIRST);
  await page.evaluate(DOM_CLICK, `#${A_FIRST}`);
  await settle(page);
  const a3 = await page.evaluate(READ_CHAIN);
  const alignFirst = await page.evaluate(ALIGN_ERR, A_FIRST);
  info(`   反向（${A_FIRST}）：scrollTop ${b3.scroller.top} -> ${a3.scroller.top}（期望 ${expFirst}）；对齐误差 ${alignFirst == null ? 'null' : alignFirst.toFixed(2) + 'px'}`);
  ok('R1-f 反向跳转真发生 + 落点 = 期望（≤ 1px）',
    Math.abs(a3.scroller.top - b3.scroller.top) > 100 && expFirst != null && Math.abs(a3.scroller.top - expFirst) <= 1,
    `Δ=${a3.scroller.top - b3.scroller.top}px / 期望 ${expFirst}`);
  ok('R1-g 反向外层零变动', outerMoved(b3, a3).length === 0, outerMoved(b3, a3).join(' | ') || '无变动');
  if (SHOTS_DIR) { mkdirSync(SHOTS_DIR, { recursive: true }); await page.screenshot({ path: join(SHOTS_DIR, `toc-${ENGINE}.png`) }); }
  info(`   页面错误：${errors.length ? errors.slice(0, 3).join(' | ') : 'none'}`);
  ok('R1-h 零新增页面错误', errors.length === 0, errors.slice(0, 2).join(' | ') || 'none');
  await ctx.close();
}

/* ── R2: 泄露条件腿① —— 根滚动层残量（整客户端可滚）⇒ 祖先 scrollTop 变化量 = 红证面 ── */
{
  const { ctx, page } = await boot();
  await openMdTab(page);
  await page.evaluate(INJECT_RESIDUAL, { sel: '#main', extra: LEAK_EXTRA_PX });
  await sleep(250);
  const rootRes = await page.evaluate(() => Math.max(0, document.documentElement.scrollHeight - document.documentElement.clientHeight));
  const b = await page.evaluate(READ_CHAIN);
  const expMid = await page.evaluate(EXPECTED_TOP, A_MID);
  await page.evaluate(DOM_CLICK, `#${A_MID}`);
  await settle(page);
  const a = await page.evaluate(READ_CHAIN);
  const moved = movedLayers(b, a);
  const outer = outerMoved(b, a);
  const alignMid = await page.evaluate(ALIGN_ERR, A_MID);
  info(`── R2 泄露条件腿①：根滚动层残量 = ${LEAK_EXTRA_PX}px（注入 #main absolute 溢出子元素，声明式构造）──`);
  info(`   注入后 documentElement 余量 = ${rootRes}px`);
  info(`   内容容器 scrollTop ${b.scroller.top} -> ${a.scroller.top}（期望 ${expMid}）`);
  info(`   外层变动量（红证面）= ${outer.length ? outer.join(' | ') : '(none)'}（全变动层：${moved.join(' | ') || '(none)'}）；对齐误差 ${alignMid == null ? 'null' : alignMid.toFixed(2) + 'px'}`);
  ok('R2-a 泄露条件成立（根滚动层残量 > 0）', rootRes > 0, `${rootRes}px`);
  ok('R2-b 内容容器仍真跳转（Δ > 100px）', a.scroller.top - b.scroller.top > 100, `Δ=${a.scroller.top - b.scroller.top}px`);
  ok('🎯 R2-c 零泄露：残量条件下外层 scrollTop 变化量 = 0', outer.length === 0, outer.join(' | ') || '无变动');
  ok('R2-d 对齐不回退：落点 = 精确期望（≤ 1px）', alignMid != null && Math.abs(alignMid) <= 1 && expMid != null && Math.abs(a.scroller.top - expMid) <= 1,
    `${alignMid == null ? 'null' : alignMid.toFixed(2) + 'px'} / 期望 ${expMid}`);
  await ctx.close();
}

/* ── R3: 泄露条件腿② —— 画板栈残量（面板内容可滚） ── */
{
  const { ctx, page } = await boot();
  await openMdTab(page);
  await page.evaluate(INJECT_RESIDUAL, { sel: '#canvas-panel', extra: LEAK_EXTRA_PX });
  await sleep(250);
  const res = await page.evaluate(() => {
    const p = document.querySelector('#canvas-panel');
    return Math.max(0, p.scrollHeight - p.clientHeight);
  });
  const b = await page.evaluate(READ_CHAIN);
  const expMid = await page.evaluate(EXPECTED_TOP, A_MID);
  await page.evaluate(DOM_CLICK, `#${A_MID}`);
  await settle(page);
  const a = await page.evaluate(READ_CHAIN);
  const outer = outerMoved(b, a);
  const alignMid = await page.evaluate(ALIGN_ERR, A_MID);
  info('── R3 泄露条件腿②：画板栈残量（注入 #canvas-panel）──');
  info(`   注入后 #canvas-panel 余量 = ${res}px；外层变动量 = ${outer.length ? outer.join(' | ') : '(none)'}；对齐误差 ${alignMid == null ? 'null' : alignMid.toFixed(2) + 'px'}`);
  ok('R3-a 泄露条件成立（画板栈残量 > 0）', res > 0, `${res}px`);
  ok('🎯 R3-b 零泄露：画板栈残量条件下外层 scrollTop 变化量 = 0', outer.length === 0, outer.join(' | ') || '无变动');
  ok('R3-c 内容容器仍真跳转（Δ > 100px）', a.scroller.top - b.scroller.top > 100, `Δ=${a.scroller.top - b.scroller.top}px`);
  ok('R3-d 对齐不回退：落点 = 精确期望（≤ 1px）', alignMid != null && Math.abs(alignMid) <= 1 && expMid != null && Math.abs(a.scroller.top - expMid) <= 1,
    `${alignMid == null ? 'null' : alignMid.toFixed(2) + 'px'} / 期望 ${expMid}`);
  await ctx.close();
}

/* ── R4: 用户滚动链（wheel）在残量条件下不外泄 + contain 施层面实验 ── */
{
  const { ctx, page } = await boot();
  await openMdTab(page);
  await page.evaluate(INJECT_RESIDUAL, { sel: '#main', extra: LEAK_EXTRA_PX });
  await sleep(200);
  const box = await page.locator('.canvas-md-scroll').boundingBox();
  const wheelToEndAndBeyond = async (rounds = 8) => {
    await page.evaluate(() => { const s = document.querySelector('.canvas-md-scroll'); s.scrollTop = s.scrollHeight; });
    await sleep(150);
    const before = await page.evaluate(READ_CHAIN);
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    for (let i = 0; i < rounds; i++) await page.mouse.wheel(0, 400);
    await sleep(500);
    const after = await page.evaluate(READ_CHAIN);
    return { before, after, outer: outerMoved(before, after) };
  };
  const noContain = await wheelToEndAndBeyond();
  info('── R4 用户滚动链（容器到底后继续 wheel ×8；根滚动层残量 ' + LEAK_EXTRA_PX + 'px）──');
  info(`   ① 当前 CSS：外层变动量 = ${noContain.outer.length ? noContain.outer.join(' | ') : '0'}`);
  info('   ⚠ 本引擎读数说明：根滚动层是 overflow:hidden（由 body 传播）⇒ **不是用户可滚容器**，'
    + 'Chromium 的 wheel 链条在它之前就停住（程序化写可以、用户滚不进去）⇒ 本轴在本引擎不可判红；'
    + '施层面因此不由「哪层截断」判别，而由**清册 + 全应用一致性**判别（见 R4-a）。');
  // 施层面实验：逐层补 contain，量哪一层真正截断链条（本引擎预期全为「无差异」，读数照登）
  const layerSets = [
    { name: 'L1 .canvas-md-scroll', sels: ['.canvas-md-scroll'] },
    { name: 'L2 #canvas-content', sels: ['#canvas-content'] },
    { name: 'L3 #canvas-panel', sels: ['#canvas-panel'] },
    { name: 'L1+L2+L3', sels: ['.canvas-md-scroll', '#canvas-content', '#canvas-panel'] },
  ];
  const effective = [];
  for (const ls of layerSets) {
    await page.evaluate((sels) => {
      document.querySelectorAll('[data-mdscroll-overscroll-probe]').forEach((e) => e.removeAttribute('data-mdscroll-overscroll-probe'));
      for (const s of sels) {
        const el = document.querySelector(s);
        if (el) { el.setAttribute('data-mdscroll-overscroll-probe', '1'); el.style.overscrollBehavior = 'contain'; }
      }
    }, ls.sels);
    const r = await wheelToEndAndBeyond();
    const blocked = r.outer.length === 0;
    if (blocked) effective.push(ls.name);
    info(`   ${blocked ? '截断 ✔' : '未截断 ✘'}  ${ls.name}  ⇒ 外层变动量 ${r.outer.length ? r.outer.join(' | ') : '0'}`);
    await page.evaluate(() => {
      document.querySelectorAll('[data-mdscroll-overscroll-probe]').forEach((e) => { e.style.overscrollBehavior = ''; e.removeAttribute('data-mdscroll-overscroll-probe'); });
    });
  }
  info(`   ⇒ 施层面实验结论：${effective.length === layerSets.length
    ? '各候选层读数全同（本引擎无非泄露可截断）⇒ 实验不可判别，施层面取清册判据（R4-a）'
    : `有效最小集合 = ${effective[0] || '(none)'}`}`);
  // 静态判据：CSS 里真正落地的层 + 画板栈清册
  const declared = await page.evaluate(() => {
    const sels = ['.canvas-md-scroll', '#canvas-content', '#canvas-panel', '.canvas-tab-pane.active'];
    return sels.map((s) => {
      const el = document.querySelector(s);
      return {
        sel: s, present: !!el,
        contain: el ? getComputedStyle(el).overscrollBehaviorY : null,
        oy: el ? getComputedStyle(el).overflowY : null,
        userScrollable: el ? (el.scrollHeight > el.clientHeight && getComputedStyle(el).overflowY !== 'hidden' && getComputedStyle(el).overflowY !== 'visible') : false,
      };
    });
  });
  info(`   静态面 overscroll-behavior-y：${declared.map((d) => `${d.sel}=${d.present ? d.contain : '(不在场)'}(${d.present ? d.oy : '-'})`).join(' | ')}`);
  info(`   画板栈「用户可滚」层：${declared.filter((d) => d.userScrollable).map((d) => d.sel).join(', ') || '(none)'}`);
  ok('R4-a 画板栈的唯一用户可滚容器已声明 overscroll-behavior: contain（= 全应用既有 5 处同值同语义）',
    declared.find((d) => d.sel === '.canvas-md-scroll').contain === 'contain',
    `.canvas-md-scroll=${declared.find((d) => d.sel === '.canvas-md-scroll').contain}`);
  ok('R4-b 回归：wheel 到底后外层变动量 = 0（本引擎恒 0，独立于 contain）', noContain.outer.length === 0,
    noContain.outer.join(' | ') || '0');
  await ctx.close();
}

/* ── R5: 同族位点 —— canvas HTML 帧内 `#` 锚点（viewers/html.js anchorNavScript） ── */
{
  const { ctx, page } = await boot();
  await openHtmlTab(page);
  const fr = await (await page.$('.canvas-tab-pane.active iframe[data-nf-canvas-html]')).contentFrame();
  const b = await page.evaluate(READ_CHAIN);
  const fb = await fr.evaluate(() => document.documentElement.scrollTop);
  await fr.evaluate(() => document.getElementById('hash-link').click());
  await sleep(1200);
  const a = await page.evaluate(READ_CHAIN);
  const fa = await fr.evaluate(() => document.documentElement.scrollTop);
  const outer = outerMoved(b, a);
  info('── R5 同族位点：canvas HTML 帧内 `#` 锚点（viewers/html.js:38-53）──');
  info(`   帧内 documentElement.scrollTop ${fb} -> ${fa}；宿主侧变动层 = ${outer.length ? outer.join(' | ') : '(none)'}`);
  ok('R5-a 帧内锚点跳转真发生（帧自成一档）', fa - fb > 100, `Δ=${fa - fb}px`);
  ok('R5-b 帧内跳转零泄露到宿主滚动层（未修位点行为不变）', outer.length === 0, outer.join(' | ') || '无变动');
  await ctx.close();
}

await browser.close();
webServer.close();
rmSync(FIX_DIR, { recursive: true, force: true });
console.log(results.join('\n'));
console.log(`\n═══ mdscroll-toc-scroll (${ENGINE}): ${pass} PASS / ${fail} FAIL ═══`);
if (SHOTS_DIR) console.log(`shots: ${SHOTS_DIR}`);
process.exit(fail ? 1 : 0);
