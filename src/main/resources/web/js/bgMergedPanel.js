// bgMergedPanel.js — 后台合并面板（后台任务 + 子智能体 → 一个后台面板）。
//
// 作者令（2026-10-23 ⑥）：后台任务与后台子智能体原是两个独立下拉 —— 归并为
// **一个后台面板**（分节组织），桌面端同步受益。形态（沿用 mobile P6 合并批
// 已验收的分节语言）：
//   · 载体 = #bg-dropdown（「后台任务」头部 + 任务列表原样）；
//   · 「智能体」分组签（.mh-bg-grp，bg-dropdown-header 同族小写字号语言）+
//     #bgagent-dropdown 的列表节点 re-parent 进载体（appendChild = move，监听
//     随节点走；renderBgAgentDropdown 的面内重渲照常命中——写的是同一节点）；
//   · 幽灵面 #bgagent-dropdown 永不可见（chat.css display:none），节点原地保留
//     作状态载体（旧脚本对其 .hidden 读写照常无害）；
//   · 空节收签：子智能体 0 行 → 分组签隐藏（与桌面头部「后台任务 · N」计数
//     语言同源 —— 无内容不摆空签）。
//
// 桌面/手机共用：本模块在 main.js 组装根调用一次（视口无关）——手机层旧
// bgStatusFace.js 的收编腿由此接管（该控制器只留键拦截/morph 呈现职责）。
// 两下拉的渲染/取数/事件路径零触碰（main.js renderBgDropdown /
// bgAgents.js renderBgAgentDropdown 原样）。

import { t } from './i18n.js';

/** @type {MutationObserver|null} 空节收签观察者（拆层无——面板结构恒久）。 */
let grpMo = null;
/** @type {HTMLElement|null} 分组签（locale 切换重写文案）。 */
let grpLabel = null;

/** 把子智能体列表收编进后台面板载体（幂等；重复调用/节点缺失静默跳过）。 */
export function initBgMergedPanel() {
  const carrier = document.getElementById('bg-dropdown');
  const ghost = document.getElementById('bgagent-dropdown');
  if (!carrier || !ghost) return;
  const cList = carrier.querySelector('.bg-dropdown-list');
  const gList = /** @type {HTMLElement|null} */ (ghost.querySelector('.bg-dropdown-list'));
  if (!cList || !gList || gList.parentElement === carrier) return;

  grpLabel = document.createElement('div');
  grpLabel.className = 'mh-bg-grp';
  grpLabel.textContent = t('bg.sectionAgents');
  carrier.appendChild(grpLabel);
  carrier.appendChild(gList);

  // 空节收签：列表 0 子 → 签 hidden（渲染面写 innerHTML/增删行都会触发）。
  const sync = () => { if (grpLabel) grpLabel.hidden = gList.children.length === 0; };
  grpMo = new MutationObserver(sync);
  grpMo.observe(gList, { childList: true });
  sync();

  window.addEventListener('locale-changed', () => {
    if (grpLabel) grpLabel.textContent = t('bg.sectionAgents');
  });
}
