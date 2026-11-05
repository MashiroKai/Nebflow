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
import { registerProvider, rankCandidates } from './tokenComplete.js';

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
      subtitle: String(r.detail || '').replace(/\s+/g, ' ').slice(0, 60),
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
  // 全名逐字插入（`#` 由引擎前的 insertText 提供，尾随空格由引擎补）。
  insertText: (item) => '#' + item.name,
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
