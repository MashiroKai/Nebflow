#!/usr/bin/env node
// check-freeze-state.mjs — 冻结态呈现门禁（冻结态误报批 2026-11-05）。
//
// 判据来源 = 作者报障逐字：「mail 给近路命中这会话让它恢复工作，它显示已冻结，但是
// 我没有冻结它，而且也没有显示恢复按钮」。本批把「一个 Frozen 枚举承载两种处境」拆开
// 之后，这条门禁把四件事变成**常驻断言**（对应任务书验收 1–4）：
//
//   ① 可自动恢复的冻结 ⇒ 分类 = auto/window、**不显示恢复按钮**、文案**不出现**
//      「已冻结」/「Frozen」这类需人工措辞；
//   ② 需人工介入的冻结 ⇒ 分类 = manual、**显示恢复按钮**（showsRecoveryAction）；
//   ③ 恢复失败 ⇒ 前端保留冻结标记 + 弹出错误（不许伪装成已恢复）；
//   ④ 状态迁移留痕 ⇒ 后端 `freeze-state` 迁移日志行在场（谁/条件/结果）。
//
// ── 判定面（都是静态/纯函数面，零端口零浏览器）────────────────────────────
//   · `src/main/resources/web/js/freezeState.js` 的纯函数逻辑（真跑，不是 grep）；
//   · `js/main.js` 的 delegateWakeResult 收口契约（失败分支的可见告警）；
//   · `AgentFrozen.scala` 的迁移日志字段（from->to/kind/by）；
//   · locales zh/en 的冻结词（自动恢复族不得出现「已冻结」措辞）。
//
// 为什么用「剥 export 后 new Function」而不是 import：package.json 没有
// `"type": "module"`，Node 把 `.js` 当 CJS —— 直接 import 这份 ESM 源码会失败。给整个
// repo 加 type 标记的代价远大于本门禁的收益，故此处只剥 `export ` 前缀（纯模块，零
// import 语句），并在下面显式断言「该模块不得出现 import / DOM」——一旦有人往
// freezeState.js 塞副作用，本门禁立刻红。
//
// 用法：node scripts/check-freeze-state.mjs   退出码 0 绿 / 1 红 / 2 环境错误。

import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, relative } from 'node:path';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const MODULE = join(WEB, 'js', 'freezeState.js');
const MAIN_JS = join(WEB, 'js', 'main.js');
const FROZEN_SCALA = join(ROOT, 'src', 'main', 'scala', 'nebflow', 'agent', 'AgentFrozen.scala');
const AGENT_EVENT_SCALA = join(ROOT, 'src', 'main', 'scala', 'nebflow', 'actor', 'AgentEvent.scala');
const LOCALES = [join(WEB, 'js', 'locales', 'zh-CN.js'), join(WEB, 'js', 'locales', 'en.js')];

for (const p of [MODULE, MAIN_JS, FROZEN_SCALA, AGENT_EVENT_SCALA, ...LOCALES]) {
  if (!existsSync(p)) {
    console.error(`环境错误：缺少 ${relative(ROOT, p)}`);
    process.exit(2);
  }
}

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

// ── 载入纯模块 ─────────────────────────────────────────────────────────
const src = readFileSync(MODULE, 'utf8');
assert(/^[^]*$/.test(src), 'unreachable');
if (/(^|\n)\s*import\s/.test(src) || /\bdocument\.|\bwindow\.|\blocalStorage\b/.test(src)) {
  console.error(`${relative(ROOT, MODULE)} 必须是纯函数模块（禁 import / DOM 访问）`);
  process.exit(1);
}
const body = src.replace(/^export\s+(const|function|let)\s/gm, '$1 ');
const NAMES = [
  'FREEZE_KIND', 'freezeKindOf', 'showsRecoveryAction', 'isFrozenStatus',
  'isFrozenFamily', 'freezeReasonWire', 'freezeBadgeKey', 'freezeReasonLabelKey', 'freezeNeedsHuman',
];
let mod;
try {
  // eslint-disable-next-line no-new-func
  mod = new Function(`${body}\nreturn { ${NAMES.join(', ')} };`)();
} catch (e) {
  console.error(`freezeState.js 无法求值：${e.message}`);
  process.exit(1);
}
for (const n of NAMES) {
  if (mod[n] === undefined) {
    console.error(`freezeState.js 缺少导出 ${n}`);
    process.exit(1);
  }
}
const { FREEZE_KIND } = mod;

// ── locale 词表 ────────────────────────────────────────────────────────
/** @param {string} file @returns {Record<string,string>} */
function localeMap(file) {
  const out = {};
  const text = readFileSync(file, 'utf8');
  for (const line of text.split('\n')) {
    const m = line.match(/^\s*(['"])([^'"]+)\1\s*:\s*(['"`])([^'`]*)\3\s*,?\s*$/);
    if (m) out[m[2]] = m[4];
  }
  return out;
}
const LOC = LOCALES.map((f) => ({ file: f, map: localeMap(f) }));
const AUTO_WORDING_KEYS = [
  'subagents.freezeWindow', 'subagents.freezeAuto', 'subagents.freezeWindowTip',
  'subagents.freezeAutoTip', 'chat.freezeAutoTile', 'chat.freezeWindowTile',
];
const MANUAL_WORDING_KEYS = ['subagents.freezeBadge', 'subagents.frozenTip', 'subagents.wake'];

// ══ ① 可自动恢复的冻结：分类正确、无按钮、无「已冻结」措辞 ══════════════
check('① auto-recovering（后端字段在场）⇒ auto，无恢复按钮', () => {
  const kind = mod.freezeKindOf({ freezeKind: 'auto-recovering', needsHuman: false });
  assert(kind === FREEZE_KIND.auto, `kind=${kind}`);
  assert(mod.showsRecoveryAction(kind) === false, 'auto 族不得显示恢复按钮');
  return 'kind=auto-recovering, button=none';
});

check('① 旧后端缺字段回落：reason=llm-transient/network/provider-down/restart-recovery/waiting-network ⇒ auto', () => {
  for (const wire of ['llm-transient', 'network', 'provider-down', 'restart-recovery', 'waiting-network']) {
    const kind = mod.freezeKindOf({ freezeReason: wire });
    assert(kind === FREEZE_KIND.auto, `${wire} ⇒ ${kind}`);
    assert(mod.showsRecoveryAction(kind) === false, `${wire} 不得给按钮`);
  }
  return '5 个退避族原因全部 ⇒ auto，零按钮';
});

check('① 时间表冻结 ⇒ waiting-window，无恢复按钮', () => {
  const kind = mod.freezeKindOf({ freezeReasonWire: 'schedule' });
  assert(kind === FREEZE_KIND.window, `kind=${kind}`);
  assert(mod.showsRecoveryAction(kind) === false, 'window 族不得给按钮');
  return 'kind=waiting-window, button=none';
});

check('① 自动恢复族文案不含「已冻结 / Frozen」措辞（zh + en 双版）', () => {
  const BANNED = /已冻结|Frozen|frozen/;
  for (const { file, map } of LOC) {
    for (const key of AUTO_WORDING_KEYS) {
      const v = map[key];
      assert(v !== undefined && v !== '', `${relative(ROOT, file)} 缺少 ${key}`);
      assert(!BANNED.test(v), `${relative(ROOT, file)} 的 ${key} 含需人工措辞：${v}`);
    }
  }
  return `${AUTO_WORDING_KEYS.length} 键 × 2 语言包零命中`;
});

// ══ ② 需人工介入的冻结：分类正确、有恢复按钮 ══════════════════════════
check('② needs-human（后端字段在场）⇒ manual + 恢复按钮', () => {
  const kind = mod.freezeKindOf({ freezeKind: 'needs-human', needsHuman: true });
  assert(kind === FREEZE_KIND.manual, `kind=${kind}`);
  assert(mod.showsRecoveryAction(kind) === true, 'manual 族必须显示恢复按钮');
  return 'kind=needs-human, button=shown';
});

check('② 回落表：loop / 真失败 delegate 冻结 ⇒ manual + 恢复按钮；可恢复 ⇒ 等待恢复中', () => {
  // 修复批 2026-11-06（作者原话「llm 不稳定 不需要向上通知…原地等待恢复就行了」）：
  // `delegate-frozen:*` 一族旧实现一律落 manual（「待你处理」）——provider 故障与真失败
  // 在面板上分不开。可恢复族（llm-failure / cancelled-by-quota）改判 waiting-recovery
  // 且**不给恢复按钮**（引擎自己续跑，按钮等于教用户做一件引擎正在做的事）；
  // 真失败（engine-fault / tool-failure）与 loop 仍是 manual + 按钮。
  for (const wire of ['loop', 'delegate-frozen:engine-fault', 'delegate-frozen:tool-failure']) {
    const kind = mod.freezeKindOf({ freezeReasonWire: wire });
    assert(kind === FREEZE_KIND.manual, `${wire} ⇒ ${kind}`);
    assert(mod.showsRecoveryAction(kind) === true, `${wire} 必须给按钮`);
  }
  for (const wire of ['delegate-frozen:llm-failure', 'delegate-frozen:cancelled-by-quota', 'llm-failure']) {
    const kind = mod.freezeKindOf({ freezeReasonWire: wire });
    assert(kind === FREEZE_KIND.recovering, `${wire} ⇒ ${kind}`);
    assert(mod.showsRecoveryAction(kind) === false, `${wire} 不该给「唤醒」按钮（引擎自愈）`);
  }
  return 'loop + 真失败 ⇒ manual；可恢复 ⇒ waiting-recovery（词色分开）';
});

check('② 未知/空原因不承诺自动恢复（诚实默认 = manual）', () => {
  for (const info of [{}, { freezeReasonWire: '' }, { status: 'Frozen' }]) {
    const kind = mod.freezeKindOf(info);
    assert(kind === FREEZE_KIND.manual, `${JSON.stringify(info)} ⇒ ${kind}`);
  }
  return '未知原因 ⇒ manual（宁可要人看一眼，不许假装会自己好）';
});

check('② 冻结族判据覆盖快照 status=Frozen（刷新后的行不再画成「进行中」）', () => {
  assert(mod.isFrozenStatus('Frozen') === true, 'status=Frozen 未识别');
  assert(mod.isFrozenFamily({ status: 'Frozen' }) === true, '快照行未进冻结族');
  assert(mod.isFrozenFamily({ frozen: true }) === true, '实时标记未进冻结族');
  assert(mod.isFrozenFamily({ status: 'Processing' }) === false, '在跑的行被误判成冻结');
  return 'status=Frozen ∪ frozen 标记';
});

check('② 需人工族的文案在册（zh + en）', () => {
  for (const { file, map } of LOC) {
    for (const key of MANUAL_WORDING_KEYS) {
      assert(map[key] !== undefined && map[key] !== '', `${relative(ROOT, file)} 缺少 ${key}`);
    }
  }
  return `${MANUAL_WORDING_KEYS.length} 键 × 2 语言包在册`;
});

// ══ ③ 恢复失败：保留冻结标记 + 可见告警（不许伪装成已恢复）══════════════
check('③ delegateWakeResult 收口：ok=true 才撤标记，ok=false 必弹出错误', () => {
  const main = readFileSync(MAIN_JS, 'utf8');
  const at = main.indexOf("onMessage('delegateWakeResult'");
  assert(at >= 0, 'main.js 缺 delegateWakeResult 收口');
  const chunk = main.slice(at, at + 2000);
  assert(/const ok = !!msg\.ok;/.test(chunk), '缺 ok 判据');
  assert(/applyDelegateFrozen\(null, sid, ok\s*\n?\s*\?/.test(chunk), '撤标记必须由 ok 分支驱动（失败分支不得清标记）');
  assert(/if \(!ok\)/.test(chunk) && /__showToast\?\.\(/.test(chunk) && /'error'/.test(chunk),
    '失败分支必须弹出可见错误（不许静默/伪装成功）');
  return 'ok 分支撤标记；!ok 分支保留标记 + toast(error)';
});

// ══ ④ 状态迁移留痕 ════════════════════════════════════════════════════
check('④ 后端迁移日志 freeze-state 在场（from->to + kind + by + reason）', () => {
  const sc = readFileSync(FROZEN_SCALA, 'utf8');
  assert(/"freeze-state"/.test(sc), 'AgentFrozen 缺 freeze-state 迁移日志');
  assert(/FreezeDisplay\.transitionDetail\(/.test(sc), '迁移日志正文必须走格式单点 FreezeDisplay.transitionDetail');
  const callers = (sc.match(/logFreezeTransition\(/g) || []).length;
  assert(callers >= 6, `迁移日志调用点过少（${callers} < 6）——恢复腿必须有对应条目`);
  return `日志行 + ${callers} 个调用点（置入 + 各恢复出口）`;
});

check('④ 迁移日志正文格式单点在册（from->to … by= … resumeAt=）', () => {
  const ev = readFileSync(AGENT_EVENT_SCALA, 'utf8');
  for (const field of ['$from->$to', 'kind=', 'needsHuman=', 'autoRecoverable=', 'reason=', 'by=', 'resumeAt=']) {
    assert(ev.includes(field), `transitionDetail 缺字段 ${field}`);
  }
  return '7 个字段在 transitionDetail 单点（单测钉格式）';
});

check('④ 置入触发者可辨识（by=loop-guard / schedule-gate / error-backoff / restart-recovery）', () => {
  const sc = readFileSync(FROZEN_SCALA, 'utf8');
  for (const by of ['loop-guard', 'schedule-gate', 'error-backoff', 'restart-recovery']) {
    assert(sc.includes(`"${by}"`), `缺触发者注记 ${by}`);
  }
  return '4 个 by 注记在册（回答「谁冻的」）';
});

// ══ ⑤ 单一真相（冻结状态两套真相批 2026-11-05 10:42 报障）════════════════
// 报障逐字：「显示需要我唤醒，我点了唤醒又弹 frozenReason=none」。
// 判据 = 面板可操作性与唤醒闸必须读**同一份派生读数**（后端 FreezeTruth 的
// `wakeable`），任何一端再自己算一套就算红。

const FREEZE_TRUTH_SCALA = join(ROOT, 'src', 'main', 'scala', 'nebflow', 'actor', 'FreezeTruth.scala');
const WS_HANDLERS_SCALA = join(ROOT, 'src', 'main', 'scala', 'nebflow', 'gateway', 'WsSessionChatHandlers.scala');
const WS_ROUTES_SCALA = join(ROOT, 'src', 'main', 'scala', 'nebflow', 'gateway', 'WebSocketRoutes.scala');
const DELEGATE_FREEZE_SCALA = join(ROOT, 'src', 'main', 'scala', 'nebflow', 'core', 'delegate', 'DelegateFreeze.scala');
const MAIL_TOOL_SCALA = join(ROOT, 'src', 'main', 'scala', 'nebflow', 'core', 'tools', 'MailTool.scala');
const WS_JS = join(WEB, 'js', 'ws.js');

check('⑤ 后端判定单点 FreezeTruth 在册（并集派生 + wakeable 面）', () => {
  assert(existsSync(FREEZE_TRUTH_SCALA), '缺少 nebflow/actor/FreezeTruth.scala');
  const s = readFileSync(FREEZE_TRUTH_SCALA, 'utf8');
  for (const key of ['val frozen = agentFrozen || slotFrozen', 'wakeable = fd.needsHuman', 'def json', 'notWakableWhy']) {
    assert(s.includes(key), `FreezeTruth 缺 ${key}`);
  }
  return '两处写点并集派生，wakeable = 冻结 ∧ 需人';
});

check('⑤ 唤醒闸读 FreezeTruth（不再各算一套 die 判据）', () => {
  const s = readFileSync(WS_HANDLERS_SCALA, 'utf8');
  const at = s.indexOf('private def handleDelegateWake');
  assert(at >= 0, '缺 handleDelegateWake');
  const chunk = s.slice(at, at + 9000);
  assert(/FreezeTruth\.of\(/.test(chunk), '唤醒闸未走 FreezeTruth');
  assert(/!truth\.frozen/.test(chunk) && /!truth\.wakeable/.test(chunk), '缺 frozen/wakeable 分路');
  assert(/"actualStatus"/.test(chunk) && /"freeze" -> truth\.json/.test(chunk),
    '拒收回执必须带实际状态 + 权威冻结块（不许只回 frozenReason=none）');
  return '闸 = truth.frozen / truth.wakeable；拒收带 actualStatus + freeze 块';
});

check('⑤ 快照行冻结面由同一份读数派生（同屏不再自相矛盾）', () => {
  const s = readFileSync(WS_ROUTES_SCALA, 'utf8');
  assert(/freezeTruth: Option\[nebflow\.actor\.FreezeTruth\]/.test(s), 'activeAgentEntryJson 未接 FreezeTruth');
  assert(/"freeze" -> truth\.json/.test(s), '快照缺 freeze 权威块');
  assert(/"frozenReason" -> \(if truth\.frozen then/.test(s), 'frozenReason 未由 truth 派生');
  assert(/"wakeAddress" -> \(if truth\.wakeable then/.test(s), 'wakeAddress 未由 wakeable 派生');
  return '扁平字段 + freeze 块同源';
});

check('⑤ 前端按钮判据 = 权威块 wakeable（旧后端才回落分类表）', () => {
  const fz = readFileSync(MODULE, 'utf8');
  for (const name of ['freezeBlockOf', 'mergeFreeze', 'wakeableOf']) {
    assert(new RegExp(`export\\s+function\\s+${name}`).test(fz), `freezeState.js 缺 ${name}`);
  }
  const bg = readFileSync(join(WEB, 'js', 'main', 'bgAgents.js'), 'utf8');
  assert(/if \(!wakeableOf\(info\)\)/.test(bg), 'bgAgents 未用 wakeableOf 判按钮');
  const mp = readFileSync(join(WEB, 'js', 'managePanel.js'), 'utf8');
  assert(/wakeableOf\(meta/.test(mp), 'managePanel 未用 wakeableOf 判恢复入口');
  const main = readFileSync(MAIN_JS, 'utf8');
  assert(/mergeFreeze\(entry, a\.freeze \|\| null\)/.test(main), '快照合并未按权威块定序');
  return '快照按 asOf 定序、按钮=wakeable（可降级）';
});

// Recovery publishes its state; Mail releases the gate or retains a refused supplement.
check('⑥ recovery publishes delegateWoken; Mail releases the gate in place', () => {
  const df = readFileSync(DELEGATE_FREEZE_SCALA, 'utf8');
  assert(/def onWoken\([\s\S]{0,300}wsSend: Option\[Json => IO\[Unit\]\]/.test(df),
    'onWoken 缺 wsSend（Mail 唤醒腿无从发帧——本轮报障成因）');
  assert(/"delegateWoken"/.test(df), 'onWoken 未发 delegateWoken 帧');
  assert(/"freeze" -> nebflow\.actor\.FreezeTruth\.running/.test(df), '帧缺非冻结 freeze 块');
  const wake = readFileSync(join(ROOT, 'src/main/scala/nebflow/agent/DelegateWakeLeg.scala'), 'utf8');
  assert(/\.onWoken\(resources, delegateId, via, wsSend\)/.test(wake), 'Explicit live recovery must pass wsSend to onWoken');
  const mail = readFileSync(MAIL_TOOL_SCALA, 'utf8');
  const delivery = mail.slice(mail.indexOf('private def deliverToDelegate('), mail.indexOf('end deliverToDelegate'));
  assert(/if nebflow\.core\.delegate\.SessionResumption\.requiresManual\(\s*Some\(current\),\s*task,\s*live\s*\)\s*=>\s*releaseGateByMail\(id, message, blocks, ctx, system, resources, current, live\)/.test(delivery),
    'Recovery-held Mail must release the gate through the dedicated leg');
  const release = mail.slice(mail.indexOf('private def releaseGateByMail('), mail.indexOf('end releaseGateByMail'));
  assert(/DelegateWakeLeg\s*\.wake\(res, ctx\.wsSend, id, message, "mail"\)/.test(release), 'Live session must wake with the supplement');
  assert(/resumeDelegateInPlace\([\s\S]*gateReleased = true/.test(release), 'Non-live session must resume in place with the gate released');
  assert(/case Left\(reason\)\s*=>\s*queueRecoverySupplement/.test(release), 'Refused wake must retain the supplement');
  assert(/current\.cancelRequestedAt\.nonEmpty/.test(delivery), 'Cancelled delegates must remain refused');
  const supplement = mail.slice(mail.indexOf('private def queueRecoverySupplement('), mail.indexOf('end queueRecoverySupplement'));
  assert(/\.mailOnce\(id, event, "mail-supplement", identity, producer = "mail", deduplicateReceipts = true\)/.test(supplement),
    'Recovery-held Mail must persist a deduplicated supplement');
  assert(/"recoveryHeld" -> true\.asJson/.test(supplement) && /MANUAL_RECOVERY_REQUIRED/.test(supplement),
    'Recovery-held Mail must mark and report the retained supplement');
  const resumption = readFileSync(join(ROOT, 'src/main/scala/nebflow/core/delegate/SessionResumption.scala'), 'utf8');
  const manual = resumption.slice(resumption.indexOf('def requiresManual('), resumption.indexOf('def resumeInPlace('));
  for (const marker of ['r.frozenSince.nonEmpty', 'r.frozenReason.nonEmpty', 'r.restartInterruptedAt.nonEmpty',
    'r.cancelRequestedAt.nonEmpty', 'Set("pending-recovery", "frozen").contains(t.status)',
    'r.status == nebflow.actor.AgentStatus.Frozen', 'r.frozenReason.nonEmpty']) {
    assert(manual.includes(marker), `Manual recovery predicate is missing ${marker}`);
  }
  const predicate = manual.slice(manual.indexOf('): Boolean =') + '): Boolean ='.length)
    .replace(/\/\*[^]*?\*\//g, '').replace(/\/\/[^\n]*/g, '').replace(/\s+/g, '');
  assert(predicate === 'record.exists(r=>r.frozenSince.nonEmpty||r.frozenReason.nonEmpty||r.restartInterruptedAt.nonEmpty||r.cancelRequestedAt.nonEmpty)||task.exists(t=>Set("pending-recovery","frozen").contains(t.status))||live.exists(r=>r.status==nebflow.actor.AgentStatus.Frozen||r.frozenReason.nonEmpty)',
    'Every recovery-held source must independently require gate release');
  const ws = readFileSync(WS_JS, 'utf8');
  assert(/'delegateWoken'/.test(ws), "ws.js 帧过滤集缺 'delegateWoken'（会被入口过滤器静默丢弃）");
  const main = readFileSync(MAIN_JS, 'utf8');
  assert(/onMessage\('delegateWoken'/.test(main), 'main.js 未收 delegateWoken');
  assert(/mergeFreeze\(info, msg\.freeze\)/.test(main), 'delegateWoken 未按权威块落行');
  return 'Recovery forwards wsSend; Mail releases the gate in place or retains a refused supplement';
});

check('⑥ 一致性扫描工具在册（可复现今日 case）', () => {
  const scan = join(ROOT, 'scripts', 'check-freeze-consistency.mjs');
  assert(existsSync(scan), '缺少 scripts/check-freeze-consistency.mjs');
  const s = readFileSync(scan, 'utf8');
  for (const k of ['--today-case', '--panel', 'mergeFreeze', 'wakeableOf']) {
    assert(s.includes(k), `扫描器缺 ${k}`);
  }
  return '全量扫描 / 面板对账 / 今日 case 复现三模式';
});

// ── 报告 ──────────────────────────────────────────────────────────────
for (const line of ok) console.log(`  ok   ${line}`);
for (const line of fails) console.log(`  FAIL ${line}`);
if (fails.length > 0) {
  console.error(`\n冻结态呈现门禁：${fails.length} 项红 / ${ok.length} 项绿`);
  process.exit(1);
}
console.log(`\n冻结态呈现门禁通过（${ok.length} 项）。`);
