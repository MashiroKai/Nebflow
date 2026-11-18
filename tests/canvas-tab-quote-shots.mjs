#!/usr/bin/env node
// canvas-tab-quote-shots.mjs — 手机看板页「展开档 + 引用钮」新旧对照特写装置
// （canvas-tab-reference 批，2026-11-18；作者令「引用只需要一个小图标就行了 不需要文字」）。
//
// 为什么单开一个装置：验收要的是**同一机位的对照帧**（改前树 vs 改后树），而录像脚本体
// 面向的是一段交互的连续实录。本装置给四张定帧：展开档整条（暗/亮）+ 引用钮特写（暗/亮,
// deviceScaleFactor=3），并对同一机位打印读数（钮盒、可见文字、图标），供逐值对账。
//
// 用法：
//   node tests/canvas-tab-quote-shots.mjs --web=<dir> --out=<dir> --tag=before|after [--port=N]
// 产物：<out>/<tag>-expanded-<theme>.png、<out>/<tag>-quote-closeup-<theme>.png、
//       <out>/<tag>-readout.json

import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadPlaywright } from '../scripts/lib/visual-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const argOf = (k, d) => {
  const a = process.argv.find((x) => x.startsWith(`--${k}=`));
  return a ? a.slice(k.length + 3) : d;
};
const WEB_DIR = resolve(argOf('web', process.env.NEBFLOW_WEB_ROOT || join(ROOT, 'src/main/resources/web')));
const PORT = Number(argOf('port', 18482));
const BASE = `http://127.0.0.1:${PORT}`;
const OUT = resolve(argOf('out', '/Users/kaiyu/stand-evidence/canvas-tab-reference-20261118/compare'));
const TAG = argOf('tag', 'after');
mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const FIX = '/tmp/nf-ctab-ref-gate';
mkdirSync(FIX, { recursive: true });
const NAMES = ['统一面板迁移总览与验收看板含截稿清单', '知识图谱多库合并与索引重建看板', '会话身份头同行批回归清单看板'];
const DOCS = NAMES.map((n) => join(FIX, `${n}.md`));
DOCS.forEach((d, i) => writeFileSync(d, `# ${NAMES[i]}\n\n第 ${i + 1} 篇正文段落。\n`));
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf', '.ico': 'image/x-icon' };
if (!existsSync(join(WEB_DIR, 'index.html'))) { console.error(`--web 无 index.html: ${WEB_DIR}`); process.exit(2); }
const server = createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, BASE).pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  let buf; try { buf = readFileSync(file); } catch { res.writeHead(404); res.end('nf'); return; }
  res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
  res.end(buf);
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const FILES = new Map(DOCS.map((d) => [d, 'markdown']));
const pw = loadPlaywright().mod;
const { chromium } = pw.playwright || pw;
const browser = await chromium.launch();

/** 同一机位：375×812 手机档，指定主题，展开活签后取两帧（档 + 钮特写）。 */
async function shot(theme) {
  const ctx = await browser.newContext({
    viewport: { width: 375, height: 812 }, colorScheme: theme, locale: 'zh-CN', hasTouch: true,
    deviceScaleFactor: 3,
  });
  await ctx.addInitScript(() => {
    for (const k of ['nebflow', 'neblink']) {
      localStorage.setItem(k + '_token', 't'); localStorage.setItem(k + '_locale', 'zh-CN');
      localStorage.setItem(k + '_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    }
    localStorage.setItem('stand_sw', '0');
  });
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, contentType: 'application/json', body: '{}' }));
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 's1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 's1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'pop.readFile') {
        const t = FILES.get(m.path);
        ws.send(JSON.stringify({ type: 'fileContent', path: m.path, absPath: m.path, content: t ? readFileSync(m.path, 'utf8') : '', itemType: t || 'markdown', fileName: (m.path || '').split('/').pop(), size: 0 }));
      }
    });
  });
  const page = await ctx.newPage();
  await page.goto(`${BASE}/index.html`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('body.mobile-layer', { timeout: 20000 });
  await sleep(900);
  const openDoc = async (p, pinned) => {
    await page.evaluate(({ path, pin }) => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: { id: 'file:' + path, itemType: 'markdown', title: path.split('/').pop().replace(/\.md$/, ''), absPath: path, pinned: pin },
      }));
    }, { path: p, pin: !!pinned });
    await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${p}"] .canvas-md-scroll`, { timeout: 15000, state: 'attached' });
    await sleep(400);
  };
  for (const d of [DOCS[0], DOCS[1]]) await openDoc(d, true);
  await openDoc(DOCS[2], false);
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click({ timeout: 8000 }).catch(() => {});
  await sleep(1200);
  await page.tap('.mf-strip-chip.on').catch(() => {});
  await sleep(1000);

  // 读数（同一机位逐值对账用）：钮盒 / 可见文字 / 图标 / 名字元素与签的可见文字。
  const readout = await page.evaluate(() => {
    const R = (n) => Math.round(n * 100) / 100;
    const chip = document.querySelector('.mf-strip-chip.is-expanded');
    const q = chip && chip.querySelector('.mf-strip-quote');
    const strip = document.querySelector('.mf-strip') || chip;
    const cr = chip ? chip.getBoundingClientRect() : null;
    const qr = q ? q.getBoundingClientRect() : null;
    const sr = strip ? strip.getBoundingClientRect() : null;
    const visNodes = [];
    const walk = (n) => {
      for (const c of n.childNodes) {
        if (c.nodeType === 3) { if ((c.nodeValue || '').trim()) visNodes.push(c.nodeValue); }
        else if (c.nodeType === 1 && !c.matches('.mf-strip-quote')) walk(c);
      }
    };
    if (chip) walk(chip);
    const svg = q ? q.querySelector('svg') : null;
    return {
      found: !!q,
      chipBox: cr ? { w: R(cr.width), h: R(cr.height), l: R(cr.left), t: R(cr.top), r: R(cr.right), b: R(cr.bottom) } : null,
      chipVisibleText: visNodes.join(''),
      nameEl: chip ? (chip.querySelector('.mf-strip-label') || {}).textContent : null,
      quoteBox: qr ? { w: R(qr.width), h: R(qr.height), l: R(qr.left), t: R(qr.top), r: R(qr.right), b: R(qr.bottom) } : null,
      quoteText: q ? q.textContent : null,
      quoteAria: q ? q.getAttribute('aria-label') : null,
      quoteIcon: svg ? svg.getAttribute('data-icon') : null,
      stripBox: sr ? { l: R(sr.left), t: R(sr.top), r: R(sr.right), b: R(sr.bottom) } : null,
      theme: document.documentElement.dataset.theme || getComputedStyle(document.body).backgroundColor,
    };
  });

  // ① 展开档整条（含上下留白，看到签在环带里的位置）
  const sb = readout.stripBox;
  if (sb) {
    await page.screenshot({
      path: join(OUT, `${TAG}-expanded-${theme}.png`),
      clip: { x: 0, y: Math.max(0, sb.t - 10), width: 375, height: Math.min(812 - Math.max(0, sb.t - 10), Math.max(48, sb.b - sb.t + 20)) },
    });
  }
  // ② 钮特写（3× 设备像素）
  await page.screenshot({ path: join(OUT, `${TAG}-full-${theme}.png`) });
  if (readout.quoteBox) {
    const qb = readout.quoteBox;
    await page.screenshot({
      path: join(OUT, `${TAG}-quote-closeup-${theme}.png`),
      clip: { x: Math.max(0, qb.l - 10), y: Math.max(0, qb.t - 10), width: qb.w + 20, height: qb.h + 20 },
    });
  }
  await ctx.close();
  return { theme, ...readout };
}

const out = [];
for (const theme of ['dark', 'light']) out.push(await shot(theme));
await browser.close();
server.close();
writeFileSync(join(OUT, `${TAG}-readout.json`), JSON.stringify(out, null, 2));
console.log(`webRoot=${WEB_DIR}\ntag=${TAG}\n` + JSON.stringify(out, null, 2));
