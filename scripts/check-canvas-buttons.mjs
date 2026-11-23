#!/usr/bin/env node
// Canvas chrome emitters must use the shared visual face; no local shadows.
// Dynamic emitters outside the template scan must be registered below; see CONTRIBUTING.
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
const web = fileURLToPath(new URL('../src/main/resources/web/', import.meta.url));
const read = p => readFileSync(join(web, p), 'utf8');
const failures = [];
for (const [file, patterns] of Object.entries({
  // Manual refresh was retired in favor of automatic refresh; do not require its emitter.
  'js/viewers/shared.js': ['canvas-source-toggle', 'canvas-ref-select', 'canvas-find-toggle'],
  'js/viewers/zoom.js': ['canvas-zoom-btn'],
  'js/viewers/image.js': ['canvas-zoom-btn canvas-pan-toggle', 'viewer-retry-btn'],
  'js/viewers/largeText.js': ['lt-btn'],
  'js/viewers/mobi.js': ['viewer-retry-btn'],
  'js/viewers/html.js': ['glass-control nf-frame-nav-back'],
  'js/askDock.js': ['ask-fab', 'ask-fab note-fab'],
  'js/canvas.js': ['deck-fab g2-fab', 'g2-fab canvas-button', 'canvas-pop-btn', 'canvas-refback-btn', 'deck-card-pop', 'canvas-merge-btn'],
  'js/mobile/navController.js': ['mf-cv-close g2-fab', 'mf-cv-stack g2-fab', 'mf-cv-star g2-fab'],
})) {
  const src = read(file);
  for (const name of patterns) {
    const lines = src.split('\n').filter(l => /(?:className\s*=|class:)\s*['"]/.test(l) && new RegExp(`(?:^|[\\s'"])${name}(?=[\\s'"])`).test(l));
    if (!lines.length || lines.some(l => !l.includes('canvas-button'))) failures.push(`${file}: ${name} missing shared class`);
  }
}
// Reader templates are exclusively canvas chrome: scan every emitted button, including new ones.
for (const file of ['js/viewers/readerChrome.js', 'js/viewers/annotations.js', 'js/viewers/xlsx.js']) {
  for (const match of read(file).matchAll(/<button\b[^>]*>/g)) {
    if (!/class="[^"]*\bcanvas-button\b/.test(match[0])) failures.push(`${file}: button missing shared class: ${match[0].slice(0, 100)}`);
  }
}
// Closed ask/note morph seeds must not paint a second glass layer below the FAB.
const askCss = read('css/ask.css');
if (!/\.ask-dock\.closed\s+\.ask-input\s*,\s*\.note-dock\.closed\s+\.note-body\s*\{[^}]*visibility:\s*hidden\s*;/s.test(askCss)) failures.push('closed ask/note glass seeds must be hidden');
// 引用入口图标单源（mobile-tab-quote-icon 批 2026-11-19）：该字形经 XML 解析建节点
// （quoteEntryIconNode），无 xmlns 的 <svg> 落 null 命名空间 ⇒ path 无描边、黑填充 ⇒ 图标不可见。
// 门禁：单源字形串必须显式带 SVG 命名空间（运行期真渲染断言见 tests/canvas-tab-reference.spec.mjs R13）。
{
  const quoteLogo = read('js/quoteLogo.js');
  const entry = quoteLogo.match(/export const QUOTE_ENTRY_ICON = '<svg([^']*)'/);
  if (!entry || !/\bxmlns="http:\/\/www\.w3\.org\/2000\/svg"/.test(entry[1])) failures.push('js/quoteLogo.js: QUOTE_ENTRY_ICON <svg> must declare xmlns (XML-parsed node would lose stroke/fill)');
}

/* ── G-CB3 单一 Canvas 面（canvas-button-unify 批 2026-11-18）────────────────
   作者令逐字口径：「单枚 Canvas 按钮共用既有全站按钮几何/行中线/毛玻璃实现」+
   「按钮组与手机待办右上角那组用**同一份实现**，不是外观复制」。本判据把
   「不许有第二份 Canvas 定义」变成机器判据。此前这里存在的那份第二定义是
     .canvas-button.canvas-button { border-radius: var(--ds-radius-pill);
                                     box-shadow: var(--ds-shadow-float) }
   双类把特异性抬到 (0,2,0)，压过 glass.css 圆钮族的「零投影」裁定（扁平化批
   2026-11-05 逐字退役 --cv-shadow）与 .ds-btn 的圆角档 --ds-radius-input。
   三支：
     G-CB3a `.canvas-button` = 纯身份钩子（同 .ask-send/.ask-stop 先例）⇒ 全仓
             任何以 `.canvas-button` 结尾的整选择器都不得携带声明（几何或材质）。
     G-CB3b Canvas 按钮组必须与手机待办组同处 glass.css 的**同一条**玻璃声明
             （一份实现、两个成员），且组容器不得在自己文件里复制组材质。
     G-CB3c 单钮投影两合法值：`none`（摘除）或 glass.css「Canvas 单钮共享面」
             那一条的 `var(--ds-shadow-float)`（回归修复：退役钩子的值自共享层
             供给，形态逐字符校验）；组容器投影只许共享 token `var(--cv-shadow)`。 */
const stripComments = s => s.replace(/\/\*[\s\S]*?\*\//g, '');
const rules = [];
for (const file of readdirSync(join(web, 'css')).filter(f => f.endsWith('.css'))) {
  const src = stripComments(read(`css/${file}`));
  for (const m of src.matchAll(/([^{}]+)\{([^{}]*)\}/g)) rules.push({ file, selector: m[1].trim(), body: m[2] });
}
// 选择器「末段复合选择器以该类收尾」判定：`.a.b` 与 `.b` 都算，`.x-b` 不算，
// `.b:hover` 不算（伪类挂在复合选择器尾，不是类本身）。
const selEndsWith = (sel, cls) => new RegExp(`(?:^|[\\s>+~])[^\\s>+~]*${cls.replace(/[.#]/g, m => '\\' + m)}$`).test(sel);
const FACE_PROPS = /(?:^|[;\s{])(?:background(?:-color|-image)?|border(?:-radius|-color|-width|-style)?|backdrop-filter|-webkit-backdrop-filter|box-shadow|width|height|min-height|padding|font-size|gap)\s*:/;
for (const r of rules) for (const sel of r.selector.split(',').map(s => s.trim())) {
  if (!selEndsWith(sel, '.canvas-button')) continue;
  if (sel.includes('.canvas-button.canvas-button')) failures.push(`G-CB3a second Canvas definition (doubled class): ${r.file} ${sel}`);
  else if (FACE_PROPS.test(r.body)) failures.push(`G-CB3a .canvas-button must stay an identity hook; ${r.file} ${sel} still declares a face`);
}
const glassSrc = stripComments(read('css/glass.css'));
const sharedGroup = [...glassSrc.matchAll(/([^{}]+)\{([^{}]*)\}/g)]
  .filter(m => /\.ta-pill\b/.test(m[1]) && /\.canvas-zoom-bar\b/.test(m[1]));
if (!sharedGroup.length) failures.push('G-CB3b canvas button group is not a member of the shared .ta-pill glass declaration');
else for (const prop of ['background', 'backdrop-filter', 'border']) {
  if (!new RegExp(`(?:^|[;\\s{])${prop}\\s*:`).test(sharedGroup[0][2])) failures.push(`G-CB3b shared group declaration lost ${prop}`);
}
for (const r of rules) {
  if (r.file === 'glass.css') continue;
  for (const sel of r.selector.split(',').map(s => s.trim())) {
    if (!selEndsWith(sel, '.canvas-zoom-bar')) continue;
    if (/(?:^|[;\s{])(?:background(?:-color)?|backdrop-filter|-webkit-backdrop-filter|border)\s*:/.test(r.body)) {
      failures.push(`G-CB3b ${r.file} ${sel} copies the group material; material belongs to the shared declaration`);
    }
  }
}
const CANVAS_SINGLE = /(?:\.canvas-button|\.canvas-source-toggle|\.canvas-ref-select|\.canvas-find-toggle|\.canvas-refresh-toggle|\.ask-fab|\.mf-cv-close|\.mf-cv-stack|\.mf-cv-star|#deck-fab|#canvas-head-close)/;
// G-CB3c（回归修复后口径，canvas-button-unify 收口 2026-11-18）：单钮投影只许
// ① none（摘除），或 ② **glass.css 那一条「Canvas 单钮共享面」**的
// var(--ds-shadow-float)（退役私有钩子的回归修复值；选择器必须整列
// `.canvas-button.族名` 复合，末段以族名收尾 ⇒ 钩子仍是身份钩子）。其它任何
// 文件/值 = 私有第二套 ⇒ 红。判据没有放宽：合法影子从「零个」变「一条具名
// 规则」，且该规则的形态被逐字符校验。
const isSharedFaceRule = (file, selector) => file === 'glass.css'
  && selector.split(',').map(s => s.trim()).every(s => /^\.canvas-button\.[a-z0-9-]+$/.test(s));
for (const r of rules) {
  const shadow = /(?:^|[;\s{])box-shadow\s*:\s*([^;]*)/.exec(r.body);
  if (!shadow) continue;
  const value = shadow[1].trim();
  if (selEndsWith(r.selector, '.canvas-zoom-bar')) {
    if (value !== 'var(--cv-shadow)') failures.push(`G-CB3c group container shadow must be the shared token: ${r.file} ${r.selector} = ${value}`);
    continue;
  }
  if (!CANVAS_SINGLE.test(r.selector)) continue;
  if (value === 'none') continue;
  // 2026-11-22 统一批：共享面投影 = 全站按钮阴影单源令牌 var(--btn-shadow)（design-system.css）。
  if (value === 'var(--btn-shadow)' && isSharedFaceRule(r.file, r.selector)) continue;
  failures.push(`G-CB3c private shadow in ${r.file}: ${r.selector} = ${value}`);
}
// G-CB3d：canvas-button 是身份钩子而非面 ⇒ 每个挂它的发射点必须同时挂一个已登记
// 族（族的几何/行中线全走 --ds-*/--cv-* 令牌）。只挂钩子 = 该钮零几何来源。
const REGISTERED_FAMILIES = [
  'canvas-source-toggle', 'canvas-ref-select', 'canvas-find-toggle', 'canvas-zoom-btn',
  'canvas-pan-toggle', 'canvas-pop-btn', 'canvas-refback-btn', 'canvas-merge-btn',
  'canvas-tab-close', 'canvas-video-btn', 'deck-fab', 'deck-card-pop', 'deck-card-x',
  'epub-nav-btn', 'epub-toc-item', 'epub-menu-btn', 'epub-anno-swatch', 'xlsx-sheet-tab',
  'g2-fab', 'ask-fab', 'note-fab',
  'lt-btn', 'viewer-retry-btn', 'nf-frame-nav-back', 'glass-control', 'mf-cv-close', 'mf-cv-stack', 'mf-cv-star',
];
const jsFiles = [...readdirSync(join(web, 'js')).filter(f => f.endsWith('.js')).map(f => `js/${f}`),
  ...readdirSync(join(web, 'js', 'viewers')).filter(f => f.endsWith('.js')).map(f => `js/viewers/${f}`),
  ...readdirSync(join(web, 'js', 'mobile')).filter(f => f.endsWith('.js')).map(f => `js/mobile/${f}`)];
for (const file of jsFiles) {
  read(file).split('\n').forEach((line, index) => {
    if (!/class="[^"]*\bcanvas-button\b/.test(line) && !/className\s*=\s*['"][^'"]*\bcanvas-button\b/.test(line)) return;
    if (!REGISTERED_FAMILIES.some(name => new RegExp(`(?:^|[\\s'"])${name}(?=[\\s'"$<{,;)\\]]|$)`).test(line))) {
      failures.push(`${file}:${index + 1} canvas-button without a registered family face: ${line.trim().slice(0, 90)}`);
    }
  });
}
// Browser-backed registration and compositing guard on the actual mobile Canvas.
// This deliberately scans the rendered fixture, not unvisited application states.
import { loadPlaywright, createSession, settle } from './lib/visual-harness.mjs';
const { mod } = loadPlaywright();
const browser = await mod.chromium.launch({ headless: true });
try {
  const context = await browser.newContext({ viewport: { width: 375, height: 812 }, hasTouch: true });
  const { page, boot } = await createSession({ newPage: () => context.newPage() }, { width: 375, height: 812, baseURL: 'https://stand.test/' });
  await boot();
  await page.waitForSelector('body.mobile-layer');
  await page.evaluate(async () => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: { id: 'file:/tmp/canvas-uniform.md', itemType: 'markdown', title: 'uniform', content: 'Canvas', pinned: true, absPath: '/tmp/canvas-uniform.md' } }));
    (await import('/js/mobile/navController.js')).dockTo('canvas');
  });
  await settle(page);
  const negative = process.env.CANVAS_BUTTON_NEGATIVE;
  if (negative) await page.evaluate(kind => {
    const search = document.querySelector('.mf-cv-host .canvas-find-toggle');
    if (kind === 'darken') { search.style.setProperty('transition', 'none', 'important'); search.style.setProperty('background', 'rgba(0,0,0,.8)', 'important'); }
    if (kind === 'unregistered') {
      const el = search.cloneNode(true); el.className = 'g2-fab unregistered-fab';
      el.style.cssText = 'position:fixed;left:100px;top:100px;z-index:9999'; document.body.append(el);
    }
    if (kind === 'layer') {
      const el = document.createElement('div'); const r = search.getBoundingClientRect();
      el.style.cssText = `position:fixed;left:${r.left}px;top:${r.top}px;width:${r.width}px;height:${r.height}px;backdrop-filter:blur(24px);background:rgba(0,0,0,.3);pointer-events:none;z-index:9`;
      document.body.append(el);
    }
  }, negative);
  const issues = await page.evaluate(() => {
    const errors = [];
    const visible = e => { const s = getComputedStyle(e); return e.getClientRects().length && s.display !== 'none' && s.visibility === 'visible' && Number(s.opacity) > 0; };
    const registry = ['canvas-find-toggle', 'canvas-source-toggle', 'canvas-ref-select', 'ask-fab', 'mf-cv-close', 'mf-cv-stack', 'mf-cv-star', 'mf-stack-x', 'mobile-back-btn', 'mh-chrome-btn', 'mh-sess-more', 'bgagent-more', 'bgagent-stop-fab', 'reminder-fab-add', 'tfl-fab-add', 'tfl-topmenu-btn', 'pf-edit-submit', 'pf-edit-cancel', 'fm-pick-back', 'fm-pick-fab', 'ta-fab', 'ta-save', 'mh-search-bar', 'deck-fab'];
    const buttons = [...document.querySelectorAll('button.g2-fab,button.canvas-find-toggle,button.canvas-source-toggle,button.canvas-ref-select,button.ask-fab,#deck-fab,#canvas-head-close')].filter(visible);
    for (const e of buttons) if (!registry.some(name => e.classList.contains(name)) && e.id !== 'canvas-head-close') errors.push(`unregistered circular button: ${e.className}`);
    const family = buttons.filter(e => e.matches('.canvas-find-toggle,.canvas-source-toggle,.canvas-ref-select,.ask-fab,.mf-cv-close,.mf-cv-stack,.mf-cv-star'));
    const ref = family.find(e => e.classList.contains('canvas-find-toggle'));
    if (!ref || family.length < 5) errors.push(`fixture missing canvas controls: ${family.length}`);
    if (ref) {
      const baseline = getComputedStyle(family.find(e => e.classList.contains('ask-fab'))).backgroundColor;
      for (const e of family) if (getComputedStyle(e).backgroundColor !== baseline) errors.push(`unequal button background: ${e.className}`);
      // Detect independently painted overlapping glass of roughly the same size.
      for (const e of family) {
        const r = e.getBoundingClientRect();
        for (const layer of document.querySelectorAll('body *')) {
          if (layer === e || layer.contains(e) || e.contains(layer) || buttons.includes(layer) || !visible(layer)) continue;
          const s = getComputedStyle(layer); if (s.backdropFilter === 'none') continue;
          const b = layer.getBoundingClientRect(); const overlap = Math.max(0, Math.min(r.right,b.right)-Math.max(r.left,b.left))*Math.max(0,Math.min(r.bottom,b.bottom)-Math.max(r.top,b.top));
          if (overlap / (r.width*r.height) > .8 && b.width <= r.width*1.25 && b.height <= r.height*1.25) errors.push(`extra glass layer under/over ${e.className}: ${layer.className}`);
        }
      }
    }
    return errors;
  });
  failures.push(...issues);
  await context.close();
} finally { await browser.close(); }
if (failures.length) { console.error(`canvas-buttons FAIL (${failures.length} hits)\n` + failures.join('\n')); process.exitCode = 1; }
else console.log('canvas-buttons PASS: shared face, rendered registration and overlap guard');
