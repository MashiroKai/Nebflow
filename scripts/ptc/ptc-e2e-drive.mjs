#!/usr/bin/env node
/**
 * ptc-e2e-drive —— 隔离实例（18123）上的一次端到端复验驱动（2026-11-02 缺陷批）。
 *
 * 做的唯一一件事：起一个会话、把一个 turn 打进去，然后**等证据落地**：
 *   - mock 侧 requests.jsonl（分配调用到达/返回时刻、delegate 的 system prompt 里
 *     有没有 `<injected-plugins>`、每次工具结果原文）
 *   - 工作区里两个插件各写出的 notes/ptc-*.json（⑤ 双侧同用例的实例侧物证）
 *
 * 用法（token 只从 auth.json 读，绝不打印）：
 *   node scripts/ptc/ptc-e2e-drive.mjs --base http://127.0.0.1:18123 --home D:/ptc-e2e/home2 \
 *        --mock D:/ptc-e2e/evidence/mock-llm --workspace D:/ptc-e2e/home2/general --timeout 240
 */
import fs from 'node:fs';
import path from 'node:path';

const argv = process.argv.slice(2);
const opt = (n, d) => { const i = argv.indexOf(`--${n}`); return i >= 0 ? argv[i + 1] : d; };
const BASE = opt('base', 'http://127.0.0.1:18123');
const HOME = opt('home', 'D:/ptc-e2e/home2');
const MOCK = opt('mock', 'D:/ptc-e2e/evidence/mock-llm');
const WS = opt('workspace', 'D:/ptc-e2e/home2/general');
const TIMEOUT = Number(opt('timeout', 240));

// token：优先取环境变量 PTC_TOKEN，否则读 auth.json（该文件是 **JSON 字符串**形态，
// 必须 JSON.parse 后再用——裸读会带上引号，网关按不符拒 403）。任何情况下不打印。
const token = (() => {
  if (process.env.PTC_TOKEN) return process.env.PTC_TOKEN.trim();
  const raw = fs.readFileSync(path.join(HOME, 'auth.json'), 'utf8');
  try {
    const parsed = JSON.parse(raw);
    return typeof parsed === 'string' ? parsed : raw.trim();
  } catch { return raw.trim(); }
})();
const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const reqJson = (p) => {
  try { return fs.readFileSync(p, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l)); } catch { return []; }
};

async function api(pathname, init) {
  const r = await fetch(`${BASE}${pathname}`, { headers, ...init });
  const text = await r.text();
  let json; try { json = JSON.parse(text); } catch { json = { raw: text }; }
  if (!r.ok) throw new Error(`${pathname} → HTTP ${r.status}: ${text.slice(0, 300)}`);
  return json;
}

const prompt = `派一个 delegate（用 Delegate 工具，你自己别动手），任务只有一件：
用 ptc-python 与 ptc-javascript 两个插件的 ptc_run_rw，在 notes/ 下各跑一遍同一份编排
（glob notes/**/*.md → 逐个 read → grep TODO 计数 → write 汇总 json），把两侧结果互相校验。
明确要求：必须真的调用 ptc_run_rw，不许用内置 read/grep/write 顶替。
跑完把两侧拿到的结果原文贴回来。`;

const t0 = Date.now();
const created = await api('/api/sessions', { method: 'POST', body: JSON.stringify({ name: 'ptc-e2e-2' }) });
console.log('SESSION', created.id);
const turnPromise = api(`/api/sessions/${created.id}/turn`, {
  method: 'POST',
  body: JSON.stringify({ content: prompt, timeoutSec: TIMEOUT }),
}).catch((e) => ({ error: String(e) }));

// 等证据：mock 记录出现工具结果 + 两侧产物文件落地
const deadline = Date.now() + TIMEOUT * 1000;
let pyFile = null; let jsFile = null; let sawAlloc = null; let sawToolResults = 0;
while (Date.now() < deadline) {
  await sleep(3000);
  const recs = reqJson(path.join(MOCK, 'requests.jsonl'));
  sawAlloc = recs.find((r) => r.phase === 'alloc-delay-start') ?? sawAlloc;
  sawToolResults = recs.filter((r) => r.phase === 'response' && /tool_calls/.test(String(r.message))).length;
  pyFile = path.join(WS, 'notes', 'ptc-python.json');
  jsFile = path.join(WS, 'notes', 'ptc-js.json');
  const done = fs.existsSync(pyFile) && fs.existsSync(jsFile);
  if (done) break;
  const allocDone = recs.find((r) => r.phase === 'alloc-delay-end');
  if (allocDone && Date.now() - t0 > 150000 && !done) break;
}
const turn = await Promise.race([turnPromise, sleep(15000).then(() => ({ status: 'pending' }))]);
const wall = Date.now() - t0;

const recs = reqJson(path.join(MOCK, 'requests.jsonl'));
const alloc = recs.filter((r) => r.phase === 'alloc-delay-start' || r.phase === 'alloc-delay-end');
const injected = recs.filter((r) => r.phase === 'request' && String(r.textHead).includes('<injected-plugins>'));
const results = recs.filter((r) => r.phase === 'request' && /tool:/.test(String(r.textHead)));

const out = {
  sessionId: created.id,
  wallMs: wall,
  turnStatus: turn?.status ?? (turn?.error ? 'error' : 'unknown'),
  turnError: turn?.error,
  allocation: alloc.map((r) => ({ at: r.at, phase: r.phase })),
  delegateSystemPromptHasInjectedPlugins: injected.length > 0,
  injectedSnippets: injected.slice(0, 1).map((r) => String(r.textHead).slice(0, 600)),
  files: {
    python: fs.existsSync(pyFile) ? pyFile : null,
    javascript: fs.existsSync(jsFile) ? jsFile : null,
    identical: fs.existsSync(pyFile) && fs.existsSync(jsFile)
      ? fs.readFileSync(pyFile, 'utf8') === fs.readFileSync(jsFile, 'utf8') : false,
  },
  mockRecords: recs.length,
};
fs.mkdirSync(path.join(MOCK, '..'), { recursive: true });
fs.writeFileSync(path.join(MOCK, 'e2e-result.json'), JSON.stringify(out, null, 2));
console.log(JSON.stringify(out, null, 2));
