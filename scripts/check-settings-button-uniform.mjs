// 设置页「按钮形态统一」静态闸（2026-11-18 设置页结构治理批 · 第三件·机检化）。
//
// 作者逐字两条：「然后就是退出按钮 我们要用我们统一的按钮风格 ……这个退出按钮太丑了」
// 「包括后台智能体的恢复按钮也是」。判据 = 三枚钮（设备行退出 / 后台智能体恢复与停止）
// 必须**登记进设置页动作族**（.cfg-btn 配色 + .set-btn 几何令牌），且**任何按钮规则都
// 不得自带字面量几何**（height/min-width/padding/border-radius 的 px 或 50% 自写）——
// 那正是修前的形态：退出钮走 `.glass-control` 毯式描边档（零圆角 + 1px 边），
// 后台钮在 flowAgentPopup.js 注入串里写死 28px×28px/50%、在 mobileLayer.css 里另写
// 一套 `--ds-hit-row` 方钮 ⇒ **同一逻辑钮两处像素真相**（桌面圆 28 / 手机方 44）。
//
// 三族判据：
//   B1 登记表：三枚钮的标记必须同时带 cfg-btn + set-btn；不得再挂 glass-control。
//   B2 禁内联几何：按钮规则只认令牌（--set-btn-h / --set-btn-pad-x / --ds-radius-pill /
//      --ds-fs-btn / --set-icon-size），不得出现字面量尺寸。
//   B3 排布一致：.set-btn 命中盒（高/宽地板）与内距/圆角/字号全部由单源令牌推出；
//      整行钮走 .set-btn-block。
//
// 自证检测力（破坏 → 红 / 还原 → 绿）：
//   · 把 managePanel.js 的 retryBtn.className 去掉 ' set-btn' ⇒ B1 红；
//   · 给 flowAgentPopup.js 的 .fa-mgmt-btn 块加回 `height: 28px` ⇒ B2 红。
import { readFileSync } from 'node:fs';

const J = (p) => readFileSync(p, 'utf8');
const web = 'src/main/resources/web';
const devices = J(`${web}/js/profileDevices.js`);
const manage = J(`${web}/js/managePanel.js`);
const flowAgent = J(`${web}/js/flowAgentPopup.js`);
const sidebar = J(`${web}/js/sidebar.js`);
const rows = J(`${web}/css/settingsRows.css`);
const tokens = J(`${web}/css/stand-tokens.css`);
const mobile = J(`${web}/css/mobileLayer.css`);

/** 取某个选择器后的第一对 {} 的块体（近似；块内无嵌套花括号的规则才可靠）。 */
const blockAfter = (src, needle) => {
  const i = src.indexOf(needle);
  if (i < 0) return null;
  const j = src.indexOf('{', i);
  const k = src.indexOf('}', j);
  return src.slice(j + 1, k);
};
const stripComments = (s) => s.replace(/\/\*[\s\S]*?\*\//g, ' ');
/** 块内是否自带字面量几何（px 或 50%）。 */
const hasLiteralGeom = (b) =>
  /(?:^|[;{\s])(?:height|min-height|min-width|width|padding|padding-top|padding-left|padding-right|padding-bottom|border-radius|font-size)\s*:\s*[^;{}]*(?:\d+px|\d+%)/i.test(b);
const tokenRule = (src, name) => (src.match(new RegExp(`${name}\\s*:\\s*([^;]+);`))?.[1] || '').trim();

const setBtn = stripComments(rows).match(/(?:^|\})\s*\.set-btn\s*\{([^}]*)\}/)?.[1] || '';
const faMgmt = stripComments(flowAgent).match(/\.flow-agent-footer\s+\.fa-mgmt-btn\s*\{([^}]*)\}/)?.[1] || '';
// 后台动作区（手机层）所有含 .fa-mgmt-btn 的规则块。
const mobileFaMgmtRules = [...stripComments(mobile).matchAll(/[^{}]*\.fa-mgmt-btn[^{}]*\{([^}]*)\}/g)].map((m) => m[0]);
const signoutCssRules = [...stripComments(rows + mobile).matchAll(/[^{}]*\.pd-device-signout[^{}]*\{([^}]*)\}/g)].map((m) => m[0]);
// 高级区：文件缓存读数行必须零按钮；三枚动作钮必须同处一个 .set-actions 网格。
const advChunk = sidebar.slice(sidebar.indexOf('data-panel-group="advanced"'));
const advActions = advChunk.slice(advChunk.indexOf('<div class="set-actions">'));
const advActionsBody = advActions.slice(0, advActions.indexOf('</div>'));
const cacheReadout = (sidebar.match(/control:\s*`<span id="nf-cache-usage"[^`]*`/) || [''])[0];

const rules = [
  // ── B1 登记表 ────────────────────────────────────────────────────────
  ['B1 signout registered into action family', /class:\s*'pd-device-signout cfg-btn set-btn'/.test(devices)],
  ['B1 signout dropped glass-control', !/(?:class|className)\s*[:=]\s*'[^']*glass-control/.test(devices)],
  ['B1 bg-agent stop registered into action family', /'fa-mgmt-btn fa-mgmt-stop cfg-btn set-btn'/.test(manage)],
  ['B1 bg-agent retry registered into action family', /'fa-mgmt-btn fa-mgmt-retry cfg-btn set-btn'/.test(manage)],
  ['B1 bg-agent buttons dropped glass-control', !/(?:class|className)\s*[:=]\s*'[^']*glass-control/.test(manage)],
  ['B1 advanced actions registered', /class="cfg-btn set-btn" id="btn-toggle-json"/.test(sidebar) && /class="cfg-btn set-btn" id="btn-rerun-onboarding"/.test(sidebar)],
  ['B1 cache-clear registered as full-width row button', /class="cfg-btn set-btn set-btn-block" id="btn-clear-nf-cache"/.test(sidebar)],

  // ── B2 禁内联几何（按钮规则只认令牌）─────────────────────────────────
  ['B2 fa-mgmt-btn block has no literal geometry', faMgmt.length > 0 && !hasLiteralGeom(faMgmt)],
  ['B2 fa-mgmt-btn block keeps layout only', /inline-flex/.test(faMgmt) && /transition/.test(faMgmt)],
  ['B2 no mobile-layer literal geometry on fa-mgmt buttons', mobileFaMgmtRules.length > 0 && !mobileFaMgmtRules.some((b) => hasLiteralGeom(b))],
  ['B2 bg-agent action row no longer pins --ds-hit-row', !/--ds-hit-row/.test(blockAfter(mobile, '.bgagent-sheet-actions {') || '')],
  ['B2 no literal geometry on the signout button (css + mobile)', signoutCssRules.length > 0 && !signoutCssRules.some((b) => hasLiteralGeom(b))],
  ['B2 no width:50% button geometry in flow agent', !/\.fa-mgmt-btn[^{}]*\{[^}]*width\s*:\s*50%/.test(stripComments(flowAgent))],

  // ── B3 命中盒与排布单源 ──────────────────────────────────────────────
  ['B3 set-btn height from token', /height:\s*var\(--set-btn-h\)/.test(setBtn) && /min-height:\s*var\(--set-btn-h\)/.test(setBtn)],
  ['B3 set-btn width floor from token (min 44x44 hit box)', /min-width:\s*var\(--set-btn-h\)/.test(setBtn)],
  ['B3 set-btn padding from token', /padding:\s*0\s+var\(--set-btn-pad-x\)/.test(setBtn)],
  ['B3 set-btn radius from token', /border-radius:\s*var\(--ds-radius-pill\)/.test(setBtn)],
  ['B3 set-btn font from token', /font-size:\s*var\(--ds-fs-btn\)/.test(setBtn)],
  ['B3 set-btn block has no literal geometry', setBtn.length > 0 && !hasLiteralGeom(setBtn)],
  ['B3 set-btn-block spans the action grid row', /\.set-actions\s+\.set-btn\.set-btn-block\s*\{\s*flex:\s*1\s+1\s+100%/.test(stripComments(rows))],
  ['B3 advanced cache row is readout-only (zero buttons)', cacheReadout.length > 0 && !/<button/i.test(cacheReadout)],
  ['B3 three advanced actions share one action grid', ['btn-toggle-json', 'btn-clear-nf-cache', 'btn-rerun-onboarding'].every((id) => advActionsBody.includes(id))],
  ['B3 tokens single-sourced', tokenRule(tokens, '--set-btn-h') === 'var(--mtap, 44px)' && tokenRule(tokens, '--set-btn-pad-x') === 'var(--ds-space-3)'],
];

for (const [name, ok] of rules) console.log(`${ok ? 'PASS' : 'FAIL'} ${name}`);
const failures = rules.filter(([, ok]) => !ok).length;
console.log(`settings-button-uniform: ${rules.length} checks, ${failures} violations; scope: signout + bg-agent stop/retry + settings action family`);
if (failures) process.exitCode = 1;
