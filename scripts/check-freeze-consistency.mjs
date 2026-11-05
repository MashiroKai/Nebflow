#!/usr/bin/env node
// check-freeze-consistency.mjs — 「冻结状态一致性扫描」（冻结状态两套真相批 2026-11-05）。
//
// 由来（作者报障逐字 2026-11-05 10:42）：「'delegate-nebflow-f7c6cd3a' is not in the
// delegate-error frozen state (frozenReason=none) — the wake action only applies to
// frozen delegates，现在前端显示有矛盾，显示需要我唤醒，我点了唤醒又弹这个」。
//
// 本工具的三件事（任务书「修完自查矛盾」+ 验收 5）：
//   ① **全量扫描**（缺省）：读数据根的两个真相写点——`delegates/*.json`（落盘冻结槽）
//      与 `subagent-tasks/*.json`（台账行 status / lastError）——列出互相矛盾的会话。
//      矛盾类见 CONTRADICTIONS 表（每条都有「面板会怎么显示 / 闸会怎么判」的后果）。
//   ② **面板行对账**（`--panel <file.json>`）：输入一份面板行导出（含后端权威块
//      `freeze`），用真正的渲染判据（`js/freezeState.js`，剥 export 直接求值，不是
//      grep）算出「这行会不会渲染唤醒按钮」，与权威块比对——不一致 = 面板与实际打架。
//   ③ **今日 case 复现**（`--today-case`）：按实录事实重建 2026-11-05 f7c6cd3a 那一格
//      （冻结 → Mail 唤醒清槽 → 后端不再冻结；旧面板合并「单调保留」⇒ 仍显示需唤醒），
//      断言旧合并**报矛盾**、新合并（`mergeFreeze`，asOf 定序）**零矛盾**。
//
// 退出码：0 = 零矛盾 / 1 = 有矛盾 / 2 = 环境错误。
//
// 用法：
//   node scripts/check-freeze-consistency.mjs                      # 扫全量会话
//   node scripts/check-freeze-consistency.mjs --data-root ~/.stand
//   node scripts/check-freeze-consistency.mjs --panel /tmp/rows.json
//   node scripts/check-freeze-consistency.mjs --today-case
//   node scripts/check-freeze-consistency.mjs --json                # 机器可读

import { readFileSync, existsSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, relative } from 'node:path';
import { homedir } from 'node:os';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const MODULE = join(ROOT, 'src', 'main', 'resources', 'web', 'js', 'freezeState.js');

const argv = process.argv.slice(2);
const flag = (name) => argv.includes(name);
const opt = (name, dflt) => {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : dflt;
};
const DATA_ROOT = opt('--data-root', join(homedir(), '.stand'));
const PANEL = opt('--panel', '');
const AS_JSON = flag('--json');

// ── 载入渲染判据（与 check-freeze-state.mjs 同一手法：剥 export，非 grep）────
if (!existsSync(MODULE)) {
  console.error(`环境错误：缺少 ${relative(ROOT, MODULE)}`);
  process.exit(2);
}
const src = readFileSync(MODULE, 'utf8');
const body = src.replace(/^export\s+(const|function|let)\s/gm, '$1 ');
const NAMES = [
  'FREEZE_KIND', 'freezeKindOf', 'showsRecoveryAction', 'freezeBlockOf', 'mergeFreeze',
  'wakeableOf', 'isFrozenFamily', 'isFrozenStatus', 'freezeReasonWire',
];
let mod;
try {
  // eslint-disable-next-line no-new-func
  mod = new Function(`${body}\nreturn { ${NAMES.join(', ')} };`)();
} catch (e) {
  console.error(`freezeState.js 无法求值：${e.message}`);
  process.exit(2);
}

/** 面板**实际渲染**出来的读数（只走渲染判据，不读后端块）。 */
function rendered(info) {
  return {
    frozenRow: mod.isFrozenFamily(info),
    // 行被画成 'frozen' 时才轮到分类与按钮（bgAgents.rowState 的顺序）。
    wakeButton: mod.isFrozenFamily(info) ? mod.wakeableOf(info) : false,
    kind: mod.isFrozenFamily(info) ? mod.freezeKindOf(info) : '',
  };
}

// ══ ① 全量扫描 ═════════════════════════════════════════════════════════
const CONTRADICTIONS = {
  slotHalfWritten: '落盘冻结槽只写了一半（frozenSince/frozenReason 缺一）⇒ 唤醒闸读到的 reason 可能是 none',
  slotFrozenLedgerNot: '落盘槽说冻结、台账行状态不是 frozen ⇒ boot 收殓/面板投影把它当跑着的',
  ledgerFrozenSlotClear: '台账行还是 frozen、落盘槽已清 ⇒ 面板可能照着台账画「已冻结」而闸说不需要唤醒',
  unknownReason: '冻结原因不在判定表里 ⇒ 呈现按「需人工确认」兜底（诚实，但值得核对写点）',
  archivedWhileFrozen: '冻结中却被保留期归档 ⇒ 唤醒入口找不到记录',
};

function loadJson(file) {
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch {
    return null;
  }
}

function scanDataRoot() {
  const out = [];
  const dirDelegates = join(DATA_ROOT, 'delegates');
  const dirTasks = join(DATA_ROOT, 'subagent-tasks');
  if (!existsSync(dirDelegates)) {
    console.error(`环境错误：${dirDelegates} 不存在（--data-root 指错了？）`);
    process.exit(2);
  }
  // ① 落盘冻结槽
  const slots = new Map();
  for (const f of readdirSync(dirDelegates)) {
    if (!f.startsWith('delegate-') || !f.endsWith('.json')) continue;
    const d = loadJson(join(dirDelegates, f));
    if (!d) continue;
    const id = String(d.id || f.replace(/\.json$/, ''));
    const reason = d.frozenReason == null ? '' : String(d.frozenReason).trim();
    const since = d.frozenSince == null ? null : Number(d.frozenSince);
    slots.set(id, { reason, since, archivedAt: d.archivedAt || null });
    const frozen = reason !== '' || since != null;
    if (!frozen) continue;
    if ((reason === '') !== (since == null)) {
      out.push({ id, kind: 'slotHalfWritten', detail: `frozenReason=${reason || 'none'} frozenSince=${since}` });
    }
    const known = ['schedule', 'llm-transient', 'network', 'provider-down', 'restart-recovery', 'waiting-network', 'loop'];
    const isDelegateWire = reason.indexOf('delegate-frozen') === 0;
    if (reason !== '' && !isDelegateWire && !known.includes(reason)) {
      out.push({ id, kind: 'unknownReason', detail: `reason=${reason}` });
    }
    if (d.archivedAt) out.push({ id, kind: 'archivedWhileFrozen', detail: `archivedAt=${d.archivedAt}` });
  }
  // ② 台账行
  if (existsSync(dirTasks)) {
    for (const f of readdirSync(dirTasks)) {
      if (!f.endsWith('.json')) continue;
      const d = loadJson(join(dirTasks, f));
      const rows = Array.isArray(d) ? d : (d && (d.tasks || d.rows)) || [];
      const list = Array.isArray(rows) ? rows : Object.values(rows || {});
      for (const r of list) {
        if (!r || typeof r !== 'object') continue;
        const id = String(r.id || r.taskId || '');
        if (!id) continue;
        const status = String(r.status || '');
        const slot = slots.get(id);
        const slotFrozen = !!slot && (slot.reason !== '' || slot.since != null);
        if (slotFrozen && status !== 'frozen') {
          out.push({ id, kind: 'slotFrozenLedgerNot', detail: `slot=${slot.reason || slot.since}, ledger=${status}` });
        }
        if (!slotFrozen && status === 'frozen') {
          out.push({ id, kind: 'ledgerFrozenSlotClear', detail: `ledger=frozen, slot=${slot ? 'clear' : 'absent'}` });
        }
      }
    }
  }
  return { scanned: slots.size, rows: out };
}

// ══ ② 面板行对账 ═══════════════════════════════════════════════════════
function scanPanelRows(file) {
  const rows = loadJson(file);
  if (!Array.isArray(rows)) {
    console.error(`环境错误：--panel 文件必须是行数组（${file}）`);
    process.exit(2);
  }
  const out = [];
  let noBlock = 0;
  for (const r of rows) {
    const block = mod.freezeBlockOf(r);
    if (!block || !block.asOf) {
      // 旧后端无权威块 ⇒ 无法对账（不冒充「一致」）。
      noBlock += 1;
      continue;
    }
    const shown = rendered(r);
    // 直判陈旧的冻结标记：面板行还挂着 frozen=true，而权威块说「已不是冻结态」
    // —— 今天这一格（Mail 唤醒清槽、面板没收到帧）就是它。与下面的渲染判据分开，
    // 因为它检的是**行数据本身**，不依赖合并规则（合并规则坏了才要这一条兜底）。
    if (r.frozen === true && block.frozen === false) {
      out.push({
        id: String(r.sessionId || ''),
        kind: 'stalePanelFrozenFlag',
        detail: `panel.frozen=true 而 backend.frozen=false (status=${block.status}, asOf=${block.asOf}) ` +
          '—— 冻结标记陈旧：唤醒/自动恢复后没有降级',
      });
      continue;
    }
    // 后端说「冻结且需人」⇔ 面板给按钮；后端说「不冻结」⇔ 面板不得画冻结行。
    const backendWantsButton = block.frozen && block.wakeable;
    if (shown.wakeButton !== backendWantsButton) {
      out.push({
        id: String(r.sessionId || ''),
        kind: 'panelButtonVsBackend',
        detail: `panel.button=${shown.wakeButton} backend.wakeable=${backendWantsButton} ` +
          `(frozen=${block.frozen}, kind=${block.kind}, status=${block.status}, asOf=${block.asOf})`,
      });
    }
    if (block.frozen !== shown.frozenRow) {
      out.push({
        id: String(r.sessionId || ''),
        kind: 'panelRowVsBackend',
        detail: `panel.frozenRow=${shown.frozenRow} backend.frozen=${block.frozen} (status=${block.status})`,
      });
    }
  }
  return { scanned: rows.length - noBlock, noBlock, rows: out };
}

// ══ ③ 今日 case 复现 ═══════════════════════════════════════════════════
function todayCase() {
  // 实录事实（本地数据根 + 链路审计可复核）：
  //   · 10:41:27–10:41:44 六个会话经 panel-wake 唤醒（chains/<id>.json
  //     kind=delegate-frozen-wake），delegate-nebflow-f7c6cd3a 在列；
  //   · 唤醒后 delegates/delegate-nebflow-f7c6cd3a.json 的冻结槽为空
  //     （frozenSince/frozenReason 均 null）；
  //   · 旧实现的 Mail/面板唤醒腿**不发状态变更帧**（onWoken 无 wsSend），
  //     面板行的冻结标记按「快照不表态就保留本地值」单调保留。
  const frozenBlock = {
    frozen: true, kind: 'needs-human', needsHuman: true, autoRecoverable: false, wakeable: true,
    reasonWire: 'delegate-frozen:llm-failure', since: 1793846400000, status: 'Frozen',
    source: 'both', asOf: 1793846400000,
  };
  const wokenBlock = {
    frozen: false, kind: '', needsHuman: false, autoRecoverable: false, wakeable: false,
    reasonWire: '', since: null, status: 'Processing', source: 'none', asOf: 1793847089000,
  };

  // 旧行为复刻：快照/唤醒块缺席时「冻结标记单调保留」（main.js 旧合并规则）。
  const oldPanel = Object.assign({ sessionId: 'delegate-nebflow-f7c6cd3a' }, {
    frozen: true, freezeKind: 'needs-human', needsHuman: true,
    freezeReasonWire: 'delegate-frozen:llm-failure', delegateFrozen: true,
  });
  const oldShown = rendered(oldPanel);
  const oldBackend = wokenBlock; // 后端此刻的真相：不是冻结态
  const oldContradiction = oldShown.wakeButton !== (oldBackend.frozen && oldBackend.wakeable);

  // 新行为：权威块按 asOf 定序落进行（含 frozen=false 的降级块）。
  const newPanel = { sessionId: 'delegate-nebflow-f7c6cd3a', status: 'Frozen' };
  mod.mergeFreeze(newPanel, frozenBlock);
  const shownWhileFrozen = rendered(newPanel);
  mod.mergeFreeze(newPanel, wokenBlock);
  const newShown = rendered(newPanel);
  const newContradiction = newShown.wakeButton !== (wokenBlock.frozen && wokenBlock.wakeable);

  // 矛盾计数 = **修复后**的读数（验收 5：修复后零矛盾）。旧合并复现成功只作为
  // 「本工具确实能抓到这一类矛盾」的证据，不参与退出码。
  const rows = [];
  if (newContradiction) {
    rows.push({
      id: 'delegate-nebflow-f7c6cd3a',
      kind: 'panelButtonVsBackend',
      detail: `新合并仍矛盾：panel.button=${newShown.wakeButton} backend.wakeable=false`,
    });
  }
  if (!oldContradiction) {
    rows.push({
      id: 'delegate-nebflow-f7c6cd3a',
      kind: 'reproductionFailed',
      detail: '旧合并未复现报障（工具本身失效，先修扫描器）',
    });
  }
  return {
    scanned: 1,
    reproduce: {
      frozenStageWakes: shownWhileFrozen.wakeButton === true,
      oldContradiction,
      newContradiction,
      newShown,
    },
    rows,
  };
}

// ══ 输出 ═══════════════════════════════════════════════════════════════
let result;
if (PANEL) result = scanPanelRows(PANEL);
else if (flag('--today-case')) result = todayCase();
else result = scanDataRoot();

if (AS_JSON) {
  console.log(JSON.stringify({ mode: PANEL ? 'panel' : flag('--today-case') ? 'today-case' : 'data-root', ...result }, null, 2));
} else {
  const mode = PANEL ? `面板行对账（${PANEL}）` : flag('--today-case') ? '今日 case 复现' : `全量扫描（${DATA_ROOT}）`;
  console.log(`冻结状态一致性扫描 — ${mode}`);
  console.log(`  扫描会话数：${result.scanned}${result.noBlock ? `（其中 ${result.noBlock} 行旧后端无权威块，跳过对账）` : ''}`);
  if (result.reproduce) {
    const r = result.reproduce;
    console.log(`  冻结期行渲染唤醒按钮：${r.frozenStageWakes}（应 true）`);
    console.log(`  旧合并（单调保留）矛盾：${r.oldContradiction}（应 true = 复现报障）`);
    console.log(`  新合并（asOf 定序）矛盾：${r.newContradiction}（应 false = 修复生效）`);
  }
  if (result.rows.length === 0) {
    console.log('  矛盾：0 条');
  } else {
    console.log(`  矛盾：${result.rows.length} 条`);
    for (const row of result.rows) {
      console.log(`   - [${row.kind}] ${row.id} — ${row.detail}`);
      if (CONTRADICTIONS[row.kind]) console.log(`     后果：${CONTRADICTIONS[row.kind]}`);
    }
  }
}

if (result.rows.length > 0) process.exit(1);
console.log('冻结状态一致性：零矛盾。');
