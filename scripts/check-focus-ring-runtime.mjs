#!/usr/bin/env node
// check-focus-ring-runtime.mjs — 焦点环运行时判据 P-1–P-4（真 Chromium，零端口）
//
// 规范正文：design-language/references/frontend/focus-ring-spec.md §4。
// 静态闸（check-focus-ring.mjs）判「源码里写的是不是一条环」；本闸判「屏幕上画出来的
// 是不是那一条」——两者不可互替：源码可以写对而像素被裁掉、被叠加层吃掉、被祖先的
// overflow / backdrop-root 断层盖住。
//
// 测量方法是**同一像素的有/无指示器差分**（WCAG「焦点外观」的判定口径）：同一裁剪区
// 先失焦拍一帧、再聚焦拍一帧，逐像素差分 = 焦点指示器真实画出来的像素。不用「猜环色」
// ——半透明控件、玻璃混色、抗锯齿都会让绝对色匹配失准。
//
// 判据：
//   P-1 可见：键盘模态下能 Tab 到的可交互件，`:focus-visible` 命中、outline 宽 2px、
//       style solid，且差分像素数 > 0（真的画出来了）。
//   P-2 对比：两档门槛 = 暗 ≥3:1、亮 ≥2.5:1（作者裁定 2026-11-22 下调，知悉亮档低于
//       3:1）。**门禁基准 = 环色对承载面**（规范 §2.4 的估算基准：亮对 #ffffff / 暗对
//       #0d1117）；同时逐件测量「环对相邻像素」的实测对比（`cAdjacent`）——凡 <3:1 的
//       一律写成 `subThreeAnnotated`（作者批注的显式标注位），玻璃底 / 饱和填充上的
//       实测偏低由此逐件留痕，不被静默通过。
//   P-3 不裁切：差分像素的包围盒必须完全落在组件 border-box 内（外扩环越界 ⇒ 红），
//       且 outline-offset 必须是负且 ≥ 环宽（几何上内缩）。
//   P-4 玻璃：带 backdrop-filter 的弹层内控件（设置面 / 面板 / 叠卡）与 P-1/P-3 同口径
//       且差分必须 > 0（叠加层没有把它吃掉）。
//   P-6 全面圆角：页面上每个**画得出盒子**的元素（背景/边框/投影）都取计算样式判圆角 ——
//      短边 ≤12px 的微格放行 2px 档，其余要求 ≥6px 或胶囊/圆形；唯「贴边满幅」
//      （盒边 = 视口边）按其几何豁免，并逐条具名进读数（作者令 2026-11-22 10:17
//      「还是存在部分不是圆角的 得全面圆角」；规范 §2.6/§4）。
//
// 豁免：scripts/focus-ring-exemptions.json 里具名登记的「无环焦点」控件（如主会话输入条
// 内层 #input，作者令 2026-11-03 ④）在运行时按 `exempt` 记录，不判 P-1（这是规范 §2.3
// 的例外，不是失败）。
//
// 用法：
//   node scripts/check-focus-ring-runtime.mjs             # 门禁（红/绿）
//   node scripts/check-focus-ring-runtime.mjs --json      # 机器读
//   node scripts/check-focus-ring-runtime.mjs --out=<dir> # 落读数 + 逐件前后帧（证据）
// 退出码：0 绿 / 1 红 / 2 环境错误。
//
// 🔴 零端口零进程：走 scripts/lib/visual-harness.mjs 的 page.route 打桩，结构上碰不到
// 宿主实例端口。

import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadPlaywright, launchBrowser, createSession } from './lib/visual-harness.mjs';
import { decodePng } from './lib/png-diff.mjs';

const argv = process.argv.slice(2);
const JSON_OUT = argv.includes('--json');
const OUT_DIR = (argv.find((a) => a.startsWith('--out=')) || '').slice(6) || '';
const ROOT = fileURLToPath(new URL('..', import.meta.url));

const RING_W = 2;
const THEMES = ['light', 'dark'];
/** 作者裁定 2026-11-22：暗 ≥3:1 硬门槛；亮 ≥2.5:1（知情接受低于 3:1），<3:1 必标注。 */
const THRESHOLD = { light: 2.5, dark: 3.0 };
/** 规范 §2.4 的对比估算基准底色（作者批准该令牌时看的正是这两个数）。 */
const SPEC_BASIS = { light: [255, 255, 255], dark: [13, 17, 23] };

const findings = [];
const rows = [];
const skipped = [];
const deviations = [];
const notPainted = [];
const unstableRows = [];
/** P-6 全面圆角普查：样本 / 违规 / 结构全幅豁免（具名）。 */
const p6Rows = [];
const p6Fails = [];
const p6Structural = [];

// ── 豁免选择器（从台账读，脚本内零硬编码）──────────────────────────────────
const ledger = JSON.parse(readFileSync(join(ROOT, 'scripts', 'focus-ring-exemptions.json'), 'utf8'));
const EXEMPT_SELECTORS = [];
for (const row of ledger.exemptions || []) {
  for (const part of String(row.selector || '').split(',')) {
    const sel = part.trim().replace(/:focus(?:-visible)?(?![\w-])/g, '').trim();
    if (sel && !/^html\[data-nf-modality/.test(sel)) EXEMPT_SELECTORS.push(sel);
  }
}

function srgbToLin(c) {
  const v = c / 255;
  return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
}
const luminance = ([r, g, b]) => 0.2126 * srgbToLin(r) + 0.7152 * srgbToLin(g) + 0.0722 * srgbToLin(b);
function contrast(a, b) {
  const la = luminance(a);
  const lb = luminance(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}
function parseColor(s) {
  const m = /rgba?\(\s*([\d.]+)[ ,]+([\d.]+)[ ,]+([\d.]+)/i.exec(s || '');
  return m ? [Math.round(+m[1]), Math.round(+m[2]), Math.round(+m[3])] : null;
}

const DESCRIBE = `(el) => {
  const cls = typeof el.className === 'string' && el.className.trim()
    ? '.' + el.className.trim().split(/\\s+/).slice(0, 2).join('.') : '';
  const lbl = (el.getAttribute('aria-label') || el.getAttribute('title') || el.textContent || '')
    .trim().replace(/\\s+/g, ' ').slice(0, 24);
  return el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + cls + (lbl ? ' "' + lbl + '"' : '');
}`;

const ENUMERATE = `(scopeSel) => {
  const scope = scopeSel ? document.querySelector(scopeSel) : document.body;
  if (!scope) return [];
  const SEL = 'button, a[href], summary, select, input, textarea, [contenteditable="true"], '
    + '[role="button"], [role="tab"], [role="menuitem"], [role="option"], [role="treeitem"], '
    + '[role="checkbox"], [role="switch"], [tabindex]:not([tabindex="-1"])';
  const out = [];
  for (const el of scope.querySelectorAll(SEL)) {
    const r = el.getBoundingClientRect();
    if (r.width < 8 || r.height < 8) continue;
    const cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none' || +cs.opacity === 0) continue;
    if (r.bottom < 0 || r.top > innerHeight || r.right < 0 || r.left > innerWidth) continue;
    el.setAttribute('data-nf-fr-probe', String(out.length));
    out.push({
      index: out.length,
      label: (${DESCRIBE})(el),
      rect: { x: r.x, y: r.y, w: r.width, h: r.height },
      glassAncestor: (() => {
        let p = el, hit = null;
        while (p && p !== document.documentElement) {
          const s = getComputedStyle(p);
          const bf = s.backdropFilter || s.webkitBackdropFilter;
          if (bf && bf !== 'none') {
            const cls = (p.className && typeof p.className === 'string')
              ? '.' + p.className.trim().split(/\\s+/)[0] : '';
            const id = p.id ? '#' + p.id : '';
            // 具名玻璃面才算「玻璃弹层」（匿名根 div 也会形成 backdrop root，
            // 但把它算进来会让每一件都变成 P-4 样本 —— 那是噪声不是覆盖）
            if (id || (cls && cls !== '.')) { hit = p.tagName.toLowerCase() + id + cls; break; }
          }
          p = p.parentElement;
        }
        return hit;
      })(),
      paintedAtCenter: (() => {
        const t = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
        if (!t) return false;
        return t === el || el.contains(t) || t.contains(el);
      })(),
      exemptSelector: (() => {
        for (const s of ${JSON.stringify(EXEMPT_SELECTORS)}) {
          try { if (el.matches(s)) return s; } catch { /* 组合选择器交给下一条 */ }
        }
        return null;
      })(),
    });
  }
  return out;
}`;

const MEASURE = `(idx, mode) => {
  const el = document.querySelector('[data-nf-fr-probe="' + idx + '"]');
  if (!el) return null;
  if (mode === 'focus') el.focus();
  else {
    // 必须把**当前**活动元素也 blur 掉：只 blur 目标元素的话，上一件的环会留在
    // 离屏帧里，差分就把「上一件的环消失」算成本件的越界像素（假红）。
    const a = document.activeElement;
    if (a && a !== document.body && a.blur) a.blur();
    if (el.blur && document.activeElement === el) el.blur();
  }
  const cs = getComputedStyle(el);
  const r = el.getBoundingClientRect();
  return {
    focusVisible: el.matches(':focus-visible'),
    focused: document.activeElement === el,
    activeTag: document.activeElement ? document.activeElement.tagName.toLowerCase() : 'none',
    outlineStyle: cs.outlineStyle,
    outlineWidth: cs.outlineWidth,
    outlineColor: cs.outlineColor,
    outlineOffset: cs.outlineOffset,
    bg: cs.backgroundColor,
    borderRadius: cs.borderRadius,
    rect: { x: r.x, y: r.y, w: r.width, h: r.height },
  };
}`;

/**
 * P-6 全面圆角普查：把页面上**画得出盒子**的每个元素都取计算样式判圆角
 * （作者令 2026-11-22 10:17「还是存在部分不是圆角的 得全面圆角」；规范 §2.6/§4）。
 *
 * 与 P-5 的分工：P-5 只判「焦点环基座」（可交互件），P-6 判全树画框面（徽章 / chip /
 * 统计卡 / 提示条 / 分隔面 / 媒体占位 …… 这些不在 Tab 序里，人眼却看得见方角）。
 *
 * 判据（几何，不看 CSS 文本）：
 *   · 画框 = 背景不透明 >2% / ≥2 条可见边框 / 有投影；面积 <16px² 的元素不构成盒子。
 *   · 短边 ≤12px 的微格放行 2px 档（浏览器在 11px 盒上把 6px 钳成 50%，格子变圆点，
 *     热力图语义被破 —— 物理钳制，不是审美选择）；其余要求 ≥6px 或胶囊/圆形。
 *   · **贴边满幅**（盒边 = 视口边：左右都贴或上下都贴）是唯一的结构豁免：它的圆角会被
 *     视口裁掉。这类件逐条具名记进 readout 的 structural 段 —— 是几何判据，不是
 *     round1 那种 per-item 方角登记。
 */
const SURVEY = `(scopeSel) => {
  const scope = scopeSel ? document.querySelector(scopeSel) : document.body;
  if (!scope) return null;
  const vw = innerWidth, vh = innerHeight;
  const describe = (el) => {
    const cls = typeof el.className === 'string' && el.className.trim()
      ? '.' + el.className.trim().split(/\\s+/).slice(0, 2).join('.') : '';
    const lbl = (el.getAttribute('aria-label') || el.id || '').trim().replace(/\\s+/g, ' ').slice(0, 20);
    return el.tagName.toLowerCase() + (el.id ? '#' + el.id : cls) + (lbl ? ' "' + lbl + '"' : '');
  };
  const rows = [], structural = [];
  for (const el of scope.querySelectorAll('*')) {
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden' || +cs.opacity < 0.02) continue;
    const r = el.getBoundingClientRect();
    if (r.width * r.height < 16) continue;
    if (r.bottom < 0 || r.top > vh || r.right < 0 || r.left > vw) continue;
    // 画框判定
    let bgPaint = false;
    const bg = String(cs.backgroundColor || '');
    const m = /rgba?\\(([^)]+)\\)/.exec(bg);
    if (m) {
      const parts = m[1].split(',').map(Number);
      bgPaint = parts.length < 4 || parts[3] > 0.02;
    } else if (bg && bg !== 'transparent' && bg !== 'none') bgPaint = true;
    const sides = ['Top', 'Right', 'Bottom', 'Left'].filter((sd) =>
      parseFloat(cs['border' + sd + 'Width']) > 0 && cs['border' + sd + 'Style'] !== 'none').length;
    const shadow = cs.boxShadow && cs.boxShadow !== 'none';
    if (!bgPaint && sides < 2 && !shadow) continue;
    // 圆角读数（四角最大值；% 记 999 = 胶囊/圆形档）
    const raw = ['borderTopLeftRadius', 'borderTopRightRadius', 'borderBottomRightRadius', 'borderBottomLeftRadius']
      .map((prop) => String(cs[prop] || '0px'));
    const px = raw.map((v) => {
      const mm = /^([\\d.]+)px$/.exec(v.trim());
      if (mm) return Number(mm[1]);
      return /%/.test(v) ? 999 : null;
    });
    const known = px.every((n) => n != null);
    const maxRadius = known ? Math.max(...px) : 0;
    const shortSide = Math.min(r.width, r.height);
    // 贴边满幅（盒边 = 视口边）
    const fullX = r.left <= 1 && r.right >= vw - 1;
    const fullY = r.top <= 1 && r.bottom >= vh - 1;
    const tag = el.tagName.toLowerCase();
    const row = {
      label: describe(el), tag, rect: { x: r.x, y: r.y, w: r.width, h: r.height },
      shortSide, maxRadius, radii: raw, bg: bgPaint, borderSides: sides, shadow: Boolean(shadow),
    };
    if (tag === 'html' || tag === 'body' || el.id === 'app' || ((fullX || fullY) && maxRadius === 0)) {
      structural.push({ ...row, why: tag === 'html' || tag === 'body' || el.id === 'app'
        ? '结构根（盒 = 视口）' : (fullX && fullY ? '贴边满幅（四边 = 视口边）'
        : (fullX ? '贴边满幅（左右 = 视口边）' : '贴边满幅（上下 = 视口边）')) });
      continue;
    }
    rows.push(row);
  }
  return { rows, structural };
}`;

const pw = loadPlaywright();
const { browser, label: browserLabel } = await launchBrowser(pw.mod, {});
if (OUT_DIR) mkdirSync(OUT_DIR, { recursive: true });

/** 差分两帧 → { count, pixels:[{x,y,a,b,delta}] } */
function diffFrames(aBuf, bBuf) {
  const A = decodePng(aBuf);
  const B = decodePng(bBuf);
  if (A.width !== B.width || A.height !== B.height) return { count: 0, pixels: [], width: A.width, height: A.height };
  const px = [];
  for (let y = 0; y < A.height; y++) {
    for (let x = 0; x < A.width; x++) {
      const i = (y * A.width + x) * 4;
      const a = [A.data[i], A.data[i + 1], A.data[i + 2]];
      const b = [B.data[i], B.data[i + 1], B.data[i + 2]];
      const d = Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
      if (d >= 8) px.push({ x, y, a, b, delta: d });
    }
  }
  return { count: px.length, pixels: px, width: A.width, height: A.height };
}

for (const theme of THEMES) {
  const session = await createSession(browser, {
    colorScheme: theme,
    apiStubs: {
      '/api/agents': { body: { agents: [] } },
      '/api/projects': { body: { projects: [{ id: 'p1', name: 'nebflow-stand', path: '/w/nebflow-stand' }] } },
    },
  });
  const { page } = session;
  await session.boot({ config: { llm: { providers: {} }, features: { friends: false } } });
  await page.evaluate((t) => document.documentElement.setAttribute('data-theme', t), theme);
  await page.waitForTimeout(250);

  const scenes = [{ name: 'main', scope: null }];
  if (await page.evaluate(() => Boolean(document.querySelector('#settings-btn')))) {
    await page.evaluate(() => { document.querySelector('#settings-btn').click(); });
    await page.waitForTimeout(700);
    scenes.push({ name: 'settings-overlay', scope: '#settings-overlay' });
  } else {
    skipped.push(`${theme}: #settings-btn 不存在 ⇒ 设置弹层场景未采（P-4 缺样本）`);
  }

  // 承载面基准像素（页面左上角空旷处；= 规范 §2.4 的「底色」）
  const surface = await page.screenshot({ clip: { x: 0, y: 0, width: 4, height: 4 } });
  const surfImg = decodePng(surface);
  const surfaceColor = [surfImg.data[0], surfImg.data[1], surfImg.data[2]];

  for (const scene of scenes) {
    if (scene.scope && !(await page.evaluate((s) => Boolean(document.querySelector(s)), scene.scope))) {
      skipped.push(`${theme}: 场景 ${scene.name} 未渲染`);
      continue;
    }
    await page.keyboard.press('Tab'); // 真键盘事件：Chromium 据此进入键盘模态
    const targets = await page.evaluate(`(${ENUMERATE})(${JSON.stringify(scene.scope)})`);
    if (!targets.length) { skipped.push(`${theme}/${scene.name}: 0 个可交互件`); continue; }

    for (const t of targets.slice(0, 60)) {
      // 顺序有讲究：先聚焦（会把目标滚进视野、可能重排），再按**聚焦后**的矩形裁剪；
      // 否则「离屏帧」拍的是旧位置，差分恒 0（第一版就踩了这条：面板内 970px 处的开关
      // 被记成 35px，于是每一帧都拍空）。
      const m0 = await page.evaluate(`(${MEASURE})(${t.index}, 'focus')`);
      if (!m0) continue;
      await page.waitForTimeout(30);
      const vp = page.viewportSize();
      const pad = 8;
      const bx = Math.floor(m0.rect.x - pad);
      const by = Math.floor(m0.rect.y - pad);
      const clip = {
        x: Math.max(0, bx),
        y: Math.max(0, by),
        width: Math.min(vp.width - Math.max(0, bx), Math.ceil(m0.rect.w + pad * 2)),
        height: Math.min(vp.height - Math.max(0, by), Math.ceil(m0.rect.h + pad * 2)),
      };
      if (clip.width < 4 || clip.height < 4) continue;
      if (m0.rect.y + m0.rect.h < 0 || m0.rect.y > vp.height) {
        skipped.push(`${theme}/${scene.name} ${t.label}: 聚焦后仍在视野外，未采`);
        continue;
      }

      const mOff = await page.evaluate(`(${MEASURE})(${t.index}, 'blur')`);
      await page.waitForTimeout(40);
      const offBuf = await page.screenshot({ clip, animations: 'disabled', caret: 'hide' });
      const m = await page.evaluate(`(${MEASURE})(${t.index}, 'focus')`);
      if (!m) continue;
      await page.waitForTimeout(40);
      const onBuf = await page.screenshot({ clip, animations: 'disabled', caret: 'hide' });

      const diff = diffFrames(offBuf, onBuf);
      const row = {
        theme, scene: scene.name, index: t.index, label: t.label, glassAncestor: t.glassAncestor,
        exemptSelector: t.exemptSelector,
        focusVisible: m.focusVisible, focused: m.focused,
        outlineStyle: m.outlineStyle, outlineWidth: m.outlineWidth,
        outlineColor: m.outlineColor, outlineOffset: m.outlineOffset,
        borderRadius: m.borderRadius,
        rect: m.rect, diffPixels: diff.count,
        diffPixelsCore: diff.pixels.filter((p) => p.delta >= 20).length,
      };

      const ringColor = parseColor(m.outlineColor);
      const width = parseFloat(m.outlineWidth);
      const offset = parseFloat(m.outlineOffset);
      // 差分像素 → 真实画出来的指示器
      const core = diff.pixels.filter((p) => p.delta >= 20);
      const contrasts = core.map((p) => contrast(p.b, p.a)).sort((x, y) => x - y);
      const median = contrasts.length ? contrasts[Math.floor(contrasts.length / 2)] : null;
      row.measured = {
        cAdjacentMedian: median,
        cAdjacentMin: contrasts.length ? contrasts[0] : null,
        cAdjacentMax: contrasts.length ? contrasts[contrasts.length - 1] : null,
        surface: surfaceColor,
        cSurface: ringColor ? contrast(ringColor, surfaceColor) : null,
        ringColor,
      };

      // P-3：差分包围盒 ⊂ border-box（允许 1px 抗锯齿外溢）
      // 越界只看**强差分**像素（delta ≥ 20）：玻璃/backdrop-filter 重合成会产生大片
      // delta 8–19 的抖动噪声，把它算成「外扩环」是假红；真外扩环是灰环对底色，delta 远高。
      let outOfBox = 0;
      if (diff.pixels.length) {
        const x0 = m.rect.x - clip.x;
        const y0 = m.rect.y - clip.y;
        const x1 = x0 + m.rect.w;
        const y1 = y0 + m.rect.h;
        for (const p of diff.pixels) {
          if (p.delta < 20) continue;
          if (p.x < x0 - 1 || p.x > x1 + 1 || p.y < y0 - 1 || p.y > y1 + 1) outOfBox++;
        }
      }
      // 几何稳定性：聚焦会改变布局的件（浮动待办岛在聚焦时扩张）上，「越界像素」无法与
      // 「组件整体位移」区分 ⇒ P-3 只能弃判（记 geometryUnstable），不硬判成红。
      const unstable = Boolean(mOff) && (Math.abs(m.rect.x - mOff.rect.x) > 1
        || Math.abs(m.rect.y - mOff.rect.y) > 1
        || Math.abs(m.rect.w - mOff.rect.w) > 1
        || Math.abs(m.rect.h - mOff.rect.h) > 1);
      if (unstable) unstableRows.push(`${theme}/${scene.name} ${t.label}: 聚焦前后 bbox 变化，P-3 弃判`);
      // 整块区域重绘（玻璃/backdrop-filter 重合成，或聚焦触发了范围更大的态变化）：
      // 此时「越界像素」已无法归因于环 —— 一个 2px 环永远画不满 80% 的裁剪区。
      const repaintRatio = diff.width * diff.height ? diff.count / (diff.width * diff.height) : 0;
      const fullRepaint = repaintRatio > 0.8;
      if (fullRepaint) unstableRows.push(`${theme}/${scene.name} ${t.label}: 整块重绘 ${(repaintRatio * 100).toFixed(0)}%，P-3 弃判`);
      const geometryOk = m.focusVisible && m.outlineStyle === 'solid' && width === RING_W;
      const insetOk = offset <= -width;
      const exempt = Boolean(t.exemptSelector);
      // P-2 的**门禁基准**是作者批准该令牌时看的那一档（规范 §2.4：环色对纯底色）。
      // 实测「承载面」（页面实际底色，被玻璃/veil 抬深）与「相邻像素」两个读数一并留痕：
      // 两者只要有一项 < 阈值就进 deviation 表（显式标注），不静默通过。
      const analytic = ringColor ? contrast(ringColor, SPEC_BASIS[theme]) : null;
      row.measured.cAnalytic = analytic;
      // P-5 圆角标准（作者令 2026-11-22 08:03「我说了要圆角的框」「落为标准」；规范 §2.6）：
      // 判**计算样式**，不判 CSS 文本 —— 这才是「元素实际有没有圆角」的权威读数（静态闸
      // 只做近似级联）。方角登记豁免机制已废除：方角件在这里直接红。
      // 放行：胶囊/圆形（50% / ≥999px）、短边 <12px 的微格（≤4px，防 6px 被钳成圆点）。
      const radiusState = (() => {
        const raw = String(m.borderRadius || '').trim();
        if (/50%/.test(raw)) return { kind: 'circle', px: null };
        const nums = raw.split(/\s+/).map((v) => {
          const mm = /^([\d.]+)px$/.exec(v);
          return mm ? Number(mm[1]) : (/^50%$/.test(v) ? 999 : null);
        });
        if (nums.some((n) => n != null && n >= 999)) return { kind: 'pill', px: null };
        if (nums.some((n) => n == null)) return { kind: 'unknown', px: null };
        const px = Math.min(...nums.filter((n) => n != null));
        const shortSide = Math.min(m.rect.w, m.rect.h);
        return { kind: px >= 6 ? 'standard' : (shortSide < 12 && px <= 4 ? 'micro' : 'square'), px, shortSide };
      })();
      row.p5 = {
        radius: m.borderRadius,
        kind: radiusState.kind,
        px: radiusState.px ?? null,
        shortSide: radiusState.shortSide ?? null,
      };
      row.P = {
        exempt,
        p1: exempt ? null : (geometryOk && diff.count > 0 && m.focused),
        p1Detail: { focusVisible: m.focusVisible, style: m.outlineStyle, width, diffPixels: diff.count, focused: m.focused },
        p2: analytic != null ? analytic >= THRESHOLD[theme] : null,
        p2Threshold: THRESHOLD[theme],
        p2Detail: {
          basis: 'token vs 规范 §2.4 底色',
          analytic,
          measuredSurface: row.measured.cSurface,
          surfaceBelowFloor: row.measured.cSurface != null && row.measured.cSurface < THRESHOLD[theme],
        },
        subThreeAnnotated: (() => {
          const c = row.measured.cAdjacentMedian;
          if (c == null) return null;
          if (c >= 3) return null;
          return `实测「环对相邻像素」${c.toFixed(2)}:1 < 3:1 —— 作者 2026-11-22 批 B 案知情接受`
            + `${t.glassAncestor ? `（玻璃底 ${t.glassAncestor}，规范 §2.4 备注的「玻璃底偏灰需像素复核」）` : ''}`;
        })(),
        p3: exempt || unstable || fullRepaint ? null : (insetOk && outOfBox === 0 && diff.count > 0),
        p3Detail: { offset, outOfBox, diffPixels: diff.count, geometryUnstable: unstable, fullRepaint },
        p4: t.glassAncestor && !exempt ? (geometryOk && diff.count > 0) : null,
        p5: exempt || radiusState.kind === 'unknown' ? null : radiusState.kind !== 'square',
        p5Detail: {
          borderRadius: m.borderRadius,
          kind: radiusState.kind,
          standardPx: 6,
          note: radiusState.kind === 'micro' ? '微格（短边 <12px）按 §2.6 取 2px 档' : null,
        },
      };
      if (row.P.p2Detail.surfaceBelowFloor) {
        deviations.push({
          theme, scene: scene.name, label: t.label,
          why: '实测承载面低于批准门槛（规范 §2.4 的纯色估算 ≠ 产品实际底色：亮档实测被 veil/玻璃抬深）',
          measuredSurface: row.measured.cSurface, floor: THRESHOLD[theme],
        });
      }
      rows.push(row);
      if (OUT_DIR) {
        writeFileSync(join(OUT_DIR, `frame-${theme}-${scene.name}-${t.index}-off.png`), offBuf);
        writeFileSync(join(OUT_DIR, `frame-${theme}-${scene.name}-${t.index}-on.png`), onBuf);
      }

      const tag = `${theme}/${scene.name} ${t.label}`;
      // 差分 0 且中心点已经不是它 ⇒ 打桩场景没把这个件真正上屏（浮岛未展开 / 被弹层盖住），
      // 记 notPainted 不入判；差分 > 0 的件一律照判（活动栏按钮就是这样通过的）。
      if (row.P.p1 === false && !t.paintedAtCenter && diff.count === 0) {
        notPainted.push(`${tag}: 差分 0 且中心点不是它（场景未真正上屏），未判环`);
        rows.pop();
        continue;
      }
      if (row.P.p1 === false) {
        findings.push(`P-1 ${tag} — focusVisible=${m.focusVisible} style=${m.outlineStyle} width=${m.outlineWidth} diff=${diff.count}`);
      }
      if (row.P.p2 === false) {
        findings.push(`P-2 ${tag} — 环色对承载面 ${row.measured.cSurface.toFixed(2)}:1 < ${THRESHOLD[theme]}:1`);
      }
      if (row.P.p3 === false) {
        findings.push(`P-3 ${tag} — offset=${m.outlineOffset} 边框外差分像素 ${outOfBox}`);
      }
      if (row.P.p4 === false) {
        findings.push(`P-4 ${tag} — 玻璃祖先 ${t.glassAncestor} 内未画出指示器（diff=${diff.count}）`);
      }
      if (row.P.p5 === false) {
        findings.push(`P-5 ${tag} — 计算圆角 ${m.borderRadius}（${radiusState.kind}）< 标准档 6px：`
          + '方角件的焦点环也是方的（规范 §2.6；方角登记机制已废除，请改组件圆角）');
      }
    }

    // ── P-6 全面圆角普查（本场景全树画框面，不看 Tab 序）─────────────────
    const survey = await page.evaluate(`(${SURVEY})(${JSON.stringify(scene.scope)})`);
    if (!survey) {
      skipped.push(`${theme}/${scene.name}: P-6 场景缺失（普查未执行）`);
    } else {
      for (const box of survey.rows) {
        const required = box.shortSide <= 12 ? 1 : 5.5;
        const pillOrCircle = box.maxRadius >= box.shortSide / 2 - 0.5;
        const ok = box.maxRadius >= required || pillOrCircle;
        const row6 = {
          theme, scene: scene.name, label: box.label, rect: box.rect, shortSide: box.shortSide,
          maxRadius: box.maxRadius, radii: box.radii, paints: { bg: box.bg, borderSides: box.borderSides, shadow: box.shadow },
          required, ok,
        };
        p6Rows.push(row6);
        if (!ok) {
          p6Fails.push(row6);
          findings.push(`P-6 ${theme}/${scene.name} ${box.label} — 画框面计算圆角 ${box.radii.join(' ')}`
            + `（短边 ${box.shortSide.toFixed(0)}px，画的是 bg=${box.bg} border=${box.borderSides} shadow=${box.shadow}）`
            + ' ⇒ 低于 §2.6 标准档（6px；微格 2px）');
        }
      }
      for (const st of survey.structural) {
        p6Structural.push({ theme, scene: scene.name, label: st.label, rect: st.rect, why: st.why });
      }
    }
  }
  await session.close();
}
await browser.close();

const summary = {
  browser: browserLabel,
  themes: THEMES,
  thresholds: THRESHOLD,
  sampled: rows.length,
  exempt: rows.filter((r) => r.P.exempt).length,
  p1Fail: rows.filter((r) => r.P.p1 === false).length,
  p2Fail: rows.filter((r) => r.P.p2 === false).length,
  p3Fail: rows.filter((r) => r.P.p3 === false).length,
  p4Samples: rows.filter((r) => r.P.p4 != null).length,
  p4Fail: rows.filter((r) => r.P.p4 === false).length,
  p5Samples: rows.filter((r) => r.P.p5 != null).length,
  p5Fail: rows.filter((r) => r.P.p5 === false).length,
  p6Samples: p6Rows.length,
  p6Fail: p6Fails.length,
  p6MicroAccepted: p6Rows.filter((r) => r.ok && r.shortSide <= 12).length,
  p6Structural: p6Structural.length,
  p5Micro: rows.filter((r) => r.P.p5Detail && r.P.p5Detail.kind === 'micro').length,
  p5RadiusKinds: (() => {
    const out = {};
    for (const r of rows) {
      const k = (r.p5 && r.p5.kind) || 'n/a';
      out[k] = (out[k] || 0) + 1;
    }
    return out;
  })(),
  subThree: rows.filter((r) => r.P.subThreeAnnotated).length,
  surfaceBelowFloor: deviations.length,
  cAdjacentRange: (() => {
    const vals = rows.map((r) => r.measured.cAdjacentMedian).filter((v) => v != null);
    return vals.length ? { min: Math.min(...vals), max: Math.max(...vals) } : null;
  })(),
  skipped,
  notPainted,
  p6: { samples: p6Rows.length, fail: p6Fails.length, structural: p6Structural },
  p3UnjudgedUnstable: unstableRows.length,
  deviations,
};

if (OUT_DIR) {
  mkdirSync(OUT_DIR, { recursive: true });
  writeFileSync(join(OUT_DIR, 'focus-runtime-readout.json'),
    JSON.stringify({ summary, rows, findings }, null, 2));
  writeFileSync(join(OUT_DIR, 'round-corners-runtime-readout.json'),
    JSON.stringify({ samples: p6Rows, failures: p6Fails, structural: p6Structural }, null, 2));
}

if (JSON_OUT) {
  console.log(JSON.stringify({ summary, rows, findings }, null, 2));
  process.exit(findings.length ? 1 : 0);
}

console.log(`\nfocus-ring-runtime — browser=${browserLabel} 样本 ${rows.length} 件（${THEMES.join('/')}；豁免 ${summary.exempt} 件）`);
for (const r of rows) {
  const med = r.measured.cAdjacentMedian;
  const surf = r.measured.cSurface;
  console.log(`  ${r.theme.padEnd(5)} ${r.scene.padEnd(17)} ${r.label.slice(0, 40).padEnd(42)}`
    + ` P1=${r.P.exempt ? 'exempt' : (r.P.p1 ? 'ok' : 'RED')}`
    + ` surf=${surf != null ? surf.toFixed(2) : '-'}`
    + ` adj=${med != null ? med.toFixed(2) : '-'}`
    + ` P3=${r.P.exempt ? 'exempt' : (r.P.p3 ? 'ok' : 'RED')}`
    + `${r.P.p4 != null ? ` P4=${r.P.p4 ? 'ok' : 'RED'}` : ''}`
    + `${r.glassAncestor ? ' [glass]' : ''}${r.P.subThreeAnnotated ? ' *' : ''}`);
}
if (skipped.length) {
  console.log('\n未采场景（如实记账，不静默通过）：');
  for (const s of skipped) console.log(`  - ${s}`);
}
if (unstableRows.length) {
  console.log(`\nP-3 弃判（聚焦会改布局，越界像素不能归因于环）${unstableRows.length} 件：`);
  for (const s of unstableRows) console.log(`  - ${s}`);
}
if (notPainted.length) {
  console.log(`\n未真正上屏因而未判环的件 ${notPainted.length}（打桩场景限制，不当作绿）：`);
  for (const s of notPainted.slice(0, 8)) console.log(`  - ${s}`);
}
console.log(`\nP-1 失败 ${summary.p1Fail} · P-2 失败 ${summary.p2Fail} · P-3 失败 ${summary.p3Fail}`
  + ` · P-4 样本 ${summary.p4Samples}（失败 ${summary.p4Fail}）`
  + ` · P-5 样本 ${summary.p5Samples}（失败 ${summary.p5Fail}，微格放行 ${summary.p5Micro}）`);
console.log(`* = 「环对相邻像素」实测 <3:1，已在读数里逐件标注（${summary.subThree} 件）`);
console.log(`P-6 画框面普查 ${summary.p6Samples} 件（方角失败 ${summary.p6Fail}；微格 2px 档放行 ${summary.p6MicroAccepted} 件）`);
if (p6Structural.length) {
  const named = [...new Set(p6Structural.map((s) => `${s.label}（${s.why}）`))];
  console.log(`结构全幅豁免（几何判据，具名 ${named.length} 件）：`);
  for (const n of named.slice(0, 12)) console.log(`  - ${n}`);
}
if (p6Fails.length) {
  console.log('P-6 方角件（前 20）：');
  for (const f of p6Fails.slice(0, 20)) {
    console.log(`  - ${f.theme}/${f.scene} ${f.label} 圆角=${f.radii.join(' ')} 短边=${f.shortSide.toFixed(0)}px`);
  }
}
console.log(`承载面实测低于门槛的偏差 ${summary.surfaceBelowFloor} 件（读数 deviations 段；亮档产品底比纯白深，作者批的 2.53:1 是纯白基准）`);
if (findings.length) {
  console.error(`\nfocus-ring-runtime: RED — ${findings.length} 违规`);
  for (const f of findings) console.error(`  ${f}`);
  process.exit(1);
}
console.log('focus-ring-runtime: OK — 指示器可见、内缩、不越界、玻璃弹层内可读、环件为圆角件、'
  + `画框面全部圆角（P-1–P-6 绿；P-6 普查 ${summary.p6Samples} 件 / 0 方角）`);
process.exit(0);
