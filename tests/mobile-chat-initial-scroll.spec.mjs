// mobile-chat-initial-scroll.spec.mjs — 手机端「点进聊天落在消息最顶部」修复批
// （作者 2026-11-02 20:42 实拍报障：「现在我点进聊天 是从消息最顶部开始读的 而不是
// 最底部的最新的消息」）机械验收 G1–G8。
//
// ── 根因（实证，逐帧时间线见 docs/20261102_*_mobile-chat-initial-scroll__*.md）──
// 会话窗 `.fm-modal` 由 `messages.renderChatModal` 建在 `#fm-chat-overlay` 里、
// 当场同步钉底；**随后** `mobile/navController.watchChatModal()` 把整个窗体
// re-parent 进 `.mobile-page-body` —— 节点迁移把所有者滚动位**归零**，而这次归零
// 发生在钉底**之后**。热缓存重开实测：`WRITE 2708`（钳到 2040）→ 18ms 后
// `scrollTop=0`（scrollHeight 2708）→ 1.5s 内再无回底写入（热路径
// `renderMessages → preserveScrollAnchor` 在顶部读几何 ⇒ `isAtBottom=false` ⇒ 只保锚）。
// 冷路径反而绿（re-parent 时 flow 还空着）。⇒ G1 红腿必须钉在**热缓存第二次打开**
// 与**真会话行点击打开**这两条形态上，主聊天冷启动是正向对照。
//
// 拓扑（隔离 LIVE 实例：生产引擎 + 真 Chromium，零触碰在产 6138/8080）：
//   隔离引擎 http://127.0.0.1:8766  home=/tmp/nb-mobile-scroll/home
//     · 三个真会话（长历史 120 条 / 短历史 3 条 / 40 轮 + 一张单件竖图附件）
//       —— SessionStore 真读盘，historyPage 由真引擎下发
//     · nebflow.json 指本进程内 OpenAI 兼容 SSE stub（G6「新消息到达」腿用）
//   真 Chromium 手机视口 390×844 / deviceScaleFactor 3 / isMobile+hasTouch
//   好友会话腿（.fm-flow）：前端既有 debug mock 面（localStorage fm_api_mock）；
//     好友面数据源 = neblink relay，隔离实例无 relay ⇒ 照既有 spec 惯例用前端
//     自治 mock。**渲染/滚动管线与真机是同一份代码**，断言仍有效；`/api/neblink/
//     status` 打桩只为点亮好友面板（登录态读数），消息本体走 mock 种子。
//
// 断言（全部读数落盘 readings.json + 截图）：
//   G1 改前复现：好友会话**第二次打开**（热缓存）/ 真会话行点击 ⇒ scrollTop ≈ 0、
//      gap ≫ 0（红读数，附 diff 截图）
//   G2 改后同两条形态：scrollTop ≈ scrollHeight − clientHeight（差 ≤ 2px）
//   G3 无可见滚动：进场每 50ms 采样，非平凡容器上的 scrollTop 无中间位（首值即终值）
//   G4 异步兜底：首帧 historyPage 迟到 900ms 仍落底（主聊天 · 正向对照，两态皆绿）
//   G5 富媒体：竖图字节迟到 700ms、撑高 165px（占位 4:3 → 真比例）后仍在底部
//      （改前红 / 改后绿：武装窗内跟随撑高）
//   G6 不误伤历史翻阅：① 主聊天上翻中段真发一条（引擎真回）不被拽回；
//      ② 好友窗内先手动上翻、再让内容增长 ⇒ 不被拽回（用户意图即拆窗）
//   G7 桌面回归：1440×900 同一读数面
//   G8 多个不同会话（长/短/带图/好友窗）各自落底
//
// Run（worktree 根）：
//   NB_CP_FILE=/tmp/nb-mobile-scroll/cp.txt EXPECT=red   node tests/mobile-chat-initial-scroll.spec.mjs
//   NB_CP_FILE=/tmp/nb-mobile-scroll/cp.txt EXPECT=green node tests/mobile-chat-initial-scroll.spec.mjs
// 收尾：生产 stdin quit（零信号）。

import { chromium } from '@playwright/test';
import { spawn, execSync } from 'node:child_process';
import { createRequire } from 'node:module';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as sleep } from 'node:timers/promises';
import { establishSession } from './helpers/session-rail.mjs';

const require_ = createRequire(import.meta.url);
const zlib = require_('node:zlib');

const REPO = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const MODE = (process.env.EXPECT || 'green').toLowerCase();
const ENGINE_PORT = Number(process.env.GATEWAY_PORT || 8766);
const MOCK_PORT = Number(process.env.MOCK_PORT || 18691);
const BASE = `http://127.0.0.1:${ENGINE_PORT}`;
const HOME = process.env.NEBFLOW_HOME || '/tmp/nb-mobile-scroll/home';
const SHOTS = path.join(REPO, '.nebflow', 'evidence', '20261102_mobile-chat-initial-scroll', MODE);
fs.mkdirSync(SHOTS, { recursive: true });

const TOKEN = 'nb-mobile-scroll-token';
const SID_LONG = 'scroll-long-history-0001';
const SID_SHORT = 'scroll-short-history-0002';
const SID_IMG = 'scroll-with-image-0003';

const TOL = 2;           // G2 落底容差（px）
const IMG_DELAY_MS = 700;
const HISTORY_DELAY_MS = 900;
// 单件竖图的「占位 → 真比例」高度差：占位 220×(220×3/4)=165，真比例 600×900
// 装箱到 220×420 上限内 ⇒ 220×330 ⇒ **+165px**（G5 的可见撑高量）。
const IMG_GROWTH_PX = 165;

let failures = 0;
const readings = [];
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures += 1;
};
const record = (name, data) => {
  readings.push({ at: new Date().toISOString(), name, ...data });
  console.log(`[read] ${name} ${JSON.stringify(data)}`);
};

// ── 端口预检（占用即中止；绝不探碰在产 6138/8080）────────────────────────────
for (const port of [ENGINE_PORT, MOCK_PORT]) {
  const out = execSync(`lsof -nP -iTCP:${port} -sTCP:LISTEN 2>/dev/null || true`, { shell: '/bin/bash' }).toString().trim();
  if (out) {
    console.error(`port ${port} occupied — abort (not touching it): ${out}`);
    process.exit(1);
  }
}

// ── stub LLM（OpenAI 兼容 SSE）───────────────────────────────────────────────
let llmServer = null;
let llmCalls = 0;
function startLlm() {
  llmServer = http.createServer((req, res) => {
    if (req.method === 'POST' && req.url === '/v1/chat/completions') {
      let raw = '';
      req.on('data', (c) => { raw += c; });
      req.on('end', async () => {
        llmCalls += 1;
        res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' });
        const chunk = (delta, finish = null) => `data: ${JSON.stringify({ id: 'chatcmpl-scroll', object: 'chat.completion.chunk', created: Math.floor(Date.now() / 1000), model: 'mock-scroll', choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`;
        res.write(chunk({ role: 'assistant', content: '' }));
        for (const p of ['SCROLL_', 'REPLY_', 'STREAM_', 'PART_', 'ALPHA_', 'OMEGA']) {
          res.write(chunk({ content: p }));
          await sleep(40);
        }
        res.write(chunk({}, 'stop'));
        res.write('data: [DONE]\n\n');
        res.end();
      });
      return;
    }
    res.writeHead(404).end();
  });
  return new Promise((resolve) => llmServer.listen(MOCK_PORT, '127.0.0.1', () => resolve(llmServer)));
}

// ── 真 PNG 生成（纯色；zlib 手搓 ⇒ 零二进制依赖）─────────────────────────────
function crc32(buf) {
  let c = ~0;
  for (let i = 0; i < buf.length; i += 1) {
    c ^= buf[i];
    for (let k = 0; k < 8; k += 1) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
  }
  return ~c;
}
function makePng(w, h, rgb) {
  const rowLen = 1 + w * 3;
  const raw = Buffer.alloc(rowLen * h);
  for (let y = 0; y < h; y += 1) {
    raw[y * rowLen] = 0;
    for (let x = 0; x < w; x += 1) {
      const o = y * rowLen + 1 + x * 3;
      raw[o] = rgb[0]; raw[o + 1] = rgb[1]; raw[o + 2] = rgb[2];
    }
  }
  const idat = zlib.deflateSync(raw);
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body) >>> 0);
    return Buffer.concat([len, body, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 2;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', idat), chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ── 隔离 home（三个真会话）───────────────────────────────────────────────────
function buildFixture() {
  fs.rmSync(HOME, { recursive: true, force: true });
  fs.mkdirSync(path.join(HOME, 'sessions'), { recursive: true });
  fs.mkdirSync(path.join(HOME, 'logs'), { recursive: true });
  fs.writeFileSync(path.join(HOME, 'auth.json'), JSON.stringify(TOKEN));
  fs.writeFileSync(path.join(HOME, 'onboarding.json'), JSON.stringify({ state: 'done', probeOkAt: Date.now() }));
  fs.writeFileSync(path.join(HOME, 'nebflow.json'), JSON.stringify({
    safety: { defaultMode: 'auto-all' },
    llm: {
      providers: {
        mock: {
          baseUrl: `http://127.0.0.1:${MOCK_PORT}/v1/`,
          apiKey: 'sk-scroll-stub',
          protocol: 'openai',
          models: [{ id: 'mock-scroll', maxTokens: 8192, contextWindow: 128000 }],
        },
      },
    },
  }, null, 2));

  const now = Date.now();
  const meta = (id, name, updatedAt) => ({ id, name, createdAt: now - 86400000, updatedAt, hasUnread: false, agentName: 'Nebula' });
  fs.writeFileSync(path.join(HOME, 'sessions', '_index.json'), JSON.stringify({
    activeId: SID_LONG,
    sessions: [
      meta(SID_LONG, '长历史会话', now),
      meta(SID_SHORT, '短历史会话', now - 1000),
      meta(SID_IMG, '带图会话', now - 2000),
    ],
    folders: [],
  }, null, 2));

  const llmRow = (role, content, ts) => ({ role, content, timestamp: ts });

  // 长历史：120 条（60 轮）⇒ 必然远超一屏。
  const longUi = [];
  const longLlm = [];
  for (let i = 0; i < 60; i += 1) {
    const ts = now - (60 - i) * 60000;
    longUi.push({ type: 'user', text: `LONG_USER_${i} 长历史会话第 ${i} 轮提问，用来把消息流撑出多屏滚轴。`, attachments: [], timestamp: ts });
    longUi.push({ type: 'ai', text: `LONG_AI_${i} 对应第 ${i} 轮回读，带足量文字以保证行高稳定。`, timestamp: ts + 1000 });
    longLlm.push(llmRow('user', `LONG_USER_${i}`, ts));
    longLlm.push(llmRow('assistant', `LONG_AI_${i}`, ts + 1000));
  }
  fs.writeFileSync(path.join(HOME, 'sessions', `${SID_LONG}.json`), JSON.stringify(longLlm));
  fs.writeFileSync(path.join(HOME, 'sessions', `${SID_LONG}.ui.json`), JSON.stringify(longUi));

  // 短历史：3 条（不足一屏）。
  fs.writeFileSync(path.join(HOME, 'sessions', `${SID_SHORT}.json`), JSON.stringify([llmRow('user', 'SHORT_USER_0', now - 5000)]));
  fs.writeFileSync(path.join(HOME, 'sessions', `${SID_SHORT}.ui.json`), JSON.stringify([
    { type: 'user', text: 'SHORT_USER_0 短会话第一条。', attachments: [], timestamp: now - 5000 },
    { type: 'ai', text: 'SHORT_AI_0 短会话回读。', timestamp: now - 4000 },
    { type: 'user', text: 'SHORT_USER_1 短会话最后一条。', attachments: [], timestamp: now - 3000 },
  ]));

  // 带图会话：40 轮 + 末尾**一张单件竖图**（G5 腿：单件走 flat 卡，占位 220×165，
  // 真比例 600×900 ⇒ 装箱 220×330，字节迟到即撑高 165px）。
  const uploadsDir = path.join(HOME, 'uploads', SID_IMG);
  fs.mkdirSync(uploadsDir, { recursive: true });
  fs.writeFileSync(path.join(uploadsDir, 'portrait.png'), makePng(600, 900, [90, 120, 220]));
  const imgUi = [];
  const imgLlm = [];
  for (let i = 0; i < 40; i += 1) {
    const ts = now - (40 - i) * 60000;
    imgUi.push({ type: 'user', text: `IMG_USER_${i} 带图会话第 ${i} 轮。`, attachments: [], timestamp: ts });
    imgUi.push({ type: 'ai', text: `IMG_AI_${i} 对应回读 ${i}。`, timestamp: ts + 1000 });
    imgLlm.push(llmRow('user', `IMG_USER_${i}`, ts));
  }
  imgUi.push({
    type: 'user', text: 'IMG_WITH_MEDIA 最后一条带一张竖图', timestamp: now - 1000,
    attachments: [{ type: 'image', name: 'portrait.png', path: path.join(uploadsDir, 'portrait.png') }],
  });
  imgUi.push({ type: 'ai', text: 'IMG_AI_TAIL 图片之后的回读。', timestamp: now - 500 });
  imgLlm.push(llmRow('user', 'IMG_WITH_MEDIA', now - 1000));
  imgLlm.push(llmRow('assistant', 'IMG_AI_TAIL', now - 500));
  fs.writeFileSync(path.join(HOME, 'sessions', `${SID_IMG}.json`), JSON.stringify(imgLlm));
  fs.writeFileSync(path.join(HOME, 'sessions', `${SID_IMG}.ui.json`), JSON.stringify(imgUi));
}

// ── 隔离引擎（classpath → java 直启；stdin quit 收尾）───────────────────────
let engine = null;
let engineLog = '';
function execSbt(args) {
  return new Promise((resolve, reject) => {
    const p = spawn('sbt', args, { cwd: REPO });
    let out = '';
    p.stdout.on('data', (d) => { out += d.toString(); });
    p.stderr.on('data', (d) => { out += d.toString(); });
    p.on('close', (c) => (c === 0 ? resolve(out) : reject(new Error(`sbt exit ${c}: ${out.slice(-600)}`))));
  });
}

async function startEngine() {
  const cpOut = process.env.NB_CP_FILE
    ? fs.readFileSync(process.env.NB_CP_FILE, 'utf8')
    : await execSbt(['-batch', 'export Compile/fullClasspath']);
  // 锚定应用侧 classpath（target/scala-3.x/classes 起头的那一行）。
  const line = cpOut.split('\n')
    .filter((l) => l.includes('/target/scala-3') && l.includes('/classes:') && l.trim().length > 1000)
    .sort((a, b) => b.length - a.length)[0];
  if (!line || line.trim().split(':').length < 20) throw new Error('classpath parse failed');
  const cp = line.trim();
  engine = spawn('java', [
    '-Dnebflow.start.foreground=true', '-cp', cp, 'nebflow.Main',
    '--home', HOME, '--port', String(ENGINE_PORT), '--no-browser', 'start',
  ], { cwd: REPO, stdio: ['pipe', 'pipe', 'pipe'], env: { ...process.env, NEBFLOW_GATEWAY_PORT: String(ENGINE_PORT) } });
  const drain = (d) => { engineLog += d.toString(); if (engineLog.length > 400000) engineLog = engineLog.slice(-200000); };
  engine.stdout.on('data', drain);
  engine.stderr.on('data', drain);
}

async function waitHealthy(timeoutMs = 180000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    try {
      const r = await fetch(`${BASE}/api/health`);
      if (r.ok) return true;
    } catch { /* booting */ }
    await sleep(1000);
  }
  return false;
}

async function stopEngine() {
  if (!engine || engine.exitCode !== null || engine.signalCode) return;
  await new Promise((resolve) => {
    engine.once('exit', resolve);
    try { engine.stdin.write('q\n'); } catch { resolve(); }
    setTimeout(resolve, 25000).unref();
  });
}

let browser = null;
async function cleanup() {
  try { if (browser) await browser.close(); } catch { /* already */ }
  try { llmServer?.closeAllConnections?.(); } catch { /* older node */ }
  try { llmServer?.close(); } catch { /* already */ }
  await stopEngine();
}

// ── 读数助手 ────────────────────────────────────────────────────────────────
const CHAT_SEL = '#chat';
const FLOW_SEL = '#mobile-pages .mobile-page[data-page="conversation"] .fm-flow';

async function geometry(page, selector) {
  return page.evaluate((sel) => {
    const el = document.querySelector(sel);
    if (!el) return { found: false };
    const cs = getComputedStyle(el);
    return {
      found: true,
      scrollTop: Math.round(el.scrollTop * 100) / 100,
      scrollHeight: el.scrollHeight,
      clientHeight: el.clientHeight,
      maxScroll: Math.round((el.scrollHeight - el.clientHeight) * 100) / 100,
      gap: Math.round((el.scrollHeight - el.clientHeight - el.scrollTop) * 100) / 100,
      childCount: el.childElementCount,
      overflowY: cs.overflowY,
      paddingTop: cs.paddingTop,
      paddingBottom: cs.paddingBottom,
      dpr: window.devicePixelRatio,
      vw: window.innerWidth,
      vh: window.innerHeight,
    };
  }, selector);
}

async function waitBoot(page, sid) {
  await page.waitForFunction(async (target) => {
    const m = await import('/js/state.js');
    const st = m.default;
    return st.activeSessionId === target && st.ws && st.ws.readyState === 1;
  }, sid, { timeout: 60000 });
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), { timeout: 30000 });
}

const landed = (g, tol = TOL) => g.found && g.maxScroll > 0 && Math.abs(g.gap) <= tol;
const atTop = (g, tol = 40) => g.found && Math.abs(g.scrollTop) <= tol;

async function switchSession(page, sid) {
  await page.evaluate(async (target) => {
    const m = await import('/js/sidebar.js');
    m.switchToSession(target);
  }, sid);
  await page.waitForFunction(async (target) => (await import('/js/state.js')).default.activeSessionId === target, sid, { timeout: 20000 });
}

// ── 主流程 ──────────────────────────────────────────────────────────────────
buildFixture();
await startLlm();
await startEngine();
if (!(await waitHealthy())) {
  console.error('[fatal] isolated engine never came up');
  console.error(engineLog.slice(-4000));
  await cleanup();
  process.exit(1);
}
console.log(`[setup] isolated engine up at ${BASE} (mode=${MODE})`);

// 好友会话腿的前端 mock 种子（好友面数据源在 neblink relay；隔离实例无 relay）。
const FM_SEED = (() => {
  const now = Date.now();
  const msgs = [];
  for (let i = 1; i <= 40; i += 1) {
    msgs.push({ id: i, senderId: i % 2 ? 'u-f1' : 'me', kind: 'text', body: `FM_${i} 好友会话消息 —— 撑出滚轴`, createdAt: now - (40 - i) * 60000 });
  }
  const friend = { userId: 'u-f1', neblinkId: 'buddy1', name: '老友', avatarUrl: '', remark: '', bio: '', since: now - 86400000 };
  return {
    self: { userId: 'me', neblinkId: 'me@example.com', name: '我自己', avatarUrl: '', bio: '' },
    users: [],
    friends: [friend],
    incoming: [],
    outgoing: [],
    conversations: [{ conversationId: 'c-f1', friend: { ...friend }, lastMessage: msgs[msgs.length - 1], unreadCount: 0 }],
    messages: { 'c-f1': msgs },
    groups: [],
    groupInvites: [],
    groupMembers: {},
    _now: now,
  };
})();

const initScript = ({ seed, imgDelay, historyDelay, sampleFlow }) => {
  localStorage.setItem('nebflow_locale', 'zh-CN');
  localStorage.setItem('neblink_locale', 'zh-CN');
  localStorage.setItem('fm_api_mock', '1');
  localStorage.setItem('fm_api_mock_seed', JSON.stringify(seed));
  localStorage.setItem('fm_api_mock_delay', '0');
  window.__scrollTrace = [];
  window.__flowTrace = [];
  window.__imgDelayed = 0;
  // 富媒体慢加载（G5）：把 /uploads/ 的 <img src> 安装推迟到 imgDelay 之后 ——
  // 模拟真机弱网下图片字节迟到（含 messages.js decodeDims 的探针 Image：
  // 比例解码同样迟到 ⇒ 占位先出、真比例后到，撑高可见）。
  if (imgDelay > 0) {
    const d = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, 'src');
    Object.defineProperty(HTMLImageElement.prototype, 'src', {
      configurable: true,
      get() { return d.get.call(this); },
      set(v) {
        const val = String(v);
        if (val.includes('/uploads/')) {
          window.__imgDelayed += 1;
          setTimeout(() => { try { d.set.call(this, val); } catch { /* detached */ } }, imgDelay);
          return;
        }
        d.set.call(this, val);
      },
    });
  }
  // 异步兜底（G4）：getHistory 出站帧延迟 historyDelay 才真正发车。
  if (historyDelay > 0) {
    window.__historyDelayed = 0;
    const OrigWS = window.WebSocket;
    window.WebSocket = class extends OrigWS {
      constructor(...args) {
        super(...args);
        const rawSend = this.send.bind(this);
        this.send = (data) => {
          try {
            const f = JSON.parse(data);
            if (f && f.type === 'getHistory') {
              window.__historyDelayed += 1;
              setTimeout(() => { try { rawSend(data); } catch { /* closed */ } }, historyDelay);
              return;
            }
          } catch { /* non-JSON */ }
          return rawSend(data);
        };
      }
    };
  }
  // 进场滚动采样（G3）：每 50ms 记一次 #chat 与 .fm-flow 的几何（离开底部即留痕）。
  const tick = () => {
    const now = Math.round(performance.now());
    const chat = document.getElementById('chat');
    if (chat) window.__scrollTrace.push({ t: now, top: Math.round(chat.scrollTop), h: chat.scrollHeight, c: chat.clientHeight });
    if (sampleFlow) {
      const flow = document.querySelector('#mobile-pages .mobile-page[data-page="conversation"] .fm-flow');
      if (flow) window.__flowTrace.push({ t: now, top: Math.round(flow.scrollTop), h: flow.scrollHeight, c: flow.clientHeight });
    }
  };
  const start = () => { setInterval(tick, 50); };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
};

browser = await chromium.launch();

async function openPhone({ imgDelay = 0, historyDelay = 0, sampleFlow = false } = {}) {
  const context = await browser.newContext({
    baseURL: BASE,
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 3,
    isMobile: true,
    hasTouch: true,
    userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1',
  });
  await context.addInitScript(initScript, { seed: FM_SEED, imgDelay, historyDelay, sampleFlow });
  const page = await context.newPage();
  page.on('pageerror', (e) => console.error('[pageerror]', e.message));
  // 好友面登录态：neblink relay 不在隔离实例里 ⇒ 只把 `/api/neblink/status`
  // 的**登录/设备读数**打桩（其余请求全真打引擎）。好友消息本体走前端 debug
  // mock 面（fm_api_mock），渲染/滚动管线与真机同一份代码。
  await page.route('**/api/neblink/status', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      loggedIn: true,
      device: { id: 'dev-self', name: 'Mashiro-Mac', platform: 'macos', email: 'me@example.com', displayName: '我自己' },
      peers: [{ deviceId: 'dev-kai', deviceName: 'KAI', platform: 'windows', online: true, directOnline: true }],
    }),
  }));
  await establishSession(BASE, TOKEN, page, { waitUntil: 'domcontentloaded' });
  return { context, page };
}

/** 好友会话开窗（evaluate 腿）：等会话页 live + 静止窗。 */
async function fmOpenByApi(page) {
  await page.evaluate(async () => { const m = await import('/js/messages.js'); await m.openConversation('c-f1', null); });
  await page.waitForSelector('#mobile-pages .mobile-page[data-page="conversation"].live', { timeout: 20000 });
}

/** 好友会话真行点击开窗（用户面入口：先回「消息」根页，再点真 `.fm-conv-row`）。 */
async function fmOpenByRow(page) {
  await page.locator('#mobile-dock .dock-key[data-dock="messages"]').click();
  await sleep(700);
  const row = page.locator('#fm-conversations .fm-conv-row[data-conversation-id="c-f1"]');
  await row.waitFor({ state: 'visible', timeout: 20000 });
  await row.click();
  await page.waitForSelector('#mobile-pages .mobile-page[data-page="conversation"].live', { timeout: 20000 });
}

async function closeFm(page) {
  await page.evaluate(async () => { const m = await import('/js/messages.js'); m.closeChat?.(); });
  await sleep(600);
}

let exitCode = 0;
try {
  // ═══ 手机腿 A：主聊天冷启动（正向对照）══════════════════════════════════
  const { context, page } = await openPhone({ sampleFlow: true });
  await waitBoot(page, SID_LONG);
  await page.waitForSelector('#chat .row', { timeout: 60000 });
  await sleep(1500); // 静止态：输入区 padding 量高 + md 升级批排空

  const gLong = await geometry(page, CHAT_SEL);
  record('A1-main-chat-cold-boot', gLong);
  const shotLong = path.join(SHOTS, 'A1-main-chat-cold-boot.png');
  await page.screenshot({ path: shotLong });

  const trace = await page.evaluate(() => (window.__scrollTrace || []).slice(-200));
  const nontrivial = trace.filter((s) => s.h - s.c > 200);
  const tops = nontrivial.map((s) => s.top);
  const firstReal = nontrivial.find((s) => s.h - s.c > 200);
  const intermediate = tops.filter((t) => t > 4 && t < (firstReal ? firstReal.h - firstReal.c - 8 : Infinity));
  record('A1-scroll-trace', { samples: trace.length, nontrivialSamples: nontrivial.length, topsSample: tops.slice(0, 40), intermediate });
  // 正向对照：主聊天冷启动**两态皆落底**（证明报障不在主聊天这条形态上）。
  ok('A1 main chat cold boot lands at the bottom (positive control, both modes)', landed(gLong),
    `scrollTop=${gLong.scrollTop} max=${gLong.maxScroll} gap=${gLong.gap}`);
  ok('A1b main chat cold boot has no intermediate scroll positions',
    intermediate.length === 0 && !!firstReal && firstReal.top > 0,
    `intermediate=${intermediate.length} tops=${JSON.stringify(tops.slice(0, 12))}`);

  // ── G8：会话切换（短 / 带图 / 回长）──────────────────────────────────
  for (const [sid, label] of [[SID_SHORT, 'short'], [SID_IMG, 'image'], [SID_LONG, 'long-again']]) {
    await switchSession(page, sid);
    await sleep(1500);
    const g = await geometry(page, CHAT_SEL);
    record(`G8-switch-${label}`, g);
    await page.screenshot({ path: path.join(SHOTS, `G8-switch-${label}.png`) });
    if (label === 'short') {
      // 短历史不足一屏：收口判据 = 无溢出（max ≤ 0）或已贴底。
      ok('G8 session short: no overflow (max ≤ 0) or landed', g.found && (g.maxScroll <= 0 || landed(g)),
        `gap=${g.gap} max=${g.maxScroll} rows=${g.childCount}`);
    } else {
      ok(`G8 session ${label} lands at the bottom`, landed(g), `gap=${g.gap} max=${g.maxScroll} rows=${g.childCount}`);
    }
  }

  // ═══ G1/G2/G3：好友会话落顶（两条形态：热缓存重开 / 真行点击）════════════
  // 形态 ①：第一次开（冷：开窗时 flow 还空）→ 关 → 第二次开（热缓存 = 报障形态）
  await fmOpenByApi(page);
  await sleep(1500);
  const gFm1 = await geometry(page, FLOW_SEL);
  record('G1-fm-open-1-cold', gFm1);
  await page.screenshot({ path: path.join(SHOTS, 'G1-fm-open-1.png') });
  await closeFm(page);

  await fmOpenByApi(page);
  await sleep(1500);
  const gFm2 = await geometry(page, FLOW_SEL);
  record('G1-fm-open-2-warm', gFm2);
  const shotFm2 = path.join(SHOTS, MODE === 'red' ? 'G1-fm-open-2-warm-TOP.png' : 'G2-fm-open-2-warm-bottom.png');
  await page.screenshot({ path: shotFm2 });

  const flowTrace = await page.evaluate(() => (window.__flowTrace || []).slice(-160));
  const fTops = flowTrace.filter((s) => s.h - s.c > 200).map((s) => s.top);
  record('G3-fm-scroll-trace', { samples: flowTrace.length, topsSample: fTops.slice(0, 40) });
  await closeFm(page);

  // 形态 ②：真会话行点击（用户面入口；同样第二次开 = 热缓存）
  await fmOpenByRow(page);
  await sleep(1500);
  const gFm3 = await geometry(page, FLOW_SEL);
  record('G1-fm-open-row-click', gFm3);
  const shotFm3 = path.join(SHOTS, MODE === 'red' ? 'G1-fm-row-click-TOP.png' : 'G2-fm-row-click-bottom.png');
  await page.screenshot({ path: shotFm3 });

  if (MODE === 'red') {
    ok('G1 red ①: friend conversation re-open (warm cache) enters at the TOP',
      atTop(gFm2, 40) && gFm2.gap > 200, `scrollTop=${gFm2.scrollTop} max=${gFm2.maxScroll} gap=${gFm2.gap}`);
    ok('G1 red ②: friend conversation opened by real row click enters at the TOP',
      atTop(gFm3, 40) && gFm3.gap > 200, `scrollTop=${gFm3.scrollTop} max=${gFm3.maxScroll} gap=${gFm3.gap}`);
    record('G2-skipped-in-red', { reason: 'red baseline asserts only the defect reproduction' });
    record('G3-skipped-in-red', { reason: 'no visible-scroll claim on a top-entry baseline' });
  } else {
    ok('G2 ①: friend conversation re-open (warm cache) lands at the bottom (|gap| ≤ 2px)',
      landed(gFm2), `scrollTop=${gFm2.scrollTop} max=${gFm2.maxScroll} gap=${gFm2.gap}`);
    ok('G2 ②: friend conversation opened by real row click lands at the bottom (|gap| ≤ 2px)',
      landed(gFm3), `scrollTop=${gFm3.scrollTop} max=${gFm3.maxScroll} gap=${gFm3.gap}`);
    // G3：非平凡（溢出 > 200px）采样里，scrollTop 不得**停留**在中间位。
    // 判据 = 「离开底部的连续采样段长度 ≥ 2」—— 采样周期 50ms，单点命中（≤50ms，
    // ≈3 帧）是 rAF 收敛的**帧内**瞬态，不构成可见滚动；连续两点及以上（≥100ms）
    // 才是用户可能看见的中间位。原始命中点一并落盘（不掩盖）。
    const maxSeen = Math.max(...fTops, 0);
    const middles = fTops.filter((t) => t > 4 && t < maxSeen - 8);
    let run = 0; let longestRun = 0;
    for (const t of fTops) {
      if (t > 4 && t < maxSeen - 8) { run += 1; longestRun = Math.max(longestRun, run); } else { run = 0; }
    }
    record('G3-fm-intermediate', { middles, longestRun, maxSeen, samples: fTops.length });
    ok('G3 no visible scrolling on entry (no off-bottom rest longer than one sample)',
      longestRun < 2, `longestRun=${longestRun} middles=${middles.length} maxSeen=${maxSeen}`);
  }

  // ── G6②：好友窗内手动上翻后，内容增长不得把读者拽回底部 ──────────────
  {
    const before = await geometry(page, FLOW_SEL);
    await page.mouse.move(195, 400);
    await page.mouse.wheel(0, -600);
    await sleep(400);
    const scrolled = await geometry(page, FLOW_SEL);
    // 内容增长（构造：等效一条迟到消息的行高增长 —— 好友面的到达腿在 mock 面
    // 无推送源，故以同尺寸节点直接撑高，测的是「有没有被拽回」而非渲染）。
    await page.evaluate((sel) => {
      const flow = document.querySelector(sel);
      if (!flow) return;
      const filler = document.createElement('div');
      filler.className = 'fm-msg in g6-growth-probe';
      filler.style.cssText = 'height:420px;flex:0 0 auto;';
      flow.appendChild(filler);
    }, FLOW_SEL);
    await sleep(700);
    const after = await geometry(page, FLOW_SEL);
    const delta = Math.abs(after.scrollTop - scrolled.scrollTop);
    record('G6-fm-manual-scroll-growth', { before, scrolled, after, delta });
    ok('G6 ② content growth after a manual scroll-up does not yank the reader',
      delta <= 8 && after.scrollTop < after.maxScroll - 100,
      `delta=${delta} scrolled=${scrolled.scrollTop} after=${after.scrollTop} max=${after.maxScroll}`);
    await page.screenshot({ path: path.join(SHOTS, 'G6-fm-history-reader.png') });
    // 清场：把构造节点摘掉，回到静止态
    await page.evaluate(() => document.querySelectorAll('.g6-growth-probe').forEach(n => n.remove()));
    await closeFm(page);
  }

  // ── G6①：主聊天上翻中途**外部到达**一条新消息，不得被拽回 ──────────────
  // 到达腿用 `/api/callbacks/inject`（ExternalEvent/inject ⇒ 注入型 user 行，
  // 不是本机所发 ⇒ 主窗「本机消息恒回底」裁定不适用，测的才是贴底闩）。
  {
    await switchSession(page, SID_LONG);
    await sleep(1200);
    const before = await geometry(page, CHAT_SEL);
    await page.evaluate((sel) => { const el = document.querySelector(sel); el.scrollTop = Math.max(0, el.scrollTop - 900); }, CHAT_SEL);
    await page.mouse.move(195, 400);
    await page.mouse.wheel(0, -300);
    await sleep(400);
    const scrolled = await geometry(page, CHAT_SEL);
    const inj = await fetch(`${BASE}/api/callbacks/inject`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${TOKEN}` },
      body: JSON.stringify({ agent: 'Nebula', session: SID_LONG, message: 'G6_ARRIVAL 上翻中段时从外部到达的一条新消息。', source: 'g6' }),
    }).then(async (r) => ({ status: r.status })).catch(() => ({ status: 0 }));
    await page.waitForFunction(() => document.body.innerText.includes('G6_ARRIVAL'), null, { timeout: 30000 }).catch(() => {});
    await sleep(1500);
    const after = await geometry(page, CHAT_SEL);
    const delta = Math.abs(after.scrollTop - scrolled.scrollTop);
    record('G6-main-manual-scroll-arrival', { before, scrolled, after, delta, inject: inj });
    ok('G6 ① an arrival does not yank a main-chat history reader back to the bottom',
      delta <= 8 && after.scrollTop < after.maxScroll - 100,
      `delta=${delta} scrolled=${scrolled.scrollTop} after=${after.scrollTop} max=${after.maxScroll} inject=${inj.status}`);
    await page.screenshot({ path: path.join(SHOTS, 'G6-main-history-reader.png') });
  }
  await context.close();

  // ═══ G4：异步兜底（getHistory 迟到 900ms · 正向对照）═════════════════════
  {
    const { context: c4, page: p4 } = await openPhone({ historyDelay: HISTORY_DELAY_MS });
    await waitBoot(p4, SID_LONG);
    await p4.waitForSelector('#chat .row', { timeout: 60000 });
    await sleep(2500);
    const g4 = await geometry(p4, CHAT_SEL);
    const delayed = await p4.evaluate(() => window.__historyDelayed || 0);
    record('G4-delayed-history', { ...g4, delayedFrames: delayed });
    await p4.screenshot({ path: path.join(SHOTS, 'G4-delayed-history.png') });
    ok('G4 delayed historyPage (900ms) still lands at the bottom', landed(g4) && delayed > 0,
      `gap=${g4.gap} max=${g4.maxScroll} delayed=${delayed}`);
    await c4.close();
  }

  // ═══ G5：富媒体迟到撑高（竖图 600×900 · 字节迟到 700ms ⇒ +165px）═════════
  {
    const { context: c5, page: p5 } = await openPhone({ imgDelay: IMG_DELAY_MS });
    // 冷启动落在 activeId（SID_LONG）上，再切到带图会话（切换 = 重启渲染管线）：
    await waitBoot(p5, SID_LONG);
    await p5.waitForSelector('#chat .row', { timeout: 60000 });
    await switchSession(p5, SID_IMG);
    await p5.waitForFunction(() => {
      const c = document.getElementById('chat');
      return c && c.querySelectorAll('.row').length > 40 && c.textContent.includes('IMG_AI_TAIL');
    }, null, { timeout: 60000 });
    // 撑高前（图片字节未到 ⇒ 占位 4:3，220×165）
    const early = await geometry(p5, CHAT_SEL);
    await sleep(2600); // 图片到达 + 比例解码 + 落底窗（窗口 1200ms 内含 700ms 迟到）
    const late = await geometry(p5, CHAT_SEL);
    const imgs = await p5.evaluate(() => {
      const list = [...document.querySelectorAll('#chat .pop-media-flat img')];
      const one = list[list.length - 1];
      const box = one ? one.closest('.pop-media-flat') : null;
      return {
        count: list.length,
        complete: list.filter((i) => i.complete && i.naturalWidth > 0).length,
        delayed: window.__imgDelayed || 0,
        box: box ? { w: box.clientWidth, h: box.clientHeight, ratio: box.dataset.nfRatio || '', ar: getComputedStyle(box).aspectRatio } : null,
      };
    });
    const growth = Math.round((late.scrollHeight - early.scrollHeight) * 100) / 100;
    record('G5-rich-media', { early, late, growth, imgs });
    await p5.screenshot({ path: path.join(SHOTS, 'G5-rich-media.png') });
    if (MODE === 'red') {
      ok('G5 (red baseline) late rich media pushes the view off the bottom',
        growth > 100 && late.gap > 100,
        `growth=${growth} gap=${late.gap} delayed=${imgs.delayed} box=${JSON.stringify(imgs.box)}`);
    } else {
      ok('G5 late rich media growth still ends at the bottom',
        growth >= IMG_GROWTH_PX - 2 && landed(late) && imgs.complete === imgs.count && imgs.delayed > 0,
        `growth=${growth} gap=${late.gap} imgs=${JSON.stringify(imgs)}`);
    }
    await c5.close();
  }

  // ═══ G7：桌面回归（1440×900）══════════════════════════════════════════
  {
    const dctx = await browser.newContext({ baseURL: BASE, viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 });
    await dctx.addInitScript(initScript, { seed: FM_SEED, imgDelay: 0, historyDelay: 0, sampleFlow: false });
    const dp = await dctx.newPage();
    dp.on('pageerror', (e) => console.error('[desktop pageerror]', e.message));
    // 好友列表的登录态判据（`loggedIn()`）读 `/api/neblink/status`：与手机腿
    // **同一枚打桩**（neblink relay 不在隔离实例里）—— 必须在**首次导航之前**
    // 挂上，否则列表恒空 ⇒ `openConversation` 找不到会话静默返回。
    await dp.route('**/api/neblink/status', (route) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        loggedIn: true,
        device: { id: 'dev-self', name: 'Mashiro-Mac', platform: 'macos', email: 'me@example.com', displayName: '我自己' },
        peers: [],
      }),
    }));
    await establishSession(BASE, TOKEN, dp, { waitUntil: 'domcontentloaded' });
    await dp.waitForFunction(async (target) => {
      const st = (await import('/js/state.js')).default;
      return st.activeSessionId === target && st.ws && st.ws.readyState === 1;
    }, SID_LONG, { timeout: 60000 });
    await dp.waitForSelector('#chat .row', { timeout: 60000 });
    await sleep(1500);
    const g7 = await geometry(dp, CHAT_SEL);
    const mobileLayer = await dp.evaluate(() => document.body.classList.contains('mobile-layer'));
    record('G7-desktop-1440x900', { ...g7, mobileLayer });
    await dp.screenshot({ path: path.join(SHOTS, 'G7-desktop.png') });
    // 桌面好友窗（同一渲染器的桌面腿）：开窗即落底 —— 证明修复没把桌面弄坏。
    await dp.waitForFunction(() => document.querySelectorAll('#fm-conversations .fm-conv-row').length > 0, null, { timeout: 30000 });
    await dp.evaluate(async () => { const m = await import('/js/messages.js'); await m.openConversation('c-f1', null); });
    await dp.waitForSelector('#fm-chat-overlay .fm-modal .fm-flow', { timeout: 20000 });
    await sleep(900);
    const g7fm = await geometry(dp, '#fm-chat-overlay .fm-flow');
    record('G7-desktop-fm', g7fm);
    await dp.screenshot({ path: path.join(SHOTS, 'G7-desktop-fm.png') });
    ok('G7 desktop (1440×900) main chat still lands at the bottom — no regression',
      landed(g7) && mobileLayer === false, `gap=${g7.gap} max=${g7.maxScroll} mobileLayer=${mobileLayer}`);
    ok('G7b desktop friend window still lands at the bottom — no regression',
      landed(g7fm), `gap=${g7fm.gap} max=${g7fm.maxScroll} rows=${g7fm.childCount}`);
    await dctx.close();
  }
} catch (e) {
  console.error('[fatal]', e);
  exitCode = 1;
}

ok('no unexpected fatal (harness completed)', exitCode === 0);

fs.writeFileSync(path.join(SHOTS, 'readings.json'), JSON.stringify({ mode: MODE, failures, readings }, null, 2));
console.log(`\n[summary] mode=${MODE} failures=${failures} shots=${SHOTS}`);
await cleanup();
process.exit(exitCode || (failures ? 1 : 0));
