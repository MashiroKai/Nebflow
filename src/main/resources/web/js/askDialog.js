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
import { renderMarkdownWithMath, createIconsIn } from './utils.js';
// 图片附件（ask 附图批）：压缩管线 = 主会话同款（input/attachments.js 单点导出），
// 上行帧形状 = 主会话 userMessage.attachments 同形（name/mimeType/data/size）。
import { imageAttachmentPayload } from './input/attachments.js';
import { attachmentImageUrl } from './persistence.js';
import { createTurnToggle, requestTurnInterrupt } from './input/composer.js';
// 次级 LLM 输入框唯一模板（todo-mobile 批 2026-11-18 作者令「把 Canvas Ask 用的那一套
// 输入框整理成统一模板，放进站内单源」）：本内核的输入条装配点与待办详情面共用同一份
// 工厂——外壳 / 裸件 / 发送·中断钮 / 两个字形全在那一边产出，本文件不再自建这段 DOM、
// 也不再各自绑键道（裁定 2 选项①：模板成为唯一装配点）。
import { createSecondaryComposer } from './input/secondaryComposer.js';
import { handleSlash } from './input/slashCommands.js';
import { createScrollPill, bottomDistance, AT_BOTTOM_PX } from './scrollPill.js';
// 未读计数单源（fix/unread-count-unify 批 · 落队 2026-11-19 手移植）：离底期间的**真实
// 新消息**计数策略全站一份（主会话侧 utils.js 同源：本机发问 / 用户注入问 / 重复终态帧
// 都不计未读）。抽取批后本内核是 canvas 档唯一的落列/计数写点，故该腿落在这里（作者线
// 该腿写在旧 askDock.js 的 settleCol，那一段已被抽取批搬空 ⇒ 按其语义在本处重建）。
import { countUnread } from './unreadCount.js';
// 回答正文相对路径图片（ask-image-ticket 批 2026-11-24 · 作者报「Ask 回答正文
// 相对路径图片看不了」）：DOM 级图片 pass 单源在 mdImagePass.js（候选表/铸票/账本/
// 失败占位/级联自愈与文档查看器共用同一实现）。本内核只补三样：cfg.docPath 缝
//（所问文档的绝对路径，消费者注入）、每渲染轮的 epoch 竞争闸、setMarkdownHtml
// 落地后的挂点。通用 markdown 渲染本身零改动——其余调用方零影响。
import { resolveRelativeImages, neutralizePendingImages } from './mdImagePass.js';
import { timeDividerIndices, timeDividerLabel, msgTs, carryTs } from './askTimeDivider.js';
import { formatHm } from './timeFormat.js';
// card-tool-revive 批（2026-11-18 作者裁定②）：ask 会话里 Card 工具返回的 HTML 卡
// 由对话列显形——渲染**复用唯一单源** cardRegistry.renderWithRegistry（哨兵解析 +
// 沙箱 iframe + 主题注入那一套语义原样借用），零第二套渲染、零裸 HTML 内联、零自造
// 材质。cleanupCardIframes = 同单源的释放契约（整列清空前必须 disconnect 观察者，
// 否则卡 iframe 的独立 browsing context 会泄漏）。
// 移植说明：作者线该腿写在 askDock.js 的 render()；抽取批后 render 整段在此，故
// 卡行分支随之落在这里（两档共用同一 render；role='card' 的消息只在 canvas 档出现）。
import { renderWithRegistry, cleanupCardIframes } from './cardRegistry.js';

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
  // 落 DOM 前先摘「会被页面相对解析」的 img src（ask-image-ticket 批）：importNode
  // /replaceChildren 一步就会让浏览器对相对路径发一轮注定 404 的请求（WebKit
  // clone 拍即取数；Chromium 任务拍判，但无文档分支 replaceWith 不摘 src ⇒ 照样
  // 发）——双核红实证。摘掉后终局 src 由 renderImages 的 DOM 级 pass 就地换上；
  // 远端 / data: / blob: / 已代理 URL 原样（通用渲染语义不动）。
  neutralizePendingImages(parsed.body);
  node.replaceChildren(...Array.from(parsed.body.childNodes, (n) => document.importNode(n, true)));
}

/** 对话框自有的两枚图标（发送 / 中断）已随输入条装配点收进唯一来源：
 *  js/input/secondaryComposer.js 的 composerSendIcon / composerStopIcon（markup 逐字节
 *  等同原字形）；canvas 档入口钮的图标仍留在 askDock.js（自有 svgNode/STROKE）。 */

/**
 * @typedef {Object} AskSession
 * @property {string} sid
 * @property {string} [fileName]
 * @property {Array<{role: string, text: string, ts?: number, images?: any[]}>} msgs
 * @property {boolean} [stateSent]
 * @property {boolean} [unread]
 * @property {boolean} [busy]
 * @property {number} [lastFrameAt]
 * @property {string} [mailAddress]
 * @property {string} [replyState]
 */

/**
 * @typedef {Object} AskDialogCfg
 * @property {(zh: string, en: string) => string} tt 双语
 * @property {string} placeholder 输入条 placeholder
 * @property {() => AskSession|null} activeSession 当前会话
 * @property {(s: AskSession) => boolean} canSubmit 发送前硬闸
 * @property {() => Promise<AskSession|null>} ensureSession 取/建会话
 * @property {(s: AskSession) => void} ensureBound 发送前绑定帧
 * @property {(s: AskSession, text: string, attachments: Array<{name: string, mimeType: string, data: string, size: number}>) => any} send 发送腿（返回值原样经 onQueued 回传）
 * @property {(msg: any) => ('state'|'delta'|'done'|'interrupted'|'error'|'user'|null)} frameKind 帧名→动作
 * @property {(s: AskSession) => void} [onUnread] 未读点等消费者副作用
 * @property {(s: AskSession) => void} [onPersist] 落盘
 * @property {(msg: any, s: AskSession) => void} [onStateExtra] askState 的消费者附加腿
 * @property {() => void} [onInput] 草稿变化（input 事件）的消费者腿（状态可见性批）
 * @property {(s: AskSession, sent: any) => void} [onQueued] send 返回值的消费者腿
 *   （状态可见性批：sendWs 返回 false ⇒ 帧入 pending 队列；改前该返回值被丢弃）
 * @property {(kind: string, msg: any, s: AskSession) => void} [onFrame] 每**消费**一帧
 *   后的通用回调（状态可见性批：内核只报「哪一帧到了」，帧语义归消费者）
 * @property {() => void} [onStop] 覆盖停止钮动作（状态可见性批：canvas 档在消费者
 *   侧收口回合事实；缺省 = 本内核的 dlg.stopAsk）
 * @property {(node: HTMLElement, msg: any) => void} [termLinks] 回答词条化钩子（多选
 *   合并 Ask 批 2026-11-23，设计正本 §5 G4）：消费方在**终态落列**的 ai 气泡体上挂
 *   词条链接腿；本内核不认识词表（缺省 = 零动作，canvas 档逐字现状）。
 * @property {() => string} [docPath] 所问文档的绝对路径钩子（ask-image-ticket 批
 *   2026-11-24）：回答正文相对路径图片以**该文档所在目录**为基准解析（候选 → 铸票
 *   → 账本 → 失败占位，单源 mdImagePass）。缺省/空 = 无文档上下文 ⇒ 相对引用落
 *   明确占位、零网络（选区档现状即此形态，不再是注定 404 的页面相对请求）。
 * @property {string} [panelClass] 面板类（缺省 'ask-dock'）
 */

/** 单条提问的图片件数上限（= 后端 AttachContract.MaxAttachmentsPerMessage，同值 9）。 */
const ASK_MEDIA_MAX = 9;
/** 单图字节上限（= 主会话图片上限 10MB，前端预检；后端 ImageInject 硬帽同族）。 */
const ASK_MEDIA_MAX_BYTES = 10 * 1024 * 1024;

/**
 * 图片托盘（ask 附图批）：输入条上方的缩略件条 + 附件钮/粘贴/拖放三入口的单源。
 * 每件 = {name, url(本地 objectURL 供气泡即时显示), payload(Promise，压缩后的 WS 载荷)}。
 * take() 把待发件一次性交出（压缩未完成的等它完成；失败件静默出册并 toast）。
 * @param {(zh:string,en:string)=>string} tt
 */
function createMediaTray(tt) {
  /** @type {{name:string, url:string, payload:Promise<any>}[]} */
  let items = [];
  const strip = h('div', { class: 'ask-media-strip', 'data-ask-media-strip': true });
  strip.style.display = 'none';
  const fileInput = h('input', { type: 'file', accept: 'image/*', multiple: true, 'aria-hidden': 'true' });
  fileInput.style.display = 'none';
  // 附件钮 = 主会话 `#attach-btn` 同一组件类 `.icon-btn`（几何/图标档/hover/焦点单源在
  // design-system 与 input.css 的 `.icon-btn` 族）；`.ask-attach` 只是身份钩子。
  const attachBtn = h('button', {
    type: 'button',
    class: 'icon-btn ask-attach',
    'aria-label': tt('附加图片', 'Attach image'),
    title: tt('附加图片（也可粘贴 / 拖入）', 'Attach images (paste or drop also works)'),
  }, [h('i', { 'data-lucide': 'paperclip' })]);
  createIconsIn(attachBtn);
  attachBtn.addEventListener('click', () => fileInput.click());

  const render = () => {
    strip.replaceChildren();
    items.forEach((it) => {
      const img = h('img', { class: 'ask-media-thumb', src: it.url, alt: it.name, title: it.name });
      const rm = h('button', {
        type: 'button',
        class: 'ask-media-rm',
        'aria-label': tt('移除 ', 'Remove ') + it.name,
        'on:click': () => {
          URL.revokeObjectURL(it.url);
          items = items.filter((x) => x !== it);
          render();
        },
      }, ['×']);
      strip.appendChild(h('div', { class: 'ask-media-chip' }, [img, rm]));
    });
    strip.style.display = items.length ? '' : 'none';
  };

  const add = (files) => {
    const imgs = Array.from(files || []).filter((f) => /^image\//.test(f.type || ''));
    if (!imgs.length) return;
    for (const file of imgs) {
      if (items.length >= ASK_MEDIA_MAX) {
        window.__showToast?.(tt(`最多附 ${ASK_MEDIA_MAX} 张图片`, `At most ${ASK_MEDIA_MAX} images`), 'error');
        break;
      }
      if (file.size > ASK_MEDIA_MAX_BYTES) {
        window.__showToast?.(tt(`图片过大（上限 10MB）：${file.name}`, `Image too large (max 10MB): ${file.name}`), 'error');
        continue;
      }
      const payload = imageAttachmentPayload(file);
      // 失败件：静默出册 + toast（take() 会跳过它）。
      payload.catch(() => {
        window.__showToast?.(tt(`图片读取失败：${file.name}`, `Failed to read image: ${file.name}`), 'error');
      });
      items.push({ name: file.name, url: URL.createObjectURL(file), payload });
    }
    render();
  };

  fileInput.addEventListener('change', () => {
    add(fileInput.files);
    fileInput.value = '';
  });

  return {
    strip, fileInput, attachBtn,
    add,
    count: () => items.length,
    /** 交出待发件（载荷 + 本地预览 url；tray 清空，url 归气泡所有、不在此回收）。 */
    async take() {
      const taken = items;
      items = [];
      render();
      const settled = await Promise.all(taken.map((it) =>
        it.payload.then((p) => ({ ...p, url: it.url }), () => null)));
      return settled.filter(Boolean);
    },
    clear() {
      items.forEach((it) => URL.revokeObjectURL(it.url));
      items = [];
      render();
    },
  };
}

/** 气泡内图片的可显示地址：live 件用本地 objectURL，回放件用 path → /uploads 路由。 */
function imageSrcOf(img) {
  if (img && img.url) return img.url;
  return attachmentImageUrl((img && img.path) || '');
}

/** 卡行身份键（card-visible-reconcile 批 2026-11-21）：卡行的身份 = **载荷本身**
 *  ——同一份 `___<AGENT>_HTML___` 载荷重渲时复用同一个卡行节点（保住已挂载的
 *  iframe 文档），不同载荷/新增卡才建新行。键 = 长度 + FNV-1a 32 位 + 首 24 字
 *  （碰撞面只在「同长同哈希同首段」同时成立，且退化为复用而非错配内容——
 *  复用前仍按 key 全等匹配，故错配只会表现为卡不刷新，不会串内容）。
 *  @param {string} text */
function cardKey(text) {
  const s = typeof text === 'string' ? text : String(text || '');
  let hash = 0x811c9dc5;
  // 全量走一遍（卡载荷 ~11KB；重渲频率 = 每帧一拍，成本可忽略）：只采样会让
  // 「改了中间几个字」的两份载荷误配成同一张卡。
  for (let i = 0; i < s.length; i++) {
    hash ^= s.charCodeAt(i);
    hash = (hash + ((hash << 1) + (hash << 4) + (hash << 7) + (hash << 8) + (hash << 24))) >>> 0;
  }
  return s.length + ':' + hash.toString(36) + ':' + s.slice(0, 24);
}

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
  // 输入条 = **次级 LLM 输入框唯一模板**（js/input/secondaryComposer.js）：毛玻璃外壳
  // （唯一工具类处由 chatComposerClass('ask') 给）+ 裸件 textarea + 发送/中断圆钮
  // （.ds-turn-btn 组件类）+ 两个字形，全部由那一个装配点产出——本文件不再自建这段
  // DOM（改模板两个面一起变）。身份类 `ask-input` 仍是对话 UI 词汇单源，两个钩子类
  // ask-send / ask-stop 传给工厂；键道与多行长高也随工厂（onSend 一并装配）。
  const composer = createSecondaryComposer({
    class: chatComposerClass('ask'),
    placeholder: cfg.placeholder,
    ariaLabel: tt('提问', 'Question'),
    sendLabel: tt('发送', 'Send'),
    stopLabel: tt('停止回答', 'Stop answering'),
    sendHook: 'ask-send',
    stopHook: 'ask-stop',
    withStop: true,
    // 草稿变化的消费者腿（状态可见性批）：状态行的「待发送 ⇄ 空闲」跟着输入走。
    onInput: cfg.onInput || null,
    onSend: () => { dlg.submit(); },
  });
  const { root: inputRow, input, sendBtn: send, stopBtn: stop } = composer;
  // 附图入口（ask 附图批）：附件钮位于输入条内「文本框之后、发送钮之前」——与主会话
  // `#input-bar` 的 DOM 次序逐一对齐（textarea → attach → send/stop，UI 小修 2026-11 作者令
  // 「以主会话为基准」）；缩略件条在输入条之上。
  const media = createMediaTray(tt);
  inputRow.insertBefore(media.attachBtn, send);
  inputRow.appendChild(media.fileInput);
  const col = h('div', { class: 'ask-col' });
  const colwrap = h('div', { class: 'ask-colwrap' }, [col]);
  const panel = h('div', { class: (cfg.panelClass || 'ask-dock') + ' closed' }, [
    handle,
    colwrap,
    media.strip,
    inputRow,
  ]);

  let wired = false;
  let turnToggle = /** @type {any} */ (null);
  let askPill = /** @type {any} */ (null);
  let typingNode = /** @type {HTMLElement|null} */ (null);
  let streamPersistAt = 0;
  let colSnapped = true;
  let colUnseen = 0;

  // ── 回答正文相对路径图片（ask-image-ticket 批 2026-11-24）──────────────────
  // 竞争闸：Ask 的正文会**多次重渲**（流式 done / askState 回放 / 切文档都重建
  // 气泡）。每渲染轮持一个 epoch；铸票/取数是异步的，迟到的旧轮结果一律弃写
  // ——不回填已被新轮替换的节点，也不对已弃图再发字节请求。同轮的多个气泡
  // 共用一个 epoch（一轮 render 只递增一次，不能逐气泡递增——否则同轮前排
  // 气泡会被后排气泡「挤死」）。
  let mdImgEpoch = 0;
  const nextMdEpoch = () => ++mdImgEpoch;
  /**
   * 气泡体落地后的图片 pass 挂点：docPath 由消费者注入（canvas 档 = 所问文档
   * 绝对路径；选区档缺省 = 无文档上下文），账本 blob 归属面 = 气泡体自身。
   * @param {HTMLElement} node 气泡体
   * @param {number} epoch 本渲染轮令牌
   */
  function renderImages(node, epoch) {
    resolveRelativeImages(node, {
      docPath: cfg.docPath ? cfg.docPath() : '',
      pane: node,
      alive: () => epoch === mdImgEpoch,
    }).catch(() => { /* 铸票/取数失败 ⇒ mdImagePass 落失败占位；这里不再加面 */ });
  }

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
      // 粘贴图片进输入框 = 附图（纯文本粘贴零拦截，走浏览器默认）。
      input.addEventListener('paste', (e) => {
        const files = [];
        for (const item of Array.from((e.clipboardData && e.clipboardData.items) || [])) {
          if (item.kind === 'file' && /^image\//.test(item.type || '')) {
            const f = item.getAsFile();
            if (f) files.push(f);
          }
        }
        if (!files.length) return;
        e.preventDefault();
        media.add(files);
      });
      // 拖放图片到面板 = 附图（非图片文件不接，不拦浏览器默认）。
      const hasFiles = (e) => !!(e.dataTransfer && Array.from(e.dataTransfer.types || []).includes('Files'));
      panel.addEventListener('dragover', (e) => { if (hasFiles(e)) { e.preventDefault(); } });
      panel.addEventListener('drop', (e) => {
        if (!hasFiles(e)) return;
        const files = Array.from(e.dataTransfer.files || []).filter((f) => /^image\//.test(f.type || ''));
        if (!files.length) return;
        e.preventDefault();
        media.add(files);
      });
      turnToggle = createTurnToggle({
        sendBtn: /** @type {HTMLElement} */ (send),
        stopBtn: /** @type {HTMLElement} */ (stop),
      });
      stop.addEventListener('click', () => { if (cfg.onStop) cfg.onStop(); else dlg.stopAsk(); });
      // 发送点击 / 多行自动长高 / 键盘·IME·发送判定三条腿已随「次级 LLM 输入框」模板收进
      // js/input/secondaryComposer.js（createSecondaryComposer 的 onSend 一次性装配，
      // 键道走 input/composer.js 同一实现）——此处若再绑一次，一次 Enter / 一次点击会
      // 发出两条 askQuestion（模板一条 + 本处一条）。
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
    // 卡行增量化（card-visible-reconcile 批 2026-11-21，现场 = 同一份 HTML 两张卡
    // 一败一成）：修前本函数**整列清空重建**（cleanupCardIframes + textContent=''
    // + 逐行重建）。卡一旦在列内，每一拍整列重渲就是一次「摘掉已挂载的卡 iframe
    // 再重新排队挂载」：`_nfCleanup` 剥掉 srcdoc 并断开观察者（cardRegistry:582-591），
    // 新 iframe 回到未挂载态（chat.css:4072 opacity:0、高度只有 150px 默认盒），
    // 只能靠 IntersectionObserver 或 3s 兜底 timer 重新挂上（cardRegistry:565-597）。
    // 于是 done 收口那一刻卡是**空白 150px**、且列高随之塌缩（实测 2439→984），
    // 滚动位跳动把卡甩到视口外 604px —— 用户看到的就是「没有卡」。
    //
    // 根因层修法（不是加超时补丁）：卡行的**身份 = 载荷**。重渲时对同一份载荷
    // 复用已在列的卡行节点，**不摘除、不移动**（实测：iframe 节点一旦被移出/
    // 移动到别的父节点，浏览器就重建其浏览上下文并重跑文档脚本 ——
    // probe_iframe_survive/reorder 的哨兵探针），因此只有「原地不动」能保住已渲染
    // 的卡；也只对**真正被移出**的卡行调 cleanupCardIframes 释放 browsing context
    // （原释放契约不放松：泄漏面仍是每张 2–10MB 的独立上下文）。
    render() {
      const s = cfg.activeSession();
      // 本渲染轮的图片 pass 令牌（见上方 mdImgEpoch 头注）：本轮内所有 ai 气泡共用。
      const mdEpoch = nextMdEpoch();
      // v2.1 显示面减法：只出 user/ai 两族——sys（回执/状态噪声类）转录仍存但永不呈现。
      const vis = s ? s.msgs.filter((m) => m.role !== 'sys') : [];
      const rowsDesired = vis.map((m) => (m.role === 'card'
        // card-tool-revive 批（2026-11-18 作者裁定②）——卡载荷行：ask 面**唯一**的
        // iframe 落点。渲染腿 = cardRegistry.renderWithRegistry（哨兵解析 + 沙箱
        // `allow-scripts allow-same-origin` iframe + 主题注入，与主会话卡行同一单源），
        // 本函数只给宿主节点与行几何；卡行不参与气泡族（不进 askBubbleClass）。
        ? { kind: 'card', key: cardKey(m.text), text: m.text, msg: m }
        : { kind: 'bubble', role: m.role, text: m.text, msg: m }));
      // 时间分隔线（canvas ask 时间分隔线批）：段界处（会话开头 / 间隔 > 5 分钟）在该行
      // 之前插一条 kind:'divider'。分段口径单点在 askTimeDivider.js；本处只落位。
      // 必须在 mark 插入与建池之前完成（同 mark：插入会平移下标）。
      const divAt = timeDividerIndices(vis);
      const desired = [];
      rowsDesired.forEach((d, i) => {
        if (divAt.has(i)) desired.push({ kind: 'divider', ts: msgTs(d.msg) });
        desired.push(d);
      });

      // 回合标记（canvas ask 状态可视批 2026-11-21，作者 13:00 令）：最后一条用户问
      // 之后挂一行「处理中 / 已回复 / 已中止 / 失败」——终态**常驻**，不随 2.6s 驻留
      // 收回（状态行的 DONE 驻留到期即隐，正是作者「看不到已回复」的根因）。
      // 态 = 会话内存里的 replyState（arm/settle 点写），刷新后不回放。
      // 必须在建池/建索引**之前**插入（插入会平移 desired 下标）。
      if (s && s.replyState) {
        let at = -1;
        desired.forEach((d, i) => { if (d.kind === 'bubble' && d.role === 'user') at = i; });
        if (at >= 0) desired.splice(at + 1, 0, { kind: 'mark', state: s.replyState });
      }

      // ① 认脸：已在列的卡行按载荷建池（同载荷多张卡 = 先进先出配对）。
      /** @type {Map<string, HTMLElement[]>} */
      const pool = new Map();
      const existing = Array.from(col.children);
      for (const node of existing) {
        if (!node.classList || !node.classList.contains('ask-card-row')) continue;
        const k = node.getAttribute('data-nf-card-key') || '';
        if (!pool.has(k)) pool.set(k, []);
        /** @type {HTMLElement[]} */ (pool.get(k)).push(/** @type {HTMLElement} */ (node));
      }
      /** @type {Map<number, Element>} */
      const keep = new Map();
      desired.forEach((d, i) => {
        if (d.kind !== 'card') return;
        const q = pool.get(d.key);
        if (q && q.length) keep.set(i, q.shift());
      });
      const kept = new Set(keep.values());

      // ② 只移出**没被复用**的节点；卡行被真移出时才释放其 browsing context。
      for (const node of existing) {
        if (kept.has(node)) continue;
        node.remove();
        try { cleanupCardIframes(/** @type {HTMLElement} */ (node)); } catch { /* 释放失败不阻断渲染 */ }
      }

      // ③ 原地补齐：ref 恒指向「下一个被复用的卡行」（或 null = 尾插）。复用节点
      //    从不移动 ⇒ 其 iframe 文档、srcdoc、观察者、已量出的高度全部保持。
      /** @type {Element|null} */
      let ref = col.firstElementChild;
      for (let i = 0; i < desired.length; i++) {
        const keptNode = keep.get(i);
        if (keptNode) { ref = keptNode.nextElementSibling; continue; }
        const d = desired[i];
        /** @type {Element} */
        let node;
        if (d.kind === 'card') {
          const row = h('div', { class: 'ask-card-row' });
          if (row.dataset) row.dataset.nfCardKey = d.key;
          const host = h('div', { class: 'ask-card-host' });
          row.appendChild(host);
          renderWithRegistry(host, d.text, 'Card');
          node = row;
        } else if (d.kind === 'divider') {
          // 时间分隔线：细横线 + 居中浅色小字（ask.css `.ask-time-divider*`）。
          node = h('div', { class: 'ask-time-divider', role: 'separator', 'data-ts': String(d.ts) }, [
            h('span', { class: 'ask-time-divider-label' },
              [timeDividerLabel(d.ts, Date.now(), formatHm, tt)]),
          ]);
        } else if (d.kind === 'mark') {
          const label = {
            working: tt('处理中…', 'Working…'),
            replied: tt('已回复', 'Replied'),
            stopped: tt('已中止', 'Stopped'),
            failed: tt('失败，见上方提示', 'Failed, see the notice above'),
          }[d.state] || '';
          node = h('div', {
            class: 'ask-reply-mark',
            role: 'status',
            'data-nf-reply-state': d.state,
          }, [label]);
        } else {
          // glass-unify 批 2026-11-05：气泡挂唯一毛玻璃工具类 `.nf-glass`。
          // （`m.role` 是转录行原文：check-chat-ui / check-glass-unify 两处静态
          //  守卫锚定这条唯一落点，故行对象按原名 `m` 传下来。）
          const m = d.msg;
          const b = h('div', { class: askBubbleClass(m.role) });
          if (m.role === 'user') {
            // 附图行（ask 附图批）：图在正文之上；有图不算空正文——图+无文字的行照常成泡。
            const srcs = (m.images || []).map(imageSrcOf).filter(Boolean);
            if (srcs.length) {
              b.appendChild(h('div', { class: 'ask-msg-imgs' },
                srcs.map((src) => h('img', { class: 'ask-msg-img', src, alt: '', loading: 'lazy' }))));
            }
            if (m.text) b.appendChild(document.createTextNode(m.text));
          } else {
            setMarkdownHtml(b, m.text, false); // 唯一 HTML 落点见 setMarkdownHtml
            // 回答词条化（多选合并 Ask 批 2026-11-23；设计正本 §5 G4「在
            // createAskDialog 增加 termLinks 钩子」）：本内核**零词表知识**——词条匹配
            // 规则、消解裁定、气泡/深链腿全在 termLexicon.js + viewers/markdown.js 那一
            // 族，消费者经 cfg 注入。只在**终态落列**（render）走一次，流式增量
            // （updateStreaming）不扫，避免每帧一次图谱遍历。
            if (m.role === 'ai') cfg.termLinks?.(b, m);
            // 相对路径图片 pass（ask-image-ticket 批）：ai 正文里的 <img> 以所问文档
            // 目录为基准铸票换 src（与词条化同一挂点、同轮 epoch；用户泡是纯文本面，
            // 不进 pass）。
            if (m.role === 'ai') renderImages(b, mdEpoch);
          }
          node = b;
        }
        col.insertBefore(node, ref);
      }
      // 落列后的跟随/计数由调用点经 settleCol/snapCol 单点决定（本函数只建 DOM）。
    },

    /** 流式增量更新（不整列重渲——textDelta 高频）。 */
    updateStreaming(s) {
      if (s !== cfg.activeSession()) return;
      const last = s.msgs[s.msgs.length - 1];
      const node = col.lastElementChild;
      if (!node || !node.classList.contains('ai')) { dlg.render(); return; }
      if (last) {
        setMarkdownHtml(node, last.text, true);
        // 流式轮自成一轮 epoch（同 render：新轮结果落地前，旧轮的图片 pass 弃写）。
        renderImages(/** @type {HTMLElement} */ (node), nextMdEpoch());
      } else node.replaceChildren();
      col.scrollTop = col.scrollHeight;
    },

    // ── 贴底跟随 ──────────────────────────────────────────────────────────
    colAtBottom() { return bottomDistance(col) < AT_BOTTOM_PX; },
    colFollows() { return colSnapped === true || dlg.colAtBottom(); },
    askPillSync(count, atBottom) {
      if (!askPill) return;
      askPill.sync(atBottom === true ? { count: 0, atBottom: true } : { count });
    },
    /**
     * 落列后调用的唯一跟随决定：跟随钉底 / 计数进胶囊。
     * @param {Array<{role:string, self?:boolean}>} arrived Newly arrived transcript messages.
     */
    settleCol(arrived = []) {
      if (dlg.colFollows()) {
        colSnapped = true;
        colUnseen = 0;
        requestAnimationFrame(() => { col.scrollTop = col.scrollHeight; });
        dlg.askPillSync(0, true);
      } else {
        colUnseen = countUnread(colUnseen, arrived);
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
      // 附图批：图片是输入的一部分——「有图无字」也是一条可发的提问（有图不算空）。
      const picked = await media.take();
      if (!v && !picked.length) return;
      input.value = '';
      input.style.height = 'auto';
      // ④ 双泡修复批 2026-10-31：发问时**不再预压空 ai 泡**——等待期只有 typing。
      const userRow = { role: 'user', text: v, ts: Date.now() };
      if (picked.length) userRow.images = picked.map((p) => ({ name: p.name, url: p.url }));
      s.msgs.push(userRow);
      cfg.onPersist?.(s);
      dlg.render();
      dlg.settleCol([{ role: 'user', self: true }]); // Local sends are not unseen.
      dlg.appendTyping();
      dlg.armTurn(s);
      if (!s.stateSent) cfg.ensureBound(s);
      // 状态可见性批（通用挂钩）：send 的返回值原样回传消费者——canvas 档据它落
      // 「已提交排队 / 会话未连接」态；改前这个返回值在消费侧被当场丢弃。
      // 附图批：第三参 = 图片载荷（WS 帧 attachments 同形；无图 = []）。
      const sent = cfg.send(s, v, picked.map(({ name, mimeType, data, size }) => ({ name, mimeType, data, size })));
      cfg.onQueued?.(s, sent);
    },

    /** 臂：该会话 turn 在飞 → 中断钮上位（活动会话才动 chrome；幂等）。 */
    armTurn(s) {
      s.busy = true;
      s.replyState = 'working';
      s.lastFrameAt = Date.now();
      if (s === cfg.activeSession() && turnToggle) turnToggle.busy();
    },
    /** 收：终态（busy 清 + 中断钮让位；幂等）。 */
    settleTurn(s, outcome = 'replied') {
      if (!s) return;
      s.busy = false;
      // 只从「处理中」落到终态；已落终态（如先点停、后到的服务端 done）不被覆盖。
      if (s.replyState === 'working') s.replyState = outcome;
      if (s === cfg.activeSession() && turnToggle) turnToggle.idle();
    },
    /** 用户点停：帧出口走包单点（与主会话 #stop-btn 逐字节同帧）；UI 即刻收口。 */
    stopAsk() {
      const s = cfg.activeSession();
      if (!s || !s.busy) return;
      requestTurnInterrupt(s.sid);
      dlg.settleTurn(s, 'stopped');
      if (dlg.isOpen()) { dlg.removeTyping(); dlg.render(); dlg.snapCol(); }
    },

    /** 消费者自定义的一行提示（如“未连接/已有提问在飞”），落 ai 气泡槽。 */
    say(text) {
      const s = cfg.activeSession();
      if (!s) return;
      s.msgs.push({ role: 'ai', text, ts: Date.now() });
      dlg.removeTyping();
      dlg.render();
      dlg.settleCol([{ role: 'ai' }]); // 提示行 = 一条助手族消息（离底才计未读）
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
      // 状态可见性批（通用挂钩）：每**消费**一帧回调一次 cfg.onFrame —— 内核只说
      // 「哪一帧到了」，帧语义/呈现口径留在消费者（canvas 档 = askDock.onKernelFrame；
      // 未挂钩的档零影响）。
      const consumed = () => { cfg.onFrame?.(kind, msg, entry); return true; };
      switch (kind) {
        case 'state': {
          const prevLen = entry.msgs.length;
          entry.sid = msg.sessionId || entry.sid;
          entry.fileName = msg.fileName || entry.fileName;
          // ask-mail 批（2026-11-16）：引擎下发的会话 Mail 地址（`ask:<sid>` 逐字，
          // 与本地派生同式——引擎字段优先，仅存内存不落盘：每帧 askState 都重发）。
          if (typeof msg.mailAddress === 'string' && msg.mailAddress) entry.mailAddress = msg.mailAddress;
          let serverMsgs = Array.isArray(msg.messages)
            ? msg.messages
              .filter((m) => m && typeof m.text === 'string')
              .map((m) => {
                const row = {
                  role: m.role === 'user' ? 'user' : m.role === 'sys' ? 'sys' : 'ai',
                  text: m.text,
                };
                // 时间戳（canvas ask 时间分隔线批）：服务端下发的 timestamp/ts 原样带入；缺席不落键。
                const rowTs = msgTs(m);
                if (rowTs) row.ts = rowTs;
                // 附图批：回放行的附件（服务端 .ui.json 的 {name,type,path}）→ 气泡图（path 寻址）。
                const imgs = Array.isArray(m.attachments)
                  ? m.attachments.filter((a) => a && typeof a.path === 'string' && a.path)
                    .map((a) => ({ name: a.name || '', path: a.path }))
                  : [];
                if (row.role === 'user' && imgs.length) row.images = imgs;
                return row;
              })
            : entry.msgs;
          // 服务端回放未带时间的行，沿用本地同位行的 ts（只补缺，防状态帧抹掉分隔线）。
          if (Array.isArray(msg.messages)) serverMsgs = carryTs(serverMsgs, entry.msgs);
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
              dlg.settleCol(entry.msgs.slice(prevLen)); // Only newly appended visible messages count.
            }
            cfg.onUnread?.(entry);
          }
          return consumed();
        }
        case 'delta': {
          if (!(entry === cfg.activeSession() && dlg.isOpen())) entry.unread = true;
          dlg.armTurn(entry);
          const last = entry.msgs[entry.msgs.length - 1];
          if (!last || last.role !== 'ai') entry.msgs.push({ role: 'ai', text: '', ts: Date.now() });
          const ai = entry.msgs[entry.msgs.length - 1];
          ai.text += msg.delta || '';
          if (entry === cfg.activeSession() && dlg.isOpen()) {
            dlg.updateTypingText(ai.text);
            if (dlg.colFollows()) col.scrollTop = col.scrollHeight;
          }
          const nowMs = Date.now();
          if (nowMs - streamPersistAt > 800) { streamPersistAt = nowMs; cfg.onPersist?.(entry); }
          if (entry === cfg.activeSession()) cfg.onUnread?.(entry);
          return consumed();
        }
        case 'done': {
          const wasBusy = entry.busy;
          if (!(entry === cfg.activeSession() && dlg.isOpen())) entry.unread = true;
          dlg.settleTurn(entry);
          const last = entry.msgs[entry.msgs.length - 1];
          if (last && last.role === 'ai' && !last.text && msg.finalText) last.text = msg.finalText;
          cfg.onPersist?.(entry);
          if (entry === cfg.activeSession()) {
            if (dlg.isOpen()) { dlg.removeTyping(); dlg.render(); dlg.settleCol(wasBusy && last && last.role === 'ai' ? [last] : []); } // Ignore repeated terminal frames.
            cfg.onUnread?.(entry);
          }
          return consumed();
        }
        case 'interrupted': {
          const wasBusy = entry.busy;
          if (!(entry === cfg.activeSession() && dlg.isOpen())) entry.unread = true;
          dlg.settleTurn(entry, 'stopped');
          cfg.onPersist?.(entry);
          if (entry === cfg.activeSession()) {
            if (dlg.isOpen()) { dlg.removeTyping(); dlg.render(); const last = entry.msgs[entry.msgs.length - 1]; dlg.settleCol(wasBusy && last && last.role === 'ai' ? [last] : []); } // Partial answer, once.
            cfg.onUnread?.(entry);
          }
          return consumed();
        }
        case 'error': {
          // v2.1：错误不再起 sys 气泡——文本落助手气泡槽（问答面只出两族）。
          if (!(entry === cfg.activeSession() && dlg.isOpen())) entry.unread = true;
          dlg.settleTurn(entry, 'failed');
          const last = entry.msgs[entry.msgs.length - 1];
          const errText = tt('出错了：', 'Error: ') + (msg.message || 'unknown');
          if (last && last.role === 'ai' && !last.text) last.text = errText;
          else entry.msgs.push({ role: 'ai', text: errText, ts: Date.now() });
          cfg.onPersist?.(entry);
          if (entry === cfg.activeSession()) {
            if (dlg.isOpen()) { dlg.removeTyping(); dlg.render(); dlg.settleCol([{ role: 'ai' }]); } // Error bubble.
            cfg.onUnread?.(entry);
          }
          return consumed();
        }
        case 'user': {
          // 注入族（injected:true）：迁移自 askDock（canvas 回问腿）；选区档同一条腿。
          if (msg.injected) {
            if (!(entry === cfg.activeSession() && dlg.isOpen())) entry.unread = true;
            entry.msgs.push({ role: 'user', text: msg.text || '', ts: Date.now() });
            cfg.onPersist?.(entry);
            if (entry === cfg.activeSession()) {
              if (dlg.isOpen()) { dlg.removeTyping(); dlg.render(); dlg.settleCol([{ role: 'user' }]); } // User-role injection is excluded.
              cfg.onUnread?.(entry);
            }
          }
          return consumed();
        }
        default:
          return false;
      }
    },
  };

  return dlg;
}
