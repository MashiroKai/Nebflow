// summarize.mjs — 把 red/summary.json 打成 markdown 表（FINDINGS 的四闸结论表直接用）。
// 用法：node summarize.mjs [summary.json 路径]

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const f = process.argv[2] || path.resolve(HERE, '..', 'red', 'summary.json');
const s = JSON.parse(fs.readFileSync(f, 'utf8'));

console.log(`deviceId=${s.deviceId} enginePid=${s.enginePid} batch=${s.batch}`);
console.log(`loopback-info served=${s.loopbackInfoServed} tickets redeemed=${s.ticketsRedeemed}\n`);
console.log('| engine | axis | variant | outcome | miss.reason | expect | ok |');
console.log('|---|---|---|---|---|---|---|');
for (const r of s.runs) {
  console.log(`| ${r.engine} | ${r.axis} | ${r.variant} | ${r.kind} | ${r.reason || '—'} | ${r.expect} | ${r.expectOk ? 'OK' : 'MISMATCH'} |`);
}
const bad = s.runs.filter((r) => !r.expectOk);
console.log(`\n${s.runs.length} runs, ${bad.length} mismatched`);
