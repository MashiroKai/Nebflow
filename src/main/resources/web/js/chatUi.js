// Shared chat UI structural primitives. Surface-specific geometry and behavior stay
// with their owners until the frozen visual differences have been reviewed.
//
// chat-ui-package 批（2026-11-16）起本模块是对话 UI **结构单源**：
//   · composer({variant:'main'|'compact'})  主输入条（index.html 原位渲染）与
//     fm 三面轻量输入条（fmComposer 消费）；
//   · searchShell()                         搜索弹层壳 —— 主对话与消息窗口
//     （手机层页面化收编后同一实例）共用；
//   · chatFooter / chatFooterDivider / chatFooterTime / mainBubbleClass /
//     askBubbleClass / chatComposerClass    既有类名工厂（chat-ui-unify 批）。
// scripts/check-chat-ui.mjs 以本文件的字符串字面量为对话 UI 选择器族词表单源
// （气泡/composer/footer/搜索 —— 族内私设拼写一律红）。
// 装配时序契约见 js/chatMount.js（main.js 首位 import，先于一切应用模块求值）。

export function chatFooter(extraClass = '') {
  const el = document.createElement('div');
  el.className = extraClass ? `${extraClass} duration-badge` : 'duration-badge';
  return el;
}

export function chatFooterDivider(extraClass = '') {
  const el = document.createElement('span');
  el.className = extraClass ? `${extraClass} duration-badge-divider` : 'duration-badge-divider';
  return el;
}

export function chatFooterTime(extraClass = '', text = '') {
  const el = document.createElement('span');
  el.className = extraClass ? `${extraClass} duration-badge-time` : 'duration-badge-time';
  el.textContent = text;
  return el;
}

// Standard main-chat bubbles keep their original classes and order. Unlike the
// message-window side factory, this does not add a side class to existing rows.
export function mainBubbleClass(role, extra = '') {
  return `bubble ${role}${extra ? ' ' + extra : ''}`;
}

// D1: Ask's glass bubble is intentionally distinct from the standard bubble.
export function askBubbleClass(role) {
  return 'ask-bub nf-glass ' + (role === 'user' ? 'user' : 'ai');
}

// D2: shell geometry stays surface-owned; the material is the common token class.
export function chatComposerClass(surface) {
  if (surface === 'ask') return 'ask-input nf-glass';
  if (surface === 'messages') return 'fm-input-bar nf-glass';
  throw new Error('Unknown chat composer surface');
}

// ── composer 工厂（批①③）──────────────────────────────────────────────────
// 主输入条模板 = 迁移前 index.html 静态块的**逐字节内芯**（含历史注记 HTML 注释
// 与缩进空白；outerHTML 快照比对要求零字节差，故注释随结构整体迁入）。
// 模板零插值（无 ${} 槽位）、零用户内容通道 —— 与旧静态标记同一信任级；
// 装配走 insertAdjacentHTML 解析，不写裸 innerHTML（组件契约 R5）。
const MAIN_COMPOSER_SHELL = `
        <!-- 语音输入 = micOrb 液态光球（voicefix 批，作者 2026-09-21 14:0x ③：
             「参考我们最新的实现……把最近的有光球的回退回来」⇒ 回退源 =
             2cba0a224 删除前的最近版（git show 2cba0a224^），非 v8.2.2 原初版；
             设置页光球调色板维持现状不恢复）。DOM 四件照原样恢复。
             🟢 voiceseal 纠正批（批 #513，作者澄清：「怎么语音气泡直接没了，
             我要去掉的是hover拖动这些功能。」）：#508 的静态 \`hidden\` 摘除，
             气泡回归**默认产品形态 = 可见可点**（点击听写等非全双工路径照常），
             但为**纯静态**——\`voice-static\` 姿态类静态携带、JS 从不摘除：
             hover 悬停反馈（.voice-drag-hint）被 css/voiceMode.css 静态姿态
             覆盖规则压死，拖动/回弹手势由 input.js pointerdown 的
             voiceEnabled() 闸（#508 原样）兜死 ⇒ 气泡固定默认位。
             挂载门 = featureFlags.voiceOrbEnabled()（默认显示；显式
             \`features.voiceOrb:false\` 才藏，与全双工正交——js/voiceSeal.js
             双参 latch）。隐藏 ≠ 删除：子树/监听/i18n/CSS 全保留
             （companion 规则 = css/input.css \`.mic-orb-wrap[hidden]\`）。 -->
        <div class="mic-orb-wrap voice-static" id="mic-orb-wrap">
          <button class="micbubble" id="voice-btn" type="button" title="Voice input" aria-label="Voice input">
            <canvas class="orb-canvas" id="mic-canvas" aria-hidden="true"></canvas>
            <div class="css-orb orb" id="mic-css-orb" role="img" aria-label="Mic state"></div>
          </button>
          <div class="voice-drag-hint" aria-hidden="true"><span></span><span></span><span></span></div>
        </div>
      <div id="input-wrap" style="position:relative;">
        <div id="attachment-preview"></div>
        <div id="ask-indicator"><span id="ask-indicator-label">ASK</span><span id="ask-indicator-cancel">&times;</span></div>
        <div id="skill-indicator"><span id="skill-indicator-label">SKILL</span><span id="skill-indicator-cancel">&times;</span></div>
        <div id="compact-indicator"><span id="compact-indicator-label">COMPACT</span><span id="compact-indicator-cancel">&times;</span></div>
        <textarea id="input" rows="1" placeholder="Type a message..." autocomplete="off"></textarea>
      </div>
      <button class="skip-freeze-btn" id="skip-freeze-btn" type="button"><i data-lucide="skip-forward"></i><span id="skip-freeze-label">跳过本次</span></button>
      <button class="icon-btn" id="attach-btn"><i data-lucide="paperclip"></i></button>
      <!-- 🔴 输入框引用图标移除（作者令 2026-11-03 10:11 ②）：#quote-item-btn
           （2026-11-02 11:00「引用入口移入 footer」批引入的动作行引用键）整键退役，
           不留替代按钮——作者原意是「引用消息时用图标替代长文本展示」，不是输入框
           加引用按钮；引用消息图标化语义另行核对后再设计入口，本批只做移除。
           装配链（msgQuote.js initQuoteItemButton / freeze.js 冻结名单 /
           input.css frozen disabled 档）同批摘除；防复活钉 =
           ~/.stand/evidence/ui-unification-20261103/input-fixes/
           verify-five-fixes.mjs ② 腿（元素 0 命中）。 -->
      <button id="send-btn" class="ds-turn-btn ds-turn-btn--send"><i data-lucide="send"></i></button>
      <button id="stop-btn" class="ds-turn-btn ds-turn-btn--stop"><i data-lucide="square"></i></button>
      `;

// 消息窗（fm 三面）气泡行的外壳类 —— 结构拼写收口进本工厂单源（批④词表）；
// 内芯（角色/归属方类）仍由 js/bubbleSide.js 的 bubbleClass() 单点生成
// （归属方判据与门禁 = scripts/check-bubble-side.mjs R1–R5，不在本文件重复）。
export function fmBubbleRowClass(inner) {
  return 'fm-msg-bubble ' + inner;
}

/**
 * 装配对话输入条。
 * variant 'main'    —— 主输入条：填充 opts.mount（index.html 的静态壳
 *                     #input-bar）；返回 mount。字节形态 = 迁移前静态标记。
 * variant 'compact' —— fm 三面轻量输入条：返回
 *   {bar, input, micBtn, sendBtn, attachBtn?, attachInput?}（canAttach=false
 *   时 attach 两件为 null）。类名/属性/子序单源在此，行为接线留在消费方
 *   （fmComposer.js：语音/附件/发送/键盘契约）。
 */
export function composer(opts = {}) {
  if (opts.variant === 'compact') return compactComposer(opts);
  const mount = opts.mount;
  if (!mount) throw new Error('composer(main): mount element required');
  mount.insertAdjacentHTML('afterbegin', MAIN_COMPOSER_SHELL);
  return mount;
}

function compactComposer(opts) {
  const bar = document.createElement('div');
  bar.className = chatComposerClass('messages');
  const micBtn = document.createElement('button');
  micBtn.className = 'icon-btn fm-mic-btn';
  micBtn.type = 'button';
  micBtn.title = opts.micLabel || '';
  micBtn.setAttribute('aria-label', opts.micLabel || '');
  micBtn.setAttribute('aria-pressed', 'false');
  micBtn.appendChild(lucideIcon('mic'));
  const input = document.createElement('input');
  input.className = 'cfg-input fm-input nf-field--bare';
  input.type = 'text';
  input.placeholder = opts.placeholder || '';
  input.maxLength = 2000;
  input.autocomplete = 'off';
  let attachBtn = null;
  let attachInput = null;
  if (opts.canAttach) {
    attachBtn = document.createElement('button');
    attachBtn.className = 'icon-btn';
    attachBtn.type = 'button';
    attachBtn.title = opts.attachLabel || '';
    attachBtn.setAttribute('aria-label', opts.attachLabel || '');
    attachBtn.appendChild(lucideIcon('paperclip'));
    attachInput = document.createElement('input');
    attachInput.type = 'file';
    attachInput.multiple = true;
    attachInput.style.display = 'none';
  }
  const sendBtn = document.createElement('button');
  sendBtn.className = 'ds-turn-btn ds-turn-btn--send fm-send-btn';
  sendBtn.type = 'button';
  sendBtn.title = opts.sendLabel || '';
  sendBtn.setAttribute('aria-label', opts.sendLabel || '');
  sendBtn.appendChild(lucideIcon('send'));
  bar.append(micBtn, input);
  if (attachBtn && attachInput) bar.append(attachBtn, attachInput);
  bar.append(sendBtn);
  return { bar, input, micBtn, sendBtn, attachBtn, attachInput };
}

// lucide 字形节点：全站唯一字形源的装配形态（节点装配，不写裸 innerHTML —— 组件契约 R5）。
function lucideIcon(name) {
  const i = document.createElement('i');
  i.setAttribute('data-lucide', name);
  return i;
}

// ── 搜索壳工厂（批②）──────────────────────────────────────────────────────
// 搜索弹层壳 = 主对话与消息窗口共用**同一实例**（手机层页面化后 navController
// 只是把 #search-modal 移入页面体，不复制壳）。模板同为主输入条口径：迁移前
// index.html 静态块的逐字节内芯、零插值、零用户内容通道。
const SEARCH_SHELL = `
    <div id="search-modal" class="ds-layer">
      <div class="search-modal-header">
        <span id="search-modal-title">Search</span>
        <button id="search-modal-close" class="panel-btn" title="Close"><i data-lucide="x"></i></button>
      </div>
      <div class="search-controls">
        <input id="search-keyword" type="text" placeholder="Keyword…" autocomplete="off"
               role="combobox" aria-expanded="true" aria-controls="search-results">
        <div class="search-tabs" role="tablist" aria-label="Search categories">
          <button type="button" class="search-tab" id="search-tab-all" role="tab" aria-selected="true" data-tab="all"></button>
          <button type="button" class="search-tab" id="search-tab-images" role="tab" aria-selected="false" data-tab="images"></button>
          <button type="button" class="search-tab" id="search-tab-files" role="tab" aria-selected="false" data-tab="files"></button>
          <button type="button" class="search-tab" id="search-tab-pop" role="tab" aria-selected="false" data-tab="pop"></button>
          <button type="button" class="search-tab" id="search-tab-date" role="tab" aria-selected="false"
                  aria-haspopup="dialog" aria-expanded="false" data-tab="date"></button>
        </div>
        <div class="search-filters">
          <select id="search-scope" class="cfg-select"></select>
          <select id="search-agent" class="cfg-select"></select>
          <button id="search-tool" type="button" class="cfg-select" aria-haspopup="menu" aria-expanded="false"></button>
          <button id="search-go" class="glass-control cfg-btn cfg-btn-sm">Search</button>
        </div>
        <div id="search-date-popover" role="dialog" hidden></div>
      </div>
      <div id="search-status" aria-live="polite"></div>
      <div id="search-results" role="listbox"></div>
    </div>
  `;

export function searchShell(opts = {}) {
  const mount = opts.mount;
  if (!mount) throw new Error('searchShell: mount element required');
  mount.insertAdjacentHTML('afterbegin', SEARCH_SHELL);
  return mount;
}
