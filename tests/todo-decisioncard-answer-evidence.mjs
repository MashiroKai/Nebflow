#!/usr/bin/env node
// tests/todo-decisioncard-answer-evidence.mjs — 决策卡「问题 + 回答框」前后对照证据腿。
//
// 作者报障（2026-11-18）：「手机端待办 决策卡视角 问题和回答输入框呢 现在怎么好像
// 只有内容？」本腿把**同一段**手机全屏交互在**未修源码**与**修后源码**上各录一遍，
// 产出可直接对照的 GIF/MP4：
//
//   打开决策卡（375×812 全屏页，不是弹窗/内嵌面板）
//     → 首屏读数（问题在不在卡内 / 有没有回答框）
//     → 有回答框就真填字 + 真提交
//     → 卡片内下滑看长正文（输入框仍在）
//     → 左滑切下一条 → 返回按钮回列表页
//
// 用法：
//   node tests/todo-decisioncard-answer-evidence.mjs                       # 修后（本树）
//   QD_WEB_ROOT=<另一棵 web 树> QD_MIN=before QD_MEDIA_OUT=<目录> \
//     node tests/todo-decisioncard-answer-evidence.mjs                     # 未修源码
// 默认：QD_MIN=after / QD_MEDIA_OUT=/Users/kaiyu/stand-evidence/todo-decisioncard-20261118
// 退出码：0 = 录完（读数落盘）；1 = 腿本身失败。
// 🔴 诚实申报：数据面 = WS 帧桩夹具 + todoStore.__seed 夹具模式；静态面 = 从磁盘
//   注入的真 web 树。**不是**作者真机上那台服务器，也不是真机手感 —— 真机另列
//   「待真机亲验」。渲染链、DOM 几何、事件序列、出帧全是真的。

import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.QD_WEB_ROOT
  ? normalize(resolve(process.env.QD_WEB_ROOT))
  : join(HERE, '..', 'src', 'main', 'resources', 'web');
const MIN = process.env.QD_MIN || 'after';
const OUT = process.env.QD_MEDIA_OUT || '/Users/kaiyu/stand-evidence/todo-decisioncard-20261118';
const FRAMES = join(OUT, `frames-${MIN}`);
rmSync(FRAMES, { recursive: true, force: true });
mkdirSync(OUT, { recursive: true });
mkdirSync(FRAMES, { recursive: true });
const MEDIA_CAP = 10 * 1024 * 1024;

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'todo-quick-decision-root';
const VW = 375, VH = 812;
const CARD_SEL = '.mobile-page[data-page="todo-quick-decision"]';
const ANSWER_TEXT = '选 A：先把待办数据模型的字段对齐做完';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function fixture() {
  const now = Date.now();
  const longDetail = Array.from({ length: 24 }, (_, i) => `内容段 ${i + 1}：这是待办正文里较长的第 ${i + 1} 段，用来把卡片撑到必须下滑才看得完。`).join('\n');
  return {
    tagList: [{ id: 'tw', name: '工作', color: 'rgb(0, 122, 255)' }],
    itemList: [
      {
        id: 'qd-1', tagId: 'tw', priority: 3, due: now + 3_600_000,
        name: '周会先讲哪件事',
        detail: `${longDetail}\n末段：正文到底了。`,
        decision: {
          question: '本周先推进哪一件？',
          options: [
            'A 把待办数据模型的字段对齐做完（选项文本故意写长一点，检验换行与行高策略）',
            'B 先把手机端列表整改发出去',
            'C 两个并行，按人力缺口再收',
          ],
        },
      },
      { id: 'qd-2', tagId: null, priority: 0, name: '纯内容待办（无问题）', detail: longDetail },
    ],
  };
}

const RECT = `(sel) => { const el = document.querySelector(sel); if (!el) return null; const b = el.getBoundingClientRect();
  const f = (v) => +v.toFixed(2);
  return { x: f(b.x), y: f(b.y), w: f(b.width), h: f(b.height), bottom: f(b.bottom), cx: f(b.x + b.width / 2), cy: f(b.y + b.height / 2) }; }`;

/** 首屏读数：问题在不在卡内、有没有回答框、底缘有没有被 dock 吃掉。 */
const READ_OPEN = `(() => {
  const card = document.querySelector('${CARD_SEL} .ta-qd-card');
  if (!card) return { error: 'no card' };
  const cb = card.getBoundingClientRect();
  const q = card.querySelector('.ta-qd-q');
  const ta = card.querySelector('.ta-qd-answer-input');
  const dock = document.querySelector('#mobile-dock');
  const scroll = card.querySelector('.ta-qd-scroll');
  const qr = q ? q.getBoundingClientRect() : null;
  const tr = ta ? ta.getBoundingClientRect() : null;
  const dr = dock ? dock.getBoundingClientRect() : null;
  const f = (v) => +v.toFixed(2);
  return {
    viewport: { w: innerWidth, h: innerHeight },
    card: { top: f(cb.top), bottom: f(cb.bottom), h: f(cb.height) },
    dock: dr ? { top: f(dr.top), bottom: f(dr.bottom) } : null,
    scrollTop: scroll ? +scroll.scrollTop.toFixed(1) : null,
    questionText: q ? q.textContent : '',
    questionRect: qr ? { top: f(qr.top), bottom: f(qr.bottom) } : null,
    questionVisibleInCard: !!(qr && qr.top >= cb.top - 1 && qr.bottom <= cb.bottom + 1),
    answerPresent: !!ta,
    answerValue: ta ? ta.value : null,
    answerRect: tr ? { top: f(tr.top), bottom: f(tr.bottom), h: f(tr.height) } : null,
    answerInCard: !!(tr && tr.top >= cb.top - 1 && tr.bottom <= cb.bottom + 1),
    answerAboveDock: !!(tr && (!dr || tr.bottom <= dr.top + 0.5)),
  };
})()`;

async function touchDrag(page, x, y, dx, dy, steps = 10, stepMs = 20) {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y, id: 1 }] });
  for (let i = 1; i <= steps; i++) {
    await cdp.send('Input.dispatchTouchEvent', {
      type: 'touchMove',
      touchPoints: [{ x: Math.round(x + (dx * i) / steps), y: Math.round(y + (dy * i) / steps), id: 1 }],
    });
    await sleep(stepMs);
  }
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await cdp.detach();
}

const readings = { min: MIN, web: WEB, viewport: { w: VW, h: VH }, deviceScaleFactor: 2 };
const sentFrames = [];

const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: { width: VW, height: VH }, deviceScaleFactor: 2,
  hasTouch: true, isMobile: true, colorScheme: 'light', locale: 'zh-CN',
});
const page = await ctx.newPage();
const pageErrors = [];
const NOISE = /Failed to load resource|fetching the script|ResizeObserver loop completed/i;
page.on('pageerror', (e) => { if (!NOISE.test(e.message)) pageErrors.push(`pageerror: ${e.message}`); });
page.on('console', (m) => { if (m.type() === 'error' && !NOISE.test(m.text())) pageErrors.push(`console.error: ${m.text()}`); });

await page.addInitScript(() => {
  localStorage.setItem('nebflow_token', 'e2e-token');
  localStorage.setItem('nebflow_locale', 'zh-CN');
  if (navigator.serviceWorker) {
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
  }
});
await page.route('**/*', (route) => {
  const url = new URL(route.request().url());
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  const file = normalize(join(WEB, p));
  if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
  try {
    return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
  } catch { return route.fulfill({ status: 404, body: 'not found' }); }
});
await page.routeWebSocket(/\/ws/, (ws) => {
  const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
  const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
  sendConfig(); sendSessions();
  ws.onMessage((raw) => {
    sentFrames.push(String(raw));
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'getConfig') sendConfig();
    else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
    else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    else if (m.type === 'listScheduledTasks') ws.send(JSON.stringify({ type: 'scheduledTaskList', scope: 'session', tasks: [] }));
    else if (typeof m.content === 'string' && m.sessionId) ws.send(JSON.stringify({ type: 'done', sessionId: m.sessionId }));
  });
});

await page.goto('http://localhost:1/');
await page.waitForFunction(async (rootSid) => {
  const s = (await import('/js/state.js')).default;
  return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
}, SID, { timeout: 20000 });
await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
await page.evaluate(async (f) => {
  const store = await import('/js/todoStore.js');
  store.todoStore.__seed({ tagList: f.tagList, itemList: f.itemList });
}, fixture());
await page.waitForTimeout(240);
await page.evaluate(async () => { const nav = await import('/js/mobile/navController.js'); nav.pushPage('tasks'); });
await page.waitForSelector('.mobile-page[data-page="tasks"].live', { timeout: 15000 });
await page.waitForTimeout(360);

/* ── 录屏：同一段交互，两棵树各录一遍 ── */
const cdp = await ctx.newCDPSession(page);
const frames = [];
cdp.on('Page.screencastFrame', (f) => {
  frames.push(f.data);
  cdp.send('Page.screencastFrameAck', { sessionId: f.sessionId }).catch(() => {});
});

const emit = async (name) => {
  await page.screenshot({ path: join(OUT, `${MIN}-${name}.png`) });
};

await emit('00-home');
await cdp.send('Page.startScreencast', { format: 'jpeg', quality: 82, maxWidth: VW, maxHeight: VH, everyNthFrame: 1 });
await sleep(500);

// ① 打开决策卡
await page.locator('.mobile-page[data-page="tasks"] .ta-quick-fab').click();
await page.waitForSelector(`${CARD_SEL}.live`, { timeout: 10000 });
await sleep(900);
readings.open = await page.evaluate(READ_OPEN);
await emit('01-card-open');
readings.openPng = `${MIN}-01-card-open.png`;

// ② 处理回答框：有就真填字 + 真提交（未修源码这一支没有输入框，如实记录 absent）
if (readings.open.answerPresent) {
  await page.locator(`${CARD_SEL} .ta-qd-answer-input`).fill(ANSWER_TEXT);
  await sleep(700);
  readings.typed = await page.evaluate(READ_OPEN);
  await emit('02-answer-typed');
  const before = sentFrames.length;
  await page.locator(`${CARD_SEL} .ta-qd-answer-send`).click();
  await sleep(900);
  readings.submit = {
    framesBefore: before,
    userFrames: sentFrames.map((r) => { try { return JSON.parse(r); } catch { return null; } })
      .filter((m) => m && typeof m.content === 'string' && m.sessionId === SID).map((m) => m.content),
    after: await page.evaluate(READ_OPEN),
  };
  await emit('03-answer-sent');
} else {
  readings.submit = { absent: true, note: '该树卡内没有回答输入框（未修源码）' };
}

// ③ 卡片内下滑看长正文（输入框应当仍在）
await touchDrag(page, Math.round(VW / 2), 300, 0, -170, 10, 22);
await sleep(700);
readings.afterScroll = await page.evaluate(READ_OPEN);
await emit('04-content-scrolled');

// ④ 左滑切下一条
await touchDrag(page, Math.round(VW / 2), 220, -140, 0, 10, 26);
await sleep(900);
readings.afterSwipe = await page.evaluate(READ_OPEN);
readings.afterSwipe.itemId = await page.evaluate(() => {
  const c = document.querySelector('.ta-qd-card');
  return c ? (c.dataset.itemId || null) : null;
});
await emit('05-after-left-swipe');

// ⑤ 返回列表页
await page.locator(`${CARD_SEL} .mobile-back-btn`).click();
await sleep(900);
await cdp.send('Page.stopScreencast');
await cdp.detach();

frames.forEach((b64, i) => writeFileSync(join(FRAMES, `${String(i).padStart(4, '0')}.jpg`), Buffer.from(b64, 'base64')));
const ff = (args) => execFileSync(args[0], args.slice(1), { stdio: ['ignore', 'pipe', 'pipe'] }).toString();
const gif = join(OUT, `${MIN}-decisioncard.gif`);
const mp4 = join(OUT, `${MIN}-decisioncard.mp4`);
ff(['ffmpeg', '-y', '-loglevel', 'error', '-framerate', '10', '-i', join(FRAMES, '%04d.jpg'),
  '-vf', 'fps=10,scale=280:-2:flags=lanczos,split[a][b];[a]palettegen=max_colors=96[p];[b][p]paletteuse=dither=bayer:bayer_scale=3', gif]);
ff(['ffmpeg', '-y', '-loglevel', 'error', '-framerate', '10', '-i', join(FRAMES, '%04d.jpg'),
  '-vf', 'fps=30,scale=750:-2:flags=lanczos', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '24', mp4]);
const fprobe = (p) => ff(['ffprobe', '-v', 'error', '-select_streams', 'v:0',
  '-show_entries', 'stream=width,height,avg_frame_rate,nb_read_packets', '-show_entries', 'format=duration,size,format_name', '-of', 'json', p]);

readings.media = {
  frames: frames.length,
  gif: { name: `${MIN}-decisioncard.gif`, bytes: readFileSync(gif).length, probe: JSON.parse(fprobe(gif)) },
  mp4: { name: `${MIN}-decisioncard.mp4`, bytes: readFileSync(mp4).length, probe: JSON.parse(fprobe(mp4)) },
  capBytes: MEDIA_CAP,
  withinCap: readFileSync(gif).length <= MEDIA_CAP && readFileSync(mp4).length <= MEDIA_CAP,
};
readings.pageErrors = pageErrors;

writeFileSync(join(OUT, `${MIN}-readings.json`), JSON.stringify(readings, null, 2) + '\n');
console.log(`[${MIN}] web=${WEB}`);
console.log(`[${MIN}] 首屏：问题「${readings.open.questionText}」 卡内可见=${readings.open.questionVisibleInCard}`
  + ` 回答框在场=${readings.open.answerPresent} 卡内=${readings.open.answerInCard} 不被dock截断=${readings.open.answerAboveDock}`);
if (readings.submit && readings.submit.userFrames) console.log(`[${MIN}] 提交出帧：${JSON.stringify(readings.submit.userFrames)}`);
console.log(`[${MIN}] GIF ${gif} (${readings.media.gif.bytes} bytes)`);
console.log(`[${MIN}] MP4 ${mp4} (${readings.media.mp4.bytes} bytes)`);
console.log(`[${MIN}] probe GIF ${JSON.stringify(readings.media.gif.probe.streams[0])} / MP4 ${JSON.stringify(readings.media.mp4.probe.streams[0])}`);

await ctx.close();
await browser.close();
process.exit(0);
