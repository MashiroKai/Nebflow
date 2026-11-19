#!/usr/bin/env node
// diff-review-ui.spec.mjs — 共享差异组件（js/diffView）**审阅动作区 / 模式闸门 /
// canvas 适配 / 多文件适配** 的验收基准（作者令 2026-11-19「diff 的逐块接受和全部
// 接受的 UI 要设计一下 然后 diff 要展示 canvas 适配以及多文件 diff 适配 只有 ask
// 是有逐块接受 正常只查看 diff」）。
//
// 断言面（逐条对四条要求）：
//   G0  载入健康：零 pageerror + 零非本机请求（随包 vendor 同源装载）
//   G1  模式闸门（req2）：read 面**零**接受/拒绝控件 —— 逐块钮 / 全量钮 / 状态标签
//       全不在场，且 DOM 里无隐藏残留（不靠 hidden/disabled 藏）
//   G2  动作区（req1）：review 面逐块接受/拒绝 + 全部接受/全部拒绝四钮在场，
//       触控 ≥44px；暗亮两档标签对比度 ≥4.5:1
//   G3  全量语义（req1）：全部接受 → 回调 (null,'accept') 恰一次；全部拒绝 →
//       (null,'reject')；逐块 → (hunkId,'accept')（既有通道同一语义，非第二套协议）
//   G4  状态可见（req1）：动作发出即该块落 data-chunk-state + 行内状态标签
//   G5  等待面：setWaiting(true) → 动作钮整组 disabled；false → 复位
//   G6  多文件（req4）：文件列表 ≥2 行（含计数、≥44px）；切到第 2 文件后逐块动作
//       归属该文件的 hunkId，且**不产生第 1 文件的任何动作**
//   G7  canvas 适配（req3）：真实容器链（.canvas-tab-pane>.fcv-view>.fcv-dvhost）在
//       多档面板宽度下 —— 无横滚死路、纵向可滚（非静默裁切）、四边不越界、几何可复算
//   G8  canvas 特质（2026-11-19 作者令「diff 怎么没看见 canvas 相关的特质？顶部的
//       标题呢？…全部接受或者拒绝可以做成 canvas 右下角的控件…逐块接受的样式也要
//       参考 canvas 的控件风格」）：顶部标题 = canvas 头部 chrome；全部钮 = 右下角玻璃
//       胶囊组且与编辑面零交叠；控件带为流内（零第二定位层 = 与悬浮控件的共存规则）；
//       逐块钮与带成员同档；canvas 阅读面标题 chrome 同族在场
//
// 运行：node tests/diff-review-ui.spec.mjs
// 可选 env：WEB_DIR（红面 = 指向基线树）、PORT（默认 8331）、SHOTS_DIR。

import { chromium } from 'playwright';
import { existsSync, mkdirSync, readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..');
const WEB_DIR = process.env.WEB_DIR || resolve(REPO, 'src/main/resources/web');
const HARNESS_SRC = resolve(HERE, 'fixtures/diff-review-ui/harness.html');
const PORT = Number(process.env.PORT || 8331);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR || resolve(REPO, 'evidence/diff-review-ui/shots');

// ── 环境闸（exit 2 = SKIP，不是红）──────────────────────────────────────
if (!existsSync(WEB_DIR) || !existsSync(HARNESS_SRC)) {
  console.error(`SKIP: 环境缺口（WEB_DIR=${WEB_DIR} / harness=${HARNESS_SRC}）`);
  process.exit(2);
}
if (process.env.SHOTS_DIR || !process.env.SKIP_SHOTS) mkdirSync(SHOTS_DIR, { recursive: true });

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  const p = decodeURIComponent(url.pathname);
  if (p === '/__harness.html') {
    res.writeHead(200, { 'Content-Type': MIME['.html'] });
    res.end(readFileSync(HARNESS_SRC));
    return;
  }
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR) || !existsSync(file)) { res.writeHead(404); res.end('not found'); return; }
  res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
  res.end(readFileSync(file));
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

let pass = 0, fail = 0;
const ok = (name, cond, detail = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
};

const CONTRAST_FN = String.raw`
window._srgb=function(c){c/=255;return c<=0.03928?c/12.92:Math.pow((c+0.055)/1.055,2.4);};
window._lum=function(r,g,b){return 0.2126*window._srgb(r)+0.7152*window._srgb(g)+0.0722*window._srgb(b);};
window._parse=function(s){
  if(!s)return null;
  let m=String(s).match(/rgba?\(([^)]+)\)/);
  if(m){const p=m[1].split(/[,\s/]+/).filter(Boolean).map(parseFloat);return {r:p[0],g:p[1],b:p[2],a:p.length>3?p[3]:1};}
  m=String(s).match(/color\(srgb\s+([^)]+)\)/);   // color-mix() 的计算值形态
  if(m){const p=m[1].split(/[\s/]+/).filter(Boolean).map(parseFloat);return {r:p[0]*255,g:p[1]*255,b:p[2]*255,a:p.length>3?p[3]:1};}
  const h=String(s).match(/#([0-9a-f]{6})\b/i);
  if(h){const n=parseInt(h[1],16);return {r:(n>>16)&255,g:(n>>8)&255,b:n&255,a:1};}
  return null;
};
window._comp=function(fg,bg){const a=fg.a;return {r:fg.r*a+bg.r*(1-a),g:fg.g*a+bg.g*(1-a),b:fg.b*a+bg.b*(1-a),a:1};};
window._ratio=function(f,b){const l1=window._lum(f.r,f.g,f.b),l2=window._lum(b.r,b.g,b.b);const hi=Math.max(l1,l2),lo=Math.min(l1,l2);return (hi+0.05)/(lo+0.05);};
/** 元素自身文字的对比度：文字色 × 从最外层不透明底逐层复合下来的有效底色。
 *  （旧的单层 _comp 不追踪 alpha，多层半透明 tint 会复合错方向 —— 这里按
 *   栈序从外到内正确复合。） */
window._textRatio=function(el){
  const stack=[];
  let e=el;
  while(e){ const c=window._parse(getComputedStyle(e).backgroundColor); if(c) stack.push(c); e=e.parentElement; }
  let bg={r:255,g:255,b:255,a:1};
  for(let i=stack.length-1;i>=0;i--){
    const c=stack[i];
    if(c.a<=0) continue;
    bg={ r:c.r*c.a+bg.r*(1-c.a), g:c.g*c.a+bg.g*(1-c.a), b:c.b*c.a+bg.b*(1-c.a), a:1 };
  }
  return window._ratio(window._parse(getComputedStyle(el).color), bg);
};
`;

/** Canvas 面几何读数（真实容器链；宽度由 __setPanelWidth 注入）。 */
const CANVAS_READ = () => {
  const app = document.getElementById('app');
  const pane = document.querySelector('#page-canvas .canvas-tab-pane');
  const view = pane.querySelector('.fcv-view');
  const host = pane.querySelector('.fcv-dvhost');
  const dv = host.querySelector('.nf-dv');
  const editor = host.querySelector('.nf-dv-editor');
  const sc = host.querySelector('.cm-scroller');
  const pill = pane.querySelector('.fcv-headpill');
  const pr = pane.getBoundingClientRect();
  const vr = view.getBoundingClientRect();
  const dr = dv.getBoundingClientRect();
  const sr = sc.getBoundingClientRect();
  const lines = host.querySelectorAll('.cm-line');
  const last = lines.length ? lines[lines.length - 1].getBoundingClientRect() : null;
  const prevTop = sc.scrollTop;
  sc.scrollTop = sc.scrollHeight;             // 滚到底 —— 内容必须可达
  const firstTop = lines.length ? lines[0].getBoundingClientRect().top : null;
  sc.scrollTop = 0;
  const lines2 = host.querySelectorAll('.cm-line');
  const firstAfterTop = lines2.length ? lines2[0].getBoundingClientRect().top : null;
  sc.scrollTop = prevTop;
  return {
    appWidth: app.getBoundingClientRect().width,
    paneWidth: pr.width, paneLeft: pr.left, paneTop: pr.top, paneBottom: pr.bottom,
    viewWidth: vr.width, viewTop: vr.top,
    dvWidth: dr.width, dvLeft: dr.left, dvTop: dr.top,
    hostWidth: host.getBoundingClientRect().width,
    scrollerClientWidth: sc.clientWidth, scrollerScrollWidth: sc.scrollWidth,
    scrollerOverflowY: getComputedStyle(sc).overflowY,
    scrollerTop: sr.top, scrollerBottom: sr.bottom,
    pillWidth: pill.getBoundingClientRect().width,
    lastLineBottom: last ? last.bottom : null,
    firstLineTopAfterScrollTop: firstAfterTop,
    docLines: (function () {
      const ed = host.querySelector('.nf-dv-editor');
      const api = ed && ed.__nfDiffApi;
      return (api && api.view.state.doc.lines) || 0;
    })(),
    mountedLines: lines.length,
  };
};

const browser = await chromium.launch();
try {
  const ctx = await browser.newContext({
    viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true,
    colorScheme: 'light',
  });
  const page = await ctx.newPage();
  const errors = [];
  const external = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 300)));
  page.on('request', (r) => {
    const u = r.url();
    if (!u.startsWith(BASE) && !u.startsWith('data:') && !u.startsWith('blob:')) external.push(u);
  });
  await page.addInitScript(() => { localStorage.setItem('nebflow_locale', 'zh-CN'); });
  await page.addInitScript({ content: CONTRAST_FN });
  await page.goto(BASE + '/__harness.html');
  await page.waitForFunction('window.__ready === true', null, { timeout: 20000 });
  await page.waitForTimeout(200);

  ok('G0a 载入健康：零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));
  ok('G0b 离线可用：零非本机请求（随包 vendor 同源装载）', external.length === 0, external.slice(0, 3).join(' | '));

  // ══ G1 模式闸门（req2）：read 面零动作控件，且无隐藏残留 ═══════════════════
  const readControls = await page.evaluate(() => {
    const root = document.getElementById('page-read');
    const nodes = [...root.querySelectorAll('*')];
    return {
      dataAction: root.querySelectorAll('[data-chunk-action]').length,
      chunkBtn: root.querySelectorAll('.nf-dv-chunkbtn').length,
      actionBtn: root.querySelectorAll('.nf-dv-actionbtn').length,
      chunkButtons: root.querySelectorAll('.cm-chunkButtons').length,
      stateLabel: root.querySelectorAll('.nf-dv-chunkstate').length,
      acceptText: root.textContent.includes('全部接受'),
      classResidue: nodes.filter((el) => /chunkbtn|actionbtn|nf-dv-accept-all|nf-dv-reject-all/.test(String(el.className))).length,
      hiddenResidue: nodes.filter((el) => /chunkbtn|actionbtn/.test(String(el.className)) && (el.hidden || el.disabled)).length,
      editorPresent: !!root.querySelector('.nf-dv .cm-editor'),
      wrapPresent: !!root.querySelector('.nf-dv-wrapbtn'),
    };
  });
  ok('G1a read 面零接受/拒绝控件（逐块/全量/状态标签全不在场）',
    readControls.dataAction === 0 && readControls.chunkBtn === 0 && readControls.actionBtn === 0
      && readControls.chunkButtons === 0 && readControls.stateLabel === 0 && !readControls.acceptText,
    JSON.stringify(readControls));
  ok('G1b read 面 DOM 无隐藏残留（不靠 hidden/disabled 藏）',
    readControls.classResidue === 0 && readControls.hiddenResidue === 0,
    `classResidue ${readControls.classResidue} / hiddenResidue ${readControls.hiddenResidue}`);
  ok('G1c read 面仍是只读阅读面（编辑面 + 折行开关在场）',
    readControls.editorPresent && readControls.wrapPresent, JSON.stringify(readControls));

  // ══ G2 动作区（req1）：四钮在场 + ≥44px + 对比度 ══════════════════════════
  await page.evaluate(() => window.__showPage('review'));
  await page.waitForTimeout(150);
  const area = await page.evaluate(() => {
    const root = document.getElementById('page-review');
    const hs = (sel) => [...root.querySelectorAll(sel)].map((e) => +e.getBoundingClientRect().height.toFixed(2));
    const allAccept = root.querySelector('.nf-dv-accept-all');
    const allReject = root.querySelector('.nf-dv-reject-all');
    const chunkAccept = root.querySelectorAll('.nf-dv-chunkbtn-accept').length;
    const chunkReject = root.querySelectorAll('.nf-dv-chunkbtn-reject').length;
    return {
      acceptAll: !!allAccept, rejectAll: !!allReject,
      acceptAllText: allAccept ? allAccept.textContent : '', rejectAllText: allReject ? allReject.textContent : '',
      chunkAccept, chunkReject,
      hAllAccept: hs('.nf-dv-accept-all'), hAllReject: hs('.nf-dv-reject-all'),
      hChunk: hs('.nf-dv-chunkbtn'), hWrap: hs('.nf-dv-wrapbtn'),
      mode: root.querySelector('.nf-dv').getAttribute('data-mode'),
    };
  });
  const minOf = (a) => (a.length ? Math.min(...a) : NaN);
  ok('G2a review 面四钮在场（逐块接受/拒绝 + 全部接受/全部拒绝）',
    area.acceptAll && area.rejectAll && area.chunkAccept === 2 && area.chunkReject === 2 && area.mode === 'review',
    JSON.stringify({ a: area.acceptAll, r: area.rejectAll, ca: area.chunkAccept, cr: area.chunkReject, mode: area.mode }));
  ok('G2b 动作区触控目标全部 ≥44px（逐块 / 全部 / 折行）',
    minOf(area.hChunk) >= 44 && minOf(area.hAllAccept) >= 44 && minOf(area.hAllReject) >= 44 && minOf(area.hWrap) >= 44,
    `chunk ${minOf(area.hChunk)} / acceptAll ${minOf(area.hAllAccept)} / rejectAll ${minOf(area.hAllReject)} / wrap ${minOf(area.hWrap)}`);

  const contrastOf = () => page.evaluate(() => {
    const root = document.getElementById('page-review');
    const a = root.querySelector('.nf-dv-accept-all');
    const r = root.querySelector('.nf-dv-reject-all');
    const c = root.querySelector('.nf-dv-chunkbtn-accept');
    const d = root.querySelector('.nf-dv-chunkbtn-reject');
    const f = (el) => (el ? +window._textRatio(el).toFixed(2) : null);
    return { acceptAll: f(a), rejectAll: f(r), chunkAccept: f(c), chunkReject: f(d) };
  });
  const cLight = await contrastOf();
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.waitForTimeout(150);
  const cDark = await contrastOf();
  await page.emulateMedia({ colorScheme: 'light' });
  await page.waitForTimeout(150);
  const allGe = (m) => Object.values(m).every((v) => v == null || v >= 4.5);
  ok('G2c 动作区标签对比度 ≥4.5:1（亮档）', allGe(cLight), JSON.stringify(cLight));
  ok('G2d 动作区标签对比度 ≥4.5:1（暗档）', allGe(cDark), JSON.stringify(cDark));

  // ══ G3 全量语义（req1）：同一通道同一语义 ══════════════════════════════════
  await page.evaluate(() => window.__resetChunkActions());
  await page.locator('#page-review .nf-dv-accept-all').click();
  await page.waitForTimeout(80);
  let acts = await page.evaluate(() => window.__chunkActions());
  ok('G3a 全部接受 → 回调恰一次且 hunkId=null / action=accept',
    acts.length === 1 && acts[0].hunkId === null && acts[0].action === 'accept', JSON.stringify(acts));

  await page.evaluate(() => window.__resetChunkActions());
  await page.locator('#page-review .nf-dv-reject-all').click();
  await page.waitForTimeout(80);
  acts = await page.evaluate(() => window.__chunkActions());
  ok('G3b 全部拒绝 → 回调恰一次且 hunkId=null / action=reject',
    acts.length === 1 && acts[0].hunkId === null && acts[0].action === 'reject', JSON.stringify(acts));

  await page.evaluate(() => window.__resetChunkActions());
  await page.locator('#page-review .nf-dv-chunkbtn-accept').first().click();
  await page.waitForTimeout(80);
  acts = await page.evaluate(() => window.__chunkActions());
  ok('G3c 逐块接受 → 回调带 hunkId / action=accept',
    acts.length === 1 && /^h\d$/.test(String(acts[0].hunkId)) && acts[0].action === 'accept', JSON.stringify(acts));

  // ══ G4 状态可见（req1）═════════════════════════════════════════════════════
  const visible = await page.evaluate(() => {
    const root = document.getElementById('page-review');
    const marked = [...root.querySelectorAll('[data-chunk-state]')].map((e) => e.getAttribute('data-chunk-state'));
    const labels = [...root.querySelectorAll('.nf-dv-chunkstate')].map((e) => e.textContent);
    return { marked, labels };
  });
  ok('G4a 逐块动作后该块落可见状态（data-chunk-state=accepted + 状态标签）',
    visible.marked.includes('accepted') && visible.labels.some((t) => t.includes('已接受')), JSON.stringify(visible));

  await page.evaluate(() => window.__resetChunkActions());
  await page.locator('#page-review .nf-dv-reject-all').click();
  await page.waitForTimeout(80);
  const visible2 = await page.evaluate(() => {
    const root = document.getElementById('page-review');
    const marked = [...root.querySelectorAll('[data-chunk-state]')].map((e) => e.getAttribute('data-chunk-state'));
    const labels = [...root.querySelectorAll('.nf-dv-chunkstate')].map((e) => e.textContent);
    return { marked, labels };
  });
  ok('G4b 全部拒绝 → 全部块落 rejected 状态标签（呈现能看出）',
    visible2.marked.length > 0 && visible2.marked.every((m) => m === 'rejected')
      && visible2.labels.some((t) => t.includes('已拒绝')), JSON.stringify(visible2));
  await page.evaluate(() => window.__resetChunkActions());

  // ══ G5 等待面（防双发）════════════════════════════════════════════════════
  const waiting = await page.evaluate(async () => {
    const api = window.__api('review');
    api.setWaiting(true);
    const root = document.getElementById('page-review');
    const on = {
      attr: root.querySelector('.nf-dv').getAttribute('data-waiting'),
      actionDisabled: [...root.querySelectorAll('.nf-dv-actionbtn')].every((b) => b.disabled),
      chunkDisabled: [...root.querySelectorAll('.nf-dv-chunkbtn')].every((b) => b.disabled),
      wrapDisabled: root.querySelector('.nf-dv-wrapbtn').disabled,
    };
    api.setWaiting(false);
    const off = {
      attr: root.querySelector('.nf-dv').getAttribute('data-waiting'),
      actionDisabled: [...root.querySelectorAll('.nf-dv-actionbtn')].some((b) => !b.disabled),
    };
    return { on, off };
  });
  ok('G5 等待面：动作钮整组禁用（折行开关不受影响）→ 复位',
    waiting.on.attr === '1' && waiting.on.actionDisabled && waiting.on.chunkDisabled
      && waiting.on.wrapDisabled === false && waiting.off.attr === '0' && waiting.off.actionDisabled,
    JSON.stringify(waiting));

  // ══ G6 多文件（req4）══════════════════════════════════════════════════════
  await page.evaluate(() => window.__showPage('multi'));
  await page.waitForTimeout(150);
  const multiShape = await page.evaluate(() => {
    const root = document.getElementById('page-multi');
    const rows = [...root.querySelectorAll('.nf-dv-file')];
    return {
      multifile: root.querySelector('.nf-dv').getAttribute('data-multifile'),
      rows: rows.length,
      heights: rows.map((r) => +r.getBoundingClientRect().height.toFixed(2)),
      paths: rows.map((r) => r.getAttribute('data-file-path')),
      counts: rows.map((r) => (r.querySelector('.nf-dv-filecounts') || {}).textContent || ''),
      active: rows.map((r) => r.getAttribute('data-active')),
    };
  });
  ok('G6a 多文件：文件列表 ≥2 行 + 逐行计数 + 触控 ≥44px + 选中态单点',
    multiShape.multifile === '1' && multiShape.rows === 2
      && multiShape.paths[0] === 'src/alpha.ts' && multiShape.paths[1] === 'src/beta.ts'
      && multiShape.counts.every((c) => /\+/.test(c) && /−/.test(c))
      && Math.min(...multiShape.heights) >= 44
      && multiShape.active[0] === '1' && multiShape.active[1] === null,
    JSON.stringify(multiShape));

  await page.evaluate(() => window.__resetChunkActions());
  await page.locator('#page-multi .nf-dv-file[data-file-index="1"]').click();
  await page.waitForTimeout(150);
  const afterSwitch = await page.evaluate(() => {
    const root = document.getElementById('page-multi');
    const rows = [...root.querySelectorAll('.nf-dv-file')];
    return { active: rows.map((r) => r.getAttribute('data-active')), doc: root.querySelector('.cm-content').textContent };
  });
  ok('G6b 切到第 2 文件：选中态转移 + 编辑面装载该文件文档',
    afterSwitch.active[1] === '1' && afterSwitch.active[0] === null && afterSwitch.doc.includes('TWO'),
    JSON.stringify(afterSwitch));

  await page.locator('#page-multi .nf-dv-chunkbtn-accept').first().click();
  await page.waitForTimeout(80);
  const actsMulti = await page.evaluate(() => window.__chunkActions());
  ok('G6c 第 2 文件内的逐块动作 → 归属该文件（hunkId=b0），第 1 文件零动作',
    actsMulti.length === 1 && actsMulti[0].hunkId === 'b0' && actsMulti[0].action === 'accept'
      && !actsMulti.some((a) => a.hunkId === 'a0'),
    JSON.stringify(actsMulti));

  // ══ G7 canvas 适配（req3）════════════════════════════════════════════════
  await page.evaluate(() => window.__showPage('canvas'));
  await page.waitForTimeout(200);
  const widths = [375, 768, 1440];
  const canvasReads = [];
  for (const w of widths) {
    await page.evaluate((px) => window.__setPanelWidth(px), w);
    await page.waitForTimeout(150);
    canvasReads.push(await page.evaluate(CANVAS_READ));
  }
  const noHScroll = canvasReads.every((r) => r.scrollerScrollWidth <= r.scrollerClientWidth + 1);
  const scrollable = canvasReads.every((r) => /auto|scroll/.test(r.scrollerOverflowY));
  const within = canvasReads.every((r) => r.dvWidth <= r.paneWidth + 0.5 && r.dvLeft >= r.paneLeft - 0.5 && r.viewWidth <= r.paneWidth + 0.5);
  const reachable = canvasReads.every((r) => r.lastLineBottom != null && r.lastLineBottom <= r.paneBottom + 1);
  const topNotClipped = canvasReads.every((r) => r.firstLineTopAfterScrollTop >= r.paneTop - 1);
  const pillFits = canvasReads.every((r) => r.pillWidth <= r.viewWidth + 1);
  const virtualised = canvasReads.every((r) => r.docLines > r.mountedLines);

  ok('G7a canvas 面：折行开 → 无死路横滚（scrollWidth ≤ clientWidth）', noHScroll,
    JSON.stringify(canvasReads.map((r) => ({ w: Math.round(r.paneWidth), sw: r.scrollerScrollWidth, cw: r.scrollerClientWidth }))));
  ok('G7b canvas 面：编辑面纵向可滚（overflow-y auto/scroll，非静默裁切）', scrollable,
    JSON.stringify(canvasReads.map((r) => r.scrollerOverflowY)));
  ok('G7c canvas 面：四边不越界（组件宽 ≤ 面板宽、左缘不越出、视图宽 ≤ 面板宽）', within,
    JSON.stringify(canvasReads.map((r) => ({ dv: +r.dvWidth.toFixed(1), pane: +r.paneWidth.toFixed(1), dvLeft: +r.dvLeft.toFixed(1), paneLeft: +r.paneLeft.toFixed(1) }))));
  ok('G7d canvas 面：内容底部可达（滚到底末行不越出面板底缘）+ 顶部不裁',
    reachable && topNotClipped,
    JSON.stringify(canvasReads.map((r) => ({ lastBottom: r.lastLineBottom && +r.lastLineBottom.toFixed(1), paneBottom: +r.paneBottom.toFixed(1), firstTop: r.firstLineTopAfterScrollTop && +r.firstLineTopAfterScrollTop.toFixed(1), paneTop: +r.paneTop.toFixed(1) }))));
  ok('G7e canvas 面：文件头胶囊与视图等宽约束内 + 视口虚拟化仍生效', pillFits && virtualised,
    JSON.stringify(canvasReads.map((r) => ({ pill: +r.pillWidth.toFixed(1), view: +r.viewWidth.toFixed(1), doc: r.docLines, mounted: r.mountedLines }))));

  // 几何稳定性：同一读数在面板宽度变化下的不变量（组件宽 === 宿主宽；高度链单调）。
  const stable = canvasReads.every((r) => Math.abs(r.dvWidth - r.hostWidth) < 0.75);
  ok('G7f canvas 面：面板宽度变化下几何稳定（组件宽逐值 === 宿主宽）', stable,
    JSON.stringify(canvasReads.map((r) => ({ dv: +r.dvWidth.toFixed(2), host: +r.hostWidth.toFixed(2) }))));

  // ══ G8 canvas 特质（作者令 2026-11-19「这 diff 肯定也是在 canvas 进行操作，但是
  //    怎么没看见 canvas 相关的特质？顶部的标题呢？…一次全部接受或者拒绝，可以做成
  //    canvas 右下角的控件。然后逐块接受的样式也要参考 canvas 的控件风格」）══════
  await page.evaluate(() => window.__showPage('review'));
  await page.waitForTimeout(150);
  const chrome = await page.evaluate(() => {
    const root = document.getElementById('page-review');
    const dv = root.querySelector('.nf-dv');
    const pill = root.querySelector('.nf-dv-title');
    const bar = root.querySelector('.nf-dv-allbar');
    const group = root.querySelector('.nf-dv-allgroup');
    const editor = root.querySelector('.nf-dv-editor');
    const chunk = root.querySelector('.nf-dv-chunkbtn-accept');
    const all = root.querySelector('.nf-dv-accept-all');
    const cs = (el) => (el ? getComputedStyle(el) : null);
    const r = (el) => (el ? el.getBoundingClientRect() : null);
    const glass = (s) => (s ? (s.backdropFilter && s.backdropFilter !== 'none' ? s.backdropFilter : (s.webkitBackdropFilter || 'none')) : 'none');
    const dvr = r(dv), pr = r(pill), br = r(bar), er = r(editor);
    const pcs = cs(pill), bcs = cs(bar), gcs = cs(group), ccs = cs(chunk), acs = cs(all);
    return {
      headPresent: !!root.querySelector('.nf-dv-head .nf-dv-title'),
      titleText: pill ? pill.textContent : '',
      headCenter: pr && dvr ? +(((pr.left + pr.right) / 2) - ((dvr.left + dvr.right) / 2)).toFixed(2) : null,
      pillGlass: glass(pcs), pillRadius: pcs ? pcs.borderRadius : '',
      barPresent: !!bar, groupPresent: !!group,
      barPosition: bcs ? bcs.position : '', groupPosition: gcs ? gcs.position : '',
      groupGlass: glass(gcs), groupRadius: gcs ? gcs.borderRadius : '',
      bandBtns: root.querySelectorAll('.nf-dv-allbar .nf-dv-allbtn').length,
      bandRightGap: br && dvr ? +(dvr.right - br.right).toFixed(2) : null,
      bandBottomGap: br && dvr ? +(dvr.bottom - br.bottom).toFixed(2) : null,
      bandOverlapsEditor: !!(br && er) ? !(br.right <= er.left || br.left >= er.right || br.bottom <= er.top || br.top >= er.bottom) : null,
      chunkH: ccs ? ccs.minHeight : '', allH: acs ? acs.minHeight : '',
      chunkRadius: ccs ? ccs.borderRadius : '', allRadius: acs ? acs.borderRadius : '',
      chunkHoverScale: ccs ? ccs.getPropertyValue('--nf-dv-ctl-hover-scale').trim() : '',
      allHoverScale: acs ? acs.getPropertyValue('--nf-dv-ctl-hover-scale').trim() : '',
      // canvas 阅读面（req3）的标题 chrome：真实容器链里的 .fcv-headpill（同一 chrome）。
      canvasPillGlass: glass(cs(document.querySelector('#page-canvas .fcv-headpill'))),
      canvasPillRadius: (document.querySelector('#page-canvas .fcv-headpill') && getComputedStyle(document.querySelector('#page-canvas .fcv-headpill')).borderRadius) || '',
    };
  });
  ok('G8a review 面补 canvas 顶部标题：居中玻璃胶囊 + 文件名/计数/状态文本在场',
    chrome.headPresent && chrome.titleText.includes('src/app/main.ts') && chrome.titleText.includes('+3 −2')
      && chrome.titleText.includes('2 块待审') && chrome.pillGlass !== 'none' && Math.abs(chrome.headCenter) <= 1,
    JSON.stringify({ head: chrome.headPresent, text: chrome.titleText, glass: chrome.pillGlass, center: chrome.headCenter }));

  ok('G8b 全部接受/全部拒绝落 canvas 右下角控件带（玻璃胶囊组 + 组内恰 2 钮）',
    chrome.barPresent && chrome.groupPresent && chrome.bandBtns === 2
      && chrome.groupGlass !== 'none' && chrome.groupRadius === '999px',
    JSON.stringify({ bar: chrome.barPresent, group: chrome.groupPresent, btns: chrome.bandBtns, glass: chrome.groupGlass, radius: chrome.groupRadius }));

  ok('G8c 控件带落位 = 容器右下角（右缘/底缘贴齐）且与编辑面零交叠',
    chrome.bandRightGap !== null && Math.abs(chrome.bandRightGap) <= 1
      && chrome.bandBottomGap !== null && Math.abs(chrome.bandBottomGap) <= 1
      && chrome.bandOverlapsEditor === false,
    JSON.stringify({ rightGap: chrome.bandRightGap, bottomGap: chrome.bandBottomGap, overlaps: chrome.bandOverlapsEditor }));

  ok('G8d 与既有 canvas 右下角控件的共存规则：本带为流内控制带（零 position 第二定位层）',
    chrome.barPosition === 'static' && chrome.groupPosition === 'static',
    JSON.stringify({ bar: chrome.barPosition, group: chrome.groupPosition }));

  ok('G8e 逐块接受/拒绝与控件带成员同档（尺寸/圆角/动效节拍逐值相等）',
    chrome.chunkH !== '' && chrome.chunkH === chrome.allH
      && chrome.chunkRadius !== '' && chrome.chunkRadius === chrome.allRadius
      && chrome.chunkHoverScale !== '' && chrome.chunkHoverScale === chrome.allHoverScale,
    JSON.stringify({ chunkH: chrome.chunkH, allH: chrome.allH, chunkR: chrome.chunkRadius, allR: chrome.allRadius, scale: chrome.chunkHoverScale }));

  ok('G8f canvas 阅读面标题 chrome（.fcv-headpill）同族玻璃胶囊在场',
    chrome.canvasPillGlass !== 'none' && chrome.canvasPillRadius === '999px',
    JSON.stringify({ glass: chrome.canvasPillGlass, radius: chrome.canvasPillRadius }));

  await page.emulateMedia({ colorScheme: 'dark' });
  await page.waitForTimeout(150);
  if (!process.env.SKIP_SHOTS) {
    await page.screenshot({ path: join(SHOTS_DIR, 'g-canvas-dark.png') });
  }
  await page.emulateMedia({ colorScheme: 'light' });
  await page.waitForTimeout(150);
  if (!process.env.SKIP_SHOTS) {
    await page.screenshot({ path: join(SHOTS_DIR, 'g-canvas-light.png') });
    await page.evaluate(() => window.__showPage('review'));
    await page.waitForTimeout(150);
    await page.screenshot({ path: join(SHOTS_DIR, 'g-review-light.png') });
    await page.evaluate(() => window.__showPage('multi'));
    await page.waitForTimeout(150);
    await page.screenshot({ path: join(SHOTS_DIR, 'g-multi-light.png') });
  }

  ok('G0c 收尾零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));

  if (process.env.DUMP_READS) console.log('CANVAS_READS ' + JSON.stringify(canvasReads));
} catch (e) {
  ok('SPEC 驱动中断（红面：动作区/canvas/多文件关键元素缺失）', false, String((e && e.message) || e).slice(0, 240));
} finally {
  await browser.close();
  server.close();
}
console.log(`\n${fail === 0 ? '✅' : '❌'} diff-review-ui: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
