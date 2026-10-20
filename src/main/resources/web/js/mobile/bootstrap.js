// mobile/bootstrap.js — 手机交互层注册钩子（mobile-ui-refactor 批，作者五点规格）。
//
// 架构定位（任务书硬要求）：移动端＝**独立交互层**，不是响应式补丁。
//   · 组件与桌面共享（画板/设置/用量/项目/图谱/插件全部复用桌面导出 API 与 DOM），
//     但交互面分离 —— 本目录自组 bootstrap + 交互控制器
//     （nav / layout / gestures / floats）。
//   · 桌面代码路径零行为改动：桌面视口本模块只做一次 media-query 判定即返回，
//     不 import 任何控制器、不建任何 DOM；唯一的桌面痕迹是 index.html 里的
//     一行 <script> 注册钩子（与一行 <link>）。
//   · 激活判据与既有 css/mobile.css 折叠点同源：matchMedia('(max-width: 768px)')。
//     手机上以 body.mobile-layer 类为总开关（css/mobile/mobileLayer.css 全部规则
//     挂在该类下，桌面窗口永远不会命中）。
//   · 视口跨越（旋转/拉窗）双向处理：进入手机态激活，退回桌面态拆除并原样
//     归还被移动的桌面 DOM 节点（floatController 记锚点）。
//
// 规格⑤侧 → layoutController（活动栏「双态」：展开=贴右缘无外浮把手，
//              收起=半嵌低调图标即展开钮；走廊 --mobile-bar-gutter 随态
//              58px/30px 适配，见 mobileLayer.css）
// 规格②③ → navController（聊天为 root 的页面栈导航 + 面板真页面 + 文档卡片画板）
// 规格③   → canvasGestures（文档卡片手势：横滑切卡 / 上滑收层；r1 页签形态已废除）
// 规格⑤   → floatController（会话工具收编 header 右簇 + 居中浮层面板；r4 起
//             底部只留输入卡 —— 搜索/历史/图标条上移归位进顶部一体化顶栏）
// 规格②   → 纯 CSS（mobileLayer.css：手机端隐藏手机绑定/文件浏览器入口；
//             项目卡「在文件浏览器中打开」按钮随层隐藏）。
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
    // 动态 import：桌面视口永不执行这些行（激活判据在前）。
    // v3 激活序：header 先行（双液态列壳就位，layout/floats 才有列可填）；
    // 拆除以**数组序**执行 ⇒ teardowns 倒排激活序：floats 最先归还搬移节点
    //（锚点归还需要列壳仍在场），nav 最后拆（pane 归还在手势解绑之后）。
    // mobile-secondary-screens 批：面板手机面控制器（P2 起逐屏）随 floats
    // 之后激活（只追加自有节点/写缝，不搬移列键），拆除紧随 tFloats。
    const [header, layout, gestures, floats, nav, searchFace, daemonFace] = await Promise.all([
      import('./headerController.js'),
      import('./layoutController.js'),
      import('./canvasGestures.js'),
      import('./floatController.js'),
      import('./navController.js'),
      import('./searchFace.js'),
      import('./daemonFace.js'),
    ]);
    if (!active) return; // 激活期间已跨越回桌面 —— 直接拆除
    const navTeardown = nav.activateNav();
    const tHeader = header.activateHeader();
    const tLayout = layout.activateLayout();
    const tGestures = gestures.activateGestures();
    const tFloats = floats.activateFloats();
    const tSearchFace = searchFace.activateSearchFace();
    const tDaemonFace = daemonFace.activateDaemonFace();
    teardowns = [tFloats, tSearchFace, tDaemonFace, tGestures, tLayout, tHeader, navTeardown];
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
  version: 'mobile-ui-refactor v3 (floating-header + liquid-dd + secondary-morph + tpanel + secondary-screens)',
};
