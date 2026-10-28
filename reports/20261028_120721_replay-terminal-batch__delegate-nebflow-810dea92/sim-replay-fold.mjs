// sim-replay-fold.mjs — 回放折叠「A 根治」实施批的自证仿真（同判据重跑）。
//
// 对真实会话转录快照（<sid>.ui.json，只读）按**前端回放渲染器同一判据**做
// 轮段落分割与 done 判定，分三个相位重跑同一份快照：
//   A「现状回放」  — 旧渲染器判据（徽章只在 text 非空且 durationMs>0 时渲染，
//                    findDoneBadge 只认 nf-phrase）⇒ 裸段落/裸工具卡基线；
//   B「新渲染器×旧数据」— 本批纯前端面（徽章出 if (m.text)）对**旧引擎写出的
//                    转录**即可回收的裸面（审计裸面①/E6 有徽章无行面）；
//   C「新渲染器×新写者仿真」— 仿真新后端写者（done/agentDone 落盘点给每段
//                    末条 ai 行盖 terminal=true；快照时刻仍在进行的尾段除外）
//                    ⇒ 数据面判据下的裸面残余。
//
// 判据对齐（源码读数，本批改后树）：
//   · 分割（buildTurnSegments ≙ turnGroup.buildTurnSummariesForHistory）：
//       opener = 非 injected 的 user 行，且 answerOf 行仅当上一段已有 done 标记
//       时开新段（priorTurnBadgeSettled 的回放口径）；injected 行在「已有 done
//       标记的段后」切开（#403 注入腿重开）。
//   · done 标记：
//       A = 段内存在 ai 行 text 非空 && durationMs>0（旧：徽章裹在 if (m.text) 里）
//       B = 段内存在 ai 行 durationMs>0（新渲染器：徽章无条件渲染）
//       C = B ∪ 段内存在 terminal 行（写者仿真）
//   · 裸段 = 段内存在可折行（tool/thinking）且无 done 标记。E6（段内全部可折、
//     无任何非可折行）单列——旧判据 E6 直接 return []，新判据下 terminal 行/
//     徽章行本身即非可折行，E6 随之消解。
//   · 失败轮/快照时刻进行中的尾段：无 done 标记 ⇒ 维持平铺/展开（设计语义，
//     非回归面）。写者仿真不给尾段盖 terminal（done 尚未发生）。
//
// 用法：node sim-replay-fold.mjs [ui.json 路径]
//       缺省 = ~/.stand/sessions/d7c3cb54-dd90-4f3f-bbf6-e7898c425cff.ui.json
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const file = process.argv[2] || path.join(os.homedir(), '.stand/sessions/d7c3cb54-dd90-4f3f-bbf6-e7898c425cff.ui.json');
const rows = JSON.parse(fs.readFileSync(file, 'utf8'));

// ---- 渲染行流（buildHistoryRow 的行产出映射；仅取判据相关面）----
// user: m.injected&&m.source ⇒ injected 行；answerOf ⇒ 作答行（条件 opener）
// ai:   thinking ⇒ thinking 行；text 或 durationMs>0（新）⇒ ai 行
// tool: tool 行；system: 常规行；askUser: 卡行（非可折）；其余照旧
function renderedStream(msgs, { badgeOutsideText }) {
  const out = [];
  for (const m of msgs) {
    if (m.type === 'user') {
      if (m.injected && m.source) out.push({ kind: 'injected', m });
      else out.push({ kind: 'user', m });
    } else if (m.type === 'ai') {
      if (m.thinking) out.push({ kind: 'thinking', m });
      const hasBadge = m.durationMs != null && m.durationMs > 0;
      const renders = (badgeOutsideText ? hasBadge : (hasBadge && !!(m.text && m.text.trim()))) || !!(m.text && m.text.trim());
      if (renders || m.terminal === true) out.push({ kind: 'ai', m });
    } else if (m.type === 'tool') {
      out.push({ kind: 'tool', m });
    } else if (m.type === 'system' && m.i18nKey === 'slash.skillActivated') {
      // 并入前一 user 行的 skill 气泡，不产独立行
    } else if (m.type === 'error') {
      // 瞬态，不渲染
    } else {
      out.push({ kind: m.type, m });
    }
  }
  return out;
}

const isDoneMarked = (seg, phase) => {
  const hasOldBadge = seg.some(r => r.kind === 'ai' && r.m.durationMs > 0 && !!(r.m.text && r.m.text.trim()));
  const hasNewBadge = seg.some(r => r.kind === 'ai' && r.m.durationMs > 0);
  if (phase === 'A') return hasOldBadge;
  if (phase === 'B') return hasNewBadge;
  return hasNewBadge || seg.some(r => r.kind === 'ai' && r.m.terminal === true);
};

// ---- 分割（opener / injected 重开 / answerOf 条件 opener）----
function segment(stream, phase) {
  const segs = [];
  let cur = [];
  const prevSettled = () => cur.length === 0 || isDoneMarked(cur, phase);
  for (const r of stream) {
    if (r.kind === 'user') {
      const isAnswer = !!r.m.answerOf;
      if (!isAnswer || prevSettled()) {
        if (cur.length) segs.push(cur);
        cur = [r];
        continue;
      }
      cur.push(r); // 作答行并入未闭合交换（mid-turn）
    } else if (r.kind === 'injected') {
      if (cur.length && isDoneMarked(cur, phase)) { segs.push(cur); cur = [r]; }
      else cur.push(r);
    } else {
      cur.push(r);
    }
  }
  if (cur.length) segs.push(cur);
  return segs;
}

function judge(segs, phase) {
  const bare = [];
  segs.forEach((seg, i) => {
    const tuckable = seg.filter(r => r.kind === 'tool' || r.kind === 'thinking');
    if (tuckable.length === 0) return;              // E5：无可折行 — 无需 header
    const nonTuckable = seg.some(r => !(r.kind === 'tool' || r.kind === 'thinking'));
    const tools = seg.filter(r => r.kind === 'tool').length;
    if (isDoneMarked(seg, phase)) return;           // 有 header（折）
    // 无 header 的段：区分 E6（全可折）与普通裸段
    bare.push({ idx: i, tools, e6: !nonTuckable, tail: i === segs.length - 1, len: seg.length });
  });
  return bare;
}

function faceOf(seg) {
  if (seg.some(r => r.kind === 'ai' && r.m.durationMs > 0 && !(r.m.text && r.m.text.trim()))) return 'F1(dur>0,text空)';
  if (seg.some(r => r.kind === 'ai')) return 'F2(无dur)';
  return 'F0(段内零ai行)';
}

const streamFor = (phase) => renderedStream(rows, { badgeOutsideText: phase !== 'A' });

// 相位 C：写者仿真 —— 每段（尾段除外）末条 ai 行盖 terminal=true
function withWriterEmulation() {
  const msgs = rows.map(m => ({ ...m }));
  const stream = renderedStream(msgs, { badgeOutsideText: true });
  const segs = segment(stream, 'B');
  let stamped = 0;
  for (let s = 0; s < segs.length - 1; s++) {           // 尾段 = 快照时刻在飞，不盖
    const aiRows = segs[s].filter(r => r.kind === 'ai');
    if (aiRows.length === 0) continue;                   // 段内零 ai 行：无载体（残余边）
    const last = aiRows[aiRows.length - 1];
    // 找回原数组对象（renderedStream 引用同一对象）
    last.m.terminal = true;
    stamped++;
  }
  return { msgs, stamped };
}

const report = { file, totalRows: rows.length };
for (const phase of ['A', 'B']) {
  const segs = segment(streamFor(phase), phase);
  const bare = judge(segs, phase);
  report[phase] = {
    segments: segs.length,
    bareSegments: bare.length,
    bareToolCards: bare.reduce((n, b) => n + b.tools, 0),
    e6Bare: bare.filter(b => b.e6).length,
    tailBare: bare.filter(b => b.tail).length,
    bareDetail: bare.slice(0, 30).map(b => ({ tools: b.tools, e6: b.e6, tail: b.tail })),
  };
}
{
  const { msgs, stamped } = withWriterEmulation();
  const segs = segment(renderedStream(msgs, { badgeOutsideText: true }), 'C');
  const bare = judge(segs, 'C');
  const bareATail = bare.filter(b => !b.tail);
  report.C = {
    writerStamps: stamped,
    segments: segs.length,
    bareSegments: bare.length,
    bareToolCards: bare.reduce((n, b) => n + b.tools, 0),
    bareExclTail: bareATail.length,
    bareExclTailToolCards: bareATail.reduce((n, b) => n + b.tools, 0),
    bareExclTailDetail: bareATail.map(b => {
      const seg = segs[b.idx];
      return { tools: b.tools, e6: b.e6, face: faceOf(seg), len: seg.length };
    }),
  };
}
console.log(JSON.stringify(report, null, 2));
