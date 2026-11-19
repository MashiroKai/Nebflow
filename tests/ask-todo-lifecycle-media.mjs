#!/usr/bin/env node
// tests/ask-todo-lifecycle-media.mjs — 溢出转待办批（2026-11-18 作者令·第二轮）的
// **动效/交互证据**：手机 375×812 真全屏 **暗 + 亮双档** 与 桌面 1280×800（默认深档）各段实录。
//
//   ① overflow-to-todo-entry  卡片超出对话分页 ⇒ **列表里出现同源待办条目**（无弹窗、无计数）
//   ② answer-in-entry         点开条目 ⇒ 条内问题卡作答 ⇒ 条目退场、原卡片已是已答
//   ③ concurrent-*            并发挂起：Q1 原卡片 / Q2 条目，正序 / 反序
//
// 载体 = **真实应用外壳**（index.html + 真实模块/CSS；scripts/lib/visual-harness.mjs
// 的 page.route 从磁盘供盘 + MockWS 走真实 ws.js 分发）——不是自搭的面板/夹具页。
// 零端口零子进程（起不来服务端 ⇒ 结构上不可能碰到宿主实例 6138/8080）。
//
// 帧路线：逐帧 PNG 序列（DPR=1 ⇒ 帧像素 = 视口像素）→ ffmpeg palettegen/paletteuse
// 出 GIF、libx264 出 MP4；ffprobe 实测像素/帧率/帧数/时长/字节。
//
// ⚠️ 口径申报（诚实边界）：WS 传输是**打桩**（无活引擎）——引擎侧 hub 的仲裁
//   （重复答复 ask-already-answered）、AskTodoBridge 的**引擎镜像条目**落库与终态
//   销账不在此证据内（本装置以同形 `scheduledTaskCreated` 帧注入镜像条目，载荷与
//   AskTodoBridge.attach 同形：name=`ask-<requestId>`、kind=todo、content=待答文案）。
//   reducedMotion=reduce（visual-harness 固定档），动效时长被压短，交互形态不变。
//
// 用法：node tests/ask-todo-lifecycle-media.mjs <outDir>
// 退出码：0 = 帧 + GIF + MP4 全落盘；2 = 装置错误。

import { mkdirSync, rmSync, existsSync, statSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { loadPlaywright, launchBrowser, createSession, settle } from '../scripts/lib/visual-harness.mjs';

const FPS = 8;
const TICK = 1000 / FPS;
const OUT = process.argv[2];
if (!OUT) { console.error('usage: node tests/ask-todo-lifecycle-media.mjs <outDir>'); process.exit(2); }
const SCENE_FILTER = (process.env.NB_MEDIA_SCENES || '').split(',').map(s => s.trim()).filter(Boolean);
const keepScene = (name) => SCENE_FILTER.length === 0 || SCENE_FILTER.some(f => name.includes(f));
const FFMPEG = ['/opt/homebrew/bin/ffmpeg', '/usr/local/bin/ffmpeg', 'ffmpeg'].find((p) => p === 'ffmpeg' || existsSync(p));
const FFPROBE = ['/opt/homebrew/bin/ffprobe', '/usr/local/bin/ffprobe', 'ffprobe'].find((p) => p === 'ffprobe' || existsSync(p));
if (!FFMPEG || !FFPROBE) { console.error('装置错误：找不到 ffmpeg/ffprobe'); process.exit(2); }

// ⚠️ 会话 id 前缀纪律：ws.js:1549 把 `ask-` 前缀的会话帧整体交给 canvas file-QA dock
// ——`ask-*` 的 historyPage 永远到不了主聊天视图。故夹具会话 id 取 `media-` 前缀。
const SID = 'media-todo-root';
const ASK1 = 'asknb-media-question-1';
const ASK2 = 'asknb-media-question-2';
const CQ1 = 'asknb-conc-q1';
const CQ2 = 'asknb-conc-q2';
const ASK_ITEMS = (n) => [{
  question: `第 ${n} 个问题：这一版先按哪个方向继续？`,
  options: [{ label: '方向 A' }, { label: '方向 B' }],
}];
const askMsg = (rid, n) => ({
  type: 'askUser', sessionId: SID, requestId: rid,
  agentName: 'Nebula', nodeName: 'dispatcher', project: 'Stand', items: ASK_ITEMS(n),
});
/** 引擎侧 Ask→待办镜像条目（与后端 AskTodoBridge.attach 广播载荷同形）。 */
const taskMsg = (rid, n) => ({
  type: 'scheduledTaskCreated', sessionId: SID,
  task: {
    id: 'task-' + rid, name: 'ask-' + rid, kind: 'todo',
    content: `[待答] 第 ${n} 个问题：这一版先按哪个方向继续？ — Nebula`,
    project: 'Stand', sessionId: SID, createdAt: 1763500100000, triggerAt: null,
  },
});

/** 真·产品单点读数：动态 import 产品模块（与产品 import 同 URL ⇒ 同一模块实例）。 */
const probeAsk = (page, ids) => page.evaluate(async (rids) => {
  const m = await import('/js/askPending.js');
  const badge = document.getElementById('pending-asks-indicator');
  const bar = document.getElementById('ask-pending-bar');
  const out = {
    count: m.pendingAskCount(),
    transferred: m.transferredAsks().map(e => e.requestId),
    inline: m.inlineAsks().map(e => e.requestId),
    placements: {},
    badgeVisible: badge ? !badge.classList.contains('hidden') : null,
    badgeCount: badge ? badge.querySelector('.pa-count').textContent : null,
    barRows: bar ? bar.querySelectorAll('.ask-pending-row').length : null,
    section: !!document.querySelector('.ask-todo-section'),
    // 「无弹窗」实测判据：弹层族可见元素计数（必须 0；防把高度 0 的常驻容器误计）。
    visiblePopups: [...document.querySelectorAll('[role="dialog"], .modal, .nebflow-modal, .ask-dock, .ask-overlay, .ask-modal')]
      .filter((e) => { const b = e.getBoundingClientRect(); return b.width > 0 && b.height > 0; }).length,
    cardStates: {},
  };
  for (const rid of rids) {
    out.placements[rid] = m.askPlacement(rid);
    const esc = window.CSS && CSS.escape ? CSS.escape(rid) : rid;
    const box = document.querySelector(`.option-box[data-request-id="${esc}"]`);
    out.cardStates[rid] = !box ? 'absent' : (box.classList.contains('resolved') ? 'locked' : 'open');
  }
  return out;
}, ids);

/** 待办面**条目**读数（对账）：条目 requestId / id / 行名；无上方区块。 */
const probeEntries = (page, host) => page.evaluate((which) => {
  const rows = which === 'mobile'
    ? [...document.querySelectorAll('.ta-items .ta-item[data-ask-entry]')]
    : [...document.querySelectorAll('#task-list .tfl-row-ask')];
  const entries = rows.map((r) => ({
    requestId: which === 'mobile' ? r.dataset.askEntry : r.dataset.askRequestId,
    id: r.dataset.itemId || r.dataset.id || '',
    title: (r.querySelector('.ta-item-title') || r.querySelector('.tfl-row-title') || {}).textContent || '',
  }));
  const cards = which === 'mobile'
    ? document.querySelectorAll('.ta-items .ask-todo-card').length
    : document.querySelectorAll('#task-list .ask-todo-card').length;
  const popupSel = '[role="dialog"], .modal, .nebflow-modal, .ask-dock, .ask-overlay, .ask-modal';
  const visiblePopups = [...document.querySelectorAll(popupSel)].filter((e) => {
    const b = e.getBoundingClientRect();
    if (!(b.width > 0 && b.height > 0)) return false;
    const cs = getComputedStyle(e);
    return cs.visibility !== 'hidden' && cs.display !== 'none';
  }).length;
  return { entries, cards, section: !!document.querySelector('.ask-todo-section'), visiblePopups };
}, host);

const state = { i: 0 };
const readings = {};

async function hold(page, dir, n) {
  for (let k = 0; k < n; k += 1) {
    const t0 = Date.now();
    await page.screenshot({ path: join(dir, `frame-${String(state.i).padStart(4, '0')}.png`) });
    state.i += 1;
    const rest = TICK - (Date.now() - t0);
    if (rest > 0) await page.waitForTimeout(rest);
  }
}

async function dockTo(page, id) {
  await page.locator(`#mobile-dock .dock-key[data-dock="${id}"]`).click();
  await page.waitForTimeout(280);
}

/** 手机端待办面入口 = 聊天根顶栏「任务」圆钮 → 「全部」卡 → 列表页（条目面）。 */
async function openMobileTodoList(page) {
  await dockTo(page, 'chat');
  await page.locator('[data-chrome="tasks"]').click();
  await page.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="tasks"]');
    return !!el && el.classList.contains('live') && !!document.querySelector('.ta-root');
  }, null, { timeout: 10000 });
  await page.waitForTimeout(220);
  await page.locator('.ta-card-all').click();
  await page.waitForFunction(() => !!document.querySelector('.ta-items'), null, { timeout: 8000 });
  await page.waitForTimeout(280);
}

/** 桌面待办面入口：挂载 todoFace + 点胶囊展开面板（条目在面板列表里）。 */
async function openDesktopTodo(p) {
  await p.evaluate(async (sid) => {
    const face = await import('/js/todoFace.js');
    try { face.renderTodoTaskList(sid); } catch { /* gate may decline */ }
    if (!document.querySelector('#task-list .tfl-capsule')) {
      const host = document.getElementById('task-list') || document.body;
      face.mountTodoFaceInto(host);
    }
  }, SID);
  await p.waitForTimeout(300);
  await p.locator('#task-list .tfl-capsule').first().click();
  await p.waitForFunction(() => {
    const el = document.querySelector('#task-list .tfl-list-inner');
    return !!el && el.getClientRects().length > 0;
  }, null, { timeout: 8000 });
  await p.waitForTimeout(300);
}

async function bootWithHistory(sess, sid, messages, expectRows, waitCard) {
  await sess.boot({ sessionId: sid, messages });
  await settle(sess.page, 200);
  await sess.page.waitForFunction((n) => document.querySelectorAll('#chat .row').length >= n, expectRows, { timeout: 8000 }).catch(() => {});
  if (waitCard) {
    const esc = waitCard.replace(/["\\]/g, '\\$&');
    await sess.page.waitForFunction(
      (sel) => !!document.querySelector(sel), `.option-box[data-request-id="${esc}"]`, { timeout: 8000 },
    ).catch(() => {});
  }
  return sess.page.evaluate(() => document.querySelectorAll('#chat .row').length);
}

function encode(dir, stem) {
  const gif = join(dir, `${stem}.gif`);
  const mp4 = join(dir, `${stem}.mp4`);
  const palette = join(dir, 'palette.png');
  execFileSync(FFMPEG, ['-y', '-loglevel', 'error', '-framerate', String(FPS), '-i', join(dir, 'frame-%04d.png'), '-vf', 'palettegen', palette]);
  execFileSync(FFMPEG, ['-y', '-loglevel', 'error', '-framerate', String(FPS), '-i', join(dir, 'frame-%04d.png'), '-i', palette, '-lavfi', 'paletteuse', '-loop', '0', gif]);
  execFileSync(FFMPEG, ['-y', '-loglevel', 'error', '-framerate', String(FPS), '-i', join(dir, 'frame-%04d.png'),
    '-vf', 'pad=ceil(iw/2)*2:ceil(ih/2)*2', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '18', '-r', String(FPS), mp4]);
  rmSync(palette, { force: true });
  const gifInfo = JSON.parse(execFileSync(FFPROBE, ['-v', 'error', '-select_streams', 'v:0', '-show_entries',
    'stream=width,height,r_frame_rate,nb_frames,duration,codec_name', '-show_entries', 'format=duration,size',
    '-of', 'json', gif], { encoding: 'utf8' }));
  const mp4Info = JSON.parse(execFileSync(FFPROBE, ['-v', 'error', '-select_streams', 'v:0', '-show_entries',
    'stream=width,height,r_frame_rate,nb_frames,duration', '-show_entries', 'format=duration,size', '-of', 'json', mp4], { encoding: 'utf8' }));
  return { gif, mp4, gifBytes: statSync(gif).size, mp4Bytes: statSync(mp4).size, gifProbe: gifInfo, mp4Probe: mp4Info, frames: state.i };
}

/** 宿主锚点：条目行选择器 + 问题卡作用域 + 条目读数面。 */
const mobileAnchors = {
  host: 'mobile',
  openTodo: openMobileTodoList,
  backToChat: (p) => dockTo(p, 'chat'),
  rowSel: (rid) => `.ta-items .ta-item[data-ask-entry="${rid}"] .ta-item-body`,
  cardSel: (rid) => `.ta-items .ask-todo-card[data-ask-todo-id="${rid}"]`,
};
const desktopAnchors = {
  host: 'desktop',
  openTodo: openDesktopTodo,
  backToChat: async (p) => { await settle(p, 100); },
  rowSel: (rid) => `#task-list .tfl-row-ask[data-ask-request-id="${rid}"] .tfl-row-main`,
  cardSel: (rid) => `#task-list .ask-todo-card[data-ask-todo-id="${rid}"]`,
};

const { mod } = loadPlaywright();
const { browser, label } = await launchBrowser(mod);
const scenes = [];

/** ① 溢出 ⇒ 列表里出现同源待办条目（无弹窗 / 无计数 / 无上方区块）。 */
async function flowOverflow(page, sess, dir, A) {
  await hold(page, dir, 6);
  const before = await probeAsk(page, [ASK1, ASK2]);
  await sess.frame({ type: 'pendingAsksSnapshot', asks: [askMsg(ASK1, 1), askMsg(ASK2, 2)] });
  await sess.frame(taskMsg(ASK1, 1));
  await sess.frame(taskMsg(ASK2, 2));
  await settle(page, 200);
  await hold(page, dir, 10);
  const afterArrive = await probeAsk(page, [ASK1, ASK2]);
  await A.openTodo(page);
  await hold(page, dir, 14);
  const inTodo = await probeEntries(page, A.host);
  return { before, afterArrive, inTodo };
}

/** ② 条目侧问题卡作答 ⇒ 条目退场 + 原卡片已答锁定态。 */
async function flowAnswer(page, sess, dir, A) {
  await sess.frame({ type: 'pendingAsksSnapshot', asks: [askMsg(ASK1, 1)] });
  await sess.frame(taskMsg(ASK1, 1));
  await settle(page, 200);
  await A.openTodo(page);
  await hold(page, dir, 6);
  // 真实 UI：点条目行体 → 行内问题卡展开 → 点选项 → 点提交。
  const expand = await expandRow(page, A.rowSel(ASK1));
  await page.waitForSelector(`${A.cardSel(ASK1)} .ask-todo-opt`, { timeout: 8000 });
  await hold(page, dir, 4);
  await page.locator(`${A.cardSel(ASK1)} .ask-todo-opt`).first().click();
  await hold(page, dir, 4);
  await page.locator(`${A.cardSel(ASK1)} .ask-todo-submit`).click();
  await settle(page, 200);
  await hold(page, dir, 6);
  const afterAnswer = await probeEntries(page, A.host);
  const sent = await page.evaluate(() => (window.__wsSent || []).filter(f => f.type === 'askUserAnswer'));
  // 重开对话（刷新后重开边界）：引擎按记忆把该提问作为**已答历史行**回放。
  await A.backToChat(page);
  await bootWithHistory(sess, SID, [
    { type: 'user', text: '先看一下仓库状态', timestamp: 1763500000000 },
    { type: 'askUser', items: ASK_ITEMS(1), requestId: ASK1, agentName: 'Nebula', timestamp: 1763500010000 },
    { type: 'user', text: '方向 A', answerOf: ASK1, timestamp: 1763500060000 },
    { type: 'ai', text: '好的，按方向 A 继续。', durationMs: 1200, model: 'media-fixture', timestamp: 1763500070000 },
  ], 3, ASK1);
  await settle(page, 250);
  await page.waitForTimeout(200);
  await hold(page, dir, 16);
  const cardState = await page.evaluate((rid) => {
    const esc = window.CSS && CSS.escape ? CSS.escape(rid) : rid;
    const box = document.querySelector(`.option-box[data-request-id="${esc}"]`);
    if (!box) return { present: false };
    return { present: true, resolved: box.classList.contains('resolved'), answer: (box.querySelector('.option-answer') || {}).textContent || null };
  }, ASK1);
  return { afterAnswer, sent, cardState, expand };
}

/** 展开条目行体 = 打开行内问题卡。
 *  正常走真点（命中测试全开）；仅当「仍在对话面的那张卡」的顶部弹窗条（本场景正常
 *  可见）恰好压在宿主的待办面板上（桌面 1280×800 无消息时代理布局）时，退化为对该
 *  行元素**派发真实 click 事件**（同一监听、同一处理器，只跳过命中测试）。
 *  @returns {Promise<'click'|'dispatch'>} */
async function expandRow(page, sel) {
  const loc = page.locator(sel);
  try {
    await loc.click({ timeout: 3000 });
    return 'click';
  } catch {
    await loc.dispatchEvent('click');
    return 'dispatch';
  }
}

/** 卡内作答腿（真实 UI）。 */
async function answerOnCard(page, rid) {
  const box = `.option-box[data-request-id="${rid}"]`;
  await page.locator(`${box} .option-btn`).first().click();
  await page.locator(`${box} .option-confirm`).click();
}
/** 条目侧作答腿（真实 UI）：点行体展开 → 点选项 → 点提交。 */
async function answerInEntry(page, rid, A) {
  const expand = await expandRow(page, A.rowSel(rid));
  await page.waitForSelector(`${A.cardSel(rid)} .ask-todo-opt`, { timeout: 8000 });
  await page.locator(`${A.cardSel(rid)} .ask-todo-opt`).first().click();
  await page.locator(`${A.cardSel(rid)} .ask-todo-submit`).click();
  return expand;
}

/** ③ 并发挂起：Q1 原卡片面（可答）/ Q2 条目，正序 / 反序。 */
async function flowConcurrent(page, sess, dir, A) {
  await sess.frame(askMsg(CQ1, 1));
  await sess.frame(taskMsg(CQ1, 1));
  await settle(page, 200);
  await hold(page, dir, 6);
  await sess.frame({ type: 'pendingAsksSnapshot', asks: [askMsg(CQ1, 1), askMsg(CQ2, 2)] });
  await sess.frame(taskMsg(CQ2, 2));
  await settle(page, 200);
  await hold(page, dir, 8);
  const coexisting = await probeAsk(page, [CQ1, CQ2]);

  const answerCardPhase = async () => {
    await page.locator(`.option-box[data-request-id="${CQ1}"]`).scrollIntoViewIfNeeded().catch(() => {});
    await hold(page, dir, 4);
    await answerOnCard(page, CQ1);
    await settle(page, 200);
    await hold(page, dir, 6);
    return probeAsk(page, [CQ1, CQ2]);
  };
  const answerEntryPhase = async () => {
    await A.openTodo(page);
    await hold(page, dir, 8);
    const inTodo = await probeEntries(page, A.host);
    const expand = await answerInEntry(page, CQ2, A);
    await settle(page, 200);
    await hold(page, dir, 6);
    return { inTodo, expand, afterTodo: await probeAsk(page, [CQ1, CQ2]) };
  };

  let afterCard, afterTodoStep;
  if (A.order === 'todo-first') {
    afterTodoStep = await answerEntryPhase();
    await A.backToChat(page);
    await hold(page, dir, 4);
    afterCard = await answerCardPhase();
  } else {
    await A.backToChat(page);
    await hold(page, dir, 4);
    afterCard = await answerCardPhase();
    afterTodoStep = await answerEntryPhase();
  }
  await A.backToChat(page);
  await hold(page, dir, 6);
  const finalChat = await probeAsk(page, [CQ1, CQ2]);
  const sent = await page.evaluate(() => (window.__wsSent || []).filter(f => f.type === 'askUserAnswer')
    .map(f => ({ requestId: f.requestId, answers: f.answers })));
  return { order: A.order, coexisting, afterCard, ...afterTodoStep, finalChat, sent };
}

async function page_wait(page) {
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer') || window.innerWidth > 768, null, { timeout: 20000 }).catch(() => {});
  await page.waitForTimeout(400);
}

// 主题档：手机 375×812 **暗亮双档**（作者令「手机真全屏 375×812 暗亮」）；
// 桌面 1280×800 走默认深档（桌面证据面是几何零回归，逐值比对见 geometry/）。
const VIEWS = [
  { view: 'mobile-375x812', w: 375, h: 812, anchors: mobileAnchors, schemes: ['dark', 'light'] },
  { view: 'desktop-1280x800', w: 1280, h: 800, anchors: desktopAnchors, schemes: ['dark'] },
];

try {
  for (const V of VIEWS) {
    for (const scheme of V.schemes) {
      for (const [name, fn] of [
        ['flow1-overflow-to-todo-entry', flowOverflow],
        ['flow2-answer-in-entry', flowAnswer],
      ]) {
        const scene = `${V.view}-${scheme}-${name}`;
        if (!keepScene(scene)) continue;
        const dir = join(OUT, scene, 'frames');
        mkdirSync(dir, { recursive: true });
        state.i = 0;
        const sess = await createSession(browser, { width: V.w, height: V.h, locale: 'zh-CN', colorScheme: scheme });
        let reading;
        try {
          await sess.boot({ sessionId: SID, messages: [] });
          await page_wait(sess.page);
          reading = await fn(sess.page, sess, dir, V.anchors);
        } finally {
          await sess.close();
        }
        const media = encode(dir, scene);
        media.reading = reading;
        media.pageErrors = sess.errors;
        scenes.push({ scene, ...media });
      }
    }
  }

  for (const V of VIEWS) {
    for (const scheme of V.schemes) {
      for (const order of ['card-first', 'todo-first']) {
        const scene = `${V.view}-${scheme}-flow3-concurrent-${order}`;
        if (!keepScene(scene)) continue;
        const dir = join(OUT, scene, 'frames');
        mkdirSync(dir, { recursive: true });
        state.i = 0;
        const sess = await createSession(browser, { width: V.w, height: V.h, locale: 'zh-CN', colorScheme: scheme });
        let reading;
        try {
          await sess.boot({ sessionId: SID, messages: [] });
          await page_wait(sess.page);
          reading = await flowConcurrent(sess.page, sess, dir, { ...V.anchors, order });
        } finally {
          await sess.close();
        }
        const media = encode(dir, scene);
        media.reading = reading;
        media.pageErrors = sess.errors;
        scenes.push({ scene, ...media });
      }
    }
  }
} finally {
  await browser.close();
}

const report = { fps: FPS, label, readings: {} };
for (const s of scenes) {
  report.readings[s.scene] = {
    frames: s.frames,
    gif: { path: s.gif, bytes: s.gifBytes, probe: s.gifProbe.streams?.[0], format: s.gifProbe.format },
    mp4: { path: s.mp4, bytes: s.mp4Bytes, probe: s.mp4Probe.streams?.[0], format: s.mp4Probe.format },
    reading: s.reading,
    pageErrors: s.pageErrors,
  };
}
writeFileSync(join(OUT, 'readings.json'), JSON.stringify(report, null, 2));
let failures = 0;
for (const s of scenes) {
  const st = report.readings[s.scene];
  const gifTooBig = st.gif.bytes > 10 * 1024 * 1024;
  console.log(`SCENE ${s.scene}`);
  console.log(`  frames=${st.frames} fps=${FPS} gif=${st.gif.probe?.width}x${st.gif.probe?.height} ${st.gif.probe?.r_frame_rate} dur=${st.gif.format?.duration}s ${(st.gif.bytes / 1048576).toFixed(2)}MB${gifTooBig ? ' (>10MB ⇒ 交付用 MP4)' : ''}`);
  console.log(`  mp4=${st.mp4.probe?.width}x${st.mp4.probe?.height} ${st.mp4.probe?.r_frame_rate} dur=${st.mp4.format?.duration}s ${(st.mp4.bytes / 1048576).toFixed(2)}MB`);
  if (st.pageErrors.length) { console.log(`  pageErrors=${JSON.stringify(st.pageErrors)}`); failures++; }
  const r = st.reading;
  if (r && r.afterArrive) {
    console.log(`  overflow: barRows=${r.afterArrive.barRows} badgeVisible=${r.afterArrive.badgeVisible} badgeCount=${r.afterArrive.badgeCount} section=${r.afterArrive.section}`);
    console.log(`  entries: ${JSON.stringify(r.inTodo.entries)} cards=${r.inTodo.cards} section=${r.inTodo.section} visiblePopups=${r.inTodo.visiblePopups}`);
    const perEntry = r.inTodo.entries.map(e => e.requestId).sort().join(',');
    if (r.afterArrive.barRows !== 0 || r.afterArrive.badgeVisible !== false
      || r.afterArrive.visiblePopups !== 0 || r.afterArrive.section
      || r.inTodo.section || r.inTodo.visiblePopups !== 0
      || perEntry !== [ASK1, ASK2].sort().join(',')
      || r.inTodo.entries.some(e => !e.id || !e.title.includes('[待答]'))) {
      console.log('  ❌ 溢出场景断言未过'); failures++;
    }
  }
  if (r && r.afterAnswer) {
    console.log(`  answered: entries=${JSON.stringify(r.afterAnswer.entries)} cards=${r.afterAnswer.cards} visiblePopups=${r.afterAnswer.visiblePopups} rowExpand=${r.expand} sent=${JSON.stringify(r.sent)}`);
    console.log(`  cardAfterReload=${JSON.stringify(r.cardState)}`);
    if (r.afterAnswer.entries.length !== 0 || r.afterAnswer.cards !== 0 || r.afterAnswer.section
      || r.afterAnswer.visiblePopups !== 0 || (r.sent || []).length !== 1
      || !(r.cardState && r.cardState.present && r.cardState.resolved)) {
      console.log('  ❌ 作答场景断言未过'); failures++;
    }
  }
  if (r && r.coexisting) {
    console.log(`  coexisting: count=${r.coexisting.count} placements=${JSON.stringify(r.coexisting.placements)} transferred=${JSON.stringify(r.coexisting.transferred)} barRows=${r.coexisting.barRows}`);
    console.log(`  inTodo:     entries=${JSON.stringify(r.inTodo.entries.map(e => e.requestId))} entryRowExpand=${r.expand}`);
    console.log(`  afterCard:  ${JSON.stringify(r.afterCard.cardStates)} count=${r.afterCard.count}`);
    console.log(`  afterTodo:  ${JSON.stringify(r.afterTodo.cardStates)} count=${r.afterTodo.count}`);
    console.log(`  finalChat:  ${JSON.stringify(r.finalChat.cardStates)} count=${r.finalChat.count} sent=${JSON.stringify(r.sent)}`);
    const c = r.coexisting, ac = r.afterCard, it = r.inTodo, at = r.afterTodo, fc = r.finalChat;
    const todoFirst = r.order === 'todo-first';
    const okCoexist = c.count === 2 && c.placements[CQ1] === 'inline' && c.placements[CQ2] === 'todo'
      && c.cardStates[CQ1] === 'open' && c.transferred.length === 1 && c.transferred[0] === CQ2 && c.barRows === 1 && !c.section;
    const okCard = ac.cardStates[CQ1] === 'locked' && fc.cardStates[CQ1] === 'locked';
    const okEntry = it.entries.length === 1 && it.entries[0].requestId === CQ2 && at.cardStates[CQ2] === 'absent';
    const okOrder = todoFirst
      ? (at.count === 1 && at.cardStates[CQ1] === 'open')
      : (ac.count === 1 && ac.transferred.length === 1 && ac.transferred[0] === CQ2);
    const okFinal = fc.count === 0;
    const sentSet = (r.sent || []).map(x => x.requestId + ':' + x.answers[0]).sort().join('|');
    const okSent = (r.sent || []).length === 2 && sentSet === [CQ1 + ':方向 A', CQ2 + ':方向 A'].slice().sort().join('|');
    if (!okCoexist || !okCard || !okEntry || !okOrder || !okFinal || !okSent) { console.log('  ❌ 并发场景断言未过'); failures++; }
  }
}
console.log(failures ? `FAIL ${failures} assertion(s)` : 'PASS all scenes');
process.exit(failures ? 1 : 0);
