#!/usr/bin/env node
// check-freeze-replay-silence.mjs — 「可恢复冻结不向上报」的**重放面**门禁
// （修复批 2026-11-06，作者原话逐字）：
//
//   「不是说了 llm 不稳定 不需要向上通知吗 原地等待恢复就行了 搜一下我之前的要求吧
//     现在还是在给你报障」（2026-11-06 09:43）
//
// 既有 `check-freeze-silence.mjs`（11 项）只看**投递**腿（监督者 freezeAndHold /
// 升级链 / flow-notify）——它在 main 上全绿，作者却仍被刷。本门禁补的是它没看的
// 那条腿：**解冻重放**。冻结窗里积在邮箱里的回执，在每次解冻时被 `resumeSweep` /
// `recoverSweep` 原样重放，每条都过 `AgentActor.visibleExternalEventSource` 冒一格
// `delegate-frozen` 气泡（日志 `mailbox-drained-at-unfreeze events=15 replayed=15`，
// 09:24 / 09:28 / 09:35 / 09:39 / 09:43 各一次）。
//
// 判据（改前红 / 改后绿，两边都量）：
//   R1 重放面接去重判据：两条重放腿（W1 recoverSweep / W4 resumeSweep）都过
//      `FreezeNotice.collapseSilentReceipts`（main 上 grep 命中 0 ⇒ 红）；
//   R2 去重判据在场且与既有分类同源：`isSilentReceipt` / `receiptReasonOf` /
//      `tierOfReason`（不另造一份词表）；
//   R3 呈现面承认合并件来源 `frozen-waiting`（后端 + 前端标签表），不许掉进首字母
//      大写兜底；
//   R4 **真跑前端纯函数** `freezeState.freezeKindOf`：可恢复冻结（llm-failure /
//      cancelled-by-quota）⇒ `waiting-recovery`（等待恢复中），真失败（tool-failure /
//      engine-fault）⇒ `needs-human`（待你处理）——**词表必须分开**；
//   R5 面板词：zh/en 双版「等待恢复中 / Recovering」键在册，且可恢复分支不再用
//      「待你处理 / needs you」那一串。
//
// 判定面零端口零浏览器（Scala 源码在场性 + 前端纯函数 new Function 真跑）。
// 用法：node scripts/check-freeze-replay-silence.mjs    exit 0 绿 / 1 红 / 2 环境错误。

import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, relative } from 'node:path';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const SCALA = (p) => join(ROOT, 'src', 'main', 'scala', 'nebflow', p);

const MODULE = join(WEB, 'js', 'freezeState.js');
const ROWS = join(WEB, 'js', 'chat', 'injectedRows.js');
const NOTICE = SCALA(join('core', 'delegate', 'FreezeNotice.scala'));
const DRAIN = SCALA(join('agent', 'MailboxDrain.scala'));
const ACTOR = SCALA(join('agent', 'AgentActor.scala'));
const CMD = SCALA(join('actor', 'AgentCommand.scala'));
const LOCALES = [join(WEB, 'js', 'locales', 'zh-CN.js'), join(WEB, 'js', 'locales', 'en.js')];

for (const p of [MODULE, ROWS, NOTICE, DRAIN, ACTOR, CMD, ...LOCALES]) {
  if (!existsSync(p)) {
    console.error(`环境错误：缺少 ${relative(ROOT, p)}`);
    process.exit(2);
  }
}

const read = (p) => readFileSync(p, 'utf8');
const fails = [];
const ok = [];
function check(name, fn) {
  try {
    const note = fn();
    ok.push(note ? `${name} — ${note}` : name);
  } catch (e) {
    fails.push(`${name} — ${e && e.message ? e.message : e}`);
  }
}
function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}

// 剥掉 `export ` 后在 new Function 里真跑（freezeState.js 是纯模块）。
function loadFreezeState() {
  const src = read(MODULE);
  assert(!/^\s*import\s/m.test(src), 'freezeState.js 必须是纯模块（不得有 import 语句）');
  assert(!/document\.|window\./.test(src), 'freezeState.js 不得触 DOM');
  const body = src.replace(/^export\s+/gm, '');
  return new Function(`${body}\nreturn { freezeKindOf, FREEZE_KIND, freezeBadgeKey, delegateFreezeIsInfra };`)();
}

// ── R1 重放面接去重判据（两条腿）──────────────────────────────────────────
check('R1 两条重放腿都过 collapseSilentReceipts（W1 recoverSweep + W4 resumeSweep）', () => {
  const src = read(DRAIN);
  const hits = src.match(/FreezeNotice\.collapseSilentReceipts\(/g) || [];
  assert(hits.length >= 2, `replay legs must call the collapse (§ resumeSweep + recoverSweep), got ${hits.length}`);
  // 当前 W4 解冻实现不经 visibility()，而是把折叠后的 events 交给
  // notifyBatch；检查顺序边界，避免绑定已退役的旧投递实现细节。
  const resumeIdx = src.indexOf('def resumeSweep');
  const notifyIdx = src.indexOf('notifyBatch(resources, state, events, depth)', resumeIdx);
  const colIdx = src.indexOf('collapseSilentReceipts(', resumeIdx);
  assert(colIdx > resumeIdx && notifyIdx > resumeIdx && colIdx < notifyIdx,
    'collapse must run before notifyBatch in resumeSweep');
});

// ── R2 判据在场且与既有分类同源 ───────────────────────────────────────────
check('R2 重放判据 = 既有分类单点的投影（没有第二张表）', () => {
  const src = read(NOTICE);
  assert(/def collapseSilentReceipts\(/.test(src), 'collapseSilentReceipts 必须在场');
  assert(/def isSilentReceipt\(/.test(src), 'isSilentReceipt 必须在场');
  assert(/def receiptReasonOf\(/.test(src), 'receiptReasonOf 必须在场');
  assert(/tierOfReason\(r, receiptWhy\(payload\)\)/.test(src), '静默判定必须复用 tierOfReason（不另写分类）');
  assert(!/INFRA_CODES\s*=/.test(src) && !/val INFRA\s*=/.test(src), '不许在本件另起原因码白名单');
});

// ── R3 呈现面承认合并件来源 ───────────────────────────────────────────────
check('R3 呈现面登记 frozen-waiting（后端 visible source + 前端标签 + 命名源集）', () => {
  assert(/eventType == "frozen-waiting" then Some\("frozen-waiting"\)/.test(read(ACTOR)),
    'AgentActor.visibleExternalEventSource 必须给 frozen-waiting 单独一格');
  assert(/'frozen-waiting':/.test(read(ROWS)), 'injectedRows.INJECTED_SOURCE_LABELS 必须显式登记（禁首字母兜底）');
  assert(/"frozen-waiting"/.test(read(CMD)), 'InjectionAttribution.BackendNamedSources 必须登记');
});

// ── R4 前端纯函数真跑：可恢复 ≠ 失败 ──────────────────────────────────────
check('R4 panel: recoverable delegate freeze → waiting-recovery；real failure → needs-human', () => {
  const fs = loadFreezeState();
  const R = fs.FREEZE_KIND.recovering;
  assert(R === 'waiting-recovery', `FREEZE_KIND.recovering 线值必须是 waiting-recovery，实际 ${R}`);
  const cases = [
    ['llm-failure', true],
    ['cancelled-by-quota', true],
    ['tool-failure', false],
    ['engine-fault', false],
  ];
  for (const [code, recoverable] of cases) {
    for (const wire of [code, 'delegate-frozen:' + code]) {
      const kind = fs.freezeKindOf({ frozen: true, frozenReason: wire });
      if (recoverable) assertEquals(kind, R, `${wire} ⇒ 等待恢复`);
      else assertEquals(kind, fs.FREEZE_KIND.manual, `${wire} ⇒ 待你处理`);
    }
  }
  // 权威块路径同判据（后端 autoRecoverable ⇒ waiting-recovery）。
  assertEquals(
    fs.freezeKindOf({ frozen: true, freeze: { frozen: true, asOf: Date.now(), kind: '', autoRecoverable: true } }),
    R,
    'authority block: autoRecoverable ⇒ waiting-recovery'
  );
  assertEquals(
    fs.freezeKindOf({ frozen: true, freeze: { frozen: true, asOf: Date.now(), kind: '', autoRecoverable: false, needsHuman: true } }),
    fs.FREEZE_KIND.manual,
    'authority block: not recoverable ⇒ needs-human'
  );
  assertEquals(fs.freezeBadgeKey(R), 'subagents.freezeRecovering', 'badge word key must be the Recovering one');
  function assertEquals(a, b, m) {
    assert(a === b, `${m}（expected ${b}, got ${a}）`);
  }
});

// ── R5 面板词分开（zh / en）──────────────────────────────────────────────
check('R5 面板词：zh/en「等待恢复中 / Recovering」在册，且不用「待你处理」那一串', () => {
  const zh = read(LOCALES[0]);
  const en = read(LOCALES[1]);
  for (const [src, keys] of [
    [zh, ['subagents.freezeRecovering', 'subagents.freezeRecoveringReason', 'subagents.freezeRecoveringTip']],
    [en, ['subagents.freezeRecovering', 'subagents.freezeRecoveringReason', 'subagents.freezeRecoveringTip']],
  ]) {
    for (const k of keys) assert(src.includes(`'${k}':`), `缺键 ${k}`);
  }
  const zhLabel = zh.match(/subagents\.freezeRecovering': '(.*)'/);
  assert(zhLabel && zhLabel[1] === '等待恢复中', `zh 文案必须逐字「等待恢复中」，实际 ${zhLabel && zhLabel[1]}`);
  const zhRec = zh.match(/subagents\.freezeRecoveringReason': '(.*)'/);
  assert(zhRec && !/待你处理/.test(zhRec[1]), '可恢复的词里不许出现「待你处理」');
  assert(/subagents\.frozenAwaitingYou': '[^']*待你处理/.test(zh), '真失败的词仍是「待你处理」（两侧必须分开）');
  const enLabel = en.match(/subagents\.freezeRecovering': '(.*)'/);
  assert(enLabel && !/needs you/i.test(enLabel[1]), 'en recoverable word must not say "needs you"');
});

// ── 报告 ────────────────────────────────────────────────────────────────
for (const line of ok) console.log(`  ok   ${line}`);
for (const line of fails) console.log(`  FAIL ${line}`);
if (fails.length > 0) {
  console.error(`\n冻结重放面门禁：${fails.length} 项红 / ${ok.length} 项绿`);
  process.exit(1);
}
console.log(`\n冻结重放面门禁通过（${ok.length} 项）。`);
