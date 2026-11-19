// todoComplete.js — `#` 待办引用补全的 provider（待办结构化批, 2026-11-05 作者令
// 「允许使用 # 来引用待办。交互逻辑和 @ 项目一样」）.
//
// 与 `@` 的复用面（**同一套**，无平行实现）：
//   弹层 DOM / 分词 / ↑↓ / Enter·Tab / Esc / 外点关闭 / 异步竞态 = tokenComplete.js
//   候选排序（名称精确 > 前缀 > 中段 > 拼音 > detail，同级最近活跃）= rankCandidates
// 本文件只提供 `#` 自己的三件事：**数据面**（待办列表）、**门禁开关**、**插入形态**。
//
// ── 门禁（比 `@` 多三条，宁可漏不要误）───────────────────────────────────
//   · 起始门：`#` 前是 ASCII 字母/数字不触发（`C#`、`a#1`），行首/空白/汉字/
//     中文标点之后触发 —— 与 `@` 逐字同一口径；
//   · blockDigitStart：`#` 后紧跟数字不触发 —— 井号+纯数字是任务号语义（台账号
//     那种），留给它，不抢；
//   · blockHexColor：`#` 后是 3/4/6/8 位纯十六进制不触发 —— 那是 CSS 色值形状；
//   · codeGate：代码围栏 / 行内代码内一律 inert（不弹、不插、不改写）。
// 判据是**形状**而非"上下文语义"：形状判据比"看前后文猜是不是颜色"更稳，
// 代价是名字恰好长得像色值/数字的候选要靠手输（宁漏不误）。
//
// ── 插入形态：`#<待办名>` ────────────────────────────────────────────────
// 选它的理由：
//   ① 与 `@<项目名>` 同形，一套解析口径、一套用户心智；
//   ② 可读 —— 消息里直接看得出引的是哪个待办，不出现裸 id；
//   ③ 可解析 —— 渲染侧按「当前待办清单最长名匹配」识别（与 `@` 的 longest-match
//      同一算法），识别不到就**原样显示**，绝不吞字；
//   ④ 稳定 —— 待办库按 name upsert（同名替换），所以 name 在同一账本内唯一，
//      不需要 `#名字·id` 这类复合形态。
// 代价（如实申报）：待办改名后旧消息里的 `#旧名` 变成未识别文本（原样显示），
// 不会跳到错误的待办 —— 这是"宁可漏"的选择。

import { t } from './i18n.js';
import { todoStore } from './todoStore.js';
import { registerProvider, rankCandidates, insertIntoComposer } from './tokenComplete.js';
import state from './state.js';
import { chatViews } from './chatView.js';

/**
 * `#<待办名>·<id>` —— 引用形态的**唯一**构造函数。
 *
 * 补全 provider 的 `insertText` 与消息流待办卡片的点击腿（todo-card-click-left
 * 批 2026-11-18 作者令「点卡片 → 对话框有 # 的待办引用」）共用它：形态只有一份，
 * 渲染侧（utils.js matchTodoAt）与插入侧才不可能漂移。
 * @param {string|null|undefined} name 待办名（= 列表行显示的那一段）
 * @param {string|null|undefined} id 待办 id（身份段；缺省时只留名字）
 * @returns {string} '' = 拿不到名字，调用方不得插入
 */
export function todoRefText(name, id) {
  const n = String(name == null ? '' : name).trim();
  if (!n) return '';
  const i = String(id == null ? '' : id).trim();
  return '#' + n + (i ? '·' + i : '');
}

/**
 * 按 id 取待办的引用身份 —— 数据面与 `#` 补全**同一来源**（todoStore 的未完成行；
 * name 是引用键、id 是身份段；渲染侧 utils.js 的待办清单同样取 `openItems()`）。
 * 取不到（行已完成/已取消/不在本机会话账本里）⇒ null，调用方回落到卡片自己
 * 显示的名字：`#` 引用本就是「宁可漏不要误」的形态（见本文件头注）。
 * @param {string|null|undefined} todoId
 * @returns {{name: string, id: string}|null}
 */
export function todoRefIdentity(todoId) {
  const id = String(todoId == null ? '' : todoId).trim();
  if (!id) return null;
  const rows = todoStore.openItems() || [];
  const row = rows.find((r) => r && String(r.id) === id);
  const name = row ? String(row.name || '').trim() : '';
  return name ? { name, id } : null;
}

/**
 * 把一条待办的 `#` 引用插进**当前对话框**（卡片点击腿的唯一入口）。
 *
 * 目标解析：活动视图的 composer → primary 视图的 composer（输入框是 shell 级
 * 单例，视图切走时仍在 DOM 里）。两条兜底行为（「没有可输入的对话框」不许静默）：
 *   · 输入框在 DOM 里但被别的面盖住（手机层推了详情页）⇒ 先把 chat home 拉回
 *     前台（navController.navigateTo('chat') = 中键语义：清详情 + 回聊天根）再
 *     聚焦 —— 用户点完卡片看得见引用落在框里；
 *   · 输入框根本取不到（被拆除 / display:none）⇒ 返回 false + 可见提示条。
 *
 * @param {{name?: string, id?: string}|null} identity
 * @returns {boolean} 是否插入成功
 */
export function insertTodoReference(identity) {
  const text = todoRefText(identity && identity.name, identity && identity.id);
  if (!text) return false;
  const input = activeComposerInput();
  if (!input) {
    // 提示条走本文件下方同一惯用法（window.__showToast?），不静态引 modal.js
    // ——`input.js → todoComplete.js` 已经在图里，新增静态边只会加深环。
    window.__showToast?.(t('todoComplete.composerMissing'), 'info');
    return false;
  }
  // 手机层才谈「被盖住」：桌面档 compose 不可见的多半是 display:none 的宿主，
  // 拨手机导航只会白建一套手机容器（副作用大于收益），故这里加层判定。
  if (document.body.classList.contains('mobile-layer') && composerCovered(input)) {
    // 动态 import：静态引手机导航模块会把 tokenComplete 拖进手机层初始化链。
    // 出口只用**已导出**的 API：navigateTo('chat') = dockHome（中键语义），
    // popToRoot 兜底（旧壳）。dockHome 本身是模块私有函数，不可从外部叫。
    import('./mobile/navController.js')
      .then((nav) => {
        if (!nav) return;
        if (typeof nav.navigateTo === 'function') nav.navigateTo('chat');
        else if (typeof nav.popToRoot === 'function') nav.popToRoot();
      })
      .catch(() => { /* 模块缺席：输入框本来就在前台 */ });
  }
  return insertIntoComposer(input, text + ' ');
}

/** 当前可用的 composer 输入框（活动视图优先，primary 兜底）。 */
function activeComposerInput() {
  const pick = (view) => {
    const el = view && view.dom && view.dom.input;
    return el && el.isConnected && typeof el.value === 'string' ? el : null;
  };
  const view = state.getActiveView && state.getActiveView();
  return pick(view) || pick(chatViews.primary);
}

/** 输入框当前是否被别的面盖住（命中测试为中心点反查，宽度 0 = 自身不可见）。 */
function composerCovered(input) {
  const r = input.getBoundingClientRect();
  if (r.width <= 0 || r.height <= 0) return true;
  const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
  return !(hit && (input === hit || input.contains(hit)));
}

/**
 * 待办行 → 补全候选。name 是插入与解析的键（= 列表行显示的那一段）；
 * detail 进排序的最后一档。createdAt 用作同级 tie-break。
 * @returns {Array<{name: string, subtitle: string, detail: string, createdAt: number, ref: any}>}
 */
function loadTodos() {
  const rows = todoStore.openItems() || [];
  return rows
    .filter((r) => r && !r.completed && typeof r.name === 'string' && r.name)
    .map((r) => ({
      name: r.name,
      // 副标题 = detail 的首行摘要（长文本收在折叠区里，不在这里铺开）
      subtitle: [r.project, r.id, String(r.detail || '').replace(/\s+/g, ' ').slice(0, 60)].filter(Boolean).join(' · '),
      detail: String(r.detail || ''),
      createdAt: Number.isFinite(r.createdAt) ? Number(r.createdAt) : 0,
      ref: r,
    }));
}

registerProvider({
  id: 'todo',
  trigger: '#',
  titleKey: 'todoComplete.title',
  emptyKey: 'todoComplete.noMatch',
  codeGate: true,
  blockDigitStart: true,
  blockHexColor: true,
  load: () => Promise.resolve(loadTodos()),
  rank: (items, query) => rankCandidates(items, query),
  // 全名逐字插入（`#` 由引擎前的 insertText 提供，尾随空格由引擎补）；
  // 形态 = todoRefText 单点（与消息流卡片的点击腿同一条构造函数）。
  insertText: (item) => todoRefText(item.name, item.ref && item.ref.id),
});

// ── 点击→待办面板 委托 ───────────────────────────────────────────────────
// 与 mentionComplete 的点击委托同款：一个 document 级监听覆盖所有渲染出来的
// .todo-tag（流式/历史/重放各腿一视同仁）。
//
// 行为：行在 DOM 里（侧栏任务列表已挂）⇒ 滚到它并**点开编辑面**（编辑面里
// detail 可读可改）；行不在 DOM 里 ⇒ 把 detail（缺则名字）作为提示条展示 ——
// 只呈现事实，不做"请去某某面板"的路径指引（自解释铁律 8）。
document.addEventListener('click', (e) => {
  const target = e.target;
  if (!(target instanceof Element)) return;
  const tag = target.closest('.todo-tag');
  if (!tag) return;
  const id = tag.getAttribute('data-todo-id') || '';
  const name = tag.getAttribute('data-todo') || '';
  if (!id && !name) return;
  const row = id
    ? document.querySelector(`.tfl-row[data-id="${CSS.escape(id)}"]`)
    : null;
  if (row instanceof HTMLElement) {
    row.scrollIntoView({ block: 'center' });
    const body = row.querySelector('.tfl-row-main');
    if (body instanceof HTMLElement) body.click();
    return;
  }
  const detail = tag.getAttribute('data-todo-detail') || '';
  window.__showToast?.(detail || name || t('todoComplete.detailUnavailable'), 'info');
});
