#!/usr/bin/env node
// check-perm-presentation.mjs — 权限呈现与等待红线静态哨兵
// （permission-landing 批 2026-11-18 · 重派单闸项）
//
// Why a static gate: 修复前同一缺陷有两个面——①后端把不可达 root 的权限卡全 root
// 扇出 fallback 帧、前端渲染成全局 toast（用户看到的「原生弹窗：nebflow 请求权限
// （目标会话不可达）」）；②等待侧无界（R1 移除定时器后无人作答时节点
// WaitingForUser 40-60min、父链级联 Idle）。两个面都是「下一个改动者顺手加回来
// 不会有任何东西变红」的形态，故本哨兵把重派单的两条硬要求变成机器判据：
//
//   A 不存在原生弹窗式权限途径  permToast 模块必须整体退役（文件不存在），
//     main.js 不得再引用 showGlobalPermissionToast / dismissGlobalPermissionToasts；
//     InteractionHub 不得再产出 `"fallback" -> true` 帧（扇出弹窗腿的帧标记）；
//     前端 askPermission 处理不得再有 fallback 弹窗分支（msg.fallback 判据）。
//   B 等待方必须有超时  AgentSessionExecution 的两条权限等待腿（内置
//     Permission / McpPermission）必须走 awaitPermissionDecisionBounded /
//     awaitMcpPermissionDecisionBounded（有界原语）；AgentCore 不得残留无界的
//     awaitPermissionDecision / awaitMcpPermissionDecision 定义（无界 seam 复活
//     = 卡死面复活）；有界原语本体必须含 timeoutTo（真的上了界）。
//
// 边界：AskUser 的无界等待是产品口径（用户提问有取消键 + 非阻塞模式），不在本闸
// 判据内；withdraw 腿（CloseRequest 精确撤回）在 PermissionWaitBoundedSpec 钉住，
// 本闸只管静态面。
//
// Exit 0 = clean；exit 1 = 违规逐条列出 file:line + 判据 id。
//
// Usage: node scripts/check-perm-presentation.mjs

import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const rel = (p) => p.replace(ROOT, '');
const read = (p) => readFileSync(ROOT + p, 'utf8');

const violations = [];
const add = (id, file, line, detail) => violations.push(`${id} ${rel(file)}:${line} ${detail}`);

const lineOf = (text, needle) => {
  const idx = text.indexOf(needle);
  return idx === -1 ? -1 : text.slice(0, idx).split('\n').length;
};

// ---------- A 不存在原生弹窗式权限途径 ----------

// A1 permToast 模块整体退役
const PERM_TOAST = 'src/main/resources/web/js/main/permToast.js';
if (existsSync(ROOT + PERM_TOAST)) {
  add('A1', PERM_TOAST, 1, '权限全局 toast 模块必须不存在（原生弹窗式权限途径）');
}

// A2 main.js 不得引用退役模块
const MAIN = 'src/main/resources/web/js/main.js';
const mainJs = read(MAIN);
for (const needle of ['showGlobalPermissionToast', 'dismissGlobalPermissionToasts']) {
  const line = lineOf(mainJs, needle);
  if (line !== -1) add('A2', MAIN, line, `不得引用已退役的权限 toast 模块（${needle}）`);
}

// A3 askPermission 处理不得再有 fallback 弹窗分支
const fallbackLine = lineOf(mainJs, 'msg.fallback');
if (fallbackLine !== -1) {
  add('A3', MAIN, fallbackLine, 'askPermission 处理不得再以 msg.fallback 分支弹全局卡（不可达卡由后端重定锚到可达会话）');
}

// A4 InteractionHub 不得再产出扇出 fallback 帧
const HUB = 'src/main/scala/nebflow/agent/InteractionHub.scala';
const hub = read(HUB);
const fanoutLine = lineOf(hub, '"fallback" -> true.asJson');
if (fanoutLine !== -1) {
  add('A4', HUB, fanoutLine, '不得再扇出 fallback:true 权限帧（该帧的前端消费 = 原生弹窗形态）');
}

// ---------- B 等待方必须有超时 ----------

// B1 两条权限等待腿都走有界原语
const EXEC = 'src/main/scala/nebflow/agent/AgentSessionExecution.scala';
const exec = read(EXEC);
const callLine = (name) => {
  const idx = exec.indexOf(`AgentCore.${name}(`);
  return idx === -1 ? -1 : exec.slice(0, idx).split('\n').length;
};
const boundedLine = callLine('awaitPermissionDecisionBounded');
if (boundedLine === -1) {
  add('B1', EXEC, 1, '内置权限等待必须走 AgentCore.awaitPermissionDecisionBounded（有界）');
}
const mcpBoundedLine = callLine('awaitMcpPermissionDecisionBounded');
if (mcpBoundedLine === -1) {
  add('B1', EXEC, 1, 'mcpPermission 等待必须走 AgentCore.awaitMcpPermissionDecisionBounded（有界）');
}

// B2 无界 seam 不得复活
const CORE = 'src/main/scala/nebflow/agent/AgentCore.scala';
const core = read(CORE);
const unbounded = /^  def awaitPermissionDecision\(|^  def awaitMcpPermissionDecision\(/m.exec(core);
if (unbounded) {
  const line = core.slice(0, unbounded.index).split('\n').length;
  add('B2', CORE, line, '无界权限等待原语不得存在（无人作答时请求方将无限挂起——重派单 P1 红线）');
}

// B3 有界原语本体必须真的上了界
const boundedDef = core.indexOf('def awaitPermissionDecisionBounded(');
if (boundedDef === -1) {
  add('B3', CORE, 1, 'AgentCore.awaitPermissionDecisionBounded 缺失');
} else {
  const body = core.slice(boundedDef, boundedDef + 1200);
  if (!body.includes('timeoutTo')) {
    const line = core.slice(0, boundedDef).split('\n').length;
    add('B3', CORE, line, '有界等待原语必须用 timeoutTo 强制上界');
  }
}

if (violations.length) {
  console.error(`perm-presentation: ${violations.length} violation(s)`);
  for (const v of violations) console.error('  ' + v);
  process.exit(1);
}
console.log('perm-presentation: OK (no native-popup permission pathway; permission waits bounded)');
