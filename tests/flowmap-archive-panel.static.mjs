// flowmap-archive-panel.static.mjs — 降级静态断言（验收 spec 终局 4/8 后，按纪律
// 「失败一次即降级 node/静态断言、严禁反复重跑」对本不重跑 Playwright，改用
// Node 直读产品源码做确定性断言，覆盖 4 个降级测试中被阻断的动态子断言背后的
// 代码事实。语义依据：20260903_flowmap-archive-panel-spec.md §5.8b/§5.10/§6/
// §7.2/§7.9；动态证据与降级原因见 20260903_archive-panel-impl-report.md。
//
// Run: node tests/flowmap-archive-panel.static.mjs

import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const read = (p) => readFileSync(join(ROOT, p), 'utf8');
const css = read('css/flowMap.css');
const pcss = read('css/projectPanel.css');
const arch = read('js/flowMapArchive.js');
const tab = read('js/flowMapTab.js');

let pass = 0;
const fails = [];
/** @param {string} id @param {string} what @param {boolean} ok */
function assert(id, what, ok) {
  if (ok) { pass += 1; console.log(`  PASS [${id}] ${what}`); }
  else { fails.push(id); console.log(`  FAIL [${id}] ${what}`); }
}
const has = (hay, needle) => hay.includes(needle);

// ── T2·A8–A10 降级部分（A8.1–A9.5 已动态 PASS；A9.6 几何 + A10.x 被阻断）──
console.log('T2 · A9.6 dock-left 几何 / A10 详情联动（静态）');
assert('A9.6a', 'CSS：.fm-detail.dock-left right:404px（§5.8b 并排）',
  has(css, '.fm-detail.dock-left { right: 404px; }'));
assert('A9.6b', 'JS：并排判定 host 口径 clientWidth>=780（§5.8b 2026-09-04 修订；视口媒体查询已删）',
  has(arch, 'const DOCK_MIN_HOST_W = 780')
  && has(arch, 'host.clientWidth >= DOCK_MIN_HOST_W')
  && !/@media \(max-width: 799px\)/.test(css));
assert('A9.5a', 'CSS：详情 z-index 70（浮前）',
  /\/\* ── 右侧详情面板[\s\S]{0,700}?z-index: 70;/.test(css));
assert('A9.5b', 'CSS：归档面板 z-index 60',
  /\/\* ── 归档面板（[^]*?z-index: 60;/.test(css));
assert('A10.0a', 'JS：主图节点点击 → openDetailFor（详情入口①）',
  has(tab, 'openNodeDetail(projectName, el.getAttribute') && has(tab, 'openDetailFor(container, projectName, nodeId)'));
assert('A10.2', 'JS：节点点击 stopPropagation（§5.10 豁免③，点节点不收面板）',
  has(tab, "e.stopPropagation(); // §5.10：点节点 = 选择（开详情），不触发空白收起"));
assert('A10.4', 'CSS：详情开合 220ms 同曲线 cubic-bezier(0.33,0,0.2,1)',
  /transition: opacity 0\.22s cubic-bezier\(0\.33, 0, 0\.2, 1\),\s*\n\s*transform 0\.22s cubic-bezier\(0\.33, 0, 0\.2, 1\)/.test(css));
assert('A10.5', 'JS：Esc 分层——详情在顶先关详情（z 70 顶层）',
  has(arch, 'if (!ctx.detail.hidden) { closeDetail(ctx); detailClosed = true; } // Esc 先关详情（z 70 顶层）'));

// ── T3·A11–A14 整体降级（前置步骤被面板遮挡，后续动态未执行）────────────
console.log('T3 · A11–A14 空白收起/拖拽豁免/hover 联动/三路关闭/焦点归还（静态）');
assert('A11.2', 'JS：文档级空白点击 → 收面板+详情（§5.10 统一收起）',
  /document\.addEventListener\('click'[\s\S]{0,900}?closePanel/.test(arch) || /统一收起/.test(arch));
assert('A11.3', 'JS：空白点击 >3px 位移豁免（拖拽不收起，§5.10②）',
  has(arch, 'pressPoint = { x: e.clientX, y: e.clientY }')
  && has(arch, 'Math.hypot(e.clientX - pressPoint.x, e.clientY - pressPoint.y) > 3'));
assert('A12', 'JS：hover 联动下游高亮（highlightDownstream / highlightChainDownstream）',
  has(arch, 'function highlightDownstream') && has(arch, 'function highlightChainDownstream'));
assert('A13', 'JS：三路关闭——fab 切换 / 面板 ✕ / 详情 ✕',
  has(arch, "ctx.fab.addEventListener('click'") && has(arch, 'panelClose.addEventListener') && has(arch, 'detailClose.addEventListener'));
assert('A14', 'JS：关闭后焦点归还悬浮钮（§5.7）',
  has(arch, 'ctx.fab.focus({ preventScroll: true })'));

// ── T6·A17+A18 整体降级（reload 后恢复态拦截导航，断言未执行）────────────
console.log('T6 · A17 重载基线 / A18 TTL 清理与防复活（静态）');
assert('A17a', 'JS：视图关闭（canvas-tab-closed）→ dropStore（重载=基线重建）',
  has(tab, 'canvas-tab-closed') && has(tab, 'dropStore(project)'));
assert('A17b', 'JS：种子路径 refreshChains+purgeExpired（快照导入触发，§7.2）',
  has(tab, 'refreshChains(projectName, fm.nodes)') && has(tab, 'purgeExpired(projectName)'));
assert('A18a', 'JS：purgeExpired 按 24h TTL 过滤（ARCHIVE_TTL_MS=86400000）',
  has(arch, 'ARCHIVE_TTL_MS = 86400000') && has(arch, 'now - (c.completedAt || 0) <= ARCHIVE_TTL_MS'));
assert('A18b', 'JS：到期成员入 expiredIds（派生输入排除，§18-C10）',
  has(arch, 's.expiredIds.add(m.id)'));
assert('A18c', 'JS：refreshChains 派生输入排除 expiredIds（防链重判复活）',
  has(arch, '.filter((n) => !s.expiredIds.has(n.id))'));
assert('A18d', 'JS：到期清墓碑（防墓碑复活链）',
  has(arch, 's.tombstones.delete(m.id)'));

// ── 回归护栏：本轮修复的幂等判定保持集合语义 ─────────────────────────────
console.log('Fix · refreshChains identical 集合语义（终局修复回归护栏）');
assert('FIXa', 'JS：identical = id 集相等（与序无关），逐位对比已废除',
  has(arch, 'const prevIds = prev ? new Set(prev.members.map((m) => m.id)) : null;')
  && !has(arch, 'b.members[i] && b.members[i].id === m.id'));
assert('FIXb', 'CSS：projectPanel 未引入 container-type（回退 375 几何回归实验）',
  !has(pcss, 'container-type'));

// ── 回归护栏：20260904 v3 三缺陷修复（§5.8b 修订注记）─────────────────────
console.log('Fix · 20260904 三缺陷修复静态护栏（D1 host 口径 max-width / D2 已上移 A9.6b / D3 头部让位）');
assert('D1a', 'CSS：.fm-archive-panel max-width 改 host 口径 calc(100% - 32px)',
  /\.fm-archive-panel \{[^]*?max-width: calc\(100% - 32px\)/.test(css));
assert('D1b', 'CSS：.fm-detail max-width 改 host 口径 calc(100% - 32px)',
  /\.fm-detail \{[^]*?max-width: calc\(100% - 32px\)/.test(css));
assert('D1c', 'CSS：flowMap.css 无 100vw 视口口径取值残留（值位置；悬浮层几何全 host 口径）',
  !has(css, 'calc(100vw') && !/: ?100vw/.test(css));
assert('D3', 'CSS：.flowmap-card-header padding-right:56px（让出悬浮钮 16+40 + 徽章区）',
  /\.flowmap-card-header \{[^]*?padding-right: 56px/.test(css));
assert('D3b', 'CSS：.flowmap-summary overflow ellipsis（D3 让位 + 窄 host 收缩时截断，不撑出横向滚动）',
  /\.flowmap-summary \{[^]*?overflow: hidden;[^]*?text-overflow: ellipsis/.test(css));

console.log(`\n${pass} passed, ${fails.length} failed${fails.length ? ' → ' + fails.join(', ') : ''}`);
process.exit(fails.length ? 1 : 0);
