#!/usr/bin/env node
// canvas-liquid-morph-panels.spec.mjs — canvas note / ask 浮窗「液态形变 + 材质在隐藏态
// 就位」验收（作者令 2026-11-07 12:22，逐字）：
//   「还有就是我们c canvas的按钮 note和ask 展开的面板要使用液态形变的效果 自然展开
//     而且原生就是毛玻璃效果 现在会有个先透明后毛玻璃的过程」
//
// 判据（改前树 = 首帧无玻璃 + 容器 opacity 渐入，N2/N3/N6 全红；证据面见下）：
//   N0  页面零 pageerror（移动腿 / 桌面腿两档）。
//   N1  隐藏态材质就位（核心判据①）：收起态玻璃面**自身** computed
//       `backdrop-filter` 非 none，且容器零 opacity/transform（backdrop root 不形成
//       —— 「先透明后毛玻璃」的根因正是祖先 opacity/transform 形成 backdrop root，
//       令后代 backdrop-filter 只能采样子树）。
//   N2  展开逐帧材质（核心判据②）：从点击前 1 帧到落定，**每一帧**玻璃面
//       backdrop-filter 非 none、无透明度中间态（surface opacity 恒 1、容器
//       opacity 恒 1、容器 transform 恒 none），零「无玻璃帧」。
//   N3  形变连续性：宽度序列从种子档（≈--ask-fab-size）增到面板档，序列里至少 5 个
//       不同读数（真形变，不是一步到位），首帧宽度 ≤ 种子档 + 8。
//   N4  reduced-motion：形变退化为直接成型（不同宽度读数 ≤ 3 档）。时长单源 =
//       design-system.css --lm-dur 的 1ms 降级档。
//   N5  亮/暗 × 桌面/手机 375×812 关键帧截图（起手/中途/落定）。
//   N6  ask 浮窗同判据（N1–N3 逐条复跑）。
//   N7  零面板壳：玻璃面自持画内描边与圆角，容器自身不造边框/标题栏。
//
// 数据面 = 自包含（静态服务器 + WS 夹具），零后端、零 sbt。
// 运行：node tests/canvas-liquid-morph-panels.spec.mjs
// 证据：EVIDENCE_DIR（默认 <repo>/.nebflow/evidence/liquid-morph-panels），落 PNG
//       关键帧 + frames-*.json（逐帧读数，机器可读）。

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const OUT = process.env.EVIDENCE_DIR || resolve(HERE, '../.nebflow/evidence/liquid-morph-panels');
const PORT = Number(process.env.PORT || 8231);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
function ok(name, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
}

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const b = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(b);
  } catch { if (!res.headersSent) { res.writeHead(404); res.end(); } else res.end(); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));
mkdirSync(OUT, { recursive: true });
const browser = await chromium.launch();

const FILE = { path: '/tmp/lm-fixture/doc.md', content: '# doc\n\nbody line\n', itemType: 'markdown' };

async function boot({ colorScheme = 'dark', viewport = { width: 1440, height: 900 }, reducedMotion = 'no-preference' } = {}) {
  const ctx = await browser.newContext({ viewport, colorScheme, reducedMotion });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await installTicketMock(ctx);
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      } else if (m.type === 'pop.readFile') {
        ws.send(JSON.stringify({
          type: 'fileContent', path: m.path, absPath: m.path,
          content: FILE.content, itemType: FILE.itemType,
          fileName: (m.path || '').split('/').pop(), size: FILE.content.length,
        }));
      } else if (m.type === 'askNoteGet') {
        ws.send(JSON.stringify({ type: 'askNoteState', sid: m.sid, text: '', ts: 0 }));
      } else if (m.type === 'askOpen') {
        ws.send(JSON.stringify({ type: 'askHistory', sessionId: m.sessionId, messages: [] }));
      }
    });
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('body.mobile-layer, #activity-bar', { timeout: 15000 });
  await sleep(600);
  return { ctx, page, errors };
}

async function openFile(page) {
  await page.evaluate(({ absPath, title, content, itemType }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType, title, content, absPath, pinned: true },
    }));
  }, { absPath: FILE.path, title: 'doc.md', content: FILE.content, itemType: FILE.itemType });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${FILE.path}"]`, { timeout: 10000, state: 'attached' });
  await sleep(300);
}

/** 手机档：canvas = 独立整页，入口 = 底 dock canvas 键。 */
async function mobileToCanvas(page) {
  const isMobile = await page.evaluate(() => document.body.classList.contains('mobile-layer'));
  if (!isMobile) return;
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
  await sleep(400);
}

/** 采样器：逐帧读玻璃面自身 + 容器态。 */
const startSampler = (page, sel, ms = 900) => page.evaluate(({ sel, ms }) => {
  const t0 = performance.now();
  const out = { frames: [], t0, rest: null };
  const read = () => {
    const s = document.querySelector(sel);
    if (!s) return null;
    const cs = getComputedStyle(s);
    const r = s.getBoundingClientRect();
    const dock = s.closest('.ask-dock, .note-dock');
    const dcs = dock ? getComputedStyle(dock) : null;
    return {
      t: Math.round(performance.now() - t0),
      bf: cs.backdropFilter || cs.webkitBackdropFilter || '',
      op: +cs.opacity,
      w: +r.width.toFixed(1), h: +r.height.toFixed(1),
      rx: parseFloat(cs.borderTopLeftRadius) || 0,
      dockOp: dcs ? +dcs.opacity : 1,
      dockTf: dcs ? dcs.transform : 'none',
    };
  };
  out.rest = read();
  const loop = () => {
    const f = read();
    if (f) out.frames.push(f);
    if (performance.now() - t0 < ms) requestAnimationFrame(loop);
    else window.__M = out;
  };
  requestAnimationFrame(loop);
  window.__M = out;
}, { sel, ms });

const readSampler = (page) => page.evaluate(() => window.__M);

function frameReport(frames) {
  const widths = frames.map((f) => f.w);
  const glassless = frames.filter((f) => !f.bf || f.bf === 'none');
  const opacityMid = frames.filter((f) => f.op < 1 || f.dockOp < 1);
  const tfMid = frames.filter((f) => f.dockTf !== 'none');
  const uniq = [...new Set(widths.map((w) => Math.round(w)))].sort((a, b) => a - b);
  const mono = widths.every((w, i) => i === 0 || w >= widths[i - 1] - 0.5);
  return { widths, glassless, opacityMid, tfMid, uniq, mono, first: frames[0], last: frames[frames.length - 1] };
}

/** 一轮：隐藏态材质 → 逐帧展开 → 关键帧截图 → 落定几何。 */
async function runPanel({ label, scheme, viewport, reducedMotion = 'no-preference', surfSel, fabSel }) {
  const { ctx, page, errors } = await boot({ colorScheme: scheme, viewport, reducedMotion });
  await openFile(page);
  await mobileToCanvas(page);
  const tag = `${label}-${scheme}-${viewport.width}x${viewport.height}${reducedMotion === 'reduce' ? '-reduce' : ''}`;

  const rest = await page.evaluate((sel) => {
    const s = document.querySelector(sel);
    if (!s) return null;
    const cs = getComputedStyle(s);
    const dock = s.closest('.ask-dock, .note-dock');
    const dcs = dock ? getComputedStyle(dock) : null;
    const r = s.getBoundingClientRect();
    return {
      bf: cs.backdropFilter || cs.webkitBackdropFilter || '',
      op: +cs.opacity, w: +r.width.toFixed(1), h: +r.height.toFixed(1),
      dockOp: dcs ? +dcs.opacity : 1, dockTf: dcs ? dcs.transform : 'none',
      closed: dock ? dock.classList.contains('closed') : false,
      fab: (() => {
        const f = document.querySelector('.ask-fab');
        return f ? +f.getBoundingClientRect().width.toFixed(1) : null;
      })(),
    };
  }, surfSel);
  ok(`N1[${tag}] 隐藏态材质就位：玻璃面 backdrop-filter 非 none + 容器零 opacity/transform + 种子 ≈ fab 档`,
    !!rest && !!rest.bf && rest.bf !== 'none' && rest.closed && rest.dockOp === 1 && rest.dockTf === 'none'
    && rest.w > 0 && rest.fab !== null && Math.abs(rest.w - rest.fab) <= 2,
    rest ? `bf=${rest.bf} dockOp=${rest.dockOp} tf=${rest.dockTf} seed=${rest.w}x${rest.h} fab=${rest.fab}` : 'no surface');

  await startSampler(page, surfSel, reducedMotion === 'reduce' ? 500 : 900);
  await page.locator(fabSel).click();
  await sleep(60);
  await page.screenshot({ path: join(OUT, `${tag}-1-start.png`) });
  await sleep(110);
  await page.screenshot({ path: join(OUT, `${tag}-2-mid.png`) });
  await sleep(reducedMotion === 'reduce' ? 350 : 700);
  await page.screenshot({ path: join(OUT, `${tag}-3-done.png`) });
  const m = await readSampler(page);
  const frames = (m && m.frames) || [];
  const rep = frameReport(frames);
  writeFileSync(join(OUT, `frames-${tag}.json`), JSON.stringify({ rest, frames }, null, 1));

  ok(`N2a[${tag}] 逐帧零「无玻璃帧」`,
    frames.length > 4 && rep.glassless.length === 0,
    `frames=${frames.length} glassless=${rep.glassless.length}`);
  ok(`N2b[${tag}] 逐帧零透明度中间态（surface/容器 opacity 恒 1）`,
    rep.opacityMid.length === 0, `mid=${rep.opacityMid.length}`);
  ok(`N2c[${tag}] 逐帧容器零 transform（backdrop root 不形成）`,
    rep.tfMid.length === 0, `tfMid=${rep.tfMid.length}`);
  ok(`N2d[${tag}] 起手帧仍是种子几何（不是一步跳满）`,
    !!rep.first && rep.first.w <= (rest ? rest.fab + 8 : 48) + 1,
    rep.first ? `firstW=${rep.first.w} firstH=${rep.first.h}` : 'no frame');
  if (reducedMotion === 'reduce') {
    ok(`N4[${tag}] reduced-motion 退化为直接成型（宽度读数 ≤ 3 档）`,
      rep.uniq.length <= 3, `uniq=${rep.uniq.join('/')}`);
  } else {
    ok(`N3a[${tag}] 形变连续：宽度单调增 + ≥5 个不同读数`,
      rep.mono && rep.uniq.length >= 5, `uniq=${rep.uniq.join('/')} mono=${rep.mono}`);
    ok(`N3b[${tag}] 落定 = 面板几何（宽 > 种子 2 倍）`,
      !!rep.last && rest && rep.last.w > rest.w * 2, rep.last ? `lastW=${rep.last.w}` : 'no frame');
  }
  const borderTop = await page.evaluate((sel) => {
    const s = document.querySelector(sel);
    const cs = getComputedStyle(s);
    return { bt: cs.borderTopWidth, r: cs.borderTopLeftRadius };
  }, surfSel);
  ok(`N7[${tag}] 面板零边缘壳（玻璃面自持画内描边 / 圆角非 0）`,
    borderTop.bt !== '0px' && parseFloat(borderTop.r) > 0, `borderTop=${borderTop.bt} radius=${borderTop.r}`);
  ok(`N0[${tag}] 零 pageerror`, errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
  return rep;
}

await runPanel({ label: 'note', scheme: 'dark', viewport: { width: 1440, height: 900 },
  surfSel: '.note-dock .note-body', fabSel: '.note-fab' });
await runPanel({ label: 'note', scheme: 'light', viewport: { width: 1440, height: 900 },
  surfSel: '.note-dock .note-body', fabSel: '.note-fab' });
await runPanel({ label: 'note', scheme: 'light', viewport: { width: 375, height: 812 },
  surfSel: '.note-dock .note-body', fabSel: '.note-fab' });
await runPanel({ label: 'note', scheme: 'dark', viewport: { width: 375, height: 812 },
  surfSel: '.note-dock .note-body', fabSel: '.note-fab' });
await runPanel({ label: 'note', scheme: 'dark', viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce',
  surfSel: '.note-dock .note-body', fabSel: '.note-fab' });
await runPanel({ label: 'ask', scheme: 'dark', viewport: { width: 1440, height: 900 },
  surfSel: '.ask-dock .ask-input', fabSel: '.ask-fab:not(.note-fab)' });
await runPanel({ label: 'ask', scheme: 'light', viewport: { width: 375, height: 812 },
  surfSel: '.ask-dock .ask-input', fabSel: '.ask-fab:not(.note-fab)' });

await browser.close();
server.close();
console.log(`\ncanvas-liquid-morph-panels: ${pass} PASS / ${fail} FAIL`);
console.log(`EVIDENCE → ${OUT}`);
process.exit(fail ? 1 : 0);
