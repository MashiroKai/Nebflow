#!/usr/bin/env node
// Inventory checks declare source-level responsibility, not computed geometry.
// A modal drawer blocks interaction intentionally; it has no underlying scroll
// clearance requirement, but its own box is required to be scrollable.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
const css = name => readFileSync(new URL(`../src/main/resources/web/css/${name}`, import.meta.url), 'utf8');
const inventory = [
  ['todo top capsule', 'mobile-todo-apple.css', /\.ta-pill\s*$/, /height\s*:\s*var\(--mcap-h\)/, /\.mobile-page-body\s*$/, /padding-top\s*:[^;]*--mchrome-clear/],
  // 落队注：'todo bottom FAB' 行取 HEAD 侧——本树（滚域末端净空单源批 2026-11-18）
  // 的 `.ta-root` 让位消费 `--mobile-scroll-end`（值仍由 dock 让位派生，见本文件
  // 末尾的同名链断言）；作者线上的 `--mpage-fab-clearance` 那版是同一 owner 的
  // 更早形态，本树已收编。
  ['todo bottom FAB', 'mobile-todo-apple.css', /\.ta-fab-wrap\s*$/, /position\s*:\s*absolute/, /\.ta-root\s*$/, /padding-bottom\s*:[^;]*--mobile-scroll-end/],
  // 'todo editor tool' clearance owner (re-pointed 2026-11-18): the anchor is
  // declared on the PAGE element (`body.mobile-layer .mobile-page[data-page=
  // "todo-apple-item"]` token block), not on `.ta-root` — `.ta-tool` is appended
  // to `.mobile-page-body` and is `.ta-root`'s SIBLING, so it can only inherit a
  // page-level custom property. The block still carries all three legs once:
  // keyboard (--ta-kb), dock (--mobile-dock-clearance) and band height
  // (--bottom-band-d); `.ta-root` then consumes the derived --ta-band-stack,
  // which the 'todo reply footer' row below asserts.
  ['todo editor tool', 'mobile-todo-apple.css', /\.ta-tool\s*$/, /position\s*:\s*fixed/,
    /\[data-page="todo-apple-item"\]\s*$/,
    /(?=[\s\S]*--ta-kb)(?=[\s\S]*--bottom-band-d)(?=[\s\S]*--mobile-dock-clearance)/],
  // 'todo reply footer' (2026-11-18): the detail page's reply band is an IN-FLOW
  // footer — it declares no positioning at all — so its clearance owner is
  // `.ta-root`'s bottom padding, which reserves the band stack. Asserting both
  // halves keeps "the reply sits 8px above the band" a source-level
  // responsibility instead of a computed coincidence.
  // 落队注：本树的页体底垫已被「滚域末端净空单源批」归零（`.mobile-page-body:has(> .ta-root)`
  // ⇒ padding-bottom: 0），故 owner 只断言「padding-bottom 消费 --ta-band-stack」
  // ——dock 净空腿仍由上一行（页元素 token 块携带 --mobile-dock-clearance）钉着，
  // 减法口径在本树会把预留少算一条 dock 净空（实测压带 64px）。
  ['todo reply footer', 'mobile-todo-apple.css', /\.ta-reply\s*$/, /flex\s*:\s*0 0 auto/,
    /\[data-page="todo-apple-item"\]\s+\.ta-root\s*$/,
    /(?=[\s\S]*padding-bottom)(?=[\s\S]*--ta-band-stack)/],
  ['message search', 'mobileSearchFlat.css', /\.mh-search-dock:not\(\.has-query\)\s*$/, /position\s*:\s*absolute/, /#fm-conversations/, /padding-top\s*:[^;]*--mhsc-band/],
  ['contact search', 'mobileSearchFlat.css', /\.mh-search-dock:not\(\.has-query\)\s*$/, /position\s*:\s*absolute/, /#fm-contacts-body/, /padding-top\s*:[^;]*--mhsc-band/],
  ['session identity', 'mobileLayer.css', /\.mh-idhead\s*$/, /position\s*:\s*absolute/, /\[data-page='conversation'\]\s+\.fm-flow\s*$/, /padding-top\s*:[^;]*104px/],
  ['reminder FAB', 'mobileLayer.css', /#reminder-panel\s+\.reminder-fab-add\s*$/, /bottom\s*:[^;]*--mobile-dock-clearance/, /#reminder-panel\s+\.reminder-panel-body\s*$/, /padding\s*:[^;]*--mobile-scroll-end/],
  // Selection Ask is dismissible and may cover its source while open; it is
  // not a persistent chrome bar. Its answer owns its own overflow scrolling
  // (the transcript column `.ask-col` scrolls inside the dialog box).
  // ⚠ 选区 Ask 抽取批（2026-11-18，作者令「复用既有组件/链路不另造第二套」）：
  // 本面的落点从自建卡片（`.selection-ask-panel` + `.selection-ask-answer`，已整族
  // 退役）改为**共用件对话框**的第二份实例——覆盖层 = `.ask-dock.selection-dock`
  // （同一 `.ask-dock` 壳的视口浮定位档，position: fixed），滚动归属 = 共用的
  // `.ask-col` 转录列（overflow-y: auto）。责任面（可关闭、可遮源、自身滚动）逐条
  // 不变，只是选择器跟着落点走；旧选择器已不存在，留在这里会让这条清单检查一个
  // 已退役的面（假红）。
  ['selection Ask dialog (source occlusion exempt)', 'ask.css', /^\.ask-dock\.selection-dock\s*$/, /position\s*:\s*fixed/, /^\.ask-col\s*$/, /overflow-y\s*:\s*auto/],
  ['GitViz bottom controls', 'gitViz.css', /^\.gitviz-corner\s*$/, /position\s*:\s*absolute/, /body\.mobile-layer\s+\.gitviz-main\s*$/, /padding-bottom\s*:[^;]*--gitviz-bottom-clearance/],
  ['GitViz ask', 'gitViz.css', /^\.gitviz-ask-dock\s*$/, /position\s*:\s*absolute/, /body\.mobile-layer\s+\.gitviz-main\s*$/, /scroll-padding-bottom\s*:[^;]*--gitviz-bottom-clearance/],
  ['GitViz sheet', 'gitViz.css', /^\.gitviz-sheet\s*$/, /position\s*:\s*absolute/, /body\.mobile-layer\s+\.gitviz-main\s*$/, /scroll-padding-bottom\s*:[^;]*--gitviz-bottom-clearance/],
  ['modal drawer', 'mobileLayer.css', /^\.mf-drawer\s*$/, /position\s*:\s*absolute/, /^\.mf-drawer-body\s*$/, /overflow-y\s*:\s*auto/],
];
export function checkInventory(get = css) {
  const errors = [];
  for (const [name, file, overlay, overlayRule, owner, ownerRule] of inventory) {
    const source = get(file).replace(/\/\*[\s\S]*?\*\//g, ' ');
    const rules = [...source.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map(m => ({ selector: m[1].trim(), body: m[2] }));
    if (!rules.some(r => overlay.test(r.selector) && overlayRule.test(r.body))) errors.push(`${name}: missing overlay declaration in ${file}`);
    if (!rules.some(r => owner.test(r.selector) && ownerRule.test(r.body))) errors.push(`${name}: missing or mismatched clearance owner in ${file}`);
  }
  // 净空 owner 一律消费单源 `--mobile-scroll-end`（滚域末端净空单源批
  // 2026-11-18），而它自身必须仍由 dock 让位派生 —— 防止「owner 换了 token、
  // 净空却不再含 dock 让位」的脱钩（与 check-floating-clearance 的同名链断言同源）。
  const layer = get('mobileLayer.css').replace(/\/\*[\s\S]*?\*\//g, ' ');
  const endDecl = layer.match(/(?:^|[\s;])--mobile-scroll-end\s*:\s*([^;]+)/);
  if (!endDecl) errors.push('--mobile-scroll-end: missing single-source declaration in mobileLayer.css');
  else if (!/--mobile-dock-clearance/.test(endDecl[1])) errors.push('--mobile-scroll-end: no longer derives from --mobile-dock-clearance in mobileLayer.css');
  return errors;
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const errors = checkInventory();
  if (errors.length) { console.error(errors.join('\n')); process.exitCode = 1; }
  else console.log('mobile floating inventory: PASS (source declarations only)');
}
