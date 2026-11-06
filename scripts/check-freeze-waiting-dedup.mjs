#!/usr/bin/env node
// check-freeze-waiting-dedup.mjs —— frozen-waiting 通知「同因只播一次 + 跨因合一个蓝气泡」门禁
// （修复批 2026-11-06 16:14，作者令逐字）：
//
//   「FROZEN-WAITING · NEBFLOW · FROZEN-WAITING 这种通知 只准去重通知一次，
//     而且要几个同类型的聚合成一个蓝色气泡通知。」
//
// 现网实证（本批要治的病）：15:29→16:14 同一组 frozen-waiting 摘要每 4 分钟重发一次，
// 每次固定 3 行（quota-balance 2 / upstream-5xx 1 / auth-subscription 12）。机械根因：
// 静默族冻结回执留在邮箱 taken/ 段随每拍重放，`collapseSilentReceipts` 折叠后每族一条
// `frozen-waiting` 行、行与行之间无「同一状态只播一次」抑制，也没有跨因聚合。
//
// 判据（改前红 / 改后绿，两边都量）：
//   D1 两条重放腿在 collapse 之后接「同因抑制 + 跨因合泡」面
//      （W1 recoverSweep / W4 resumeSweep 都调 `FreezeNotice.suppressRepeatedWaiting`，
//      且必须在 visibility/injectIdleTurn **之前**——否则照样冒泡）；
//   D2 跨因合成一条：FreezeNotice 有合并件组装（多族 ⇒ **至多一条** frozen-waiting，
//      明细在正文分行列），合并件带 `quietMerged` 标记；
//   D3 抑制态**落盘**：`freeze-waiting-announce.json` 挂 `<dataRoot>/sessions/<parent>/`
//      （AtomicJson 原子写）——重启不重置；禁纯内存台账（纯 ConcurrentHashMap 键在场
//      且无落盘 ⇒ 红）；
//   D4 成员集判据：抑制键 = (parent, family) 的**成员集合**（members），成员增减或
//      状态转好（解冻恢复清账）才允许再播；判据读数在 Scala 侧 spec 真跑
//      （多次 pump ⇒ 只出一条 / 成员变化 ⇒ 恰再播一次 / 台账文件在场）；
//   D5 呈现面零新增配色：合并件 eventType 仍为 `frozen-waiting` ⇒ 复用现网既有
//      蓝色注入泡（`.bubble.injected`）与已登记来源词面（injectedRows.js / AgentActor
//      / InjectionAttribution 三处登记保持在场，不新造词表不新造色令牌）。
//
// 判定面零端口零浏览器（Scala 源码在场性 + spec 断言在场性）。
// 用法：node scripts/check-freeze-waiting-dedup.mjs    exit 0 绿 / 1 红 / 2 环境错误。

import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, relative } from 'node:path';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const SCALA = (p) => join(ROOT, 'src', 'main', 'scala', 'nebflow', p);
const TEST = (p) => join(ROOT, 'src', 'test', 'scala', 'nebflow', p);
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');

const NOTICE = SCALA(join('core', 'delegate', 'FreezeNotice.scala'));
const DRAIN = SCALA(join('agent', 'MailboxDrain.scala'));
const ACTOR = SCALA(join('agent', 'AgentActor.scala'));
const CMD = SCALA(join('actor', 'AgentCommand.scala'));
const SPEC = TEST(join('agent', 'FreezeWaitingDedupSpec.scala'));
const ROWS = join(WEB, 'js', 'chat', 'injectedRows.js');

for (const p of [NOTICE, DRAIN, ACTOR, CMD, SPEC, ROWS]) {
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

// ── D1 两条重放腿都接「同因抑制 + 跨因合泡」面 ────────────────────────────────
check('D1 W1/W4 两条重放腿在 collapse 之后接 suppressRepeatedWaiting（注入/visibility 之前）', () => {
  const src = read(DRAIN);
  const hits = src.match(/FreezeNotice\.suppressRepeatedWaiting\(/g) || [];
  assert(hits.length >= 2, `两条重放腿（recoverSweep + resumeSweep）都必须接抑制面，实际 ${hits.length}`);
  // W1：抑制必须发生在 injectIdleTurn 消费 events 之前。
  const recIdx = src.indexOf('def recoverSweep');
  const recCol = src.indexOf('suppressRepeatedWaiting(', recIdx);
  const recInj = src.indexOf('injectIdleTurn(', recIdx);
  assert(recCol > 0 && recInj > 0 && recCol < recInj, 'recoverSweep：抑制必须在 injectIdleTurn 之前');
  // W4：抑制必须发生在 visibility 之前（否则照样冒泡）。
  const resIdx = src.indexOf('def resumeSweep');
  const resCol = src.indexOf('suppressRepeatedWaiting(', resIdx);
  const resVis = src.indexOf('events.traverse_(e => visibility(', resIdx);
  assert(resCol > 0 && resVis > 0 && resCol < resVis, 'resumeSweep：抑制必须在 visibility() 之前');
  // 抑制计数落解冻日志（复盘面：抑制了多少条可读）。
  assert(/suppressedWaiting/.test(src), '解冻日志必须带 suppressedWaiting 读数');
});

// ── D2 跨因合成一条 ─────────────────────────────────────────────────────────
check('D2 跨因合泡：多族 ⇒ 至多一条 frozen-waiting（明细正文分行列），带 quietMerged 标记', () => {
  const src = read(NOTICE);
  assert(/def suppressRepeatedWaiting\(/.test(src), 'suppressRepeatedWaiting 必须在场（两条腿的单一入口）');
  assert(/def mergedWaitingText\(/.test(src), '合并件正文组装（mergedWaitingText）必须在场');
  assert(/quietMerged/.test(src), '合并件必须带 quietMerged 标记');
  // 明细分行：正文逐族一行（`- <family> ×<n>` 形）。
  assert(/-\s*`\$\w|-\s*`\$\{/.test(src), '合并正文必须逐族一行（`- ` 列表）');
  // 跨因合流：kept 族 >1 时合成一条（合并路径在场）。
  assert(/toAnnounce\.size > 1/.test(src), '多族保留时必须走合并路径（合成一条）');
});

// ── D3 抑制态落盘（重启不重置）───────────────────────────────────────────────
check('D3 抑制态落盘：<dataRoot>/sessions/<parent>/freeze-waiting-announce.json（AtomicJson 原子写）', () => {
  const src = read(NOTICE);
  assert(/freeze-waiting-announce\.json/.test(src), '台账文件名 freeze-waiting-announce.json 必须在场');
  assert(/PathUtil\.dataRoot \/ "sessions"/.test(src), '台账必须挂在 <dataRoot>/sessions/<parent>/ 下');
  assert(/AtomicJson\.writeSync/.test(src), '写盘必须走 AtomicJson 原子写');
  // 成员集键（不是时间戳节流——时间戳节流到点必然重播，成员集判据才可能零重播）。
  assert(/members/.test(src) && /announcedAt/.test(src), '台账条目必须带 members 成员集（+announcedAt 供复盘）');
  // 解冻恢复清账：成员当前全部不在冻 ⇒ 清账（下次同成员再冻 = 新事件，允许再播）。
  assert(/frozenIdsNow|stillFrozen|frozenSince\.isDefined/.test(src), '判据必须读当前冻结态（解冻恢复 ⇒ 清账/放行）');
});

// ── D4 成员集判据在 spec 真跑（多次 pump ⇒ 只出一条）──────────────────────────
check('D4 spec 真跑判据在场：多次 pump 只出一条 / 成员变化恰再播一次 / 台账文件落盘 / 恢复清账', () => {
  const src = read(SPEC);
  for (const frag of [
    /suppressRepeatedWaiting\(/,                 // 真调抑制面
    /freeze-waiting-announce\.json/,             // 落盘文件被断言存在
    /pump|again|suppress/,                       // 多次 pump 形态
  ]) {
    assert(frag.test(src), `spec 必须含 ${frag}`);
  }
  assert(/ members |members:|members=|setOf|assertEquals[\s\S]*members|members[\s\S]*assertEquals/.test(src),
    'spec 必须断言成员集合（抑制键 = members）');
});

// ── D5 呈现面零新增配色（复用既有蓝色注入泡 + 既有登记词面）───────────────────
check('D5 合并件仍走既有 frozen-waiting 蓝泡呈现（三处登记保持，不新造配色）', () => {
  assert(/eventType == "frozen-waiting" then Some\("frozen-waiting"\)/.test(read(ACTOR)),
    'AgentActor.visibleExternalEventSource 的 frozen-waiting 登记必须保持');
  assert(/'frozen-waiting':/.test(read(ROWS)), '前端 injectedRows 来源登记必须保持');
  assert(/"frozen-waiting"/.test(read(CMD)), 'InjectionAttribution.BackendNamedSources 登记必须保持');
  // 合并件不改派 eventType（仍 frozen-waiting）⇒ 复用既有蓝色注入泡路径。
  const src = read(NOTICE);
  const m = src.match(/def suppressRepeatedWaiting\([\s\S]*?^  end suppressRepeatedWaiting/m) ||
    src.match(/def suppressRepeatedWaiting\([\s\S]*$/m);
  assert(m && !/eventType\s*=\s*"(?!frozen-waiting")[^"]*"/.test(m[0].replace(/eventType = "frozen-waiting"/g, '')),
    '抑制/合并面不得改派 eventType（必须保持 frozen-waiting 走既有蓝泡）');
});

// ── 报告 ────────────────────────────────────────────────────────────────
for (const line of ok) console.log(`  ok   ${line}`);
for (const line of fails) console.log(`  FAIL ${line}`);
if (fails.length > 0) {
  console.error(`\nfrozen-waiting 去重门禁：${fails.length} 项红 / ${ok.length} 项绿`);
  process.exit(1);
}
console.log(`\nfrozen-waiting 去重门禁通过（${ok.length} 项）。`);
