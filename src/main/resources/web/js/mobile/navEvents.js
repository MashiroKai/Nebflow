// navEvents.js — 手机层事件流水（QA 探针 `window.__nfMobileLayerEvents` 的数据源）。
//
// 抽为叶子模块（循环门禁修复批 2026-11-04）：这流水原本住在 navController.js
// （`__nfEvents` + `logEvent`），而 taskPanel.js 为了打 3 条事件静态 import
// navController —— 反向 navController 又要 import taskPanel 的
// ensureTaskPanel/closeTaskPanel（页面收编的真集成）⇒ 成 SCC[2]
// （scripts/check-circular.mjs）。被断的是环上**最薄**的边：1 个符号、纯追加写、
// 零依赖（不 import 任何模块）——正是门禁处方第三条「把共享的纯函数提取到独立
// 模块」的形态。抽出后聚合面（导航实现）与遥测面（事件流水）分离。
//
// 纪律：本文件**零依赖零 DOM**。`logEvent` 语义逐字不变（{t, kind, ...extra}
// 追加进同一条流水）；读侧仍是 navController 在 activateNav 里挂到 window 上的
// **同一个数组引用**（`navLayerEvents()`），既有采集脚本 / spec 读数零变化。
//
// 用法（写侧）：`import { logEvent } from './navEvents.js';` —— 不要为了打事件
// 去 import navController.js（那会把导航整条依赖链拖进调用方的静态闭包）。

/** 事件流水本体（对象恒等由本模块独占；外部只经 logEvent 写、navLayerEvents 读）。 */
const events = [];

/** 追加一条层事件。kind = 事件名（字符串），extra = 附加字段（浅展开进条目）。 */
export function logEvent(kind, extra = {}) {
  events.push({ t: Date.now(), kind, ...extra });
}

/** 流水数组引用（只读消费面：navController 挂 window.__nfMobileLayerEvents）。 */
export function navLayerEvents() {
  return events;
}
