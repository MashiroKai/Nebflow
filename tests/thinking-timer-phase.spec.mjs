#!/usr/bin/env node
// thinking-timer-phase.spec.mjs — 修复批「思考中计时显示失真」（2026-10-28 20:2x
// 作者报障逐字：「这个思考中的计时离得也太远了」，截图：思考中 1m 58s）验收。
//
// 报障：turn 明明已进行很久（当晚 provider 抖动、内部多次重试重连），turn 行的
// 「思考中」dots 计时却只显示 1m58s——疑似计时器在每个 LLM 尝试/重试边界被重置，
// 显示的是「当前尝试」耗时而非「该阶段」累计耗时。
//
// 根因（客户端，引擎零触碰）：dots 计时锚直接读 state.turnStartTimes——而它会被
// 在 turn 中途推进/删除：① textDone 经 consumeTurnDuration 消费删除，下一个流帧
// `if (!turnStartTimes)` 就地重盖 = now；② WS 重连腿 input.js rearmBusyQueueDrains
// 无条件重盖（turn-start 确认网对账基点）且重连历史初载重放整替 #chat（live dots
// 行被拆，下一帧按新锚重建）；③ 重试风暴里引擎重发 `thinking` 帧 → dots 项按重盖
// 后的锚重建。三腿都让显示变成「自最近一次尝试/重连以来」。
//
// 语义定准（作者裁定）：思考中计时显示**当前阶段的连续耗时**——从阶段开始累计，
// 含内部重试等待（用户不关心内部重试切分）；阶段多次尝试不重置、连续累计；阶段
// 切换才归零。修法 = 独立段锚 state.phaseStartAt（与 turnStartTimes 分账）：
//   盖戳 = 派发腿 / sessionBusy{busy:true} 空闲开轮 / roundComplete / 流帧族
//          phaseStampIfMissing（段未开时开段）；
//   清除 = clearBusyFor 终态帧族 + 工具边界（toolCallDetected/toolStart——工具
//          调用 = 上一 LLM 轮已完整产出，等待段终结）；
//   不触碰 = retryStatus / textDone / 单独 sessionBusy{false} / 重连重盖腿 / 历史
//          整替（锚在 state 存活，重建 dots 从原段起点继续计数）。
//
// 三面验收钉（本 spec）：
//   T1 内部重试不重置：重试风暴（retryStatus + `thinking` 重发 + thinkingDelta
//      重流）与重连历史整替（真实 replayHistoryPageInitialLoad 腿拆行重建）后，
//      段锚不变、显示从原段起点单调续走；
//   T2 阶段切换归零：工具边界 / roundComplete 后，下一段从 0 重计；
//   T3 显示单调递增：段内采样序列非降（不闪跳）；
//   T4 终态清锚 + 新段：done 清锚；重发新段新锚；空闲开轮 busy{true} 盖锚；
//      busy{true} 重申与单独 busy{false} 都不动锚（speculative/幻影帧不重置）。
//   E  静态钉：盖戳/清除/不触碰接缝在源面在场（含 rearmBusyQueueDrains 不碰锚）。
//
// 场景（真模块图 + routeWebSocket 桩服务端，真用户动作驱动；harness 同
// busy-lifecycle.spec.mjs 族）。
//
// Run: node tests/thinking-timer-phase.spec.mjs
// 自包含：静态服务器随机隔离端口 + routeWebSocket 桩，finally 必关。
// 变异安全：基线树（无 phaseStartAt / 锚仍读 turnStartTimes）上 T1/T2/T4 的
// 状态面断言必红（锚被重盖/缺失）；E 静态钉同理。红腿照 stream-ux.spec.mjs 头
// 的 git archive 配方导出基线树后对本文件直跑即可。
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SID = 's-think-timer';

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
// getHistory 的可控应答（重连历史整替腿换剧本用）；null = 空页兜底。
let historyReply = null;

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
  });
  // 引导帧（保持应用安静 + 挂上被测会话）
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{},"console":{"inputMode":"queue"}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '思考计时E2E' }], activeId: SID, folders: [] }));
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

// 「思考中」dots 计时读数：hint 文本（formatLive 形态 `42s` / `1m 03s`）→ 秒。
const HINT_SEC = `(() => {
  const el = document.querySelector('#chat .nf-workline .nf-wl-hint');
  if (!el) return null;
  const m = (el.textContent || '').match(/(?:(\\d+)m\\s*)?(\\d+)s/);
  if (!m) return null;
  return (m[1] ? +m[1] * 60 : 0) + (+m[2]);
})()`;
const dotsVisible = `!!document.querySelector('#chat .nf-workline .nf-wl-dots')`;

const browser = await chromium.launch();
let ctx = null;
try {
  ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
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
  await sleep(1500); // 引导帧消化（sessionList → 视图挂载）

  const phaseAt = () => page.evaluate(async (sid) => {
    const s = await import('/js/state.js');
    return s.default.phaseStartAt[sid] || 0;
  }, SID);
  const hintSec = () => page.evaluate(HINT_SEC);
  const hasDots = () => page.evaluate(dotsVisible);
  const sendText = async (text) => {
    await page.fill('#input', text);
    await page.keyboard.press('Enter');
  };
  /** 重试风暴注入：retryStatus ×2 + `thinking` 重发 + thinkingDelta 重流 +
   *  再一次 `thinking`（尝试边界的三种典型帧形）。 */
  const retryStorm = () => serverSends([
    { type: 'retryStatus', sessionId: SID, message: 'provider/dead-model failed, retrying...' },
    { type: 'thinking', sessionId: SID },
    { type: 'thinkingDelta', sessionId: SID, delta: '重流的第一段思考……' },
    { type: 'retryStatus', sessionId: SID, message: '所有模型不可用，等待恢复中...' },
    { type: 'thinking', sessionId: SID },
  ]);

  ok('boot 后在线', conns.length > 0);

  // ══ T1 内部重试不重置 + 显示单调递增 ══════════════════════════════
  // 真人派发开 turn → 引擎开轮（busy{true} + thinking）→ dots 段开跑。
  await sendText('想想再答（会遇上一场重试风暴）');
  await sleep(300);
  serverSends([
    { type: 'sessionBusy', sessionId: SID, busy: true },
    { type: 'thinking', sessionId: SID },
  ]);
  await sleep(400);
  const t1Anchor0 = await phaseAt();
  ok('T1.1 派发开段：段锚已盖（>0）', t1Anchor0 > 0, `anchor=${t1Anchor0}`);
  ok('T1.2 dots 项在行上（计时面在场）', await hasDots());

  // 风暴期间采样：hint 秒数序列必须非降（不闪跳、不回跳）。
  await retryStorm();
  const samples = [];
  for (let i = 0; i < 7; i++) {
    samples.push({ t: await hintSec(), a: await phaseAt() });
    if (i < 2) await retryStorm(); // 采样窗内再打两轮风暴
    await sleep(420);
  }
  const secs = samples.map(s => s.t);
  ok('T1.3 风暴 + 采样窗内段锚零移动（内部重试不重置·状态面）',
    samples.every(s => s.a === t1Anchor0),
    `anchors=${JSON.stringify([...new Set(samples.map(s => s.a))])}`);
  ok('T1.4 显示读数全程可读且非降（单调递增面）',
    secs.every(s => s !== null) && secs.every((s, i) => i === 0 || s >= secs[i - 1]),
    `series=${JSON.stringify(secs)}`);
  ok('T1.5 计时确实在走（末读数 ≥ 1s，非冻结）', (secs[secs.length - 1] || 0) >= 1,
    `last=${secs[secs.length - 1]}`);
  const preTextDoneSec = secs[secs.length - 1];

  // 报障主力腿：尝试带正文后死亡——textDone（时长徽章挂点，consumeTurnDuration
  // 消费 turnStartTimes）到场后引擎重试重流、`thinking` 重发重建 dots。旧实现把
  // 锚读回 turnStartTimes：此刻它已被消费、下一流帧就地重盖 = now ⇒ 计时归零重
  // 数（=「思考中 1m58s」的形态）。段锚必须原样存活，显示从原段起点续走。
  serverSends([
    { type: 'textDelta', sessionId: SID, delta: '部分正文（本次尝试的半截回复）……' },
    { type: 'textDone', sessionId: SID },
  ]);
  await sleep(300);
  ok('T1.6 textDone（时长徽章消费 turnStartTimes）不动段锚',
    (await phaseAt()) === t1Anchor0, `anchor=${await phaseAt()}`);
  retryStorm();
  await sleep(400);
  const postTdSec = await hintSec();
  ok('T1.6b 正文重试边界后显示仍从原段续走（≥ 风暴前读数，旧实现必红）',
    postTdSec !== null && postTdSec >= preTextDoneSec,
    `pre=${preTextDoneSec} post=${postTdSec}`);
  const preNukeSec = postTdSec;

  // 重连腿：历史初载重放整替 #chat（onReconnect → pendingInitialLoad=true →
  // getHistory → replayHistoryPageInitialLoad wipe 重建）——live dots 行被拆。
  await page.evaluate(async (sid) => {
    const s = await import('/js/state.js');
    const vMod = await import('/js/chatView.js');
    const view = vMod.findViewBySessionId(sid) || vMod.activeView;
    view.pagination.pendingInitialLoad = true;
  }, SID);
  historyReply = {
    type: 'historyPage', sessionId: SID, offset: 0, total: 1, hasMore: false,
    messages: [{ type: 'user', text: '想想再答（会遇上一场重试风暴）', timestamp: Date.now() }],
  };
  await page.evaluate(async (sid) => {
    // 走真实出线：ws.js sendWs 语义 = 页面直发 getHistory（与 onReconnect 同帧形）。
    const { sendWs } = await import('/js/ws.js');
    sendWs({ type: 'getHistory', sessionId: sid, limit: 50 });
  }, SID);
  await sleep(900); // 重放腿异步挂载消化
  ok('T1.7 整替后 dots 行确被拆（前置成立：重建才有意义）', !(await hasDots()));
  // 下一帧 `thinking`（重连后引擎续流）→ dots 按存活段锚重建。
  serverSends([{ type: 'thinking', sessionId: SID }]);
  await sleep(400);
  const t1Anchor1 = await phaseAt();
  const rebuiltSec = await hintSec();
  ok('T1.8 整替重建后段锚不变（重连不构成阶段边界·状态面）', t1Anchor1 === t1Anchor0,
    `before=${t1Anchor0} after=${t1Anchor1}`);
  ok('T1.9 重建 dots 从原段起点续走（显示面，≥ 整替前读数）',
    rebuiltSec !== null && rebuiltSec >= preNukeSec,
    `pre=${preNukeSec} rebuilt=${rebuiltSec}`);
  ok('T1.10 全程零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));

  // ══ T2 阶段切换归零（工具边界） ═══════════════════════════════════
  serverSends([
    { type: 'toolCallDetected', sessionId: SID, name: 'Grep' },
    { type: 'toolStart', sessionId: SID, label: 'Grep("phaseStartAt")' },
  ]);
  await sleep(200);
  ok('T2.1 工具边界收段：锚已清（阶段真结束）', (await phaseAt()) === 0);
  serverSends([{ type: 'toolEnd', sessionId: SID, label: 'Grep("phaseStartAt")', summary: 'ok', content: '', isError: false, input: '{}' }]);
  await sleep(150);
  serverSends([{ type: 'thinking', sessionId: SID }]); // 下一轮首帧重开新段
  await sleep(400);
  const t2Anchor = await phaseAt();
  ok('T2.2 下一段在新轮首帧重开（新锚 ≈ now）', t2Anchor > t1Anchor0 + 2000, `anchor=${t2Anchor}`);
  const t2Sec = await hintSec();
  ok('T2.3 新段显示从 0 重计（阶段切换归零·显示面）', t2Sec !== null && t2Sec <= 2, `sec=${t2Sec}`);

  // ══ T3 阶段切换归零（roundComplete） ══════════════════════════════
  serverSends([{ type: 'roundComplete', sessionId: SID }]);
  await sleep(200);
  const t3Anchor = await phaseAt();
  ok('T3.1 roundComplete 重盖段锚（上一轮定稿 = 阶段边界）', t3Anchor > t2Anchor, `anchor=${t3Anchor}`);
  const t3Sec = await hintSec();
  ok('T3.2 roundComplete 重挂的 dots 从 0 重计', t3Sec !== null && t3Sec <= 2, `sec=${t3Sec}`);

  // ══ T4 终态清锚 + 新段 + 空闲开轮 / 幻影帧不动锚 ═════════════════
  serverSends([
    { type: 'done', sessionId: SID, model: 'mock-llm' },
    { type: 'sessionBusy', sessionId: SID, busy: false },
  ]);
  await sleep(300);
  ok('T4.1 终态清锚（done 经 clearBusyFor）', (await phaseAt()) === 0);
  const send2At = Date.now();
  await sendText('第二条消息（新段新锚）');
  await sleep(300);
  serverSends([
    { type: 'sessionBusy', sessionId: SID, busy: true },
    { type: 'thinking', sessionId: SID },
  ]);
  await sleep(400);
  const t4Anchor = await phaseAt();
  ok('T4.2 新派发开新段（新锚 ≈ 派发时刻）', t4Anchor >= send2At - 50 && t4Anchor <= Date.now(), `anchor=${t4Anchor}`);
  // 空闲开轮（引擎自主新 turn）：busy{true} 盖锚。先清场。
  serverSends([
    { type: 'done', sessionId: SID, model: 'mock-llm' },
    { type: 'sessionBusy', sessionId: SID, busy: false },
  ]);
  await sleep(300);
  const idleAt = Date.now();
  serverSends([
    { type: 'sessionBusy', sessionId: SID, busy: true },
    { type: 'thinking', sessionId: SID },
  ]);
  await sleep(400);
  const idleAnchor = await phaseAt();
  ok('T4.3 空闲开轮 busy{true} 盖新锚（引擎自主轮也是新段）', idleAnchor >= idleAt - 50, `anchor=${idleAnchor}`);
  // busy{true} 重申（同 turn 权威重发）不动锚；单独 busy{false}（终态对第二帧 /
  // speculative 幻影清除）也不动锚——重试风暴期间这两类帧不得把计时打回零。
  serverSends([
    { type: 'sessionBusy', sessionId: SID, busy: true },
    { type: 'sessionBusy', sessionId: SID, busy: false },
  ]);
  await sleep(300);
  ok('T4.4 busy{true} 重申与单独 busy{false} 都不动锚（保单调）', (await phaseAt()) === idleAnchor,
    `anchor=${await phaseAt()} expected=${idleAnchor}`);
  serverSends([{ type: 'done', sessionId: SID, model: 'mock-llm' }]);
  await sleep(200);
  ok('T4.5 done 终态清锚', (await phaseAt()) === 0);
  ok('T4.6 全程零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));

  // ══ E 静态钉：接缝在源面在场 ═════════════════════════════════════
  const mainSrc = await readFile(join(WEB, 'js', 'main.js'), 'utf8');
  const inputSrc = await readFile(join(WEB, 'js', 'input.js'), 'utf8');
  const stateSrc = await readFile(join(WEB, 'js', 'state.js'), 'utf8');
  const wlSrc = await readFile(join(WEB, 'js', 'workline.js'), 'utf8');
  ok('E1 state.js 声明段锚 phaseStartAt', /phaseStartAt:\s*\{\}/.test(stateSrc));
  ok('E2 dots 两处调用都读段锚（零 turnStartTimes 残留）',
    (mainSrc.match(/startWorklineDots\(activeView,\s*state\.phaseStartAt\[sid\]\)/g) || []).length === 2
    && !/startWorklineDots\(activeView,\s*state\.turnStartTimes/.test(mainSrc));
  ok('E3 流帧族段未开则开段（thinking/thinkingDelta/textDelta 三处）',
    (mainSrc.match(/phaseStampIfMissing\(sid\)/g) || []).length >= 3);
  ok('E4 roundComplete 盖新段 + 空闲开轮盖段',
    /phaseStamp\(sid\)/.test(mainSrc) && /!state\.busySessionIds\.has\(busySid\)\) phaseStamp\(busySid\)/.test(mainSrc));
  ok('E5 工具边界收段（toolCallDetected + toolStart）',
    /phaseClear\(sid\); \/\/ thinkingtimer-fix/.test(mainSrc) && /phaseClear\(toolStartSid\)/.test(mainSrc));
  ok('E6 终态帧族清锚挂 clearBusyFor（唯一必经点）',
    /function clearBusyFor\(msg\)[\s\S]{0,1400}phaseClear\(sid\)/.test(mainSrc));
  ok('E7 派发腿盖新段（send / offline 补发 / drain newTurn / 插件注入）',
    (inputSrc.match(/state\.phaseStartAt\[/g) || []).length >= 4);
  // rearmBusyQueueDrains 函数体内不得**写**段锚（重连腿不重置显示锚——报障重置源
  // 之一）。判写作面（`state.phaseStartAt[` = 赋值形态），函数体注释里的提法不算。
  const rearmBody = (inputSrc.match(/function rearmBusyQueueDrains\(\)[\s\S]*?\n\}/) || [''])[0];
  ok('E8 重连补发腿不写段锚', rearmBody.includes('rearmBusyQueueDrains') && !rearmBody.includes('state.phaseStartAt['),
    `bodyWritesAnchor=${rearmBody.includes('state.phaseStartAt[')}`);
  ok('E9 workline 消费面钉：dots 计时锚语义注在 startWorklineDots',
    /startWorklineDots\(view, startedAt\)/.test(wlSrc) && /phaseStartAt/.test(wlSrc));

  // ══ 收尾 ══════════════════════════════════════════════════════════
  console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURE(S)`);
} catch (e) {
  failures++;
  console.error('SPEC ERROR:', e);
} finally {
  await browser.close().catch(() => {});
  server.close();
}
process.exit(failures === 0 ? 0 : 1);
