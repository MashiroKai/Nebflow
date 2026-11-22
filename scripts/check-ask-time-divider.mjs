#!/usr/bin/env node
// check-ask-time-divider.mjs — canvas Ask 时间分隔线门禁（功能 · canvas ask 会话显示时间分隔线）
//
// 口径（作者令）：① 会话开头显示起始时间；② 相邻消息间隔 > 5 分钟 ⇒ 段界插居中分隔线；
// ③ 标签：当天 HH:mm / 昨天 HH:mm / 本年 MM-DD HH:mm / 跨年带年份；④ 样式 = 细线 + 小字。
//
//   A 组（静态结构）
//     S1 纯模块在场、零 import（无 DOM / 无存储 / 无 i18n）
//     S2 askDialog.js 消费分段单点并渲染 kind:'divider' 行（落在 render 的 desired 构造里）
//     S3 时间源 = 既有 ts 字段：实时落行处（user/ai/error/say/注入）均带 ts: Date.now()
//     S4 落盘保留 ts（askDock.js persistRow），旧行缺席不落键
//     S5 样式在场（ask.css `.ask-time-divider`，细线 + 小字，零新材质 token）
//     S11 服务端回放下发 timestamp（WsAskHandlers.encodeTranscript，仅 >0）
//     S6 无新状态：分隔线模块与 askDialog 不新建 localStorage 键
//   B 组（行为：真跑纯模块，不是 grep）
//     S7 分段：会话开头必插；间隔 > 阈值插；= 阈值不插；无时间行不起段不断段
//     S8 标签：当天 HH:mm；昨天「昨天 HH:mm」；本年「MM-DD HH:mm」；跨年「YYYY-MM-DD HH:mm」
//     S9 续传：服务端行缺 ts 时沿用本地同位同角色同文行的 ts；服务端已有 ts 不覆盖；文本不符不续
//     S10 msgTs：缺席 / 0 / 负数 / NaN ⇒ 0；timestamp 字段可被读取
//
// 检测力自证：把 askDialog.js 里 `kind === 'divider'` 分支删掉 ⇒ S2 红；
//   把 timeDividerIndices 的 `>` 改 `>=` ⇒ S7 红。
//
// Usage: node scripts/check-ask-time-divider.mjs      exit 0 = clean；exit 1 = 违规逐条列出
// 环境变量 ASK_TIME_DIVIDER_WEB_ROOT 可指向另一份 web 树（取证「改前红」用）。

import { readFileSync, existsSync, writeFileSync, mkdtempSync, rmSync } from 'node:fs';
import { join, relative } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const WEB = process.env.ASK_TIME_DIVIDER_WEB_ROOT || join(ROOT, 'src', 'main', 'resources', 'web');
const JS = join(WEB, 'js');
const MODULE = join(JS, 'askTimeDivider.js');
const DIALOG = join(JS, 'askDialog.js');
const DOCK = join(JS, 'askDock.js');
const CSS = join(WEB, 'css', 'ask.css');

const failures = [];
const passes = [];
function check(code, ok, file, msg) {
  if (ok) passes.push(code);
  else failures.push({ code, file: file ? relative(ROOT, file) : '(none)', msg });
}
const read = (f) => (existsSync(f) ? readFileSync(f, 'utf8') : '');

if (!existsSync(MODULE)) {
  console.error(`环境错误：找不到 ${MODULE}`);
  process.exit(1);
}

// ── A 组 ─────────────────────────────────────────────────────────────────
const modSrc = read(MODULE);
check('S1', !/^\s*import\s/m.test(modSrc) && /export\s+function\s+timeDividerIndices\s*\(/.test(modSrc),
  MODULE, '纯模块必须零 import 且导出 timeDividerIndices');

const dlgSrc = read(DIALOG);
check('S2', /from\s+['"]\.\/askTimeDivider\.js['"]/.test(dlgSrc)
  && /timeDividerIndices\(vis\)/.test(dlgSrc)
  && /kind:\s*'divider'/.test(dlgSrc)
  && /d\.kind === 'divider'/.test(dlgSrc),
  DIALOG, 'askDialog.js 未消费分段单点或未渲染 divider 行');

const stamped = (dlgSrc.match(/ts:\s*Date\.now\(\)/g) || []).length;
check('S3', stamped >= 5, DIALOG, `实时落行处的 ts: Date.now() 仅 ${stamped} 处（需 ≥5：user/ai/delta/error/say/注入）`);

const dockSrc = read(DOCK);
check('S4', /row\.ts = m\.ts/.test(dockSrc) && /m\.ts > 0/.test(dockSrc),
  DOCK, 'askDock.js persistRow 未保留 ts（或未做缺席不落键）');

const cssSrc = read(CSS);
check('S5', /\.ask-time-divider\s*\{/.test(cssSrc) && /\.ask-time-divider::before/.test(cssSrc)
  && /\.ask-time-divider-label\s*\{/.test(cssSrc),
  CSS, 'ask.css 缺 .ask-time-divider 样式（细线 + 小字）');

// 只看代码（去掉 // 注释），避免头注释里的说明词误伤。
const codeOf = (s) => s.replace(/\/\/.*$/gm, '');
// S11 服务端回放下发时间戳（encodeTranscript user/ai 行仅 >0 时带 timestamp）。
const WS_ASK = join(ROOT, 'src', 'main', 'scala', 'nebflow', 'gateway', 'WsAskHandlers.scala');
const wsSrc = read(WS_ASK);
check('S11', /private\[gateway\] def withTimestamp\(/.test(wsSrc)
  && /withTimestamp\(Json\.obj\("role" -> "user"/.test(wsSrc)
  && /withTimestamp\(Json\.obj\("role" -> "ai"/.test(wsSrc)
  && /if ts > 0L/.test(wsSrc),
  WS_ASK, 'encodeTranscript 未对 user/ai 行下发 timestamp（或缺 >0 闸）');

const newStore = /localStorage/.test(codeOf(modSrc)) || /localStorage\.setItem\(\s*key\(\s*'ask-time/.test(codeOf(dlgSrc));
check('S6', !newStore, MODULE, '分隔线不得新建 localStorage 状态');

// ── B 组：真跑纯模块 ─────────────────────────────────────────────────────
const tmp = mkdtempSync(join(tmpdir(), 'ask-time-divider-'));
let mod;
try {
  const copy = join(tmp, 'askTimeDivider.mjs');
  writeFileSync(copy, modSrc);
  mod = await import(pathToFileURL(copy).href);
} catch (e) {
  check('S7', false, MODULE, `纯模块无法加载：${e && e.message}`);
}

if (mod) {
  const M = 60 * 1000;
  const T0 = new Date(2026, 10, 21, 22, 35, 0).getTime(); // 本地时间 2026-11-21 22:35
  const row = (ts, role = 'user', text = 'x') => ({ role, text, ts });

  // S7 分段
  {
    const rows = [row(T0), row(T0 + 1 * M), row(T0 + 10 * M)];
    const at = mod.timeDividerIndices(rows);
    check('S7', at.has(0) && !at.has(1) && at.has(2) && at.size === 2,
      MODULE, `会话开头 + 间隔>5min 应插两条分隔线，实得 ${JSON.stringify([...at])}`);

    const eq = mod.timeDividerIndices([row(T0), row(T0 + 5 * M)]);
    check('S7', eq.size === 1 && eq.has(0), MODULE, '间隔恰为 5 分钟不应开新段（判据是 > 而非 >=）');

    const gap = mod.timeDividerIndices([row(T0), row(T0 + 6 * M)]);
    check('S7', gap.has(1), MODULE, '间隔 6 分钟应开新段');

    const noTs = mod.timeDividerIndices([row(T0), { role: 'ai', text: 'no-ts' }, row(T0 + 2 * M)]);
    check('S7', noTs.size === 1 && noTs.has(0), MODULE, '无时间行不应起段也不应断段');

    const leading = mod.timeDividerIndices([{ role: 'ai', text: 'legacy' }, row(T0)]);
    check('S7', leading.size === 1 && leading.has(1), MODULE, '会话开头首条带时间行必插（即使前面有无时间行）');

    check('S7', mod.timeDividerIndices([]).size === 0 && mod.timeDividerIndices([{ role: 'user', text: 'a' }]).size === 0,
      MODULE, '空列表或全无时间行 ⇒ 零分隔线（旧历史行零回归）');
  }

  // S8 标签
  {
    const tt = (zh) => zh;
    const fmt = (ms) => {
      const d = new Date(ms);
      return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
    };
    const now = new Date(2026, 10, 21, 23, 0, 0).getTime();
    const lab = (ts) => mod.timeDividerLabel(ts, now, fmt, tt);
    check('S8', lab(new Date(2026, 10, 21, 9, 5).getTime()) === '09:05', MODULE, `当天应为 HH:mm，实得 ${lab(new Date(2026, 10, 21, 9, 5).getTime())}`);
    check('S8', lab(new Date(2026, 10, 20, 22, 35).getTime()) === '昨天 22:35', MODULE, `昨天应为「昨天 22:35」，实得 ${lab(new Date(2026, 10, 20, 22, 35).getTime())}`);
    check('S8', lab(new Date(2026, 10, 19, 22, 35).getTime()) === '11-19 22:35', MODULE, `本年他日应为「11-19 22:35」，实得 ${lab(new Date(2026, 10, 19, 22, 35).getTime())}`);
    check('S8', lab(new Date(2025, 11, 31, 8, 0).getTime()) === '2025-12-31 08:00', MODULE, `跨年应带年份，实得 ${lab(new Date(2025, 11, 31, 8, 0).getTime())}`);
    // 本地日历日口径：现在 = 11-22 00:10，消息 = 11-21 23:59（相差 11 分钟但跨日）⇒ 「昨天 23:59」
    const now2 = new Date(2026, 10, 22, 0, 10).getTime();
    const cross = mod.timeDividerLabel(new Date(2026, 10, 21, 23, 59).getTime(), now2, fmt, tt);
    check('S8', cross === '昨天 23:59', MODULE, `跨本地午夜应按日历日判「昨天」，实得 ${cross}`);
  }

  // S9 续传
  {
    const local = [row(T0, 'user', 'hello'), row(T0 + M, 'ai', 'answer')];
    const server = [{ role: 'user', text: 'hello' }, { role: 'ai', text: 'answer' }];
    const out = mod.carryTs(server, local);
    check('S9', out[0].ts === T0 && out[1].ts === T0 + M, MODULE, '服务端缺 ts 时应沿用本地同位行 ts');
    const keep = mod.carryTs([{ role: 'user', text: 'hello', ts: 42 }], local);
    check('S9', keep[0].ts === 42, MODULE, '服务端已有 ts 不得被覆盖');
    const mismatch = mod.carryTs([{ role: 'ai', text: 'different' }], [row(T0, 'ai', 'answer')]);
    check('S9', mismatch[0].ts === undefined, MODULE, '文本不符时不得续传时间（防错位）');
    check('S9', server[0].ts === undefined, MODULE, 'carryTs 不得改写入参');
  }

  // S10 msgTs
  {
    check('S10', mod.msgTs(null) === 0 && mod.msgTs({}) === 0 && mod.msgTs({ ts: -5 }) === 0
      && mod.msgTs({ ts: NaN }) === 0 && mod.msgTs({ ts: 'x' }) === 0, MODULE, 'msgTs 非法值应返回 0');
    check('S10', mod.msgTs({ timestamp: 1234 }) === 1234 && mod.msgTs({ ts: 7 }) === 7,
      MODULE, 'msgTs 应读取 ts 与 timestamp 字段');
  }

  rmSync(tmp, { recursive: true, force: true });
}

// ── 报告 ─────────────────────────────────────────────────────────────────
if (failures.length) {
  for (const f of failures) console.error(`[${f.code}] ${f.file}: ${f.msg}`);
  console.error(`check-ask-time-divider: FAIL（${failures.length} 项红，${passes.length} 项绿）`);
  process.exit(1);
}
console.log(`check-ask-time-divider: OK（${passes.length} 项断言全绿）`);
