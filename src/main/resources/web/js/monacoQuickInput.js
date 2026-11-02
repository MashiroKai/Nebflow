// monacoQuickInput.js — Monaco「快捷输入」组件桥（keyboard-first 三期，2026-11-02）。
//
// 为什么有这一层：命令面板的 UI 从「手写 DOM」换成 **Monaco 自带的 QuickInput**。
// vendored 的 AMD 包（vendor/monaco/vs/editor/editor.main.js）里装的就是 VS Code
// 命令面板用的那套组件 —— `vs/platform/quickinput/browser/quickInputController`
// 的 `QuickInputController`（列表/过滤/分组/键位提示/无障碍语义全在里面）。
//
// 三条实测事实决定了本文件写成这样（不是猜的，`docs/` 与提交信息里记了探针过程）：
//   ① platform 模块**可以从 vendored AMD 包 require 出来**，但必须**先**加载
//      `vs/editor/editor.main`（模块注册表在那一份 bundle 里；先 require 子模块
//      会去 fetch 不存在的 .js 文件 → 404）。所以入口先 `loadMonaco()`。
//   ② `IQuickInputService`（standalone 版）在**没有聚焦编辑器**时直接抛
//      「Quick input service needs a focused editor to work.」—— 页面级命令面板
//      用不了它。可用的是它底下那层 `QuickInputController`（workbench 的
//      QuickInputService 也只是把它包起来）。
//   ③ `QuickInputController` 的构造只要四件（options / layoutService / 实例化服务 /
//      上下文键服务），其中 layoutService 只是被读 6 个字段的**薄接口** —— 页面级
//      宿主完全能给得起（见 layoutAdapter）。
//
// 皮肤归属：**样式一律走我们的 CSS**。所有 style 值传空串 ⇒ 组件不写内联样式 ⇒
// `css/keyboard.css` 的 `.nf-quickinput-host` 段是唯一外观真相（玻璃材质、圆角、
// 字重都跟既有设计系统走）。这也是「不为了能跑就降标准」的一条：宁可多写一段 CSS，
// 也不让面板长成 VS Code 默认皮肤。

/** Monaco AMD 树根（与 monacoEditor.js 同一个常量口径）。 */
const MONACO_BASE = '/vendor/monaco/vs';

/** 宿主元素 id（CSS 与键盘验收脚本共用）。 */
export const HOST_ID = 'nf-quickinput-host';

/** @typedef {Object} MonacoQuickInputController
 * @property {() => any} createQuickPick
 * @property {(dimension: {width: number, height: number}, titleBarOffset: number) => void} layout
 * @property {() => void} dispose
 */

/** @type {Promise<MonacoQuickInputController>|null} */
let controllerPromise = null;

/**
 * 异步 require 一批 vendored AMD 模块。
 * @param {string[]} ids
 * @returns {Promise<any[]>}
 */
function amdRequire(ids) {
  const req = /** @type {any} */ (window).require;
  if (typeof req !== 'function') return Promise.reject(new Error('monaco AMD loader 未就位'));
  return new Promise((resolve, reject) => {
    req(ids, (/** @type {any[]} */ ...mods) => resolve(mods), () => reject(new Error(`monaco 模块加载失败：${ids.join(', ')}`)));
  });
}

/** @returns {HTMLElement} */
function ensureHost() {
  let host = document.getElementById(HOST_ID);
  if (host) return host;
  host = document.createElement('div');
  host.id = HOST_ID;
  document.body.appendChild(host);
  return host;
}

/** 组件会读的六个布局字段（见文件头 ③）。 @returns {any} */
function layoutAdapter() {
  /** @returns {{width: number, height: number}} */
  const dim = () => ({ width: window.innerWidth, height: window.innerHeight });
  const offset = () => ({ top: 0, quickPickTop: 0 });
  return {
    get mainContainer() { return document.body; },
    get mainContainerDimension() { return dim(); },
    get mainContainerOffset() { return offset(); },
    get activeContainer() { return document.body; },
    get activeContainerDimension() { return dim(); },
    get activeContainerOffset() { return offset(); },
    get mainWindow() { return window; },
    getLayout() { return { dimension: dim() }; },
  };
}

/** 面板顶部偏移（与改造前 `.nf-palette-overlay { padding-top: 12vh }` 同一位置）。 */
function titleBarOffset() {
  return Math.round(window.innerHeight * 0.12);
}

/**
 * 组件的样式入参：**全空串** ⇒ 组件不写内联样式 ⇒ 外观由 css/keyboard.css 决定。
 * 键名清单来自实机探针（对 Proxy 记录「组件真的读了哪些键」得出的 56 个），不是照
 * 抄类型定义 —— 少给一个键会在 `updateStyles()` 解构时直接抛，多给的无副作用。
 * @returns {any}
 */
function blankStyles() {
  const N = '';
  return {
    widget: {
      quickInputTitleBackground: N, quickInputBackground: N, quickInputForeground: N,
      widgetBorder: N, widgetShadow: N,
    },
    inputBox: { inputBackground: N, inputForeground: N, inputBorder: N },
    toggle: { inputActiveOptionBorder: N, inputActiveOptionForeground: N, inputActiveOptionBackground: N },
    countBadge: { badgeBackground: N, badgeForeground: N, badgeBorder: N },
    button: {
      buttonBackground: N, buttonForeground: N, buttonBorder: N,
      buttonSecondaryBackground: N, buttonSecondaryForeground: N, buttonSecondaryBorder: N,
      secondary: false, supportIcons: false, supportShortLabel: false,
    },
    progressBar: { progressBarBackground: N },
    pickerGroup: { pickerGroupBorder: N, pickerGroupForeground: N },
    keybindingLabel: {
      keybindingLabelBackground: N, keybindingLabelForeground: N, keybindingLabelBorder: N,
      keybindingLabelBottomBorder: N, keybindingLabelShadow: N,
    },
    list: {
      listBackground: N, listForeground: N, listFocusBackground: N, listFocusForeground: N,
      listFocusOutline: N, listActiveSelectionBackground: N, listActiveSelectionForeground: N,
      listActiveSelectionIconForeground: N, listInactiveSelectionBackground: N,
      listInactiveSelectionForeground: N, listInactiveSelectionIconForeground: N,
      listInactiveFocusBackground: N, listInactiveFocusForeground: N, listInactiveFocusOutline: N,
      listHoverBackground: N, listHoverForeground: N, listHoverOutline: N,
      listDropOverBackground: N, listDropBetweenBackground: N, listFocusAndSelectionBackground: N,
      listFocusAndSelectionForeground: N, listFocusAndSelectionOutline: N,
      listSelectionOutline: N, listHighlightForeground: N, tableColumnsBorder: N,
      tableOddRowsBackgroundColor: N, treeIndentGuidesStroke: N,
      treeStickyScrollBackground: N, treeStickyScrollBorder: N, treeStickyScrollShadow: N,
    },
  };
}

/**
 * 拿到（并缓存）页面级 quick input 控制器。
 * @returns {Promise<MonacoQuickInputController>}
 */
export function getQuickInput() {
  if (controllerPromise) return controllerPromise;
  controllerPromise = (async () => {
    // ① 先加载 monaco 主包（AMD 注册表在它里面），再 require 平台层模块。
    const { loadMonaco } = await import('./monacoEditor.js');
    await loadMonaco();
    const [SSM, IM, KM, QICM] = await amdRequire([
      'vs/editor/standalone/browser/standaloneServices',
      'vs/platform/instantiation/common/instantiation',
      'vs/platform/contextkey/common/contextkey',
      'vs/platform/quickinput/browser/quickInputController',
    ]);
    const StandaloneServices = SSM.StandaloneServices;
    const instantiation = StandaloneServices.get(IM.IInstantiationService);
    const contextKeys = StandaloneServices.get(KM.IContextKeyService);
    const host = ensureHost();
    const controller = new QICM.QuickInputController({
      idPrefix: 'nfpal',
      container: host,
      styles: blankStyles(),
      // hoverDelegate 只被 ActionBar 用来挂 tooltip；面板没有工具按钮，给空实现。
      hoverDelegate: { showHover: () => ({ dispose() {} }), hideHover: () => {}, placement: /** @type {any} */ ('mouse') },
      linkOpenerDelegate: () => {},
      ignoreFocusOut: () => false,
      setContextKey: () => {},
      backKeybindingLabel: () => 'Esc',
      returnFocus: () => {
        // 兜底回焦：正常路径由组件自己的 previousFocusElement 负责。
        const op = document.activeElement;
        if (op instanceof HTMLElement) op.focus({ preventScroll: true });
      },
    }, layoutAdapter(), instantiation, contextKeys);
    controller.layout({ width: window.innerWidth, height: window.innerHeight }, titleBarOffset());
    window.addEventListener('resize', () => {
      try { controller.layout({ width: window.innerWidth, height: window.innerHeight }, titleBarOffset()); } catch { /* 收场期布局失败不影响按键 */ }
    });
    return controller;
  })();
  return controllerPromise;
}

/**
 * 探针/断言用：桥是否可用（不触发加载）。
 * @returns {boolean}
 */
export function isQuickInputReady() {
  return !!controllerPromise;
}

/** 仅供测试清理：忘掉已缓存的控制器（不 dispose，DOM 由 host 复用）。 @returns {void} */
export function resetQuickInputForTests() {
  controllerPromise = null;
}
