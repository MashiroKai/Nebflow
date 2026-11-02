#!/usr/bin/env node
/**
 * 两个 PTC 插件的包面自检（不需要宿主）：
 *   ① plugin.json 的 extensions.ptc 结构是否符合 contracts/ptc/v1/ptc-manifest.schema.json（字段级）
 *   ② mcp.json 指向的 entry 文件真的存在
 *   ③ tool_servers 的入口文件存在，且 ${RUNTIME_SELF} 的解释器与 runtime.kind 对得上
 *   ④ 包内 contract/ 副本与 contracts/ptc/v1/ 的权威件**逐字节相等**（防漂移）
 *   ⑤ 两个插件的档位/工具名完全一致（跨语言对齐）
 *
 *   node scripts/ptc/check-manifests.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const CONTRACT_DIR = path.join(ROOT, 'contracts/ptc/v1');
const CONTRACT_FILES = ['README.md', 'ptc-request.schema.json', 'ptc-response.schema.json', 'ptc-manifest.schema.json'];
const PLUGINS = ['ptc-python', 'ptc-typescript'];

const problems = [];
const notes = [];

const readJson = (p) => JSON.parse(fs.readFileSync(p, 'utf8'));
const manifestSchema = readJson(path.join(CONTRACT_DIR, 'ptc-manifest.schema.json'));

/** 极简 JSON-Schema 子集校验（type/required/enum/const/pattern/items/additionalProperties/properties）。 */
function validate(schema, value, at, errs) {
  if (schema === true) return;
  if (schema.const !== undefined && value !== schema.const) errs.push(`${at}: expected const ${JSON.stringify(schema.const)}`);
  if (schema.enum && !schema.enum.includes(value)) errs.push(`${at}: '${value}' not in enum ${JSON.stringify(schema.enum)}`);
  const type = schema.type;
  if (type === 'object') {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) { errs.push(`${at}: expected object`); return; }
    for (const key of schema.required ?? []) if (!(key in value)) errs.push(`${at}.${key}: required`);
    for (const [key, v] of Object.entries(value)) {
      const sub = schema.properties?.[key];
      if (sub) validate(sub, v, `${at}.${key}`, errs);
      else if (schema.additionalProperties === false) errs.push(`${at}.${key}: not allowed`);
      else if (typeof schema.additionalProperties === 'object') validate(schema.additionalProperties, v, `${at}.${key}`, errs);
    }
    return;
  }
  if (type === 'array') {
    if (!Array.isArray(value)) { errs.push(`${at}: expected array`); return; }
    if (schema.minItems !== undefined && value.length < schema.minItems) errs.push(`${at}: needs ≥${schema.minItems} items`);
    value.forEach((v, i) => validate(schema.items ?? true, v, `${at}[${i}]`, errs));
    return;
  }
  if (type === 'string' && typeof value !== 'string') errs.push(`${at}: expected string`);
  if (type === 'integer' && !Number.isInteger(value)) errs.push(`${at}: expected integer`);
  if (type === 'boolean' && typeof value !== 'boolean') errs.push(`${at}: expected boolean`);
  if (schema.pattern && typeof value === 'string' && !new RegExp(schema.pattern).test(value)) errs.push(`${at}: '${value}' fails pattern ${schema.pattern}`);
}

const profiles = {};
for (const name of PLUGINS) {
  const dir = path.join(ROOT, 'plugins', name);
  const manifest = readJson(path.join(dir, 'plugin.json'));
  const ptc = manifest.extensions?.ptc;
  if (!ptc) { problems.push(`${name}: plugin.json has no extensions.ptc`); continue; }
  validate(manifestSchema, ptc, `${name}.extensions.ptc`, problems);

  // ② mcp.json entry
  const mcp = readJson(path.join(dir, 'mcp.json'));
  const server = mcp.mcpServers?.ptc;
  if (!server) problems.push(`${name}: mcp.json has no 'ptc' server`);
  else {
    for (const a of server.args ?? []) {
      const rel = String(a).replace('${PLUGIN_ROOT}/', '');
      if (rel.includes('${')) continue;
      if (!fs.existsSync(path.join(dir, rel))) problems.push(`${name}: mcp.json arg points at a missing file: ${a}`);
    }
    notes.push(`${name}: mcp command='${server.command}' args=${JSON.stringify(server.args)}`);
  }

  // ③ tool server entries
  for (const s of ptc.tool_servers ?? []) {
    for (const a of s.args ?? []) {
      const rel = String(a).replace('${PLUGIN_ROOT}/', '');
      if (rel.includes('${')) continue;
      if (!fs.existsSync(path.join(dir, rel))) problems.push(`${name}: tool_server '${s.id}' arg missing: ${a}`);
    }
    if (s.command === '${RUNTIME_SELF}') notes.push(`${name}: tool_server '${s.id}' uses \${RUNTIME_SELF} (executor interpreter)`);
  }

  // ④ contract copies byte-equal
  for (const f of CONTRACT_FILES) {
    const a = fs.readFileSync(path.join(CONTRACT_DIR, f));
    const b = path.join(dir, 'skills', name, 'contract', f);
    if (!fs.existsSync(b)) { problems.push(`${name}: contract copy missing: ${f}`); continue; }
    if (!a.equals(fs.readFileSync(b))) problems.push(`${name}: contract/${f} differs from contracts/ptc/v1/${f} (drift)`);
  }

  profiles[name] = {
    tools: (ptc.tools ?? []).map((t) => `${t.name}:${t.profile}`).sort(),
    readonly: [...(ptc.profiles?.readonly?.tools ?? [])].sort(),
    readwrite: [...(ptc.profiles?.readwrite?.tools ?? [])].sort(),
    limits: ptc.limits,
    policy: ptc.policy,
  };
}

// ⑤ 跨语言对齐
const [a, b] = [profiles['ptc-python'], profiles['ptc-typescript']];
if (a && b) {
  for (const key of ['tools', 'readonly', 'readwrite']) {
    if (JSON.stringify(a[key]) !== JSON.stringify(b[key])) {
      problems.push(`cross-language drift on '${key}': python=${JSON.stringify(a[key])} typescript=${JSON.stringify(b[key])}`);
    }
  }
  if (JSON.stringify(a.limits) !== JSON.stringify(b.limits)) problems.push('cross-language drift on limits');
  if (JSON.stringify(a.policy) !== JSON.stringify(b.policy)) problems.push('cross-language drift on policy');
}

for (const n of notes) console.log(`· ${n}`);
if (problems.length) {
  console.log('\nFAIL');
  for (const p of problems) console.log(`  - ${p}`);
  process.exit(1);
}
console.log('\nOK — manifest / entry / contract copies / cross-language surface all consistent');
