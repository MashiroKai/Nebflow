#!/usr/bin/env node
/**
 * ptc-probe —— 直接以 MCP client 打两个 PTC 插件的执行器（不起引擎）。
 *
 * 用途（2026-11-02 缺陷批的证据面）：①④⑤ 的**原始信封**要能逐字落盘，
 * 而不是只看到引擎侧 "Tool [MCP] ptc_run_rw OK"。两侧跑**同一份编排**，
 * 输出逐字比对（与 scripts/ptc/fs-contract.mjs 同精神，但走的是执行器 +
 * 运行器 + 工具 server 的完整三层）。
 *
 *   node scripts/ptc/ptc-probe.mjs --workspace D:/ptc-e2e/home2/general \
 *        --out D:/ptc-e2e/evidence/probe
 *
 * 环境事实：本机 `python3` 命中的是 Microsoft Store 转发桩，故默认用
 * --python 指定的绝对路径（部署前置条件，详见 docs 复验章）。
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const argv = process.argv.slice(2);
const opt = (n, d) => { const i = argv.indexOf(`--${n}`); return i >= 0 ? argv[i + 1] : d; };
const WS = opt('workspace', 'D:/ptc-e2e/home2/general');
const OUT = opt('out', 'D:/ptc-e2e/evidence/probe');
const PY = opt('python', 'C:/Users/Administrator/AppData/Local/Programs/Python/Python312/python.exe');
fs.mkdirSync(OUT, { recursive: true });

const PTC_PY = { root: path.join(ROOT, 'plugins/ptc-python'), entry: 'skills/ptc-python/scripts/ptc_executor.py', cmd: PY, name: 'ptc-python' };
const PTC_JS = { root: path.join(ROOT, 'plugins/ptc-javascript'), entry: 'skills/ptc-javascript/dist/ptc_executor.js', cmd: process.execPath, name: 'ptc-javascript' };

class Mcp {
  constructor(command, args, env) {
    this.proc = spawn(command, args, { env, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    this.pending = new Map(); this.id = 0; this.stderr = '';
    this.proc.stderr.on('data', (d) => { this.stderr += d.toString(); });
    readline.createInterface({ input: this.proc.stdout }).on('line', (l) => {
      if (!l.trim()) return; let m; try { m = JSON.parse(l); } catch { return; }
      const s = this.pending.get(m.id); if (s) { this.pending.delete(m.id); s(m); }
    });
  }
  req(method, params, timeoutMs = 120000) {
    const id = ++this.id;
    return new Promise((res, rej) => {
      const t = setTimeout(() => { this.pending.delete(id); rej(new Error(`timeout ${method}`)); }, timeoutMs);
      this.pending.set(id, (m) => { clearTimeout(t); m.error ? rej(new Error(JSON.stringify(m.error))) : res(m.result); });
      this.proc.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
    });
  }
  async init() {
    await this.req('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'ptc-probe', version: '1' } });
    this.proc.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized', params: {} })}\n`);
  }
  close() { try { this.proc.kill(); } catch { /* gone */ } }
}

// 同一份编排：glob(**/*.md) → 逐个 read → grep TODO → write 汇总
const CODE_PY = `
import json
hits = tools.glob({"pattern": "**/*.md"})
rows = []
for p in hits["paths"]:
    doc = tools.read({"path": p})
    g = tools.grep({"pattern": "TODO", "path": p})
    rows.append({"path": p, "chars": len(doc["text"]), "todo": g["count"]})
tools.write({"path": "notes/probe-python.json", "content": json.dumps(rows)})
{"files": len(rows), "rows": rows}
`;
const CODE_JS = `
const hits = tools.glob({ pattern: '**/*.md' });
const rows = [];
for (const p of hits.paths) {
  const doc = tools.read({ path: p });
  const g = tools.grep({ pattern: 'TODO', path: p });
  rows.push({ path: p, chars: doc.text.length, todo: g.count });
}
tools.write({ path: 'notes/probe-js.json', content: JSON.stringify(rows) });
({ files: rows.length, rows });
`;
// ④：类型注解必须被明确拒绝（PTC_SYNTAX），而不是含糊通过
const CODE_TS_ANNOT = "const x: string = 'a';\n({ x })";

async function runOne(spec, calls) {
  const pluginData = path.join(os.tmpdir(), 'ptc-probe-data', spec.name);
  fs.mkdirSync(pluginData, { recursive: true });
  const env = {
    PATH: process.env.PATH ?? '', SYSTEMROOT: process.env.SYSTEMROOT ?? '',
    TEMP: process.env.TEMP ?? '', TMP: process.env.TMP ?? '',
    PLUGIN_ROOT: spec.root, PLUGIN_DATA: pluginData,
    PTC_FS_ROOTS_OPEN: '1', // 缺陷③：允许请求级 fs_roots 点名工作区
  };
  const c = new Mcp(spec.cmd, [path.join(spec.root, spec.entry)], env);
  const out = { plugin: spec.name, tools: [], results: {} };
  try {
    await c.init();
    const list = await c.req('tools/list', {});
    out.tools = (list.tools ?? []).map((t) => t.name);
    for (const [id, args] of Object.entries(calls)) {
      const r = await c.req('tools/call', { name: 'ptc_run_rw', arguments: args });
      const text = (r.content ?? []).map((b) => b.text).join('');
      out.results[id] = JSON.parse(text);
    }
  } finally { c.close(); }
  out.stderr = c.stderr.slice(0, 4000);
  return out;
}

const out = {};
out.python = await runOne(PTC_PY, {
  'same-orchestration': { code: CODE_PY, language: 'python', profile: 'readwrite', fs_roots: [WS], timeout_ms: 60000 },
});
out.javascript = await runOne(PTC_JS, {
  'same-orchestration': { code: CODE_JS, language: 'javascript', profile: 'readwrite', fs_roots: [WS], timeout_ms: 60000 },
  'type-annotation-rejected': { code: CODE_TS_ANNOT, language: 'javascript', profile: 'readonly', fs_roots: [WS], timeout_ms: 60000 },
});

// ⑤ 比对：两侧同一份编排的 value（路径/字符数/TODO 计数）
const a = out.python.results['same-orchestration'];
const b = out.javascript.results['same-orchestration'];
out.comparison = {
  pythonOk: a?.ok, jsOk: b?.ok,
  pythonValue: a?.value ?? a?.error,
  jsValue: b?.value ?? b?.error,
  valuesEqual: JSON.stringify(a?.value) === JSON.stringify(b?.value),
  pythonFilesWritten: a?.files_written,
  jsFilesWritten: b?.files_written,
};
out.typeAnnotation = {
  ok: out.javascript.results['type-annotation-rejected']?.ok,
  error: out.javascript.results['type-annotation-rejected']?.error,
};

fs.writeFileSync(path.join(OUT, 'ptc-probe.json'), JSON.stringify(out, null, 2));
console.log(JSON.stringify(out.comparison, null, 2));
console.log('typeAnnotation:', JSON.stringify(out.typeAnnotation).slice(0, 400));
