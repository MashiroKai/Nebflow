// panelSearch.js — 桌面「消息」面板顶部搜索框（desktop-search-field 批 2026-11-17）
// 作者令：「然后电脑端的消息 顶部应该要有搜索的框 同时优化一下样式 联系人搜索
//  那边也是 风格和手机端的搜索框保持一致」。
//
// 形态（与手机常驻条同构，微信桌面口径）：
//   · 面板头（.panel-header）之下、会话列表（#fm-conversations）之上常驻一条
//     搜索框 —— 输入行 = socialSearchCore.buildFieldShell（两端同一构建器，
//     材质 .nf-glass / 几何 css/searchField.css 的 --sfs-* 令牌单源）；
//   · 检索引擎 = socialSearchCore.createSocialMessageSearch（与手机消息页
//     **同一实现**）：跨会话社交消息客户端检索 —— conversationSnapshot() 会话
//     快照（messages.js 列表同源；极早期回落 api.getConversations）→ 范围档
//     过滤（全部/好友/设备/群聊 chips）→ 逐会话 api.getMessages 前向分页扫描
//     （50/页 × 每段 20 页，段尾「加载更多（本会话）」续扫到最早一条）→
//     按会话分组 + <mark> 高亮 + 点行 openConversation + revealMessage 定位。
//     **零新后端**（/api/search/chat 是 agent 会话历史搜索，与此无关）。
//   · 输入即搜（300ms 去抖）/ Enter 即搜 / Esc 与清空键 = 清空回列表 /
//     空态 = 列表照常（条常驻）；无结果 = 状态行「未找到 · 提示」。
//   · 快捷键：Mod+K（Ctrl/Cmd+K）聚焦搜索框（仅消息面板可见时；Mod+F 已被
//     chatSearch 的 agent 会话搜索占用，不夺）。
//
// 边界：只追加自有节点；封存态（features.friends OFF）main.js 不调 init ⇒ 不建。

import { t } from './i18n.js';
import { createIconsIn } from './utils.js';
import { createSocialMessageSearch, buildFieldShell } from './socialSearchCore.js';

/** @type {ReturnType<typeof createSocialMessageSearch>|null} */
let engine = null;

function buildMsgSearch() {
  const panel = document.querySelector('#panel-messages');
  if (!panel || panel.querySelector('.fm-msg-search')) return;
  const header = panel.querySelector('.panel-header');

  const wrap = document.createElement('div');
  wrap.className = 'fm-msg-search';
  wrap.setAttribute('role', 'search');
  wrap.setAttribute('aria-label', t('mhsearch.placeholder'));

  const input = document.createElement('input');
  input.type = 'text';
  input.autocomplete = 'off';
  input.id = 'fm-msg-search-input';
  const { shell, clear } = buildFieldShell(input, { placeholder: t('mhsearch.placeholder') });

  const status = document.createElement('div');
  status.className = 'mh-search-status';
  status.setAttribute('role', 'status');
  status.setAttribute('aria-live', 'polite');
  status.hidden = true;

  const results = document.createElement('div');
  results.className = 'mh-search-results';

  const note = document.createElement('div');
  note.className = 'mh-search-note';
  note.textContent = t('mhsearch.capNote');

  engine = createSocialMessageSearch({
    input,
    resultsHost: results,
    statusEl: status,
    // 有查询 ⇒ 会话列表让位、结果流自滚（CSS 判据 = .has-query，与手机页同构）。
    onHasQuery: (has) => wrap.classList.toggle('has-query', has),
  });
  const scopeRow = engine.scopeRow();

  wrap.append(shell, scopeRow, status, results, note);
  panel.insertBefore(wrap, header ? header.nextSibling : panel.firstChild);
  if (typeof lucide !== 'undefined') createIconsIn(wrap);

  input.addEventListener('input', () => engine?.onValueInput());
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      // Esc = 清空回列表（与手机条同语义）。
      e.preventDefault();
      clear.click();
    } else if (e.key === 'Enter') {
      e.preventDefault();
      engine?.submitNow();
    }
  });
}

/** Mod+K：消息面板可见时聚焦搜索框（栓在 window，一次装入；面板不可见不夺键）。 */
function bindShortcut() {
  window.addEventListener('keydown', (e) => {
    if (!(e.metaKey || e.ctrlKey)) return;
    if (e.key !== 'k' && e.key !== 'K') return;
    const panel = document.querySelector('#panel-messages');
    if (!panel || !panel.classList.contains('active')) return;
    const input = /** @type {HTMLInputElement|null} */ (document.querySelector('#fm-msg-search-input'));
    if (!input) return;
    e.preventDefault();
    input.focus();
    input.select();
  });
}

let wired = false;

/** 装配入口（main.js 好友门控内调用，与 initContacts/initMessages 同拍）。 */
export function initPanelSearch() {
  if (wired) return;
  wired = true;
  buildMsgSearch();
  bindShortcut();
  window.addEventListener('locale-changed', () => {
    const wrap = document.querySelector('#panel-messages .fm-msg-search');
    if (!wrap) return;
    const input = /** @type {HTMLInputElement|null} */ (wrap.querySelector('.sf-field-input'));
    if (input) {
      input.placeholder = t('mhsearch.placeholder');
      input.setAttribute('aria-label', t('mhsearch.placeholder'));
    }
    wrap.setAttribute('aria-label', t('mhsearch.placeholder'));
    wrap.querySelector('.sf-field-clear')?.setAttribute('aria-label', t('mhsearch.clear'));
    const note = wrap.querySelector('.mh-search-note');
    if (note) note.textContent = t('mhsearch.capNote');
  });
}
