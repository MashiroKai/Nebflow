#!/usr/bin/env node
// ask-status-visibility-shots.mjs — Canvas Ask 状态可见性与失败上报**实录**收档
// （作者令 2026-11-18：「Canvas Ask 对话框的状态显示不足 我不知道它在干嘛 是否
//   正常 LLM 失败也没有报错等 需要补充更多的信息」）。
//
// 产出（全部落 $ASK_STATUS_EVIDENCE_DIR，缺省
//       /Users/kaiyu/stand-evidence/ask-status-visibility-20261118）：
//   ① 正常流程各状态的连续呈现 —— 手机 375×812 与桌面 1280×800 各一段
//      （Playwright recordVideo 真实录屏 → ffmpeg 转 GIF + MP4）；
//   ② 失败注入四例（断连 / 超时 / 非 2xx / 解析失败）—— 手机端各一段实录
//      （每例都是**真的把请求打失败**：断 socket、真等满静默看门、真发错误帧）；
//   ③ 每段的 still（PNG）+ 每步的状态行读数 + ffprobe 实测像素/帧率/时长
//      （readings.json）。
//
// Run:
//   SHOTS_WEB=<web 根> ASK_STATUS_EVIDENCE_DIR=<目录> PHASE=all|mobile|desktop \
//     node tests/ask-status-visibility-shots.mjs
// 缺省 SHOTS_WEB = 本仓 src/main/resources/web。
//
// 说明：本脚本只**观测**，不改任何产物；ffmpeg/ffprobe 来自系统 PATH。
import { chromium } from 'playwright';
import { createHash } from 'node:crypto';
import { readFileSync, mkdirSync, writeFileSync, renameSync, existsSync, statSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { execFileSync } from 'node:child_process';
import { dirname, join, normalize, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.SHOTS_WEB || resolve(HERE, '../src/main/resources/web');
const OUT = process.env.ASK_STATUS_EVIDENCE_DIR
  || '/Users/kaiyu/stand-evidence/ask-status-visibility-20261118';
const PHASE = process.env.PHASE || 'all';
const RAW = join(OUT, 'raw');
const VIDEO = join(OUT, 'video');
const SHOTS = join(OUT, 'shots');
for (const d of [OUT, RAW, VIDEO, SHOTS]) mkdirSync(d, { recursive: true });

const PORT = Number(process.env.PORT || 8321);
const BASE = `http://127.0.0.1:${PORT}`;
const FILE = '记忆检索调优笔记.md';
const ABS = '/tmp/canvas-ask-status-fixture/' + FILE;
const sidOf = (p) => 'ask-' + createHash('sha256').update(p, 'utf8').digest('hex').slice(0, 8);
const SID = sidOf(ABS);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = normalize(join(WEB, p));
  if (!file.startsWith(normalize(WEB))) { res.writeHead(403); res.end(); return; }
  try {
    const b = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(b);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const readings = { web: WEB, out: OUT, viewport: {}, scenarios: [], ffmpeg: {}, errors: [] };
const say = (s) => console.log(s);

/* ── ffmpeg / ffprobe ────────────────────────────────────────────────── */
const FFMPEG = process.env.FFMPEG || 'ffmpeg';
const FFPROBE = process.env.FFPROBE || 'ffprobe';
function probe(file) {
  try {
    const out = execFileSync(FFPROBE, [
      '-v', 'error', '-select_streams', 'v:0',
      '-show_entries', 'stream=width,height,r_frame_rate,avg_frame_rate,nb_read_frames,duration',
      '-show_entries', 'format=duration,size,format_name',
      '-count_frames', '-of', 'json', file,
    ], { encoding: 'utf8' });
    const j = JSON.parse(out);
    const st = (j.streams && j.streams[0]) || {};
    const fmt = j.format || {};
    const [num, den] = String(st.avg_frame_rate || st.r_frame_rate || '0/1').split('/').map(Number);
    return {
      file, width: st.width, height: st.height,
      fps: den ? +(num / den).toFixed(3) : null,
      frames: st.nb_read_frames ? Number(st.nb_read_frames) : null,
      durationSec: fmt.duration ? +Number(fmt.duration).toFixed(3) : null,
      sizeBytes: fmt.size ? Number(fmt.size) : statSync(file).size,
      format: fmt.format_name,
    };
  } catch (e) { return { file, error: String(e && e.message ? e.message : e) }; }
}
function toMp4(src, dst, { fps = 30, width, height } = {}) {
  const scale = width ? ['-vf', `fps=${fps},scale=${width}:${height}:flags=lanczos`] : ['-vf', `fps=${fps}`];
  execFileSync(FFMPEG, ['-y', '-loglevel', 'error', '-i', src, ...scale,
    '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '20', '-movflags', '+faststart', dst]);
  return dst;
}
function toGif(src, dst, { fps = 12, width, height } = {}) {
  const pal = join(RAW, 'palette.png');
  const vf = `fps=${fps},scale=${width}:${height}:flags=lanczos`;
  execFileSync(FFMPEG, ['-y', '-loglevel', 'error', '-i', src, '-vf', `${vf},palettegen`, pal]);
  execFileSync(FFMPEG, ['-y', '-loglevel', 'error', '-i', src, '-i', pal,
    '-lavfi', `${vf}[x];[x][1:v]paletteuse`, '-loop', '0', dst]);
  rmSync(pal, { force: true });
  return dst;
}

/* ── WS mock（可控句柄；帧留档）───────────────────────────────────────── */
let wsHandle = null;
const inbound = [];
const wsSend = (obj) => { if (wsHandle) wsHandle.send(JSON.stringify(obj)); };

const browser = await chromium.launch();

/**
 * 起一个录取上下文：固定视口 + 真实录屏（recordVideo 的 size 精确钉住像素）。
 * @returns {Promise<{ctx:any, page:any, errors:string[], video:any, save:()=>Promise<string>}>}
 */
async function record({ width, height, mobile, label }) {
  const ctx = await browser.newContext({
    viewport: { width, height }, colorScheme: 'dark', hasTouch: !!mobile,
    deviceScaleFactor: 2, locale: 'zh-CN',
    recordVideo: { dir: RAW, size: { width, height } },
  });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('neblink_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => new Promise(() => {}), configurable: true });
  });
  await ctx.route('**/api/**', (route) => {
    const p = decodeURIComponent(new URL(route.request().url()).pathname);
    if (p === '/api/sessions') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: 'sess-1', name: 'root', agentName: 'Nebula' }], activeId: 'sess-1' }) });
    }
    if (p === '/api/nf-ticket') {
      let paths = [];
      try { paths = JSON.parse(route.request().postData() || '{}').paths || []; } catch { paths = []; }
      const tickets = {};
      for (const q of (Array.isArray(paths) ? paths : [])) tickets[q] = { t: 'tk', exp: Date.now() + 600000, uses: -1 };
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ tickets, rejected: [] }) });
    }
    if (p === '/api/nf-file') {
      return route.fulfill({ status: 200, contentType: 'text/markdown; charset=utf-8', body: Buffer.from(`# ${FILE}\n\nfixture body\n`) });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    wsHandle = ws;
    inbound.length = 0;
    const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    const ses = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', name: 'root', agentName: 'Nebula' }], folders: [], activeId: 'sess-1' }));
    cfg(); ses();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      inbound.push(m);
      if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      else if (m.type === 'askOpen') {
        const filePath = (m.file && m.file.absPath) || ABS;
        ws.send(JSON.stringify({
          type: 'askState', sessionId: sidOf(filePath), sessionKey: 'ask:' + sidOf(filePath).slice(4, 8),
          file: filePath, fileName: FILE, messages: [],
        }));
      }
      // askQuestion：**刻意不回**——回合由场景脚本手动驱动（textDelta/done/error）。
    });
  });

  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 220)));
  await page.goto(BASE + '/');
  if (mobile) {
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
    await page.evaluate(async () => {
      const m = await import('/js/mobile/navController.js');
      m.navigateTo('canvas');
    });
    await sleep(600);
  } else {
    await page.waitForSelector('#activity-bar', { timeout: 20000 });
    await sleep(600);
  }
  await page.evaluate(({ name, absPath }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title: name, content: `# ${name}\n\nfixture body\n`, absPath, pinned: true },
    }));
  }, { name: FILE, absPath: ABS });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${ABS}"]`, { timeout: 15000 });
  await page.waitForFunction(() => {
    const f = document.querySelector('.ask-fab');
    return f && !f.classList.contains('gate-off');
  }, null, { timeout: 15000 });
  await page.click('.ask-fab');
  await page.waitForFunction(() => {
    const d = document.querySelector('.ask-dock');
    return d && !d.classList.contains('closed');
  }, null, { timeout: 15000 });
  await sleep(700);

  const video = page.video();
  return {
    ctx, page, errors, video,
    async save(name) {
      await ctx.close();
      await sleep(400);
      let src = null;
      try { src = await video.path(); } catch { src = null; }
      if (!src || !existsSync(src)) return null;
      const dst = join(RAW, `${name}.webm`);
      renameSync(src, dst);
      return dst;
    },
  };
}

/* ── 读数 ────────────────────────────────────────────────────────────── */
const stateSnap = (page) => page.evaluate(() => {
  const l = document.querySelector('[data-ask-state-line]');
  if (!l) return { missing: true };
  const q = (s) => l.querySelector(s);
  const t = q('.ask-state-text'), n = q('.ask-state-next'), d = q('.ask-state-diag');
  const r = l.getBoundingClientRect();
  return {
    hidden: !!l.hidden, cls: String(l.className || ''),
    text: t ? t.textContent : '', next: n && !n.hidden ? n.textContent : '',
    diag: d && !d.hidden ? d.textContent : '', diagHidden: !d || !!d.hidden,
    line: l.textContent.trim(),
    box: { w: +r.width.toFixed(1), h: +r.height.toFixed(1) },
    alphabet: getComputedStyle(l).color,
  };
});
async function step(page, name, list, shotName) {
  const snap = await stateSnap(page);
  const row = { step: name, ...snap };
  if (shotName) {
    const p = join(SHOTS, `${shotName}.png`);
    await page.screenshot({ path: p });
    row.shot = p;
  }
  list.push(row);
  say(`  · ${name}: hidden=${snap.hidden} text=${JSON.stringify(snap.line)}`);
  return row;
}

/* ── 场景：正常流程（各状态连续呈现）──────────────────────────────────── */
async function scenarioNormal(page, label, steps) {
  await step(page, 'idle-空闲（状态行隐藏）', steps, `${label}-01-idle`);
  await page.fill('.ask-dock textarea', 'τ 为什么取 30 天？');
  await page.waitForTimeout(700);
  await step(page, 'draft-待发送', steps, `${label}-02-draft`);

  await page.press('.ask-dock textarea', 'Enter');
  await page.waitForTimeout(1400);
  await step(page, 'waiting-等待响应（含已等耗时）', steps, `${label}-03-waiting`);

  const chunks = [
    '笔记「近因加权」节：', '按龄衰减让近期证据权重更高，', '同时保留长尾；',
    '固定 30 天会在跨季度复盘时丢失有效样本。', '因此 τ 取 30 天。',
  ];
  for (let i = 0; i < chunks.length; i++) {
    wsSend({ type: 'textDelta', sessionId: SID, delta: chunks[i] });
    await page.waitForTimeout(i === 0 ? 1300 : 900);
    if (i === 0) await step(page, 'streaming-正在生成（已产出量 + 已耗时）', steps, `${label}-04-streaming`);
  }
  await page.waitForTimeout(900);
  await step(page, 'streaming-生成中（累计进度）', steps, `${label}-05-streaming2`);

  wsSend({ type: 'done', sessionId: SID });
  await page.waitForTimeout(900);
  await step(page, 'done-已完成（耗时）', steps, `${label}-06-done`);

  await page.waitForTimeout(2600);
  await step(page, 'idle-终态驻留到期回空闲', steps, `${label}-07-back-idle`);

  // 取消 ≠ 失败：第二问 → 点停 ⇒ 「已中止」观感（无 toast、无错误样式）
  await page.fill('.ask-dock textarea', '再问一次，然后点停。');
  await page.press('.ask-dock textarea', 'Enter');
  await page.waitForTimeout(1600);
  await step(page, 'waiting-第二问排队中', steps, `${label}-08-waiting2`);
  await page.click('.ask-dock .ask-stop');
  await page.waitForTimeout(1500);
  await step(page, 'aborted-已中止（取消≠失败：无错误样式/toast）', steps, `${label}-09-aborted`);
  await page.waitForTimeout(2200);
  await step(page, 'idle-中止后回空闲', steps, `${label}-10-idle-after-abort`);
}

/* ── 场景：失败注入 ──────────────────────────────────────────────────── */
async function scenarioDisconnect(page, label, steps) {
  await page.fill('.ask-dock textarea', '断连时问一句，看它怎么说。');
  // 真断连：把前端连接态打成未连接（ws.js 的 sendWs 因此走「未连接 ⇒ 入有界队列」
  // 那条真实分支，返回 false）。
  await page.evaluate(async () => {
    const m = await import('/js/state.js');
    m.default.ws = null;
    m.default.connected = false;
  });
  await page.waitForTimeout(1200);
  await step(page, 'offline-会话未连接（前置不可用，未发起）', steps, `${label}-disconnect-01-pre`);
  await page.press('.ask-dock textarea', 'Enter');
  await page.waitForTimeout(1600);
  await step(page, 'offline-会话未连接（帧已排队，给出路）', steps, `${label}-disconnect-02-offline`);
}

async function scenarioNon2xx(page, label, steps) {
  await page.fill('.ask-dock textarea', '让模型返回一个 500 看看。');
  await page.press('.ask-dock textarea', 'Enter');
  await page.waitForTimeout(1200);
  await step(page, 'waiting-等待响应', steps, `${label}-non2xx-01-waiting`);
  wsSend({ type: 'error', sessionId: SID, status: 500, message: 'upstream provider error: HTTP 500 Internal Server Error' });
  await page.waitForTimeout(1600);
  await step(page, 'failed-模型侧报错（HTTP 500 + toast error 档）', steps, `${label}-non2xx-02-failed`);
}

async function scenarioParse(page, label, steps) {
  await page.fill('.ask-dock textarea', '让响应解析失败看看。');
  await page.press('.ask-dock textarea', 'Enter');
  await page.waitForTimeout(1200);
  wsSend({ type: 'error', sessionId: SID, message: 'unexpected token < in JSON at position 0 (schema mismatch)' });
  await page.waitForTimeout(1600);
  await step(page, 'failed-响应解析失败', steps, `${label}-parse-02-failed`);
}

async function scenarioTimeout(page, label, steps) {
  await page.fill('.ask-dock textarea', '发出去之后没有任何流帧（真等满静默看门）。');
  await page.press('.ask-dock textarea', 'Enter');
  await page.waitForTimeout(1500);
  await step(page, 'waiting-等待响应（此后零流帧）', steps, `${label}-timeout-01-waiting`);
  say('  … 真等满静默看门阈值（180s，1s 节拍），期间不发任何帧');
  for (let i = 0; i < 19; i++) {
    await sleep(10000);
    const s = await stateSnap(page);
    say(`     t≈${(i + 1) * 10}s state=${JSON.stringify(s.line)}`);
  }
  await sleep(3000);
  await step(page, 'timeout-响应超时（看门到点，改前此处零痕迹）', steps, `${label}-timeout-02-timeout`);
  await page.waitForTimeout(1500);
}

/* ── 跑一段：录屏 + 转码 + 实测 ──────────────────────────────────────── */
async function run({ label, width, height, mobile, scenario }) {
  say(`\n▶ ${label} ${width}×${height} mobile=${!!mobile}`);
  const rec = await record({ width, height, mobile, label });
  const steps = [];
  let err = null;
  try { await scenario(rec.page, label, steps); } catch (e) { err = String(e && e.message ? e.message : e); say(`  !! ${err}`); }
  await sleep(400);
  const webm = await rec.save(label);
  const row = { label, width, height, mobile: !!mobile, steps, pageErrors: rec.errors, error: err, webm };
  if (webm) {
    try { row.mp4 = toMp4(webm, join(VIDEO, `${label}.mp4`), { fps: 30 }); } catch (e) { row.mp4Error = String(e.message); }
    if (scenario.gif) {
      try {
        row.gif = toGif(webm, join(VIDEO, `${label}.gif`), { fps: 12, width, height });
      } catch (e) { row.gifError = String(e.message); }
    }
    row.measured = {};
    for (const [k, f] of [['webm', webm], ['mp4', row.mp4], ['gif', row.gif]]) {
      if (f && existsSync(f)) row.measured[k] = probe(f);
    }
  }
  readings.scenarios.push(row);
  const m = row.measured || {};
  for (const k of Object.keys(m)) {
    say(`  ${k}: ${m[k].width}×${m[k].height} ${m[k].fps}fps ${m[k].durationSec}s ${(m[k].sizeBytes / 1024 / 1024).toFixed(2)}MB ${m[k].frames || ''}frames`);
  }
  if (row.error) readings.errors.push(`${label}: ${row.error}`);
  if (rec.errors.length) readings.errors.push(`${label} pageerror: ${rec.errors.join(' | ')}`);
}

scenarioNormal.gif = true;
scenarioDisconnect.gif = false;
scenarioNon2xx.gif = false;
scenarioParse.gif = false;
scenarioTimeout.gif = false;

const mobile = { width: 375, height: 812, mobile: true };
const desktop = { width: 1280, height: 800, mobile: false };

if (PHASE === 'all' || PHASE === 'mobile') {
  readings.viewport.mobile = `${mobile.width}×${mobile.height} (deviceScaleFactor 2 ⇒ 750×1624 px still)`;
  await run({ label: 'mobile-normal', ...mobile, scenario: scenarioNormal });
  await run({ label: 'mobile-fail-disconnect', ...mobile, scenario: scenarioDisconnect });
  await run({ label: 'mobile-fail-non2xx', ...mobile, scenario: scenarioNon2xx });
  await run({ label: 'mobile-fail-parse', ...mobile, scenario: scenarioParse });
  await run({ label: 'mobile-fail-timeout', ...mobile, scenario: scenarioTimeout });
}
if (PHASE === 'all' || PHASE === 'desktop') {
  readings.viewport.desktop = `${desktop.width}×${desktop.height} (deviceScaleFactor 2)`;
  await run({ label: 'desktop-normal', ...desktop, scenario: scenarioNormal });
  await run({ label: 'desktop-fail-non2xx', ...desktop, scenario: scenarioNon2xx });
}

writeFileSync(join(OUT, 'readings.json'), JSON.stringify(readings, null, 2) + '\n');
say(`\nreadings: ${join(OUT, 'readings.json')}`);
say(`scenarios: ${readings.scenarios.map((s) => s.label).join(', ')}`);
if (readings.errors.length) { say(`ERRORS:\n  ${readings.errors.join('\n  ')}`); }
await browser.close();
server.close();
process.exit(readings.errors.length ? 1 : 0);
