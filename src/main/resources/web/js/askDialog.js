// askDialog.js — 共用件：Ask 对话框内核（气泡列 + 输入条 + 流式/提交/中断腿）。
//
// 抽取批（2026-11-18 作者令「选区操作重做」④）：作者要求「点击选区 Ask 弹出
// **canvas 里那套 Ask 对话框**（同样的提问交互与链路），复用既有组件/链路，不要
// 另造第二套；若 canvas Ask 与 canvas 上下文耦合，把它抽成共用件」。
//
// canvas 档 Ask 原先与画布上下文强耦合（currentFile / 可问面白名单 / 可见性闸 /
// 座位 / 会话账 / askOpen 绑定全在 askDock.js 的同一段代码里）。本批把**对话框
// 内核**（DOM 构形 + 转录渲染 + 流式 + 提交/中断 + 贴底跟随）整段抽到这里，成为
// 一个工厂：`createAskDialog(cfg)`。两个消费者各自 new 一份实例：
//   · canvas 档 Ask（js/askDock.js，文件绑定：sid = ask-<hash(absPath)>，
//     发送腿 = askOpen/askQuestion，帧名 = 状态回放 textDelta/done/error）；
//   · 选区 Ask（js/selectionAsk.js，主会话绑定：sid = 主会话 id，
//     发送腿 = {type:'ask'}，帧名 = askTextDelta/askDone/askError）。
// ⚠ 同一实现、两份实例（消费者各自的座位/绑定不同），**不是两套实现**：
//   DOM 结构/类名/材质/滚动跟随/中断状态机/斜杠先审全部逐字同源。
//
// 与 canvas 档解耦所依赖的**绑定缝**（全部经 cfg 注入，本文件不认识画布）：
//   · activeSession()  当前会话对象（含 msgs/busy/stateSent/…）
//   · canSubmit(s)     发送前硬闸（canvas = 文件白名单；选区 = 恒真）
//   · ensureSession()  取/建会话（异步）
//   · ensureBound(s)   发送前的绑定帧（canvas = askOpen；选区 = 无绑定帧，空实现）
//   · send(s, text)    发送腿（帧名与载荷由消费者决定）
//   · frameKind(msg)   帧名 → 内核动作（两 lane 的协议名不同，收敛在此一张表）
//   · onUnread/onPersist/onStateExtra/onTerminal  消费者副作用钩子（通知点/落盘/
//     审阅路由）
//
// 不搬走的东西（留在 askDock.js）：入口钮 / 记事本 / 可见性闸 / 座位 / 会话账与
// 落盘 / 拖拽改尺寸状态机 / 画布专属帧（askNote*/askEdit*/askHandoffAck）。

import { h } from './dom.js';
import { askBubbleClass, chatComposerClass } from './chatUi.js';
import { renderMarkdownWithMath } from './utils.js';
import { bindComposerKeys, createTurnToggle, requestTurnInterrupt } from './input/composer.js';
import { handleSlash } from './input/slashCommands.js';
import { createScrollPill, bottomDistance, AT_BOTTOM_PX } from './scrollPill.js';

const SVG_NS = 'http://www.w3.org/2000/svg';

/**
 * 造一个 SVG 命名空间节点（dom.js 的 h() 只能造 HTML 命名空间元素，`<svg>`/`<path>`
 * 落在 HTML 命名空间里不参与绘制）。canvas 档与本模块同用这一份（单点）。
 * @param {string} tag
 * @param {Record<string,string>} [attrs]
 * @param {Node[]} [children]
 * @returns {SVGElement}
 */
export function svgNode(tag, attrs, children) {
  const node = document.createElementNS(SVG_NS, tag);
  // @ts-ignore -- setAttribute 的 SVG 分支 lib.dom 类型窄化
  if (attrs) for (const [name, value] of Object.entries(attrs)) node.setAttribute(name, value);
  if (children) for (const child of children) node.appendChild(child);
  return node;
}

const STROKE = { fill: 'none', stroke: 'currentColor', 'stroke-linecap': 'round', 'stroke-linejoin': 'round' };

/**
 * markdown 正文落点 —— **本模块唯一的 HTML 落点**（业务 DOM 一律走 h()/textContent）。
 *
 * 渲染源 = `renderMarkdownWithMath`（全站唯一的 markdown→HTML 渲染腿，含净化），
 * 与抽取前 askDock.js 用的**同一条腿**；变的只是**落 DOM 的写法**：抽取前的
 * 「字符串赋值到元素的 HTML 属性」换成「DOMParser(text/html) → importNode →
 * replaceChildren」—— component-contract R5 对**新文件**要求五项计数全 0
 * （askDock.js 里那两处是存量基线；抽取批把这段代码整套搬到本文件，正是不许把
 * 存量写法在新文件里再抄一遍的那两处）。两条写法解析上下文同为 "in body"、产出
 * 同一棵 DOM：零脚本执行（旧写法同样不执行）、属性/事件/文本逐字相同。
 *
 * @param {Element} node 目标节点（气泡体）
 * @param {string} text markdown 原文
 * @param {boolean} [streaming] true ⇒ 流式档（关缓存）
 */
function setMarkdownHtml(node, text, streaming) {
  const html = streaming
    ? renderMarkdownWithMath(text, true, { cache: false })
    : renderMarkdownWithMath(text);
  const parsed = new DOMParser().parseFromString(String(html || ''), 'text/html');
  node.replaceChildren(...Array.from(parsed.body.childNodes, (n) => document.importNode(n, true)));
}

/** 对话框自有的两枚图标（发送 / 中断）；canvas 档入口钮的图标仍留在 askDock.js。 */
export const DIALOG_ICONS = {
  send: () => svgNode('svg', { viewBox: '0 0 24 24', 'stroke-width': '2.4', ...STROKE },
    [svgNode('path', { d: 'M12 19V5M5 12l7-7 7 7' })]),
  // 中断钮（输入行为包批）：与主会话 #stop-btn 同语（lucide square 族）——红圆白方停标。
  stop: () => svgNode('svg', { viewBox: '0 0 24 24', 'stroke-width': '2.4', ...STROKE },
    [svgNode('rect', { x: '6', y: '6', width: '12', height: '12', rx: '2' })]),
};

/**
 * @typedef {Object} AskSession
 * @property {string} sid
 * @property {string} [fileName]
 * @property {Array<{role: string, text: string}>} msgs
 * @property {boolean} [stateSent]
 * @property {boolean} [unread]
 * @property {boolean} [busy]
 * @property {number} [lastFrameAt]
 * @property {string} [mailAddress]
 */

/**
 * @typedef {Object} AskDialogCfg
 * @property {(zh: string, en: string) => string} tt 双语
 * @property {string} placeholder 输入条 placeholder
 * @property {() => AskSession|null} activeSession 当前会话
 * @property {(s: AskSession) => boolean} canSubmit 发送前硬闸
 * @property {() => Promise<AskSession|null>} ensureSession 取/建会话
 * @property {(s: AskSession) => void} ensureBound 发送前绑定帧
 * @property {(s: AskSession, text: string) => void} send 发送腿
 * @property {(msg: any) => ('state'|'delta'|'done'|'interrupted'|'error'|'user'|null)} frameKind 帧名→动作
 * @property {(s: AskSession) => void} [onUnread] 未读点等消费者副作用
 * @property {(s: AskSession) => void} [onPersist] 落盘
 * @property {(msg: any, s: AskSession) => void} [onStateExtra] askState 的消费者附加腿
 * @property {string} [panelClass] 面板类（缺省 'ask-dock'）
 */

/**
 * 造一份 Ask 对话框实例（DOM 只建一次；wire() 只绑一次）。
 * @param {AskDialogCfg} cfg
 */
export function createAskDialog(cfg) {
  const tt = cfg.tt;
  const handle = h('div', {
    class: 'ds-resize-handle',
    'data-ask-resize': true,
    role: 'separator',
    tabindex: '0',
    'aria-label': tt('拖拽调整面板大小', 'Resize panel'),
    title: tt('拖拽调整面板大小（←→ 改宽 / ↑↓ 改高）', 'Drag to resize (←→ width / ↑↓ height)'),
  });
  const input = h('textarea', {
    rows: '1',
    placeholder: cfg.placeholder,
    'aria-label': tt('提问', 'Question'),
  });
  const send = h('button', {
    type: 'button',
    class: 'ask-send ds-turn-btn ds-turn-btn--send',
    'aria-label': tt('发送', 'Send'),
  }, [DIALOG_ICONS.send()]);
  const stop = h('button', {
    type: 'button',
    class: 'ask-stop ds-turn-btn ds-turn-btn--stop',
    'aria-label': tt('停止回答', 'Stop answering'),
  }, [DIALOG_ICONS.stop()]);
  const col = h('div', { class: 'ask-col' });
  const colwrap = h('div', { class: 'ask-colwrap' }, [col]);
  const inputRow = h('div', { class: chatComposerClass('ask') }, [input, send, stop]);
  const panel = h('div', { class: (cfg.panelClass || 'ask-dock') + ' closed' }, [
    handle,
    colwrap,
    inputRow,
  ]);

  let wired = false;
  let turnToggle = /** @type {any} */ (null);
  let askPill = /** @type {any} */ (null);
  let typingNode = /** @type {HTMLElement|null} */ (null);
  let streamPersistAt = 0;
  let colSnapped = true;
  let colUnseen = 0;

  const dlg = {
    panel, handle, colwrap, col, inputRow, input, send, stop,

    /** 开/收的 DOM 面（消费者的高层 open()/close() 调它们）。 */
    openDom() { panel.classList.remove('closed'); },
    closeDom() { panel.classList.add('closed'); },
    isOpen() { return !panel.classList.contains('closed'); },
    hideOnClose() { panel.classList.add('closed'); },

    wire() {
      if (wired) return;
      wired = true;
      panel.addEventListener('click', (e) => e.stopPropagation());
      panel.addEventListener('pointerdown', (e) => e.stopPropagation());
      turnToggle = createTurnToggle({
        sendBtn: /** @type {HTMLElement} */ (send),
        stopBtn: /** @type {HTMLElement} */ (stop),
      });
      send.addEventListener('click', () => { dlg.submit(); });
      stop.addEventListener('click', () => { dlg.stopAsk(); });
      // 多行草稿自动长高（主会话 textarea 同式：auto → scrollHeight，封顶留 dock 紧凑形态）。
      input.addEventListener('input', () => {
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 120) + 'px';
      });
      // 键盘/IME/发送判定 = 主会话同一实现（包装配；组字期 Enter 只确认候选）。
      bindComposerKeys(input, { onSend: () => { dlg.submit(); } });
      // ── ask-stream-ui（2026-11-17）：贴底跟随 + 「↓N 条新消息 / 回到底部」胶囊 ──
      askPill = createScrollPill({
        host: panel,
        scrollEl: col,
        anchor: () => inputRow,
        onReturn: () => { colUnseen = 0; colSnapped = true; },
      });
      col.addEventListener('scroll', () => {
        colSnapped = bottomDistance(col) < AT_BOTTOM_PX;
        if (colSnapped) colUnseen = 0;
        if (askPill) askPill.sync({ count: colUnseen, atBottom: colSnapped });
      }, { passive: true });
      // ── 气泡穿入输入条批（作者令 2026-11-05 16:50）：气泡列盒底 = 输入条垂直中线。
      if (typeof ResizeObserver === 'function') {
        const measureUnder = () => {
          // 下一帧再量：RO 回调可能落在「输入条刚插入、发送钮/字号还没落位」的那一拍。
          requestAnimationFrame(() => {
            // ⚠ 必须用 offsetHeight（布局高度），不能用 getBoundingClientRect()：
            // 位移腿（液态形变）会让 bbox 返回变换后的几何。
            if (panel.classList.contains('closed')) return;
            const hh = inputRow.offsetHeight;
            if (hh > 0) panel.style.setProperty('--ask-under', (hh / 2).toFixed(2) + 'px');
            if (askPill) askPill.refresh();
            if (colSnapped) col.scrollTop = col.scrollHeight;
          });
        };
        measureUnder();
        new ResizeObserver(measureUnder).observe(inputRow);
      }
    },

    // ── 转录渲染 ──────────────────────────────────────────────────────────
    render() {
      const s = cfg.activeSession();
      col.textContent = '';
      if (!s) return;
      // v2.1 显示面减法：只出 user/ai 两族——sys（回执/状态噪声类）转录仍存但永不呈现。
      const vis = s.msgs.filter((m) => m.role !== 'sys');
      vis.forEach((m) => {
        // glass-unify 批 2026-11-05：气泡挂唯一毛玻璃工具类 `.nf-glass`。
        const b = h('div', { class: askBubbleClass(m.role) });
        if (m.role === 'user') b.textContent = m.text;
        else setMarkdownHtml(b, m.text, false); // 唯一 HTML 落点见 setMarkdownHtml
        col.appendChild(b);
      });
      // 落列后的跟随/计数由调用点经 settleCol/snapCol 单点决定（本函数只建 DOM）。
    },

    /** 流式增量更新（不整列重渲——textDelta 高频）。 */
    updateStreaming(s) {
      if (s !== cfg.activeSession()) return;
      const last = s.msgs[s.msgs.length - 1];
      const node = col.lastElementChild;
      if (!node || !node.classList.contains('ai')) { dlg.render(); return; }
      if (last) setMarkdownHtml(node, last.text, true);
      else node.replaceChildren();
      col.scrollTop = col.scrollHeight;
    },

    // ── 贴底跟随 ──────────────────────────────────────────────────────────
    colAtBottom() { return bottomDistance(col) < AT_BOTTOM_PX; },
    colFollows() { return colSnapped === true || dlg.colAtBottom(); },
    askPillSync(count, atBottom) {
      if (!askPill) return;
      askPill.sync(atBottom === true ? { count: 0, atBottom: true } : { count });
    },
    settleCol(arrived) {
      if (dlg.colFollows()) {
        colSnapped = true;
        colUnseen = 0;
        requestAnimationFrame(() => { col.scrollTop = col.scrollHeight; });
        dlg.askPillSync(0, true);
      } else {
        if (arrived > 0) colUnseen += arrived;
        dlg.askPillSync(colUnseen);
      }
    },
    snapCol() {
      colSnapped = true;
      colUnseen = 0;
      requestAnimationFrame(() => { col.scrollTop = col.scrollHeight; });
      dlg.askPillSync(0, true);
    },

    // ── 思考指示 / typing ─────────────────────────────────────────────────
    appendTyping() {
      dlg.removeTyping();
      const tp = h('div', {
        class: 'ask-stream-line',
        role: 'status',
        'aria-live': 'polite',
        'aria-label': tt('正在生成回答', 'Generating answer'),
      }, [
        h('span', { class: 'ask-thinking-dots', 'aria-hidden': 'true' }, [
          h('span', { class: 'thinking-dot' }),
          h('span', { class: 'thinking-dot' }),
          h('span', { class: 'thinking-dot' }),
        ]),
        h('span', { class: 'ask-thinking-label' }, tt('思考中', 'Thinking')),
      ]);
      col.appendChild(tp);
      if (dlg.colFollows()) col.scrollTop = col.scrollHeight;
      typingNode = tp;
    },
    updateTypingText(text) {
      if (!typingNode || !typingNode.isConnected || typeof text !== 'string') return;
      if (!text) return; // 思考期保持 spinner：首个非空 token 才换相
      typingNode.textContent = text;
      typingNode.scrollLeft = typingNode.scrollWidth;
    },
    removeTyping() {
      if (typingNode && typingNode.isConnected) typingNode.remove();
      typingNode = null;
    },

    // ── 发送 / 中断 ───────────────────────────────────────────────────────
    async submit() {
      // 斜杠先审（ask 面三修③ 2026-11-11）：白名单命中（现仅 UI-only 的 /model）
      // ⇒ 本地开面板、清输入、零 WS 零气泡；未命中 ⇒ '/' 仍是纯文本。
      const slashText = input.value.trim();
      if (slashText && handleSlash(slashText)) {
        input.value = '';
        input.style.height = 'auto';
        return;
      }
      const s0 = cfg.activeSession();
      if (!s0 || !cfg.canSubmit(s0)) return;
      const s = await cfg.ensureSession();
      if (!s) return;
      const v = input.value.trim();
      if (!v) return;
      input.value = '';
      input.style.height = 'auto';
      // ④ 双泡修复批 2026-10-31：发问时**不再预压空 ai 泡**——等待期只有 typing。
      s.msgs.push({ role: 'user', text: v });
      cfg.onPersist?.(s);
      dlg.render();
      dlg.settleCol(1);
      dlg.appendTyping();
      dlg.armTurn(s);
      if (!s.stateSent) cfg.ensureBound(s);
      cfg.send(s, v);
    },

    /** 臂：该会话 turn 在飞 → 中断钮上位（活动会话才动 chrome；幂等）。 */
    armTurn(s) {
      s.busy = true;
      s.lastFrameAt = Date.now();
      if (s === cfg.activeSession() && turnToggle) turnToggle.busy();
    },
    /** 收：终态（busy 清 + 中断钮让位；幂等）。 */
    settleTurn(s) {
      if (!s) return;
      s.busy = false;
      if (s === cfg.activeSession() && turnToggle) turnToggle.idle();
    },
    /** 用户点停：帧出口走包单点（与主会话 #stop-btn 逐字节同帧）；UI 即刻收口。 */
    stopAsk() {
      const s = cfg.activeSession();
      if (!s || !s.busy) return;
      requestTurnInterrupt(s.sid);
      dlg.settleTurn(s);
      if (dlg.isOpen()) { dlg.removeTyping(); dlg.render(); dlg.snapCol(); }
    },

    /** 消费者自定义的一行提示（如“未连接/已有提问在飞”），落 ai 气泡槽。 */
    say(text) {
      const s = cfg.activeSession();
      if (!s) return;
      s.msgs.push({ role: 'ai', text });
      dlg.removeTyping();
      dlg.render();
      dlg.settleCol(1);
    },

    /** 按会话的 busy 位重落发送/中断钮（切会话、宿主迁移后的补一拍；幂等）。 */
    setTurnChrome(s) {
      if (!turnToggle) return;
      if (s && s.busy) turnToggle.busy();
      else turnToggle.idle();
    },

    /**
     * 帧进（消费者路由：帧名 → 动作由 cfg.frameKind 决定）。
     * @param {any} msg
     * @param {AskSession} entry
     * @returns {boolean} 是否消费
     */
    consume(msg, entry) {
      const kind = cfg.frameKind(msg);
      if (!kind) return false;
      switch (kind) {
        case 'state': {
          const prevLen = entry.msgs.length;
          entry.sid = msg.sessionId || entry.sid;
          entry.fileName = msg.fileName || entry.fileName;
          // ask-mail 批（2026-11-16）：引擎下发的会话 Mail 地址（`ask:<sid>` 逐字，
          // 与本地派生同式——引擎字段优先，仅存内存不落盘：每帧 askState 都重发）。
          if (typeof msg.mailAddress === 'string' && msg.mailAddress) entry.mailAddress = msg.mailAddress;
          const serverMsgs = Array.isArray(msg.messages)
            ? msg.messages
              .filter((m) => m && typeof m.text === 'string')
              .map((m) => ({
                role: m.role === 'user' ? 'user' : m.role === 'sys' ? 'sys' : 'ai',
                text: m.text,
              }))
            : entry.msgs;
          const localLastUser = entry.msgs.filter((m) => m.role === 'user').slice(-1)[0];
          const adopted = serverMsgs.length >= entry.msgs.length ? serverMsgs : entry.msgs;
          const adoptedLastUser = adopted.filter((m) => m.role === 'user').slice(-1)[0];
          entry.msgs = adopted;
          // 本地发问尚不在账（回放快照早于本拍提问落账）⇒ 补挂到尾。幂等双闸。
          if (
            localLastUser &&
            (!adoptedLastUser || adoptedLastUser.text !== localLastUser.text) &&
            !entry.msgs.some((m) => m.role === 'user' && m.text === localLastUser.text)
          ) {
            entry.msgs = entry.msgs.concat([localLastUser]);
          }
          cfg.onPersist?.(entry);
          cfg.onStateExtra?.(msg, entry);
          // 断线中 turn 已终了的形态：回放转录末条已是 ai 答复而本地仍臂着 busy ⇒ 收口。
          if (entry.busy && adopted.length && adopted[adopted.length - 1].role === 'ai') dlg.settleTurn(entry);
          if (entry === cfg.activeSession()) {
            if (dlg.isOpen()) {
              entry.unread = false;
              dlg.removeTyping();
              dlg.render();
              dlg.settleCol(Math.max(0, entry.msgs.length - prevLen));
            }
            cfg.onUnread?.(entry);
          }
          return true;
        }
        case 'delta': {
          if (!(entry === cfg.activeSession() && dlg.isOpen())) entry.unread = true;
          dlg.armTurn(entry);
          const last = entry.msgs[entry.msgs.length - 1];
          if (!last || last.role !== 'ai') entry.msgs.push({ role: 'ai', text: '' });
          const ai = entry.msgs[entry.msgs.length - 1];
          ai.text += msg.delta || '';
          if (entry === cfg.activeSession() && dlg.isOpen()) {
            dlg.updateTypingText(ai.text);
            if (dlg.colFollows()) col.scrollTop = col.scrollHeight;
          }
          const nowMs = Date.now();
          if (nowMs - streamPersistAt > 800) { streamPersistAt = nowMs; cfg.onPersist?.(entry); }
          if (entry === cfg.activeSession()) cfg.onUnread?.(entry);
          return true;
        }
        case 'done': {
          if (!(entry === cfg.activeSession() && dlg.isOpen())) entry.unread = true;
          dlg.settleTurn(entry);
          const last = entry.msgs[entry.msgs.length - 1];
          if (last && last.role === 'ai' && !last.text && msg.finalText) last.text = msg.finalText;
          cfg.onPersist?.(entry);
          if (entry === cfg.activeSession()) {
            if (dlg.isOpen()) { dlg.removeTyping(); dlg.render(); dlg.settleCol(1); }
            cfg.onUnread?.(entry);
          }
          return true;
        }
        case 'interrupted': {
          if (!(entry === cfg.activeSession() && dlg.isOpen())) entry.unread = true;
          dlg.settleTurn(entry);
          cfg.onPersist?.(entry);
          if (entry === cfg.activeSession()) {
            if (dlg.isOpen()) { dlg.removeTyping(); dlg.render(); dlg.settleCol(1); }
            cfg.onUnread?.(entry);
          }
          return true;
        }
        case 'error': {
          // v2.1：错误不再起 sys 气泡——文本落助手气泡槽（问答面只出两族）。
          if (!(entry === cfg.activeSession() && dlg.isOpen())) entry.unread = true;
          dlg.settleTurn(entry);
          const last = entry.msgs[entry.msgs.length - 1];
          const errText = tt('出错了：', 'Error: ') + (msg.message || 'unknown');
          if (last && last.role === 'ai' && !last.text) last.text = errText;
          else entry.msgs.push({ role: 'ai', text: errText });
          cfg.onPersist?.(entry);
          if (entry === cfg.activeSession()) {
            if (dlg.isOpen()) { dlg.removeTyping(); dlg.render(); dlg.settleCol(1); }
            cfg.onUnread?.(entry);
          }
          return true;
        }
        case 'user': {
          // 注入族（injected:true）：迁移自 askDock（canvas 回问腿）；选区档同一条腿。
          if (msg.injected) {
            if (!(entry === cfg.activeSession() && dlg.isOpen())) entry.unread = true;
            entry.msgs.push({ role: 'user', text: msg.text || '' });
            cfg.onPersist?.(entry);
            if (entry === cfg.activeSession()) {
              if (dlg.isOpen()) { dlg.removeTyping(); dlg.render(); dlg.settleCol(1); }
              cfg.onUnread?.(entry);
            }
          }
          return true;
        }
        default:
          return false;
      }
    },
  };

  return dlg;
}
