// assert-zero-presence.mjs — 可复跑的断言：「近路判定链零 presence 参与」。
//
// 判据：给定一段引擎日志（近路闸链评估窗口），其中不得出现任何 presence/P2P
// 平面信号 —— `presence sendData` / `Presence dial failed` / `connectionsHasKey=`。
// ws.js:767-829 的环回闸链是**纯浏览器 HTTP 面**（portal/status、loopback-info、
// 跨源 fetch /api/health、铸票 POST、整页跳转），不咨询 presence，也不拨 P2P。
//
// 用法：
//   node assert-zero-presence.mjs <engine.log> [--from "2026-11-04 11:30:00"] [--to "2026-11-04 11:35:00"]
// 输出：一行 `ASSERT nearpath-gate-chain-zero-presence: PASS|FAIL (...)`；FAIL 时附前 5 条样本。
// 退出码：PASS=0 / FAIL=1（可被后续节点的脚本直接 `set -e` 消费）。

import fs from 'node:fs';

const [, , file, ...rest] = process.argv;
if (!file) {
  console.error('usage: node assert-zero-presence.mjs <engine.log> [--from "YYYY-MM-DD HH:MM:SS"] [--to "..."]');
  process.exit(2);
}
const arg = (k) => { const i = rest.indexOf(k); return i >= 0 ? rest[i + 1] : null; };
const from = arg('--from');
const to = arg('--to');

const RE = /presence sendData|Presence dial failed|connectionsHasKey=/;
const TS = /^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})/;

let matchedInWindow = 0;
const samples = [];
for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
  if (!RE.test(line)) continue;
  const m = TS.exec(line);
  const ts = m ? m[1] : '';
  if (from && ts && ts < from) continue;
  if (to && ts && ts > to) continue;
  matchedInWindow++;
  if (samples.length < 5) samples.push(line.trim().slice(0, 220));
}

const scoped = from || to;
if (matchedInWindow === 0) {
  console.log(`ASSERT nearpath-gate-chain-zero-presence: PASS (presence lines=0${scoped ? ' in window' : ''}; file=${file}${from ? ` from=${from}` : ''}${to ? ` to=${to}` : ''})`);
  process.exit(0);
}
console.log(`ASSERT nearpath-gate-chain-zero-presence: FAIL (presence lines=${matchedInWindow})`);
for (const s of samples) console.log('  ' + s);
process.exit(1);
