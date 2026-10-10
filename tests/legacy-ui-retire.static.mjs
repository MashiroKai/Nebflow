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
// flowmaptab-retire（#1228 两裁② 停用清单面 3）：main.js 的 flowAnim 副作用 import 摘除
// （上游 a60af705d 申报 S10/S11 零复用）——原「再锚定在场」断言随面翻转为退役脸断言。
check('main: flowAnim side-effect import removed (retired)', !/import\s+['"]\.\/flowAnim\.js['"]/.test(main));
check('main: flowAgentPopup import kept (bg-agent popup domain)', main.includes("from './flowAgentPopup.js'"));
const flowAnim = read('js/flowAnim.js') ?? '';
// 文件留盘（脱离模块图，build-web ORPHAN_EXEMPTIONS 在案）候 #1229 随 REST/WS 退役批删除。
check('flowAnim.js kept on disk (dormant, orphan-exempt, #1229 batch)', flowAnim.includes('solar-node'));
const flowCss = read('js/flowCss.js') ?? '';
check('flowCss.js kept (shared with projectTab list / delegate-chain views)', flowCss.includes('ensureFlowCss'));
const flowViewers = read('js/flowViewers.js') ?? '';
check('flowViewers.js kept (agentFileViewer consumes)', flowViewers.includes('openViewerShell'));
const ws = read('js/ws.js') ?? '';
check('ws: agentStart/agentDone STREAM entries kept (popup paths)', ws.includes("'agentStart'") && ws.includes("'agentDone'"));
check('ws: STREAM set dropped legacy flow events', !/['"]flowMail['"]|['"]flowStarted['"]|['"]teamList['"]/.test(ws.replace(/^\s*\/\/.*$/gm, '')));

// ── G. flowmaptab-retire 停用清单（#1228 两裁② · 退役脸断言移本族）───────────
// 六面清单（出处 20261006_201326_tasklist-delegate-stub §2.2 追认版）：
//   面 1 flowMapTab.js 入口停用（projectsView='flow-map' 分支改指 delegate-chain）
//   面 2 flowMapArchive.js 前端消费面摘除（引擎 GET /flow-map/archive 不动＝#1229 面）
//   面 3 flowAnim.js main.js 副作用 import 摘除（见 F 段翻转断言）
//   面 4 flowCss.js 状态色 token 保留、仅 per-project 布局面停用
//   面 5 nodeData.js 非整文件停用（fetchFlowMap/controlChain 随 per-project REST 退役
//        批同摘——本批现读非 per-project 消费者在场，保留边＋偏差申报）
//   面 6 REST/WS 引擎退役面本批零触碰（时序＝N1 结构帧落地面）
const retireFiles = {
  'js/main.js': read('js/main.js') ?? '',
  'js/projectTab.js': read('js/projectTab.js') ?? '',
  'js/taskList.js': read('js/taskList.js') ?? '',
  'js/flowCss.js': read('js/flowCss.js') ?? '',
  'js/fmNodeCard.js': read('js/fmNodeCard.js') ?? '',
};
const codeOnly = (src) => src.split('\n').filter((l) => {
  const t = l.trim();
  return !(t.startsWith('//') || t.startsWith('/*') || t.startsWith('*'));
}).join('\n');

// 面 1：入口与视图种类零残留（代码符号形态；注释历史提及豁免）。
for (const [name, re] of [
  ['projectTab: flowMapTab import absent', /from\s+['"]\.\/flowMapTab\.js['"]/],
  ['projectTab: renderFlowMapInto symbol absent', /renderFlowMapInto/],
  ['projectTab: flow-map view kind no longer produced', /projectsView\s*=\s*['"]flow-map['"]/],
  ['projectTab: flowmap-view-body skeleton absent', /flowmap-view-body/],
  ['projectTab: data-back-to-projects back entry absent', /data-back-to-projects/],
  ['taskList: openProjectFlowMapAt entry absent', /openProjectFlowMapAt/],
  ['taskList: openProjectFlowMapChain entry absent', /openProjectFlowMapChain/],
  ['main: no flowMapTab import', /from\s+['"]\.\/flowMapTab\.js['"]/],
]) {
  const hits = [];
  for (const [f, src] of Object.entries(retireFiles)) {
    if (re.test(codeOnly(src))) hits.push(f);
  }
  check(`retire G: ${name}`, hits.length === 0, hits.join(', '));
}
// 面 1（改指面在场钉）：项目卡标题入口与 delegate 行 S10 入口改指 delegate-chain。
check('retire G: projectTab card title routes to delegate-chain',
  /import\(['"]\.\/delegateChainView\.js['"]\)/.test(codeOnly(retireFiles['js/projectTab.js'])) &&
  /openDelegateChainStructure/.test(codeOnly(retireFiles['js/projectTab.js'])));
// 换源即切翻转（#56 残项 · 晨裁②「并行一窗不保留」）：原「节点行/链徽标入口 ≥3 处」
// 断言随节点区块退役——taskList 仅存 delegate 行 S10 入口（openDelegateChainTask）；
// S11 结构入口 = 项目卡标题点击 ＋ S10 视图内结构钮（不在 taskList）。
check('retire G: taskList keeps only the delegate-row S10 entry; node-row/badge structure entries retired',
  (codeOnly(retireFiles['js/taskList.js']).match(/import\(['"]\.\/delegateChainView\.js['"]\)/g) || []).length === 1 &&
  /openDelegateChainTask/.test(codeOnly(retireFiles['js/taskList.js'])) &&
  !/openDelegateChainStructure/.test(codeOnly(retireFiles['js/taskList.js'])));
// 拆出件单源确认（零重复拆出、零复制双权威；上游 a60af705d 申报＋delegate-chain-stub T6 静态钉）。
check('retire G: fmNodeCard single source intact (flowMapTab import edge present in file kept on disk)',
  (read('js/flowMapTab.js') ?? '').includes("from './fmNodeCard.js'") &&
  retireFiles['js/fmNodeCard.js'].includes("from './nodeData.js'"));

// 面 2：flowMapArchive 消费面——live 消费面只剩 fmNodeCard 的 isTerminalStatus 纯函数
// 单源（T6 钉）；归档面板 face 随 flowMapTab 断链休眠（文件留盘，无豁免必要＝在图）。
check('retire G: fmNodeCard keeps flowMapArchive isTerminalStatus single source',
  retireFiles['js/fmNodeCard.js'].includes("from './flowMapArchive.js'"));

// 面 4：状态色 token 保留＋per-project 布局面摘除。
check('retire G: flowCss solar status tokens kept',
  flowCss.includes('@keyframes solar-spin') && flowCss.includes('.solar-node-status') &&
  flowCss.includes('.solar-node.blocked .solar-ring'));
check('retire G: flowCss per-project layout face removed',
  (() => {
    const cssCode = codeOnly(flowCss); // 注释历史提及豁免（B 段同法）
    return !cssCode.includes('.solar-card {') && !cssCode.includes('.solar-scroll') &&
      !cssCode.includes('.solar-canvas') && !cssCode.includes('.solar-edges') &&
      !cssCode.includes('@keyframes flow-dash');
  })());

// ── H. 换源即切承接位（#56 残项 · 晨裁②「并行一窗不保留」＋入口键退役）───────
// 原裁出处：20261006_201326 呈审稿 §2.2 REST/WS 行内嵌候裁项（并行一窗 vs 换源即切）
// ＋§3.3 project.openFlowMap 退役面；晨裁②（2026-10-07）：换源即切（并行一窗不保留）
// ＋入口键退役照荐落。engine setSource 落库面 = 88368a259（delegateChainProvider）。
const switchFiles = {
  'js/taskList.js': read('js/taskList.js') ?? '',
  'js/projectTab.js': read('js/projectTab.js') ?? '',
  'css/taskList.css': read('css/taskList.css') ?? '',
  'js/delegateChainProvider.js': read('js/delegateChainProvider.js') ?? '',
};
const taskListCode = codeOnly(switchFiles['js/taskList.js']);
const projectTabCode = codeOnly(switchFiles['js/projectTab.js']);

// 节点区块数据腿零残留（快照对账/WS 订阅/链控缓存——代码符号形态，注释豁免）。
check('switchcut H: taskList node snapshot/WS-cache legs absent',
  !/refreshNodeSnapshot|applyNodeWsEvent|nodeCache|chainCache|chainPendingAction|collectNodes\b/.test(taskListCode));
// 面板行脸 = 平铺 flowmap 节点行（delegate 数据源）：树时代构建器零残留，
// 行族构建器（链徽标/分组节/平铺收集）在场。
check('flowmap-rows H: taskList tree-era builders absent, flat-row builders present',
  !/buildDelegateDetail|buildTreeChevron|delegateTreeOpenable|expandedDelegates|expandedFlowTrees|appendInlineBadges/.test(taskListCode) &&
  /buildChainBadge|buildNodeSection|collectPanelItems/.test(taskListCode));
// 面板零 WS 知识：帧只当 provider 刷新触发器；面板重渲走镜像事件（换源即切接线）。
check('switchcut H: taskList has no direct WS subscription (provider-mirror driven)',
  !/from\s+['"]\.\/ws\.js['"]/.test(taskListCode) && !/onMessage\(/.test(taskListCode));
// nodeData 消费面收敛到 NODE_STATUS_CLS（fetchFlowMap/controlChain/fetchProjects 随
// 节点区块退役；projectTab 摘要腿保留边不动＝#1229 面）。
check('switchcut H: taskList nodeData import shrunk to NODE_STATUS_CLS',
  /import \{ NODE_STATUS_CLS \} from '\.\/nodeData\.js';/.test(taskListCode) &&
  !/fetchFlowMap|controlChain|fetchProjects/.test(taskListCode));
// 入口键 project.openFlowMap 退役照荐落：零 t() 消费（键值保留在册＝零文案 diff）。
check('switchcut H: openFlowMap entry key retired (zero t() consumption)',
  !/project\.openFlowMap/.test(taskListCode) && !/project\.openFlowMap/.test(projectTabCode));
// 统计口径：task.statsProgress 挂可见行数（根行＋成员行——flowmap 行脸面）。
check('flowmap-rows H: stats = visible row count',
  /task\.statsProgress', \{ progress: items\.length \}/.test(taskListCode));
// 换源即切接线：provider 镜像刷新完成广播 ↔ 面板监听重渲（零载荷、零帧解读）。
check('switchcut H: provider mirror event wired (dispatch + panel listener)',
  switchFiles['js/delegateChainProvider.js'].includes("new CustomEvent('delegate-chain-mirror-updated')") &&
  taskListCode.includes("'delegate-chain-mirror-updated'"));
// CSS 面：flowmap 行脸在场（链徽标胶囊族 + 项目分组族）；树时代面（chevron/
// 展开详情/行内徽标/子行族/flow 子行）摘除；节点行基样与 delegate 出口面保留。
check('flowmap-rows H: taskList.css flat-node face present, tree-era face removed',
  (() => {
    const css = switchFiles['css/taskList.css']
      .replace(/^\s*\/\*[\s\S]*?\*\/\s*$/gm, '')  // 整块注释豁免（退役注记）
      .replace(/^\s*\/\/.*$/gm, '');
    return !/\.task-chain[-. {]/.test(css) &&
      css.includes('.task-node-chain') && css.includes('.task-node-group') &&
      !css.includes('.task-tree-chev') && !css.includes('.task-delegate-detail') &&
      !css.includes('.task-kids-badge') && !css.includes('.task-flow-badge') &&
      !css.includes('.task-kids-list') && !css.includes('.bgagent-child-row') &&
      !css.includes('.task-flow-row') && !css.includes('.task-flow-nodes') &&
      css.includes('.task-node {') && css.includes('.task-delegate-btn') &&
      css.includes('--task-chain-btn-w');
  })());
// 退役脸 spec/取证工具随批除名（原节点区块验收面）。
check('switchcut H: retired node-block spec/shot tooling absent',
  !existsSync(join(HERE, '..', 'tests', 'tasklist-nodes.spec.mjs')) &&
  !existsSync(join(HERE, '..', 'tests', 'tasklist-chainctl-optimistic.spec.mjs')) &&
  !existsSync(join(HERE, '..', 'scripts', 'shot-tasklist-nodes.cjs')));

// ── I. 测试脚本自身语法 ────────────────────────────────────────
console.log(failed === 0 ? '\nALL STATIC CHECKS PASS' : `\n${failed} CHECK(S) FAILED`);
process.exit(failed === 0 ? 0 : 1);
