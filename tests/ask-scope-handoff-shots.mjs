#!/usr/bin/env node
// ask-scope-handoff-shots.mjs — ask 写面收窄批（2026-11-18）· Ask 面板「已转交主会话」
// 状态行的形态证据（GIF + 逐帧 PNG）。
//
// 作者验收条目 3/7：「转交必须机器可读 + UI 可见（Ask 面板要有明确状态，如「已转交
// 主会话」+ 转交内容摘要），不许静默」「UI 形态若属交互/动效改动必须有 GIF（≤10MB，
// 超出换 MP4；ffprobe 实测标真实像素/帧率/时长）；手机形态用真全屏页（375×812 量级），
// 禁用弹窗/内嵌面板顶替；暗亮两档」。
//
// 形态：沿用 canvas-ask-dock.spec.mjs 的自足夹具（静态服务真实 web/ 源码树 + 
// routeWebSocket mock 服务端），**不改产品代码、不连后端**。帧序列由真实页面驱动：
//   ① 开 dock（.ask-fab）→ ② 提问（用户气泡）→ ③ 回答流式落泡 → ④ 引擎推
//   askHandoffAck（工具腿/ dock 腿同一帧）⇒ 面板状态行「已转交主会话 + 摘要」出现。
//
// Usage: node tests/ask-scope-handoff-shots.mjs [--out <dir>]
// 产物：<out>/desktop-dark.gif / desktop-light.gif / mobile-dark.gif + frames/<form>/*.png
import { chromium } from 'playwright';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readdirSync, rmSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const OUT = (() => {
  const i = process.argv.indexOf('--out');
  return i >= 0 ? resolve(process.argv[i + 1]) : resolve(HERE, '../evidence/ask-scope-20261118/shots');
})();
const FRAMES = join(OUT, 'frames');
mkdirSync(FRAMES, { recursive: true });

const PORT = Number(process.env.PORT || 8231);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const { readFileSync } = await import('node:fs');
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const sidOf = (absPath) => 'ask-' + createHash('sha256').update(absPath, 'utf8').digest('hex').slice(0, 8);

/** 一次转交的结构化载荷（与 AskHandoffWire.json 逐字段同名）。 */
const HANDOFF = {
  task: '把 notes.md 与 other.md 合并成一份 README，并跑一次构建',
  paths: ['/tmp/canvas-ask-fixture/notes.md', '/tmp/canvas-ask-fixture/other.md'],
  reason: '跨文件 + 需要跑构建命令，本会话只有只读 shell',
  expected: '合并后的 README + 构建结果',
};

async function boot(browser, { viewport, colorScheme, mobile }) {
  const ctx = await browser.newContext({ viewport, colorScheme, hasTouch: !!mobile });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route(/\/api\/nf-file\?/, (r) => r.fulfill({ status: 200, contentType: 'text/markdown', body: '# notes\n\nmock body\n' }));
  let ws = null;
  await page.routeWebSocket(/\/ws/, (sock) => {
    ws = sock;
    sock.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') sock.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'askOpen') {
        const absPath = (m.file && m.file.absPath) || '';
        sock.send(JSON.stringify({
          type: 'askState', sessionId: sidOf(absPath), sessionKey: 'ask:' + sidOf(absPath).slice(4, 8),
          file: absPath, fileName: (m.file && m.file.name) || 'notes.md', messages: [],
        }));
      }
    });
    sock.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    sock.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  if (mobile) await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), { timeout: 20000 });
  else await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await sleep(700);
  return { page, ws: () => ws, errors };
}

async function openDock(page, name, { mustBeVisible = true } = {}) {
  const absPath = `/tmp/canvas-ask-fixture/${name}`;
  await page.evaluate(({ name, absPath }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title: name, content: `# ${name}\n\nmock body\n`, absPath, pinned: true },
    }));
  }, { name, absPath });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { state: mustBeVisible ? 'visible' : 'attached', timeout: 15000 })
    .catch(async (error) => {
      const diag = await page.evaluate(() => {
        const panel = document.getElementById('canvas-panel');
        const pane = document.querySelector('.canvas-tab-pane');
        const cs = pane ? getComputedStyle(pane) : null;
        return {
          body: document.body.className,
          panel: panel ? `${getComputedStyle(panel).display} ${JSON.stringify(panel.getBoundingClientRect())}` : 'none',
          pane: pane ? `${cs.display}/${cs.visibility} ${JSON.stringify(pane.getBoundingClientRect())}` : 'none',
          tabs: document.querySelectorAll('.canvas-tab-pane').length,
        };
      });
      console.error('openFile diagnostics:', JSON.stringify(diag));
      throw error;
    });
  await sleep(350);
  if (!mustBeVisible) {
    // 移动卡层：pane 在卡层展开前 hidden ⇒ 先建卡、再经底 dock 键进 canvas 页
    // （canvas-ask-dock.spec.mjs T9 同款自举）。此后 .ask-fab 才可见可点。
    await page.click('#mobile-dock .dock-key[data-dock="canvas"]');
    await sleep(900);
    await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { state: 'visible', timeout: 8000 }).catch(() => {});
    await sleep(400);
  }
  await page.click('.ask-fab');
  await page.waitForFunction(() => {
    const d = document.querySelector('.ask-dock');
    return d && !d.classList.contains('closed');
  }, { timeout: 10000 });
  await sleep(450);
  return sidOf(absPath);
}

async function shootForms(browser) {
  const forms = [
    { key: 'desktop-dark', viewport: { width: 1600, height: 1000 }, colorScheme: 'dark', mobile: false },
    { key: 'desktop-light', viewport: { width: 1600, height: 1000 }, colorScheme: 'light', mobile: false },
    { key: 'mobile-dark', viewport: { width: 375, height: 812 }, colorScheme: 'dark', mobile: true },
  ];
  const produced = [];
  for (const form of forms) {
    const dir = join(FRAMES, form.key);
    if (existsSync(dir)) rmSync(dir, { recursive: true, force: true });
    mkdirSync(dir, { recursive: true });
    const { page, ws, errors } = await boot(browser, form);
    const sid = await openDock(page, 'notes.md', { mustBeVisible: !form.mobile });
    let n = 0;
    const shot = async () => {
      await page.screenshot({ path: join(dir, `f${String(n).padStart(3, '0')}.png`) });
      n += 1;
    };
    // ① dock 已开（状态行未出现）
    await shot();
    await shot();
    // ② 用户提一个跨文件复杂任务
    await page.fill('.ask-dock textarea', '帮我把这份文件和 another.md 合并，再跑一遍构建');
    await sleep(220);
    await shot();
    await page.click('.ask-dock .ask-send');
    await sleep(240);
    await shot();
    await shot();
    // ③ 回答流式落泡
    ws().send(JSON.stringify({ type: 'textDelta', sessionId: sid, delta: '这件事超出本窗能力：' }));
    await sleep(180); await shot();
    ws().send(JSON.stringify({ type: 'textDelta', sessionId: sid, delta: '它要跨文件还要跑构建，本会话只有这一份文档的编辑权。' }));
    await sleep(180); await shot();
    // ④ 引擎推 askHandoffAck（工具腿/dock 腿同一帧）⇒ 面板状态行出现
    ws().send(JSON.stringify({
      type: 'askHandoffAck', sessionId: sid, ok: true,
      summary: `${HANDOFF.task} · ${HANDOFF.paths.length} 个路径 · 期望：${HANDOFF.expected}`,
      question: HANDOFF.task,
      handoff: { askSessionId: sid, file: '/tmp/canvas-ask-fixture/notes.md', fileName: 'notes.md', leg: 'ask-tool', ...HANDOFF },
    }));
    await sleep(160); await shot();
    ws().send(JSON.stringify({ type: 'textDelta', sessionId: sid, delta: '已把它整件转交主会话（它可回问），我继续留在这份文件上。' }));
    await sleep(200); await shot();
    ws().send(JSON.stringify({ type: 'done', sessionId: sid }));
    // 停留帧（GIF 的可读性：摘要在屏上停住，不靠快放揣摩）
    for (let i = 0; i < 16; i++) { await sleep(300); await shot(); }
    // 断言：状态行可见且带摘要（形态证据的机器判据面）
    const reading = await page.evaluate(() => {
      const el = document.querySelector('[data-ask-handoff]');
      if (!el) return { present: false };
      const r = el.getBoundingClientRect();
      return {
        present: true,
        hidden: el.hidden,
        text: (el.textContent || '').trim(),
        tag: (el.querySelector('.ask-handoff-tag') || {}).textContent || '',
        w: Math.round(r.width), h: Math.round(r.height),
      };
    });
    const pass = reading.present && !reading.hidden && reading.tag.includes('已转交主会话')
      && reading.text.includes('README') && reading.h > 0;
    console.log(`${pass ? 'PASS' : 'FAIL'}  ${form.key}  statusRow=${JSON.stringify(reading)}  pageerrors=${errors.length}`);
    if (errors.length) console.log('   pageerror:', errors.slice(0, 3).join(' | '));
    await page.context().close();
    produced.push({ form: form.key, dir, frames: n, pass });
  }
  return produced;
}

function gif(form, dir, out, scale) {
  const gifPath = join(OUT, `${form}.gif`);
  execFileSync('ffmpeg', [
    '-y', '-loglevel', 'error', '-framerate', '8', '-i', join(dir, 'f%03d.png'),
    '-vf', `fps=8,scale=${scale}:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse`,
    gifPath,
  ]);
  const probe = execFileSync('ffprobe', [
    '-v', 'error', '-select_streams', 'v:0',
    '-show_entries', 'stream=width,height,avg_frame_rate,nb_frames,duration',
    '-show_entries', 'format=duration,size', '-of', 'json', gifPath,
  ]).toString();
  const info = JSON.parse(probe);
  const st = info.streams[0];
  const bytes = statSync(gifPath).size;
  const line = `${form}.gif ${st.width}x${st.height} fps=${st.avg_frame_rate} frames=${st.nb_frames} ` +
    `duration=${info.format.duration}s bytes=${bytes} (${(bytes / 1024 / 1024).toFixed(2)} MiB)`;
  console.log(`${bytes <= 10 * 1024 * 1024 ? 'PASS' : 'FAIL'}  gif-size  ${line}`);
  return { gifPath, line, bytes };
}

const browser = await chromium.launch();
let exitCode = 0;
try {
  const produced = await shootForms(browser);
  const gifs = [];
  for (const p of produced) {
    const scale = p.form.startsWith('mobile') ? 375 : 1100;
    gifs.push(gif(p.form, p.dir, p.form, scale));
    if (!p.pass) exitCode = 1;
  }
  console.log(`\nframes dir: ${FRAMES}`);
  console.log(`gifs: ${gifs.map((g) => g.gifPath).join(', ')}`);
} finally {
  await browser.close();
  server.close();
}
process.exit(exitCode);
