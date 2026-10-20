// mobile/bgStatusFace.js — P6「运行状态」合并面板（mobile-secondary-screens
// 实施批；样张 v3.5 P6 + 开放点①「与桌面两下拉的归并（移动单面板）、子智能体
// 并入」按设计批推荐采纳）。
//
// 归并形态：
//   · 载体 = #bg-dropdown（后台任务面板，P6 精确 308×560）；#bgagent-dropdown
//     （子智能体下拉）手机层整面 display:none（CSS），其**列表节点** re-parent
//     进载体（navController adoptPane 同款：appendChild = move，监听随节点走，
//     renderBgAgentDropdown 的面内重渲照常命中）。载体桌面头（「后台任务」）即
//     第一分组题；收编列表前一枚「智能体」分组签（.mh-rd-grp 站内小写字号语言
//     同 P3）—— 不另加「后台任务」签，避免与桌面头逐字重复。
//   · bgagent 键（capture 拦截，toggle 语义保真在载体上）：点按 = 载体开/收，
//     桌面对幽灵面的 toggle 不落地（stopPropagation —— 幽灵面恒 hidden，无
//     状态镜像战）；开腿自播二级液态 morph（floatController 同款 160ms 时序，
//     origin = 该键圆钮）。
//   · bg 键路径零改动：桌面自身 toggle 载体（bg-indicator 监听原样）。
//   · 行结构零重造：状态点/状态词六档（含卡死阈值）/origin chip/本地·远程
//     kind chip/行数/时长/取消钮/输出卡 —— 全部桌面既有（renderBgDropdown /
//     renderBgAgentDropdown 原样）。
//   · 资源占用条（开放点③【新增提案·后端无字段】）：不实施不伪造，记开放点。
//
// 桌面零改动边界：本文件只搬移列表节点（拆层原位归还）+ 追加分组签 + 拦截
// bgagent 键点击；两下拉的渲染/事件路径零触碰。

import { t } from '../i18n.js';
import { morphIn } from './morphPanel.js';
import { logEvent } from './navController.js';

/** @type {HTMLElement|null} 被收编的子智能体列表。 */
let adoptedList = null;
/** @type {{parent: Node, next: Node|null}|null} 归还锚点。 */
let adoptAnchor = null;
/** @type {HTMLElement[]} 分组签（拆层移除）。 */
const grpLabels = [];
/** @type {(() => void)|null} 键拦截退订。 */
let unbindKey = null;
/** @type {Array<() => void>} */
let unsubs = [];
/** @type {number|null} 开腿 morph 时序（拆层清理）。 */
let morphTimer = null;

function carrier() { return document.getElementById('bg-dropdown'); }
function ghost() { return document.getElementById('bgagent-dropdown'); }

function grpLabel(key) {
  const el = document.createElement('div');
  el.className = 'mh-rd-grp mh-bg-grp';
  el.dataset.i18nGrp = key;
  el.textContent = t(key);
  grpLabels.push(el);
  return el;
}

function syncGrpLabels() {
  for (const el of grpLabels) el.textContent = t(/** @type {string} */ (el.dataset.i18nGrp));
}

/** bgagent 键 capture 拦截：载体 toggle + 幽灵面押回 hidden（桌面 toggle 不落地）。 */
function onBgagentKeyCapture(e) {
  if (!(e.target instanceof Element)) return;
  const key = e.target.closest('#bgagent-indicator');
  if (!key) return;
  e.stopPropagation();
  e.preventDefault();
  const c = carrier();
  if (!c) return;
  const willOpen = c.classList.contains('hidden');
  if (willOpen) {
    c.classList.remove('hidden');
    logEvent('p6-merged-open', { via: 'bgagent-key' });
    // 二级液态（floatController 同款时序：先隐 → 160ms → morphIn 自键圆钮）。
    if (morphTimer) clearTimeout(morphTimer);
    c.style.opacity = '0';
    morphTimer = setTimeout(() => {
      c.style.opacity = '';
      morphIn(c, /** @type {HTMLElement} */ (key));
    }, 160);
  } else {
    if (morphTimer) { clearTimeout(morphTimer); morphTimer = null; }
    c.classList.add('hidden');
    logEvent('p6-merged-close', { via: 'bgagent-key' });
  }
  const g = ghost();
  if (g && !g.classList.contains('hidden')) g.classList.add('hidden');
}

export function activateBgStatusFace() {
  const c = carrier();
  const g = ghost();
  if (!c || !g) return () => {};

  // 「智能体」分组签 + 列表收编（幂等；拆层原位归还）。第一分组题 = 载体
  // 桌面头（bg-dropdown-header「后台任务」）零触碰，不另设签。
  const cList = c.querySelector('.bg-dropdown-list');
  const gList = g.querySelector('.bg-dropdown-list');
  if (cList && gList && gList.parentElement !== c) {
    adoptAnchor = { parent: gList.parentElement, next: gList.nextSibling };
    c.appendChild(grpLabel('bg.sectionAgents'));
    c.appendChild(gList);
    adoptedList = gList;
  }

  document.addEventListener('click', onBgagentKeyCapture, true);
  unbindKey = () => document.removeEventListener('click', onBgagentKeyCapture, true);

  const onLocale = () => syncGrpLabels();
  window.addEventListener('locale-changed', onLocale);
  unsubs.push(() => window.removeEventListener('locale-changed', onLocale));

  return function teardownBgStatusFace() {
    for (const fn of unsubs.splice(0)) { try { fn(); } catch { /* ignore */ } }
    if (unbindKey) { unbindKey(); unbindKey = null; }
    if (morphTimer) { clearTimeout(morphTimer); morphTimer = null; }
    if (adoptedList && adoptAnchor) {
      try {
        if (adoptAnchor.parent && adoptAnchor.parent.isConnected) {
          adoptAnchor.parent.insertBefore(adoptedList, adoptAnchor.next);
        }
      } catch { /* 树已变：放弃归还，避免二次破坏 */ }
    }
    adoptedList = null;
    adoptAnchor = null;
    for (const el of grpLabels.splice(0)) el.remove();
  };
}
