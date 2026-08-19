// test-model-badge.mjs — verify #308 badge decision logic (extracted from the
// real module source, not a copy) against the spec decision table.
import { readFileSync } from 'node:fs';

const files = ['flowAgentPopup.js', 'bgAgentPopup.js'];
let allOk = true;

for (const f of files) {
  const src = readFileSync(`src/main/resources/web/js/${f}`, 'utf8');

  // Extract modelBadgeHtml function body from the actual module source
  const fnRe = /function modelBadgeHtml\(current, preferred\) \{([\s\S]*?)\n\}/.exec(src);
  if (!fnRe) { console.log(`FAIL ${f}: modelBadgeHtml not found`); allOk = false; continue; }
  const fnBody = fnRe[1].replace(/\besc\(/g, 'stubEsc(');
  const stubEsc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const modelBadgeHtml = new Function('stubEsc', `return function modelBadgeHtml(current, preferred) {${fnBody}\n};`)(stubEsc);

  const cases = [
    ['deepseek/deepseek-v4-flash', 'zhipu/GLM-5.3', 'badge', 'live fallback != preferred -> highlighted'],
    ['zhipu/GLM-5.3', 'zhipu/GLM-5.3', 'subtitle', 'live == preferred -> plain'],
    ['deepseek/deepseek-v4-pro', 'zhipu/GLM-5.3', 'badge', 'current(fallback) != preferred -> highlighted'],
    ['zhipu/GLM-5.3', '', 'subtitle', 'no preferred -> plain'],
    ['', 'zhipu/GLM-5.3', '', 'empty current -> empty html'],
  ];
  let pass = 0;
  for (const [cur, pref, expect, desc] of cases) {
    const html = modelBadgeHtml(cur, pref);
    const got = html.includes('flow-agent-model-badge') ? 'badge' : html.includes('flow-agent-subtitle') ? 'subtitle' : '';
    if (got !== expect) { console.log(`FAIL ${f}: ${desc} -> got=${got} html=${html}`); allOk = false; }
    else pass++;
  }

  // renderModelBadge priority: live || cfg.current || cfg.preferred
  if (!src.includes('const current = live || cfg.current || cfg.preferred ||')) {
    console.log(`FAIL ${f}: priority chain missing`); allOk = false;
  }
  if (!src.includes('modelBadgeHtml(current, cfg.preferred)')) {
    console.log(`FAIL ${f}: highlight compare missing`); allOk = false;
  }
  if (!src.includes('lastModelBadgeHtml = null; // fresh badge element')) {
    console.log(`FAIL ${f}: open reset missing`); allOk = false;
  }
  console.log(`${f}: ${pass}/5 decision cases PASS, priority/compare/reset present`);
}

// flowTeams: live-first priority + live refresh wiring
const ft = readFileSync('src/main/resources/web/js/flowTeams.js', 'utf8');
if (!ft.includes('const current = live || cfg.current || cfg.preferred || cfg.default ||')) {
  console.log('FAIL flowTeams: fetch-path priority missing'); allOk = false;
}
if (!ft.includes('writeLabel(tile, live || cached)')) {
  console.log('FAIL flowTeams: cache-hit live override missing'); allOk = false;
}
if (!ft.includes("onMessage('usageUpdate', (msg) => refreshTileModelLive(msg.sessionId))")) {
  console.log('FAIL flowTeams: usageUpdate live handler missing'); allOk = false;
}
if (!ft.includes("onMessage('done', (msg) => refreshTileModelLive(msg.sessionId))")) {
  console.log('FAIL flowTeams: done live handler missing'); allOk = false;
}
console.log('flowTeams: live-first priority + both live handlers present');

// main.js F2: msg.model preferred
const main = readFileSync('src/main/resources/web/js/main.js', 'utf8');
if (!main.includes('model: msg.model || state.sessionModelInfo[sid]?.model,')) {
  console.log('FAIL main: F2 msg.model priority missing'); allOk = false;
}
console.log('main: F2 present');

// ws.js F0+F1
const ws = readFileSync('src/main/resources/web/js/ws.js', 'utf8');
const inWhitelist = /STREAM_MSG_TYPES = new Set\([\s\S]*?'usageUpdate'/.test(ws);
if (!inWhitelist) { console.log('FAIL ws: usageUpdate not in STREAM_MSG_TYPES'); allOk = false; }
if (!ws.includes("model: msg.model }")) { console.log('FAIL ws: convertAgentEvent model forwarding missing'); allOk = false; }
console.log('ws: F0 whitelist + F1 forwarding present');

console.log(allOk ? '\nALL PASS' : '\nSOME FAIL');
process.exit(allOk ? 0 : 1);
