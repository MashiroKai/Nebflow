#!/usr/bin/env node
// capture-focus-ring.mjs — 焦点环统一批的对照图采集（真 Chromium，零端口）
//
// 为什么必须是本装置重采：设计件 focus-ring-unify-design__20261121_185700 里那张
// 对照板的「改后」列是**注入样式的模拟**（页内插一条规则 + !important），不是实施后的
// 像素。本脚本同时喂两份源树 —— 改前 = `--before-ref` 指向的 git 引用里的 css/，
// 改后 = 当前工作树 —— 于是四列（亮·改前 / 亮·改后 / 暗·改前 / 暗·改后）都是真像素。
//
// 用法：
//   node scripts/capture-focus-ring.mjs --out=<dir> [--before-ref=feat/base] [--zoom=3]
// 产物：<out>/focus-contact-sheet.png（对照板）、<out>/focus-readout.json（逐格读数）。
//
// 🔴 零端口零进程：走 scripts/lib/visual-harness.mjs 的 page.route 打桩。

import { mkdirSync, writeFileSync, readFileSync, cpSync, rmSync, existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { loadPlaywright, launchBrowser, createSession, setWebRoot, settle } from './lib/visual-harness.mjs';
import { decodePng, encodePng } from './lib/png-diff.mjs';

const argv = process.argv.slice(2);
const arg = (k, d) => (argv.find((a) => a.startsWith(`--${k}=`)) || '').slice(k.length + 3) || d;
const OUT = arg('out', join(tmpdir(), 'focus-ring-contact'));
const BEFORE_REF = arg('before-ref', 'feat/base');
const ZOOM = Number(arg('zoom', '3'));
const ROOT = fileURLToPath(new URL('..', import.meta.url));
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const CSS_REL = 'src/main/resources/web/css';

mkdirSync(OUT, { recursive: true });

// ── 改前源树：整树拷贝 + css/ 换回 --before-ref 的字节 ────────────────────
const beforeRoot = join(tmpdir(), `focus-before-web-${process.pid}`);
rmSync(beforeRoot, { recursive: true, force: true });
cpSync(WEB, beforeRoot, { recursive: true });
const beforeCssFiles = execFileSync('git', ['-C', ROOT, 'ls-tree', '--name-only', BEFORE_REF, `${CSS_REL}/`], { encoding: 'utf8' })
  .split('\n').map((s) => s.trim()).filter((s) => s.endsWith('.css'));
let swapped = 0;
for (const rel of beforeCssFiles) {
  const body = execFileSync('git', ['-C', ROOT, 'show', `${BEFORE_REF}:${rel}`], { maxBuffer: 64 * 1024 * 1024 });
  writeFileSync(join(beforeRoot, 'css', rel.split('/').pop()), body);
  swapped++;
}
// 当前树里存在但改前不存在的 css（本批没新增，防御性删除，避免残留盖住改前态）
for (const f of readdirSync(join(beforeRoot, 'css'))) {
  if (f.endsWith('.css') && !beforeCssFiles.some((r) => r.endsWith(`/${f}`))) rmSync(join(beforeRoot, 'css', f));
}

/** 对照件的清单：设计件「读图要点」里点名的面 + 本批补测面。 */
// 行清单（round2 修订）：① 大框行按**容器框本体**取（`frameSel`），不是取里面的透明内层
// —— 主会话 composer 的控件是 `#input`（作者令 2026-11-03 10:11 ④ 无环豁免），照它裁图
// 只能裁到一条没有框的文字带（round1 对照板第 5 行的实况）；作者令 2026-11-22 08:03
// 「像主会话这种框目前还无法完全显示 我要能完全显示」—— 取框本体，整框进格。
// ② 圆角标准批（规范 §2.6）落地的 6 个行族/格 + 运行时实测发现的方角披露行进表。
const ROWS = [
  { key: 'activity', label: '活动栏图标钮', scene: 'main', sel: '#settings-btn' },
  { key: 'search', label: '搜索按钮', scene: 'main', sel: '#search-btn' },
  { key: 'attach', label: '附件按钮', scene: 'main', sel: '#attach-btn' },
  { key: 'send', label: '发送按钮', scene: 'main', sel: '#send-btn' },
  { key: 'composer', label: '主会话输入条外壳框', scene: 'main', sel: '#input-bar', frameSel: '#input-bar', focusSel: '#input' },
  { key: 'input', label: '对话输入框', scene: 'main', sel: '#input' },
  { key: 'treeFile', label: '文件树行', scene: 'main', sel: '.explorer-item.explorer-file' },
  { key: 'treeFolder', label: '文件树文件夹行', scene: 'main', sel: '.explorer-item.explorer-folder' },
  { key: 'treeTool', label: '文件树工具栏钮', scene: 'main', sel: '#explorer-new-file-btn' },
  { key: 'todo', label: '待办胶囊', scene: 'main', sel: '.tfl-capsule' },
  // 待办行 `.tfl-row` 在零端口装置的空列表场景里不上屏（round1 同局限）—— 它的圆角
  // 只由 F7 静态判据覆盖，不进对照板（不拿没拍到的格充数）。
  { key: 'setNav', label: '设置导航行', scene: 'settings', sel: '#btn-open-secrets' },
  { key: 'setInput', label: '设置选择件', scene: 'settings', sel: '.nf-select-trigger' },
  { key: 'setBtn', label: '设置主按钮', scene: 'settings', sel: '#btn-toggle-json' },
  { key: 'providers', label: 'providers 配置入口', scene: 'settings', sel: '.set-disclosure' },
  { key: 'perm', label: '权限卡按钮', scene: 'perm', sel: '.option-box button' },
];

const pw = loadPlaywright();
const { browser, label: browserLabel } = await launchBrowser(pw.mod, {});

const cells = [];
const readout = { browser: browserLabel, beforeRef: BEFORE_REF, zoom: ZOOM, cells: [] };

/** 打开场景（主面 / 设置弹层 / 权限卡）。 */
async function openScene(page, s, frame) {
  if (s === 'settings') {
    await page.evaluate(() => document.querySelector('#settings-btn')?.click());
    await page.waitForTimeout(700);
    return true;
  }
  if (s === 'perm') {
    await frame({
      type: 'askPermission', sessionId: 'visual-root', toolName: 'Bash',
      summary: 'rm -rf build/tmp && npm run build', dangerLevel: 2, requestId: 'req-visual-1',
      safetyMode: 'ask-edits',
      input: { command: 'rm -rf build/tmp && npm run build' },
      permDetail: { command: 'rm -rf build/tmp && npm run build', risky: true },
    });
    await page.waitForTimeout(600);
    return Boolean(await page.evaluate(() => document.querySelector('.option-box')));
  }
  return true;
}

/** 键盘模态聚焦 + 裁图。
 *
 *  🔴 round2 修订（作者令 2026-11-22 08:03「像主会话这种框目前还无法完全显示 我要能完全
 *  显示」）—— 采集侧原来有四处会让「框显示不完整」，逐条修：
 *   ① 裁切框贴视口边时被 `Math.min(vp - x, …)` 截断（大框在右/下缘被切）；
 *   ② 边距只有 6px，环贴着裁切边，读作「框被切断」；
 *   ③ 只按**控件**取图（composer 取到了无框的 `#input`），容器框本体根本没进图；
 *   ④ 合成时横向与纵向各自限幅（`Math.min(img.w*ZOOM, colW-pad)` / 高度同理），
 *      宽图被横向压扁 —— 框「变形」比裁切更像画错了。
 *  现在：整框必须在裁切区内（`frameComplete` 硬判），边距 10px，容器框按 frameSel 取
 *  本体、用 focusSel 制造聚焦态；合成侧改为**等比**缩放（单一比例因子），只缩不裁。 */
async function shootFocused(page, sel, opts = {}) {
  const frameSel = opts.frameSel || sel;
  const focusSel = opts.focusSel || sel;
  const ok = await page.evaluate(({ sel, frameSel, focusSel }) => {
    // 取第一枚**真的上屏**的匹配件（同一选择器常命中隐藏的辅助按钮）
    const pick = (s) => [...document.querySelectorAll(s)].find((e) => {
      const r = e.getBoundingClientRect();
      const cs = getComputedStyle(e);
      return r.width >= 8 && r.height >= 8 && cs.visibility !== 'hidden' && cs.display !== 'none';
    }) || null;
    const frameEl = pick(frameSel);
    if (!frameEl) return null;
    frameEl.scrollIntoView({ block: 'center' });
    const focusEl = focusSel === frameSel ? frameEl : pick(focusSel);
    if (focusEl && focusEl.focus) focusEl.focus();
    const r = frameEl.getBoundingClientRect();
    const vp = { w: innerWidth, h: innerHeight };
    const cs = getComputedStyle(frameEl);
    const focusCs = focusEl ? getComputedStyle(focusEl) : null;
    return {
      ok: true,
      focusedEl: focusEl ? focusEl.matches(':focus-visible') : false,
      cv: focusEl ? focusEl.matches(':focus-visible') : false,
      rect: { x: r.x, y: r.y, w: r.width, h: r.height },
      vp,
      frame: { radius: cs.borderRadius, overflow: cs.overflow, outline: cs.outline, offset: cs.outlineOffset },
      outline: focusCs ? focusCs.outline : cs.outline,
      offset: focusCs ? focusCs.outlineOffset : cs.outlineOffset,
    };
  }, { sel, frameSel, focusSel });
  if (!ok || ok.rect.w < 4) return null;
  await page.waitForTimeout(80);
  const PAD2 = 10;
  const x0 = ok.rect.x - PAD2;
  const y0 = ok.rect.y - PAD2;
  const x1 = ok.rect.x + ok.rect.w + PAD2;
  const y1 = ok.rect.y + ok.rect.h + PAD2;
  // 整框是否完整可见（框 + 边距都在视口内）—— 不完整就记下来，绝不当成「拍到了」
  const frameComplete = x0 >= 0 && y0 >= 0 && x1 <= ok.vp.w && y1 <= ok.vp.h;
  const clip = {
    x: Math.max(0, Math.floor(x0)),
    y: Math.max(0, Math.floor(y0)),
    width: Math.ceil(Math.min(ok.vp.w, x1) - Math.max(0, x0)),
    height: Math.ceil(Math.min(ok.vp.h, y1) - Math.max(0, y0)),
  };
  if (clip.width < 4 || clip.height < 4) return null;
  const buf = await page.screenshot({ clip, animations: 'disabled', caret: 'hide' });
  return {
    buf,
    meta: {
      focusVisible: ok.cv, outline: ok.outline, offset: ok.offset, rect: ok.rect,
      frame: ok.frame, frameComplete,
      clippedBy: frameComplete ? null : `视口 ${ok.vp.w}×${ok.vp.h} 装不下整框（${Math.round(ok.rect.w)}×${Math.round(ok.rect.h)} @ ${Math.round(ok.rect.x)},${Math.round(ok.rect.y)}）`,
    },
  };
}

for (const theme of ['light', 'dark']) {
  for (const variant of ['before', 'after']) {
    setWebRoot(variant === 'before' ? beforeRoot : WEB);
    const session = await createSession(browser, {
      colorScheme: theme,
      apiStubs: {
        '/api/agents': { body: { agents: [] } },
        '/api/projects': { body: { projects: [{ id: 'p1', name: 'nebflow-stand', path: '/w/nebflow-stand' }] } },
      },
    });
    const { page, frame } = session;
    await session.boot({ config: { llm: { providers: {} }, features: { friends: false } } });
    await page.evaluate((t) => document.documentElement.setAttribute('data-theme', t), theme);
    await page.waitForTimeout(250);

    const sceneOpened = { main: true, settings: false, perm: false };
    for (const row of ROWS) {
      if (!sceneOpened[row.scene]) {
        // 每个场景独立：先回主面（重新 boot 的成本高，这里按「设置 → Esc」回收）
        if (row.scene === 'settings' && !sceneOpened.settings) {
          sceneOpened.settings = await openScene(page, 'settings', frame);
        }
        if (row.scene === 'perm' && !sceneOpened.perm) {
          await page.keyboard.press('Escape');
          await page.waitForTimeout(400);
          sceneOpened.perm = await openScene(page, 'perm', frame);
        }
        if (!sceneOpened[row.scene]) {
          readout.cells.push({ theme, variant, key: row.key, label: row.label, captured: false, why: '场景未打开' });
          continue;
        }
      }
      await page.keyboard.press('Tab'); // 键盘模态
      const shot = await shootFocused(page, row.sel, { frameSel: row.frameSel, focusSel: row.focusSel });
      if (!shot) {
        readout.cells.push({ theme, variant, key: row.key, label: row.label, captured: false, why: '元素未上屏/未聚焦' });
        continue;
      }
      cells.push({ theme, variant, key: row.key, buf: shot.buf });
      readout.cells.push({
        theme, variant, key: row.key, label: row.label, captured: true,
        focusVisible: shot.meta.focusVisible, outline: shot.meta.outline, offset: shot.meta.offset,
        rect: shot.meta.rect,
        // round2：整框可见性硬判（作者令「我要能完全显示」）——frameComplete=false 即「没拍全」，
        // 报告里按失败列出，不许当成已采到。
        frameComplete: shot.meta.frameComplete,
        clippedBy: shot.meta.clippedBy,
        frameRadius: shot.meta.frame.radius,
        frameOverflow: shot.meta.frame.overflow,
      });
    }
    await session.close();
  }
}
await browser.close();
rmSync(beforeRoot, { recursive: true, force: true });

// ── 合成对照板（最近邻放大，无插值）──────────────────────────────────────
const COLS = [
  { theme: 'light', variant: 'before', title: '亮 · 改前' },
  { theme: 'light', variant: 'after', title: '亮 · 改后' },
  { theme: 'dark', variant: 'before', title: '暗 · 改前' },
  { theme: 'dark', variant: 'after', title: '暗 · 改后' },
];
const byKey = new Map();
for (const c of cells) {
  if (!byKey.has(c.key)) byKey.set(c.key, new Map());
  byKey.get(c.key).set(`${c.theme}-${c.variant}`, c.buf);
}
const rowKeys = ROWS.filter((r) => COLS.some((c) => byKey.get(r.key)?.has(`${c.theme}-${c.variant}`)));

const CELL_PAD = 8;
const LABEL_W = 150;
const HEADER_H = 34;
// ── 合成（round2 重写）：**等比缩放，只缩不裁** ───────────────────────────────
// 改前：列宽 `min(560, w)` 与行高 `min(220, h)` 各自限幅，贴图时又分别算 dw/dh ⇒
// 宽图的横向被单独压 —— 大框（composer 外壳）因此「变形/读不出完整框」。
// 现在每张图只取**一个**比例因子 s = min(ZOOM, MAX_CELL_W/img.w, MAX_CELL_H/img.h)，
// 宽高同除同乘，框的比例恒为真；整框永远完整进格（裁切在采集侧已保证 pad 内完整）。
const MAX_CELL_W = 620;
const MAX_CELL_H = 320;
const decoded = new Map();
for (const [key, map] of byKey) {
  for (const [ck, buf] of map) {
    decoded.set(`${key}/${ck}`, decodePng(buf));
  }
}
const scaleOf = (img) => Math.min(ZOOM, MAX_CELL_W / img.width, MAX_CELL_H / img.height);
const scaled = new Map();
for (const [k, img] of decoded) {
  const sc = scaleOf(img);
  scaled.set(k, { w: Math.max(1, Math.round(img.width * sc)), h: Math.max(1, Math.round(img.height * sc)), scale: sc });
}
const colW = [], rowH = [];
for (let ci = 0; ci < COLS.length; ci++) {
  let w = 0;
  for (const r of rowKeys) {
    const m = scaled.get(`${r.key}/${COLS[ci].theme}-${COLS[ci].variant}`);
    if (m) w = Math.max(w, m.w);
  }
  colW[ci] = Math.max(60, w) + CELL_PAD * 2;
}
for (const r of rowKeys) {
  let h = 0;
  for (const c of COLS) {
    const m = scaled.get(`${r.key}/${c.theme}-${c.variant}`);
    if (m) h = Math.max(h, m.h);
  }
  rowH.push(Math.max(36, h) + CELL_PAD * 2);
}
const W = LABEL_W + colW.reduce((a, b) => a + b, 0);
const H = HEADER_H + rowH.reduce((a, b) => a + b, 0) + 26;
const canvas = Buffer.alloc(W * H * 4, 255);
for (let i = 0; i < W * H; i++) { // 底色 #f7f8fa
  canvas[i * 4] = 0xf7; canvas[i * 4 + 1] = 0xf8; canvas[i * 4 + 2] = 0xfa; canvas[i * 4 + 3] = 255;
}
const fill = (x0, y0, w, h, [r, g, b]) => {
  for (let y = Math.max(0, y0); y < Math.min(H, y0 + h); y++) {
    for (let x = Math.max(0, x0); x < Math.min(W, x0 + w); x++) {
      const i = (y * W + x) * 4;
      canvas[i] = r; canvas[i + 1] = g; canvas[i + 2] = b; canvas[i + 3] = 255;
    }
  }
};
// 列头色带
fill(0, 0, W, HEADER_H, [0x1f, 0x29, 0x37]);
let cx = LABEL_W;
for (let ci = 0; ci < COLS.length; ci++) {
  fill(cx, 0, colW[ci], HEADER_H, ci % 2 ? [0x2b, 0x36, 0x45] : [0x1f, 0x29, 0x37]);
  cx += colW[ci];
}
// 行分隔线
let cy = HEADER_H;
for (let ri = 0; ri < rowKeys.length; ri++) {
  fill(LABEL_W - 1, cy, W - LABEL_W + 1, 1, [0xd8, 0xdc, 0xe3]);
  cy += rowH[ri];
}
// 贴图（最近邻）
let y = HEADER_H;
for (let ri = 0; ri < rowKeys.length; ri++) {
  let x = LABEL_W;
  for (let ci = 0; ci < COLS.length; ci++) {
    const img = decoded.get(`${rowKeys[ri].key}/${COLS[ci].theme}-${COLS[ci].variant}`);
    const sc = scaled.get(`${rowKeys[ri].key}/${COLS[ci].theme}-${COLS[ci].variant}`);
    if (img && sc) {
      const dw = sc.w;
      const dh = sc.h;
      const ox = x + CELL_PAD;
      const oy = y + CELL_PAD;
      for (let py = 0; py < dh; py++) {
        for (let px = 0; px < dw; px++) {
          const sx = Math.floor((px / dw) * img.width);
          const sy = Math.floor((py / dh) * img.height);
          const si = (sy * img.width + sx) * 4;
          const di = ((oy + py) * W + ox + px) * 4;
          canvas[di] = img.data[si];
          canvas[di + 1] = img.data[si + 1];
          canvas[di + 2] = img.data[si + 2];
          canvas[di + 3] = 255;
        }
      }
    }
    x += colW[ci];
  }
  y += rowH[ri];
}

writeFileSync(join(OUT, 'focus-contact-sheet.png'), encodePng(W, H, canvas));
writeFileSync(join(OUT, 'focus-readout.json'), JSON.stringify(readout, null, 2));

const captured = readout.cells.filter((c) => c.captured).length;
const incomplete = readout.cells.filter((c) => c.captured && c.frameComplete === false);
console.log(`focus-contact-sheet: ${W}x${H} · 行 ${rowKeys.length} · 列 ${COLS.length}（改前源树 = ${BEFORE_REF}，css 换回 ${swapped} 个文件）`);
console.log(`  格 ${captured}/${readout.cells.length} 采到；产物：${join(OUT, 'focus-contact-sheet.png')}`);
const missing = readout.cells.filter((c) => !c.captured);
if (missing.length) {
  console.log('  未采到的格：');
  for (const m of missing.slice(0, 12)) console.log(`    - ${m.theme}/${m.variant} ${m.label}: ${m.why}`);
}
console.log(`  行标签：${rowKeys.map((r) => r.label).join(' / ')}`);
if (incomplete.length) {
  console.log(`  ⚠ 整框未完整进格 ${incomplete.length} 格（frameComplete=false）：`);
  for (const c of incomplete.slice(0, 10)) console.log(`    - ${c.theme}/${c.variant} ${c.label}: ${c.clippedBy}`);
} else {
  console.log('  整框完整性：全部格 frameComplete=true（每格含整框 + 10px 边距，无裁切、无变形）');
}
