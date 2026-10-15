// mobile/bootstrap.js — 手机交互层注册钩子（mobile-ui-refactor 批，作者五点规格）。
//
// 架构定位（任务书硬要求）：移动端＝**独立交互层**，不是响应式补丁。
//   · 组件与桌面共享（画板/设置/用量/项目/图谱/插件全部复用桌面导出 API 与 DOM），
//     但交互面分离 —— 本目录自组 bootstrap + 交互控制器（layout / gestures / floats）。
//   · 桌面代码路径零行为改动：桌面视口本模块只做一次 media-query 判定即返回，
//     不 import 任何控制器、不建任何 DOM；唯一的桌面痕迹是 index.html 里的
//     一行 <script> 注册钩子（与一行 <link>）。
//   · 激活判据与既有 css/mobile.css 折叠点同源：matchMedia('(max-width: 768px)')。
//     手机上以 body.mobile-layer 类为总开关（css/mobile/mobileLayer.css 全部规则
//     挂在该类下，桌面窗口永远不会命中）。
//   · 视口跨越（旋转/拉窗）双向处理：进入手机态激活，退回桌面态拆除并原样
//     归还被移动的桌面 DOM 节点（floatController 记锚点）。
//
// 规格①③ → layoutController（右侧竖排活动栏 + 左侧内容面板）
// 规格④   → canvasGestures（左右滑切页签 / 上滑关闭 / 长按卡片视图）
// 规格⑤   → floatController（定时任务/心跳/记忆居中悬浮 + 后台任务/智能体居中浮层）
// 规格②   → 纯 CSS（mobileLayer.css：手机端隐藏手机绑定/文件浏览器入口；
//             项目卡「在文件浏览器中打开」按钮随层隐藏）。
//
// QA 探针：window.__nfMobileLayer = { active, version } —— 采集脚本用它确认
// 交互层已激活（结构读数），不依赖任何视觉推断。

const MOBILE_MQ = '(max-width: 768px)';

/** @type {boolean} */
let active = false;
/** @type {Array<() => void>} */
let teardowns = [];

async function activate() {
  if (active) return;
  active = true;
  document.body.classList.add('mobile-layer');
  try {
    // 动态 import：桌面视口永不执行这三行（激活判据在前）。
    const [layout, gestures, floats] = await Promise.all([
      import('./layoutController.js'),
      import('./canvasGestures.js'),
      import('./floatController.js'),
    ]);
    if (!active) return; // 激活期间已跨越回桌面 —— 直接拆除
    teardowns = [
      layout.activateLayout(),
      gestures.activateGestures(),
      floats.activateFloats(),
    ];
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
window.__nfMobileLayer = {
  get active() { return active; },
  version: 'mobile-ui-refactor spec1-5 r1',
};
