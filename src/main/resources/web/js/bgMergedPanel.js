// bgMergedPanel.js — 后台合并面板（后台任务 + 子智能体 → 一个后台面板载体）。
//
// 作者令（2026-10-23 ⑥）：后台任务与后台子智能体原是两个独立下拉 —— 归并为
// **一个后台面板**（分节组织），桌面端同步受益。形态（沿用 mobile P6 合并批
// 已验收的分节语言）：
//   · 载体 = #bg-dropdown；两下拉的渲染/取数/事件路径零触碰（main.js
//     renderBgDropdown / bgAgents.js renderBgAgentDropdown 原样）；
//   · 幽灵面 #bgagent-dropdown 永不可见（chat.css display:none），节点原地保留
//     作状态载体（旧脚本对其 .hidden 读写照常无害）。
//
// 面板分区批（2026-10-29 ③ · 作者报障「后台任务会和子智能体混在一起」）：两节
// 从平铺改为 **两个独立 section**（.bg-section）——
//   · .bg-section-tasks =「后台任务 · N」头 + 任务列表；
//   · .bg-section-agents =「智能体 · N」分组签（.mh-bg-grp）+ 子智能体列表；
//   · 各自独立滚动（chat.css：载体 flex column + 两列表各自 overflow-y，内容
//     少时按内容收缩）；
//   · 分区签带各自实时行数（MutationObserver 空节收签批语义保留：子智能体 0 行
//     → 签与节整体隐藏——无内容不摆空签）。
//
// 桌面/手机共用：本模块在 main.js 组装根调用一次（视口无关）——手机层旧
// bgStatusFace.js 的收编腿由此接管（该控制器只留键拦截/morph 呈现职责）。

import { t } from './i18n.js';

/** @type {MutationObserver|null} 空节收签观察者（拆层无——面板结构恒久）。 */
let grpMo = null;
/** @type {HTMLElement|null} 分组签（locale 切换重写文案）。 */
let grpLabel = null;
/** @type {HTMLElement|null} 子智能体列表（计数源）。 */
let grpList = null;

/** 把子智能体列表收编进后台面板载体（幂等；重复调用/节点缺失静默跳过）。 */
export function initBgMergedPanel() {
  const carrier = document.getElementById('bg-dropdown');
  const ghost = document.getElementById('bgagent-dropdown');
  if (!carrier || !ghost) return;
  const cList = carrier.querySelector('.bg-dropdown-list');
  const gList = ghost.querySelector('.bg-dropdown-list');
  if (!cList || !gList || gList.closest('#bg-dropdown')) return; // 已收编 = 幂等跳过

  // 任务节：包裹既有头部（「后台任务 · N」，renderBgDropdown 权威写点不动）+ 任务列表。
  const tSec = document.createElement('div');
  tSec.className = 'bg-section bg-section-tasks';
  const header = carrier.querySelector('.bg-dropdown-header');
  if (header) carrier.insertBefore(tSec, header); else carrier.appendChild(tSec);
  if (header) tSec.appendChild(header);
  tSec.appendChild(cList);

  // 智能体节：分组签（.mh-bg-grp 同族小写字号语言，附实时计数）+ 收编列表。
  const aSec = document.createElement('div');
  aSec.className = 'bg-section bg-section-agents';
  grpLabel = document.createElement('div');
  // panel-ia 批（2026-11-03）：分组签收编 .ds-section-label（与 .bg-dropdown-header
  // 同语言——字号/字距/大写化/色走 design-system 组件类单源）。
  grpLabel.className = 'mh-bg-grp ds-section-label';
  carrier.appendChild(aSec);
  aSec.appendChild(grpLabel);
  aSec.appendChild(gList);
  grpList = /** @type {HTMLElement} */ (gList);

  // 空节收签：列表 0 子 → 签 hidden（渲染面写 innerHTML/增删行都会触发）；
  // 计数随行数现算（签文案 = t('bg.sectionAgents') + ' · N'）。
  // 野节点归置批（2026-11-05 腿③）：签数改数**行**（.bg-task-row），不数列表子元素
  // ——野节点分组的组头是标签不是行（data-bg-orphan-group），数子元素会把标签算成
  // 一行，签与行集当场打架。口径与 data-bg-row-total / 头部计数同源：一行一个数。
  const sync = () => {
    if (!grpLabel || !grpList) return;
    const n = grpList.querySelectorAll('.bg-task-row').length;
    grpLabel.hidden = n === 0;
    grpLabel.textContent = t('bg.sectionAgents') + (n > 0 ? ' · ' + n : '');
  };
  grpMo = new MutationObserver(sync);
  grpMo.observe(gList, { childList: true });
  sync();

  window.addEventListener('locale-changed', sync);

  // 全名可达性（三行结构批 2026-11-05 · 作者令「name 截断但必须能完整获取，不许
  // 出现「搜…」这种到死都看不到全名的形态」）：L1 主名恒单行截断，全名有两条路 ——
  //   ① title（桌面 hover，既有 nameTooltip 读取点，零改动）；
  //   ② 点击主名展开（**手机无 hover**，这条是触屏端的唯一路）：在 单行截断 ↔
  //      多行完整 之间翻转（.is-expanded，chat.css）。
  // 只在**真的被截断**时接管点击（scrollWidth > clientWidth）：没截断的名字点击
  // 照旧冒泡给行体（开详情弹窗），零行为漂移。事件挂载体一次（两节共用），
  // 行重渲染不丢（委派），幂等（重复 init 不重复挂）。
  if (!carrier.dataset.bgNameExpandBound) {
    carrier.dataset.bgNameExpandBound = '1';
    // 捕获相（第三参 true）是必需的：行自身挂了 click（开详情弹窗），冒泡相在载体
    // 上收到的点击已经太晚（弹窗已开、面板已隐）；捕获相先于行处理器 ⇒
    // stopPropagation 才真正把「展开全名」与「开详情」分开。
    carrier.addEventListener('click', (e) => {
      const target = /** @type {HTMLElement|null} */ (e.target);
      const nameEl = target && target.closest ? target.closest('.bg-task-name') : null;
      if (!nameEl) return;
      const truncated = nameEl.scrollWidth > nameEl.clientWidth + 1;
      if (!truncated && !nameEl.classList.contains('is-expanded')) return;
      e.stopPropagation(); // 展开/收起全名 ≠ 开详情（行体点击语义不变）
      nameEl.classList.toggle('is-expanded');
      nameEl.setAttribute('aria-expanded', nameEl.classList.contains('is-expanded') ? 'true' : 'false');
    }, true);
  }
}
