// legacy-ui-retire.static.mjs — 旧 UI 退役批·静态降级断言（node 直读源码，
// 无浏览器）。与 tests/legacy-ui-retire.spec.mjs 同源验收面；当 Playwright
// 不可用/失败时，本脚本按「失败一次即降级 node/静态断言」纪律兜底。
//
// Run: node tests/legacy-ui-retire.static.mjs   (全绿 exit 0)

import { readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');

let failed = 0;
const check = (name, cond, detail = '') => {
  if (cond) console.log(`PASS  ${name}`);
  else { failed++; console.error(`FAIL  ${name}${detail ? ' — ' + detail : ''}`); }
};
const read = (p) => {
  try { return readFileSync(join(WEB, p), 'utf8'); } catch { return null; }
};

// ── A. 整文件删除 ──────────────────────────────────────────────
for (const f of ['js/flowCanvas.js', 'js/flowTeams.js', 'js/flowList.js', 'js/flowDag.js']) {
  check(`deleted file absent: ${f}`, !existsSync(join(WEB, f)));
}
for (const f of ['scripts/verify-flows-indicator.cjs', 'scripts/shot-flows.cjs']) {
  const p = join(HERE, '..', f);
  check(`deleted script absent: ${f}`, !existsSync(p));
}

// ── B. 代码符号零残留（js 全域；注释豁免仅限白名单历史句）──────
const jsFiles = [];
const walk = (dir) => {
  for (const e of readdirSorted(dir)) {
    const full = join(dir, e);
    if (e.endsWith('.js')) jsFiles.push(full);
    else walkRec(full);
  }
};
import { readdirSync, statSync } from 'node:fs';
function walkRec(dir) { if (statSync(dir).isDirectory()) walk(dir); }
function readdirSorted(dir) { try { return readdirSync(dir).sort(); } catch { return []; } }
walk(join(WEB, 'js'));

// 代码符号形态：import 语句/调用/字面量键名（纯中文历史注释里的
// 「旧面板」等不在禁则内）。
const CODE_PATTERNS = [
  ['SIDEBAR_LEGACY_ENTRIES', /SIDEBAR_LEGACY_ENTRIES/],
  ['bindLegacyPanels', /bindLegacyPanels/],
  ['anchorLegacyPop', /anchorLegacyPop/],
  ['legacy-btn binding/getElementById', /getElementById\((['"`])legacy-btn\1\)/],
  ['teams-btn binding/getElementById', /getElementById\((['"`])teams-btn\1\)/],
  ['flows-btn binding/getElementById', /getElementById\((['"`])flows-btn\1\)/],
  ['flows-dropdown binding/getElementById', /getElementById\((['"`])flows-dropdown\1\)/],
  ['flows-indicator binding/getElementById', /getElementById\((['"`])flows-indicator\1\)/],
  ['flowCanvas import', /import\s+(?:\*\s+as\s+flowCanvas|[^;]*from\s+['"]\.\/flowCanvas\.js)/],
  ['flowTeams import', /from\s+['"]\.\/flowTeams\.js['"]/],
  ['flowList import', /from\s+['"]\.\/flowList\.js['"]/],
  ['flowDag import', /from\s+['"]\.\/flowDag\.js['"]/],
  ['legacy flow WS handler (flowStarted)', /onMessage\(\s*['"]flowStarted['"]/],
  ['legacy flow WS handler (flowProgress)', /onMessage\(\s*['"]flowProgress['"]/],
  ['legacy flow WS handler (flowCompleted)', /onMessage\(\s*['"]flowCompleted['"]/],
  ['legacy flow WS handler (flowNodesAdded)', /onMessage\(\s*['"]flowNodesAdded['"]/],
  ['legacy flow WS handler (flowMail)', /onMessage\(\s*['"]flowMail['"]/],
  ['legacy flow WS handler (teamList)', /onMessage\(\s*['"]teamList['"]/],
  ['legacy flow WS handler (mailQueued)', /onMessage\(\s*['"]mailQueued['"]/],
  ['legacy flow WS handler (mailDequeued)', /onMessage\(\s*['"]mailDequeued['"]/],
  ['state.teams data plane', /state\.teams\s*=/],
  ['state.flows data plane', /state\.flows\s*=/],
];
for (const [name, re] of CODE_PATTERNS) {
  const hits = [];
  for (const f of jsFiles) {
    const src = readFileSync(f, 'utf8');
    // 逐行匹配并剥掉纯注释行，注释历史提及不算残留
    const codeLines = src.split('\n').filter((l) => {
      const t = l.trim();
      return !(t.startsWith('//') || t.startsWith('/*') || t.startsWith('*'));
    }).join('\n');
    if (re.test(codeLines)) hits.push(f.replace(WEB + '/', ''));
  }
  check(`code symbol zero-residue: ${name}`, hits.length === 0, hits.join(', '));
}

// ── C. DOM / 静态产物 ─────────────────────────────────────────
const html = read('index.html') ?? '';
for (const id of ['legacy-btn', 'legacy-pop', 'teams-btn', 'flows-btn', 'flows-indicator', 'flows-dropdown',
  'legacy-pop-title', 'legacy-item-teams', 'legacy-item-flows']) {
  check(`html id absent: ${id}`, !html.includes(`id="${id}"`));
}
check('html keeps projects-btn', html.includes('id="projects-btn"'));
check('html keeps agents-btn (plugins)', html.includes('id="agents-btn"'));
check('html keeps settings-btn', html.includes('id="settings-btn"'));
check('html keeps canvas-toggle-btn', html.includes('id="canvas-toggle-btn"'));
check('html keeps bgagent-indicator', html.includes('id="bgagent-indicator"'));

// ── D. CSS 零残留 ─────────────────────────────────────────────
const cssFiles = ['nav.css', 'chat.css', 'base.css', 'sidebar.css'].map((f) => `css/${f}`);
for (const f of cssFiles) {
  const css = read(f) ?? '';
  for (const cls of ['.legacy-pop', '.legacy-item', '#flows-indicator', '#flows-dropdown', '.flows-row', '.flows-cancel', '--accent-flow']) {
    if (cls === '--accent-flow' && f !== 'css/sapphire.css') continue;
    if (f === 'css/sapphire.css') continue;
    check(`css zero-residue: ${f} ${cls}`, !css.includes(cls));
  }
}
check('css sapphire --accent-flow removed', !(read('css/sapphire.css') ?? '').includes('--accent-flow'));
check('css nav keeps neblink-logged-out (settings section intact)', (read('css/nav.css') ?? '').includes('.neblink-logged-out'));
check('css chat keeps bgagent-dropdown (sub-agent domain intact)', (read('css/chat.css') ?? '').includes('#bgagent-dropdown'));

// ── E. i18n 成对删除 + 绑定清除 ────────────────────────────────
const zh = read('js/locales/zh-CN.js') ?? '';
const en = read('js/locales/en.js') ?? '';
const removedKeys = ['activity.teams', 'activity.flows', 'activity.legacy', 'flows.indicatorTitle', 'flows.panelTitle',
  'flows.running', 'flows.none', 'flows.progress', 'flows.section', 'flows.definitions', 'flows.viewDag',
  'flows.nodesCount', 'flows.maxLoop', 'flows.entry', 'flows.status.running', 'flows.status.completed',
  'flows.status.failed', 'flows.status.cancelled', 'flows.status.definition', 'flows.status.idle',
  'flows.completed', 'flows.failed', 'flows.close', 'flows.cancelFlow', 'flows.noRunning',
  'flows.noRunningHint', 'flows.notFound', 'flows.noData', 'flows.loadingDag', 'flows.agentsRunning',
  'flows.agentsIdle', 'flows.role.manager', 'flowTeams.noActive', 'flowTeams.mountHint'];
let parityOk = true;
for (const k of removedKeys) {
  if (zh.includes(`'${k}'`) || en.includes(`'${k}'`)) { parityOk = false; console.error(`  key still present: ${k}`); }
}
check('locales: 35 retired keys absent in BOTH zh-CN and en (parity)', parityOk);
check('locales: flows.cancel kept (flowViewers consumes)', zh.includes("'flows.cancel'") && en.includes("'flows.cancel'"));
check('locales: activity.agents kept (agentManager consumes)', zh.includes("'activity.agents'") && en.includes("'activity.agents'"));

const i18n = read('js/i18n.js') ?? '';
for (const bind of ['legacy-btn', 'legacy-pop-title', 'legacy-item-teams', 'legacy-item-flows',
  'teams-btn', 'flows-btn', 'flows-indicator']) {
  check(`i18n binding removed: ${bind}`, !i18n.includes(`'${bind}':`));
}
check('i18n flows-header block removed', !i18n.includes("'#flows-dropdown .bg-dropdown-header'"));

// ── F. 保留面（Canvas 基建 + 新体系）───────────────────────────
const canvas = read('js/canvas.js') ?? '';
check('canvas: registerCanvasPanelButton mechanism intact', canvas.includes('export function registerCanvasPanelButton'));
check('canvas: retired panel tabs skipped at persist', /type === 'teams' \|\| t\.type === 'flows'/.test(canvas));
check('canvas: retired panel tabs filtered at restore', /t\.type !== 'teams' && t\.type !== 'flows'/.test(canvas));
check('canvas: retired panel tabs skipped at reconcile', /tab\.type === 'teams' \|\| tab\.type === 'flows'/.test(canvas));
const plugins = read('js/plugins.js') ?? '';
check('plugins: registerCanvasPanelButton(plugins, agents-btn) intact', plugins.includes("registerCanvasPanelButton('plugins', 'agents-btn'"));
const main = read('js/main.js') ?? '';
check('main: registerCanvasPanelButton(projects, projects-btn) intact', main.includes("registerCanvasPanelButton('projects', 'projects-btn'"));
check('html: settings-overlay element kept', html.includes('id="settings-overlay"'));
const activityBarSrc = read('js/activityBar.js') ?? '';
check('activityBar: settings button wiring intact', activityBarSrc.includes('bindSettingsButton') && activityBarSrc.includes("openSettingsPanel()"));
check('main: flowAnim side-effect import re-anchored', /import\s+['"]\.\/flowAnim\.js['"]/.test(main));
check('main: flowAgentPopup import kept (bg-agent popup domain)', main.includes("from './flowAgentPopup.js'"));
const flowAnim = read('js/flowAnim.js') ?? '';
check('flowAnim.js kept (Flow Map orbit driver)', flowAnim.includes('solar-node'));
const flowCss = read('js/flowCss.js') ?? '';
check('flowCss.js kept (shared with flowMapTab/projectTab)', flowCss.includes('ensureFlowCss'));
const flowViewers = read('js/flowViewers.js') ?? '';
check('flowViewers.js kept (agentFileViewer consumes)', flowViewers.includes('openViewerShell'));
const ws = read('js/ws.js') ?? '';
check('ws: agentStart/agentDone STREAM entries kept (popup paths)', ws.includes("'agentStart'") && ws.includes("'agentDone'"));
check('ws: STREAM set dropped legacy flow events', !/['"]flowMail['"]|['"]flowStarted['"]|['"]teamList['"]/.test(ws.replace(/^\s*\/\/.*$/gm, '')));

// ── G. 测试脚本自身语法 ────────────────────────────────────────
console.log(failed === 0 ? '\nALL STATIC CHECKS PASS' : `\n${failed} CHECK(S) FAILED`);
process.exit(failed === 0 ? 0 : 1);
