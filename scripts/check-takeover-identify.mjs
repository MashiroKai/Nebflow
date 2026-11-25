#!/usr/bin/env node
// check-takeover-identify.mjs — 控制台单实例「身份必达 + 真多开=只读拒写」静态哨兵
//
// 两批合一（同一台机器，两个不可交付的失效面）：
//
// ① 身份必达（2026-11-03 作者报障批：手机端和电脑端同时在线，没有被挤掉）。
//    Why a static gate: the single-instance verdict only kicks when BOTH sides carry a
//    provable browser identity. The identity used to ride the WS handshake query
//    (`/ws?clientId=`), and that rail is NOT reliable on the console's real delivery
//    path (portal/tunnel leg or any proxy that rewrites the request target). Production
//    evidence 2026-11-03 10:07–10:30 (`stand.log`): seven consecutive console connections
//    registered with a session-id holder — `joined — no takeover (joining page carries no
//    client id (legacy cache))`. With no identity on either side the verdict is
//    Undecidable by design: no prompt, no kick.
//    The fix has two halves that MUST stay in lockstep, and neither half can be checked
//    by a compiler: client `web/js/ws.js` re-declares the identity IN-BAND on every open;
//    server `gateway/ConsoleTakeover.scala` owns the frame-type literal and the receive
//    pipe (`WebSocketRoutes`) intercepts it. A silent rename on either side reproduces the
//    reported bug exactly (wire-level, nobody notices) — this script is the machine that
//    notices.
//
// ② 真多开 = 静默接管 + 失权端自动落草稿（2026-11-19 作者令「直接静默吧 交给主会话
//    实现」；取代 2026-11-18 的「非抢占 = 第二个窗口只读」）。Console 卡片与状态机
//    后由 18b7da220 恢复；canvas 席位仍是独立路径，**申请即授予**：
//    已有别的写者时**静默接管**（翻位 + epoch 自增），被顶下的写者由前端静默落
//    「未合并草稿」承接「不丢」。失效面同样是静默的——把静默接管悄悄改回非抢占、
//    或把「定向通知被顶下的写者」这条腿删掉（失权端就再也收不到落草稿的信号了），
//    界面看着都「正常」，只有机检能发现。
//
// Assertions (exit 1 on any violation):
//   A. `IdentifyFrameType` literal exists in ConsoleTakeover.scala;
//   B. WebSocketRoutes intercepts that exact literal (isIdentifyFrame) and routes it
//      to `onIdentify`;
//   C. ws.js EMITS that exact literal with `clientId` taken from `deviceClientId()`;
//   D. ws.js still carries the handshake `?clientId=` rail (direct face keeps working);
//   F. automation declaration stays in lockstep (navigator.webdriver / declaredAutomated /
//      endprintOf+sameEnd) — a silent rename re-opens the false-positive face;
//   G. restored console retake card and state machine remain isolated from canvas;
//   H. write rejection carries a REAL save-failure message on the server, while the
//      client retains unsynced reader state silently (862f93c9e1);
//   I. the writer seat is a SILENT PREEMPTIVE seat (申请即授予 + 定向通知被顶下的写者).
//      Browser takeover viewer glue is a separate surface, allowed only in marked blocks;
//      the console takeover surface remains forbidden in canvas.
//
// Usage: node scripts/check-takeover-identify.mjs

import { existsSync, readFileSync } from 'node:fs';
import { stripBrowserTakeoverGlue } from './lib/browser-takeover-glue.mjs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const SCALA = join(ROOT, 'src', 'main', 'scala', 'nebflow', 'gateway');
const JS = join(ROOT, 'src', 'main', 'resources', 'web', 'js');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');

const failures = [];
const fail = (msg) => failures.push(msg);
const readIf = (p) => (existsSync(p) ? readFileSync(p, 'utf8') : null);

const takeoverSrc = readIf(join(SCALA, 'ConsoleTakeover.scala'));
const wsRoutesSrc = readIf(join(SCALA, 'WebSocketRoutes.scala'));
const configRoutesSrc = readIf(join(SCALA, 'ConfigRoutes.scala'));
const wsJs = readIf(join(JS, 'ws.js'));
const canvasJs = readIf(join(JS, 'canvas.js'));
const indexHtml = readIf(join(WEB, 'index.html'));

for (const [name, src] of [
  ['ConsoleTakeover.scala', takeoverSrc], ['WebSocketRoutes.scala', wsRoutesSrc],
  ['ConfigRoutes.scala', configRoutesSrc], ['ws.js', wsJs], ['canvas.js', canvasJs],
  ['index.html', indexHtml],
]) {
  if (src === null) fail(`missing required source: ${name}`);
}
if (failures.length) {
  console.error('check-takeover-identify: FAIL');
  for (const f of failures) console.error(`  · ${f}`);
  process.exit(1);
}

// ── A. the frame-type literal is owned by the companion object ──
const typeMatch = /val\s+IdentifyFrameType\s*:\s*String\s*=\s*"([^"]+)"/.exec(takeoverSrc);
if (!typeMatch) {
  fail('A: ConsoleTakeover.scala has no `val IdentifyFrameType: String = "…"` — the in-band frame type lost its single source');
}
const frameType = typeMatch ? typeMatch[1] : 'takeoverIdentify';

// ── B. the receive pipe intercepts that literal and routes it to onIdentify ──
if (!/def\s+isIdentifyFrame\s*\(/.test(takeoverSrc)) {
  fail('B: ConsoleTakeover.isIdentifyFrame is missing (no frame discriminator)');
}
if (!/def\s+onIdentify\s*\(/.test(takeoverSrc)) {
  fail('B: ConsoleTakeover.onIdentify is missing (the in-band identity leg was dropped)');
}
if (!/ConsoleTakeover\.isIdentifyFrame\s*\(/.test(wsRoutesSrc)) {
  fail('B: WebSocketRoutes no longer intercepts the identify frame — declared identity is silently discarded');
}
if (!/takeover\.onIdentify(Declared)?\s*\(/.test(wsRoutesSrc)) {
  fail('B: WebSocketRoutes does not call takeover.onIdentify — declared identity never reaches the registry');
}

// ── C. ws.js emits the SAME literal with the shared identity source ──
// 同端判据批（2026-11-07）：声明帧多带一个 `automated`（navigator.webdriver），因此
// 发出点的形状是条件表达式的对象字面量——断言钉**对象字面量**本身（帧型 + clientId
// 两键必须在场），并要求同一窗口内有 sendWs 调用。
const emitRe = new RegExp(
  `\\{\\s*type:\\s*['"]${frameType.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}['"]\\s*,\\s*clientId:`
);
const emitMatch = emitRe.exec(wsJs);
if (!emitMatch) {
  fail(`C: ws.js does not emit {type:'${frameType}', clientId: …} on open — every tunneled console would fall back to the session-id rail (the reported bug)`);
} else {
  const identifyBlock = wsJs.slice(Math.max(0, emitMatch.index - 500), emitMatch.index + 500);
  if (!/deviceClientId\(\)/.test(identifyBlock)) {
    fail('C: the identify frame must take its value from deviceClientId() (the same localStorage key as the handshake rail — a second identity source would re-open the false-positive face)');
  }
  if (!/sendWs\(/.test(identifyBlock)) {
    fail('C: the identify frame literal is built but never sent with sendWs(…)');
  }
}

// ── F. automation declaration stays in lockstep（同端判据批 C，2026-11-07） ──
if (!/navigator\.webdriver/.test(wsJs)) {
  fail('F: ws.js no longer reports navigator.webdriver — automated browsers would claim the console again');
}
if (!/def\s+declaredAutomated\s*\(/.test(takeoverSrc)) {
  fail('F: ConsoleTakeover.declaredAutomated is missing — the server can no longer read the automation declaration');
}
if (!/def\s+endprintOf\s*\(/.test(takeoverSrc) || !/def\s+sameEnd\s*\(/.test(takeoverSrc)) {
  fail('F: the same-end fingerprint core (endprintOf/sameEnd) is missing — same-device clientId churn would prompt itself again');
}

// ── D. the handshake rail must stay (direct face: no extra round-trip) ──
if (!/\?clientId=\$\{encodeURIComponent\(clientIdParam\)\}/.test(wsJs)) {
  fail('D: ws.js no longer appends ?clientId= to the handshake URL (direct-face rail regressed)');
}

// ── G. Console retake was explicitly restored by 18b7da220; canvas remains separate. ──
const modal = readIf(join(JS, 'takeoverModal.js'));
const routes = readIf(join(SCALA, 'ConsoleTakeoverRoutes.scala'));
if (!modal || !routes || !/id="takeover-retake"/.test(indexHtml) ||
    !/def\s+handleRetake\s*\(/.test(takeoverSrc) ||
    !/def\s+claimAndReverseKick\s*\(/.test(takeoverSrc)) {
  fail('G: restored console retake card, route or state machine is missing');
}
if (/showCanvasTakeover|takeover-retake|takeover-exit/.test(canvasJs)) {
  fail('G: console retake UI leaked into the separate canvas writer path');
}

// ── H. the refusal carries a REAL save-failure message (never takeover/yield wording) ──
const SAVE_FAIL = '保存失败，本次修改未保存到服务器。';
for (const [code, needs] of [
  ['canvas_read_only', true],
  ['stale_writer_epoch', true],
  ['writer_already_held', true],
]) {
  const re = new RegExp(`${code}[\\s\\S]{0,400}?${SAVE_FAIL.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`);
  if (!re.test(configRoutesSrc)) {
    fail(`H: ConfigRoutes rejection for \`${code}\` no longer carries the real save-failure message («${SAVE_FAIL}»)`);
  }
  if (needs && !configRoutesSrc.includes(code)) {
    fail(`H: ConfigRoutes no longer emits the \`${code}\` rejection code`);
  }
}
const refusalRe = /(?:canvas_read_only|stale_writer_epoch|writer_already_held)[\s\S]{0,400}?"message"/g;
for (const hit of configRoutesSrc.matchAll(refusalRe)) {
  const window = configRoutesSrc.slice(hit.index, hit.index + 400);
  if (/接管|让出|取回控制权/.test(window)) {
    fail('H: a canvas write-rejection payload uses takeover/yield wording — it must speak only of a save failure');
    break;
  }
}
const failedWire = /failed:\s*\(\)\s*=>\s*\{([^}]*)\}/.exec(canvasJs);
// 862f93c9e1: failed writes remain silent but must retain unsynced reader state.
if (!failedWire || !/localUnsynced\s*=\s*true/.test(failedWire[1]) || !/writerChanged\('reader'\)/.test(failedWire[1])) {
  fail('H: failed writes must retain unsynced state and switch to reader');
}

// ── I. the writer seat is a SILENT PREEMPTIVE seat（静默接管批 2026-11-19 作者令
//    「直接静默吧 交给主会话实现」）：申请即授予；已有别的写者时**静默接管**——翻位 +
//    epoch 自增 + **定向通知被顶下的写者**（前端据此落未合并草稿）。旧的「非抢占 +
//    活性判定腿」整套退役，故这里不再要求 `state.writer.isDefined && !mine` 这条守卫。 ──
const claimIdx = takeoverSrc.indexOf('def claimCanvas(');
if (claimIdx < 0) {
  fail('I: ConsoleTakeover.claimCanvas is missing — no single-writer seat to gate on');
} else {
  const claimWindow = takeoverSrc.slice(claimIdx, claimIdx + 2400);
  if (!/val displaced/.test(claimWindow)) {
    fail('I: claimCanvas no longer captures the writer it displaces — a silent takeover must know who lost the seat (it is the one that must draft)');
  }
  if (!/notifyDisplacedWriter\(/.test(claimWindow)) {
    fail('I: claimCanvas no longer notifies the displaced writer — that is the losing window\'s only trigger to stash its edits (silent + no-loss would regress to silent + lost)');
  }
  if (/state\.writer\.isDefined\s*&&\s*!mine/.test(claimWindow)) {
    fail('I: claimCanvas still carries the retired non-preemptive guard — the seat must grant on every claim (silent takeover, 2026-11-19)');
  }
}
if (!/def\s+notifyDisplacedWriter\s*\(/.test(takeoverSrc)) {
  fail('I: ConsoleTakeover.notifyDisplacedWriter is missing — the silent seat-change signal has no single source');
}
const writerSliceStart = canvasJs.indexOf('function createCanvasWriterClient(');
const writerSliceEnd = canvasJs.indexOf('// END CANVAS WRITER CLIENT', writerSliceStart);
const writerSlice = writerSliceStart >= 0 && writerSliceEnd > writerSliceStart
  ? canvasJs.slice(writerSliceStart, writerSliceEnd) : '';
if (!/async put\(payload\)[\s\S]*?await this\.claim\(\)/.test(writerSlice)) {
  fail('I: the canvas write path no longer acquires the seat before writing (a fresh claim must precede the shared PUT)');
}
// 失权端落草稿的触发点必须在场（丢席位 ⇒ 静默落草稿，零阻断式通知）。
if (!/function captureSeatDraft\s*\(/.test(canvasJs)) {
  fail('I: canvas.js has no captureSeatDraft — the displaced writer would silently lose its edits (only half of 「静默 + 不丢」)');
}
if (!/captureSeatDraft\(\)/.test(canvasJs)) {
  fail('I: captureSeatDraft is never called — the seat-loss hook is unwired');
}
try {
  const { outside } = stripBrowserTakeoverGlue(canvasJs);
  if (/takeover/i.test(outside)) {
    fail('I: canvas.js still references the console-takeover surface — the canvas producer must carry none of it');
  }
} catch (error) {
  fail(`I: ${error.message}`);
}

if (failures.length > 0) {
  console.error('check-takeover-identify: FAIL');
  for (const f of failures) console.error(`  · ${f}`);
  console.error(`\n${failures.length} violation(s) — the single-instance identity / read-only-multi-open chain is broken (frame type in use: "${frameType}")`);
  process.exit(1);
}

console.log(
  `check-takeover-identify: PASS — identity declared in-band as "${frameType}" (client emit ↔ receive-pipe intercept), ` +
  `handshake ?clientId= rail intact, console retake restored and isolated from canvas, ` +
  `refusal preserves unsynced reader state, writer seat silently preemptive (displaced writer notified → draft); ` +
  `marked browser takeover viewer glue is a separate surface, console takeover remains forbidden in canvas`
);
