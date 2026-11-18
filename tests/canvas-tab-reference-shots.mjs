#!/usr/bin/env node
// canvas-tab-reference-shots.mjs — 手机端「点标签页顶部 → 展开 → 点引用 → 输入框出现引用
// → 提交 → 会话真收到」连续实录（视频 → GIF/MP4），以及桌面端前后对照帧。
//
// 为什么用录像而不是连拍：本批的验收对象是**一段交互**（展开 → 点引用 → 落框 → 提交），
// 单帧证明不了「手指点得到」这条改前病根。装置与门禁同源（本地静态服务 + 全本地 WS mock，
// 零宿主接触），录像由 Playwright context 录制（375×812 CSS px）。
//
// 用法：
//   node tests/canvas-tab-reference-shots.mjs [--web=<dir>] [--out=<dir>] [--port=N]
// 产物：<out>/canvas-tab-reference-*.webm（原始）+ 表 tt 里由 ffmpeg 转出的 gif/mp4。

import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync, renameSync } from 'node:fs';
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
const PORT = Number(argOf('port', 18481));
const BASE = `http://127.0.0.1:${PORT}`;
const OUT = resolve(argOf('out', '/Users/kaiyu/stand-evidence/canvas-tab-reference-20261118/gif'));
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

/** 录一段手机端 375×812 的连续实录。@param {string} label 产物名 @param {string} webDir */
async function record(label, webDir) {
  const vdir = join(OUT, `_video-${label}`);
  mkdirSync(vdir, { recursive: true });
  const ctx = await browser.newContext({
    viewport: { width: 375, height: 812 }, colorScheme: 'dark', locale: 'zh-CN', hasTouch: true,
    recordVideo: { dir: vdir, size: { width: 375, height: 812 } },
  });
  await ctx.addInitScript(() => {
    for (const k of ['nebflow', 'neblink']) {
      localStorage.setItem(k + '_token', 't'); localStorage.setItem(k + '_locale', 'zh-CN');
      localStorage.setItem(k + '_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    }
    localStorage.setItem('stand_sw', '0');
  });
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, contentType: 'application/json', body: '{}' }));
  const sent = [];
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 's1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 's1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      sent.push(m);
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
  // 本体入口 = 底 dock「看板」键（作者真实路径）
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click({ timeout: 8000 }).catch(() => {});
  await sleep(1400);
  await page.screenshot({ path: join(OUT, `${label}-1-canvas.png`) });
  // ① 点标签页顶部（活签）= 展开
  await page.tap('.mf-strip-chip.on').catch(() => {});
  await sleep(1100);
  await page.screenshot({ path: join(OUT, `${label}-2-expanded.png`) });
  // ② 点引用钮
  await page.tap('.mf-strip-chip.is-expanded .mf-strip-quote', { timeout: 8000 }).catch(() => {});
  await sleep(1400);
  await page.screenshot({ path: join(OUT, `${label}-3-quoted.png`) });
  // ③ 输入框出现引用 + 打一句正文
  const ta = await page.$('#input-bar textarea');
  if (ta) {
    await ta.click().catch(() => {});
    await page.keyboard.type('这条引用请把身份读给我', { delay: 45 }).catch(() => {});
  }
  await sleep(900);
  await page.screenshot({ path: join(OUT, `${label}-4-typed.png`) });
  // ④ 提交 → 会话侧真收到（WS 帧带 refs）
  sent.length = 0;
  await page.click('#send-btn', { timeout: 8000 }).catch(() => {});
  await sleep(1800);
  await page.screenshot({ path: join(OUT, `${label}-5-sent.png`) });
  const withRefs = sent.filter((f) => Array.isArray(f.refs) && f.refs.length > 0);
  writeFileSync(join(OUT, `${label}-sent-frames.json`), JSON.stringify(sent, null, 2));
  console.log(`[${label}] frames=${sent.length} withRefs=${withRefs.length}`);
  if (withRefs[0]) console.log(`[${label}] refs[0]=` + JSON.stringify(withRefs[0].refs[0]));
  await page.close();
  await ctx.close();     // 收视频
  const vids = readdirSync(vdir).filter((f) => f.endsWith('.webm'));
  if (vids.length) {
    const dst = join(OUT, `${label}.webm`);
    renameSync(join(vdir, vids[0]), dst);
    console.log(`[${label}] video=${dst}`);
  }
  return { label, frames: sent.length, withRefs: withRefs.length, ref: withRefs[0] ? withRefs[0].refs[0] : null };
}

/** 桌面 1440×900 对照帧（本批对桌面零改动；给「桌面不回归」一张同帧面）。 */
async function desktopShot(label) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'dark', locale: 'zh-CN' });
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
  await page.waitForSelector('#activity-bar', { timeout: 20000 });
  await sleep(900);
  for (const d of [DOCS[0], DOCS[1]]) {
    await page.evaluate((path) => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: { id: 'file:' + path, itemType: 'markdown', title: path.split('/').pop().replace(/\.md$/, ''), absPath: path, pinned: true },
      }));
    }, d);
    await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${d}"] .canvas-md-scroll`, { timeout: 15000, state: 'attached' });
    await sleep(400);
  }
  await sleep(600);
  await page.screenshot({ path: join(OUT, `${label}-desktop.png`) });
  await ctx.close();
}

const res = process.argv.includes('--desktop-only')
  ? null
  : await record('canvas-tab-reference-mobile', WEB_DIR);
await desktopShot(argOf('tag', 'canvas-tab-reference'));
await browser.close();
server.close();
if (res) writeFileSync(join(OUT, 'shots-summary.json'), JSON.stringify(res, null, 2));
console.log(res ? JSON.stringify(res, null, 2) : '(desktop-only)');
