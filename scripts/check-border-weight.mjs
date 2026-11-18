#!/usr/bin/env node
// check-border-weight.mjs — 边框权重闸（dark-border-weight 批 2026-11-18）。
//
// 立案：作者 2026-11-18 18:00 逐字「现在的边框 在暗色模式下 太明显了 需要优化一下」。
// 清点结论（scripts/audit-border-weight.mjs，8 个场景 × 暗亮两档 × 真 Chromium 渲染）：
//   暗档全站边框读数里，**同一条白光细边**在亮档落在 #f5f6f8 上是 ratio 1.028 /
//   ΔE00 0.89（几乎不可见），在暗档落在 #13161c 上是 ratio 3.241 / ΔE00 27.11
//   （一条亮线）。根因 = 透明色描边的强度是 α × 底色，同一个 α 在两种底色上
//   不是同一种强度。另一处同类 = AI 气泡里写死 `#eee` 且无暗档覆写的 hr。
//
// ── 四条判据 ────────────────────────────────────────────────────────────
//  B1 暗档绝对上限（computed）：暗档下每条**中性**（Lab chroma ≤ 6）边框线的
//     合成对比度 ≤ 1.55 且 ΔE00 ≤ 10。阈值来源 = 站内既有暗档中性发丝档实测上缘
//     （--nf-field-border-color 1.475 / ΔE00 8.99、--color-border 1.316 / 7.33、
//     --color-frame-border 1.255 / 6.00）—— 阈值必须容得下站内既有档，否则闸
//     会去改既有正确面。彩度高的描边（品牌绿发送键 6.08、危险红 6.06、
//     sapphire tint 1.61）是**故意可读的语义缘**，不属「发丝边」，按 chroma 排除。
//  B2 暗亮同权重（computed）：两档都渲染到的同一面，暗档 ΔE00 不得比亮档高过
//     PARITY_MAX（默认 5.0）。这条抓的正是「同一个值照搬到暗档」这类错——它不靠
//     绝对阈值，靠两档的差。
//  B3 玻璃细边单源（static）：`--nf-glass-border-color` 必须**恰好**在四个解析块
//     里出现，且每处都是发丝边族的 var() 组合（零字面量）；发丝边族的三个 α/
//     基色槽只许在 stand-tokens.css 定义一次。第二处写死 = 红。
//  B4 边框字面量台账（static，两表）：全站边框类声明里「近白（相对亮度 ≥ .55）
//     字面量颜色」与「彩色（Lab chroma > 6）字面量颜色」分别按文件计数，均不得超过
//     冻结台账（各批 tip 实测值，只许降不许升）。与 design-token 基线闸同规：
//     存量不追溯，新增即红。
//
// ── 彩色语义面判据（C 组 · dark-border-color 批 2026-11-18）──────────────
//  判据对象 = brief 指名的 α .35 语义面（dark-border-color 批三枚指示环 ＋
//  dark-border-family 批两枚同族 chip，共五枚；见 COLORED_RING_FACES 注册表）。
//  它们**不复用** B1/B2 的阈值：中性发丝边的上限（比 ≤1.55 / ΔE00 ≤10）是拿白光
//  细边标定出来的，彩边还要同时承担色相载荷，两条读数的增长速率不同（同底同 α，
//  品牌绿的 ΔE00 增长比白光快，对比度却慢），把中性阈值套上去会去砍真正要保的东西。
//  故 C 组**从不变式推导**，与 B 组分开、各自具名（作者 2026-11-18 裁定）：
//
//  C1 不放大（computed，继承 B2 语义）：同一面**暗档 ΔE00 ≤ 亮档 ΔE00**。
//     抓的正是「同一个值照搬到暗档」——不靠绝对阈值，靠两档的差。
//  C2 色相可指认（computed）：合成 Lab 色相距**色相族轴** ≤ 10°（绿 180° / 蓝 270°），
//     且合成 chroma ≥ 相邻底色 chroma × 1.5。
//     为什么拿「色相族轴」而当面色的通道色相当基准：品牌绿通道自身是 148.8°（黄绿—
//     绿带内的一个点），若按通道自身色相判 ≤10°，则与 C1/C3 **无交集**（对
//     α ∈ [0.01,0.35] 穷举得 0 解，见 stand-tokens.css 彩边族注）——判据不可满足。
//     轴即「这枚环还读不读成它那个色名」，α .06 的合成 h* 205°（青蓝侧）被判红，
//     与作者裁定里「A 把绿洗到 h*=205° 方向错了」逐字一致。
//  C3 亮度权重（computed）：暗档对比度 ∈ 1.16–1.48（= 站内既有暗档中性发丝档带，
//     见 stand-tokens.css 中性发丝边族注，非另拍数字）。
//  C4 单源与零字面量（static）：彩边族通道/α 槽只许在 stand-tokens.css 定义一次；
//     四个解析块（:root / html[data-theme=dark] / @media dark / html[data-theme=light]）
//     的 --nf-hairline-tint-* 各**恰好四处**且全是纯 var() 引用；三枚消费面在
//     chat.css 里的边框声明只许 var(--nf-hairline-tint-*)、零字面量。
//
//  判据的参照面：注册表里的 ref = `outer`（元素**外侧**底色）。理由 = ①环/芯片是画在
//  面板上的，它读成的「轮廓」就是与面板的边界；②改前读数（brief 表）里两枚正是 outer，
//  固定参照面才让改前/改后逐条可比；③装置 measure() 取 self/outer 的**较大对比度**，
//  而这条规则在 α 降到 ~0.21 处会**翻面**（实测），参照面若跟着翻，判据就不再是同一个
//  对象。故 C 组显式取 outer；B 组仍用既有 measure()（不动，保持与改前基线同装置）。
//
// 用法：
//   node scripts/check-border-weight.mjs                 # 门禁（红/绿）
//   node scripts/check-border-weight.mjs --json          # 机器读
//   node scripts/check-border-weight.mjs --scenes shell-chat,mobile-chat
//   node scripts/check-border-weight.mjs --root <web根>  # 跑在另一检出/导出的 web 根上
//   node scripts/check-border-weight.mjs --freeze        # 打印 B4 台账（改台账时用）
// 退出码：0 = 绿；1 = 红；2 = 环境错误。

import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { loadPlaywright, launchBrowser, createSession, settle, setWebRoot, ROOT as REPO_ROOT } from './lib/visual-harness.mjs';
import { BORDER_SCENES, COLLECT_BORDERS_FN, MOBILE_BREAKPOINT, mobileLayerState } from './lib/border-scenes.mjs';
import { borderMetrics, rgb2lab, hex, parseColor } from './lib/color-metrics.mjs';

const argv = process.argv.slice(2);
const valOf = (f, d = null) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const JSON_OUT = argv.includes('--json');
const FREEZE = argv.includes('--freeze');
const SCENES_FILTER = valOf('--scenes');
const ROOT_OVERRIDE = valOf('--root');
if (ROOT_OVERRIDE) setWebRoot(ROOT_OVERRIDE);

// ── 阈值（常量化 + 出处，改阈值先改上面注释）─────────────────────────────
const NEUTRAL_CHROMA_MAX = 6;    // 中性描边判定（Lab chroma）
const DARK_CONTRAST_MAX = 1.55;  // B1 暗档对比度上限（站内既有暗档发丝档上缘 1.475 + 余量）
const DARK_DELTAE_MAX = 10.0;    // B1 暗档 ΔE00 上限（站内上缘 8.99 + 余量）
const PARITY_DELTAE_MAX = 5.0;   // B2 暗亮 ΔE00 差上限

// ── C 组阈值（彩色语义面；出处见文件头 C 组注，全部由不变式/站内既有带推导）──
const C_C3_BAND = [1.16, 1.48];  // C3 亮度权重带 = 站内既有暗档中性发丝档带
const C_C2_HUE_MAX = 10;         // C2 合成 Lab 色相距色相族轴上限（°）
const C_C2_CHROMA_MIN = 1.5;     // C2 合成 chroma ≥ 相邻底色 chroma × 该值

/**
 * C 组判据对象注册表 = brief 指名的语义面（dark-border-color 批三枚指示环 ＋
 * dark-border-family 批两枚同族 chip）。键 = 场景 | 探针 sig | 边框类。
 * ref 固定 outer（见文件头 C 组注），axis = 色相族轴（Lab a*b* 度数：绿 180° / 蓝 270°）。
 */
const COLORED_RING_FACES = [
  { key: 'dropdown|div#bg-indicator|border', name: '后台任务指示环(绿)', axis: 180 },
  { key: 'dropdown|span.bg-task-origin.bg-origin-delegate|border', name: 'delegate 归属环(蓝)', axis: 270 },
  { key: 'dropdown|div#bgagent-indicator|border', name: '后台 agent 指示环(蓝)', axis: 270 },
  // dark-border-family 批（2026-11-18 作者令「#colored-edge-family-rest 做」）：与
  // delegate 归属 chip **同族同值**（改前两处各写 α .35 字面量）的两枚面一并收进
  // 彩边族。判据（C1–C3）与参照面（固定 outer）**逐字沿用**，不新起第二闸、不放宽。
  { key: 'dropdown|span.bg-task-origin.bg-origin-node|border', name: 'node 归属环(蓝)', axis: 270 },
  { key: 'dropdown|span.bg-task-mode.bg-mode-kernel|border', name: 'kernel 模式环(蓝)', axis: 270 },
];

/** 发丝边族槽位：只许在 stand-tokens.css 定义一次。 */
const HAIRLINE_SLOTS = ['--nf-hairline-ink', '--nf-hairline-a', '--nf-hairline-a-dark'];
/** 语义彩边族槽位（dark-border-color 批）：同样只许在 stand-tokens.css 各定义一次。 */
const COLORED_HAIRLINE_SLOTS = [
  '--nf-hairline-green-ink', '--nf-hairline-green-a', '--nf-hairline-green-a-dark',
  '--nf-hairline-sapphire-ink', '--nf-hairline-sapphire-ink-dark',
  '--nf-hairline-sapphire-a', '--nf-hairline-sapphire-a-dark',
];
/** 彩边族解析别名 → 合法引用形态（值只许是族内「通道 / α」组合，零字面量）。 */
const COLORED_TINT_RES = {
  '--nf-hairline-tint-green': /^rgb\(\s*var\(--nf-hairline-green-ink\)\s*\/\s*var\(--nf-hairline-green-a(?:-dark)?\)\s*\)$/,
  '--nf-hairline-tint-sapphire': /^rgb\(\s*var\(--nf-hairline-sapphire-ink(?:-dark)?\)\s*\/\s*var\(--nf-hairline-sapphire-a(?:-dark)?\)\s*\)$/,
};
/** 彩色语义面（CSS 侧唯一写点）：只许写 var(--nf-hairline-tint-*)，零字面量。 */
const COLORED_CONSUMER_SITES = [
  { file: 'css/chat.css', selector: '#bg-indicator', token: '--nf-hairline-tint-green' },
  { file: 'css/chat.css', selector: '#bgagent-indicator', token: '--nf-hairline-tint-sapphire' },
  { file: 'css/chat.css', selector: '.bg-task-origin.bg-origin-delegate', token: '--nf-hairline-tint-sapphire' },
  // dark-border-family 批：两枚同族面并入同一消费面清单（C4 零字面量 + 单源）
  { file: 'css/chat.css', selector: '.bg-task-origin.bg-origin-node', token: '--nf-hairline-tint-sapphire' },
  { file: 'css/chat.css', selector: '.bg-task-mode.bg-mode-kernel', token: '--nf-hairline-tint-sapphire' },
];
/** 玻璃细边的四个解析块（:root / 强制暗 / 系统暗 / 强制亮）—— 恰好四份，见文件结构注。 */
const GLASS_BORDER_SITES = 4;
/** 发丝边族的合法引用形态（值必须同时引用通道基色与 α 档）。 */
const HAIRLINE_REF_RE = /^rgb\(\s*var\(--nf-hairline-ink\)\s*\/\s*var\(--nf-hairline-a(?:-dark)?\)\s*\)$/;

/**
 * B4 台账（近白边框字面量按文件计数；本批 tip 实测，只许降不许升）。
 * 数值由 `node scripts/check-border-weight.mjs --freeze` 生成。
 */
const NEAR_WHITE_LITERAL_LEDGER = {
  '_note': '键 = 相对仓库根的路径；值 = 该文件里「边框类声明中的近白发丝边字面量」条数（近白不透明，或半透明白 α ≥ .15）。新增/升高 ⇒ 红。',
  'src/main/resources/web/css/base.css': 1,
  // 16 → 15：dark-border-color 批把 #bg-indicator 环上那枚 rgba(7,193,96,.35) 字面量
  // 收进彩边族（它照亮度算 0.574，一直躺在近白表里）。台账下调即可抓住「写回字面量」。
  'src/main/resources/web/css/chat.css': 15,
  'src/main/resources/web/css/diffView.css': 2,
  'src/main/resources/web/css/flowMap.css': 9,
  'src/main/resources/web/css/input.css': 4,
  'src/main/resources/web/css/modal.css': 1,
  'src/main/resources/web/css/nav.css': 1,
  'src/main/resources/web/css/perception.css': 2,
  'src/main/resources/web/css/projectPanel.css': 1,
  'src/main/resources/web/css/reminder.css': 2,
  'src/main/resources/web/css/secrets.css': 1,
  'src/main/resources/web/css/sidebar.css': 5,
  'src/main/resources/web/css/social.css': 1,
  'src/main/resources/web/css/split.css': 3,
  'src/main/resources/web/css/taskList.css': 5,
  'src/main/resources/web/css/todo-face.css': 4,
  'src/main/resources/web/css/voice.css': 3,
  'src/main/resources/web/css/voiceMode.css': 2,
  'src/main/resources/web/js/activityBar.js': 1,
  'src/main/resources/web/js/canvas.js': 3,
  'src/main/resources/web/js/daemons.js': 1,
  'src/main/resources/web/js/flowCss.js': 11,
  'src/main/resources/web/index.html': 4,
};

/**
 * B4 第二表：彩色（Lab chroma > 6）边框字面量按文件计数（dark-border-color 批
 * 2026-11-18 起；数值由 `--freeze` 生成，只许降不许升）。
 * 为什么单列一表：近白表按「相对亮度 ≥ .55」判定，品牌绿 (7 193 96) 照亮度算进来
 * 是 0.574 —— 它落在近白表里纯属巧合，而危险红 / 强调蓝这类有色缘根本进不了近白表，
 * 于是「有色缘被写回字面量」在旧闸下不可见。本批把这一格补上。
 */
const COLORED_LITERAL_LEDGER = {
  '_note': '键 = 相对仓库根的路径；值 = 该文件里「边框类声明中的彩色（Lab chroma > 6）字面量颜色」条数。新增/升高 ⇒ 红。本批 tip 实测，由 --freeze 生成。',
  'src/main/resources/web/css/chat.css': 28,
  'src/main/resources/web/css/diffView.css': 4,
  'src/main/resources/web/css/dropbox.css': 1,
  'src/main/resources/web/css/executors.css': 3,
  'src/main/resources/web/css/flowMap.css': 12,
  'src/main/resources/web/css/input.css': 9,
  'src/main/resources/web/css/modal.css': 7,
  'src/main/resources/web/css/neblink.css': 2,
  'src/main/resources/web/css/perception.css': 4,
  'src/main/resources/web/css/reminder.css': 14,
  'src/main/resources/web/css/sidebar.css': 11,
  'src/main/resources/web/css/split.css': 3,
  'src/main/resources/web/css/taskList.css': 4,
  'src/main/resources/web/css/todo-page.css': 5,
  'src/main/resources/web/css/voice.css': 3,
  'src/main/resources/web/css/voiceMode.css': 2,
  'src/main/resources/web/js/daemonPanel.js': 5,
  'src/main/resources/web/js/daemons.js': 10,
  'src/main/resources/web/js/flowCss.js': 31,
  'src/main/resources/web/index.html': 3,
};

const violations = [];
const add = (c, m) => violations.push({ check: c, message: m });

const WORK = REPO_ROOT;                       // 仓根（脚本所在检出）
const WEB = join(WORK, 'src', 'main', 'resources', 'web');

// ══ B1 / B2：computed ════════════════════════════════════════════════════
const bgHex = (a) => hex(a);
const chromaOf = (rgb) => { const [, a, b] = rgb2lab(rgb); return Math.hypot(a, b); };

function measure(row) {
  const self = borderMetrics(`rgba(${row.color.join(',')})`, bgHex(row.bgSelf));
  const outer = borderMetrics(`rgba(${row.color.join(',')})`, bgHex(row.bgOuter));
  const use = self && (!outer || self.contrast >= outer.contrast) ? self : outer;
  return use
    ? { contrast: use.contrast, deltaE: use.deltaE, rgb: use.rgb, bg: use.bgRgb, ref: use === self ? 'self' : 'outer',
        // C 组固定参照面用的外侧读数（与 B 组的 measure() 选择无关）
        outer: outer ? { contrast: outer.contrast, deltaE: outer.deltaE, rgb: outer.rgb, bg: outer.bgRgb } : null }
    : null;
}

/** 色相族轴距离（Lab a*b* 度数，取短弧）。 */
function axisDistance(hueDeg, axis) {
  const d = Math.abs(((hueDeg % 360) + 360) % 360 - axis);
  return Math.min(d, 360 - d);
}

/**
 * C 组：彩色语义面（三枚指示环）的三条不变式。参照面固定 = 注册面的外侧底色。
 * @param {any[]} rows computed() 采到的全部读数行
 */
function coloredChecks(rows) {
  const table = [];
  for (const face of COLORED_RING_FACES) {
    const pick = (theme) => {
      const rs = rows.filter((r) => `${r.scene}|${r.sig}|${r.kind}` === face.key && r.theme === theme && r.outer);
      if (!rs.length) return null;
      return rs.reduce((a, b) => (b.outer.contrast > a.outer.contrast ? b : a));
    };
    const dark = pick('dark');
    const light = pick('light');
    if (!dark) { add('SCENE', `彩色语义面暗档无读数：${face.key}（该面没渲染 / 场景变了 —— 装置问题，不是产品红）`); continue; }

    const [L, a, b] = rgb2lab(dark.outer.rgb);
    const chroma = Math.hypot(a, b);
    const hue = ((Math.atan2(b, a) * 180 / Math.PI) % 360 + 360) % 360;
    const dAxis = axisDistance(hue, face.axis);
    const bgChroma = chromaOf(dark.outer.bg);
    const row = {
      key: face.key, name: face.name,
      dark: { hex: hex(dark.outer.rgb), bg: hex(dark.outer.bg), contrast: dark.outer.contrast, deltaE: dark.outer.deltaE },
      light: light ? { hex: hex(light.outer.rgb), bg: hex(light.outer.bg), contrast: light.outer.contrast, deltaE: light.outer.deltaE } : null,
      hue: +hue.toFixed(1), axis: face.axis, dAxis: +dAxis.toFixed(1), chroma: +chroma.toFixed(2), bgChroma: +bgChroma.toFixed(2),
      chromaRatio: +(chroma / bgChroma).toFixed(2),
      c1: null, c2: null, c3: null,
    };
    const nm = `${face.name} ${face.key}`;

    // C1 不放大：暗档 ΔE00 ≤ 亮档同面 ΔE00
    if (!light) { add('C1', `彩色语义面亮档无读数，无法判「不放大」：${nm}`); }
    else if (dark.outer.deltaE > light.outer.deltaE) {
      add('C1', `暗档比亮档明显（ΔE00 ${dark.outer.deltaE} > 亮 ${light.outer.deltaE}）：${nm}`);
    }
    row.c1 = !!light && dark.outer.deltaE <= light.outer.deltaE;

    // C2 色相可指认：距色相族轴 ≤ 10° 且 chroma ≥ 底色 × 1.5
    if (dAxis > C_C2_HUE_MAX) {
      add('C2', `合成色相跑出族轴：${nm}  合成 ${hex(dark.outer.rgb)} h* ${row.hue}° vs 轴 ${face.axis}°（差 ${row.dAxis}° > ${C_C2_HUE_MAX}°）`);
    }
    if (chroma < C_C2_CHROMA_MIN * bgChroma) {
      add('C2', `合成不够有色（被洗成中性）：${nm}  合成 C* ${row.chroma} < 底 ${row.bgChroma} × ${C_C2_CHROMA_MIN}（实 ${row.chromaRatio}×）`);
    }
    row.c2 = dAxis <= C_C2_HUE_MAX && chroma >= C_C2_CHROMA_MIN * bgChroma;

    // C3 亮度权重：暗档对比度落在站内既有暗档中性发丝档带内
    if (dark.outer.contrast < C_C3_BAND[0] || dark.outer.contrast > C_C3_BAND[1]) {
      add('C3', `暗档对比度出带：${nm}  ${dark.outer.contrast} ∉ [${C_C3_BAND[0]}, ${C_C3_BAND[1]}]`);
    }
    row.c3 = dark.outer.contrast >= C_C3_BAND[0] && dark.outer.contrast <= C_C3_BAND[1];

    table.push(row);
  }
  return table;
}

async function computed() {
  const scenes = BORDER_SCENES
    .filter((s) => !SCENES_FILTER || SCENES_FILTER.split(',').includes(s.name))
    // 手机场景与桌面场景并行跑没有额外价值，这里按稳定序串行；只跑有边框的两个视口。
    .map((s) => ({ ...s, viewports: s.viewports.slice(0, 1) }));
  const pwRes = loadPlaywright();
  const pw = pwRes.mod.playwright || pwRes.mod;
  const { browser, label } = await launchBrowser(pw);
  const rows = [];
  const sceneErrs = [];
  for (const scene of scenes) {
    for (const vp of scene.viewports) {
      for (const theme of ['dark', 'light']) {
        const s = await createSession(browser, { colorScheme: theme, width: vp.width, height: vp.height, clock: scene.clock });
        try {
          await scene.setup(s);
          await settle(s.page, 250);
          // 手机视口必须先自证真进了手机交互层（否则量到的是一张窄桌面窗的边框）
          if (vp.width <= MOBILE_BREAKPOINT) {
            const st = await mobileLayerState(s.page);
            if (!st.active) add('SCENE', `手机形态不成立：${scene.name}/${theme} 视口 ${vp.width}x${vp.height} 未进 body.mobile-layer（实渲染 ${st.viewport}）`);
          }
          const raw = await s.page.evaluate(COLLECT_BORDERS_FN);
          for (const r of raw) {
            const m = measure(r);
            if (!m) continue;
            rows.push({ scene: scene.name, theme, vp: `${vp.width}x${vp.height}`, sig: r.sig, kind: r.kind, prop: r.prop, width: r.width, chroma: chromaOf(m.rgb), ...m });
          }
        } catch (e) {
          sceneErrs.push(`${scene.name}/${theme}: ${String(e.message).split('\n')[0]}`);
        } finally {
          await s.close();
        }
      }
    }
  }
  await browser.close();

  for (const e of sceneErrs) add('SCENE', `场景采集失败（装置异常，不是产品红）：${e}`);

  const hair = rows.filter((r) => r.chroma <= NEUTRAL_CHROMA_MAX);
  const dark = hair.filter((r) => r.theme === 'dark');

  // B1：暗档中性发丝边绝对上限
  const b1 = new Map();
  for (const r of dark) {
    const k = `${r.scene}|${r.sig}|${r.kind}`;
    const cur = b1.get(k);
    if (!cur || r.contrast > cur.contrast) b1.set(k, r);
  }
  for (const [k, r] of b1) {
    if (r.contrast > DARK_CONTRAST_MAX || r.deltaE > DARK_DELTAE_MAX) {
      add('B1', `暗档发丝边超阈值：${k}  ${r.prop.replace('border-', '')} ${r.width}px  ${hex(r.rgb)} on ${hex(r.bg)}  ratio ${r.contrast} (≤${DARK_CONTRAST_MAX})  ΔE00 ${r.deltaE} (≤${DARK_DELTAE_MAX})`);
    }
  }

  // B2：暗亮同权重（两档都出现的面）
  const byKey = new Map();
  for (const r of hair) {
    const k = `${r.scene}|${r.sig}|${r.kind}`;
    if (!byKey.has(k)) byKey.set(k, {});
    const t = byKey.get(k);
    if (!t[r.theme] || r.deltaE > t[r.theme].deltaE) t[r.theme] = r;
  }
  const b2rows = [];
  for (const [k, t] of byKey) {
    if (!t.dark || !t.light) continue;
    const gap = +(t.dark.deltaE - t.light.deltaE).toFixed(3);
    b2rows.push({ k, gap, dark: t.dark, light: t.light });
    if (gap > PARITY_DELTAE_MAX) {
      add('B2', `暗档比亮档明显（ΔE00 差 ${gap} > ${PARITY_DELTAE_MAX}）：${k}  暗 ${t.dark.contrast}/${t.dark.deltaE}  亮 ${t.light.contrast}/${t.light.deltaE}`);
    }
  }
  const colored = coloredChecks(rows);
  return { browser: label, rows, hairlineRows: hair.length, b1Count: b1.size, b2rows, colored };
}

// ══ B3 / B4：static ══════════════════════════════════════════════════════
const COLOR_LITERAL_RE = /#[0-9a-fA-F]{3,8}\b|\brgba?\([^)]*\)|\bhsla?\([^)]*\)/g;
const BORDER_PROP_RE = /^(border|border-(top|right|bottom|left|color|block|block-start|block-end|inline|inline-start|inline-end)|border-(top|right|bottom|left)-color|outline|outline-color|box-shadow|column-rule|background-image)$/;

function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:])\/\/[^\n]*/g, (m, p) => p + ' '.repeat(m.length - p.length));
}

/** 相对亮度（用于「近白」判定）。 */
function luma(hexStr) {
  const h = hexStr.length === 4 ? hexStr.slice(1).split('').map((c) => c + c).join('') : hexStr.slice(1, 7);
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16));
  return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
}

/** 简易规则遍历（与 check-glass-unify 同法：at-rule prelude 压栈）。 */
function* walkRules(src) {
  const text = stripComments(src);
  const lineOf = (i) => text.slice(0, i).split('\n').length;
  let i = 0; const stack = []; let selStart = 0;
  while (i < text.length) {
    const ch = text[i];
    if (ch === '{') {
      const prelude = text.slice(selStart, i).trim();
      if (prelude.startsWith('@')) { stack.push(prelude.replace(/\s+/g, ' ')); i++; selStart = i; continue; }
      const bodyStart = i + 1; let j = bodyStart; let inner = 0;
      while (j < text.length) {
        if (text[j] === '{') inner++;
        else if (text[j] === '}') { if (inner === 0) break; inner--; }
        j++;
      }
      const body = text.slice(bodyStart, j);
      const decls = []; let cur = ''; let declStart = bodyStart;
      for (let k = 0; k <= body.length; k++) {
        const c = body[k];
        if (c === ';' || k === body.length) {
          const colon = cur.indexOf(':');
          if (colon > 0) decls.push({ prop: cur.slice(0, colon).trim().toLowerCase(), value: cur.slice(colon + 1).trim().replace(/\s+/g, ' '), line: lineOf(declStart) });
          cur = ''; declStart = bodyStart + k + 1;
        } else { if (c === '\n') declStart = bodyStart + k + 1; cur += c; }
      }
      yield { selector: prelude.replace(/\s+/g, ' '), decls, line: lineOf(selStart), atStack: [...stack] };
      i = j + 1; selStart = i; continue;
    }
    if (ch === '}') { if (stack.length) stack.pop(); i++; selStart = i; continue; }
    i++;
  }
}

/**
 * 「近白发丝边字面量」判定 —— 本批的错类形态就两种，两种都收：
 *   ① 不透明近白（#hex 相对亮度 ≥ .55，如 hr 的 #eee）；
 *   ② 半透明白 α ≥ .15（如 `#ffffff59` / `rgba(255,255,255,0.22)`）—— 这一类在
 *      亮底上隐形、在暗底上被放大，正是立案那一条。
 * 半透明白但 α < .15（玻璃内侧高光 0.04–0.10）不算：实测暗档 ΔE00 ≤ 4.5，属正常发丝档。
 */
/** 彩色边框字面量：Lab chroma > 中性阈 ⇒ 它是「一条有色缘」，必须也是单一源。 */
function coloredLiteral(hit) {
  const c = parseColor(hit);
  if (!c) return false;
  return chromaOf([c[0], c[1], c[2]]) > NEUTRAL_CHROMA_MAX;
}

const WHITE_A_MIN = 0.15;
function nearWhiteLiteral(hit) {
  if (hit.startsWith('#')) {
    const h = hit.slice(1);
    const base = h.length >= 6 ? h.slice(0, 6) : h.length === 4 ? h.slice(0, 3).split('').map((c) => c + c).join('') : h.length === 3 ? h : null;
    if (!base) return false;
    const expanded = base.length === 3 ? base.split('').map((c) => c + c).join('') : base;
    const bright = luma(`#${expanded}`);
    const alpha = h.length === 8 ? parseInt(h.slice(6, 8), 16) / 255 : h.length === 4 ? parseInt(h[3] + h[3], 16) / 255 : 1;
    return bright >= 0.55 && alpha >= WHITE_A_MIN;
  }
  const m = /^rgba?\(([^)]*)\)$/i.exec(hit);
  if (!m) return false;
  const parts = m[1].split(/[\s,/]+/).filter(Boolean);
  const [r, g, b] = parts.slice(0, 3).map((p) => (p.endsWith('%') ? parseFloat(p) / 100 * 255 : parseFloat(p)));
  const a = parts.length >= 4 ? (parts[3].endsWith('%') ? parseFloat(parts[3]) / 100 : parseFloat(parts[3])) : 1;
  if (![r, g, b, a].every(Number.isFinite)) return false;
  const bright = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
  return bright >= 0.55 && a >= WHITE_A_MIN;
}

function staticChecks() {
  const tokensPath = join(WEB, 'css', 'stand-tokens.css');
  const tokensRel = relative(WORK, tokensPath);
  if (!existsSync(tokensPath)) { add('B3', `令牌单一源缺失：${tokensRel}`); return { ledger: {} }; }

  // B3-a：玻璃细边的四处解析块必须是发丝边族引用（零字面量）
  const glassDecls = [];
  for (const rule of walkRules(readFileSync(tokensPath, 'utf8'))) {
    for (const d of rule.decls) if (d.prop === '--nf-glass-border-color') glassDecls.push({ ...d, selector: rule.selector });
  }
  if (glassDecls.length !== GLASS_BORDER_SITES) {
    add('B3', `--nf-glass-border-color 解析块数 = ${glassDecls.length}，应为 ${GLASS_BORDER_SITES}（:root / html[data-theme=dark] / @media dark / html[data-theme=light]）`);
  }
  for (const d of glassDecls) {
    if (!HAIRLINE_REF_RE.test(d.value)) {
      add('B3', `${tokensRel}:${d.line}  --nf-glass-border-color: ${d.value} —— 必须引用发丝边族（形如 rgb(var(--nf-hairline-ink) / var(--nf-hairline-a))），不许自带色值`);
    }
  }
  if (!/--nf-glass-border-color\s*:/.test(readFileSync(tokensPath, 'utf8'))) add('B3', '--nf-glass-border-color 未定义');

  // B3-b：发丝边族槽位只许在 stand-tokens.css 定义一次
  for (const slot of HAIRLINE_SLOTS) {
    const seen = [];
    for (const f of readdirSync(join(WEB, 'css')).filter((x) => x.endsWith('.css'))) {
      const p = join(WEB, 'css', f);
      for (const rule of walkRules(readFileSync(p, 'utf8'))) {
        for (const d of rule.decls) if (d.prop === slot && !/^var\(/.test(d.value)) seen.push(`${relative(WORK, p)}:${d.line}`);
      }
    }
    const nonTokens = seen.filter((s) => !s.startsWith(tokensRel));
    if (seen.length !== 1) add('B3', `${slot} 定义处 ${seen.length} 个（应为 1）：${seen.join(', ') || '(无)'}`);
    if (nonTokens.length) add('B3', `${slot} 在令牌单一源之外定义：${nonTokens.join(', ')}`);
  }

  // B3-c / C4-a：语义彩边族的槽位与解析块
  const coloredSites = new Map(COLORED_HAIRLINE_SLOTS.map((s) => [s, []]));
  const tintSites = new Map(Object.keys(COLORED_TINT_RES).map((s) => [s, []]));
  for (const f of readdirSync(join(WEB, 'css')).filter((x) => x.endsWith('.css'))) {
    const p = join(WEB, 'css', f);
    for (const rule of walkRules(readFileSync(p, 'utf8'))) {
      for (const d of rule.decls) {
        if (coloredSites.has(d.prop)) coloredSites.get(d.prop).push({ at: `${relative(WORK, p)}:${d.line}`, value: d.value });
        if (tintSites.has(d.prop)) tintSites.get(d.prop).push({ at: `${relative(WORK, p)}:${d.line}`, value: d.value });
      }
    }
  }
  for (const [slot, sites] of coloredSites) {
    if (sites.length !== 1) add('B3', `${slot} 定义处 ${sites.length} 个（应为 1）：${sites.map((s) => s.at).join(', ') || '(无)'}`);
    const off = sites.filter((s) => !s.at.startsWith(tokensRel));
    if (off.length) add('B3', `${slot} 在令牌单一源之外定义：${off.map((s) => s.at).join(', ')}`);
    const v = sites[0] ? sites[0].value : '';
    const isChannel = /-ink(?:-dark)?$/.test(slot);
    const ok = isChannel
      ? (/^\d{1,3} \d{1,3} \d{1,3}$/.test(v) || /^var\(--[a-z0-9-]+\)$/i.test(v))
      : /^(?:0|1)(?:\.\d+)?$|^\.\d+$/.test(v);
    if (sites.length === 1 && !ok) add('B3', `${slot} 值形态不合法（${isChannel ? '通道应为三元组或单枚令牌引用' : 'α 应为 0–1 数值'}）：${v}`);
  }
  for (const [slot, re] of Object.entries(COLORED_TINT_RES)) {
    const sites = tintSites.get(slot);
    if (sites.length !== GLASS_BORDER_SITES) {
      add('C4', `${slot} 解析块数 = ${sites.length}，应为 ${GLASS_BORDER_SITES}（:root / html[data-theme=dark] / @media dark / html[data-theme=light]）`);
    }
    for (const s of sites) if (!re.test(s.value)) {
      add('C4', `${s.at}  ${slot}: ${s.value} —— 必须引用彩边族（形如 rgb(var(--nf-hairline-green-ink) / var(--nf-hairline-green-a))），不许自带色值`);
    }
  }

  // C4-b：三枚消费面在 chat.css 里的边框声明只许写族引用、零字面量
  for (const site of COLORED_CONSUMER_SITES) {
    const p = join(WEB, site.file);
    if (!existsSync(p)) { add('C4', `消费面文件缺失：${site.file}`); continue; }
    const hits = [];
    for (const rule of walkRules(readFileSync(p, 'utf8'))) {
      if (rule.selector !== site.selector) continue;
      for (const d of rule.decls) if (BORDER_PROP_RE.test(d.prop)) hits.push({ ...d, selector: rule.selector });
    }
    if (!hits.length) { add('C4', `${site.file} 里 ${site.selector} 没有任何边框类声明（该面被删或改了写法）`); continue; }
    for (const d of hits) {
      const lit = [...d.value.matchAll(COLOR_LITERAL_RE)].map((m) => m[0]).filter((h) => !(/^rgba?\(/i.test(h) && /var\(/.test(h)));
      if (lit.length) add('C4', `${site.file}:${d.line}  ${site.selector} { ${d.prop}: ${d.value} } —— 环色必须是 var(${site.token}) 的纯引用，禁止字面量（${lit.join(', ')}）`);
      else if (!d.value.includes(`var(${site.token})`)) add('C4', `${site.file}:${d.line}  ${site.selector} { ${d.prop}: ${d.value} } —— 未引用 ${site.token}`);
    }
  }

  // B4：边框字面量台账（两表：近白 + 彩色，按文件计数）
  const ledger = {};       // 近白表
  const coloredLedger = {}; // 彩色表
  const files = [];
  for (const f of readdirSync(join(WEB, 'css'))) if (f.endsWith('.css')) files.push(join(WEB, 'css', f));
  for (const f of readdirSync(join(WEB, 'js'))) if (f.endsWith('.js')) files.push(join(WEB, 'js', f));
  if (existsSync(join(WEB, 'index.html'))) files.push(join(WEB, 'index.html'));
  for (const p of files) {
    let src; try { src = readFileSync(p, 'utf8'); } catch { continue; }
    if (!src.includes('border')) continue;
    let n = 0; let nc = 0;
    for (const rule of walkRules(src)) {
      for (const d of rule.decls) {
        if (!BORDER_PROP_RE.test(d.prop)) continue;
        for (const m of d.value.matchAll(COLOR_LITERAL_RE)) {
          const hit = m[0];
          if (/^rgba?\(/i.test(hit) && /var\(/.test(hit)) continue;   // rgb(var(--x) / .n) 是令牌组合
          if (nearWhiteLiteral(hit)) n++;
          if (coloredLiteral(hit)) nc++;
        }
      }
    }
    if (n) ledger[relative(WORK, p)] = n;
    if (nc) coloredLedger[relative(WORK, p)] = nc;
  }

  const frozen = { ...NEAR_WHITE_LITERAL_LEDGER };
  delete frozen['_note'];
  for (const [f, n] of Object.entries(ledger)) {
    const cap = frozen[f];
    if (cap === undefined) add('B4', `新增文件出现近白边框字面量 ${n} 条：${f}（新面一律走 var(--nf-hairline-* / --color-border / --glass-border)）`);
    else if (n > cap) add('B4', `近白边框字面量回升：${f} ${n} > 台账 ${cap}（只许降不许升）`);
  }
  for (const [f, cap] of Object.entries(frozen)) {
    if (ledger[f] === undefined) add('B4', `台账条目已消失：${f}（原 ${cap} 条）—— 若确已清零，请用 --freeze 重生成台账并在批内说明`);
  }

  const frozenCol = { ...COLORED_LITERAL_LEDGER };
  delete frozenCol['_note'];
  for (const [f, n] of Object.entries(coloredLedger)) {
    const cap = frozenCol[f];
    if (cap === undefined) add('B4', `新增文件出现彩色边框字面量 ${n} 条：${f}（有色缘一律走彩边族 var(--nf-hairline-tint-*) 或既有语义令牌）`);
    else if (n > cap) add('B4', `彩色边框字面量回升：${f} ${n} > 台账 ${cap}（只许降不许升）`);
  }
  for (const [f, cap] of Object.entries(frozenCol)) {
    if (coloredLedger[f] === undefined) add('B4', `彩色台账条目已消失：${f}（原 ${cap} 条）—— 若确已清零，请用 --freeze 重生成台账并在批内说明`);
  }
  return { ledger, coloredLedger, glassDecls: glassDecls.length };
}

// ══ main ════════════════════════════════════════════════════════════════
const staticRes = staticChecks();
if (FREEZE) {
  console.log('// NEAR_WHITE_LITERAL_LEDGER（近白表：相对亮度 ≥ .55 或半透明白 α ≥ .15）');
  console.log(JSON.stringify(staticRes.ledger, null, 2));
  console.log('\n// COLORED_LITERAL_LEDGER（彩色表：Lab chroma > 6）');
  console.log(JSON.stringify(staticRes.coloredLedger, null, 2));
  process.exit(0);
}

const comp = await computed();

if (JSON_OUT) {
  console.log(JSON.stringify({
    ok: violations.length === 0,
    thresholds: { NEUTRAL_CHROMA_MAX, DARK_CONTRAST_MAX, DARK_DELTAE_MAX, PARITY_DELTAE_MAX,
      C_C3_BAND, C_C2_HUE_MAX, C_C2_CHROMA_MIN },
    browser: comp.browser,
    darkNeutralSurfaces: comp.b1Count,
    computedRows: comp.rows.length,
    nearWhiteLiteralLedger: staticRes.ledger,
    coloredLiteralLedger: staticRes.coloredLedger,
    coloredRingFaces: comp.colored,
    violations,
  }, null, 2));
  process.exit(violations.length ? 1 : 0);
}

console.log(`# border-weight gate — browser ${comp.browser}`);
console.log(`#   暗档中性发丝面 ${comp.b1Count} 个 · 边框读数 ${comp.rows.length} 条 · 近白字面量台账 ${Object.keys(staticRes.ledger).length} 文件 · 彩色字面量台账 ${Object.keys(staticRes.coloredLedger).length} 文件`);
console.log(`#   阈值 B 组（中性）：chroma ≤ ${NEUTRAL_CHROMA_MAX} · 暗档 ratio ≤ ${DARK_CONTRAST_MAX} · 暗档 ΔE00 ≤ ${DARK_DELTAE_MAX} · 暗亮 ΔE00 差 ≤ ${PARITY_DELTAE_MAX}`);
console.log(`#   阈值 C 组（彩色语义面）：暗档 ratio ∈ [${C_C3_BAND[0]}, ${C_C3_BAND[1]}] · 距色相族轴 ≤ ${C_C2_HUE_MAX}° · 合成 chroma ≥ 底色 × ${C_C2_CHROMA_MIN} · 暗档 ΔE00 ≤ 亮档 ΔE00`);
console.log('\n# C 组 · 彩色语义面（参照面 = 元素外侧底色 bgOuter）');
console.log('#   面                        暗 合成         暗 比   暗 ΔE00  亮 ΔE00  Δ距轴   chroma    C1 C2 C3');
for (const r of comp.colored) {
  console.log(`#   ${r.name.padEnd(22)} ${r.dark.hex}  ${String(r.dark.contrast).padEnd(6)} ${String(r.dark.deltaE).padEnd(8)} ${String(r.light ? r.light.deltaE : '-').padEnd(8)} ${String(r.dAxis + '°').padEnd(7)} ${r.chroma}/${r.bgChroma}=${r.chromaRatio}x  ${r.c1 ? ' ok' : ' NG'}${r.c2 ? ' ok' : ' NG'}${r.c3 ? ' ok' : ' NG'}`);
}
if (violations.length) {
  console.error(`\n✗ border-weight gate FAILED — ${violations.length} 条`);
  for (const v of violations) console.error(`  [${v.check}] ${v.message}`);
  console.error('\n处置：①中性暗档发丝边强度只由 css/stand-tokens.css 的 --nf-hairline-* 族给，');
  console.error('      其它面一律 var(--glass-border) / var(--color-border) / var(--ds-border*)，禁写字面量；');
  console.error('      ②彩色语义环只由彩边族 --nf-hairline-{green,sapphire}-* 给，消费面写 var(--nf-hairline-tint-*)；');
  console.error('      ③C 组是**不变式**（不放大 / 色相可指认 / 亮度权重进带），不许把 B 组的中性阈值套到彩色面。');
  process.exit(1);
}
console.log('\n✓ border-weight gate passed — 暗档无超阈值中性发丝边 · 发丝边族（中性+彩色）单一源 · 两张字面量台账未回升 · 五枚彩色语义面（三环 + 两同族 chip）C1/C2/C3 全过');
process.exit(0);
