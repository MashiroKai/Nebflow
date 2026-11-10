// mobile/navController.js — dock 批（作者令 2026-10-29 13:31/13:36/13:38）：
// 小红书式五栏底 dock ＋「每个节目都是独立界面」的全屏页导航。
//
// ── 导航模型（dock 批重写；中键改版批 2026-10-29 23:36 修订）─────────
//   · 底 dock = 根页切换器：五栏 `消息 / 联系人 / 对话 / canvas / 工作台`
//     （工作台批 2026-10-30 作者令：设置从顶层导航降级为工作台成员——
//     第五栏 设置 → 工作台；DOCK_ORDER 钉死）。dock 常驻（聊天根上也显示，
//     激活中键）；点 tab = 切换根页（互斥替换，非堆叠）。
//   · 中键改版批（作者 23:36 逐字「中间那个对话 就是进入我跟你的这个输入框
//     …最左侧的消息就是 跟联系人的对话消息」）：第三栏对话语义 = **chat
//     home 键**（点 = 详情全清 + 回聊天根输入框；不是页面路由，无 section），
//     形态 = 小红书中心键（sapphire 主色大圆、高出基线、图标 = agent 头像，
//     无头像回落气泡字形）；消息键 = 跟联系人的对话消息（好友会话列表自原
//     「对话」面迁入，气泡图标随迁）。
//   · 栈模型 = `stack = [home, ...details]`：stack[0] ∈ 'chat' ∪ 五栏根；
//     详情页（任务/搜索/定时/心跳/后台/智能体/项目/图谱/插件/用量/
//     设置/会话窗）push 在当前根之上；返回 pop 一层；根页「<」= 回聊天根。
//   · 总原则（作者 13:38 逐字「各节目都是独立界面 不再像目前这样跟其他界面
//     重叠」）：页面 = 全屏不透明 section（#mobile-pages 容器互斥 .live）；
//     浮层族全部退役 —— v3 双液态下拉列（headerController/layoutController/
//     floatController）、任务浮层 morph（mh-tpanel 呈现位）、画板 bottom
//     sheet（#canvas-panel 悬浮卡）、二级液态 morph 面板全部改为**收编进
//     独立页面**（overlay/pane 锚点原位归还惯例不变）。
//   · canvas = 独立整页（作者 13:36 判）：dock tab 进全屏 canvas 页，
//     非弹窗非悬浮；页内卡片式切换多张 canvas 卡（卡片列表 chip 条 +
//     ‹ › 步进 + 内容区横滑切卡，环式）；桌面 canvas 零变化（pane re-parent
//     进页面体，#canvas-panel 手机层永不显示）。
//   · 栈状态 = 会话内存（模块变量）；刷新后归位聊天根（口径同前批：刷新 ≙
//     App 冷启动）。
//
// ── 面收编（沿用 paneAnchors / overlayAnchors 两套锚点惯例）─────────
//   · 面板页（项目/图谱/插件）：桌面画板 pane re-parent 进页面体（不变）。
//   · overlay 面（设置/用量/搜索/定时/心跳/后台/智能体/任务）：
//     面节点 re-parent 进页面体；overlay 载体原地保留作状态源（外部关闭
//     对账 = watchOverlayClose 扩展到全页面注册表）。
//   · 会话窗（#fm-chat-overlay）：窗体节点 re-parent 进「会话」详情页；
//     桌面 closeChat 摘除 overlay ⇒ 对账弃节点 + 弹页。
//
// ── App 打包留缝点 ──────────────────────────────────────────────────
//   · 路由名稳定：'chat' | 五栏根 | 详情页 id（PAGES 注册表）；
//   · 通用导航 API：pushPage / popPage / goHome / dockTo；
//   · 状态广播：document 事件 `nf:mobile-nav`（detail = { stack, top, home, action }）；
//   · 只读探针：window.__nfMobileNav = { stack, top, home, version }。
//
// 共享组件复用声明：本文件不复制任何桌面逻辑 —— 页面内容全部走桌面导出
// API / 桌面已渲染节点收编；桌面侧零改动。

import {
  getTabPane, setActiveTab, closeTab, deckFaceOf, quoteActiveCardRef,
  // 堆叠视角真快照（作者令 2026-11-02 21:47 §三「让堆叠视觉能看到卡片内容」）：
  // 源解析/克隆腿**单源复用**桌面 canvas.js 的同一内核（不另造第二套截图方案），
  // 手机层只负责缓存与挂载。
  canvasSnapshotFor, canvasSnapshotStamp,
} from '../canvas.js';
import { hasBackHandler } from '../gesture/backSwipe.js';
import { key } from '../branding.js';
import { openSettingsPanel, closeSettingsPanel, isSettingsPanelActive } from '../sidebar.js';
import { createIconsIn } from '../utils.js';
import { ensureSocialPage, syncSocialFaces } from './socialFaces.js';
// The 'tasks' page body is the Apple-Reminders-style todo face, a three-page
// stack (tasks / todo-apple-list / todo-apple-item). The face receives push/pop
// by injection and never imports this module. mobile/taskPanel.js is no longer
// mounted by navigation; the file stays for its taskBadgeCount read seam.
import { mountTodoAppleInto } from './todoAppleFace.js';
// 层事件流水（循环门禁修复批 2026-11-04）：叶子模块，taskPanel 等打事件方直取它，
// 本模块只写 + 在 activateNav 挂 window（见该定义处注释）。
import { logEvent, navLayerEvents } from './navEvents.js';
import { buildMeFace } from './meFace.js';
// 弹窗退场批③（2026-10-31）：设置页折叠配置项 → 层级子页（meFace 同款注入式）。
import { buildSettingsSubFace, syncSettingsSubFaces, initSettingsSub, SETTINGS_SUB } from './settingsSub.js';
import { showLoginModal } from '../activityBar.js';
import { avatarViewState, noteAvatarFailure, paintAvatarSlot, getNeblinkState, onNeblinkStatus, fetchNeblinkStatus } from '../neblink.js';
import { brand } from '../brand.js';
import { wikiGraphEnabled } from '../featureFlags.js';
import { agentAvatarDataUrl, onAgentAvatarChange } from '../agentProfile.js';
import { avatarImgNode } from '../avatarRender.js';

/**
 * 底 dock 五栏（作者 2026-10-29 令逐字顺序；顺序钉死 = spec 断言面）。
 * 中键改版批（同日 23:36）：第三栏 = 'chat' —— 对话键是 **chat home 键**
 * （点 = 回主对话输入框），不是页面路由：不在 PAGES、ensureContainer 不为
 * 它建 section。 */
export const DOCK_ORDER = ['messages', 'contacts', 'chat', 'canvas', 'workbench'];

/** chat home 键定义。收形批（作者令 10-31 22:50 ②③）：中键只留图案 —— 无
 *  agent 头像时回落 **stand logo**（不显示空白/默认人形，气泡字形随批退役）；
 *  有头像 = 圆头像图。zh/en 不再渲染为可见标签，只承接 aria-label。 */
const DOCK_CHAT_DEF = { id: 'chat', icon: 'message-circle', zh: '对话', en: 'Chats' };

/** dock 键定义解析：chat home 键走 CHAT_DEF，其余查 PAGES。 */
function dockDef(id) {
  return id === 'chat' ? DOCK_CHAT_DEF : PAGE_BY_ID.get(id);
}

/** 工作台分组行（工作台根页体；dock 放不下的功能面的统一入口）。
 *  工作台批（作者令 2026-10-30「设置并入工作台一览」）：末行 = 设置——
 *  设置自顶层导航降级为工作台成员，点行进设置页（push 详情）。 */
const HUB_ROWS = [
  { page: 'projects',   icon: 'folder-kanban',   zh: '项目',      en: 'Projects' },
  { page: 'plugins',    icon: 'puzzle',          zh: '插件',      en: 'Plugins' },
  { page: 'usage',      icon: 'bar-chart-3',     zh: 'token 用量', en: 'Usage' },
  { page: 'graph',      icon: 'sparkles',        zh: '图谱',      en: 'Wiki Graph', flagGated: true },
  { page: 'reminder',   icon: 'alarm-clock',     zh: '定时任务',  en: 'Scheduled' },
  { page: 'daemon',     icon: 'heart-pulse',     zh: '心跳',      en: 'Daemons' },
  { page: 'background', icon: 'square-terminal', zh: '后台任务',  en: 'Background' },
  // account-hero 批（2026-11-07）：设置**不再是**工作台列表的一员 —— 它已升到
  // hero 的独立入口（buildWorkbenchMeEntry 的 [data-hero-settings]）。同一功能
  // 两个入口会打架（作者令「把设置的统一到这里」），故此行删除；PAGES 里的
  // 'settings' 页注册保留（pushPage('settings') 仍走同一详情页壳）。
];

/** 页面注册表。dock: true = 底栏根页；其余 = push 详情页。route id 稳定。
 *  （chat home 键不在此表 —— 见 DOCK_ORDER/CHAT_DEF。）中键改版批：消息键
 *  图标 = message-circle 气泡（作者令 23:36「图标换位」——气泡自原中键迁入；
 *  消息页内容 = 跟联系人的对话消息，面迁移见 socialFaces.js）。 */
export const PAGES = [
  { id: 'messages',      dock: true, icon: 'message-circle', zh: '消息',   en: 'Messages' },
  { id: 'contacts',      dock: true, icon: 'contact-round',  zh: '联系人', en: 'Contacts' },
  { id: 'canvas',        dock: true, icon: 'layers',         zh: '看板', en: 'canvas' },
  { id: 'workbench',     dock: true, icon: 'layout-grid',    zh: '工作台', en: 'Workspace' },
  { id: 'settings',      tabId: null,      icon: 'settings',  zh: '设置',   en: 'Settings' },
  { id: 'projects', tabId: 'projects', icon: 'folder-kanban',   zh: '项目',     en: 'Projects' },
  { id: 'graph',    tabId: 'wiki-graph', icon: 'sparkles',      zh: '图谱',     en: 'Wiki Graph' },
  { id: 'plugins',  tabId: 'plugins',  icon: 'puzzle',          zh: '插件',     en: 'Plugins' },
  { id: 'usage',    tabId: null,       icon: 'bar-chart-3',     zh: 'token 用量', en: 'Usage' },
  // 'tasks' 页（**恢复**：作者令 2026-11-05 09:28 —— 手机端待办入口回到左上
  // 悬浮圆钮，入口收敛批 2026-11-04 的撤除被本批反转；桌面端仍走胶囊）。
  { id: 'tasks',    tabId: null,       icon: 'list-todo',       zh: '任务',     en: 'Tasks' },
  // Todo face sub-pages, drilled into from the 'tasks' home page. The face
  // hides the shell title on all three pages, so zh/en only feed the section
  // aria-label.
  { id: 'todo-apple-list', tabId: null, icon: 'list-todo',      zh: '待办列表', en: 'To-Do List' },
  { id: 'todo-apple-item', tabId: null, icon: 'square-pen',     zh: '待办详情', en: 'To-Do' },
  // 搜索页标题（三点入口批 2026-11-05 页名胶囊定为唯一标题；2026-11-06 作者令
  // 「不要有省略 那就用搜索两个字吧」——「搜索聊天记录」整族写法退役，页名胶囊
  // 随 search.title 同批改「搜索」）；面内部那枚重复的 `.search-modal-header`
  // 标题已由 mobileLayer.css 隐藏（顶部只剩这一个胶囊）。
  { id: 'search',   tabId: null,       icon: 'search',          zh: '搜索',     en: 'Search' },
  { id: 'reminder', tabId: null,       icon: 'alarm-clock',     zh: '定时任务', en: 'Scheduled' },
  // 改版批（2026-10-31 作者令）：待办事项页——入口 = 消息列表顶部行（桌面 =
  // #todo-page 玻璃 overlay；手机 = 本详情页收编同一 DOM，层级下钻左上返回）。
  // ⛔ 【整面退役】todo-unify-entry-collapse 2026-11-04：#todo-page 处置 = (a)
  // 整面退役（DOM 保留在 index.html、不可达）。两个入口（消息列表入口行 /
  // `nf:mobile-todo-entry` → pushPage('todo')）同时撤除后，本页已无触发器；
  // 待办面唯一实现 = 顶带胶囊 + 展开面板（js/todoFace.js / todoStore 真源）。
  // 理由全文见 js/scheduled-task.js openTodoPage 的退役注记。
  // { id: 'todo',     tabId: null,       icon: 'list-checks',     zh: '待办事项', en: 'To-Dos' },
  { id: 'daemon',   tabId: null,       icon: 'heart-pulse',     zh: '心跳',     en: 'Daemons' },
  { id: 'background', tabId: null,     icon: 'square-terminal', zh: '后台任务', en: 'Background' },
  { id: 'profile',  tabId: null,       icon: 'bot',             zh: '智能体', en: 'Agent' },
  // 手机接入 social 详情页随联系人页顶入口行整链撤除（作者令 2026-10-31
  // 22:55 撤除批，仅手机端；桌面 #social-btn/#social-overlay 不动）。
  { id: 'conversation', tabId: null,   icon: 'message-circle',  zh: '会话',   en: 'Conversation' },
  // 手机顶栏批（2026-10-31 作者令④）：个人主页 + 层级配置子页（苹果层级式
  // 点进下一层铺满屏、左上返回 —— 复用既有详情页壳，返回钮/pop 语义零新建）。
  // 内容面 = 账号读数（/api/neblink/status 的 id_token claims + /api/users/search 自证探针，
  // 单点 = settingsIdentity.js getIdentityReadout）。**件四（2026-11-02 作者令）起配置/
  // 编辑动作不再跳官网**，而是就地开 App 内个人资料面
  // （settingsIdentity.js openProfileFace）—— 四个 me 子页零外链。
  { id: 'me',        tabId: null, icon: 'user-round', zh: '个人主页', en: 'Profile' },
  { id: 'me-user',   tabId: null, icon: 'user-round', zh: '用户名',   en: 'Username' },
  { id: 'me-avatar', tabId: null, icon: 'image',      zh: '头像',     en: 'Avatar' },
  { id: 'me-bind',   tabId: null, icon: 'link',       zh: '绑定',     en: 'Bindings' },
  // 弹窗退场批③（作者令「不再有展开式配置：点配置项进下一层级铺满屏、左上
  // 返回」）：设置页三处 chevron 折叠体的层级子页——内容面 = 收编桌面
  // .settings-collapse-body 节点（settingsSub.js 锚点惯例，桌面零改动）。
  ...SETTINGS_SUB.map((d) => ({ id: d.id, tabId: /** @type {string | null} */ (null), icon: 'chevron-right', zh: d.zh, en: d.en })),
];
/** 个人主页层级页 id 集（buildSection 面内容分派；meFace.js 单点实现）。 */
const ME_PAGE_IDS = new Set(['me', 'me-user', 'me-avatar', 'me-bind']);
const PAGE_BY_ID = new Map(PAGES.map((p) => [p.id, p]));
/** 面板页 id 集（项目/图谱/插件——承载桌面画板 pane 的页）。栈顶 = 面板页时
 *  canvas-open 上升沿/切卡事件是面板开页签的副产物，自动弹 canvas 页会把刚
 *  push 的面板页顶掉（工作台误路由批 2026-10-30 作者令实证：点工作台行进
 *  项目 ⇒ 弹出 canvas）。 */
const PANEL_PAGE_IDS = new Set(PAGES.filter((p) => p.tabId).map((p) => p.id));

// ── 共享模块惰性单载（首次进入页面后同步可用；与 r1 同款机制）────────
/** @type {Record<string, Promise<any>>} */
const lazyMods = {};
function lazy(key, loader) {
  if (!lazyMods[key]) lazyMods[key] = loader().catch((err) => { delete lazyMods[key]; throw err; });
  return lazyMods[key];
}
const modProjects = () => lazy('projects', () => import('../projectTab.js'));
const modGraph = () => lazy('graph', () => import('../wikiGraph.js'));
const modPlugins = () => lazy('plugins', () => import('../plugins.js'));
const modUsage = () => lazy('usage', () => import('../usageDashboard.js'));
const modAgentProfile = () => lazy('agentProfile', () => import('../agentProfile.js'));

/** 空闲预热（首次点击零延迟；失败静默 —— 页面路径有兜底）。 */
export function warm() { modProjects(); modGraph(); modPlugins(); modUsage(); }

function tt(zh, en) {
  // 语言源 = i18n.js 同源键（key('locale')，branding.js 命名空间派生）。
  let lang = '';
  try { lang = localStorage.getItem(key('locale')) || ''; } catch { /* private mode */ }
  if (!lang) lang = document.documentElement.lang || navigator.language || '';
  return /^zh/i.test(lang) ? zh : en;
}

// ── 层事件流水（QA 探针 window.__nfMobileLayerEvents，只读）──────────
// 循环门禁修复批（2026-11-04）：流水 + logEvent 抽到叶子 navEvents.js —— 这里是
// 它的**挂载点**（activateNav 把同一数组引用挂 window）。taskPanel/searchFace/
// socialSearch/chatChrome 打事件时直取叶子，不再反过来 import 本模块（那是 SCC[2]
// 的一半）。

// ── 首触标记（启动自动展开抑制的判据）────────────────────────────────
let layerTouched = false;
export function touchLayer() {
  if (!layerTouched) { layerTouched = true; logEvent('layer-first-touch'); }
}
export function isLayerTouched() { return layerTouched; }

// ── 模块状态 ─────────────────────────────────────────────────────────
/** @type {string[]} 页面栈；stack[0] = home（'chat' 或 dock 根），其余 = 详情。 */
let stack = ['chat'];
/** @type {HTMLElement|null} */
let container = null;
/** @type {Map<string, HTMLElement>} route id → section */
const sections = new Map();
/** @type {Map<string, HTMLElement>} 页面 hub 容器 */
const hubBodies = new Map();
/** @type {{pane: Element, parent: Node, next: Node|null}[]} pane 归还锚点。 */
const paneAnchors = [];
/** @type {HTMLElement|null} 底 dock（dock 批：常驻根页切换器）。 */
let dockEl = null;
/** @type {MutationObserver|null} */
let contentObserver = null;
/** @type {MutationObserver|null} 会话窗收编观察者。 */
let chatModalMo = null;
/** @type {{modal: Element}|null} 会话窗收编槽。 */
let chatAdopted = null;
/** @type {MutationObserver[]} */
let overlayObservers = [];
/** @type {Array<() => void>} */
let unsubs = [];

const backLabel = () => tt('返回', 'Back');

// ── 面板头标题「大标题 → 胶囊」滚动探针（两段式标题批 2026-11-02 作者令，
//    标题单元素化批 2026-11-02 22:32 沿用同一进度源）──────────────────
// 承接 B 批（2026-11-01 17:01 选型）的 56px 吸附阈值，并把它由「布尔切换」
// 升级为**连续进度**：同一元素（.mobile-page-title）的全部形态量（字号/盒高/
// 内距/外边距/字距/壳层 alpha）都由 CSS 侧的 --mtitle-p 0→1 插值，逐帧跟手。
//   进度区间：[ROLL_START, ROLL_THRESHOLD] = [8, 56]（区间外钳死在 0/1）——
//   8 起算 = 手指一动即开始收，56 到位 = 字形缩到胶囊档、壳层显影到满。
//   .rolled 保留为「进度到顶」的布尔判据（spec 与兼容读者用；阈值仍 56）。
const ROLL_START = 8;
const ROLL_THRESHOLD = 56;
/** reduced-motion：形态二值切换（进度只在 0/1 两值 —— 本形态零 transition、
 *  零位移量，故二值即「瞬时换形、不卡中间」，无额外摘除）。 */
const reducedMotion = () => typeof window.matchMedia === 'function'
  && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
/** 事件目标到 section 之间**最外层**纵向可滚容器（getComputedStyle overflowY
 *  auto/scroll + 真实可滚余量）—— 内层横滑 chip 条/嵌套小滚区不误判。 */
function primaryScroller(section, target) {
  let primary = null;
  for (let n = /** @type {Element} */ (target); n && n !== section; n = n.parentElement) {
    const oy = getComputedStyle(n).overflowY;
    if ((oy === 'auto' || oy === 'scroll') && n.scrollHeight - n.clientHeight > 4) primary = n;
  }
  return primary;
}
/** 写进度 + 两个判据类：
 *   --mtitle-p        0→1 连续进度（CSS 两段形态量单源）；
 *   .rolled           scrollTop > 阈值（吸附成立，spec/兼容读者）；
 *   .mpt-pane-scroll  主滚轴**不是**页面体（pane/内滚面：大标题不会随滚走，
 *                     故 CSS 侧按进度收高把整条顶带还给收编面）。 */
function applyRoll(section, scroller) {
  const st = scroller ? scroller.scrollTop : 0;
  let p = (st - ROLL_START) / (ROLL_THRESHOLD - ROLL_START);
  p = p < 0 ? 0 : p > 1 ? 1 : p;
  if (reducedMotion()) p = p >= 0.5 ? 1 : 0;
  section.style.setProperty('--mtitle-p', String(Math.round(p * 1000) / 1000));
  section.classList.toggle('rolled', st > ROLL_THRESHOLD);
  const body = section.querySelector('.mobile-page-body');
  section.classList.toggle('mpt-pane-scroll', !!scroller && scroller !== body);
  // 长页名溢出标记（端区分批 2026-11-05 09:28 起 `.mtitle-clip` 不再画渐隐蒙版，
  // 只作「这一页的名字被钳宽了」的**溢出标记类**：截断本体 = 基座档的可见省略号
  // （css/mobileLayer.css .mobile-page-title）。类本身保留，供标题几何/探针按
  // 「是否钳宽」分档读数。进度写盘后同帧读 scrollWidth（样式落定后读布局量会
  // 同步重排，无滞后帧），不误伤装得下的页名。
  const title = /** @type {HTMLElement|null} */ (section.querySelector('.mobile-page-title'));
  if (title) title.classList.toggle('mtitle-clip', title.scrollWidth > title.clientWidth + 1);
  measureTitleStill(section, title);
}

/**
 * 大标题档内容宽实测（schedule-todo-unify 批 2026-11-06「顶栏标题被截成『定时任...』」
 * 修复的读数腿）：
 *  · CSS 侧大标题宽度 = calc(100% − (100% − var(--mtitle-w-still)) × 进度)，即
 *    静止档吃满两端内缩带；内容宽只能在**进度 0** 时测（进度 >0 时盒已被收窄，
 *    scrollWidth 会自己钳住 —— 那样测出来的值会随滚动变化，不是内容宽）。
 *  · 故把进度暂时打到 0、测 `scrollWidth`，再还原进度 —— 全部在同一次样式落定内
 *    完成，不产生可见帧。只在文字可能变了的时候重测（首帧 / 换页 / locale 重绘 /
 *    窗口宽度变），拖动滚动时不重测（内容宽与滚动无关，省掉每帧强制布局）。
 * @param {HTMLElement} section
 * @param {HTMLElement|null} title
 */
function measureTitleStill(section, title) {
  if (!title) return;
  const cs = getComputedStyle(title);
  const mcapPad = parseFloat(cs.getPropertyValue('--mcap-pad')) || 12;
  const prevP = section.style.getPropertyValue('--mtitle-p') || '0';
  const oldW = title.style.getPropertyValue('--mtitle-w-still');
  // 静止档实测：进度 0 + 拔 max-width ⇒ scrollWidth = 纯文字宽（静止档水平内距
  // 已归 0 —— 内缩由盒宽钳制承担，见 mobileLayer.css .mobile-page-title 注）。
  const oldMax = title.style.maxWidth;
  title.style.maxWidth = 'none';
  section.style.setProperty('--mtitle-p', '0');
  const textW = title.scrollWidth;
  title.style.maxWidth = oldMax;
  section.style.setProperty('--mtitle-p', prevP);
  // 胶囊终态盒 = 文字 + 2×胶囊内距（贴字收紧）；超长页名钳到「满宽 − 2×胶囊内距」
  // （胶囊永不贴屏幕边；bodyW = 页体内容宽 = 视口 − 页体内距，实测不按页写死）。
  const body = title.parentElement;
  let bodyW = 0;
  if (body) {
    const bcs = getComputedStyle(body);
    bodyW = body.clientWidth - (parseFloat(bcs.paddingLeft) || 0) - (parseFloat(bcs.paddingRight) || 0);
  }
  const still = Math.min(textW + 2 * mcapPad, Math.max(0, bodyW - 2 * mcapPad));
  title.style.setProperty('--mtitle-w-still', `${still}px`);
  if (oldW !== `${still}px`) {
    // 文字变了才重算截断标记（同一帧内读，不额外起帧）。
    title.classList.toggle('mtitle-clip', title.scrollWidth > title.clientWidth + 1);
  }
}
/** 滚动收缩探针：section 上挂捕获相位监听（scroll 不冒泡但走捕获，页内一切
 *  滚动容器——页体自身/pane 内部 .team-scroll、#settings-content、.todo-page-body
 *  等——统一汇入，零逐面接线）。 */
function bindRollProbe(section) {
  section.addEventListener('scroll', (e) => {
    const sc = primaryScroller(section, e.target) || /** @type {HTMLElement} */ (e.target);
    applyRoll(section, sc);
  }, { capture: true, passive: true });
}
/** 进场对账（push/切根时调用）：页面壳 DOM 常驻、滚动位保留，故按当前实测
 *  主滚轴重算一次进度（否则离场时的吸附态会带进下一次进场）。
 *  判据同事件腿：**最外层**有余量的纵向滚动器（按距 section 的层级取最小者，
 *  避免把 earlier 兄弟里的嵌套滚区误当主滚轴）。 */
function refreshRoll(section) {
  if (section.classList.contains('mobile-page-nohead')) return;
  const body = section.querySelector('.mobile-page-body');
  let primary = null;
  let primaryDepth = Infinity;
  const depthOf = (n) => {
    let d = 0;
    for (let p = n; p && p !== section; p = p.parentElement) d += 1;
    return d;
  };
  if (body) {
    for (const n of [body, ...body.querySelectorAll('*')]) {
      const oy = getComputedStyle(n).overflowY;
      if (oy !== 'auto' && oy !== 'scroll') continue;
      if (n.scrollHeight - n.clientHeight <= 4) continue;
      const d = depthOf(n);
      if (d < primaryDepth) { primary = n; primaryDepth = d; }
      if (d === 1) break; // 页体自身可滚 ⇒ 它必是最外层，无需再扫
    }
  }
  applyRoll(section, primary || body);
  measureTitleStill(section, /** @type {HTMLElement|null} */ (section.querySelector('.mobile-page-title')));
}

// ── 页面壳构建 ───────────────────────────────────────────────────────

function buildSection(def) {
  const section = document.createElement('section');
  section.className = 'mobile-page';
  section.dataset.page = def.id;
  section.setAttribute('aria-label', tt(def.zh, def.en));

  const body = document.createElement('div');
  body.className = 'mobile-page-body';

  // 手机顶栏批（2026-10-31 作者令①「手机端各页顶部标题文字全去掉」，main 侧）
  //  ∪ 顶栏环形模糊批①（canvas 胶囊退役，本批）：dock 根页（消息/联系人/
  //  canvas/工作台）顶栏整体退役——标题胶囊移除、内容上移吃回顶栏空间；
  //  安全区顶距改由页面壳自管（mobileLayer.css .mobile-page-nohead =
  //  env(safe-area-inset-top)，真机不留刘海侵入、桌面视口零高）。
  //  面板头标识 B 批（2026-11-01 作者 17:01 选型 B「iOS 大标题式」，样张
  //  /Users/kaiyu/.stand/design-explore/panel-label-variants/）：详情页顶部
  //  旧 glass-control 胶囊退役，换两态大标题 —— 展开态 = 大标题在页体流内
  //  （随滚走；.mobile-page-title 类名不变，locale 重绘/ push 聚焦两个既有
  //  查询点零改）；收缩态 = 小字居中 + 顶缘渐隐带承底（几何/材质在
  //  mobileLayer.css）。dock 根页无页头不变。
  //  两段式标题批（2026-11-02 作者令，实拍 IMG_1677/1678）：两态升级为
  //  「静止大标题（水平居中）→ 吸附毛玻璃胶囊」连续两段，渐隐带退役；
  //  滚动进度 --mtitle-p 由 bindRollProbe 写（几何全在 mobileLayer.css）。
  if (!def.dock) {
    const head = document.createElement('div');
    // 零底带类 = design-system.css 单源（透传悬浮批 2026-11-03：与聊天根
    // #mobile-fab-layer / canvas 工具条同一族，一处定义处处引用）。
    head.className = 'mobile-page-header ds-chrome-band';
    // 标题单元素化批（2026-11-02 22:32 作者令「两个割裂的形态…一直是同一个的
    // 形式」）：吸附态装饰副本 .mobile-page-title-mini **退役**（DOM 不再建节点）。
    // 双影根因 = 两套元素同屏交接（大标题盒收高的同时胶囊元素登场，交接区间内
    // 两者同时可见）。现在页名只有一个节点 .mobile-page-title，吸附态由它自身的
    // ::before 壳层（玻璃底/圆角/描边）连续渐显承担 —— 原理与读数见
    // mobileLayer.css「面板头标题单元素连续变形」块。
    const back = document.createElement('button');
    back.type = 'button';
    // 悬浮钮统一批（作者令 10-31 16:03）：「<」归 48px g2-fab 基准材质，
    // 悬浮位/几何由 mobileLayer.css 页面悬浮带块承担；单元素化批后吸附态几何
    // 不变（标题左缘恒 72 = 钮右缘 64 + 缝 8），吸附态保持可点。
    back.className = 'mobile-back-btn g2-fab';
    back.title = backLabel();
    back.setAttribute('aria-label', backLabel());
    back.innerHTML = '<i data-lucide="chevron-left"></i>';
    back.addEventListener('click', () => backOut());
    head.append(back);
    if (typeof lucide !== 'undefined') createIconsIn(head);
    // 唯一页名节点：静止态 = 流内大标题，吸附态 = 同一件的胶囊形态（无第二份）。
    const title = document.createElement('span');
    title.className = 'mobile-page-title';
    title.tabIndex = -1; // push 后聚焦（读屏从头播报页名）
    title.textContent = tt(def.zh, def.en);
    body.appendChild(title);
    section.append(head, body);
    bindRollProbe(section);
  } else {
    // 顶栏批：根页无页头 —— 壳级标记 = 安全区顶距由页面壳自管（mobileLayer.css）。
    section.classList.add('mobile-page-nohead');
    section.append(body);
  }
  if (def.id === 'workbench') buildHub(body);
  if (def.id === 'canvas') buildCanvasPage(body);
  if (ME_PAGE_IDS.has(def.id)) unsubs.push(buildMeFace(def.id, body, { push: pushPage }));
  if (SETTINGS_SUB.some((d) => d.id === def.id)) buildSettingsSubFace(def.id, body);
  return section;
}

/** 工作台根页的「工作台」分组行（dock 五栏放不下的功能面统一入口；push 详情；
 *  末行 = 设置——设置自顶层导航降级为工作台成员，工作台批 2026-10-30）。 */
function buildHubRow(r) {
  const row = document.createElement('button');
  row.type = 'button';
  row.className = 'mf-hub-row';
  row.dataset.hubPage = r.page;
  row.innerHTML = `<i data-lucide="${r.icon}"></i><span class="mf-hub-row-label"></span><i data-lucide="chevron-right" class="mf-hub-chev"></i>`;
  /** @type {HTMLElement|null} */ (row.querySelector('.mf-hub-row-label')).textContent = tt(r.zh, r.en);
  row.addEventListener('click', (e) => {
    // 打开的面板（桌面 outside-close 监听 document 冒泡）会被本记点击的
    // 继续冒泡误关 —— 入口键一律停冒泡（dock 批 2026-10-29 实测修复）。
    e.stopPropagation();
    touchLayer();
    logEvent('hub-open', { page: r.page });
    pushPage(r.page);
  });
  return row;
}

function buildHub(body) {
  // 手机顶栏批（2026-10-31 作者令②④）：工作台顶 = 用户头像入口（圆形，与设置页
  // 头像同源同态：avatarViewState 双态 + paintAvatarSlot 就绪门绘制）。点击 =
  // 账号统一行为（activityBar 口径）：已登录 → push 个人主页；未登录/配对中 →
  // 登录面（showLoginModal 必须在真实手势栈内调用）。
  body.appendChild(buildWorkbenchMeEntry());
  const hub = document.createElement('div');
  hub.className = 'mf-hub';
  const label = document.createElement('div');
  label.className = 'mf-hub-label';
  label.textContent = tt('工作台', 'Workspace');
  hub.appendChild(label);
  const rows = document.createElement('div');
  rows.className = 'mf-hub-rows';
  hub.appendChild(rows);
  body.appendChild(hub);
  hubBodies.set('workbench', hub);
  syncHubGate();
  // 状态拍重绘（登录/登出/头像变更）：订阅 neblink 状态流，拆除时随层注销。
  unsubs.push(onNeblinkStatus(() => paintWorkbenchMe()));
  void fetchNeblinkStatus().then(() => paintWorkbenchMe()).catch(() => {});
}

/** @type {HTMLElement|null} 工作台头像入口（paintWorkbenchMe 重绘对象）。 */
let workbenchMeEntry = null;

function buildWorkbenchMeEntry() {
  // account-hero 批（2026-11-07 作者令逐字「手机端工作台上面的账户 做出 hero 那种
  // 感觉 并且去重 把设置的统一到这里」）：工作台顶部 = **身份 hero**（头像 88 /
  // 名 / 邮箱为主体，大面积留白），且是「账号 + 设置」的**唯一入口**：
  //   · 整块点击 = 个人主页（既有 me 层级面，含用户名/头像/绑定子页）；
  //   · hero 内独立「设置」钮 = 设置页（原先工作台列表里那一行 settings 已从
  //     HUB_ROWS 移除，避免同一功能两个入口打架）；
  //   · 设置页不再重复身份信息（见 js/sidebar.js 账号段去重注）。
  const entry = document.createElement('button');
  entry.type = 'button';
  entry.className = 'mf-me-entry';
  entry.setAttribute('data-me-entry', '1'); // QA 断言面：工作台头像入口机械定位
  entry.setAttribute('data-hero', 'account'); // hero 化断言面（本批新增）
  entry.innerHTML = `
    <span class="mf-me-avatar-frame huge">
      <picture>
        <source media="(prefers-color-scheme: dark)" srcset="${brand.logoDarkUrl}">
        <img class="mf-me-avatar-logo" src="${brand.logoUrl}" alt="">
      </picture>
      <img class="mf-me-avatar-photo" alt="" hidden>
    </span>
    <span class="mf-me-meta">
      <span class="mf-me-name"></span>
      <span class="mf-me-sub"></span>
      <span class="mf-me-tagline">我的空间 · 账号与设置</span>
    </span>
    <span class="mf-me-actions">
      <span class="mf-me-settings" role="button" tabindex="0" data-hero-settings="1">
        <i data-lucide="settings" class="mf-me-settings-ic"></i>
        <span class="mf-me-settings-label">设置</span>
      </span>
    </span>`;
  // hero 内设置钮（子元素上的 click 冒泡到 entry，必须先 stopPropagation 并
  // 自己 push settings —— 两个入口语义分离，互不触发的另一个）。
  const settingsBtn = /** @type {HTMLElement | null} */ (entry.querySelector('[data-hero-settings]'));
  if (settingsBtn) {
    const openSettings = (e) => {
      e.stopPropagation();
      touchLayer();
      logEvent('hub-open', { page: 'settings' });
      pushPage('settings');
    };
    settingsBtn.addEventListener('click', openSettings);
    settingsBtn.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') openSettings(e);
    });
  }
  entry.addEventListener('click', (e) => {
    e.stopPropagation();
    touchLayer();
    const st = getNeblinkState();
    if (st.pairing) return;
    if (st.loggedIn) {
      logEvent('me-open', { from: 'workbench' });
      pushPage('me');
      return;
    }
    logEvent('me-login', { from: 'workbench' });
    showLoginModal();
  });
  workbenchMeEntry = entry;
  paintWorkbenchMe();
  if (typeof lucide !== 'undefined') createIconsIn(entry); // chevron 就位（图标绘制零依赖后续清扫）
  return entry;
}

/** 工作台头像入口重绘：头像双态（paintAvatarSlot 就绪门，永不空环）+ 名/副行。
 *  数据源 = neblink 状态（displayName/email，与设置账号区同读数面）。 */
function paintWorkbenchMe() {
  const entry = workbenchMeEntry;
  if (!entry) return;
  const st = getNeblinkState();
  const { url: validAvatarUrl, showPhoto } = avatarViewState();
  const photoEl = entry.querySelector('.mf-me-avatar-photo');
  const logoEl = entry.querySelector('.mf-me-avatar-logo');
  if (photoEl instanceof HTMLImageElement) {
    paintAvatarSlot(photoEl, logoEl instanceof HTMLImageElement ? logoEl : null,
      validAvatarUrl, showPhoto, () => {
        noteAvatarFailure(validAvatarUrl);
        const view = avatarViewState();
        photoEl.hidden = true;
        if (logoEl instanceof HTMLImageElement) logoEl.hidden = view.showPhoto;
      });
  }
  const nameEl = entry.querySelector('.mf-me-name');
  const subEl = entry.querySelector('.mf-me-sub');
  if (nameEl) nameEl.textContent = st.loggedIn
    ? (st.device?.displayName || st.device?.email || tt('已登录', 'Signed in'))
    : tt('未登录', 'Not signed in');
  if (subEl) subEl.textContent = st.loggedIn
    ? (st.device?.email || '')
    : tt('点击登录账号', 'Tap to sign in');
}

/** flag 门控行（graph）随配置到达重对账：ensureContainer 在 boot 早期建全部
 *  页面，首帧 configData 可能尚未写入 parsedConfig —— 若只按建行当时的 flag
 *  判定，graph 行会缺席到下次刷新（p9 单跑/全量均实测同点 flaky）。
 *  renderPages 每次翻页对账一次：行集与 flag 期望不一致才重建（一致零成本）。 */
function syncHubGate() {
  const hub = hubBodies.get('workbench');
  const rows = hub?.querySelector('.mf-hub-rows');
  if (!rows) return;
  const want = HUB_ROWS.filter((r) => !r.flagGated || wikiGraphEnabled()).map((r) => r.page);
  const have = [...rows.querySelectorAll('.mf-hub-row')].map((el) => (/** @type {HTMLElement} */ (el)).dataset.hubPage);
  if (want.length === have.length && want.every((p, i) => p === have[i])) return;
  rows.replaceChildren();
  for (const r of HUB_ROWS) {
    if (r.flagGated && !wikiGraphEnabled()) continue; // graphseal 同 flag（featureFlags 惯例）
    rows.appendChild(buildHubRow(r));
  }
  if (typeof lucide !== 'undefined') createIconsIn(rows);
}

function ensureContainer() {
  if (container) return container;
  container = document.createElement('div');
  container.id = 'mobile-pages';
  for (const def of PAGES) {
    const section = buildSection(def);
    sections.set(def.id, section);
    container.appendChild(section);
  }
  // 次级面板边缘返回（作者令 2026-11-01 ③）：容器级捕获相位手势，建容器即绑
  // （详情页进出零重绑；范围 = stack.length > 1 的详情面，见 bindEdgeBack 头注）。
  bindEdgeBack(container);
  document.body.appendChild(container);
  const onLocale = () => {
    for (const def of PAGES) {
      const s = sections.get(def.id);
      if (!s) continue;
      // 标题单元素化批（2026-11-02）：页名只有 .mobile-page-title 一个节点
      // （装饰副本已退役），locale 重绘单点。
      const t = s.querySelector('.mobile-page-title');
      if (t) t.textContent = tt(def.zh, def.en);
      const b = /** @type {HTMLElement|null} */ (s.querySelector('.mobile-back-btn'));
      if (b) { b.title = backLabel(); b.setAttribute('aria-label', backLabel()); }
    }
    const hub = hubBodies.get('workbench');
    const hubLabel = hub?.querySelector('.mf-hub-label');
    if (hubLabel) hubLabel.textContent = tt('工作台', 'Workspace');
    paintWorkbenchMe(); // 顶栏批：未登录/已登录文案随语言切换重绘
    const rows = hub?.querySelectorAll('.mf-hub-row');
    if (rows) {
      for (const row of rows) {
        const r = HUB_ROWS.find((x) => x.page === (/** @type {HTMLElement} */ (row)).dataset.hubPage);
        if (r) (/** @type {HTMLElement|null} */ (row.querySelector('.mf-hub-row-label'))).textContent = tt(r.zh, r.en);
      }
    }
    syncCanvasPage();
  };
  window.addEventListener('locale-changed', onLocale);
  unsubs.push(() => window.removeEventListener('locale-changed', onLocale));
  return container;
}

function bodyOf(pageId) {
  return sections.get(pageId)?.querySelector('.mobile-page-body') || null;
}

// ── 面板 pane re-parent（桌面画板 pane → 项目/图谱/插件页）──────────

/** 把面板页签 pane 移入页面体（幂等）。移动 = appendChild（监听随节点走）。 */
function adoptPane(pageId) {
  const def = PAGE_BY_ID.get(pageId);
  const pane = def?.tabId ? getTabPane(def.tabId) : null;
  const body = bodyOf(pageId);
  if (!pane || !body || pane.parentElement === body) return;
  paneAnchors.push({ pane, parent: pane.parentElement, next: pane.nextSibling });
  logEvent('pane-adopt', { page: pageId, tab: def.tabId });
  body.appendChild(pane);
}

// ── overlay 内容面收编（设置/用量惯例扩展到全部页面面）───────────────
// overlay 载体节点原地保留，只作状态载体（.on/.open/.hidden/[hidden] 与外部
// 关闭对账照旧）；可见内容面 re-parent 进页面体。桌面重渲/销毁兼容：面写入
// 都是收编节点内部的子树写入，与面的落点无关。
/** @type {Map<string, {face: Element, parent: Node, next: Node|null}>} page id → 面锚点 */
const overlayAnchors = new Map();
/** 页面 → overlay 内容面（收编对象；conversation 页走动态会话窗槽，不在此表）。 */
const overlayFaceByPage = {
  settings: '#settings-modal',
  usage: '#usage-dashboard',
  search: '#search-modal',
  reminder: '#reminder-panel',
  daemon: '#daemon-panel',
  background: '#bg-dropdown',
  profile: '#memory-modal',
  // 'tasks' is absent on purpose: its body is the Apple todo face, mounted
  // straight into the page body by ensurePanelContent, so there is no overlay
  // to adopt.
  // 'todo'（#todo-page）仍整面退役（本批无入口要求，见 PAGES 同批注记）。
  // todo: '#todo-page',
};

/** 把 overlay 内容面移入页面体（幂等；锚点照 paneAnchors 惯例记录）。 */
function adoptOverlayFace(pageId) {
  const sel = overlayFaceByPage[pageId];
  const face = sel ? document.querySelector(sel) : null;
  const body = bodyOf(pageId);
  if (!face || !body || face.parentElement === body) return;
  overlayAnchors.set(pageId, { face, parent: face.parentElement, next: face.nextSibling });
  logEvent('pane-adopt', { page: pageId, face: sel });
  body.appendChild(face);
}

/** 页面体 → overlay 原位归还（出栈/拆层；面不在页面体时空操作）。 */
function restoreOverlayFace(pageId) {
  const a = overlayAnchors.get(pageId);
  if (!a) return;
  overlayAnchors.delete(pageId);
  const body = bodyOf(pageId);
  if (!a.face || !body || a.face.parentElement !== body || !a.parent || !a.parent.isConnected) return;
  try {
    a.parent.insertBefore(a.face, a.next);
    logEvent('pane-restore', { page: pageId, face: overlayFaceByPage[pageId] });
  } catch { /* 树已变：放弃归还，避免二次破坏 */ }
}

/** 栈 ↔ overlay 面对账（汇合点 = renderPages：在栈即收编，出栈即归还）。 */
function syncOverlayFaces() {
  for (const id of Object.keys(overlayFaceByPage)) {
    if (stack.includes(id)) adoptOverlayFace(id);
    else restoreOverlayFace(id);
  }
}

/** 页面 → 桌面模块入口（pane 的创建/渲染全部走桌面导出 API，零复制）。 */
const openFnByPage = {
  projects: () => modProjects().then((m) => m.openProjectsTab()),
  graph: () => modGraph().then((m) => m.openWikiGraphTab()),
  plugins: () => modPlugins().then((m) => m.openPlugins()),
};

/** 桌面按钮同一入口的合面腿（桌面键在手机层不可见，click 语义 = 同一处理函数）。 */
function clickDesktopBtn(id) { (/** @type {HTMLElement|null} */ (document.getElementById(id)))?.click(); }

async function ensurePanelContent(pageId) {
  const fn = openFnByPage[pageId];
  if (fn) {
    try {
      await fn();
    } catch (e) {
      console.error('[mobile-layer] page open failed:', pageId, e);
    }
    adoptPane(pageId);
    return;
  }
  // 页面内容臂（按页面 id 分派；全部幂等）。
  switch (pageId) {
    case 'messages':
    case 'contacts':
      ensureSocialPage(pageId, bodyOf(pageId));
      break;
    case 'settings':
      try { openSettingsPanel(); } catch (e) { console.error('[mobile-layer] settings', e); }
      break;
    case 'usage':
      modUsage()
        .then((m) => {
          m.openUsageDashboard();
          ensureOverlayReconcile('usage', '#usage-overlay', (el) => !!el.hidden, ['hidden']);
          syncOverlayFaces(); // overlay 首开此刻才建出 —— 建好即收编
        })
        .catch((e) => console.error('[mobile-layer] usage', e));
      break;
    case 'search':
      try {
        const { openSearchModal } = await import('../chatSearch.js');
        openSearchModal();
      } catch (e) { console.error('[mobile-layer] search', e); }
      break;
    case 'reminder':
      if (!document.querySelector('#reminder-panel.open')) clickDesktopBtn('reminder-btn');
      break;
    // ⛔ 【退役】todo-unify-entry-collapse 2026-11-04：'todo' 页整面退役（PAGES
    // 与 overlayFaceByPage 的同批注记）——本分支不再可达（无 pushPage('todo')），
    // 原文留档，零物理删除：
    // case 'todo':
    //   // 改版批（2026-10-31）：待办页无桌面钮（入口 = 消息列表行）——直调
    //   // scheduled-task.js 开合臂；overlay 首开此刻建态，收编由 syncOverlayFaces 对账。
    //   import('../scheduled-task.js')
    //     .then((m) => m.openTodoPage())
    //     .catch((e) => console.error('[mobile-layer] todo', e));
    //   break;
    case 'daemon':
      if (!document.querySelector('#daemon-panel.open')) clickDesktopBtn('daemon-btn');
      break;
    case 'background':
      if (document.querySelector('#bg-dropdown.hidden')) clickDesktopBtn('bg-indicator');
      // dock 批：手机层 bgagent 键属退场桌面簇 —— 载体开时补跑子智能体列表
      // 渲染（桌面键开面分支同款 fetch + render；幂等，收编列表随节点走）。
      import('../main/bgAgents.js').then((m) => {
        m.fetchActiveAgentsOnOpen();
        m.renderBgAgentDropdown();
      }).catch(() => { /* 未载即无行 */ });
      break;
    case 'profile':
      try {
        const ap = await modAgentProfile();
        if (ap.openProfilePanel) ap.openProfilePanel();
      } catch (e) { console.error('[mobile-layer] profile', e); }
      break;
    case 'tasks':   // Apple todo face home (entry: top-left round button)
    case 'todo-apple-list':
    case 'todo-apple-item':
      // The face rebuilds the whole page body idempotently; nav is injected so
      // the face never imports this module.
      mountTodoAppleInto(pageId, bodyOf(pageId), { push: pushPage, pop: popPage });
      break;
    default:
      break;
  }
}

/** 画板内容区监视：面板 pane 出现即收编进页面（文档 pane 由 canvas 页管）。 */
function watchCanvasContent() {
  const content = document.getElementById('canvas-content');
  if (!content) return;
  const tabIdToPage = new Map(PAGES.filter((p) => p.tabId).map((p) => [p.tabId, p.id]));
  const sweep = () => {
    for (const pane of content.querySelectorAll(':scope > .canvas-tab-pane')) {
      const pageId = tabIdToPage.get((/** @type {HTMLElement} */ (pane)).dataset.tabId);
      if (pageId) adoptPane(pageId);
    }
    syncCanvasPage();
  };
  contentObserver = new MutationObserver(sweep);
  contentObserver.observe(content, { childList: true });
  sweep();
  unsubs.push(() => { contentObserver?.disconnect(); contentObserver = null; });
}

// ══════════════════════════════════════════════════════════════════════
// canvas 独立页（作者令 2026-10-29 13:36：手机端 canvas 不做悬浮窗，独立
// 整页；页内卡片式呈现/切换多张 canvas 卡 —— 卡片列表 chip 条 + ‹ › 步进 +
// 内容区横滑切卡。桌面 canvas 行为零变化：pane re-parent，#canvas-panel
// 手机层永不显示（bottom sheet 退役）。
// ══════════════════════════════════════════════════════════════════════

/** 面板页签 id 集（文档页签 = 页签栏全集 − 本集）。 */
const PANEL_TAB_IDS = new Set(PAGES.filter((p) => p.tabId).map((p) => p.tabId));

const SWIPE_AXIS_PX = 8;
const SWIPE_SWITCH_PX = 48;

// ── 横向圆环标签栏（作者令 2026-11-02 07:22「手机端 canvas 顶部标签栏重做」）──
// 桌面语言 = hover 后上下展开的竖向圆环（canvas.js §胶囊环：缘位逐级缩径 +
// 减淡 + 弧深，|d|≥2 越出 = 读作绕到圈外）；手机端无 hover，映射为**常驻展开
// 的横向圆环**（竖向 → 横向的等价变换：「上下展」改为「左右展」，缩径 / 减淡 /
// 模糊三条深度语言沿用并改为连续曲线 —— 见下「对齐修正批」）。
//
// 对齐修正批（作者令 2026-11-02 09:00「不应该都是同一水平吗」）——根因实证：
// 签的垂直定位 = `top:40px` + `translateY(-50% + var(--rdy))`。`-50%` 量的是**未
// 缩放盒**的一半，而 `--rdy` 是环的垂直弧深 ⇒ 渲染盒中心 = 40 + rdy（|k|=1 时
// 40 + 11.1 = 51.1，与中签 40 差 11.1px，正是实测偏差）。缩放本身无责：
// transform-origin:center 下 scale 围绕盒中心对称，中心不动。
// 修法（几何源头，不做反向偏移补偿）：**删除纵向弧深**。环的「绕到圈后」只由
// 横向位移 + 缩径 + 连续减淡/模糊表达，签心恒 y=40（= 两悬浮钮中心线）。
//
// 几何（375 基线；环带左右各让 68px = 悬浮钮 48 + 边距 16 + 4 气隙）：
//   · 环带（.mf-strip）高 72px，overflow:visible + 双侧 mask 52 → 64px 渐隐
//     （浮钮右缘 64 起恒全实色 ⇒ 可见签不被误伤）；
//   · 签心恒 y = 40（= 两悬浮钮垂直中心）；scale 走 transform-origin:center ⇒
//     缩放后中心不动 ⇒ 全组纸签严格同一水平线（机械断言极差 ≤1px）；
//   · 中签 dx=0、基准宽 112px（CSS `.on` 档）、scale 1.14 ⇒ 渲染 127.7×36.5，
//     文字框 78px（= 112 − 2×16 内衬 − 2 边框；旧 58px）⇒ 有效字号 14.82px、
//     单行可见约 5.3 个汉字宽（旧 4.5）——「能显示稍微完整一些」；中签左缘
//     123.7，远在悬浮钮右缘 64 之外（不压钮）；
//   · 步距 RING_DX1 = 95px：邻签基准宽 68px、scale 0.80 ⇒ 渲染 54.4×25.6，
//     中心 187.5 ± 95 ⇒ 左邻左缘 65.3 ≥ 悬浮钮右缘 64（不压钮，余量 1.3px）；
//     邻签右缘 119.7 vs 中签左缘 123.7 ⇒ 缝隙 4.0px（**不重叠**）；
//   · |k|=2 = ±190px：被环带 mask 咬掉 + 曲线归零 = 读作已绕到圈外
//     （.is-far 零透明零命中）；
//   · 露出半径 RING_VISIBLE = 1 —— 环带净宽只容得下「中签 + 左右各一枚邻签」。
const RING_DX1 = 93.5;
const RING_VISIBLE = 1;
// 宽度档住 CSS（唯一视觉源）：两侧签 `.mf-strip-chip` max-width 66px、中签
// `.mf-strip-chip.on` max-width 112px —— JS 只给 scale，不改宽，两者相乘才是
// 渲染宽度（中签 112×1.10 = 123.2 / 邻签 66×0.87 = 57.4）。
// 连续曲线（作者令「越到边缘越模糊」+「不用…号」）：三条量全按 t = min(|k|,2)/2
// ∈ [0,1] 连续单调取值，不再分三档跳；k 取整数，故实际采样点 = |k| 0/1/2，
// 其余行只为证明曲线连续（拖动换签的过渡帧即落在这些 t 上）。
//   t   |k|   scale           opacity        blur 系数     rotY
//   0    0    1.100           1.000          0.00         0.0°
//   0.25 0.5  0.988           0.951          0.50         −12.0°
//   0.5  1    0.870           0.901          1.00         −24.0°  ← 邻签（唯一可见的非中签）
//   0.75 1.5  0.735           0.772          1.50         −36.0°
//   1    2    0.600           0.000          2.00         −48.0°  ← 与 .is-far 的无缝点
// 三条曲线（**可读字批 2026-11-03 12:59 作者令**重定 —— 「两边的标签尽量能清晰
// 显现」+「切换要有旋转感」）：
//   · scale 用 (1−t)^0.889：邻签 0.87（旧 0.80）—— 字号档 × 0.87 ≥13px 可读
//     下限（基档 --mfs-sub 15 ⇒ 13.05px 有效），|k|≥2 仍触底 0.60 保轨迹连续；
//   · op 用 (1−t)^0.15：邻签 0.901（旧 0.66 —— 作者判「近乎不可见」的根因面）——
//     指数 < 0.2 ⇒ 前段几乎平坦（邻签保持全可见），只在 |k| 1→2 的最后一段
//     陡降到 0，与 .is-far 无缝衔接（可见集恒 = |k| ≤ 1）；
//   · blur **系数** = min(|k|, 2)：本文件不再写模糊半径（方案 C 落地批 2026-11-06，
//     作者令「还有电脑端hover 上下选项的那种模糊效果」）。半径与饱和的唯一出生地
//     = design-system.css ⑯b 槽的 `--ds-ring-blur`（1.6px）/ `--ds-ring-saturate`
//     （1.25）—— 与桌面 hover 环 `.cr-capsule.is-edge` 同两枚令牌；本文件只写
//     「拿几档」这枚系数（|k|=1 恰拿满一档 = 桌面 hover 上下选项的同一档）。
//     旧档（MAX 2.40、指数 2.6 ⇒ |k|=1 只有 0.40px）退役：数值自拟 + 邻位被压到
//     1/4 档，正是作者实拍「手机端没有电脑端那种糊感」的根因。
//   · rotY = −24°·k：**转盘位姿**（见 layoutRing 的 --rrot）。环是横向圆环，
//     邻签绕圆心转到侧面 ⇒ 环绕 Y 轴转 24°（左邻右缘退到纸面里、右邻对称），
//     换签时 k 变动 ⇒ 位移 + 缩径 + 旋转三者同步插值 = 「转盘转过去」的动效
//     （纯淡入淡出被否 —— 作者令「不要纯淡入淡出」）。
const RING_SCALE_ON = 1.10;      // 中签缩径（旧 1.14：与邻签 0.87 的宽度差仍 >2×）
const RING_SCALE_MIN = 0.60;     // 环外下限（|k| ≥ 2）
const RING_SCALE_POW = 0.889;    // s = MIN + (ON − MIN)·(1−t)^P ⇒ |k|=1 恰 0.870
const RING_OP_POW = 0.15;        // op = (1−t)^P ⇒ |k|=1 0.901、|k|=2 恰 0
/** 环邻位模糊系数上限 = 2（|k| 钳到 2；1 = 拿满 `--ds-ring-blur` 那一档）。
 *  CSS 侧只做一次乘法（blur: calc(--ds-ring-blur × 本系数)）⇒ 半径值的家仍在令牌层。 */
const RING_BLUR_FACTOR_MAX = 2;
/** 邻签绕 Y 轴转角（转盘位姿；单位 °，符号 = 环形偏移 k 的负值）。 */
const RING_ROT_Y_DEG = 24;

// ── 底部小点指示器（作者令：1 个标签不显示 / 2 个起显示 / 最多 3 点）──────
// 省略策略（申报，批文档 §3「小点数量规则」）：n > 3 时点数**恒为 3**，点位 =
// 标签序号归一映射 bucket = round(idx × 2 / (n−1)) ∈ {0,1,2} —— 点本身不滑动
// （位置稳定、不随换签跳动），当前位置由「哪一点高亮放大」表达 = 首/中/末三段
// 各占一档（5 签 ⇒ 序号 0/1,2,3/4 分别落三点）。n ≤ 3 时一一对应。
const DOTS_MAX = 3;
// ── 底部三点手势区（作者令 2026-11-02 21:47 §二「三点区手势化」）──────────
// 判据换代：**去掉长按门槛**（旧 DOT_LONGPRESS_MS 260ms 放大档已退役）——三点
// 那一条就是一枚手势交互区，一上手即响应：
//   · 左右滑 = 上一张 / 下一张（切文档卡）；
//   · 上滑   = 进入堆叠视角；
//   · 下滑   = **关闭当前标签页**（作者 2026-11-02 21:54 裁定「下滑删除不需要
//              确认 只是关闭标签页而已 后果没那么严重」——语义 = 关标签、不是
//              删文档；零确认步；失败不乐观：只有手势过阈才调 closeTab，
//              关不掉（页签已不在/closeTab 拒）就等于没发生，UI 由 syncCanvasPage
//              按真实页签集重建，绝不先删 DOM 再祈祷）；
//   · 轻触     = 跳该点对应签（旧腿保留，同时是「非手势可点等价入口」之一）。
// 阈值（实测调优读数见批文档 §I4/I5/I6/I7）：轴锁 6px（touch-action:none 下
// 不需要大的死区，但要压住点按抖动）；横向提交 = 位移 ≥26px **或** 位移 ≥14px
// 且末段速度 ≥0.35px/ms（快弹腿，短促轻扫也换页）；纵向提交 = 22px。纵向阈值
// 比横向小：纵向只有「开堆叠 / 关标签」两个明确意图，且要与横向互斥判定。
const DOT_AXIS_PX = 6;          // 轴锁死区（内 = 点按）
const DOT_SWIPE_X_PX = 26;      // 横向位移阈（换页）
const DOT_FLICK_X_PX = 14;      // 快弹位移下限（配合速度阈）
const DOT_FLICK_V_PX_MS = 0.35; // 快弹速度阈（px/ms）
const DOT_SWIPE_Y_PX = 22;      // 纵向位移阈（上滑开堆叠 / 下滑关标签）
// 点按容差 = 上面的轴锁死区本身（未过 DOT_AXIS_PX 即「没有位移」，等同点按）；
// 独立常量退役 —— 两个 6px 并存的读数口径只会互相打架。

// ── 堆叠视角（iOS app switcher 形态；作者令 2026-11-02 21:47 §三）───────
// 形态 = **横向卡列**（旧「纵向卡叠」退役）：中央卡最大最清晰，左右相邻卡
// 被裁切只露出一部分、越靠边越暗越模糊，背景重度模糊。位姿变量单源在本文件
// （layoutDeck 写 --dtx/--dsc/--dop/--dblur），CSS 只消费。
const DECK_CARD_W_RATIO = 0.78;  // 中央卡宽 = 舞台宽 × 0.78（iOS 观感档；高
                                 // 由 CSS 侧 min(74%, 520px) 承担 —— 高不参与 JS 几何）
const DECK_SIDE_STEP_RATIO = 0.66;  // 邻卡中心距 = 卡宽 × 0.66（露约 1/3 卡身）
const DECK_SCALE_SIDE = 0.84;    // 邻卡缩径
const DECK_SCALE_FAR = 0.72;     // 第二邻卡缩径
const DECK_SWITCH_PX = 56;       // 横拖过阈即换中央卡（松手吸附）
const DECK_AXIS_PX = 8;          // 轴锁死区（内 = 点按）
const DECK_SWIPE_Y_PX = 48;      // 纵拖过阈：上滑收面回阅读 / 下滑关当前标签

// ── 手势仲裁（内容操作优先，作者令 2026-11-01「拖动图谱、调整图片大小这类
//    内容操作，会和左右滑动切换卡片的手势冲突……内容区起手的横滑归内容操作，
//    不触发切卡；切卡手势限定在非交互内容区」）────────────────────────
/** 内容手势面选择器：这些面上起手的指针序列归内容操作（拖图/调尺寸/拖图谱/
 *  流程图平移），切卡手势**永不接管**，也不许 host 抢 pointer capture（修前
 *  capture 把 viewport 自己的 pointermove/pointerup 拦走 —— 图片抓手开了也
 *  拖不动 + .panning 类泄漏）。逃逸口 = [data-nf-content-gesture] 标记。
 *  判据走 closest 委托：内容面内的子元素（img/文字层）同享仲裁。 */
const CONTENT_GESTURE_SELECTOR =
  '.canvas-image-viewport, .wiki-canvas, .fm-viewport, [data-nf-content-gesture]';

/** 边缘返回手势（bindEdgeBack）认领横滑后的抑制旗：bindDocSwipe 在 move/finish
 *  查它 —— 认领期内切卡手势整体让路（同模块直读，零 DOM 事件戏法）。 */
let swipeSuppressed = false;

/** @type {{strip: HTMLElement, toolbar: HTMLElement, host: HTMLElement,
 *          empty: HTMLElement, dots: HTMLElement}|null} */
let canvasUI = null;
/** @type {{pane: Element, parent: Node, next: Node|null}|null} 活动文档 pane 收编槽（单槽）。 */
let canvasDocAnchor = null;

/** 文档页签全集（不按 pane 落点过滤 —— 活动 pane 可能已收编进页面体）。
 *  @returns {{id: string, label: string}[]} */
export function docTabs() {
  const bar = document.getElementById('canvas-tab-bar');
  if (!bar) return [];
  const out = [];
  for (const el of bar.querySelectorAll('.canvas-tab')) {
    const id = (/** @type {HTMLElement} */ (el)).dataset.tabId;
    if (!id || PANEL_TAB_IDS.has(id)) continue;
    out.push({ id, label: el.querySelector('.canvas-tab-label')?.textContent || id });
  }
  return out;
}

export function hasDocTabs() { return docTabs().length > 0; }

export function activeDocId() {
  const el = /** @type {HTMLElement|null} */ (document.querySelector('#canvas-tab-bar .canvas-tab.active'));
  const id = el ? el.dataset.tabId : null;
  return id && !PANEL_TAB_IDS.has(id) ? id : null;
}

/** 文档卡邻位切换（环绕；单卡不换）。@param {number} delta +1 下一张 / -1 上一张 */
export function switchDocCard(delta) {
  const docs = docTabs();
  const id = activeDocId();
  const idx = docs.findIndex((d) => d.id === id);
  if (docs.length < 2 || idx < 0) return;
  const nb = docs[(idx + delta + docs.length) % docs.length];
  if (nb && nb.id !== id) setActiveTab(nb.id);
}

/** 活动文档 pane 收编进 canvas 页内容区（单槽锚点；换卡即归旧纳新）。
 *  双页叠开修复批①（2026-10-31 作者令 23:29 截图实证「两个标签页上下叠着」）：
 *  修前只靠单锚点归还旧 pane——锚的邻位引用（a.next）若已被摘出画板内容区
 *  （邻卡被关等树变），insertBefore 抛错被吞、锚又已清空 ⇒ 旧 pane 永久滞留
 *  页体；再收编新 pane 后 host 内双 pane，叠加手机层 pane 通配 display:flex
 *  !important（压掉桌面 .active 显隐），两页同屏上下叠开 = 截图 bug。修复三件：
 *  ⑴ 归还腿 insertBefore 失败回退 appendChild（pane 可见性由 .active 驱动，
 *  内容区内位置无语义，追加零副作用）；⑵ 收编前扫口：host 内一切非当前活动
 *  pane（孤儿/历史滞留）一律送回画板内容区，单 pane 不变式机械保证；⑶ 离页
 *  即归还（closePageFace 'canvas'，头注「canvas 页离开 = 面归还」的兑现）。 */
function releaseDocPane(stale, home) {
  // 横滑跟手可能残留的内联位姿一并清掉（换卡由 adopt 归位，滞留 pane 无腿清）。
  stale.style.transition = '';
  stale.style.transform = '';
  stale.style.opacity = '';
  if (home && home.isConnected) home.appendChild(stale);
}

function adoptActiveDoc() {
  const id = activeDocId();
  const pane = id ? getTabPane(id) : null;
  const host = canvasUI?.host;
  if (!host) return;
  // ⑵ 收口扫口：host 内非当前活动 pane 全数归还（锚内 pane 交下方归还腿处理）。
  const home = document.getElementById('canvas-content');
  for (const stale of host.querySelectorAll(':scope > .canvas-tab-pane')) {
    if (pane && stale === pane) continue;
    if (canvasDocAnchor && canvasDocAnchor.pane === stale) continue;
    releaseDocPane(stale, home);
  }
  if (canvasDocAnchor && (!pane || canvasDocAnchor.pane !== pane)) {
    // 换卡/关卡：旧 pane 原位归还画板内容区。
    const a = canvasDocAnchor;
    canvasDocAnchor = null;
    if (a.pane.isConnected && a.parent && a.parent.isConnected) {
      try { a.parent.insertBefore(a.pane, a.next); } catch { releaseDocPane(a.pane, home); }
    }
  }
  if (pane && pane.parentElement !== host) {
    canvasDocAnchor = { pane, parent: pane.parentElement, next: pane.nextSibling };
    logEvent('canvas-page-adopt', { tab: id });
    host.appendChild(pane);
  }
}

// ══ 横向圆环标签栏（作者令 2026-11-02 07:22；对齐修正批 2026-11-02 09:00）══
// 环位姿 = 唯一几何源（ringPose）：chip 的 dx/scale/opacity/blur 四值全由环形
// 距离 k 派生，CSS 只消费变量不另算 —— 换签即换 k，四值同步过渡 = 环在转。
// **无纵向量（无 dy / 无 --rdy）**：对齐修正批删掉了弧深，签心恒 y=40，全组纸签
// 同一水平线；环的「绕到圈后」只由横向位移 + 缩径 + 连续减淡/模糊表达。
// 三条量都是 |k| 的连续单调函数（不再分档跳），|k| ≥ 2 时 s 触底、op 归零 ⇒
// 与 .is-far（零命中）无缝衔接；>2 仍参与计算，换签时轨迹连续而不是瞬切。

/** 环内最短偏移（i 相对活动位；n=1 恒 0）。@returns {number} */
function ringOffset(d, n) {
  if (n < 2) return 0;
  let k = ((d % n) + n) % n;      // 0..n-1
  if (k > n / 2) k -= n;          // (−n/2, n/2]
  return k;
}

/** chip 位姿四值（k → dx/scale/opacity/blur 系数）。三条曲线均按
 *  t = min(|k|,2)/2 ∈ [0,1] 连续单调；数值表见文件头 RING_* 常量注释。
 *  `blurf` 是**系数**不是半径（半径 = `--ds-ring-blur` × 本系数，配方在令牌层）。
 *  @returns {{dx: number, s: number, op: number, blurf: number}} */
function ringPose(k) {
  const t = Math.min(Math.abs(k), 2) / 2;
  return {
    dx: RING_DX1 * k,
    s: RING_SCALE_MIN + (RING_SCALE_ON - RING_SCALE_MIN) * (1 - t) ** RING_SCALE_POW,
    op: (1 - t) ** RING_OP_POW,
    blurf: Math.min(Math.abs(k), RING_BLUR_FACTOR_MAX),
  };
}

/** 环重排（不发 DOM 重建）：按当前活动位给每个 chip 写变量 + on/far 类。
 *  重建仅由签名闸驱动（syncCanvasPage）——本函数只做位姿对账，切签零建删。
 *  中签基准宽 112px（CSS `.on` 档 `max-width` 消费），两侧签 68px 窄一档 ——
 *  两者差只体现在**宽度**上：圆角/玻璃底/描边/字重/字号全同源（作者令
 *  「样式应该要是和两边的统一」，机械断言 T13③ 钉死）。 */
function layoutRing() {
  const strip = canvasUI?.strip;
  if (!strip) return;
  const docs = docTabs();
  const ai = docs.findIndex((d) => d.id === activeDocId());
  for (const chip of strip.querySelectorAll('.mf-strip-chip')) {
    const el = /** @type {HTMLElement} */ (chip);
    const i = docs.findIndex((d) => d.id === el.dataset.tabId);
    if (i < 0) continue;
    const k = ringOffset(i - (ai < 0 ? 0 : ai), docs.length);
    const p = ringPose(k);
    el.style.setProperty('--rdx', `${p.dx.toFixed(1)}px`);
    el.style.setProperty('--rsc', p.s.toFixed(3));
    el.style.setProperty('--rop', p.op.toFixed(2));
    /* 环邻位模糊 = 系数（方案 C 落地批 2026-11-06）：半径与饱和由 CSS 取
       `--ds-ring-blur` / `--ds-ring-saturate`（与桌面 hover 上下选项同两枚令牌），
       本文件只写「拿几档」。--rbl（半径字面量）同批退役。 */
    el.style.setProperty('--rblf', p.blurf.toFixed(2));
    /* 转盘位姿（可读字批 2026-11-03）：邻签绕 Y 轴 −24°×k —— 环是横向圆环，
       邻签绕到圆心侧面 ⇒ 左邻右缘退进纸面、右邻对称；换签时 k 变 ⇒ 位移 +
       缩径 + 旋转同步插值（CSS transform 过渡）＝「转盘转过去」。中签 k=0
       恒 0°（正面朝向读者，不受透视变形 ⇒ 文字恒正）。 */
    el.style.setProperty('--rrot', `${(-RING_ROT_Y_DEG * k).toFixed(1)}deg`);
    el.classList.toggle('on', k === 0);
    el.classList.toggle('is-far', Math.abs(k) > RING_VISIBLE);
    /* 环方向类（k 的符号）：保留为**环位语义**（「这枚签绕到了哪一侧」），判据与
       手势腿都读它。**水平渐隐的 CSS 消费已整条退役**（方案 C 落地批 2026-11-06，
       作者令「文字两边不要渐隐」）—— 旧档 `--rfade` 宽度同步停写：化开这件事不再
       存在，留着它只会让下一个人以为还有一条水平遮罩在跑。 */
    el.classList.toggle('side-l', k < 0);
    el.classList.toggle('side-r', k > 0);
    el.setAttribute('aria-selected', k === 0 ? 'true' : 'false');
    el.tabIndex = Math.abs(k) <= 1 ? 0 : -1;
  }
  // 环带渐隐门控退役（方案 C 落地批）：旧档 2 签起给带子挂 `.fade`（双侧 14px
  // 羽化 mask）。水平渐隐整族退役后 `.fade` 无消费面 ⇒ 不再挂类（带子恒无 mask）。
  fitRingLabels();
}

// ══ 中签整字收束（作者令 2026-11-02 11:23「前置显示的那个标签，两端不要有虚化
// 渐隐」）════════════════════════════════════════════════════════════
// 上一批给所有签的 label 加了水平 alpha 渐隐（`.mf-strip-label` 的 mask-image）
// 来替代「…」。本令只撤**中签**那一段（CSS `.mf-strip-chip.on .mf-strip-label`
// 的 `mask-image: none`），两侧邻签的渐隐原样保留。
//
// 撤掉 mask 之后，超宽文字若只靠 `overflow: hidden` 硬裁，右端会**切半个字**——
// 比渐隐更难看。故本函数把中签 label 的 `max-width` 收到**「能完整放下的最大整字
// 前缀」**上：截断点永远落在字界（grapheme cluster，中文一字、emoji 一簇、拉丁
// 一个字母），一个完整字都不缺省、也绝不出现半个字，**不出省略号、不溢出签盒**。
// 多出来的部分照既有展开腿承载（点活签 → `.is-expanded` 加宽一档 → 2600ms 自动
// 收）；全名另有两处保底（label.textContent 全名 + chip.title），收束只压绘制宽。
//
//   实测（375 基线，中签 scale 1.14）：常态 label 可用宽 78px ⇒ 整 6 个汉字；
//   展开档可用宽 168.6px ⇒ 整 12 个汉字（= 展开腿在 48px 浮钮钳制下的几何上限）。
//
// 只在**中签**上生效：非中签的 label 每次重排都清掉内联 max-width，回到「由
// max-width 收紧 + mask 渐隐」的原始行为 ⇒ 邻签视觉零变化。
//
// 度量腿 = 隐藏探针 span（字体从 label 抄）+ 签的**内衬内容宽**（= 计算 max-width
// − 左右内衬 − 左右描边，CSS px）：探针不受 chip 的 `scale(--rsc)` 与 flex 收紧
// 影响 ⇒ 与 `max-width` 同一度量面。签宽是 auto（贴合内容收缩），故「可用宽」取
// 签的上限内容宽，而不是 label 的当前宽（后者会被文字长短反向污染）。
const FIT_EPS_PX = 0.05;        // 亚像素容差（advance 边界处的侧承，非墨迹）

/** 按 grapheme cluster 切分（中文一字 / emoji 一簇 / 拉丁一字母）。 */
function graphemes(text) {
  if (typeof Intl !== 'undefined' && Intl.Segmenter) {
    return [...new Intl.Segmenter(undefined, { granularity: 'grapheme' }).segment(text)]
      .map((s) => s.segment);
  }
  return Array.from(text);
}

/** 度量用的隐藏探针（body 直属：不受签名 flex 收紧、不受 chip 的 scale 影响 ——
 *  量到的是**未缩放 CSS px**，正好是 max-width 的度量面）。字体样式从 label 抄。 */
let fitProbe = null;
function fitProbeEl(label) {
  if (!fitProbe) {
    fitProbe = document.createElement('span');
    fitProbe.setAttribute('aria-hidden', 'true');
    fitProbe.style.cssText = 'position:absolute;left:-9999px;top:0;visibility:hidden;white-space:nowrap;pointer-events:none;contain:layout style;';
    document.body.appendChild(fitProbe);
  }
  const cs = getComputedStyle(label);
  fitProbe.style.font = cs.font;
  fitProbe.style.letterSpacing = cs.letterSpacing;
  fitProbe.style.wordSpacing = cs.wordSpacing;
  fitProbe.style.textTransform = cs.textTransform;
  return fitProbe;
}

/** 签的**目标档**内衬内容宽（CSS px，= max-width − 左右内衬 − 左右描边）。
 *  **不能直接读计算 max-width**：它带 220ms 过渡，切签瞬间读到的是过渡中的中间值
 *  （实测 34px ⇒ 中签只留 2 个字）。故先把该签的 `transition` 临时掐掉读一次目标值
 *  再复原（同一任务内改回 ⇒ 不触发重绘，无视觉抖动、无额外 DOM 节点）。
 *  **不造探针元素**：任何带 `.mf-strip-chip` 的离屏节点都会污染
 *  `querySelectorAll('.mf-strip-chip')` 的既有判据面（T13 / 手势腿 / 小点对账）。
 *  @returns {number} */
function chipInnerWidth(chip) {
  /* 陷阱（方案 C 落地批实测，别再加回来）：旧档为了拿**目标档** max-width 会先
     `chip.style.transition = 'none'`、读一次计算值、再复原。收束面从「只收中签」
     扩到「全组」以后，这一手会落在**正在切签的那几枚签**身上 —— 掐过渡的那次强制
     重算把**终点位姿**提前提交，整段切签动画就静默消失（实测：L3③ 无任何
     transitionend、L5 连拍零中间帧，且不影响任何静态判据 ⇒ 只有动的腿才看得见）。
     故此处只读不算：切签瞬间可能读到过渡起点档（比目标窄一档），稳态由下面
     `transitionend` 自校补齐。 */
  const cs = getComputedStyle(chip);
  const cap = parseFloat(cs.maxWidth);
  return Number.isFinite(cap)
    ? cap - (parseFloat(cs.paddingLeft) || 0) - (parseFloat(cs.paddingRight) || 0)
      - (parseFloat(cs.borderLeftWidth) || 0) - (parseFloat(cs.borderRightWidth) || 0)
    : 0;
}

/** 把某枚签的 label 收到整字边界（幂等：先回原始态再量，收束只压绘制宽）。 */
function fitChipLabel(chip) {
  const label = /** @type {HTMLElement|null} */ (chip.querySelector('.mf-strip-label'));
  if (!label) return;
  label.style.maxWidth = '';                    // 回原始态：量到的是「未收束」的真实排版
  const avail = chipInnerWidth(chip);           // 签能容纳的文本上限（CSS px）
  if (avail <= 0) return;
  const chars = graphemes(label.textContent || '');
  if (!chars.length) return;
  const probe = fitProbeEl(label);
  probe.textContent = chars.join('');
  if (probe.getBoundingClientRect().width <= avail - FIT_EPS_PX) return;   // 全文放得下：不收
  let keep = 0;
  let keepW = 0;
  for (let i = 1; i < chars.length; i++) {
    probe.textContent = chars.slice(0, i).join('');
    const w = probe.getBoundingClientRect().width;
    if (w > avail - FIT_EPS_PX) break;          // 再放一个字就压进内衬 ⇒ 停在字界
    keep = i;
    keepW = w;
  }
  if (!keep) return;                            // 一个字都放不下（病态窄）：让 clip 兜底
  // 向上取整到 0.01：收到字界的那一刻不能比最后一个字的 advance 窄（窄 0.4px 就会
  // 削掉末字右缘的笔画 —— 那正是本批要消灭的「硬裁半个字」）。
  label.style.maxWidth = `${Math.ceil(keepW * 100) / 100}px`;
}

/** 全体签的收束对账（方案 C 落地批 2026-11-06：**全组生效**，不再只收中签）。
 *  理由 = 水平渐隐整族退役（作者令「文字两边不要渐隐」）—— 旧档邻签超宽时靠
 *  方向 mask 化开兜底，mask 一去，若仍只收中签，邻签右端就会**硬裁半个字**。
 *  改为每枚签都把 label 收到字界（不出现半个字、不出省略号、不越签盒），
 *  代价是邻签可能比旧档少显示一个字（旧档那一个字本来也是半透明的）。 */
function fitRingLabels() {
  const strip = canvasUI?.strip;
  if (!strip) return;
  for (const chip of strip.querySelectorAll('.mf-strip-chip')) {
    const el = /** @type {HTMLElement} */ (chip);
    if (!el.querySelector('.mf-strip-label')) continue;
    fitChipLabel(el);
  }
}

/** 切到第 idx 签（环绕安全；同签零动作）。@param {number} idx */
function switchToIndex(idx) {
  const docs = docTabs();
  if (!docs.length) return;
  const i = Math.min(docs.length - 1, Math.max(0, Math.round(idx)));
  const d = docs[i];
  if (!d || d.id === activeDocId()) return;
  logEvent('card-switch', { via: 'ring', tab: d.id });
  setActiveTab(d.id);
}

/** canvas 页同步：环标签重建（签名闸）+ 工具条态 + 小点指示器 + pane 收编 + 空态。 */
function syncCanvasPage() {
  if (!canvasUI) return;
  const docs = docTabs();
  const activeId = activeDocId();
  const sig = docs.map((d) => d.id).join('|');
  if (canvasUI.strip.dataset.sig !== sig) {
    canvasUI.strip.dataset.sig = sig;
    canvasUI.strip.innerHTML = '';
    for (const d of docs) {
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.className = 'mf-strip-chip';
      chip.dataset.tabId = d.id;
      chip.setAttribute('role', 'tab');
      const label = document.createElement('span');
      label.className = 'mf-strip-label';
      label.textContent = d.label;
      chip.appendChild(label);
      const quote = document.createElement('span');
      quote.className = 'mf-strip-quote';
      quote.setAttribute('role', 'button');
      quote.setAttribute('tabindex', '0');
      quote.setAttribute('aria-label', '引用此 Canvas');
      quote.title = '引用此 Canvas';
      quote.textContent = '↗';
      quote.addEventListener('click', (event) => {
        event.stopPropagation();
        setActiveTab(d.id);
        goHome();
        quoteActiveCardRef();
      });
      quote.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          event.stopPropagation();
          setActiveTab(d.id);
          goHome();
          quoteActiveCardRef();
        }
      });
      chip.appendChild(quote);
      // 全名三处保留（对齐修正批：视觉上不再有「…」，但读屏/悬停仍拿得到全名）：
      //   ① label.textContent = 全名（读屏直接读 DOM 文本，mask 只影响绘制）；
      //   ② chip.title = 全名（桌面悬停 / 长按提示）；
      //   ③ 触屏展开腿 = 点活签 `.is-expanded`（宽度解一档，超宽继续 mask 淡出）。
      chip.title = d.label;
      chip.addEventListener('click', () => {
        if (chip.classList.contains('on')) {
          // 活签再点 = 展开/收起全名（长标签专利；其余签点击一律换签）。
          const open = !chip.classList.contains('is-expanded');
          chip.classList.toggle('is-expanded', open);
          fitRingLabels();          // 展开档另一档宽度 ⇒ 重新按整字收束（全名放得下就全显）
          logEvent('tab-label-expand', { tab: d.id, open });
          if (open) armLabelCollapse(chip);
          return;
        }
        logEvent('card-switch', { via: 'strip', tab: d.id });
        setActiveTab(d.id);
      });
      canvasUI.strip.appendChild(chip);
    }
  }
  // 活签的全名展开态不跨切签存活（换签即收，防宽签压邻签）。
  for (const chip of canvasUI.strip.querySelectorAll('.mf-strip-chip.is-expanded')) {
    if ((/** @type {HTMLElement} */ (chip)).dataset.tabId !== activeId) chip.classList.remove('is-expanded');
  }
  layoutRing();
  syncDots();
  // 位置读数仍留 sr-only live 区（小点是纯视觉，读屏靠这行播报「第几/共几」）。
  const idx = docs.findIndex((d) => d.id === activeId);
  const cnt = /** @type {HTMLElement|null} */ (canvasUI.toolbar.querySelector('.mf-cv-count'));
  if (cnt) cnt.textContent = docs.length > 1 && idx >= 0 ? `${idx + 1}/${docs.length}` : '';
  // Canvas 改造批②⑤（2026-10-31 作者令）：工具条 = 左叉号 + 右堆叠视角钮
  // （计数改 sr-only；档位读数下沉到底部小点指示器）。
  const stackBtn = /** @type {HTMLButtonElement|null} */ (canvasUI.toolbar.querySelector('.mf-cv-stack'));
  if (stackBtn) stackBtn.disabled = docs.length === 0;
  adoptActiveDoc();
  canvasUI.empty.hidden = docs.length > 0;
  canvasUI.strip.hidden = docs.length === 0;
  canvasUI.toolbar.hidden = docs.length === 0;
  canvasUI.host.hidden = docs.length === 0;
  if (stackOpen) syncDeck();
}

/** 全名展开的自动收起定时（同一时刻至多一枚活签展开）。@type {number} */
let labelCollapseTimer = 0;
function armLabelCollapse(chip) {
  clearTimeout(labelCollapseTimer);
  labelCollapseTimer = window.setTimeout(() => {
    chip.classList.remove('is-expanded');
    fitRingLabels();     // 收回常态宽度 ⇒ 重按整字收束
  }, 2600);
}

// ══ 底部小点指示器（作者令 2026-11-02 07:22 §三）════════════════════
// 数量规则：n = 1 不显示；n ≥ 2 显示且最多 3 点（DOTS_MAX）。n > 3 时点**不滑动**
// ——点数恒 3，序号归一映射到 0/1/2 三档，当前位置由「高亮点」表达（申报口径）。
// 交互：见下方 bindDots 头注（长按门槛已退役，一上手即响应）。

/** 标签序号 → 点位（n ≤ 3 一一对应；n > 3 三段归一）。@returns {number} */
function dotBucket(idx, n) {
  if (n <= DOTS_MAX) return Math.max(0, idx);
  return Math.round((idx * (DOTS_MAX - 1)) / (n - 1));
}

/** 点位 → 该点的代表签序号（n > 3 时取该段首签；n ≤ 3 即本签）。@returns {number} */
function dotIndex(bucket, n) {
  if (n <= DOTS_MAX) return Math.min(bucket, n - 1);
  return Math.round((bucket * (n - 1)) / (DOTS_MAX - 1));
}

/** 小点重绘（点数 + 高亮；点数变化才重建 DOM，其余只改类）。 */
function syncDots() {
  const dotsEl = canvasUI?.dots;
  if (!dotsEl) return;
  const docs = docTabs();
  const n = docs.length;
  const ai = Math.max(0, docs.findIndex((d) => d.id === activeDocId()));
  const count = n < 2 ? 0 : Math.min(DOTS_MAX, n);
  dotsEl.hidden = count === 0;
  dotsEl.setAttribute('aria-hidden', count === 0 ? 'true' : 'false');
  if (Number(dotsEl.dataset.count) !== count) {
    dotsEl.dataset.count = String(count);
    dotsEl.innerHTML = '';
    for (let i = 0; i < count; i++) {
      const dot = document.createElement('span');
      dot.className = 'mf-dot';
      dotsEl.appendChild(dot);
    }
  }
  const on = dotBucket(ai, n);
  [...dotsEl.querySelectorAll('.mf-dot')].forEach((el, i) => el.classList.toggle('on', i === on));
  dotsEl.dataset.n = String(n);
  dotsEl.dataset.idx = String(ai);
}

/** 触感反馈（有 vibrate API 才响；reduced-motion 不影响触感，仅关动效）。 */
function hapticTap(ms = 8) {
  try { navigator.vibrate?.(ms); } catch { /* 无 API / 被策略拒 */ }
}

/** 小点手势区（作者令 2026-11-02 21:47 §二「三点区手势化……不用长按才响应」）：
 *  一上手即响应 —— 左右滑换签（快弹/位移双判据）、上滑进堆叠、下滑关标签
 *  （作者 21:54 裁定：关标签零确认）、轻触点跳签。阈值常量见上方 DOT_* 注释。 */
function bindDots(dotsEl) {
  /** @type {{id: number, x0: number, y0: number, t0: number, lx: number, lt: number,
   *          vx: number, axis: string|null, bucket: number}|null} */
  let g = null;
  const endGrip = () => dotsEl.classList.remove('is-grip');
  dotsEl.addEventListener('pointerdown', (e) => {
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    const docs = docTabs();
    if (!docs.length) return;      // 无文档卡 = 手势区无对象（空态另有空态面）
    e.stopPropagation();          // 不惊动页面壳的边缘返回/切卡手势
    // 点按落点必须**下手时**取：setPointerCapture 会把后续 pointer 事件整体
    // 重定向到采集元素（pointerup.target 恒为 dotsEl，closest('.mf-dot') 恒
    // null ⇒ 轻触跳签整条腿失效 —— 探针实证）。
    // 其次，胶囊的 44px 热区是 ::before 伪元素（纵向把 26px 本体外扩），伪元素
    // 参与父元素的命中 ⇒ 落在点上方的指针 target 是 .mf-dots 本身（closest 拿
    // 不到点）。故 target 拿不到点时按**横坐标几何**兜底：取水平覆盖 x 的点。
    const hitEl = /** @type {Element|null} */ (e.target instanceof Element ? e.target.closest('.mf-dot') : null);
    const dots = [...dotsEl.querySelectorAll('.mf-dot')];
    const bucket0 = hitEl ? dots.indexOf(hitEl) : dots.findIndex((d) => {
      const r = d.getBoundingClientRect();
      return e.clientX >= r.left - 3 && e.clientX <= r.right + 3;
    });
    g = {
      id: e.pointerId, x0: e.clientX, y0: e.clientY, t0: performance.now(),
      lx: e.clientX, lt: performance.now(), vx: 0,
      axis: null, bucket: bucket0,
    };
    try { dotsEl.setPointerCapture(e.pointerId); } catch { /* 合成事件无 capture */ }
  });
  dotsEl.addEventListener('pointermove', (e) => {
    if (!g || e.pointerId !== g.id) return;
    const dx = e.clientX - g.x0;
    const dy = e.clientY - g.y0;
    // 末段速度 EMA（快弹判据用；普通位移腿不依赖它）。
    const dt = Math.max(1, performance.now() - g.lt);
    g.vx = 0.6 * g.vx + 0.4 * ((e.clientX - g.lx) / dt);
    g.lx = e.clientX;
    g.lt = performance.now();
    if (!g.axis && (Math.abs(dx) > DOT_AXIS_PX || Math.abs(dy) > DOT_AXIS_PX)) {
      g.axis = Math.abs(dx) >= Math.abs(dy) ? 'x' : 'y';
      // 横划即放大态（不等长按 —— 「抓住了」的读感保留，零等待）。
      if (g.axis === 'x') dotsEl.classList.add('is-grip');
    }
    if (g.axis !== 'x') return;
    // 横划**不在拖动中逐签换**（一次滑 = 一张，松手提交）：早期实现按
    // dx/26px 步距在「下手时基准」上连跳，52px 的快扫会一次翻两张 —— 与
    // 「左右滑 = 上一张 / 下一张」的语义不符（探针实证：52px 滑出落在两张
    // 之后）。拖动中只维持放大态（is-grip）作「抓住了」的临界反馈。
  });
  const finish = (e, cancelled) => {
    if (!g || e.pointerId !== g.id) return;
    const { axis, bucket, vx } = g;
    const dx = e.clientX - g.x0;
    const dy = e.clientY - g.y0;
    g = null;
    endGrip();
    if (cancelled) return;
    // 轻触（未过轴锁）：落点小点 = 跳到该点对应签。
    if (!axis) {
      if (bucket >= 0) {
        switchToIndex(dotIndex(bucket, docTabs().length));
        hapticTap(6);
      }
      return;
    }
    if (axis === 'x') {
      // 横向提交 = 位移阈 或 快弹（短位移 + 末段速度）。
      const hit = Math.abs(dx) >= DOT_SWIPE_X_PX ||
        (Math.abs(dx) >= DOT_FLICK_X_PX && Math.abs(vx) >= DOT_FLICK_V_PX_MS);
      if (hit && docTabs().length > 1) {
        logEvent('dots-swipe-x', { dx: Math.round(dx), dir: dx < 0 ? 1 : -1 });
        switchDocCard(dx < 0 ? 1 : -1);
        hapticTap(6);
      }
      return;
    }
    // 纵向：上滑进堆叠 / 下滑关当前标签（关标签 = closeTab 一发，零确认 ——
    // 作者 21:54 裁定「只是关闭标签页而已」；语义 = 关页签不是删文档）。
    if (dy <= -DOT_SWIPE_Y_PX) {
      if (!stackOpen) {
        logEvent('dots-swipe-up', { dy: Math.round(dy) });
        toggleDeck(true);
        hapticTap(8);
      }
    } else if (dy >= DOT_SWIPE_Y_PX) {
      logEvent('dots-swipe-down', { dy: Math.round(dy) });
      closeCard();
      hapticTap(12);
    }
  };
  dotsEl.addEventListener('pointerup', (e) => finish(e, false));
  dotsEl.addEventListener('pointercancel', (e) => finish(e, true));
  // 触屏长按的系统 contextmenu 一律吞掉（手势区自己的语义，不弹原生菜单）。
  dotsEl.addEventListener('contextmenu', (e) => e.preventDefault());
}

// ══ 堆叠视角（iOS app switcher 形态；作者令 2026-11-02 21:47 §三）════════
// 以作者附图（iPhone app switcher 实拍）为设计基准：
//   · **中央卡最大最清晰**，左右相邻卡**被裁切只露一部分**（横列，露约 1/3 卡身）；
//   · **越往边越暗**（邻卡 brightness 压暗 + 减淡 + 模糊，连续曲线）；
//   · **背景重度模糊**（浮层 backdrop blur 重档，阅读面在背后化开）；
//   · **每张卡有真实内容快照**（不是空白框也不是标题占位）——快照管线**单源复用
//     桌面 canvas.js 的 deckSnapshotNode 内核**（md/代码/html/图片/pdf 各自的
//     真渲染面克隆），本文件只做缓存与挂载；内容指纹（canvasSnapshotStamp）
//     命中即不重截，「内容变了再重截」由指纹失配兜底 —— 作者令「同一张卡不
//     重复截图，内容变了再重截」。
//   · 卡上方有可辨识标识（类型图标 + 标题 + ✕，参考图的「图标 + 名字」档）。
// 交互（与阅读面语义连续）：
//   · 左右拖 = 换中央卡（跟手 + 松手吸附/回弹）；
//   · 点中央卡 = 收面回阅读；点邻卡 = 换入中央；点 ✕ = 关该标签；
//   · 纵拖下滑 = 关当前标签（零确认，作者 21:54 裁定）；上滑 = 收面回阅读；
//   · 显式出口 = 头行 ✕ / Esc（可点等价入口，Apple HIG 硬要求）。
const DECK_VISIBLE = 2;

/** 堆叠卡视图开关态。 */
let stackOpen = false;
/** @type {{view: HTMLElement, stage: HTMLElement, count: HTMLElement,
 *          cards: Map<string, HTMLElement>}|null} */
let deckUI = null;
/** 拖动中的临时位移（px）：正值方向由轴决定 —— 横拖 = 卡列平移（换中央卡），
 *  纵拖 = 中央卡下坠（关闭预览）/ 整列上移（收面预览）。 */
let deckDragDx = 0;
let deckDragDy = 0;
/** 卡内 ✕ / 点卡换入的 pointerup 抑制窗（跟手拖过后松手不触发 click）。 */
let deckDragEndedAt = 0;

// ── 快照缓存（作者令「同一张卡不重复截图，内容变了再重截」）────────────
// tabId → { node, srcW, kind, stamp }。stamp = canvas.js canvasSnapshotStamp()
// 的内容指纹：命中即挂缓存节点（零重截）；失配（内容/类型变了）才走重截。
// 缓存不随堆叠面关闭清空（卡在、快照在），由指纹失配驱动刷新 —— 面每次开合
// 都重截的话，连开连关就是纯浪费（作者令的另一半「同一张卡不重复截图」）。
/** @type {Map<string, {node: Element, srcW: number, kind: string, stamp: string}>} */
const deckSnapCache = new Map();
/** 挂载中标记（防同卡并发重截；rAF 批处理用）。 */
const deckSnapPending = new Set();
/** 快照构建读数探针（性能验收 I9 用；window 只读）。 */
let deckSnapLastMs = 0;
/** 累计真建次数（缓存命中不计数）—— 「内容变了才重截」的机械判据。 */
let deckSnapBuilds = 0;
/** 单次构建的最长耗时（掉帧面读数）。 */
let deckSnapMaxMs = 0;

/** 建一张卡的快照并挂进 thumb 槽（mock 先在，真快照好了就位替换 —— 不闪白）。
 *  失败（不可克隆腿）= 留 mock，不清空：mock 也是桌面 deckThumbMock 单源。 */
function deckMountSnapshot(id, card) {
  if (deckSnapPending.has(id)) return;
  const thumb = card.querySelector('.mf-deck-thumb');
  if (!thumb) return;
  const stamp = canvasSnapshotStamp(id);
  const cached = deckSnapCache.get(id);
  if (cached && cached.stamp === stamp && cached.node.isConnected) return;
  if (!stamp) { deckSnapCache.delete(id); return; }   // 不可克隆面 → mock 兜底
  deckSnapPending.add(id);
  // rAF 卸载到下一帧：开面当帧只做布局，快照构建（cloneNode 重活）不挤首帧。
  requestAnimationFrame(() => {
    deckSnapPending.delete(id);
    if (!stackOpen || !deckUI || !docTabs().some((d) => d.id === id)) return;
    const t0 = performance.now();
    let snap = null;
    try {
      snap = canvasSnapshotFor(id, deckCardW());
    } catch { snap = null; }
    deckSnapLastMs = performance.now() - t0;
    deckSnapBuilds++;
    if (deckSnapLastMs > deckSnapMaxMs) deckSnapMaxMs = deckSnapLastMs;
    if (!snap || !snap.node) return;                 // 不可克隆 → mock 留守
    deckSnapCache.set(id, { node: snap.node, srcW: snap.srcW, kind: snap.kind, stamp });
    const slot = deckUI.cards.get(id)?.querySelector('.mf-deck-thumb');
    if (!slot) return;
    mountDeckSnapNode(slot, snap.node, snap.srcW, snap.kind);
  });
}

/** 快照节点挂载（桌面 .card-snap 同语言：原宽渲染 + scale 缩进槽，origin 左上，
 *  只读）。img 腿 = object-fit contain 直排（同桌面 card-snap-img 档）。 */
function mountDeckSnapNode(slot, node, srcW, kind) {
  const wrap = document.createElement('div');
  wrap.className = 'mf-deck-snap';
  wrap.dataset.kind = kind;
  const inner = document.createElement('div');
  inner.className = 'mf-deck-snap-inner';
  const slotW = Math.max(1, slot.clientWidth || (deckUI ? deckCardW() : 320));
  if (kind === 'img') {
    /** @type {HTMLElement} */ (node).classList.add('mf-deck-snap-img');
    /* 图片快照腿的几何单源 = 槽盒（CSS `.mf-deck-snap[data-kind="img"]
       .mf-deck-snap-inner { inset:0 }` ⇒ img 的 100%/100% + object-fit:contain
       有确定盒可解 = 按槽宽等比铺满、高受限时居中留白）。
       但克隆节点带的是**阅读面当时的内联变换**（viewers/image.js 的
       `translate(tx,ty) scale(s)`）—— 那是「按阅读视口算的适屏比」，原样带进
       卡槽就是作者实拍的「缩略图缩成中间一小块四周全白」（实测渲染宽 = 槽宽
       的 0.349 = 旧 fit 的 s 值，不是槽盒几何）。故此处必须掐掉内联几何：
       卡面是**只读缩放面**，视觉尺寸只由槽盒决定，与源视口的缩放态无关。 */
    const imgEl = /** @type {HTMLElement} */ (node);
    imgEl.style.transform = '';
    imgEl.style.transformOrigin = '';
    imgEl.style.width = '';
    imgEl.style.height = '';
    inner.appendChild(node);
  } else {
    const k = srcW > 0 ? slotW / srcW : 1;
    inner.style.width = `${srcW}px`;
    inner.style.transform = `scale(${k.toFixed(4)})`;
    inner.appendChild(node);
  }
  wrap.appendChild(inner);
  slot.replaceChildren(wrap);
  slot.classList.add('has-snap');
}

/** 中央卡宽（快照 srcW 与拖拽几何共用一个口径）。@returns {number} */
function deckCardW() {
  const stage = deckUI?.stage;
  const w = stage?.clientWidth || (canvasUI?.host?.clientWidth ?? window.innerWidth);
  return Math.max(240, Math.round(w * DECK_CARD_W_RATIO));
}

/** 卡片翻页视图构建（一次）：头行「N 张文档」+ ✕，卡叠舞台（.mf-deck）。
 *  浮层宿主仍用 .mf-stack-view 类名（开合动画/工具条让位选择器零改）。 */
function buildDeckView(wrap) {
  const view = document.createElement('div');
  view.className = 'mf-stack-view';
  view.hidden = true;
  const head = document.createElement('div');
  head.className = 'mf-stack-head';
  const count = document.createElement('span');
  count.className = 'mf-stack-count';
  const x = document.createElement('button');
  x.type = 'button';
  x.className = 'mf-stack-x g2-fab';
  x.title = tt('返回标签视图', 'Back to tabs');
  x.setAttribute('aria-label', x.title);
  x.innerHTML = '<i data-lucide="x"></i>';
  x.addEventListener('click', () => toggleDeck(false));
  head.append(count, x);
  const stage = document.createElement('div');
  stage.className = 'mf-deck';
  view.append(head, stage);
  wrap.appendChild(view);
  bindDeckSwipe(stage);
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && stackOpen) toggleDeck(false);
  });
  deckUI = { view, stage, count, cards: new Map() };
  if (typeof lucide !== 'undefined') createIconsIn(head);
  return view;
}

/** 堆叠面拖拽（iOS app switcher 手势；轴锁 + 分轴语义）：
 *  · 横轴 = 卡列跟手平移，松手过 DECK_SWITCH_PX 换中央卡，不过阈回弹；
 *  · 纵轴下 = 中央卡跟手坠落 + 渐淡，松手过 DECK_SWIPE_Y_PX 关该标签（零确认）；
 *  · 纵轴上 = 整列上移预览，松手过阈收面回阅读（与阅读面「上滑进堆叠」互逆）。 */
function bindDeckSwipe(stage) {
  /** @type {{id: number, x0: number, y0: number, axis: string|null}|null} */
  let g = null;
  stage.addEventListener('pointerdown', (e) => {
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    if (performance.now() < deckDragEndedAt) return;  // 拖后松手的 click 抑制窗内不再武装
    const t = /** @type {Element} */ (e.target);
    if (t.closest('button')) return;         // 卡内 ✕ 不参与翻页
    g = { id: e.pointerId, x0: e.clientX, y0: e.clientY, axis: null };
    // capture 延到轴锁后才抢：一上手就 setPointerCapture 会把 pointerup 整体
    // 重定向到舞台 ⇒ 卡面（含卡内 ✕ / 点卡换入）的 click 永不触发（探针实证）。
  });
  stage.addEventListener('pointermove', (e) => {
    if (!g || e.pointerId !== g.id) return;
    const dx = e.clientX - g.x0;
    const dy = e.clientY - g.y0;
    if (!g.axis && (Math.abs(dx) > DECK_AXIS_PX || Math.abs(dy) > DECK_AXIS_PX)) {
      g.axis = Math.abs(dx) >= Math.abs(dy) ? 'x' : 'y';
      if (g.axis) {
        try { stage.setPointerCapture(e.pointerId); } catch { /* 合成事件无 capture */ }
        stage.dataset.dragging = '1';             // CSS 关过渡（直写变量即跟手）
      }
    }
    if (!g.axis) return;
    if (g.axis === 'x') {
      deckDragDx = dx;
      deckDragDy = 0;
    } else {
      deckDragDy = dy;
      deckDragDx = 0;
    }
    layoutDeck();
  });
  const finish = (e, cancelled) => {
    if (!g || e.pointerId !== g.id) return;
    const { axis } = g;
    const dx = e.clientX - g.x0;
    const dy = e.clientY - g.y0;
    g = null;
    deckDragDx = 0;
    deckDragDy = 0;
    delete stage.dataset.dragging;            // 回过渡档：回弹/落位走弹簧
    // 只有**真的拖过**（轴锁建立过）才吞后续 click：一次纯点按没有轴 ⇒ click 必须
    // 照常送达卡面（否则「点邻卡换入 / 点中央卡收面」两条可点等价入口全废 ——
    // 点按的 pointerup 也会把 click 抑制窗打开，click 紧跟着就被吞掉）。
    if (axis) deckDragEndedAt = performance.now() + 350;
    if (cancelled) { layoutDeck(); return; }
    if (axis === 'x') {
      // 横向提交：左滑（dx<0）= 下一张；右滑 = 上一张。吸附 = 落到该签。
      if (Math.abs(dx) > DECK_SWITCH_PX && docTabs().length > 1) {
        logEvent('deck-page', { dir: dx < 0 ? 1 : -1 });
        switchDocCard(dx < 0 ? 1 : -1);
        hapticTap(6);
      }
    } else if (axis === 'y') {
      if (dy >= DECK_SWIPE_Y_PX) {
        // 下滑 = 关当前标签（零确认 —— 作者 21:54 裁定「只是关闭标签页而已」）。
        logEvent('deck-close', { via: 'swipe-down' });
        closeCard();
        hapticTap(12);
      } else if (dy <= -DECK_SWIPE_Y_PX) {
        // 上滑 = 收面回阅读（与阅读面「上滑进堆叠」互逆成环）。
        logEvent('deck-exit', { via: 'swipe-up' });
        toggleDeck(false);
        return;                                // 面已收，无需回弹排版
      }
    }
    layoutDeck();                             // 回弹/落位（过阈时活动签已变）
  };
  stage.addEventListener('pointerup', (e) => finish(e, false));
  stage.addEventListener('pointercancel', (e) => finish(e, true));
}

/** 单张卡面（图标 + 标题 + ✕ + **真实内容快照**）。
 *  快照先在 mock（桌面 deckThumbMock 单源，立即有面），rAF 里换真快照。 */
function buildDeckCard(doc) {
  const card = document.createElement('div');
  card.className = 'mf-deck-card';
  card.dataset.tabId = doc.id;
  card.setAttribute('role', 'tab');
  const face = deckFaceOf({ id: doc.id, title: doc.label });
  const top = document.createElement('div');
  top.className = 'mf-deck-top';
  const icon = document.createElement('span');
  icon.className = 'mf-deck-icon';
  icon.innerHTML = face.icon;
  const title = document.createElement('span');
  title.className = 'mf-deck-title';
  title.textContent = doc.label;
  title.title = doc.label;
  const x = document.createElement('button');
  x.type = 'button';
  x.className = 'mf-deck-x';
  x.title = tt('关闭卡片', 'Close card');
  x.setAttribute('aria-label', `${tt('关闭', 'Close')} ${doc.label}`);
  x.innerHTML = '<i data-lucide="x"></i>';
  x.addEventListener('pointerdown', (e) => e.stopPropagation());
  x.addEventListener('click', (e) => {
    e.stopPropagation();
    logEvent('card-close', { tab: doc.id, via: 'deck' });
    closeTab(doc.id);
    syncCanvasPage();
  });
  top.append(icon, title, x);
  const thumb = document.createElement('div');
  thumb.className = 'mf-deck-thumb';
  thumb.innerHTML = face.thumb;
  card.append(top, thumb);
  /* 点击语义（iOS app switcher 同构）：
     · 邻卡（露出的那一段）= 换入中央（不换视图）；
     · 中央卡（= 活动签）= 收面回阅读。 */
  card.addEventListener('click', () => {
    if (performance.now() < deckDragEndedAt) return;   // 跟手拖过 = 不是点击
    if (doc.id !== activeDocId()) {
      logEvent('card-switch', { via: 'deck', tab: doc.id });
      setActiveTab(doc.id);
      return;
    }
    logEvent('deck-exit', { via: 'front-card' });
    toggleDeck(false);
  });
  return card;
}

/** 卡列位姿对账（唯一几何源；CSS 只消费 --dtx/--dty/--dsc/--dop/--ddark/--dblur）。
 *  iOS app switcher 形态：中央卡（|k|=0）满幅清晰；邻卡（|k|=1）横移露出约
 *  1/3、缩径、压暗、轻模糊；第二邻（|k|=2）更远更暗；再远（|k|>2）隐藏
 *  （class .is-far，op 已压到近 0 —— 留在列里只为换卡连续性）。
 *  |k| 是环距（整数），故位姿是**三档表**而非插值式；越靠边越暗越糊由档位
 *  单调性保证，拖动中的跟手连续量只来自 deckDragDx/deckDragDy（不是几何插值）：
 *    |k|   scale   opacity  brightness  blur
 *    0     1.000   1.000    1.000       0px     ← 中央
 *    1     0.840   0.920    0.620       1.4px   ← 邻卡
 *    2     0.720   0.520    0.340       3.2px   ← 第二邻
 */
function layoutDeck() {
  if (!deckUI) return;
  const docs = docTabs();
  if (!docs.length) return;
  const ai = Math.max(0, docs.findIndex((d) => d.id === activeDocId()));
  const cardW = deckCardW();
  const step = cardW * DECK_SIDE_STEP_RATIO;
  for (const [id, card] of deckUI.cards) {
    const i = docs.findIndex((d) => d.id === id);
    if (i < 0) continue;
    const k = ringOffset(i - ai, docs.length);
    const ak = Math.abs(k);
    const scale = ak === 0 ? 1 : (ak === 1 ? DECK_SCALE_SIDE : DECK_SCALE_FAR);
    const op = ak === 0 ? 1 : ak === 1 ? 0.92 : 0.52;
    const dark = ak === 0 ? 1 : ak === 1 ? 0.62 : 0.34;
    const blur = ak === 0 ? 0 : ak === 1 ? 1.4 : 3.2;
    // 中央卡：纵拖跟手（下坠 = 关闭预览 / 上移 = 收面预览）；
    // 两侧卡：横拖时整列跟手（换卡跟手感），纵拖只吃 35%（视差，不跟着飞出去）。
    const dtx = k * step + deckDragDx;
    const dty = ak === 0 ? deckDragDy * 0.9 : deckDragDy * 0.35;
    card.style.setProperty('--dtx', `${dtx.toFixed(1)}px`);
    card.style.setProperty('--dty', `${dty.toFixed(1)}px`);
    card.style.setProperty('--dsc', scale.toFixed(3));
    card.style.setProperty('--dop', String(op));
    card.style.setProperty('--ddark', dark.toFixed(2));
    card.style.setProperty('--dblur', `${blur.toFixed(2)}px`);
    // 中央卡拖动中随下坠/上移淡出（关闭/收面的跟手反馈）。
    if (ak === 0 && deckDragDy !== 0) {
      const fade = Math.max(0.35, 1 - Math.abs(deckDragDy) / 420);
      card.style.setProperty('--dop', fade.toFixed(3));
    }
    card.style.zIndex = String(60 - ak);
    card.classList.toggle('on', ak === 0);
    card.classList.toggle('is-far', ak > DECK_VISIBLE);
    card.tabIndex = ak === 0 ? 0 : -1;
    card.setAttribute('aria-selected', ak === 0 ? 'true' : 'false');
  }
}

/** 卡列重建（签名闸 = 签集）；点卡换入、✕ 关卡、拖拽换卡/关卡三腿都在此对账。 */
function syncDeck() {
  if (!deckUI || !stackOpen) return;
  const docs = docTabs();
  deckUI.count.textContent = docs.length ? tt(`${docs.length} 张文档`, `${docs.length} cards`) : '';
  deckUI.count.dataset.n = String(docs.length);
  const sig = docs.map((d) => d.id).join('|');
  if (deckUI.stage.dataset.sig !== sig) {
    deckUI.stage.dataset.sig = sig;
    deckUI.stage.innerHTML = '';
    deckUI.cards.clear();
    for (const d of docs) {
      const card = buildDeckCard(d);
      deckUI.stage.appendChild(card);
      deckUI.cards.set(d.id, card);
    }
  }
  // 卡集变了 ⇒ 缓存里已无主稿的条目清掉（防 Map 无界增长）
  for (const id of [...deckSnapCache.keys()]) {
    if (!docs.some((d) => d.id === id)) deckSnapCache.delete(id);
  }
  layoutDeck();
  // 快照：先给中央卡与两侧卡（可视窗）挂，深处的分帧挂（作者令「不阻塞主线程」）。
  const ordered = docs
    .map((d) => ({ id: d.id, ak: Math.abs(ringOffset(docs.findIndex((x) => x.id === d.id) - Math.max(0, docs.findIndex((x) => x.id === activeDocId())), docs.length)) }))
    .filter((o) => o.ak <= DECK_VISIBLE)
    .sort((a, b) => a.ak - b.ak);
  for (const o of ordered) {
    const card = deckUI.cards.get(o.id);
    if (card) deckMountSnapshot(o.id, card);
  }
}

/** 堆叠卡视图开合（open 省略 = 翻转）。开 = 摘 hidden → 建卡 → 回流 → .open
 *  （浮面淡入 + 卡叠逐张自落位升起，CSS 承接）；收 = 摘 .open 即时回落 →
 *  淡出后 hidden（.closing 清零递延不拖尾）。 */
function toggleDeck(open) {
  if (!deckUI) return;
  const next = open === undefined ? !stackOpen : !!open;
  if (next === stackOpen) return;
  stackOpen = next;
  deckDragDx = 0;
  deckDragDy = 0;
  logEvent('canvas-stack-' + (next ? 'open' : 'close'));
  const view = deckUI.view;
  if (next) {
    view.hidden = false;
    view.classList.remove('closing');
    syncDeck();                        // 先建卡（卡生于收合落位态，:not(.open) 初始样式）
    void view.offsetWidth;             // 回流：.open 自初始态起播
    view.classList.add('open');
  } else {
    view.classList.remove('open');
    view.classList.add('closing');
    setTimeout(() => {
      if (!stackOpen) { view.hidden = true; view.classList.remove('closing'); }
    }, 200);
  }
  syncCanvasPage();                    // 工具条让位/计数对账（:has 选择器读 hidden 态）
}

/** canvas 页内容臂构建（一次；环标签带 + 工具条 + 文档区 + 空态 + 小点 +
 *  卡叠视图）。Canvas 改造批②⑤（2026-10-31 作者令）：工具条换代 = 左叉号
 *  （液态玻璃悬浮钮）+ 右堆叠视角钮（.g2-fab 复用液体玻璃 v2 材质，零新造）。
 *  标签栏重做批（2026-11-02 07:22 作者令）：
 *   ① 两颗钮图标修复 —— toolbar 挂 section 根（body.parentElement），图标替换
 *      必须对它单独跑一次 createIconsIn（修前只跑 wrap 子树 ⇒ 两钮永远空圆）；
 *   ② 标签条 = 常驻展开的横向圆环（位姿变量由 layoutRing 写），计数降为
 *      sr-only（档位读数下沉到底部小点指示器）；
 *   ③ 底部小点指示器 = 内容区底部居中（不压 dock）——host 之后的独立行；
 *   ④ 右上堆叠钮 → 卡叠翻页视图（.mf-stack-view 宿主 + .mf-deck 卡叠）。
 *  退役：‹ › 步进、独立窗钮（批⑤）、堆叠列表（本批改卡叠）。 */
function buildCanvasPage(body) {
  const wrap = document.createElement('div');
  wrap.className = 'mf-canvas-page';
  const strip = document.createElement('div');
  // 零底带类 = design-system.css 单源（透传悬浮批 2026-11-03）：环带离流成
  // 覆盖层（几何零变化：签 top:40 / 高 72 不变），带本体零视觉 + 手势落穿
  // （pointer-events:none，签本体自接事件）—— 滚动正文从签底下穿过。
  strip.className = 'mf-strip ds-chrome-band';
  strip.setAttribute('role', 'tablist');
  strip.setAttribute('aria-label', tt('文档卡片', 'Document cards'));

  const toolbar = document.createElement('div');
  // 零底带类同上（与页头带 / 聊天根双钮带同一族）。
  toolbar.className = 'mf-cv-toolbar ds-chrome-band';
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'mf-cv-close g2-fab';
  close.title = tt('关闭文档', 'Close document');
  close.setAttribute('aria-label', tt('关闭文档', 'Close document'));
  close.innerHTML = '<i data-lucide="x"></i>';
  close.addEventListener('click', () => closeCard());
  const count = document.createElement('span');
  count.className = 'mf-cv-count';
  const stack = document.createElement('button');
  stack.type = 'button';
  stack.className = 'mf-cv-stack g2-fab';
  stack.title = tt('堆叠卡视图', 'Stacked cards');
  stack.setAttribute('aria-label', tt('堆叠卡视图', 'Stacked cards'));
  stack.innerHTML = '<i data-lucide="layers"></i>';
  stack.addEventListener('click', () => { logEvent('canvas-stack-toggle'); toggleDeck(); });
  toolbar.append(close, count, stack);

  const host = document.createElement('div');
  host.className = 'mf-cv-host';
  bindDocSwipe(host);
  bindStripSwipe(strip);
  /* 整字收束的**稳态自校**（方案 C 落地批 2026-11-06）：切签瞬间签的 max-width
     正在 220ms 过渡里，`chipInnerWidth` 直读到的是过渡起点档（比目标窄一档）。
     过渡一落定就再收一次 —— 稳态恒落在整字边界，且**不碰过渡**（本腿只读）。
     代价只有 220ms 内可能早收一个字（旧档那一个字本来也是半透明的边缘字）。 */
  strip.addEventListener('transitionend', (ev) => {
    if (/** @type {TransitionEvent} */ (ev).propertyName === 'max-width') fitRingLabels();
  });
  const dots = document.createElement('div');
  dots.className = 'mf-dots';
  dots.hidden = true;
  dots.setAttribute('role', 'group');
  dots.setAttribute('aria-label', tt('卡片位置', 'Card position'));
  bindDots(dots);
  const empty = document.createElement('div');
  empty.className = 'mf-cv-empty';
  const eIcon = document.createElement('i');
  eIcon.setAttribute('data-lucide', 'layers');
  const eTitle = document.createElement('div');
  eTitle.className = 'mf-cv-empty-title';
  eTitle.textContent = tt('看板暂无文档卡片', 'No document cards yet');
  const eSub = document.createElement('div');
  eSub.className = 'mf-cv-empty-sub';
  eSub.textContent = tt('在聊天里打开文件或文档后会出现在这里', 'Documents opened from chat appear here');
  empty.append(eIcon, eTitle, eSub);
  const stackView = buildDeckView(wrap);
  wrap.append(strip, host, dots, empty);
  body.appendChild(wrap);
  // 悬浮钮统一批：工具条 = 页面悬浮 chrome 带控件 —— 挂 section 根（与页头带同
  // 定位上下文，top:16+safe 落位），mf-canvas-page 只吃环带/文档区/小点/空态。
  body.parentElement?.appendChild(toolbar);
  canvasUI = { strip, toolbar, host, empty, dots };
  // 标签栏重做批①：图标替换覆盖到 toolbobar 子树（根因修复 —— 修前只 createIconsIn(wrap)，
  // 而 ✕/堆叠两钮在 toolbar 内、wrap 之外 ⇒ 永远停在 <i data-lucide>）。
  if (typeof lucide !== 'undefined') {
    createIconsIn(wrap);
    createIconsIn(toolbar);
  }
  // 视口变化：环带渐隐/小点位姿均为纯 CSS + 变量，无需重算；补小点计数对账，
  // 并按新可用宽重跑中签整字收束（字体/内衬不变时内联 maxWidth 数值不变，幂等）。
  window.addEventListener('resize', () => { syncDots(); fitRingLabels(); });
  // 字体后到（网络字体 / 首帧 fallback 度量不同）⇒ 重跑一次收束，避免按 fallback
  // 字体算出的截断点。document.fonts 缺失（老 WebView）则跳过，无副作用。
  document.fonts?.ready?.then(() => { fitRingLabels(); }).catch(() => {});
  void stackView;
}

// ══ 顶部环签横滑切签（ui-final-four ②；作者令 2026-11-04 13:04 逐字
// 「把canvas顶部的标签 支持左右滑的手势现在也没了」）══════════════════════
// 史检（本腿第一步，结论先行）：签条**从未**有过切签手势 —— 全史（git log -60
// 逐版 navController.js）对 `.mf-strip` / `.mf-strip-chip` 的 pointer 绑定命中数
// 恒为 0（chip 只绑 click）；切卡横滑一直是**内容区**那条腿（bindDocSwipe，
// dc7c965bb 引入，至今在场）。作者读到的「没了」= 签条横拖**响应面退化**：
// 9a992ea68（2026-11-02 08:39 标签栏重做）把签条从 in-flow `overflow-x:auto`
// 签行（横拖 = 原生横滚）改成离流覆盖带（overflow:visible + 带 pointer-events:
// none + 签 touch-action:manipulation）⇒ 横拖在签上零响应、零位移、零切签。
// 故本腿 = **加法**：只给签本体加横滑切签（内容是环式的既有语义一字不动 ——
// tests/mobile-canvas-sheet.spec.mjs T3 钉死）。判据三条：
//   ① 横滑真的切签（setActiveTab → syncCanvasPage；非「整段禁切」凑绿）；
//   ② 端点**非环式**：首/尾越界一律阻尼回弹，active 不变、不越界、不闪白；
//   ③ 纵拖一律不认领（axis=y 时不 setPointerCapture、不 preventDefault，原生
//      滚动路径保持）；横滑判定 = 双条件（|dx| ≥ 1.2×|dy| ∧ |dx| ≥ 8px），
//      轴锁后单次成立不来回翻转。
const STRIP_AXIS_PX = 8;        // 轴锁最小位移（与内容区 SWIPE_AXIS_PX 同参）
const STRIP_AXIS_RATIO = 1.2;   // 横滑判据：|dx| ≥ 1.2×|dy|（两条件缺一不认）
const STRIP_SWITCH_PX = 48;     // 提交阈（与内容区 SWIPE_SWITCH_PX 同参）
const STRIP_BOUNCE_K = 0.35;    // 端点越界段阻尼（跟手 0.35 折 = 回弹手感）

/** 签条横滑切签（非环式端点；只认签上的指针序列）。
 *  跟手位移 = 签组统一变量 `--sdx`（chip 的 transform 链里加一项，布局零改、
 *  环位姿 --rdx 照旧各算各的）；松手先摘 `.is-swiping`（恢复 CSS 过渡）再写 0
 *  ⇒ 真回弹（不靠 JS 补间）。探针 `window.__nfStripSwipe` = 只读判据面。
 *  @param {HTMLElement} strip */
function bindStripSwipe(strip) {
  /** @type {{id: number, x0: number, y0: number, axis: 'x'|'y'|null}|null} */
  let g = null;
  /** 横滑后必须吞掉的那一次 click（否则松手瞬间 chip 的 click 腿会把刚切好的
   *  签再切一次 / 展开中签全名）。 */
  let swallowClick = false;
  const probe = { axis: /** @type {string|null} */ (null), claims: 0, switches: 0, bounces: 0, lastDx: 0, lastOffset: 0 };
  (/** @type {any} */ (window)).__nfStripSwipe = probe;
  const writeOffset = (px) => {
    strip.style.setProperty('--sdx', `${px.toFixed(1)}px`);
    probe.lastOffset = +px.toFixed(1);
  };
  const ringState = () => {
    const docs = docTabs();
    const i = docs.findIndex((d) => d.id === activeDocId());
    return { i, n: docs.length, atStart: i <= 0, atEnd: i < 0 || i >= docs.length - 1 };
  };
  /** 端点越界段阻尼（首签右滑 / 尾签左滑 —— 非环式，不许环绕）。 */
  const rubberBand = (dx, s) => ((s.atEnd && dx < 0) || (s.atStart && dx > 0) ? dx * STRIP_BOUNCE_K : dx);
  const drop = () => { g = null; strip.classList.remove('is-swiping'); };

  strip.addEventListener('pointerdown', (e) => {
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    const target = e.target instanceof Element ? e.target : null;
    if (!target) return;
    // 仲裁①原样保留（内容手势面起手永不接管；签条不压在内容面上，本闸是
    // 「内容优先」硬口径的延续，防未来叠加层/标记面把内容手势漏进切签腿）。
    if (target.closest(CONTENT_GESTURE_SELECTOR)) return;
    if (docTabs().length < 2) return;     // 单签 / 空态：切签无对象
    if (g) return;
    swallowClick = false;
    probe.axis = null;
    g = { id: e.pointerId, x0: e.clientX, y0: e.clientY, axis: null };
  });

  strip.addEventListener('pointermove', (e) => {
    if (!g || e.pointerId !== g.id) return;
    // 双指 = pinch（调尺寸/缩放）：解除已武装的签条手势，让位内容操作。
    if (e.isPrimary === false) { drop(); writeOffset(0); return; }
    const dx = e.clientX - g.x0;
    const dy = e.clientY - g.y0;
    if (!g.axis) {
      const ax = Math.abs(dx);
      const ay = Math.abs(dy);
      if (ax >= STRIP_AXIS_PX && ax >= STRIP_AXIS_RATIO * ay) g.axis = 'x';
      else if (ay >= STRIP_AXIS_PX) g.axis = 'y';
      else return;                                    // 死区内：不判定（点按路径原样）
      probe.axis = g.axis;
      if (g.axis === 'y') return;                     // 纵拖：零 capture / 零 preventDefault
      // 横轴锁后**单次**成立（不来回翻转）：此刻才抢 capture + 关过渡（跟手）。
      try { strip.setPointerCapture(e.pointerId); } catch { /* 合成事件无 capture */ }
      swallowClick = true;
      strip.classList.add('is-swiping');
      probe.claims += 1;
    }
    if (g.axis !== 'x') return;
    e.preventDefault();
    probe.lastDx = dx;
    writeOffset(rubberBand(dx, ringState()));
  });

  const finish = (e) => {
    if (!g || e.pointerId !== g.id) return;
    const { axis } = g;
    g = null;
    strip.classList.remove('is-swiping');
    if (axis !== 'x') return;                 // 纵拖 / 死区：零接管、零归位写入
    const dx = probe.lastDx;
    const s = ringState();
    const dir = dx < 0 ? 1 : -1;              // 左滑 = 下一张签，右滑 = 上一张签
    const nextIdx = s.i + dir;
    const canSwitch = Math.abs(dx) >= STRIP_SWITCH_PX && s.n > 1 && s.i >= 0
      && nextIdx >= 0 && nextIdx < s.n;       // 端点非环式：越界一律回弹
    // 弹簧归位：先摘 `.is-swiping`（恢复 CSS transform 过渡）并强制一次样式刷新，
    // 再写 0 —— 同一帧「摘类 + 改值」在 WebKit 按无过渡直跳（实测），刷新后写值
    // 才是真回弹；换签腿同样归零（新环位姿接管位移，不叠双重偏移）。
    void strip.offsetWidth;
    writeOffset(0);
    // 吞 click 的旗有看门狗：pointercancel 路径不会到来 click，若旗滞留会吞掉
    // 下一次真实的键盘/辅助点击（指针路径的 pointerdown 已重置，这里再兜一层）。
    if (swallowClick) setTimeout(() => { swallowClick = false; }, 400);
    if (canSwitch) {
      probe.switches += 1;
      logEvent('card-switch', { via: 'strip-swipe', dir });
      switchToIndex(nextIdx);
    } else {
      probe.bounces += 1;
      logEvent('strip-swipe-bounce', {
        dir, edge: s.atStart ? 'start' : (s.atEnd ? 'end' : 'none'), dx: Math.round(dx),
      });
    }
  };
  strip.addEventListener('pointerup', finish);
  strip.addEventListener('pointercancel', finish);

  // 横滑吞 click（捕获阶段先于 chip 自身的 click 腿）。
  strip.addEventListener('click', (e) => {
    if (!swallowClick) return;
    swallowClick = false;
    e.stopPropagation();
    e.preventDefault();
  }, true);
}

/** 文档区横滑切卡（轴锁 8px / 阈 48px；环式；纵滚零劫持 —— 卡头手势同参数）。
 *  Canvas 改造批③（2026-10-31 作者令「标签页手机端左右滑动，两边露圆环相邻
 *  页 = 桌面上下滚动的横屏变体」）：横拖跟手位移 + 拖向侧露圆环（邻卡环露
 *  指示，进深随拖距 0→1）+ 松手判定（过阈换卡滑出 / 不过阈弹簧回位）。
 *  圆环 = 玻璃描边半露圆（CSS .mf-swipe-ring），只在横轴锁后出现，松手即退。
 *  手势仲裁批（2026-11-01 作者令②「内容操作优先、误切率趋零」）三闸：
 *   ① 内容手势面（CONTENT_GESTURE_SELECTOR）起手 = 内容操作，不接管、不抢
 *     capture（图片拖动/双指调尺寸、图谱/流程图平移全链原生）；
 *   ② 双指 = pinch：第二指落地即解除已武装的切卡手势（调尺寸永不误切卡）；
 *   ③ 边缘返回认领期（swipeSuppressed）整体让路。 */
function bindDocSwipe(host) {
  /** @type {{id: number, x0: number, y0: number, dx: number, dy: number, axis: string|null}|null} */
  let g = null;
  /** @type {HTMLElement|null} 当前收编 pane（跟手位移对象；换卡由 adoptActiveDoc 归位）。 */
  let paneEl = null;
  /** @type {HTMLElement|null} */
  let ringL = null;
  /** @type {HTMLElement|null} */
  let ringR = null;
  const ensureChrome = () => {
    if (!ringL) {
      ringL = document.createElement('div');
      ringL.className = 'mf-swipe-ring left';
      ringL.setAttribute('aria-hidden', 'true');
      ringR = document.createElement('div');
      ringR.className = 'mf-swipe-ring right';
      ringR.setAttribute('aria-hidden', 'true');
      host.append(ringL, ringR);
    }
  };
  const setRing = (side, k) => {
    const el = side < 0 ? ringL : ringR;
    if (!el) return;
    const v = Math.max(0, Math.min(1, k));
    el.style.opacity = v <= 0 ? '' : String(0.25 + 0.75 * v);
    el.style.transform = `translateY(-50%) scale(${0.72 + 0.28 * v})`;
  };
  const clearRings = () => {
    if (ringL) { ringL.style.opacity = ''; ringL.style.transform = ''; }
    if (ringR) { ringR.style.opacity = ''; ringR.style.transform = ''; }
  };
  /** 解除武装时把跟手位移一并清掉（仲裁②③让位路径 —— pane 不能带着
   *  translate3d 滞留，否则下一拍渲染就是叠影卡）。 */
  const dropSwipePane = () => {
    if (paneEl) { paneEl.style.transition = ''; paneEl.style.transform = ''; paneEl.style.opacity = ''; }
    paneEl = null;
  };
  host.addEventListener('pointerdown', (e) => {
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    const target = /** @type {Element} */ (e.target);
    // 自愈：抑制旗在场但边缘手势已不在（pointerup 丢失的极端路径）→ 复位。
    if (swipeSuppressed && !edgeBack) swipeSuppressed = false;
    // 仲裁②前置：已武装 + 新触点 = 双指（调尺寸）——解除切卡手势，让位 pinch。
    if (g && e.pointerId !== g.id) { g = null; dropSwipePane(); clearRings(); return; }
    if (target.closest('button, input, textarea, select, a')) return;
    // 仲裁①：内容手势面起手 = 内容操作（拖图/调尺寸/拖图谱），切卡零接管、
    // 零 capture —— viewport/画布自己的 pointer 管线全链保活。
    if (target.closest(CONTENT_GESTURE_SELECTOR)) return;
    g = { id: e.pointerId, x0: e.clientX, y0: e.clientY, dx: 0, dy: 0, axis: null };
    paneEl = /** @type {HTMLElement|null} */ (host.querySelector(':scope > .canvas-tab-pane'));
    try { host.setPointerCapture(e.pointerId); } catch (_) {}
  });
  host.addEventListener('pointermove', (e) => {
    if (!g || e.pointerId !== g.id) return;
    // 仲裁②：第二触点落地 = pinch（调尺寸）—— 已武装的切卡手势立即解除。
    if (e.isPrimary === false) { g = null; dropSwipePane(); clearRings(); return; }
    // 仲裁③：边缘返回认领期，切卡手势整体让路。
    if (swipeSuppressed) { g = null; dropSwipePane(); clearRings(); return; }
    g.dx = e.clientX - g.x0;
    g.dy = e.clientY - g.y0;
    if (!g.axis && (Math.abs(g.dx) > SWIPE_AXIS_PX || Math.abs(g.dy) > SWIPE_AXIS_PX)) {
      g.axis = Math.abs(g.dx) > Math.abs(g.dy) ? 'x' : 'y';
      if (g.axis === 'x') ensureChrome();
    }
    if (g.axis === 'x') {
      const docs = docTabs();
      if (paneEl && docs.length > 1) {
        paneEl.style.transition = 'none';
        paneEl.style.transform = `translate3d(${g.dx}px,0,0)`;
      }
      setRing(-1, docs.length > 1 && g.dx > 0 ? g.dx / SWIPE_SWITCH_PX : 0);
      setRing(1, docs.length > 1 && g.dx < 0 ? -g.dx / SWIPE_SWITCH_PX : 0);
    }
  });
  const finish = (e) => {
    if (!g || e.pointerId !== g.id) return;
    const { dx, axis } = g;
    g = null;
    if (axis !== 'x') { paneEl = null; return; }
    const docs = docTabs();
    const hit = Math.abs(dx) > SWIPE_SWITCH_PX && docs.length > 1 && !!activeDocId();
    if (hit) {
      // 过阈：即刻换卡（adoptActiveDoc 归位旧 pane、纳新 pane——松手即切是
      // 既有契约，spec T3 同步断言；跟手位移已发生在拖动段）。
      logEvent('card-switch', { via: 'page-swipe', dir: dx < 0 ? 1 : -1 });
      switchDocCard(dx < 0 ? 1 : -1);
      if (paneEl) { paneEl.style.transition = ''; paneEl.style.transform = ''; paneEl.style.opacity = ''; paneEl = null; }
      clearRings();
    } else {
      // 不过阈：弹簧回位（220ms 后清内联，避免遗留过渡影响后续纵滚）。
      if (paneEl) {
        paneEl.style.transition = 'transform 220ms cubic-bezier(0.16, 1, 0.3, 1)';
        paneEl.style.transform = 'translate3d(0,0,0)';
        const el = paneEl;
        paneEl = null;
        setTimeout(() => { el.style.transition = ''; el.style.transform = ''; }, 240);
      } else paneEl = null;
      clearRings();
    }
  };
  host.addEventListener('pointerup', finish);
  host.addEventListener('pointercancel', finish);
}

// ══════════════════════════════════════════════════════════════════════
// 次级面板边缘返回（作者令 2026-11-01 ③「所有手机端次级面板统一支持从左边缘
// 向右滑的返回手势，iOS 同款；边缘判定带宽度合适，不干扰正常横滑与内容操作」）
// ══════════════════════════════════════════════════════════════════════
// 范围 = 详情页（stack.length > 1）——下钻页/设置/图谱/项目/插件/用量/任务/
// 待办/会话/个人主页层级/设置子层级全在页面栈上，一枚手势统一覆盖；dock 根页
// （消息/联系人/看板/工作台）与聊天根 = 一级面，无返回语义，手势不认领
// （「<」钮契约不变，本手势是加法不是替换）。
// 交互形态 = iOS 同款跟手：横轴锁后整页随指右移（越带阻尼），松手过阈
// （宽 22% 或快弹）即 pop（无动画直切语义不变——页面壳零过渡，滑出只发生在
// 手势进行中），不过阈弹簧回位。认领即仲裁：swipeSuppressed 压住切卡手势，
// 并向起手目标派发合成 pointercancel（内容手势面/切卡手势按浏览器同款契约
// 「指针已被系统手势收走」收尾），带内纵滑照旧让给原生滚动/画布纵拖。
// 带宽 20px：iOS 边缘带同档；正常横滑/内容操作起手几乎不落带内。

const EDGE_BAND_PX = 20;    // 左缘判定带宽
const EDGE_AXIS_PX = 8;     // 轴锁阈值（与切卡手势同参）
const EDGE_FLICK_MS = 200;  // 快弹判定窗（短距快滑也提交）

/** @type {{id: number, x0: number, y0: number, t0: number, claimed: boolean,
 *          target: EventTarget | null, pageEl: HTMLElement | null} | null} */
let edgeBack = null;

/** 活动页面 section（跟手位移对象）。 */
function livePageEl() {
  const top = stack[stack.length - 1];
  return top ? (sections.get(top) || null) : null;
}

/** 阻尼映射：40% 视口宽内 1:1 跟手，超出部分 0.35 递推（iOS 橡皮筋手感）。 */
function edgeDamp(dx, w) {
  const cap = w * 0.4;
  return dx <= cap ? dx : cap + (dx - cap) * 0.35;
}

function bindEdgeBack(root) {
  root.addEventListener('pointerdown', (e) => {
    if (edgeBack) {
      // 双指 = pinch/内容操作：解除已武装的边缘手势（调尺寸永不误触发返回）；
      // 认领态不解除（第一根手指独占返回手势，后续触点无关）。
      if (!edgeBack.claimed && e.pointerId !== edgeBack.id) disarmEdgeBack();
      return;
    }
    if (!document.body.classList.contains('mobile-layer')) return;
    if (stack.length <= 1) return;                       // 一级面（dock 根/聊天根）不认领
    // 滑返批（作者令 2026-11-07 12:17）：**浮层优先** —— 次级面板（设置/搜索/记忆/
    // 规则/确认框/pop sheet 详情）登记了滑返登记项时，右滑属于那一层（关面板），
    // 页面栈本腿让路；面板关完登记项清空，这条腿自然恢复（主会话/页面栈语义优先
    // 于「没有浮层」的那个常态）。
    if (hasBackHandler()) return;
    if (e.clientX > EDGE_BAND_PX) return;
    if (/** @type {Element} */ (e.target).closest('button, input, textarea, select, a')) return;
    edgeBack = {
      id: e.pointerId, x0: e.clientX, y0: e.clientY, t0: Date.now(),
      claimed: false, target: e.target, pageEl: livePageEl(),
    };
  }, true);   // 捕获相位：先于内容面/切卡手势（认领权在带内最高）

  root.addEventListener('pointermove', (e) => {
    if (!edgeBack || e.pointerId !== edgeBack.id) return;
    const dx = e.clientX - edgeBack.x0;
    const dy = e.clientY - edgeBack.y0;
    if (!edgeBack.claimed) {
      if (Math.abs(dx) <= EDGE_AXIS_PX && Math.abs(dy) <= EDGE_AXIS_PX) return;
      if (Math.abs(dy) >= Math.abs(dx)) { disarmEdgeBack(); return; }   // 纵向意图：让给滚动/内容
      if (dx <= 0) { disarmEdgeBack(); return; }                        // 只认右滑
      claimEdgeBack(e);
    }
    if (!edgeBack.claimed) return;
    const page = edgeBack.pageEl;
    if (page && page.isConnected) {
      const w = page.getBoundingClientRect().width || window.innerWidth;
      page.style.transition = 'none';
      page.style.transform = `translate3d(${Math.max(0, edgeDamp(dx, w))}px,0,0)`;
    }
  }, true);

  const settle = (e) => {
    if (!e.isTrusted) return;   // 合成 pointercancel（claimEdgeBack 收走内容面用）不进本手势收尾
    if (!edgeBack || e.pointerId !== edgeBack.id) return;
    const st = edgeBack;
    edgeBack = null;
    if (!st.claimed) return;
    swipeSuppressed = false;
    const dx = e.clientX - st.x0;
    const dt = Date.now() - st.t0;
    const page = st.pageEl;
    const w = (page && page.isConnected ? page.getBoundingClientRect().width : 0) || window.innerWidth;
    const commit = dx > Math.max(80, w * 0.22) || (dx > 28 && dt < EDGE_FLICK_MS);
    if (commit) {
      logEvent('edge-back', { page: stack[stack.length - 1], dx: Math.round(dx) });
      if (page) { page.style.transition = ''; page.style.transform = ''; delete page.dataset.edgeSpring; }
      backOut();   // 详情 pop 一层（栈语义与「<」同源；根页永不进来）
    } else if (page && page.isConnected) {
      // 弹簧回位（复用切卡手势回位曲线；token 防新认领后清理定时器误擦跟手位）。
      page.dataset.edgeSpring = '1';
      page.style.transition = 'transform 220ms cubic-bezier(0.16, 1, 0.3, 1)';
      page.style.transform = 'translate3d(0,0,0)';
      setTimeout(() => {
        if (page.dataset.edgeSpring !== '1') return;
        delete page.dataset.edgeSpring;
        page.style.transition = '';
        page.style.transform = '';
      }, 240);
    }
  };
  root.addEventListener('pointerup', settle, true);
  root.addEventListener('pointercancel', settle, true);

  // 横向意图的 touchmove 一律 preventDefault（capture + 非被动）：修前可滚动
  // 详情页（用量/列表族）带内右滑会被浏览器滚动接管 → pointercancel 掐断指针
  // 流 → 返回手势死（settings 等无滚页面不复现，T3b 只红在滚动面）。横意图
  // 才拦 —— 带内纵滑照旧让位原生滚动（JS 判横先于浏览器滚动接管竞速，
  // touchmove preventDefault 是唯一可靠截流点）。鼠标腿零影响（无 touch 事件）。
  document.addEventListener('touchmove', (e) => {
    if (!edgeBack) return;
    const t = e.changedTouches && e.changedTouches[0];
    if (!t) return;
    const dx = t.clientX - edgeBack.x0;
    const dy = t.clientY - edgeBack.y0;
    if (Math.abs(dx) > Math.abs(dy) && dx > 0) e.preventDefault();
  }, { passive: false, capture: true });
}

/** 认领：压住切卡手势 + 收走内容面指针（合成 pointercancel = 浏览器同款契约）。
 *  触屏指针自带对起手目标的隐式 capture，pointer 流天然续到 pointerup ——
 *  无需（也不可）显式 setPointerCapture（容器 pointer-events:none 无效且多余）；
 *  浏览器滚动接管由 bindEdgeBack 尾部的横向 touchmove preventDefault 截流。 */
function claimEdgeBack(e) {
  if (!edgeBack) return;
  edgeBack.claimed = true;
  swipeSuppressed = true;
  logEvent('edge-back-claim', { page: stack[stack.length - 1] });
  const page = edgeBack.pageEl;
  if (page) delete page.dataset.edgeSpring;   // 新认领即夺回清理权（防回位定时器误擦）
  // 收走起手目标的手势链：内容手势面（图谱/图片/流程图）与切卡手势按
  // 「指针被系统手势收走」收尾（合成事件 isTrusted=false，消费端不查）。
  if (edgeBack.target && /** @type {Element} */ (edgeBack.target).isConnected) {
    const ev = new PointerEvent('pointercancel', {
      bubbles: true, cancelable: false, pointerId: e.pointerId,
      pointerType: e.pointerType, isPrimary: e.isPrimary,
      clientX: e.clientX, clientY: e.clientY,
    });
    /** @type {Element} */ (edgeBack.target).dispatchEvent(ev);
  }
  // 切卡手势可能已写下的少量跟手位移清零（认领后视觉归边缘返回手势所有）。
  const host = canvasUI?.host;
  // 类型标注：querySelector 静态返回 Element，纯样式清零需要 HTMLElement 上的
  // style 面（2026-11-02 合并批 checkJs 归零：zhang 烧平基线 total=0，本批
  // 不得把新错带进来，故就地窄化而非抬高基线）。
  const pane = /** @type {HTMLElement|null|undefined} */ (host?.querySelector(':scope > .canvas-tab-pane'));
  if (pane) { pane.style.transition = ''; pane.style.transform = ''; pane.style.opacity = ''; }
}

/** 解除武装（纵滑/左滑/双指）：未认领态零副作用；认领态不可解除（唯一出口
 *  = settle 的 commit/spring 收尾，swipeSuppressed 只在那里复位）。 */
function disarmEdgeBack() {
  if (!edgeBack || edgeBack.claimed) return;
  edgeBack = null;
}

/** 关闭当前文档（工具条 X）：关单卡；关后落相邻文档卡，无文档卡则保持空态。 */
export function closeCard() {
  const id = activeDocId();
  if (!id) return;
  const docs = docTabs();
  const idx = docs.findIndex((d) => d.id === id);
  logEvent('card-close', { tab: id, via: 'canvas-page' });
  closeTab(id);
  const rest = docTabs().filter((d) => d.id !== id);
  if (rest.length) {
    const next = rest[Math.min(Math.max(idx, 0), rest.length - 1)];
    if (next && next.id !== activeDocId()) setActiveTab(next.id);
  }
  syncCanvasPage();
}

/** 主会话键：回聊天根（dock 根的「<」）；canvas 页离开 = 面归还、文档保留。 */
export function goHome() {
  while (stack.length > 1) closePageFace(stack.pop());
  if (stack[0] !== 'chat') {
    closePageFace(stack[0]);
    stack[0] = 'chat';
  }
  renderPages();
  emitNav('home');
}

// ── 页面栈（home + details）─────────────────────────────────────────

export function navState() {
  return {
    stack: [...stack],
    top: stack[stack.length - 1],
    home: stack[0],
    cardsLive: false, // dock 批：bottom sheet 退役；字段保留兼容旧探针读者
  };
}

function emitNav(action) {
  document.dispatchEvent(new CustomEvent('nf:mobile-nav', { detail: { ...navState(), action } }));
}

function renderPages() {
  const top = stack[stack.length - 1];
  for (const [id, section] of sections) {
    const live = top === id;
    section.classList.toggle('live', live);
    if (live) {
      (/** @type {HTMLElement|null} */ (section.querySelector('.mobile-page-title')))?.focus?.();
      // 两段式标题批：进场按当前实测主滚轴重算进度（DOM 常驻、滚动位保留，
      // 离场时的吸附态不得带进下一次进场）。
      refreshRoll(section);
    }
  }
  document.body.classList.toggle('mf-page-open', top !== 'chat');
  syncDockActive();
  // 徽标可能晚于 buildDock 建出（封存态剖面好友面不初始化）——翻页时重查补绑。
  syncDockBadges();
  // hub 门控行（graph 随 wikiGraph flag）与 configData 到达时序重对账（p9 flaky 实证）。
  syncHubGate();
  syncOverlayFaces();
  syncSettingsSubFaces(stack);
  syncSocialFaces((id) => stack.includes(id));
  if (top === 'canvas') syncCanvasPage();
}

/** dock 根页「<」/详情页「<」统一出栈腿：详情 pop 一层；根页回聊天。 */
function backOut() {
  if (stack.length > 1) popPage();
  else if (stack[0] !== 'chat') goHome();
}

// 大标题内容宽在「文字 / 视口宽」变化时重测（schedule-todo-unify 批）：
// locale 重绘（i18n 改 .mobile-page-title 文本）与视口变化（横竖屏）都会让静止档
// 内容宽变化，而这两个事件不经过 renderPages —— 只挂在 document 上一次监听，
// 按当前可见页重测，零逐面接线。
window.addEventListener('locale-changed', () => {
  const top = stack[stack.length - 1];
  const section = sections.get(top);
  if (!section) return;
  measureTitleStill(section, /** @type {HTMLElement|null} */ (section.querySelector('.mobile-page-title')));
});
window.addEventListener('resize', () => {
  const top = stack[stack.length - 1];
  const section = sections.get(top);
  if (!section) return;
  measureTitleStill(section, /** @type {HTMLElement|null} */ (section.querySelector('.mobile-page-title')));
});

/** 页面面关闭（出栈/换根时归位桌面开/关态）。 */
function closePageFace(pageId) {
  switch (pageId) {
    case 'settings':
      try { if (isSettingsPanelActive()) closeSettingsPanel(); } catch { /* 模块态异常不阻断 */ }
      break;
    case 'usage':
      modUsage().then((m) => m.closeUsageDashboard()).catch(() => { /* 未载即未开 */ });
      break;
    case 'search':
      document.getElementById('search-overlay')?.classList.remove('on');
      break;
    case 'reminder':
      document.querySelector('#reminder-panel')?.classList.remove('open');
      break;
    // ⛔ 【退役】todo-unify-entry-collapse 2026-11-04：'todo' 页整面退役（PAGES
    // 同批注记）；原文留档，零物理删除：
    // case 'todo':
    //   document.querySelector('#todo-page')?.classList.remove('open');
    //   break;
    case 'daemon':
      document.querySelector('#daemon-panel')?.classList.remove('open');
      break;
    case 'background':
      document.querySelector('#bg-dropdown')?.classList.add('hidden');
      break;
    case 'profile':
      import('../memory.js').then((m) => m.closeMemoryModal?.()).catch(() => { /* 未载即未开 */ });
      break;
    case 'tasks':   // Apple todo face pages keep no overlay: nothing to close
    case 'todo-apple-list':
    case 'todo-apple-item':
      break;
    case 'canvas':
      // 双页叠开修复批①（2026-10-31）：离页即归还活动文档 pane（头注既有契约
      // 「canvas 页离开 = 面归还、文档保留」的兑现——修前默认 no-op，pane 滞留
      // 隐藏页体直到下次换卡才归还，滞留窗内的树变即触发双页叠开）。
      releaseAdoptedDoc();
      break;
    case 'conversation':
      // 会话页头同构批（件二+件三，2026-11-02）：会话页的左上返回钮必须**真关窗**
      // —— 旧 `default` 是 no-op，靠随后「桌面关窗 ⇒ watchChatModal 对账」
      // 巧合兜底；现在窗体头（×）与窗头行在手机层已退场（身份头接管），返回钮
      // 成为唯一出页入口 ⇒ 在这里显式走 `messages.closeChat()`（唯一关窗实现：
      // 摘 overlay ⇒ watchChatModal 的 childList 腿照旧弃收编节点 + 弹页）。
      // 动态 import：`messages.js` 静态引 `navController.js`（pushPage/logEvent），
      // 静态反向边会成环 —— 本腿只在运行期、页面已建出时执行，模块必已求值。
      import('../messages.js')
        .then((m) => m.closeChat?.())
        .catch(() => { /* 模块态异常不阻断出页 */ });
      break;
    default:
      break; // messages/contacts/canvas/panels：面态无需翻转
  }
}

/** 活动文档 pane 归还画板内容区（单点：离页/层拆除共用；幂等）。 */
function releaseAdoptedDoc() {
  if (!canvasDocAnchor) return;
  const a = canvasDocAnchor;
  canvasDocAnchor = null;
  if (a.pane.isConnected && a.parent && a.parent.isConnected) {
    try { a.parent.insertBefore(a.pane, a.next); } catch { releaseDocPane(a.pane, document.getElementById('canvas-content')); }
  }
}

/**
 * 通用 push（详情页/壳/深链留缝点）：dock 根 id 走 dockTo；'chat' = 中键
 * home；目标已在栈中先摘除再压顶（摘除位移的 overlay 面同步关闭）。
 * @param {string} pageId
 */
export function pushPage(pageId) {
  if (pageId === 'chat') return dockHome();
  if (!PAGE_BY_ID.has(pageId)) return;
  if (PAGE_BY_ID.get(pageId).dock) return dockTo(pageId);
  ensureContainer();
  const displaced = [];
  stack = stack.filter((p) => (p === pageId ? (displaced.push(p), false) : true));
  for (const d of displaced) closePageFace(d);
  stack.push(pageId);
  logEvent('nav-push', { page: pageId, depth: stack.length });
  void ensurePanelContent(pageId);
  renderPages();
  emitNav('push');
}

/** pop 一层（返回钮契约）。 */
export function popPage() {
  if (stack.length <= 1) return;
  const pageId = stack.pop();
  logEvent('nav-pop', { page: pageId });
  closePageFace(pageId);
  renderPages();
  emitNav('pop');
}

/** pop 到 home（聊天根或当前 dock 根；详情全清）。 */
export function popToRoot() {
  if (stack.length <= 1) return;
  while (stack.length > 1) closePageFace(stack.pop());
  renderPages();
  emitNav('pop-to-root');
}

/** pop 到指定页（注入式返回钮用：被弹页可能不在栈顶）。 */
export function popToPage(pageId) {
  const i = stack.lastIndexOf(pageId);
  if (i === -1) return;
  while (stack.length - 1 > i) closePageFace(stack.pop());
  renderPages();
  emitNav('pop');
}

/**
 * 中键 = 对话 home 键（作者令 2026-10-29 23:36「中间那个对话 就是进入我跟你的
 * 这个输入框」）：详情全清 + 回聊天根（输入框）；已在聊天根 = noop。
 */
function dockHome() {
  ensureContainer();
  if (stack[0] === 'chat' && stack.length === 1) { emitNav('dock-noop'); return; }
  logEvent('dock-switch', { page: 'chat' });
  goHome();
}

/**
 * 底 dock 切换根页：详情全清 + 根替换（互斥）。同根再点 = 清详情回根。
 * chat home 键（中键）改走 dockHome。
 * @param {string} pageId dock 根 id
 */
export function dockTo(pageId) {
  if (pageId === 'chat') return dockHome();
  if (!PAGE_BY_ID.get(pageId)?.dock) return;
  ensureContainer();
  if (stack[0] === pageId && stack.length === 1) { emitNav('dock-noop'); return; }
  while (stack.length > 1) closePageFace(stack.pop());
  if (stack[0] !== pageId) {
    if (stack[0] !== 'chat') closePageFace(stack[0]);
    stack[0] = pageId;
    logEvent('dock-switch', { page: pageId });
  }
  void ensurePanelContent(pageId);
  renderPages();
  emitNav('dock');
}

/** UI 内统一入口：dock 根走 dockTo，详情走 pushPage；'chat' = 中键 home。 */
export function navigateTo(pageId) {
  if (pageId === 'chat') return dockHome();
  if (!PAGE_BY_ID.has(pageId)) return;
  if (PAGE_BY_ID.get(pageId).dock) return dockTo(pageId);
  if (stack[stack.length - 1] === pageId) { emitNav('push-noop'); return; }
  pushPage(pageId);
}

// ── overlay 面外部关闭对账（watchOverlayClose 扩展到全页面注册表）────

/** @type {HTMLElement[]} 注入的返回钮（拆层移除，桌面零残留）。 */
const injectedBacks = [];

function ensureInjectedBack(hostSel, pageId) {
  const host = document.querySelector(hostSel);
  if (!host || host.querySelector('.mobile-back-injected')) return;
  const back = document.createElement('button');
  back.type = 'button';
  // 统一批：注入返回钮同 g2-fab 基准（与页面壳「<」同材质同几何）。
  back.className = 'mobile-back-btn mobile-back-injected g2-fab';
  back.dataset.page = pageId;
  back.title = backLabel();
  back.setAttribute('aria-label', backLabel());
  back.innerHTML = '<i data-lucide="chevron-left"></i>';
  back.addEventListener('click', () => {
    if (stack[stack.length - 1] === pageId) popPage();
    else popToPage(pageId);
  });
  host.insertBefore(back, host.firstChild);
  injectedBacks.push(back);
  if (typeof lucide !== 'undefined') createIconsIn(back);
}

/** 外部关闭对账注册表：页面 ←→ overlay 状态载体（桌面关闭路径 ⇄ 栈同步弹层）。 */
const CLOSE_WATCH = [
  { page: 'settings', sel: '#settings-overlay', off: (el) => !el.classList.contains('on'), attr: ['class'] },
  { page: 'search', sel: '#search-overlay', off: (el) => !el.classList.contains('on'), attr: ['class'] },
  { page: 'reminder', sel: '#reminder-panel', off: (el) => !el.classList.contains('open'), attr: ['class'] },
  // ⛔ 【退役】todo-unify-entry-collapse 2026-11-04：'todo' 页整面退役 ⇒ 关闭对账
  // 无对象（#todo-page 不再有 .open 开合路径）；原文留档：
  // { page: 'todo', sel: '#todo-page', off: (el) => !el.classList.contains('open'), attr: ['class'] },
  { page: 'daemon', sel: '#daemon-panel', off: (el) => !el.classList.contains('open'), attr: ['class'] },
  { page: 'background', sel: '#bg-dropdown', off: (el) => el.classList.contains('hidden'), attr: ['class'] },
  { page: 'profile', sel: '#memory-overlay', off: (el) => !el.classList.contains('on'), attr: ['class'] },
];

/** overlay 载体建出后才可挂观察者的页面（usage 面板首开建；taskPanel 惰性建）。 */
const overlayReconcileBound = new Map();
function ensureOverlayReconcile(pageId, sel, off, attr) {
  if (overlayReconcileBound.get(pageId)) return;
  const ov = document.querySelector(sel);
  if (!ov) return;
  overlayReconcileBound.set(pageId, true);
  bindReconcile(pageId, ov, off, attr);
}

function bindReconcile(pageId, ov, off, attr) {
  const reconcile = () => {
    const top = stack[stack.length - 1];
    if (top === pageId && off(ov)) {
      stack.pop();
      renderPages();
      emitNav('pop-external');
    }
  };
  const mo = new MutationObserver(reconcile);
  mo.observe(ov, { attributes: true, attributeFilter: attr });
  overlayObservers.push(mo);
}

function watchOverlayClose() {
  for (const w of CLOSE_WATCH) {
    const ov = document.querySelector(w.sel);
    if (ov) bindReconcile(w.page, ov, w.off, w.attr);
  }
  unsubs.push(() => {
    for (const mo of overlayObservers.splice(0)) mo.disconnect();
  });
  // 头像腿（桌面 handler 直开合并面板）：memory-overlay 被外部置 .on 且本层
  // 不在 profile 页 ⇒ pushPage 收编进页面（浮层形态不落地）。与 closeWatch
  // 同源观察（close 腿弹栈 / open 腿进页，一对镜像）。
  const memOv = document.getElementById('memory-overlay');
  if (memOv) {
    let onPrev = memOv.classList.contains('on');
    const mo = new MutationObserver(() => {
      const on = memOv.classList.contains('on');
      const rose = on && !onPrev;
      onPrev = on;
      if (rose && stack[stack.length - 1] !== 'profile' && !stack.includes('profile')) {
        logEvent('profile-open-adopt', {});
        pushPage('profile');
      }
    });
    mo.observe(memOv, { attributes: true, attributeFilter: ['class'] });
    overlayObservers.push(mo);
  }
}

// ── 会话窗收编（对话列表行 → 「会话」详情页）────────────────────────
// 桌面 renderChatModal 每次整建 #fm-chat-overlay（remove + create）；窗体
// .fm-modal re-parent 进 conversation 页体；closeChat remove overlay ⇒ 对账
// 弃收编节点（桌面整建语义下复用无意义）+ 弹页。

function watchChatModal() {
  chatModalMo = new MutationObserver(() => {
    const ov = document.getElementById('fm-chat-overlay');
    const modal = ov?.querySelector('.fm-modal') || null;
    if (modal && chatAdopted?.modal !== modal) {
      // 新窗建出：弃旧槽 + 收编新窗。
      // 🔴 串窗根因修复（设备平级批 2026-10-31，截图②实锤）：旧收编窗被
      // re-parent **出** overlay 进了页体 ⇒ 桌面 renderChatModal 整建时摘除的
      // 只是空 overlay 壳，**旧窗体留在页体内**；此处若只弃引用不摘节点，
      // 新窗 append 后页体里就是上下两个对话窗（各带各的输入条）。故弃槽
      // 必须连同旧窗节点一起摘（节点已不在 DOM 时 remove() 为空操作）。
      chatAdopted?.modal?.remove();
      chatAdopted = null;
      const body = bodyOf('conversation');
      if (!body) return;
      logEvent('chat-adopt', {});
      chatAdopted = { modal };
      body.appendChild(modal);
      if (stack[stack.length - 1] !== 'conversation') pushPage('conversation');
    } else if (!ov && chatAdopted) {
      // 桌面关窗：弃收编节点（其 overlay 已整体摘除）+ 弹页。
      chatAdopted.modal.remove();
      chatAdopted = null;
      if (stack[stack.length - 1] === 'conversation') popPage();
    }
  });
  chatModalMo.observe(document.body, { childList: true, subtree: true });
  unsubs.push(() => { chatModalMo?.disconnect(); chatModalMo = null; chatAdopted = null; });
}

// ── 底 dock（五栏根页切换器；作者令 2026-10-29 小红书样式基准）───────
// 白底固定底栏、图标在上 + 文字标签在下、五等分、active 态可辨（克制形态：
// 强调色 icon/label）、未读红点徽标镜像桌面既有徽标读数。
// 中键改版批（同日 23:36；收形批②③ 2026-10-31 22:50 修订）：中键 = 小红书
// 中心键形态 —— sapphire 主色实心圆收进 dock 边界内（lift 退役）、**只留图案**
// （「对话」文字标签退役）、无头像回落 stand logo（气泡字形退役），点 = 回主
// 对话输入框（chat home）。

/** @type {Map<string, HTMLElement>} dock id → 键 */
const dockKeys = new Map();
/** @type {MutationObserver|null} 徽标镜像观察者。 */
let badgeMo = null;

function buildDock() {
  if (dockEl) return dockEl;
  dockEl = document.createElement('nav');
  dockEl.id = 'mobile-dock';
  dockEl.setAttribute('role', 'tablist');
  dockEl.setAttribute('aria-label', tt('主导航', 'Main navigation'));
  const DEFS = DOCK_ORDER.map(dockDef);
  for (const def of DEFS) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'dock-key';
    btn.dataset.dock = def.id;
    btn.setAttribute('role', 'tab');
    btn.setAttribute('aria-selected', 'false');
    const badge = document.createElement('span');
    badge.className = 'dock-badge';
    badge.hidden = true;
    if (def.id === 'chat') {
      // 中键（作者令 23:36；收形批② 10-31 22:50 修订）：中心键容器只留图案 ——
      // 「对话」文字标签随批退役（可见标签 = 图标即语义），aria-label 承接
      // 可及性；图标位由 paintDockCenterAvatar 按「agent 头像 / stand logo
      // 回落」两态填装（形态见 mobileLayer.css 中键块）。
      btn.classList.add('dock-key-center');
      btn.setAttribute('aria-label', tt(def.zh, def.en));
      const center = document.createElement('span');
      center.className = 'dock-center';
      btn.append(center, badge);
    } else {
      const label = document.createElement('span');
      label.className = 'dock-label';
      label.textContent = tt(def.zh, def.en);
      const icon = document.createElement('span');
      icon.className = 'dock-ic';
      icon.innerHTML = `<i data-lucide="${def.icon}"></i>`;
      btn.append(icon, label, badge);
    }
    btn.addEventListener('click', (e) => {
      // 同 hub 行：桌面面 outside-close 监听 document 冒泡，入口键停冒泡防误关。
      e.stopPropagation();
      touchLayer();
      logEvent('dock-click', { page: def.id });
      if (def.id === 'chat') dockHome();
      else dockTo(def.id);
    });
    dockEl.appendChild(btn);
    dockKeys.set(def.id, btn);
  }
  document.body.appendChild(dockEl);
  paintDockCenterAvatar(); // 先装字形/头像再统一 createIcons（<i data-lucide> 替换腿）
  if (typeof lucide !== 'undefined') createIconsIn(dockEl);
  // 头像落点汇合订阅（本机上传/跨端收敛/会话切换都经 agentProfile.paintSlot）。
  unsubs.push(onAgentAvatarChange(paintDockCenterAvatar));
  const onLocale = () => {
    for (const def of DEFS) {
      const k = dockKeys.get(def.id);
      if (!k) continue;
      // 收形批②：中键无可见标签（aria-label 仍随 locale 更新）。
      const lbl = k.querySelector('.dock-label');
      if (lbl) lbl.textContent = tt(def.zh, def.en);
      if (def.id === 'chat') k.setAttribute('aria-label', tt(def.zh, def.en));
    }
    if (dockEl) dockEl.setAttribute('aria-label', tt('主导航', 'Main navigation'));
  };
  window.addEventListener('locale-changed', onLocale);
  unsubs.push(() => window.removeEventListener('locale-changed', onLocale));
  watchDockBadges();
  return dockEl;
}

/** 中键图标两态（作者令 23:36「图标应该用给agent设置的头像」；收形批③修订）：
 *  读链 = 会话头同一本地条目（agentProfile.agentAvatarDataUrl，跨端 sha 收敛同键），
 *  节点走 avatarRender 解码复用池；无头像回落 **stand logo**（brand.logoUrl/
 *  logoDarkUrl 双主题 picture，与「我」入口同货源 —— 作者令 10-31 22:50：
 *  不显示空白/默认人形；原 message-circle 气泡字形随批退役）。 */
function paintDockCenterAvatar() {
  const holder = dockKeys.get('chat')?.querySelector('.dock-center');
  if (!holder) return;
  const dataUrl = agentAvatarDataUrl();
  const img = dataUrl ? avatarImgNode(dataUrl) : null;
  if (img) {
    img.className = 'dock-center-img';
    img.draggable = false;
    img.alt = '';
    holder.replaceChildren(img);
    holder.classList.add('has-avatar');
    return;
  }
  holder.classList.remove('has-avatar');
  if (holder.querySelector('.dock-center-logo')) return; // logo 已就位（幂等零重建）
  holder.innerHTML =
    `<picture><source media="(prefers-color-scheme: dark)" srcset="${brand.logoDarkUrl}">` +
    `<img class="dock-center-logo" src="${brand.logoUrl}" alt=""></picture>`;
}

/** 徽标镜像：桌面徽标读数（#messages-badge/#contacts-badge，手机层按钮不可见
 *  但 WS 计数流照写）→ dock 徽标。零新轮询、零第二判据。
 *
 *  数字角标（作者令 2026-10-30 08:29 逐字「就像微信一样就行了，有新消息在消息
 *  按钮的右上角加数字角标就行」）：消息键 = **数字角标**，读数与文案逐字取桌面
 *  `#messages-badge`（messages.js `updateBadge` → `setActivityBadge` 已归一
 *  「>99 ⇒ 99+」+ 空串/0 ⇒ hidden），手机层零换算零第二实现。联系人键口径不变
 *  （布尔红点 —— 该键语义 = 好友待处理请求，非「新消息」，作者令只针对消息键）。
 *
 *  🔴 写回必须**幂等**（同值不写）：`watchDockBadges` 挂的是 body 子树
 *  childList 观察器，而 `textContent =` 无论值是否相同都会重建子节点 ——
 *  无守卫的赋值 = 自触发无限循环（本轮实测：D3 3 分钟超时、页面假死）。 */
function applyDockBadges() {
  const msgs = document.getElementById('messages-badge');
  const cts = document.getElementById('contacts-badge');
  const setBadge = (dockId, on, text = '') => {
    const b = /** @type {HTMLElement|null} */ (dockKeys.get(dockId)?.querySelector('.dock-badge'));
    if (!b) return;
    const want = on ? text : '';
    if (b.hidden !== !on) b.hidden = !on;
    if (b.textContent !== want) b.textContent = want;
    b.classList.toggle('is-count', on && text !== ''); // 数字角标形态 vs 纯红点
  };
  const msgOn = !!msgs && !msgs.hidden && msgs.textContent !== '';
  setBadge('messages', msgOn, msgOn ? String(msgs.textContent) : '');
  setBadge('contacts', !!cts && !cts.hidden && cts.textContent !== '');
}

/** 已观察的徽标节点（徽标可能晚于 buildDock 才建出 —— 封存态剖面里好友面
 *  不初始化，D3 判据实测：build 时查无节点 ⇒ 观察从未绑定，晚写的徽标读数
 *  不触发镜像）。观察者挂 body 子树 childList 兜底捕捉晚建节点 + 已绑节点的
 *  读数变化，回调统一走 bind+apply。 */
const badgeWatched = new Set();
function syncDockBadges() {
  if (!badgeMo) return;
  for (const sel of ['messages-badge', 'contacts-badge']) {
    const el = document.getElementById(sel);
    if (el && !badgeWatched.has(el)) {
      badgeMo.observe(el, { attributes: true, attributeFilter: ['hidden'], childList: true, characterData: true, subtree: true });
      badgeWatched.add(el);
    }
  }
  applyDockBadges();
}

function watchDockBadges() {
  badgeMo = new MutationObserver(() => syncDockBadges());
  badgeWatched.clear();
  badgeMo.observe(document.body, { childList: true, subtree: true });
  syncDockBadges();
  unsubs.push(() => { badgeMo?.disconnect(); badgeMo = null; badgeWatched.clear(); });
}

function syncDockActive() {
  const home = stack[0];
  for (const [id, btn] of dockKeys) {
    const on = home === id;
    btn.classList.toggle('active', on);
    btn.setAttribute('aria-selected', on ? 'true' : 'false');
  }
}

// ── 画板事件 → canvas 页同步（canvas-open 拦截：桌面 openCanvas ⇒ 进页）─

function watchCanvasEvents() {
  // 桌面开画板/切卡（聊天文件卡等路径）⇒ 手机层进 canvas 独立页（bottom sheet
  // 退役）。三条闸：①canvas-open 在场；②启动抑制窗 3s（boot 恢复/兜底路径
  // restoreTabs→openPlugins 先于/紧随本层把 canvas-open 置真 —— 那是恢复不是
  // 用户开卡，刷新归聊天根契约不被拽页）；③已不在 canvas 页（页内切换不抢）。
  // 触发源 = 画板 open 类的 **false→true 迁移沿** + canvas-tab-switched
  // （已开画板时点文件卡 = openCanvas 幂等无类变化，靠切卡事件补触发）。
  let bootedAt = Date.now();
  const clearPending = () => {
    try { (/** @type {any} */ (window)).__nfPendingCanvasOpen = 0; } catch { /* ignore */ }
  };
  // 显式收养（无启动抑制闸）：nf:canvas-open-intent = 用户点击 Pop/文件卡的即时
  // 意图（canvas.js Pop 重开腿派发）——点击不可能是 boot 恢复，故不受 3s 窗约束；
  // 「canvas-open 已真」的老会话（restoreTabs 恢复后腿）也只有这条路能自动弹页。
  const adoptNow = (why) => {
    if (!document.body.classList.contains('canvas-open')) return;
    if (stack[stack.length - 1] === 'canvas') return;
    // 工作台误路由修复（作者令 2026-10-30「点工作台弹出的是 canvas」）：项目/
    // 图谱/插件面板页在栈顶时，canvas-open 上升沿是面板页开页签的副产物——
    // 面板页自己承载 pane，自动弹 canvas 页会把刚 push 的面板详情顶掉（canvas
    // 页只呈现文档卡，不呈现面板 pane）。文档卡路径（Pop/文件卡）栈顶恒非面板页。
    if (PANEL_PAGE_IDS.has(stack[stack.length - 1])) return;
    clearPending();
    logEvent('canvas-open-adopt', { why });
    dockTo('canvas');
  };
  const maybeAdopt = (why) => {
    if (bootedAt && Date.now() - bootedAt <= 3000) return;
    adoptNow(why);
  };
  const onIntent = () => adoptNow('open-intent');
  document.addEventListener('nf:canvas-open-intent', onIntent);
  // 点击早于本层挂载的竞态补放（canvas.js Pop 重开腿记旗）：慢机冷启时用户在
  // navController 挂载前点完 Pop 图片——open 沿与切卡事件都已错过，无回放则
  // 「点了不弹、手动开才弹」。旗 = 显式用户开卡意图（boot 恢复不记旗），不受
  // 上方 3s 启动抑制闸约束； freshness 30s + canvas-open 在场双判据，陈旧旗/
  // 用户已手动关面板都不拽页。
  try {
    const pending = (/** @type {any} */ (window)).__nfPendingCanvasOpen;
    if (pending && Date.now() - pending < 30000 && document.body.classList.contains('canvas-open')) {
      clearPending();
      if (stack[stack.length - 1] !== 'canvas') {
        logEvent('canvas-open-adopt', { why: 'pending-replay' });
        dockTo('canvas');
      }
    }
  } catch { /* ignore */ }
  const onSwitch = () => { syncCanvasPage(); maybeAdopt('tab-switched'); };
  document.addEventListener('canvas-tab-switched', onSwitch);
  document.addEventListener('canvas-tab-closed', () => { syncCanvasPage(); maybeAdopt('tab-closed'); });
  document.addEventListener('canvas-independent-changed', onSwitch);
  // 「手机接入」入口行已撤除（作者令 2026-10-31 22:55）——nf:mobile-social-entry
  // 事件环外发腿随之摘除，联系人页无 social 页 push 路径。
  // ⛔ 【退役】todo-unify-entry-collapse 2026-11-04：消息列表「待办事项」入口行已撤
  // ⇒ 它的手机层推页腿（事件环外发 nf:mobile-todo-entry → pushPage('todo')）随之
  // 摘除；手机端待办唯一入口 = 顶带待办胶囊（点开即展开面板）。原文留档：
  // document.addEventListener('nf:mobile-todo-entry', () => pushPage('todo'));
  document.addEventListener('nf:mobile-goto', (e) => {
    const page = (/** @type {CustomEvent<{page?: string}>} */ (e)).detail?.page;
    if (page && PAGE_BY_ID.get(page)?.dock) dockTo(page);
  });
  let canvasOpenPrev = document.body.classList.contains('canvas-open');
  const mo = new MutationObserver(() => {
    const open = document.body.classList.contains('canvas-open');
    const rose = open && !canvasOpenPrev;
    canvasOpenPrev = open;
    if (rose) maybeAdopt('open-edge');
  });
  mo.observe(document.body, { attributes: true, attributeFilter: ['class'] });
  unsubs.push(() => {
    document.removeEventListener('canvas-tab-switched', onSwitch);
    document.removeEventListener('canvas-tab-closed', onSwitch);
    document.removeEventListener('canvas-independent-changed', onSwitch);
    document.removeEventListener('nf:canvas-open-intent', onIntent);
    mo.disconnect();
  });
}

// ── 激活 / 拆除 ─────────────────────────────────────────────────────

export function activateNav() {
  (/** @type {any} */ (window)).__nfMobileLayerEvents = navLayerEvents();
  // 堆叠视角只读探针（性能验收 I9：首建耗时 / 缓存命中数 / 缓存条目）。
  (/** @type {any} */ (window)).__nfDeckProbe = () => ({
    lastMs: +deckSnapLastMs.toFixed(2),
    maxMs: +deckSnapMaxMs.toFixed(2),
    builds: deckSnapBuilds,
    cacheSize: deckSnapCache.size,
    pending: deckSnapPending.size,
    open: stackOpen,
    cards: deckUI ? deckUI.cards.size : 0,
    snapped: deckUI ? [...deckUI.cards.values()].filter((c) => c.querySelector('.mf-deck-thumb.has-snap')).length : 0,
  });
  (/** @type {any} */ (window)).__nfDeckProbeReset = () => { deckSnapBuilds = 0; deckSnapMaxMs = 0; deckSnapLastMs = 0; };
  ensureContainer();
  buildDock();
  watchCanvasContent();
  watchOverlayClose();
  watchCanvasEvents();
  watchChatModal();
  initSettingsSub({ push: pushPage });
  // 激活即收编启动恢复已建成的面板 pane（同步路径；异步路径走 watchCanvasContent）。
  for (const def of PAGES) if (def.tabId) adoptPane(def.id);
  renderPages();
  // 只读探针（App 壳/深链留缝点）。
  (/** @type {any} */ (window)).__nfMobileNav = {
    get stack() { return navState().stack; },
    get top() { return navState().top; },
    get home() { return navState().home; },
    get dock() { return [...DOCK_ORDER]; },
    version: 'mobile dock batch (five-tab dock + workbench root + settings-as-hub-row + chat-home center key + fullscreen pages + canvas page) + popup-retire (mfs sheets + settings sub-pages)',
  };
  return function teardownNav() {
    for (const fn of unsubs.splice(0)) { try { fn(); } catch { /* ignore */ } }
    // pane 原样归还画板内容区（逆序归还）。
    for (const a of paneAnchors.reverse()) {
      try {
        if (a.pane && a.parent && a.parent.isConnected) a.parent.insertBefore(a.pane, a.next);
      } catch { /* 树已变：放弃归还 */ }
    }
    paneAnchors.length = 0;
    // canvas 页活动文档 pane 归还（双页叠开修复批①：与离页同一收口单点）。
    releaseAdoptedDoc();
    // overlay 内容面原位归还。
    for (const id of [...overlayAnchors.keys()].reverse()) restoreOverlayFace(id);
    overlayAnchors.clear();
    if (chatAdopted) { try { chatAdopted.modal.remove(); } catch { /* ignore */ } chatAdopted = null; }
    if (dockEl) { dockEl.remove(); dockEl = null; }
    dockKeys.clear();
    canvasUI = null;
    // 标签栏重做批：卡叠视图/小点手势句柄随页面树一并归零（重挂时重建，
    // 防旧节点引用悬挂 + stackOpen 卡在 true 导致新页首帧就开浮面）。
    deckUI = null;
    stackOpen = false;
    deckDragDy = 0;
    hubBodies.clear();
    for (const b of injectedBacks.splice(0)) b.remove();
    document.body.classList.remove('mf-page-open');
    if (container) { container.remove(); container = null; }
    sections.clear();
    stack = ['chat'];
    layerTouched = false;
    overlayReconcileBound.clear();
    delete (/** @type {any} */ (window)).__nfMobileNav;
    delete (/** @type {any} */ (window)).__nfDeckProbe;
  };
}
