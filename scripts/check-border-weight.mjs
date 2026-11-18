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
//  B4 近白发丝边字面量台账（static）：全站边框类声明里「近白（相对亮度 ≥ .55）
//     字面量颜色」按文件计数，不得超过冻结台账（本批 tip 实测值，只许降不许升）。
//     与 design-token 基线闸同规：存量不追溯，新增即红。
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
import { borderMetrics, rgb2lab, hex } from './lib/color-metrics.mjs';

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

/** 发丝边族槽位：只许在 stand-tokens.css 定义一次。 */
const HAIRLINE_SLOTS = ['--nf-hairline-ink', '--nf-hairline-a', '--nf-hairline-a-dark'];
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
  'src/main/resources/web/css/chat.css': 16,
  'src/main/resources/web/css/diffView.css': 2,
  'src/main/resources/web/css/flowMap.css': 9,
  'src/main/resources/web/css/input.css': 4,
  'src/main/resources/web/css/modal.css': 1,
  'src/main/resources/web/css/nav.css': 1,
  'src/main/resources/web/css/neblink.css': 1,
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
    ? { contrast: use.contrast, deltaE: use.deltaE, rgb: use.rgb, bg: use.bgRgb, ref: use === self ? 'self' : 'outer' }
    : null;
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
  return { browser: label, rows, hairlineRows: hair.length, b1Count: b1.size, b2rows };
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

  // B4：近白字面量边框台账（按文件计数）
  const ledger = {};
  const files = [];
  for (const f of readdirSync(join(WEB, 'css'))) if (f.endsWith('.css')) files.push(join(WEB, 'css', f));
  for (const f of readdirSync(join(WEB, 'js'))) if (f.endsWith('.js')) files.push(join(WEB, 'js', f));
  if (existsSync(join(WEB, 'index.html'))) files.push(join(WEB, 'index.html'));
  for (const p of files) {
    let src; try { src = readFileSync(p, 'utf8'); } catch { continue; }
    if (!src.includes('border')) continue;
    let n = 0;
    for (const rule of walkRules(src)) {
      for (const d of rule.decls) {
        if (!BORDER_PROP_RE.test(d.prop)) continue;
        for (const m of d.value.matchAll(COLOR_LITERAL_RE)) {
          const hit = m[0];
          if (/^rgba?\(/i.test(hit) && /var\(/.test(hit)) continue;   // rgb(var(--x) / .n) 是令牌组合
          if (nearWhiteLiteral(hit)) n++;
        }
      }
    }
    if (n) ledger[relative(WORK, p)] = n;
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
  return { ledger, glassDecls: glassDecls.length };
}

// ══ main ════════════════════════════════════════════════════════════════
const staticRes = staticChecks();
if (FREEZE) {
  console.log(JSON.stringify(staticRes.ledger, null, 2));
  process.exit(0);
}

const comp = await computed();

if (JSON_OUT) {
  console.log(JSON.stringify({
    ok: violations.length === 0,
    thresholds: { NEUTRAL_CHROMA_MAX, DARK_CONTRAST_MAX, DARK_DELTAE_MAX, PARITY_DELTAE_MAX },
    browser: comp.browser,
    darkNeutralSurfaces: comp.b1Count,
    computedRows: comp.rows.length,
    nearWhiteLiteralLedger: staticRes.ledger,
    violations,
  }, null, 2));
  process.exit(violations.length ? 1 : 0);
}

console.log(`# border-weight gate — browser ${comp.browser}`);
console.log(`#   暗档中性发丝面 ${comp.b1Count} 个 · 边框读数 ${comp.rows.length} 条 · 近白字面量台账 ${Object.keys(staticRes.ledger).length} 文件`);
console.log(`#   阈值：中性 chroma ≤ ${NEUTRAL_CHROMA_MAX} · 暗档 ratio ≤ ${DARK_CONTRAST_MAX} · 暗档 ΔE00 ≤ ${DARK_DELTAE_MAX} · 暗亮 ΔE00 差 ≤ ${PARITY_DELTAE_MAX}`);
if (violations.length) {
  console.error(`\n✗ border-weight gate FAILED — ${violations.length} 条`);
  for (const v of violations) console.error(`  [${v.check}] ${v.message}`);
  console.error('\n处置：暗档发丝边强度只由 css/stand-tokens.css 的 --nf-hairline-* 族给；');
  console.error('      其它面一律 var(--glass-border) / var(--color-border) / var(--ds-border*)，禁写字面量。');
  process.exit(1);
}
console.log('\n✓ border-weight gate passed — 暗档无超阈值发丝边、发丝边族单一源、近白字面量台账未回升');
process.exit(0);
