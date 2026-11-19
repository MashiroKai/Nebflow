#!/usr/bin/env node
// thinking-residue-terminal.spec.mjs — 修复批「回合终态后思考流式残片清理」（2026-10-29
// 13:59 作者报障逐字：「你已经完成回复了 但是下面还是有一个思考过程流式卡在那」，
// 手机截图：回复气泡 + 时间戳齐全，底部仍残留一条思考过程流式块，停在流式态永不收起）
// 验收。
//
// 报障 DOM 形态（截图判读）：`.nf-workline` 行（› chevron + 流式细节/思考单行揭示）
// 骑在**已完成轮**（turn-header + 正文气泡 + 时间戳全齐）下方 —— 面是终态**之后**
// 开出来的，永远等不到它的终态帧。
//
// 根因（客户端，引擎零触碰；三腿同族）：
//   ① 晚到帧闸不完整 —— `thinking` 段起点帧 / 工具族帧在终态后**零闸**可开新流式面
//      （startWorklineDots / renderToolPending 无 turn 在场判据）；thinkingDelta 闸以
//      `turnExpecting ∨ 在场面` 为通过条件，而 turnExpecting 有多处非真人派发的复活臂
//      （sessionBusy{true} / injected user 帧 / 权威 busy 重同步），复活后开的面若其
//      turn 终态帧丢失（断连窗）→ 面永久滞留流式态。
//   ② 终态清理缺腿 —— 终态处理只收指针在场的面（finishThinking/finishAi/collapseTurn），
//      没有「在场流式块全数 final 化」的 DOM 兜底；权威 idle 重同步
//      （sessionBusyStates{false}，composer-resync 批的终态事实）也不收面。
//   ③ 重连重放腿流式化 —— historyReplay/sidebar 从陈旧缓冲重建思考块时以**陈旧**
//      busySessionIds 判 `done`，重建面带 cursor + currentThinkingBubble 指针（流式态），
//      权威 idle 到达后无人收口。
//
// 修法（三腿各钉断言；判据面 = the RENDERED DOM / state 面，never the source wording）：
//   A 终态兜底 —— 在飞面 + 权威 idle（无 done 的重连重同步形态）⇒ 在场流式块全数
//      final 化：无 ：not(.thinking-done) 思考块、零 .cursor、零 .nf-workline。
//   B 晚到帧闸 —— 终态后到达的 `thinking` / thinkingDelta / toolStart 帧不开新流式面
//      （无新工作线、无新待命卡、无新思考块）、不重开计时段锚；thinkingDelta 丢弃腿
//      作回归钉（基线已绿）。
//   C 重放静态化 —— 断连重放腿从陈旧缓冲重建的思考块不带 cursor（历史渲染），
//      权威 idle 到场后整块 thinking-done（流式指针清零）。
//   D 守卫钉（基线不回退）—— live 思考流照常单行揭示、done 自动收拢（turn-header +
//      tucked）、完整轮照常出正文气泡 + 时长徽章。
//
// 场景（真模块图 + routeWebSocket 桩服务端，真用户动作驱动；harness 同
// thinking-timer-phase.spec.mjs 族）。截图落 NB_RESIDUE_SHOT_DIR（缺省
// evidence/thinking-residue-terminal/<RUN_TAG>/），红绿两跑即「前后对比截图」。
//
// Run: node tests/thinking-residue-terminal.spec.mjs
// 红腿（对修复前基线树，照 stream-ux.spec.mjs 头的 git archive 配方）：
//   mkdir -p /tmp/nb-residue-base
//   git -C <repo> archive main src/main/resources/web | tar -x -C /tmp/nb-residue-base
//   NB_UX_ROOT=/tmp/nb-residue-base RUN_TAG=baseline-red \
//     node tests/thinking-residue-terminal.spec.mjs
// 绿腿（本树）：node tests/thinking-residue-terminal.spec.mjs
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = process.env.NB_UX_ROOT
  ? join(process.env.NB_UX_ROOT, 'src', 'main', 'resources', 'web')
  : join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SID = 's-think-residue';
const RUN_TAG = process.env.RUN_TAG || 'fixed-green';
const SHOT_DIR = process.env.NB_RESIDUE_SHOT_DIR ||
  join(dirname(fileURLToPath(import.meta.url)), '..', 'evidence', 'thinking-residue-terminal', RUN_TAG);

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

// ── 桩服务端状态 ────────────────────────────────────────────────────
let currentWs = null;
const conns = [];
// getHistory 的可控应答（重连历史整替腿剧本）；null = 空页兜底。
let historyReply = null;
// getSessionBusy 的可控应答（权威 busy 重同步剧本）；null = 全闲兜底。
let busyReply = null;

function wireUp(ws) {
  currentWs = ws;
  const log = { inbound: [] };
  conns.push(log);
  ws.onMessage(raw => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    log.inbound.push(m);
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getHistory') {
      const reply = historyReply || { type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 };
      ws.send(JSON.stringify(reply));
      return;
    }
    if (m.type === 'getSessionBusy') {
      const states = busyReply || { [(m.sessionIds && m.sessionIds[0]) || SID]: false };
      ws.send(JSON.stringify({ type: 'sessionBusyStates', states }));
      return;
    }
  });
  // 引导帧（保持应用安静 + 挂上被测会话）
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{},"console":{"inputMode":"queue"}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '思考残片E2E' }], activeId: SID, folders: [] }));
}

/** 服务端注入任意帧（模拟引擎事件流）。 */
const serverSends = (frames) => {
  for (const f of frames) currentWs.send(JSON.stringify(f));
};

// ── 静态服务器 + 浏览器 ────────────────────────────────────────────────
const server = createServer(async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = normalize(join(WEB, path === '/' ? 'index.html' : path));
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{}'); // /api/* 探针一律 200 空对象（boot 静音）
  }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

// ── DOM 读数（形态无关：只读既有类名计数，不写被测代码新引入的名字）────
const READ = {
  strayThinking: `document.querySelectorAll('#chat .thinking-bubble:not(.thinking-done)').length`,
  cursors: `document.querySelectorAll('#chat .cursor').length`,
  worklines: `document.querySelectorAll('#chat .nf-workline').length`,
  pendingCards: `document.querySelectorAll('#chat .tool-card--pending').length`,
  headers: `document.querySelectorAll('#chat .turn-header').length`,
  aiBubbles: `document.querySelectorAll('#chat .bubble.ai:not(.thinking-bubble)').length`,
  thinkingRows: `document.querySelectorAll('#chat .row.thinking-row').length`,
  thinkRevealText: `(() => { const el = document.querySelector('#chat .nf-workline .nf-wl-think'); return el ? (el._nfText || el.textContent || '') : null; })()`,
};

const browser = await chromium.launch();
try {
  await mkdir(SHOT_DIR, { recursive: true });

  // ── 场景公共驱动 ────────────────────────────────────────────────
  const newPage = async () => {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    await ctx.addInitScript(() => {
      localStorage.setItem('nebflow_token', 't');
      localStorage.setItem('nebflow_locale', 'zh-CN');
      localStorage.setItem('stand_input_history', '[]');
    });
    const page = await ctx.newPage();
    const pageErrors = [];
    page.on('pageerror', e => pageErrors.push(e.message));
    await page.route('**/api/**', r => r.fulfill({ json: {} }));
    await page.routeWebSocket(/\/ws/, ws => wireUp(ws));
    await page.goto(BASE + '/index.html');
    await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
    await sleep(1200); // 引导帧消化（sessionList → 视图挂载）
    return { ctx, page, pageErrors };
  };
  const sendText = async (page, text) => {
    await page.fill('#input', text);
    await page.keyboard.press('Enter');
  };
  const counts = (page) => page.evaluate(`(() => { const R = ${JSON.stringify(READ)}; const o = {}; for (const k in R) o[k] = eval(R[k]); return o; })()`);
  const phaseAt = (page) => page.evaluate(async (sid) => {
    const s = await import('/js/state.js');
    return s.default.phaseStartAt[sid] || 0;
  }, SID);
  const busyHas = (page) => page.evaluate(async (sid) => {
    const s = await import('/js/state.js');
    return s.default.busySessionIds.has(sid);
  }, SID);
  const expecting = (page) => page.evaluate(async (sid) => {
    const s = await import('/js/state.js');
    return !!s.default.turnExpecting[sid];
  }, SID);
  const shot = (page, name) => page.screenshot({ path: join(SHOT_DIR, name), fullPage: false });

  // ══ D 守卫钉：完整 live 轮照常（闸不杀真流）════════════════════════
  {
    const { ctx, page, pageErrors } = await newPage();
    await sendText(page, '完整轮守卫：思考→正文→done');
    await sleep(200);
    serverSends([
      { type: 'sessionBusy', sessionId: SID, busy: true },
      { type: 'thinking', sessionId: SID },
      { type: 'thinkingDelta', sessionId: SID, delta: '守卫钉的思考流，必须在飞可见。' },
    ]);
    await sleep(400);
    const live = await counts(page);
    ok('D1 live 思考流照常单行揭示（工作线在飞）', live.worklines === 1 && !!live.thinkRevealText,
      `worklines=${live.worklines} reveal=${JSON.stringify((live.thinkRevealText || '').slice(0, 12))}`);
    serverSends([
      { type: 'textDelta', sessionId: SID, delta: '守卫钉正文。' },
      { type: 'textDone', sessionId: SID },
      { type: 'done', sessionId: SID, model: 'zhipu/glm-5.3-flash', durationMs: 47000 },
    ]);
    await sleep(400);
    const doneC = await counts(page);
    ok('D2 done 自动收拢：turn-header 在场 + 工作线消费', doneC.headers >= 1 && doneC.worklines === 0,
      `headers=${doneC.headers} worklines=${doneC.worklines}`);
    ok('D3 完整轮出正文气泡（流式面照常转正）', doneC.aiBubbles >= 1, `aiBubbles=${doneC.aiBubbles}`);
    ok('D4 终态后零流式残片（正常终态的干净面）',
      doneC.strayThinking === 0 && doneC.cursors === 0,
      `stray=${doneC.strayThinking} cursors=${doneC.cursors}`);
    ok('D5 全程零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
    await ctx.close();
  }

  // ══ A 终态兜底：在飞面 + 权威 idle（无 done 的重连重同步形态）═════════
  {
    const { ctx, page, pageErrors } = await newPage();
    await sendText(page, 'A 终态兜底：权威 idle 收口在飞面');
    await sleep(200);
    serverSends([
      { type: 'sessionBusy', sessionId: SID, busy: true },
      { type: 'thinking', sessionId: SID },
      { type: 'thinkingDelta', sessionId: SID, delta: '在飞思考：权威 idle 到场前这一面必须还活着。' },
    ]);
    await sleep(400);
    const pre = await counts(page);
    ok('A1 在飞面在场（对照：闸未误杀真流）', pre.worklines === 1 && pre.strayThinking === 1,
      `worklines=${pre.worklines} stray=${pre.strayThinking}`);
    await shot(page, 'A-before-authoritative-idle.png');
    // 权威 idle（composer-resync 批语义：AgentRegistry 快照 = 唯一权威重同步源）。
    // 断连窗内 done 丢失的形态：没有 done 帧，只有 sessionBusyStates{false}。
    serverSends([{ type: 'sessionBusyStates', states: { [SID]: false } }]);
    await sleep(400);
    const post = await counts(page);
    ok('A2 权威 idle 收口思考块（在场流式块全数 final 化）', post.strayThinking === 0,
      `stray=${post.strayThinking}`);
    ok('A3 权威 idle 扫尾游标（零 .cursor 残片）', post.cursors === 0, `cursors=${post.cursors}`);
    ok('A4 权威 idle 拆除在飞工作线（零 .nf-workline 残片）', post.worklines === 0,
      `worklines=${post.worklines}`);
    ok('A5 权威 idle 收 busy（composer 闲，基线既有行为不动）', !(await busyHas(page)));
    ok('A6 权威 idle 清陈旧思考缓冲（终态后无消费方）', await page.evaluate(async (sid) => {
      const s = await import('/js/state.js');
      return !s.default.sessionThinkingBuffers[sid];
    }, SID));
    await shot(page, 'A-after-authoritative-idle.png');
    ok('A7 全程零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
    await ctx.close();
  }

  // ══ B 晚到帧闸：终态后到达的流帧不开新面 ═════════════════════════
  {
    const { ctx, page, pageErrors } = await newPage();
    await sendText(page, 'B 晚到帧闸：终态后的迟到帧');
    await sleep(200);
    serverSends([
      { type: 'sessionBusy', sessionId: SID, busy: true },
      { type: 'thinking', sessionId: SID },
      { type: 'thinkingDelta', sessionId: SID, delta: 'B 场景在飞思考。' },
      { type: 'textDelta', sessionId: SID, delta: 'B 场景正文。' },
      { type: 'textDone', sessionId: SID },
      { type: 'done', sessionId: SID, model: 'zhipu/glm-5.3-flash', durationMs: 12000 },
    ]);
    await sleep(400);
    const settled = await counts(page);
    ok('B1 轮已终态（header 在场、工作线消费、门位拆）',
      settled.headers >= 1 && settled.worklines === 0 && !(await expecting(page)),
      `headers=${settled.headers} worklines=${settled.worklines}`);
    const phaseBefore = await phaseAt(page);
    // 终态后的迟到帧族：thinking 段起点 / thinkingDelta 增量 / 工具开卡。
    serverSends([
      { type: 'thinking', sessionId: SID },
      { type: 'thinkingDelta', sessionId: SID, delta: '迟到的思考增量：终态后不得再挂流式态。' },
      { type: 'toolStart', sessionId: SID, label: 'Task(迟到工具)' },
      { type: 'toolArgDelta', sessionId: SID, toolName: 'Task', delta: '{"prompt":"迟到参数"}' },
    ]);
    await sleep(400);
    const late = await counts(page);
    ok('B2 迟到 `thinking` 不开新工作线（晚到帧闸·段起点腿）', late.worklines === 0,
      `worklines=${late.worklines}`);
    ok('B3 迟到 thinkingDelta 不开新思考块（晚到帧闸·增量腿，基线已绿回归钉）',
      late.strayThinking === 0 && late.thinkingRows === settled.thinkingRows,
      `stray=${late.strayThinking} rows=${late.thinkingRows}/${settled.thinkingRows}`);
    ok('B4 迟到 toolStart 不开新待命卡（晚到帧闸·工具族腿）', late.pendingCards === 0,
      `pendingCards=${late.pendingCards}`);
    ok('B5 迟到帧不重开计时段锚（终态清锚不被残帧复活）', (await phaseAt(page)) === 0 && phaseBefore === 0,
      `phase=${await phaseAt(page)}`);
    ok('B6 迟到帧不复活 busy（composer 保持终态）', !(await busyHas(page)));
    await shot(page, 'B-after-late-frames.png');
    ok('B7 全程零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
    await ctx.close();
  }

  // ══ C 重放静态化：断连重放腿不挂流式态 ═══════════════════════════
  {
    const { ctx, page, pageErrors } = await newPage();
    await sendText(page, 'C 重放静态化：断连窗内的思考流');
    await sleep(200);
    serverSends([
      { type: 'sessionBusy', sessionId: SID, busy: true },
      { type: 'thinking', sessionId: SID },
      { type: 'thinkingDelta', sessionId: SID, delta: '断连前已收到的思考片段，将走陈旧缓冲重放腿。' },
    ]);
    await sleep(400);
    // 断线（真 ws.close → onclose → 重连循环 → onReconnect → getHistory/getSessionBusy）
    historyReply = {
      type: 'historyPage', sessionId: SID, offset: 0, total: 2, hasMore: false,
      messages: [
        { type: 'user', text: 'C 重放静态化：断连窗内的思考流', timestamp: 1769711460000 },
        {
          type: 'ai', text: '正文已完成（重放自后端历史）。',
          thinking: '后端已定稿的思考全文。',
          durationMs: 47000, model: 'zhipu/glm-5.3-flash', timestamp: 1769711507000,
          terminal: true,
        },
      ],
    };
    busyReply = { [SID]: false };
    await page.evaluate(async () => {
      const s = await import('/js/state.js');
      if (s.default.ws) s.default.ws.close();
    });
    await sleep(3500); // 重连退避 + historyPage 初载重放 + 权威 busy 重同步
    const rep = await counts(page);
    ok('C1 重放腿出完成轮面（header + 正文，历史照常）', rep.headers >= 1 && rep.aiBubbles >= 1,
      `headers=${rep.headers} aiBubbles=${rep.aiBubbles}`);
    ok('C2 重放思考块不带游标（历史渲染，不进流式管线）',
      rep.thinkingRows >= 1 && rep.cursors === 0,
      `thinkingRows=${rep.thinkingRows} cursors=${rep.cursors}`);
    ok('C3 权威 idle 后重放思考块终态化（零流式残面）', rep.strayThinking === 0,
      `stray=${rep.strayThinking}`);
    ok('C4 重放不落工作线残片', rep.worklines === 0, `worklines=${rep.worklines}`);
    ok('C5 权威 idle 清陈旧思考缓冲（残片无二次重放源）', await page.evaluate(async (sid) => {
      const s = await import('/js/state.js');
      return !s.default.sessionThinkingBuffers[sid];
    }, SID));
    await shot(page, 'C-after-replay-resync.png');
    ok('C6 全程零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
    await ctx.close();
  }

  // ══ 收尾 ══════════════════════════════════════════════════════════
  console.log(failures === 0 ? `\nALL PASS  (${RUN_TAG})` : `\n${failures} FAILURE(S)  (${RUN_TAG})`);
  process.exitCode = failures === 0 ? 0 : 1;
} finally {
  await browser.close();
  server.close();
}
