// count-wild.mjs — 野节点计数复算（只读：读盘 ≠ 改后端；零 sbt、零 6138、零进程动作）
//
// 目的：把审计腿（terminology-audit.md §④）的普查读数与**实现后判定式**在同一批真
// 数据上复算，做「改前 / 改后」并列对照。判定式对位 src/main/resources/web/js/main/
// bgAgents.js 的 bgOrphanRowKeys（三条款）：
//   ① 父整体行 key 可定位（父键命中行集里的整体行）
//   ② 父行同屏可见且键可定位（父键命中行集里任意一行）
//   ③ 父会话在册且链可解析（注册行在场）
// 数据面（与审计腿同一批真源）：~/.stand/subagent-tasks/*.json（G3 store 子条目）
//   + ~/.stand/delegates/*.json（DelegateRegistry 注册行镜像）。
// 行集面近似说明（如实记）：① ② 需要 WS live 行集，本脚本无鉴权取不到（审计腿
// §0：/api/delegates 403），故按 ③ 单条判据复算——即「父会话在册」为唯一可解析面，
// 与审计腿 C3 的判定（父 ∉ 在册 ∧ 子前缀 ∉ {delegate-,external-}）同源同分母。
// Run: node reports-evidence/agentflow-orphans-20261105/count-wild.mjs

import { readdirSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

const HOME = homedir();
const STORE = join(HOME, '.stand', 'subagent-tasks');
const REG = join(HOME, '.stand', 'delegates');

const readJson = (p) => { try { return JSON.parse(readFileSync(p, 'utf8')); } catch { return null; } };

// 注册行镜像：id 全集（delegate 记录的 id 字段，剥 team- 前缀——与 bgRowLocatorKey 同缝）。
const regIds = new Set();
const regFiles = readdirSync(REG).filter((f) => f.endsWith('.json'));
for (const f of regFiles) {
  const j = readJson(join(REG, f));
  const id = String((j && j.id) || f.replace(/\.json$/, '')).replace(/^team-/, '').trim();
  if (id) regIds.add(id);
}

// G3 store 子条目（面板成员行的数据面）。
const children = [];
let storeFiles = 0;
for (const f of readdirSync(STORE).filter((x) => x.endsWith('.json'))) {
  storeFiles += 1;
  const arr = readJson(join(STORE, f));
  if (Array.isArray(arr)) for (const c of arr) if (c && c.taskId) children.push(c);
}

const HEAD_PREFIX = /^(delegate-|external-)/;
const strip = (s) => String(s || '').replace(/^team-/, '').trim();

const buckets = { head: 0, parented: 0, wild: 0 };
const wildByKind = {};
const wildStatus = {};
const wildParents = {};
for (const c of children) {
  const self = String(c.taskId);
  const pk = strip(c.parentSessionId);
  if (HEAD_PREFIX.test(self) && !pk) { buckets.head += 1; continue; }
  if (HEAD_PREFIX.test(self)) { buckets.head += 1; continue; }
  const resolvable = !!pk && pk !== self && regIds.has(pk);   // ③ 父会话在册
  if (resolvable) { buckets.parented += 1; continue; }
  buckets.wild += 1;
  const kind = /^node-/.test(pk) ? 'node-*' : (pk ? 'other-uuid' : 'no-parent-field');
  wildByKind[kind] = (wildByKind[kind] || 0) + 1;
  const st = String(c.status || 'unknown');
  wildStatus[st] = (wildStatus[st] || 0) + 1;
  const p = pk || '(empty)';
  wildParents[p] = (wildParents[p] || 0) + 1;
}

const N = children.length;
const pct = (n) => (N ? ((n * 100) / N).toFixed(1) : '0.0');
const topParents = Object.entries(wildParents).sort((a, b) => b[1] - a[1]).slice(0, 5);

console.log(JSON.stringify({
  storeFiles,
  N,
  registryIds: regIds.size,
  buckets,
  pct: { head: pct(buckets.head), parented: pct(buckets.parented), wild: pct(buckets.wild) },
  wildByKind,
  wildStatus,
  distinctWildParents: Object.keys(wildParents).length,
  topParents,
}, null, 2));
