// mobile/bgStatusFace.js — P6「运行状态」合并面板 · 手机呈现面（mobile-secondary-screens
// 实施批；面板合并批 2026-10-23 ⑥ 收敛职责）。
//
// 职责收敛（后台合并批）：两下拉的**结构归并**（列表收编 + 分组签）上移为
// 桌面共享单点 bgMergedPanel.js（main.js 组装根调用，视口无关）——本控制器
// 不再搬移任何节点（旧收编/归还腿退役）。手机层只保留两件呈现职责：
//   · bgagent 键 capture 拦截：点按 = 合并载体 #bg-dropdown 开/收（桌面对
//     幽灵面的 toggle 不落地——幽灵面恒 display:none，无状态镜像战）；
//     开腿自播二级液态 morph（floatController 同款 160ms 时序，origin = 该键
//     圆钮）。bg 键路径零改动：桌面自身 toggle 载体（bg-indicator 监听原样）。
//   · 幽灵面押回 hidden（CSS display:none 为主，此处兜底防任何解隐路径落地）。
//
// 行结构零重造：状态点/状态词六档（含卡死阈值）/origin chip/本地·远程
// kind chip/行数/时长/取消钮/输出卡 —— 全部桌面既有（renderBgDropdown /
// renderBgAgentDropdown 原样），行节点现居合并载体内。

import { morphIn } from './morphPanel.js';
import { logEvent } from './navController.js';

/** @type {(() => void)|null} 键拦截退订。 */
let unbindKey = null;
/** @type {number|null} 开腿 morph 时序（拆层清理）。 */
let morphTimer = null;

function carrier() { return document.getElementById('bg-dropdown'); }
function ghost() { return document.getElementById('bgagent-dropdown'); }

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
  if (!c) return () => {};

  document.addEventListener('click', onBgagentKeyCapture, true);
  unbindKey = () => document.removeEventListener('click', onBgagentKeyCapture, true);

  return function teardownBgStatusFace() {
    if (unbindKey) { unbindKey(); unbindKey = null; }
    if (morphTimer) { clearTimeout(morphTimer); morphTimer = null; }
  };
}
