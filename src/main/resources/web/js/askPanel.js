// askPanel.js — Ask 面板门面（ask-unify 规格第一期第一批，P4 2026-11-21）。
//
// 作者约束（逐字）：「面板内 ask 与 canvas ask 同一组件（同一 FAB/窗体/动画），
// 只换应用点」「使用一样的图标和动画，只是应用点不一样」。门面把这套固化下来：
// 所有 Ask 面板（canvas 文件 ask、选区 ask；后续 GitViz / 办公格式窗口）都经
// `createAskPanel(options)` 构造，**不允许**消费方直接调用 `createAskDialog`
// 形成双入口。静态闸：scripts/check-askpanel-facade.mjs。
//
// 分层：`askDialog.js` = 内部对话引擎（DOM 构形 + 转录 + 流式 + 提交/中断）；
// 本文件 = 唯一公共构造门面，内部组合引擎，不并列第二套对话实现。
//
// 本批范围（第一期第一批）：门面 API 面 + canvas/选区两个消费者迁入。
// 不在本批：把 canvas 的全局键盘/指针收起监听迁入门面（仍在 askDock.js，
// 留给下一批；门面 destroy 只拆门面自己持有的东西）。
//
// API：
//   createAskPanel({ host?, dialog, onOpen?, onClose?, returnFocus? })
//     → { root, dialog, open, close, isOpen, focusInput, destroy }
//   · root    面板 DOM（.ask-dock / .selection-dock），调用方负责挂载
//             （host 给了则门面代挂到 host 末尾）
//   · dialog  内核实例（消息/流式/发送等既有能力，原样透传 cfg）
//   · open()  幂等：已开则无动作；否则亮面板并回调 onOpen
//   · close() 幂等：已收则无动作；否则收面板、回调 onClose；若焦点原本在面板内，
//             恢复到 returnFocus（元素或取元素函数；未连接则不恢复）
//   · focusInput() 聚焦输入条（失败静默）
//   · destroy() 幂等：释放卡 iframe 上下文、移除 root；之后 open/close 不再生效

import { createAskDialog } from './askDialog.js';
import { cleanupCardIframes } from './cardRegistry.js';

/**
 * @typedef {Object} AskPanelOptions
 * @property {HTMLElement} [host] 挂载宿主（缺省 = 调用方自行挂载 root）
 * @property {Parameters<typeof createAskDialog>[0]} dialog 内核 cfg（原样透传）
 * @property {() => void} [onOpen] 面板打开后的消费者腿
 * @property {() => void} [onClose] 面板收起后的消费者腿
 * @property {HTMLElement|null|(() => HTMLElement|null)} [returnFocus] 收起时恢复焦点的目标
 */

/**
 * 造一个 Ask 面板（门面）。
 * @param {AskPanelOptions} options
 */
export function createAskPanel(options) {
  const opts = options || {};
  // 唯一的内核构造点：门面之外不允许出现 createAskDialog 的调用（静态闸守卫）。
  const dialog = createAskDialog(opts.dialog);
  dialog.wire();
  const root = dialog.panel;
  let destroyed = false;
  if (opts.host && typeof opts.host.appendChild === 'function') opts.host.appendChild(root);

  const resolveReturnFocus = () => {
    const r = opts.returnFocus;
    const el = typeof r === 'function' ? r() : r;
    return el && el.isConnected ? el : null;
  };

  return {
    root,
    dialog,

    open() {
      if (destroyed || dialog.isOpen()) return;
      dialog.openDom();
      if (opts.onOpen) opts.onOpen();
    },

    close() {
      if (destroyed || !dialog.isOpen()) return;
      const hadFocus = root.contains(document.activeElement);
      dialog.closeDom();
      if (opts.onClose) opts.onClose();
      if (hadFocus) {
        const back = resolveReturnFocus();
        if (back) { try { back.focus(); } catch { /* 目标不可聚焦则静默 */ } }
      }
    },

    isOpen() {
      return !destroyed && dialog.isOpen();
    },

    focusInput() {
      if (destroyed) return;
      try { dialog.input.focus(); } catch { /* detached */ }
    },

    destroy() {
      if (destroyed) return;
      destroyed = true;
      // 卡行的 browsing context 必须逐个释放（同 cardRegistry 释放契约），再摘 DOM。
      for (const node of Array.from(dialog.col.children)) {
        try { cleanupCardIframes(/** @type {HTMLElement} */ (node)); } catch { /* 释放失败不阻断 */ }
      }
      root.remove();
    },
  };
}
