// mentionComplete.js — `@` 项目补全的**数据面 + provider 注册**（mention-routing
// 批 2026-09-27 立；待办结构化批 2026-11-05 把弹层机器上移到 tokenComplete.js）.
//
// 本文件只剩两块职责：
//   ① 项目清单**唯一**的取数与缓存（GET /api/projects，60s TTL、单飞、失败退避）
//      —— 渲染侧（utils.js 的 @ 提及）、任务列表、`@` 补全三处共用这一份快照；
//   ② 把 `@` 注册成 tokenComplete 引擎的一个 provider。
// 弹层 DOM / 分词 / 键盘 / 插入 / 排序全部在 tokenComplete.js（一份实现，
// `#` 待办复用同一条路 —— 见 todoComplete.js），本文件不再有第二套。
//
// `@` 的触发与交互契约（既有口径，逐字保留）：
//   · `@` 前是 ASCII 字母/数字不触发（`foo@` 邮件）；行首、空白、汉字、中文标点
//     一律开 token —— 中文句子里随处可用。
//   · token 到下一个空白为止；继续输入即过滤。
//   · ↑↓ 选择、Tab/Enter 确认、Esc 关闭；插入 `@<全名> `（一个尾随空格）。
//   · 排序 = rankCandidates：名称精确 > 前缀 > 中段 > **拼音** > 工作区路径命中，
//     同级按最近创建/活跃。拼音档（拼音匹配批新增）让 `dian` 也能命中「电离能损」。
//
// 宿主纪律：
//   · IME 判定单点在 imeGuard（本文件不读 composition 原始标志）。
//   · 渲染只走 textContent（项目名/路径是数据不是标记）。
//   · 样式沿用 `.mention-dropdown` 玻璃配方，不新增设计令牌。

import { t } from './i18n.js';
import { fetchProjects } from './nodeData.js';
import { registerProvider, rankCandidates } from './tokenComplete.js';

// ── 项目清单缓存（惰性，≤60s TTL，单飞请求）──────────────────────────────
const MENTION_CACHE_TTL_MS = 60_000;
const MENTION_RETRY_BACKOFF_MS = 10_000;
/** @type {any[] | null} */
let cacheProjects = null; // 上次成功的结果（数组）或 null
let cacheAt = 0;          // 上次成功的 epoch ms（0 = 从未成功）
let failAt = 0;           // 上次失败的 epoch ms（重试退避）
/** @type {Promise<any[]> | null} */
let inflight = null;      // 在飞请求（去重）

/** 解析已挂载项目清单，走 TTL 缓存。永不 reject：失败时回落缓存，没缓存回落
 *  []（cacheAt 保持 0，渲染侧据此区分「没有项目」与「清单不可用」并保持安静）。 */
function getProjectsCached() {
  const now = Date.now();
  if (cacheProjects && now - cacheAt < MENTION_CACHE_TTL_MS) {
    return Promise.resolve(cacheProjects);
  }
  // 刚失败过：先用手上的，别每个按键都去敲网关，等退避窗口过去。
  if (now - failAt < MENTION_RETRY_BACKOFF_MS) {
    return Promise.resolve(cacheProjects || []);
  }
  if (!inflight) {
    inflight = fetchProjects()
      .then((projects) => {
        cacheProjects = Array.isArray(projects) ? projects : [];
        cacheAt = Date.now();
        failAt = 0;
        listGen++; // 渲染侧 LRU 代次 —— 清单变了缓存必须失效
        notifyProjectsArrival(); // 一次性补齐钩子（main.js）
        return cacheProjects;
      })
      .catch(() => {
        failAt = Date.now();
        return cacheProjects || [];
      })
      .finally(() => { inflight = null; });
  }
  return inflight;
}

// ── 共享清单读口 ─────────────────────────────────────────────────────────
// 消息渲染器（utils.js，main.js 注入 provider）与下方点击委托读**同一个**缓存
// —— 客户端只有一份项目清单、一份取数缓存（就是本模块）。`listGen` 每次成功
// 取数自增，渲染侧 LRU 用它做代次，缓存 HTML 就不可能和当前清单矛盾。
let listGen = 0;
/** @type {any[]} */
const EMPTY_PROJECTS = [];

/**
 * 已挂载项目清单的同步快照。零拷贝：消费方按只读处理（取数整体换引用，从不
 * 原地改，返回的快照跨重取保持稳定）。
 * @returns {Array<{name: string, workspace?: string, createdAt?: number}>}
 */
export function getProjectsSnapshot() {
  return Array.isArray(cacheProjects) ? cacheProjects : EMPTY_PROJECTS;
}

/**
 * 共享清单代次 —— 每次成功取数自增。
 * @returns {number}
 */
export function getProjectsListGen() {
  return listGen;
}

/** 预热共享清单（发后不管）。getProjectsCached 永不 reject，失败由退避吸收。 */
export function preheatProjects() {
  void getProjectsCached();
}

// 首次到达钩子：清单第一次变非空时，渲染侧跑**一次**定向 DOM 补齐 —— 之前已
// 按纯文本画出来的气泡不用用户动手也能长出 @ 提及 span。
/** @type {Array<() => void> | null} */
let arrivalCbs = null;

/**
 * 订阅「首次非空到达」。每次页面加载最多触发一次（清单已就绪则立即触发）。
 * @param {() => void} cb
 */
export function onProjectsArrival(cb) {
  if (cacheProjects && cacheProjects.length > 0) {
    cb();
    return;
  }
  (arrivalCbs ??= []).push(cb);
}

function notifyProjectsArrival() {
  if (!cacheProjects || cacheProjects.length === 0 || !arrivalCbs) return;
  const cbs = arrivalCbs;
  arrivalCbs = null;
  for (const fn of cbs) {
    try { fn(); } catch (_) { /* 一个坏订阅者不能吃掉其余 */ }
  }
}

// ── provider 注册（`@` 项目）────────────────────────────────────────────
registerProvider({
  id: 'project',
  trigger: '@',
  titleKey: 'mention.title',
  emptyKey: 'mention.noMatch',
  /**
   * 候选源：共享清单 → 补全行形状。项目名是 `@` 引用的解析键（逐字，含空格与
   * 中文）；工作区路径既作副标题也作 detail 档（路径命中排在名称档之后，与旧版
   * 「名称子串过滤」行为兼容且更宽一档）。
   * @returns {Promise<Array<{name: string, subtitle: string, detail: string, createdAt: number}>>}
   */
  load: () => getProjectsCached().then((projects) => projects
    .filter((p) => p && typeof p.name === 'string' && p.name)
    .map((p) => {
      const workspace = typeof p.workspace === 'string' ? p.workspace : '';
      return {
        name: p.name,
        subtitle: workspace,
        detail: workspace,
        createdAt: Number.isFinite(p.createdAt) ? Number(p.createdAt) : 0,
      };
    })),
  rank: (items, query) => rankCandidates(items, query),
  // 全名逐字插入（含空格 / 中文名），零引号零转义 —— 提示词层按全名匹配。
  insertText: (item) => '@' + item.name,
});

// ── 点击→文件浏览器 委托 ─────────────────────────────────────────────────
// 一个 document 级委托监听覆盖所有渲染出来的 .mention-tag —— 后加的气泡、
// 主窗口与 agent 弹窗一视同仁。规范名对着**同一份**共享快照解析：已卸载（或
// 无工作区）的名字是静默 no-op，绝不报错。
document.addEventListener('click', (e) => {
  const target = e.target;
  if (!(target instanceof Element)) return;
  const tag = target.closest('.mention-tag');
  if (!tag) return;
  const name = tag.getAttribute('data-project') || '';
  if (!name) return;
  const project = getProjectsSnapshot().find((p) => p && p.name === name);
  const workspace = project && typeof project.workspace === 'string' ? project.workspace : '';
  if (!workspace) return;
  // 与 projectTab 的「打开工作区」同一条懒加载 + toast 先例：静态 import 会把
  // 文件树拖进每次页面加载。
  import('./explorer.js').then(({ openExplorerAt }) => {
    openExplorerAt(workspace);
    window.__showToast?.(t('project.workspaceOpened', { path: workspace }), 'info');
  }).catch(() => window.__showToast?.(t('project.openWorkspaceFail'), 'error'));
});
