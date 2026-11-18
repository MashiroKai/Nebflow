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
// ② 真多开 = 只读 + 服务端拒写 + 真实失败文案（2026-11-18 作者令）。剔除的是**卡片
//    与状态机**（「让出 / 取回控制权」弹窗、HTTP retake 入口、抢占式翻位），留下的是
//    **检测**：单写者席位非抢占（第二个窗口只读），拒写走真实「保存失败」文案。
//    失效面同样是静默的——弹窗被悄悄改回来、或拒写文案被换成接管措辞，界面看着都
//    「正常」，只有机检能发现。
//
// Assertions (exit 1 on any violation):
//   A. `IdentifyFrameType` literal exists in ConsoleTakeover.scala;
//   B. WebSocketRoutes intercepts that exact literal (isIdentifyFrame) and routes it
//      to `onIdentify`;
//   C. ws.js EMITS that exact literal with `clientId` taken from `deviceClientId()`;
//   D. ws.js still carries the handshake `?clientId=` rail (direct face keeps working);
//   F. automation declaration stays in lockstep (navigator.webdriver / declaredAutomated /
//      endprintOf+sameEnd) — a silent rename re-opens the false-positive face;
//   G. no console-takeover / yield CARD and no ownership-retake state machine remain
//      (takeoverModal.js gone, ConsoleTakeoverRoutes.scala gone, no #takeover-* card node,
//      no handleRetake / claimAndReverseKick);
//   H. write rejection carries a REAL save-failure message (never takeover/yield wording),
//      on both the server payload and the client notice;
//   I. the writer seat is non-preemptive (真多开的第二个窗口拿不到席位 ⇒ 只读).
//
// Usage: node scripts/check-takeover-identify.mjs

import { existsSync, readFileSync } from 'node:fs';
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

// ── G. no console-takeover / yield CARD and no ownership-retake state machine ──
if (existsSync(join(JS, 'takeoverModal.js'))) {
  fail('G: web/js/takeoverModal.js is back — the「让出/取回控制权」card must stay retired');
}
if (existsSync(join(SCALA, 'ConsoleTakeoverRoutes.scala'))) {
  fail('G: gateway/ConsoleTakeoverRoutes.scala is back — the HTTP retake entry point must stay retired');
}
const cardRe = /\bid="takeover-(?:box|title|msg|error|actions|exit|retake)"/;
if (cardRe.test(indexHtml)) {
  fail('G: index.html carries a #takeover-* card node again — the retired takeover modal is back in the DOM');
}
if (/showCanvasTakeover|takeover-retake|takeover-exit/.test(canvasJs + indexHtml)) {
  fail('G: a canvas takeover entry point (showCanvasTakeover / takeover-retake / takeover-exit) is back in the web assets');
}
if (/def\s+handleRetake\s*\(/.test(takeoverSrc) || /def\s+claimAndReverseKick\s*\(/.test(takeoverSrc)) {
  fail('G: ConsoleTakeover still exposes handleRetake / claimAndReverseKick — the retake state machine must stay retired');
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
if (!failedWire || !/保存失败/.test(failedWire[1])) {
  fail('H: canvas.js no longer surfaces a real 保存失败 notice when the server refuses a write');
} else if (/接管|让出|取回|控制权/.test(failedWire[1])) {
  fail('H: canvas.js refused-write notice uses takeover/yield wording — the ruling forbids it');
}

// ── I. the writer seat is non-preemptive (真多开的第二个窗口 = 只读) ──
const claimFn = /def\s+claimCanvas\s*\([\s\S]*?\n\s*\}\n/.exec(takeoverSrc);
if (!claimFn) {
  fail('I: ConsoleTakeover.claimCanvas is missing — no single-writer seat to gate on');
} else if (!/state\.writer\.isDefined\s*&&\s*!mine/.test(claimFn[0])) {
  fail('I: claimCanvas lost its non-preemptive guard — a second window would preempt the incumbent writer again (真多开 ⇒ 只读 regressed)');
}
const writerSliceStart = canvasJs.indexOf('function createCanvasWriterClient(');
const writerSliceEnd = canvasJs.indexOf('// END CANVAS WRITER CLIENT', writerSliceStart);
const writerSlice = writerSliceStart >= 0 && writerSliceEnd > writerSliceStart
  ? canvasJs.slice(writerSliceStart, writerSliceEnd) : '';
if (!/async put\(payload\)[\s\S]*?await this\.claim\(\)/.test(writerSlice)) {
  fail('I: the canvas write path no longer acquires the seat before writing (a refused seat must never reach the shared PUT)');
}
if (/takeover/i.test(canvasJs)) {
  fail('I: canvas.js still references the console-takeover surface — the canvas producer must carry none of it');
}

if (failures.length > 0) {
  console.error('check-takeover-identify: FAIL');
  for (const f of failures) console.error(`  · ${f}`);
  console.error(`\n${failures.length} violation(s) — the single-instance identity / read-only-multi-open chain is broken (frame type in use: "${frameType}")`);
  process.exit(1);
}

console.log(
  `check-takeover-identify: PASS — identity declared in-band as "${frameType}" (client emit ↔ receive-pipe intercept), ` +
  `handshake ?clientId= rail intact, no takeover/yield card or retake state machine, ` +
  `refusal speaks the real save-failure copy, writer seat non-preemptive`
);
