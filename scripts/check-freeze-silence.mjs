#!/usr/bin/env node
// check-freeze-silence.mjs — 冻结通知策略门禁（冻结通知策略批 2026-11-05）。
//
// 判据来源 = 作者令两连逐字：
//   10:23「然后frozen这种可恢复的，不需要通知，」
//   10:36「不论是节点还是delegate，由于provider 故障，都不需要向上通知，冻结等待恢复就
//         行。然后在面板显示，这个是需要用户去处理的，我们引擎要做的就行检测恢复了能
//         接着工作。所以不需要向上汇报。因为provider 故障上级也基本死了，汇报给上级没用。」
//
// 本门禁把「这一族冻结到底还吵不吵人」变成**常驻断言**（对应回执 ②③④）：
//   ① 监督者冻结腿**不再无条件投回执**（provider 故障族零投递，闸门在场的机械判据）；
//   ② agent 级升级链对 provider 故障族**不通知上级**（只有确定性循环才升级）；
//   ③ flow-notify（dispatch-notify）对基础设施故障**不投递**（判据单点在册并被调用）；
//   ④ 引擎自动续跑：走既有唤醒腿（UserInput / wakeFrozen）——**绝不** RestartAgent
//      （不花 supervisor 重启预算），且有次数上限 + 递增间隔的纯函数判据；
//   ⑤ 聚合规则：归并键 = **错误类型族**（provider 冻结告警合并窗批 2026-11-05 19:08，
//      作者令「30分钟才提醒，而且是有一个合并窗口，把同类型的错误合并后一起汇报，不要
//      分开汇报」）——同类型窗内一条、异类型各一条、确定性缺陷即时单独一条；provider 族
//      由「本轮冻结 ≥30 分钟」触发，之后按 30 分钟合并窗 tumbling 滚动；
//   ⑥ 面板面：静默统计帧 + 「N 条已冻结（可唤醒）」文案 + 待你处理徽标在册。
//
// 判定面全是静态/纯函数（零端口零浏览器）：Scala 源码的闸门在场性 + 前端纯函数真跑。
//
// 用法：node scripts/check-freeze-silence.mjs   退出码 0 绿 / 1 红 / 2 环境错误。

import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, relative } from 'node:path';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const RUBY = (p) => join(ROOT, 'src', 'main', 'scala', 'nebflow', p);

const MODULE = join(WEB, 'js', 'freezeState.js');
const MAIN_JS = join(WEB, 'js', 'main.js');
const STATE_JS = join(WEB, 'js', 'state.js');
const BG_AGENTS = join(WEB, 'js', 'main', 'bgAgents.js');
const LOCALES = [join(WEB, 'js', 'locales', 'zh-CN.js'), join(WEB, 'js', 'locales', 'en.js')];
const BACKOFF = RUBY(join('agent', 'BackoffSupervisor.scala'));
const AGENT_FROZEN = RUBY(join('agent', 'AgentFrozen.scala'));
const WAKE_LEG = RUBY(join('agent', 'DelegateWakeLeg.scala'));
const SWEEP = RUBY(join('agent', 'FreezeAlertSweep.scala'));
const NOTICE = RUBY(join('core', 'delegate', 'FreezeNotice.scala'));
const DISPATCH_NOTIFY = RUBY(join('core', 'project', 'DispatchNotify.scala'));

for (const p of [MODULE, MAIN_JS, STATE_JS, BG_AGENTS, BACKOFF, AGENT_FROZEN, WAKE_LEG, SWEEP, NOTICE, DISPATCH_NOTIFY, ...LOCALES]) {
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
const read = (p) => readFileSync(p, 'utf8');

// freezeState.js 是纯模块（无 import / 无 DOM）；剥掉 `export ` 后在 new Function 里真跑。
function loadFreezeState() {
  const src = read(MODULE);
  assert(!/^\s*import\s/m.test(src), 'freezeState.js 必须是纯模块（不得有 import 语句）');
  assert(!/document\.|window\./.test(src), 'freezeState.js 不得触 DOM');
  const body = src.replace(/^export\s+/gm, '');
  return new Function(`${body}\nreturn { delegateFreezeWire, delegateFreezeIsInfra, freezeKindOf, showsRecoveryAction };`)();
}

// ── ① 监督者冻结腿：provider 故障族零投递（闸门在场）──────────────────────
check('① 监督者冻结腿按分类闸门投递（provider 故障族零投递）', () => {
  const src = read(BACKOFF);
  assert(/FreezeNotice\.tierOfReason/.test(src), 'freezeAndHold 必须走分类单点 FreezeNotice.tierOfReason');
  assert(/val quiet\s*=\s*(nebflow\.shared\.)?Defaults\.DelegateFreezeSilence/.test(src), '静默闸（val quiet）必须在场');
  assert(/if quiet then/.test(src), '投递必须包在 `if quiet then` 的分支里（静默 ⇒ 不投）');
  const quietIdx = src.indexOf('if quiet then');
  const deliverIdx = src.indexOf('MailboxProducer', quietIdx);
  assert(deliverIdx > quietIdx, 'MailboxProducer 投递必须位于 quiet 闸门之后');
  assert(
    src.indexOf('MailboxProducer', 0) >= quietIdx,
    '冻结腿里不得出现闸门之外的 MailboxProducer 调用'
  );
  return 'quiet 闸门在场，投递只在闸门外分支';
});

// ── ② agent 级升级链：provider 故障族不通知上级 ───────────────────────────
check('② agent 级升级链对 provider 故障族不通知上级', () => {
  const src = read(AGENT_FROZEN);
  assert(/val providerFamily\s*=\s*FreezeDisplay\.of\(reason\)\.autoRecoverable/.test(src),
    '升级链进入判定必须读 FreezeDisplay 的可自动恢复分类');
  assert(/isErrorFamily && !providerFamily && count >= ErrorFreezeEscalationThreshold/.test(src),
    '升级链条件必须排除 provider 故障族（否则「一波六条」的升级通知照旧）');
  return 'providerFamily 排除在升级链之外';
});

// ── ③ flow-notify：基础设施故障不向上汇报 ─────────────────────────────────
check('③ flow-notify 对基础设施故障不投递（判据单点 + 调用点）', () => {
  const notice = read(NOTICE);
  assert(/def infraFault\(detail: String\): Boolean/.test(notice), 'FreezeNotice.infraFault 判据必须存在');
  const dn = read(DISPATCH_NOTIFY);
  assert(/def suppressFailedNotifyForInfraFault\(node: NodeDef, silenceOn: Boolean\): Boolean/.test(dn),
    'dispatch-notify 必须暴露纯函数判据');
  assert(/DispatchNotify\.suppressFailedNotifyForInfraFault\(/.test(dn),
    'failed 守卫必须调用该判据（否则只是死代码）');
  const guardIdx = dn.indexOf('case NotifyReason.Failed =>');
  const callIdx = dn.indexOf('DispatchNotify.suppressFailedNotifyForInfraFault(');
  assert(callIdx > guardIdx, '判据必须用在 Failed 守卫里');
  assert(/markSent\(node\.id\)\.as\(false\)/.test(dn), '抑制后必须落 notifySentAt（否则 30s 拍反复重试）');
  return 'Failed 守卫 → infraFault 判据 → markSent 止重扫';
});

// ── ④ 自动续跑：既有唤醒腿、不花重启预算、上限 + 递增间隔 ────────────────
check('④ 自动续跑走既有唤醒腿（UserInput / wakeFrozen），绝不 RestartAgent', () => {
  const sweep = read(SWEEP);
  assert(/DelegateWakeLeg\s*\n?\s*\.wake\(/.test(sweep) || /DelegateWakeLeg\.wake\(/.test(sweep),
    '自动续跑必须复用 DelegateWakeLeg（既有唤醒腿）');
  // 只判**调用**（注释里说明「绝不发 RestartAgent」是允许的）。
  assert(!/AgentCommand\.RestartAgent/.test(sweep), '自动续跑腿里不得发 RestartAgent（会花 supervisor 重启预算）');
  const leg = read(WAKE_LEG);
  assert(/AgentCommand\.UserInput\(/.test(leg), 'live 腿 = UserInput 注入');
  assert(/clientMessageId = Some\(s"frozen-wake:/.test(leg), '唤醒必须带幂等键 clientMessageId');
  assert(/DelegateRevival\s*\n?\s*\.wakeFrozen\(/.test(leg) || /wakeFrozen\(/.test(leg),
    '不 live 腿 = wakeFrozen 同 id 复活');
  assert(!/AgentCommand\.RestartAgent/.test(leg), '唤醒腿里不得发 RestartAgent');
  return 'UserInput(clientMessageId) / wakeFrozen 两条子腿，零 RestartAgent';
});

check('④ 续跑次数上限 + 递增间隔是纯函数判据（60s/120s/240s…）', () => {
  const notice = read(NOTICE);
  assert(/def autoResumeDue\(frozenSince: Long, now: Long, attempts: Int\): Boolean/.test(notice),
    'autoResumeDue 纯函数必须在场');
  assert(/1L << math\.min\(attempts - 1, 16\)/.test(notice), '间隔必须按 2^(n-1) 递增');
  assert(/def autoResumesExhausted\(/.test(notice), '次数用尽判据必须在场');
  assert(/Defaults\.DelegateFreezeAutoResumeMax/.test(notice), '上限读参数面（可配）');
  return 'autoResumeDue / autoResumesExhausted 单点';
});

// ── ⑤ 聚合规则：同类型一条 + 只有需人处理才发 + 合并窗滚动 ─────────────────
check('⑤ 聚合规则：归并键 = 错误类型族；同类型一条、异类型各一条、缺陷即时', () => {
  const notice = read(NOTICE);
  // 归并键是**类型**（不是父会话、不是 id 集：id 集键会让每多冻一条就多发一条）。
  assert(/freeze-defect:\$parentSessionId:/.test(notice),
    '缺陷族的键必须含父会话 + 冻结 id 集（新缺陷立刻另发，不被合并逻辑吞掉）');
  assert(/freeze-family:\$family/.test(notice),
    'provider 族的键必须只含类型（窗内同类只发一条）');
  assert(/ids\.mkString\(","\)/.test(notice), '缺陷族键必须含冻结 id 集（新缺陷立刻另发，不被合并逻辑吞掉）');
  // 计划面：缺陷腿即时 + provider 腿按「本轮冻结超过时限」进计划，按类型归并。
  assert(/def planAlerts\(d: Digest\): List\[Alert\]/.test(notice), 'planAlerts 纯函数必须在场');
  assert(/i\.tier == Tier\.Escalate \|\| familyOfItem\(i\) == DeterministicFamily/.test(notice),
    '缺陷腿 = 确定性缺陷（含正文判为缺陷的静默条）——即时、不受时限与合并窗约束');
  assert(/escalateDue\(i\.delegateId, i\.frozenSince, d\.now\)/.test(notice), 'provider 腿的唯一升级判据 = 本轮冻结时限');
  assert(/groupBy\(familyOfItem\)/.test(notice), '必须按错误类型族归并（同类型合并成一条）');
  // 窗口参数与滚动。
  assert(/def claimAlert\(key: String, now: Long, windowMs: Long/.test(notice), 'claimAlert 必须可带窗口（按类型冷却）');
  assert(/Defaults\.DelegateFreezeDigestMergeWindowMs/.test(notice), 'provider 族冷却读合并窗参数（可配，缺省 30 分钟）');
  assert(/def rollWindow\(/.test(notice), '提交后必须滚动窗口（tumbling），否则要么刷屏要么永不重发');
  assert(/def trackWave\(/.test(notice), '窗口台账必须在场（会话数/跨度/已恢复读数的来源）');
  return '类型单键 + 缺陷即时腿 + 时限判据 + tumbling 滚动';
});

check('⑤ 告警腿读全量在冻集（时限 30 分钟与面板 10 分钟波窗不许错配）', () => {
  const sweep = read(SWEEP);
  assert(/FreezeNotice\.fullDigest\(recs, now\)/.test(sweep),
    'alertTick 必须读 fullDigest（窗口 0）——读 10 分钟波窗会让「冻过 10 分钟」的条目掉出候选，30 分钟时限永不触发');
  assert(!/planAlerts\(FreezeNotice\.digestOf\(/.test(sweep), '告警腿不得再读面板波窗 digestOf');
  assert(/FreezeNotice\.trackWave\(/.test(sweep), '扫描拍必须先累积窗口台账');
  assert(/IO\(FreezeNotice\.planAlerts\(digest\)\)/.test(sweep),
    'planAlerts 必须在 IO 内求值（写在 `*>` 右侧会先于 trackWave 求值，读到上一拍读数）');
  assert(/alert\.identity/.test(sweep), '投递身份必须按窗口滚动（否则在途条目会永久挡住后续窗）');
  return 'fullDigest + trackWave + IO(planAlerts) + 窗口化投递身份';
});

// ── ⑥ 面板面：静默统计 + 待你处理徽标 + 唤醒按钮 ─────────────────────────
check('⑥ 面板：静默统计帧 + 「N 条已冻结（可唤醒）」+ 行徽标带唤醒按钮', () => {
  const main = read(MAIN_JS);
  assert(/onMessage\('delegateFreezeDigest'/.test(main), 'main.js 必须订阅静默统计帧');
  assert(/state\.freezeDigest = \{/.test(main), '统计帧必须落到 state.freezeDigest');
  const state = read(STATE_JS);
  assert(/freezeDigest: null/.test(state), 'state 必须声明 freezeDigest 槽');
  const bg = read(BG_AGENTS);
  assert(/subagents\.freezeDigestRecoverable/.test(bg), '面板头部必须渲染「N 条已冻结（可唤醒）」');
  assert(/delegateFreezeWire\(info\)/.test(bg), '行面必须按归一后的 delegate 冻结线值分流');
  assert(/subagents\.frozenAwaitingYou/.test(bg), '行徽标必须用「已冻结 · 原因（待你处理）」');
  const wakeIdx = bg.indexOf('subagents.frozenAwaitingYou');
  const btnIdx = bg.indexOf('data-bg-wake=', wakeIdx);
  assert(btnIdx > wakeIdx, 'delegate 冻结行必须带「唤醒」按钮（作者报障点）');
  return 'set 状态可见 + 唤醒按钮在位';
});

check('⑥ 文案：zh/en 双版七键在册，且「N 条已冻结（可唤醒）」逐字可渲染', () => {
  const KEYS = [
    'subagents.frozenAwaitingYou',
    'subagents.frozenAwaitingYouNoReason',
    'subagents.frozenInfraTip',
    'subagents.frozenAutoResumes',
    'subagents.frozenAutoResumesSpent',
    'subagents.freezeDigestRecoverable',
    'subagents.freezeDigestNeedsHuman',
  ];
  for (const p of LOCALES) {
    const src = read(p);
    for (const k of KEYS) assert(src.includes(`'${k}':`), `${relative(ROOT, p)} 缺键 ${k}`);
  }
  const zh = read(LOCALES[0]);
  const m = zh.match(/subagents\.freezeDigestRecoverable': '(.*)'/);
  assert(m, 'zh 统计串键必须在册');
  assert(m[1] === '{count} 条已冻结（可唤醒）', `zh 统计串必须逐字为「{count} 条已冻结（可唤醒）」，实际：${m[1]}`);
  assert(m[1].replace('{count}', '6') === '6 条已冻结（可唤醒）', '占位替换后必须能渲染「6 条已冻结（可唤醒）」');
  return '7 键 × 2 语言包在册';
});

// ── ⑦ 前端归一判据真跑（纯函数）────────────────────────────────────────
check('⑦ 线值归一：实时帧裸原因码与快照前缀线值归一到同一族', () => {
  const fs = loadFreezeState();
  assert(fs.delegateFreezeWire({ frozenReason: 'llm-failure' }) === 'delegate-frozen:llm-failure',
    '实时帧裸原因码必须归一到带前缀线值');
  assert(fs.delegateFreezeWire({ frozenReason: 'delegate-frozen:tool-failure' }) === 'delegate-frozen:tool-failure',
    '快照前缀线值必须原样通过');
  assert(fs.delegateFreezeWire({ frozenReason: 'llm-transient' }) === '', 'actor 级退避族不得被误判成 delegate 冻结');
  assert(fs.delegateFreezeIsInfra({ frozenReason: 'llm-failure' }), 'llm-failure 属 provider 故障族');
  assert(fs.delegateFreezeIsInfra({ frozenReason: 'delegate-frozen:cancelled-by-quota' }), '额度族同属');
  assert(!fs.delegateFreezeIsInfra({ frozenReason: 'delegate-frozen:tool-failure' }), '工具层缺陷不属（需人）');
  assert(!fs.delegateFreezeIsInfra({ frozenReason: 'delegate-frozen:engine-fault' }), '引擎侧缺陷不属（需人）');
  return '4 原因码 × 2 形态全覆盖';
});

// ── ⑧ 「重启并续跑」不提供给 provider 故障族（不花重启预算）──────────────
check('⑧ 管理条：provider 故障族不给「重启并续跑」（RestartAgent 会扣重启预算）', () => {
  const mp = read(join(WEB, 'js', 'managePanel.js'));
  assert(/delegateFreezeIsInfra/.test(mp), 'isRetryableStatus 必须读 provider 故障族判据');
  const idx = mp.indexOf('delegateFreezeIsInfra(m)');
  assert(idx > 0 && /return false;/.test(mp.slice(idx, idx + 40)), 'provider 故障族必须 return false（隐藏重启按钮）');
  return 'restart 按钮对 infra 族隐藏，唤醒按钮照旧在行面';
});

// ── 报告 ──────────────────────────────────────────────────────────────
for (const line of ok) console.log(`  ok   ${line}`);
for (const line of fails) console.log(`  FAIL ${line}`);
if (fails.length > 0) {
  console.error(`\n冻结通知策略门禁：${fails.length} 项红 / ${ok.length} 项绿`);
  process.exit(1);
}
console.log(`\n冻结通知策略门禁通过（${ok.length} 项）。`);
