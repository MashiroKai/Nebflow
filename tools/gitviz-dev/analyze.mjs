// analyze.mjs — 用 capture.mjs 的请求日志给「只读 + 转发只收集」做机器判读（dev-only）。
// 跑法：node tools/gitviz-dev/analyze.mjs [report.json]
// 退出码 0 = 全部判据通过；1 = 有判据不通过（打印明细）。
import { readFile } from 'node:fs/promises';
import path from 'node:path';

const FILE = process.argv[2] ||
  (process.env.GITVIZ_EVIDENCE_DIR || '/Users/kaiyu/Claude code/Stand/.stand/tmp/gitviz-mvp-20261119/evidence') +
  '/capture-report.json';

const rep = JSON.parse(await readFile(FILE, 'utf8'));
const reqs = rep.requests || [];
const m = rep.marks || {};
const at = (n) => (Number.isInteger(m[n]) ? m[n] : null);

const fail = [];
const line = (ok, label, detail) => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  — ' + detail : ''}`);
  if (!ok) fail.push(label);
};
const FWD = '/api/gitviz/forward';

console.log('report  = ' + path.relative(process.cwd(), FILE));
console.log('at      = ' + rep.at);
console.log('requests= ' + reqs.length);

// ① 全会话唯一的写方法 = 转发 sinks（dev-only 内存/日志收集器）；其余一律 GET
const nonGet = reqs.filter((r) => r.m !== 'GET');
const strayWrites = nonGet.filter((r) => !r.u.includes(FWD));
line(strayWrites.length === 0, '① 除转发 sink 外全会话零写请求',
  strayWrites.length ? strayWrites.map((r) => r.m + ' ' + r.u).join(', ')
    : `non-GET ${nonGet.length} 条，全部 = POST ${FWD}`);

// ② 面板就绪 → 点转发之前：零网络请求（选中/多选/备注纯前端状态）
const a = at('panelReady'), c = at('beforeForward');
const win = a != null && c != null ? reqs.slice(a, c) : [];
line(a != null && c != null && win.length === 0, '② 面板交互（选中/多选/备注）零请求',
  win.length + ' 条' + (win.length ? '：' + win.map((r) => r.m + ' ' + r.u).join(', ') : ''));

// ③ 转发点击：只打 dev-only 收集器，且只有这一次（不投递、不写盘到仓库/数据根）
const b = at('afterForward');
const fwd = b != null && c != null ? reqs.slice(c, b) : [];
line(fwd.length === 1 && fwd[0].u.includes(FWD) && fwd[0].m === 'POST',
  '③ 转发 = 单次 POST 到 dev-only 收集器', fwd.map((r) => r.m + ' ' + r.u).join(', ') || '0 条');

// ④ /api 面只出现三类：项目列表、git-graph、转发 sink
const api = reqs.filter((r) => r.u.includes('/api/'));
const bad = api.filter((r) => !(r.u.includes('/git-graph') || r.u.endsWith('/api/projects') || r.u.includes(FWD)));
line(bad.length === 0 && api.some((r) => r.u.includes('/git-graph')), '④ /api 面只用了既定三类端点',
  `api ${api.length} 条 / 越界 ${bad.length} 条`);

// 读数摘要（与截屏同源）
const g = rep.graph || {};
console.log('\n--- 同源读数（git-graph?heatDays=371&lanes=8&limit=1200）---');
for (const k of ['state', 'root', 'lanes', 'refsTotal', 'refsShown', 'commitsTotal', 'commitsLimit',
  'edgeCount', 'heatDays', 'heatTotal', 'heatMax', 'heatFrom', 'heatTo', 'payloadBytes', 'serverMs', 'digest']) {
  console.log(String(k).padEnd(13) + '= ' + g[k]);
}
console.log('head         = ' + (g.head ? g.head.ref + ' @ ' + g.head.short : '-'));
console.log('consoleErrors= ' + (rep.consoleErrors || []).length);

console.log('\n' + (fail.length === 0 ? 'ANALYZE: PASS' : 'ANALYZE: FAIL (' + fail.join(' / ') + ')'));
process.exit(fail.length === 0 ? 0 : 1);
