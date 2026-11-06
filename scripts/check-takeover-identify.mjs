#!/usr/bin/env node
// check-takeover-identify.mjs — 控制台单实例「身份必达」静态哨兵
// （2026-11-03 作者报障批：手机端和电脑端同时在线，没有被挤掉）。
//
// Why a static gate: the single-instance verdict only kicks when BOTH sides carry a
// provable browser identity. The identity used to ride the WS handshake query
// (`/ws?clientId=`), and that rail is NOT reliable on the console's real delivery
// path (portal/tunnel leg or any proxy that rewrites the request target). Production
// evidence 2026-11-03 10:07–10:30 (`stand.log`): seven consecutive console connections
// registered with a session-id holder — `joined — no takeover (joining page carries no
// client id (legacy cache))` — one of them from the very browser that had just sent
// `clientId` on the HTTP retake leg. With no identity on either side the verdict is
// Undecidable by design: no prompt, no kick.
//
// The fix has two halves that MUST stay in lockstep, and neither half can be checked
// by a compiler:
//   ① client: `web/js/ws.js` re-declares the identity IN-BAND on every open
//      (`{type:'takeoverIdentify', clientId}`), using the same `deviceClientId()`
//      source as the handshake rail (no second identity source);
//   ② server: `gateway/ConsoleTakeover.scala` owns the frame-type literal
//      (`IdentifyFrameType`) and the receive pipe (`WebSocketRoutes`) intercepts it.
// A silent rename on either side reproduces the reported bug exactly (wire-level,
// nobody notices) — this script is the machine that notices.
//
// Assertions (exit 1 on any violation):
//   A. `IdentifyFrameType` literal exists in ConsoleTakeover.scala;
//   B. WebSocketRoutes intercepts that exact literal (isIdentifyFrame) and routes it
//      to `onIdentify`;
//   C. ws.js EMITS that exact literal with `clientId` taken from `deviceClientId()`;
//   D. ws.js still carries the handshake `?clientId=` rail (direct face keeps working);
//   E. takeoverModal.js posts the same `clientId` on the HTTP retake leg (third leg of
//      the same identity — otherwise retake ⇒ reload re-registers as a stranger).
//
// Usage: node scripts/check-takeover-identify.mjs

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const SCALA = join(ROOT, 'src', 'main', 'scala', 'nebflow', 'gateway');
const JS = join(ROOT, 'src', 'main', 'resources', 'web', 'js');

const read = (p) => readFileSync(p, 'utf8');
const failures = [];
const fail = (msg) => failures.push(msg);

const takeoverSrc = read(join(SCALA, 'ConsoleTakeover.scala'));
const wsRoutesSrc = read(join(SCALA, 'WebSocketRoutes.scala'));
const wsJs = read(join(JS, 'ws.js'));
const modalJs = read(join(JS, 'takeoverModal.js'));

// ── A. the frame-type literal is owned by the companion object ──
const typeMatch = /val\s+IdentifyFrameType\s*:\s*String\s*=\s*"([^"]+)"/.exec(takeoverSrc);
if (!typeMatch) {
  fail('A: ConsoleTakeover.scala has no `val IdentifyFrameType: String = "…"` — the in-band frame type lost its single source');
}
const frameType = typeMatch ? typeMatch[1] : 'takeoverIdentify';

// ── B. the receive pipe intercepts that literal and routes it to onIdentify ──
if (!new RegExp(`isIdentifyFrame`).test(takeoverSrc)) {
  fail('B: ConsoleTakeover.isIdentifyFrame is missing (no frame discriminator)');
}
if (!/def\s+onIdentify\s*\(/.test(takeoverSrc)) {
  fail('B: ConsoleTakeover.onIdentify is missing (the in-band identity leg was dropped)');
}
if (!/ConsoleTakeover\.isIdentifyFrame\s*\(/.test(wsRoutesSrc)) {
  fail('B: WebSocketRoutes no longer intercepts the identify frame — declared identity is silently discarded');
}
if (!/takeover\.onIdentify\s*\(/.test(wsRoutesSrc)) {
  fail('B: WebSocketRoutes does not call takeover.onIdentify — declared identity never reaches the registry');
}

// ── C. ws.js emits the SAME literal with the shared identity source ──
const emitRe = new RegExp(
  `sendWs\\(\\{\\s*type:\\s*['"]${frameType.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}['"]\\s*,\\s*clientId:`
);
const emitMatch = emitRe.exec(wsJs);
if (!emitMatch) {
  fail(`C: ws.js does not emit {type:'${frameType}', clientId: …} on open — every tunneled console would fall back to the session-id rail (the reported bug)`);
} else {
  // 身份源必须与握手腿同一个（deviceClientId）——在发出点前后各留一窗，避免只命中
  // 注释里的字面量（本批注释刻意引用了帧型）。
  const identifyBlock = wsJs.slice(Math.max(0, emitMatch.index - 400), emitMatch.index + 400);
  if (!/deviceClientId\(\)/.test(identifyBlock)) {
    fail('C: the identify frame must take its value from deviceClientId() (the same localStorage key as the handshake rail — a second identity source would re-open the false-positive face)');
  }
}

// ── D. the handshake rail must stay (direct face: no extra round-trip) ──
if (!/\?clientId=\$\{encodeURIComponent\(clientIdParam\)\}/.test(wsJs)) {
  fail('D: ws.js no longer appends ?clientId= to the handshake URL (direct-face rail regressed)');
}

// ── E. the HTTP retake leg declares the same identity (key parity) ──
if (!/JSON\.stringify\(clientId \? \{ clientId \} : \{\}\)/.test(modalJs)) {
  fail('E: takeoverModal.js no longer posts clientId on POST /api/takeover/retake — retake ⇒ reload would register as a different instance and prompt itself');
}
if (!/deviceClientId\(\)/.test(modalJs)) {
  fail('E: takeoverModal.js must take clientId from deviceClientId() (same source as ws legs)');
}

if (failures.length > 0) {
  console.error('check-takeover-identify: FAIL');
  for (const f of failures) console.error(`  · ${f}`);
  console.error(`\n${failures.length} violation(s) — the single-instance identity chain is broken (frame type in use: "${frameType}")`);
  process.exit(1);
}

console.log(`check-takeover-identify: PASS — identity declared in-band as "${frameType}" (client emit ↔ receive-pipe intercept ↔ HTTP retake), handshake ?clientId= rail intact`);
