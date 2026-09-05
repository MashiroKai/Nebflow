// flowmap-ui-fix.static.mjs — ①连线 hover 可读性修复 + ②标题栏「← 项目名」单元素
// 的静态护栏（Node 直读产品源码确定性断言；Playwright 动态验收见 flowmap-ui-fix.spec.mjs。
// 语义依据：作者 2026-09-05 12:29 报告两处修复要求。
//
// Run: node tests/flowmap-ui-fix.static.mjs

import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const read = (p) => readFileSync(join(ROOT, p), 'utf8');
const css = read('css/flowMap.css');
const pcss = read('css/projectPanel.css');
const tab = read('js/flowMapTab.js');
const ptab = read('js/projectTab.js');
const zh = read('js/locales/zh-CN.js');
const en = read('js/locales/en.js');

let pass = 0;
const fails = [];
/** @param {string} id @param {string} what @param {boolean} ok */
function assert(id, what, ok) {
  if (ok) { pass += 1; console.log(`  PASS [${id}] ${what}`); }
  else { fails.push(id); console.log(`  FAIL [${id}] ${what}`); }
}
const has = (hay, needle) => hay.includes(needle);

// ── ① 主因：applyHover(null) 还原必须连边类同清（stale .fm-edge-dim 根治）──
console.log('① 主因修复：L0 还原路径清边类（stale .fm-edge-dim 根治）');
assert('H1a', 'JS：clearHoverClasses 同时清节点类与边类（.fm-edge-hi/.fm-edge-dim）',
  has(tab, "canvas.querySelectorAll('.fm-hi, .fm-nb, .fm-dim')")
  && has(tab, "canvas.querySelectorAll('.fm-edge-hi, .fm-edge-dim')"));
assert('H1b', 'JS：applyHover 在 hoverId 提前 return 之前先走 clearHoverClasses（还原不漏边）',
  /function applyHover\(canvas, hoverId\) \{\s*\n\s*const adj = adjByCanvas\.get\(canvas\);\s*\n\s*if \(!adj\) return;\s*\n\s*clearHoverClasses\(canvas\);\s*\n\s*if \(!hoverId\) return;/.test(tab));

// ── ① 次因：连线本体 hover 入口（pointer-events + 边加亮语义）────────────
console.log('① 次因修复：连线 hover 入口 + 只加亮不淡化');
assert('H2a', 'CSS：.flowmap-card 域内边路径/箭头开启 pointer-events（flow-run 不受影响）',
  /\.flowmap-card \.solar-edges path\.flow-edge,\s*\n\.flowmap-card \.solar-edges \.fm-edge-arrow \{ pointer-events: visiblePainted; \}/.test(css));
assert('H2b', 'JS：bindHover 处理 [data-edge-id] 目标（连线委托入口）',
  has(tab, "e.target.closest?.('[data-edge-id]')"));
assert('H2c', 'JS：applyEdgeHover 存在且不加任何淡化类（.fm-dim/.fm-edge-dim 全程不落）',
  (() => {
    const i = tab.indexOf('function applyEdgeHover(canvas, edgeId)');
    const body = i >= 0 ? tab.slice(i, tab.indexOf('function bindHover', i)) : '';
    return body !== '' && !has(body, "fm-dim'") && !has(body, 'fm-edge-dim');
  })());
assert('H2d', 'JS：applyEdgeHover 跳过退场边（.fm-edge-exit）',
  /function applyEdgeHover\(canvas, edgeId\) \{[\s\S]*?classList\.contains\('fm-edge-exit'\)/.test(tab));
assert('H2e', 'CSS：.fm-edge-hi 加粗提亮（2.5 + sapphire .85，CSS 变量双主题自适应）',
  has(css, '.flow-edge.fm-edge.fm-edge-hi { stroke-width: 2.5; opacity: 1; stroke: rgb(var(--sapphire) / 0.85); }'));
assert('H2f', 'JS：边 hover 宽限 80ms 还原（与节点 hover 同 N2 语义）',
  /data-edge-id.*\n\s*.*clearTimeout\(graceTimer\);\s*\n\s*graceTimer = setTimeout\(restore, 80\)/.test(tab));

// ── ① 零回归：节点 hover N1 语义原样保留 ────────────────────────────────
console.log('① 零回归：节点 hover N1 语义保留');
assert('H3a', 'JS：applyHover 节点三档（fm-hi/fm-nb/fm-dim）与边按端点分档原样',
  has(tab, "if (id === hoverId) n.classList.add('fm-hi');")
  && has(tab, "else if (set && set.has(id)) n.classList.add('fm-nb');")
  && has(tab, "else n.classList.add('fm-dim');")
  && has(tab, "if (e && e.has(hoverId)) p.classList.add('fm-edge-hi');"));
assert('H3b', 'JS：退场卡/退场边不参与强调（.fm-exit / .fm-edge-exit 跳过）',
  has(tab, "if (n.classList.contains('fm-exit')) continue;"));

// ── ② 标题栏：单元素「← 项目名」────────────────────────────────────────
console.log('② 标题栏单元素「← 项目名」');
assert('B1a', 'JS：模板 = 箭头 icon + span.flowmap-back-name（项目名 esc）',
  has(ptab, '<i data-lucide="arrow-left"></i><span class="flowmap-back-name">${esc(projectName)}</span>'));
assert('B1b', 'JS：title 提示项目全名、aria-label 沿用 backToProjects（键保留取舍）',
  has(ptab, 'title="${esc(projectName)}"') && has(ptab, 'aria-label="${esc(t(\'project.backToProjects\'))}"'));
assert('B1c', 'JS：nav-bar 内不再出现「返回项目列表」文本按钮（键仅存 aria 引用）',
  !/class="flowmap-back-btn"[^>]*>\s*<i[^>]*><span>\$\{esc\(t\('project\.backToProjects'\)\)\}/.test(ptab));
assert('B1d', 'i18n：backToProjects 键 zh/en 成对保留（无增删，locale 文件零改动前提）',
  has(zh, 'backToProjects') && has(en, 'backToProjects'));
assert('B2a', 'CSS：长项目名 ellipsis 截断（.flowmap-back-name nowrap + ellipsis）',
  /\.flowmap-back-btn \.flowmap-back-name \{[^]*?text-overflow: ellipsis/.test(pcss)
  && /\.flowmap-back-btn \.flowmap-back-name \{[^]*?white-space: nowrap/.test(pcss));
assert('B2b', 'CSS：按钮 max-width 不撑横 nav-bar（min(100%, 420px)）+ icon 不挤压（flex 0 0 auto）',
  /\.flowmap-back-btn \{[^]*?max-width: min\(100%, 420px\)/.test(pcss)
  && /\.flowmap-back-btn svg \{ width: 14px; height: 14px; flex: 0 0 auto; \}/.test(pcss));
assert('B2c', 'JS：整元素可点返回（data-back-to-projects click → showProjectsList + stopPropagation 保留）',
  has(ptab, "[data-back-to-projects]").valueOf()
  && has(ptab, "e.stopPropagation();")
  && has(ptab, 'showProjectsList();'));
assert('B2d', 'JS：两条渲染路径共用（sameView 快速路径不重建 nav-bar，dataset 项目名一致性成立）',
  has(ptab, "pane.dataset.flowMapProject === projectName"));

console.log(`\n${pass} passed, ${fails.length} failed${fails.length ? ' → ' + fails.join(', ') : ''}`);
process.exit(fails.length ? 1 : 0);
