#!/usr/bin/env node
/**
 * PTC 契约一致性门：把 contracts/ptc/v1/conformance/cases.v1.json 逐条打到两个插件的 MCP server 上，
 * 断言响应符合 contracts/ptc/v1/ptc-response.schema.json 的字段与 §6 的错误枚举。
 *
 *   node scripts/ptc/run-conformance.mjs                     # 两个插件都跑
 *   node scripts/ptc/run-conformance.mjs --only ptc-python   # 只跑一个
 *   node scripts/ptc/run-conformance.mjs --json              # 机器可读输出
 *
 * 退出码：0 = 全部通过；1 = 有失败。
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { McpStdioClient, serverSpec } from './lib/mcp-client.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const CASES = JSON.parse(fs.readFileSync(path.join(ROOT, 'contracts/ptc/v1/conformance/cases.v1.json'), 'utf8'));
const RESPONSE_SCHEMA = JSON.parse(fs.readFileSync(path.join(ROOT, 'contracts/ptc/v1/ptc-response.schema.json'), 'utf8'));

const args = process.argv.slice(2);
const only = args.includes('--only') ? args[args.indexOf('--only') + 1] : null;
const asJson = args.includes('--json');

const PLUGINS = [
  { name: 'ptc-python', dir: path.join(ROOT, 'plugins/ptc-python'), dirName: 'skills/ptc-python', language: 'python', command: process.env.PTC_PYTHON || null },
  { name: 'ptc-javascript', dir: path.join(ROOT, 'plugins/ptc-javascript'), dirName: 'skills/ptc-javascript', language: 'javascript', command: process.env.PTC_NODE || null },
];

function get(obj, dotted) {
  return dotted.split('.').reduce((acc, key) => (acc === undefined || acc === null ? undefined : acc[key]), obj);
}

function checkOp(actual, op, expected) {
  switch (op) {
    case 'eq': return JSON.stringify(actual) === JSON.stringify(expected);
    case 'ne': return JSON.stringify(actual) !== JSON.stringify(expected);
    case 'in': return Array.isArray(expected) && expected.includes(actual);
    case 'contains': return typeof actual === 'string' && actual.includes(expected);
    case 'gt': return typeof actual === 'number' && actual > expected;
    case 'len': return actual !== undefined && actual !== null && (actual.length ?? 0) === expected;
    default: return false;
  }
}

/** 契约必填字段（response schema 的 required + 类型抽查）。 */
function contractShape(body) {
  const problems = [];
  for (const key of RESPONSE_SCHEMA.required) if (!(key in body)) problems.push(`missing required field '${key}'`);
  const cats = RESPONSE_SCHEMA.definitions.category.enum;
  if (body.error && !cats.includes(body.error.category)) problems.push(`error.category '${body.error?.category}' not in the fixed enum`);
  if (body.ok === false && !body.error) problems.push('ok=false but error is null');
  if (body.ok === true && body.error) problems.push('ok=true but error is non-null');
  for (const key of ['stdout', 'stderr']) if (typeof body[key] !== 'string') problems.push(`${key} must be a string`);
  if (!Array.isArray(body.tool_calls)) problems.push('tool_calls must be an array');
  if (!body.usage || typeof body.usage.wall_ms !== 'number') problems.push('usage.wall_ms must be a number');
  return problems;
}

function prepareWorkspace(pluginDirName) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ptc-conf-'));
  const workspace = path.join(dir, 'workspace');
  const seed = (pluginDirName.startsWith('skills/ptc-python') ? 'python' : 'ts');
  fs.mkdirSync(workspace, { recursive: true });
  for (const [rel, content] of Object.entries(CASES.fixtures)) {
    const full = path.join(workspace, rel);
    fs.mkdirSync(path.dirname(full), { recursive: true });
    fs.writeFileSync(full, content);
  }
  fs.writeFileSync(path.join(dir, 'seed.txt'), seed);
  return { dir, workspace };
}

async function runPlugin(plugin) {
  const results = [];
  const { dir: pluginData } = prepareWorkspace(plugin.dirName);
  const spec = serverSpec(plugin.dir, 'ptc', { pluginData, ...(plugin.command ? { command: plugin.command } : {}) });
  const client = new McpStdioClient(spec, { label: plugin.name });
  await client.initialize();
  const tools = await client.listTools();

  for (const c of CASES.cases) {
    const request = { ...c.request, code: c.code[plugin.language] };
    const started = Date.now();
    const res = await client.callTool(c.tool, request);
    const durationMs = Date.now() - started;
    const failures = [];
    let body = res.json;
    if (!body) {
      failures.push(`response is not JSON: ${String(res.text).slice(0, 200)}`);
    } else {
      failures.push(...contractShape(body));
      if (Boolean(c.expect.ok) !== body.ok) failures.push(`expected ok=${c.expect.ok}, got ok=${body.ok} (${body.error?.category ?? '-'})`);
      for (const [key, want] of Object.entries(c.expect.error ?? {})) {
        const got = get(body, `error.${key}`);
        if (got !== want) failures.push(`error.${key}: expected '${want}', got '${got}'`);
      }
      for (const [p, op, want] of c.expect.assert ?? []) {
        const got = get(body, p);
        if (!checkOp(got, op, want)) failures.push(`${p} ${op} ${JSON.stringify(want)} → got ${JSON.stringify(got)}`);
      }
    }
    results.push({
      plugin: plugin.name, case: c.id, title: c.title, pass: failures.length === 0,
      duration_ms: durationMs, failures,
      response: body ?? { raw: res.text },
    });
  }
  await client.close();
  fs.rmSync(pluginData, { recursive: true, force: true });
  return { tools: tools.map((t) => t.name), results };
}

const report = [];
for (const plugin of PLUGINS) {
  if (only && plugin.name !== only) continue;
  try {
    const r = await runPlugin(plugin);
    report.push({ plugin: plugin.name, tools: r.tools, results: r.results });
  } catch (e) {
    report.push({ plugin: plugin.name, fatal: `${e?.message ?? e}` });
  }
}

if (asJson) {
  process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
} else {
  for (const p of report) {
    if (p.fatal) { console.log(`\n${p.plugin}: FATAL ${p.fatal}`); continue; }
    const passed = p.results.filter((r) => r.pass).length;
    console.log(`\n${p.plugin}  tools=[${p.tools.join(', ')}]  ${passed}/${p.results.length} cases passed`);
    for (const r of p.results) {
      console.log(`  ${r.pass ? 'PASS' : 'FAIL'}  ${r.case}  ${r.duration_ms}ms  ${r.title}`);
      for (const f of r.failures) console.log(`        · ${f}`);
    }
  }
}

const failed = report.some((p) => p.fatal || p.results.some((r) => !r.pass));
process.exit(failed ? 1 : 0);
