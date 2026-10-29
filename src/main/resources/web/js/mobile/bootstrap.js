// mobile/bootstrap.js — 手机交互层注册钩子（mobile-ui-refactor 批，作者五点规格；
// dock 批 2026-10-29 换代）。
//
// 架构定位（任务书硬要求）：移动端＝**独立交互层**，不是响应式补丁。
//   · 组件与桌面共享（画板/设置/用量/项目/图谱/插件/社交面全部复用桌面导出
//     API 与 DOM），但交互面分离 —— 本目录自组 bootstrap + 交互控制器。
//   · 桌面代码路径零行为改动：桌面视口本模块只做一次 media-query 判定即返回，
//     不 import 任何控制器、不建任何 DOM；唯一的桌面痕迹是 index.html 里的
//     一行 <script> 注册钩子（与一行 <link>）。
//   · 激活判据与既有 css/mobile.css 折叠点同源：matchMedia('(max-width: 768px)')。
//     手机上以 body.mobile-layer 类为总开关（css/mobile/mobileLayer.css 全部规则
//     挂在该类下，桌面窗口永远不会命中）。
//   · 视口跨越（旋转/拉窗）双向处理：进入手机态激活，退回桌面态拆除并原样
//     归还被移动的桌面 DOM 节点（navController 记锚点）。
//
// dock 批激活谱系（作者令 2026-10-29 五栏底 dock + 各节目独立界面零重叠）：
//   · nav = 五栏底 dock + 页面栈（dockTo/pushPage/popPage）+ canvas 独立页
//     + 全页面收编（overlay 面/面板 pane/会话窗）；
//   · socialFaces = 消息/联系人两面内容臂 + 消息页通知段（中键改版批 23:36：
//     好友会话列表迁入消息页，原「对话」面退役；桌面面收编 + 封存态）；
//   · chatChrome = 聊天根顶栏双键（任务/搜索；液态下拉列退役后的常驻入口）；
//   · searchFace / daemonFace / memoryFace = 桌面面的手机呈现附件（脚注/角色行/
//     卡读态——面收编进页面后照常生效）；
//   · taskPanel = 任务页面板（数据/卡渲染同源，呈现位=页面收编）。
//   · 退役（随批摘除激活）：headerController（双液态下拉列）、layoutController
//     （右列页面键）、floatController（左列键+二级 morph）、morphPanel、
//     canvasGestures（bottom sheet 手势）、bgStatusFace（键拦截 morph）。
//
// QA 探针：window.__nfMobileLayer = { active, version } —— 采集脚本用它确认
// 交互层已激活（结构读数），不依赖任何视觉推断。导航探针：window.__nfMobileNav。
// 事件流水：window.__nfMobileLayerEvents（navController 挂载）。

const MOBILE_MQ = '(max-width: 768px)';

/** @type {boolean} */
let active = false;
/** @type {Array<() => void>} */
let teardowns = [];

async function activate() {
  if (active) return;
  active = true;
  document.body.classList.add('mobile-layer');
  // r4 ②：视口链 html 层压点 —— html 无法以 body 类作条件选择，故由本钩子
  // 在与 body.mobile-layer 完全相同的激活/拆除路径上同步加/摘 html 层类
  // （桌面视口两路径都不跑 ⇒ html.mobile-html-layer 桌面永不出现）。
  document.documentElement.classList.add('mobile-html-layer');
  try {
    // dock 批激活序：nav 先行（dock/页面壳就位，chrome/faces 才有栈可推）；
    // 拆除以**数组序**执行 ⇒ teardowns 倒排激活序：faces/chrome 先归还搬移
    // 节点，nav 最后拆（pane/overlay 面归还锚点在页面壳仍在场时执行）。
    const [nav, socialFaces, chatChrome, searchFace, daemonFace, memoryFace] = await Promise.all([
      import('./navController.js'),
      import('./socialFaces.js'),
      import('./chatChrome.js'),
      import('./searchFace.js'),
      import('./daemonFace.js'),
      import('./memoryFace.js'),
    ]);
    if (!active) return; // 激活期间已跨越回桌面 —— 直接拆除
    const navTeardown = nav.activateNav();
    const tFaces = socialFaces.activateSocialFaces();
    const tChrome = chatChrome.activateChatChrome();
    const tSearchFace = searchFace.activateSearchFace();
    const tDaemonFace = daemonFace.activateDaemonFace();
    const tMemoryFace = memoryFace.activateMemoryFace();
    teardowns = [tMemoryFace, tDaemonFace, tSearchFace, tChrome, tFaces, navTeardown];
  } catch (err) {
    // 控制器加载失败：回到桌面形态（宁可退回可用桌面，不可半挂层）。
    console.error('[mobile-layer] activate failed', err);
    deactivate();
  }
}

function deactivate() {
  for (const fn of teardowns.splice(0)) {
    try { fn(); } catch { /* 拆除单点失败不得阻断其余归还 */ }
  }
  document.body.classList.remove('mobile-layer');
  document.documentElement.classList.remove('mobile-html-layer');
  active = false;
}

const mq = window.matchMedia(MOBILE_MQ);
if (typeof mq.addEventListener === 'function') {
  mq.addEventListener('change', (e) => (e.matches ? activate() : deactivate()));
} else if (typeof mq.addListener === 'function') {
  mq.addListener((e) => (e.matches ? activate() : deactivate()));
}
if (mq.matches) activate();

// QA / 诊断探针（只读）。
(/** @type {any} */ (window)).__nfMobileLayer = {
  get active() { return active; },
  version: 'mobile dock batch (five-tab dock + fullscreen independent pages + canvas page + social faces)',
};
