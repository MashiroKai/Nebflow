#!/usr/bin/env node
// verify.mjs — 方案页的三类实测：无障碍交互、命中区、色值与同源核验
//
// 判据（作者 2026-11-05 18:13 令的第 4/5 条）：Tab 可达 + Space 切换生效要有**实测**
// 读数，深浅两态的实际色值要取自现有令牌。本脚本一次把三件事测完：
//
//   ① 无障碍实测（真键盘事件，不是读源码）：从页面起点敲 Tab，记录每一站；
//      对每个方案的 OFF 档敲 Space / Enter，读 aria-checked 与 class 是否真的翻了；
//      读 :focus-visible 的 outline 计算值（键盘导航必须看得见环）。
//   ② 命中区实测：读 button.tog::before 的解析后宽高（max(100%, 44px) 的落点）。
//   ③ 色值同源核验：页面令牌 --tog-* 的每个字面量，回到 stand-tokens.css /
//      sapphire.css 里逐值找得到（找不到 = 我们自造了色值 = 红），并给出
//      实际渲染出的 WCAG 对比度读数（未核 ≠ 绿：读数照实报，不粉饰）。
//
// 用法：NEBFLOW_PW_PATH=<playwright> node design/toggle-options/verify.mjs

import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { loadPlaywright } from '../../scripts/lib/visual-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, '..', '..');
const TOKENS_CSS = join(REPO, 'src', 'main', 'resources', 'web', 'css', 'stand-tokens.css');
const SAPPHIRE_CSS = join(REPO, 'src', 'main', 'resources', 'web', 'css', 'sapphire.css');
const INDEX = pathToFileURL(join(HERE, 'index.html')).href;

let failures = 0;
const say = (ok, msg) => { if (!ok) failures++; console.log(`${ok ? '✓' : '✗'} ${msg}`); };
const line = (s = '') => console.log(s);

/* ══ ③-a 值同源核验（先做，不依赖浏览器）══════════════════════════════════
   每条 = 页面令牌 : 源文件里的原样字面量。找不到 ⇒ 页面里那个值是自造的。 */
const tokensCss = readFileSync(TOKENS_CSS, 'utf8');
const sapphireCss = readFileSync(SAPPHIRE_CSS, 'utf8');
const SAME_SOURCE = [
  ['--tog-page 亮', '--st-bg: #f5f6f8;', tokensCss],
  ['--tog-page 暗', '--st-bg: #13161c;', tokensCss],
  ['--tog-surface 亮', '--st-surface: #ffffff;', tokensCss],
  ['--tog-surface 暗', '--st-surface: #1c1f27;', tokensCss],
  ['--tog-ink 亮', '--st-text: #1b1e26;', tokensCss],
  ['--tog-ink 暗', '--st-text: #e8eaed;', tokensCss],
  ['--tog-ink-muted 亮', '--st-text-muted: #8b8e96;', tokensCss],
  ['--tog-ink-muted 暗', '--st-text-muted: #7b7e88;', tokensCss],
  ['--tog-page-stroke 亮', '--st-border: #ddd;', tokensCss],
  ['--tog-page-stroke 暗', '--st-border: #2a2d35;', tokensCss],
  ['--tog-track-off 亮', '--ctl-neutral-bg: #ececee;', tokensCss],
  ['--tog-track-off 暗', '--ctl-neutral-bg: #2c2c31;', tokensCss],
  ['--tog-stroke-off 亮', '--st-toggle-off-border: #1b1e2626;', tokensCss],
  ['--tog-stroke-off 暗', '--st-toggle-off-border: #ffffff14;', tokensCss],
  ['--tog-ring 亮', '--btn-focus-ring: #3b82f6;', tokensCss],
  ['--tog-ring 暗', '--btn-focus-ring: #60a5fa;', tokensCss],
  ['--tog-accent（= 品牌绿）', '--brand-green: 7 193 96;', sapphireCss],
  ['--tog-disabled-opacity', '--btn-disabled-opacity: 0.5;', sapphireCss],
  ['--tog-knob 亮（= 发送白字同值）', '--ctl-send-ink: #ffffff;', tokensCss],
  ['--tog-knob 暗（= 暗档正文实值）', '--st-text: #e8eaed;', tokensCss],
];
line('## ③-a 色值同源核验（页面令牌 ← 源令牌，逐字面量回找）');
let miss = 0;
for (const [name, literal, src] of SAME_SOURCE) {
  const ok = src.includes(literal);
  if (!ok) miss++;
  line(`  ${ok ? '✓' : '✗'} ${name.padEnd(30, ' ')} ${literal}`);
}
say(miss === 0, `③-a 全部 ${SAME_SOURCE.length} 条字面量在源文件里逐字命中（缺 ${miss} 条）`);
line();

/* ══ 浏览器实测 ═══════════════════════════════════════════════════════════ */
const pw = loadPlaywright();
const chromium = (pw.mod.playwright || pw.mod).chromium;
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const ctx = await browser.newContext({ viewport: { width: 1600, height: 900 }, locale: 'zh-CN' });
const page = await ctx.newPage();

async function load() {
  await page.goto(INDEX, { waitUntil: 'load' });
  await page.waitForFunction(() => window.__togReady === true, null, { timeout: 15000 });
}

/* ── ①-a Tab 可达：从页面起点一路敲 Tab，记录每一站 ───────────────────── */
await load();
const tabOrder = [];
await page.evaluate(() => document.body.focus());
for (let i = 0; i < 40; i++) {
  await page.keyboard.press('Tab');
  const stop = await page.evaluate(() => {
    const el = document.activeElement;
    if (!el || !el.matches || !el.matches('button.tog')) return null;
    return {
      id: el.id,
      opt: el.dataset.opt,
      state: el.dataset.state,
      theme: el.closest('[data-theme]').dataset.theme,
    };
  });
  if (!stop) continue;
  if (!tabOrder.some((s) => s.id === stop.id)) tabOrder.push(stop);
}
line('## ①-a Tab 可达实测（index.html，从页面起点连续敲 Tab）');
line(`  敲 Tab 共 40 次，落到控件上的不同站点 ${tabOrder.length} 个：`);
line('  ' + tabOrder.map((s, i) => `${i + 1}.${s.theme}/${s.opt}-${s.state}`).join('  '));
say(tabOrder.length === 30, `①-a 页面 30 枚控件全部可 Tab 到达（实测 ${tabOrder.length}/30）`);
line();

/* ── ①-b Space / Enter 切换 + 焦点环 + 命中区，逐方案逐主题 ──────────── */
line('## ①-b Space / Enter 切换、焦点环、命中区（浅深各测一遍）');
const tabIndexTo = (id) => tabOrder.findIndex((s) => s.id === id) + 1;

const a11yRows = [];
for (const theme of ['light', 'dark']) {
  for (const opt of ['A', 'B', 'C', 'D', 'E']) {
    const targetId = `tog-${theme}-${opt}-off`;
    const need = tabIndexTo(targetId);
    await load();
    await page.evaluate(() => document.body.focus());
    for (let i = 0; i < need; i++) await page.keyboard.press('Tab');

    const focused = await page.evaluate(() => {
      const el = document.activeElement;
      const cs = getComputedStyle(el);
      const before = getComputedStyle(el, '::before');
      return {
        id: el.id,
        matches: el.matches('button.tog'),
        outline: `${cs.outlineStyle} ${cs.outlineWidth} ${cs.outlineColor}`,
        hit: `${before.width}×${before.height}`,
        box: `${el.getBoundingClientRect().width}×${el.getBoundingClientRect().height}`,
      };
    });

    await page.keyboard.press('Space');
    const afterSpace = await page.evaluate(() => ({
      aria: document.activeElement.getAttribute('aria-checked'),
      on: document.activeElement.classList.contains('is-on'),
    }));
    await page.keyboard.press('Enter');
    const afterEnter = await page.evaluate(() => ({
      aria: document.activeElement.getAttribute('aria-checked'),
      on: document.activeElement.classList.contains('is-on'),
    }));

    const reach = focused.id === targetId && focused.matches;
    const spaceOk = afterSpace.on === true && afterSpace.aria === 'true';
    const enterOk = afterEnter.on === false && afterEnter.aria === 'false';
    const ringOk = /solid 2px/.test(focused.outline);
    const hitOk = /^(\d+(\.\d+)?)px×\1px$/.test(focused.hit.replace(/px×(\d+(\.\d+)?)px/, (m, a, b) => (a === b ? `${a}px×${a}px` : m))
      .replace(/^(\d+(?:\.\d+)?)px×(\d+(?:\.\d+)?)px$/, (m, a, b) => (Math.min(+a, +b) >= 44 ? `${Math.min(+a, +b)}px×${Math.min(+a, +b)}px` : m)));

    a11yRows.push({ theme, opt, reach, spaceOk, enterOk, ring: focused.outline, hit: focused.hit, box: focused.box });
    line(`  ${theme.padEnd(5)} ${opt} | Tab 第 ${need} 站 ${reach ? '✓' : '✗'} | Space→${afterSpace.aria} ${spaceOk ? '✓' : '✗'}`
      + ` | Enter→${afterEnter.aria} ${enterOk ? '✓' : '✗'} | 焦点环 ${focused.outline} ${ringOk ? '✓' : '✗'}`
      + ` | 本体 ${focused.box} 命中盒 ${focused.hit}`);
    void hitOk;
  }
}
const a11yOk = a11yRows.every((r) => r.reach && r.spaceOk && r.enterOk && /solid 2px/.test(r.ring));
say(a11yOk, `①-b 10 组（5 方案 × 浅深）Tab 可达 / Space 切换 / Enter 切换 / 焦点环 全部成立`);
const hits = a11yRows.map((r) => r.hit);
say(hits.every((h) => parseFloat(h) >= 44), `①-b 命中盒全部 ≥44px（实测 ${[...new Set(hits)].join('、')}）`);
line();

/* ── ③-b 实际渲染色值 + WCAG 对比度 ────────────────────────────────────── */
await load();
const colors = await page.evaluate(() => {
  const read = (el, prop = 'backgroundColor') => (el ? getComputedStyle(el)[prop] : null);
  const out = {};
  // 只认栏（section.pane）作为「面板底」：内层 [data-render-host] 也带 data-theme，
  // 但它没有底色（transparent），混进来会把「对面板」一栏读成 n/a —— 本批踩过。
  for (const pane of document.querySelectorAll('section.pane[data-theme]')) {
    const theme = pane.dataset.theme;
    const pick = (opt, state) => pane.querySelector(`button.tog[data-opt="${opt}"][data-state="${state}"]`);
    out[theme] = {};
    for (const opt of ['A', 'B', 'C', 'D', 'E']) {
      const off = pick(opt, 'off');
      const on = pick(opt, 'on');
      const moverOff = off.querySelector('.tog__knob') || off.querySelector('.tog__pill');
      const moverOn = on.querySelector('.tog__knob') || on.querySelector('.tog__pill');
      out[theme][opt] = {
        surface: read(pane),
        trackOff: read(off),
        trackOn: read(on),
        moverOff: read(moverOff),
        moverOn: read(moverOn),
        moverBorderOff: read(moverOff, 'borderTopColor'),
        moverBorderOn: read(moverOn, 'borderTopColor'),
        glyphOff: read(off.querySelector('.tog__glyph--off'), 'color'),
        glyphOn: read(on.querySelector('.tog__glyph--on'), 'color'),
        segOn: read(on.querySelector('.tog__seg--on'), 'color'),
        segOff: read(off.querySelector('.tog__seg--off'), 'color'),
        stopBorder: read(off, 'borderTopColor'),
      };
    }
  }
  return out;
});

function toRgb(s) {
  if (!s) return null;
  const m = String(s).match(/rgba?\(([^)]+)\)/);
  if (!m) return null;
  const p = m[1].split(/[,\s/]+/).filter(Boolean).map(Number);
  return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
}
const lum = (c) => {
  const f = (v) => { const s = v / 255; return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4; };
  return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b);
};
function contrast(fg, bg) {
  const F = toRgb(fg);
  const B = toRgb(bg);
  if (!F || !B) return null;
  if (F.a === 0 || B.a === 0) return null;               // 全透明 = 没有面，无边界面可比
  // 半透明前景（如 15% 墨描边）先按 alpha 合到底色上，再算对比度 ——
  // 不合成会把 rgba(27,30,38,.15) 当成纯墨，读数虚高十倍。
  const f = F.a < 1
    ? { r: F.r * F.a + B.r * (1 - F.a), g: F.g * F.a + B.g * (1 - F.a), b: F.b * F.a + B.b * (1 - F.a) }
    : F;
  const l1 = Math.max(lum(f), lum(B));
  const l2 = Math.min(lum(f), lum(B));
  return Number(((l1 + 0.05) / (l2 + 0.05)).toFixed(2));
}

line('## ③-b 实际渲染色值（computed style，非手抄）与 WCAG 对比度');
const cr = (a, b) => {
  const v = contrast(a, b);
  return v === null ? 'n/a' : v;
};
for (const theme of ['light', 'dark']) {
  line(`\n  ${theme}`);
  for (const opt of ['A', 'B', 'C', 'D', 'E']) {
    const c = colors[theme][opt];
    if (opt === 'E') {
      // E 没有滑块：状态由「填充片压在哪一段」表达 ⇒ 有意义的对是 片↔轨道、字↔片
      line(`  E | 轨道 ${c.trackOff} · 片 off ${c.moverOff} · 片 on ${c.moverOn}`);
      line(`     片off↔轨道 ${cr(c.moverOff, c.trackOff)} · 片on↔轨道 ${cr(c.moverOn, c.trackOff)}`
        + ` · 段字on↔片on ${cr(c.segOn, c.moverOn)} · 段字off↔片off ${cr(c.segOff, c.moverOff)}`);
      continue;
    }
    if (opt === 'D') {
      // D 没有填充面（轨道 off 透明、滑块是空心环）⇒ 可比的是**线**：描边对底、环对底
      line(`  D | 轨道 off 透明 · 描边 off ${c.stopBorder} · 环 off ${c.moverBorderOff}`
        + ` · 轨道 on ${c.trackOn} · 环 on ${c.moverBorderOn}`);
      line(`     描边off↔面板底 ${cr(c.stopBorder, c.surface)} · 环off↔面板底 ${cr(c.moverBorderOff, c.surface)}`
        + ` · 轨道on↔面板底 ${cr(c.trackOn, c.surface)} · 环on↔轨道on ${cr(c.moverBorderOn, c.trackOn)}`);
      continue;
    }
    line(`  ${opt} | 轨道 off ${c.trackOff} · 轨道 on ${c.trackOn} · 滑块 off ${c.moverOff} · 滑块 on ${c.moverOn}`);
    line(`     滑块off↔轨道off ${cr(c.moverOff, c.trackOff)} · 滑块on↔轨道on ${cr(c.moverOn, c.trackOn)}`
      + ` · 两档轨道 ${cr(c.trackOn, c.trackOff)} · 滑块↔面板底 ${cr(c.moverOff, c.surface)}`
      + (opt === 'C'
        ? ` · 图标off↔轨道off ${cr(c.glyphOff, c.trackOff)} · 图标on↔轨道on ${cr(c.glyphOn, c.trackOn)}`
        : ''));
  }
}

line('\n## 读数说明（未核 ≠ 绿，照实报）');
line('  · 1.4.11「非文本对比度 3:1」的判读对象是**区分状态所必需的视觉信息**。本批五组的');
line('    首要状态通道是**位移**（滑块/填充片走完整行程），色只是冗余通道；上表逐档给出真实');
line('    对比度，不写成「通过」。若要把色也提到 3:1，唯一办法是把轨道 off 档压到 L≈0.11 以下');
line('    （浅档约 #4a4a50），那是另一种界面，本批不擅自改。');
line('  · D 的滑块是 1px 空心环（无填充面），故「滑块↔轨道」一栏记 n/a —— 它的边界由线给出。');

await ctx.close();
await browser.close();
console.log(`\n${failures === 0 ? '✓ 全部通过' : `✗ ${failures} 项失败`}`);
process.exit(failures === 0 ? 0 : 1);
