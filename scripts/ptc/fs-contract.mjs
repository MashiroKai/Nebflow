#!/usr/bin/env node
/**
 * fs-contract —— **两个 PTC 插件同一份工具契约用例**（缺陷⑤，2026-11-02）。
 *
 * 背景：ptc-python 与 ptc-javascript 各自实现同一套 `ptc_tools_fs`（read/glob/grep/
 * stat/…），但两侧语义漂移过：TS 返回相对 base 的路径、Python 的 `notes/**\/*.md`
 * 递归返回空、`read` 吃行尾换行（同文件 138 vs 141 字符）。模型换插件就踩坑。
 *
 * 本脚本是**两侧唯一的共同判据**：同一份 fixture 目录、同一串 JSON-RPC `tools/call`，
 * 把两侧返回的原样 JSON 逐字比对（只把 fixture 绝对路径归一成 <FIXTURE>）。任何一处
 * 不一致即 exit 1，并打印首个差异的两侧原文。
 *
 *   node scripts/ptc/fs-contract.mjs            # 跑并写报告
 *   node scripts/ptc/fs-contract.mjs --quiet    # 只跑（CI）
 *
 * 环境：python3（PTC_PYTHON 可覆盖）、node（本进程自身）。
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
// 路径可被环境变量覆写：验红时指向**修复前**的实现（git show HEAD:…），同一脚本出红。
const PY_TOOLS = process.env.PTC_PY_TOOLS
  || path.join(ROOT, 'plugins/ptc-python/skills/ptc-python/scripts/ptc_tools_fs.py');
const JS_TOOLS = process.env.PTC_JS_TOOLS
  || path.join(ROOT, 'plugins/ptc-javascript/skills/ptc-javascript/dist/ptc_tools_fs.js');
const OUT_DIR = process.env.PTC_CONTRACT_OUT || path.join(ROOT, 'reports/20261102_ptc-defect-fixes');
const QUIET = process.argv.includes('--quiet');

// ── fixture：一份目录树，两侧共用 ──────────────────────────────────────────
function buildFixture(dir) {
  const w = (rel, content) => {
    const p = path.join(dir, rel);
    fs.mkdirSync(path.dirname(p), { recursive: true });
    fs.writeFileSync(p, content);
  };
  w('notes/a.md', '# A\n\nalpha line\n'); // 尾随换行（read 必须逐字保留）
  w('notes/deep/b.md', '# B\n\nbeta line\n');
  w('notes/deep/deeper/c.md', '# C\n\nno trailing newline');
  w('src/app.js', 'const a = 1;\nconst b = 2;\n');
  w('src/nested/deep.js', 'const c = 3;\n');
  w('root.md', '# root\n');
  w('.hidden/secret.md', '# nope\n'); // 点目录必须被跳过
  w('crlf.md', 'crlf line one\r\ncrlf line two\r\n');
  return dir;
}

// ── MCP stdio 客户端（最小实现：initialize + tools/call）────────────────────
class McpStdio {
  constructor(command, args, env) {
    this.proc = spawn(command, args, { env, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    this.pending = new Map();
    this.nextId = 0;
    this.stderr = '';
    this.proc.stderr.on('data', (d) => { this.stderr += d.toString(); });
    readline.createInterface({ input: this.proc.stdout }).on('line', (line) => {
      if (!line.trim()) return;
      let msg;
      try { msg = JSON.parse(line); } catch { return; }
      const slot = this.pending.get(msg.id);
      if (slot) { this.pending.delete(msg.id); slot(msg); }
    });
  }

  request(method, params) {
    const id = ++this.nextId;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`timeout: ${method}`)); }, 20000);
      this.pending.set(id, (msg) => {
        clearTimeout(timer);
        if (msg.error) reject(new Error(`rpc error: ${JSON.stringify(msg.error)}`));
        else resolve(msg.result);
      });
      this.proc.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
    });
  }

  async init() {
    await this.request('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'fs-contract', version: '1' } });
    this.proc.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized', params: {} })}\n`);
  }

  /** tools/call → 解析 content[0].text 的 JSON 信封（工具层原样返回，不再加工）。 */
  async call(name, args) {
    const result = await this.request('tools/call', { name, arguments: args });
    const text = (result.content ?? []).filter((b) => typeof b?.text === 'string').map((b) => b.text).join('');
    return { envelope: JSON.parse(text), isError: result.isError === true };
  }

  close() { try { this.proc.kill(); } catch { /* already gone */ } }
}

// ── 用例表（同一份，两侧都跑）──────────────────────────────────────────────
const CASES = [
  { id: 'read/whole-file-keeps-trailing-newline', tool: 'read', args: { path: 'notes/a.md' } },
  { id: 'read/no-trailing-newline', tool: 'read', args: { path: 'notes/deep/deeper/c.md' } },
  { id: 'read/crlf-preserved', tool: 'read', args: { path: 'crlf.md' } },
  { id: 'read/offset-limit', tool: 'read', args: { path: 'notes/a.md', offset: 1, limit: 2 } },
  { id: 'glob/**-recursive', tool: 'glob', args: { pattern: '**/*.md' } },
  { id: 'glob/notes-prefixed-double-star', tool: 'glob', args: { pattern: 'notes/**/*.md' } },
  { id: 'glob/star-single-segment-only', tool: 'glob', args: { pattern: '*.md' } },
  { id: 'glob/dot-dirs-skipped', tool: 'glob', args: { pattern: '**/*.md', path: '.' } },
  { id: 'glob/js-files', tool: 'glob', args: { pattern: '**/*.js' } },
  { id: 'grep/hit-order-and-count', tool: 'grep', args: { pattern: 'const [a-z]' } },
  { id: 'grep/base-relative-paths', tool: 'grep', args: { pattern: 'line', path: 'notes' } },
  { id: 'grep/crlf-text-normalised', tool: 'grep', args: { pattern: 'crlf line' } },
  { id: 'stat/file', tool: 'stat', args: { path: 'notes/a.md' } },
  { id: 'stat/missing', tool: 'stat', args: { path: 'nope.md' } },
  { id: 'policy/escape-root', tool: 'read', args: { path: '../../etc/passwd' } },
];

// ── 归一化：绝对路径 → <FIXTURE>，mtime/size 等噪声不动（stat 用固定 fixture）──
function normalise(envelope, fixture) {
  const json = JSON.stringify(envelope);
  const slash = fixture.replace(/\\/g, '\\\\');
  const re = new RegExp(slash.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'g');
  const swapped = json.replace(re, '<FIXTURE>').replaceAll(fixture.replace(/\\/g, '/'), '<FIXTURE>');
  return JSON.parse(swapped);
}

async function runSide(label, command, scriptPath, fixture) {
  const env = {
    PATH: process.env.PATH ?? '',
    SYSTEMROOT: process.env.SYSTEMROOT ?? '',
    PTC_FS_ROOTS: fixture,
    PTC_FS_WRITE: '1',
    PTC_MAX_VALUE_BYTES: '16384',
  };
  const client = new McpStdio(command, [scriptPath], env);
  const out = {};
  try {
    await client.init();
    for (const c of CASES) out[c.id] = await client.call(c.tool, c.args);
  } finally {
    client.close();
  }
  if (!QUIET && client.stderr.trim()) process.stderr.write(`[${label} stderr] ${client.stderr.trim()}\n`);
  return out;
}

function main() {
  const fixture = fs.mkdtempSync(path.join(os.tmpdir(), 'ptc-fs-contract-'));
  buildFixture(fixture);
  const python = process.env.PTC_PYTHON || (process.platform === 'win32' ? 'python' : 'python3');
  const node = process.execPath;

  const lines = [];
  const log = (s) => { lines.push(s); if (!QUIET) console.log(s); };

  log(`# PTC fs 契约用例（同一 fixture、同一用例、两侧逐字比对）`);
  log(`fixture: ${fixture}`);
  log(`python: ${python}  → ${PY_TOOLS}`);
  log(`node:   ${node}  → ${JS_TOOLS}`);
  log('');

  return (async () => {
    const py = await runSide('python', python, PY_TOOLS, fixture);
    const js = await runSide('node', node, JS_TOOLS, fixture);
    let failures = 0;
    for (const c of CASES) {
      const a = JSON.stringify(normalise(py[c.id].envelope, fixture));
      const b = JSON.stringify(normalise(js[c.id].envelope, fixture));
      const same = a === b;
      if (!same) failures += 1;
      log(`${same ? 'PASS' : 'FAIL'}  ${c.id}`);
      if (!same) {
        log(`   python: ${a}`);
        log(`   node  : ${b}`);
      }
    }
    log('');
    log(failures === 0 ? `RESULT: all ${CASES.length} cases identical on both plugins` : `RESULT: ${failures}/${CASES.length} case(s) DIVERGE`);
    fs.mkdirSync(OUT_DIR, { recursive: true });
    fs.writeFileSync(path.join(OUT_DIR, 'fs-contract.transcript.txt'), lines.join('\n') + '\n');
    fs.writeFileSync(
      path.join(OUT_DIR, 'fs-contract.machine.json'),
      JSON.stringify({ fixture, cases: CASES.length, failures, python: normalise2(py, fixture), node: normalise2(js, fixture) }, null, 2),
    );
    process.exitCode = failures === 0 ? 0 : 1;
  })();
}

function normalise2(all, fixture) {
  return Object.fromEntries(Object.entries(all).map(([k, v]) => [k, normalise(v.envelope, fixture)]));
}

main();
