// externalOpen.js — 「打开外部文件」对话框（external-open 批，2026-11-19）
//
// 作者令（2026-11-19 18:46）：「再出一个批支持外部文件拉取进 canvas 打开」。
//
// ## 现状定性（本批的起点，代码见各处注释）
//
// 「把不在项目里的文件拿进 canvas 看」在服务端**早就有两条腿**：`pop.readFile`
// （WsFileOpsHandlers.scala:633，零域门：绝对路径 + 存在 + 普通文件 + 大小闸）与
// 字节腿 `/api/nf-file`（票据 + 凭据判据）。前端的 `openWorkspaceItem`
// （canvas.js:1722）对任意 absPath 有效。**缺的只有入口**：控制台没有任何一处
// 让用户「粘贴一个项目外路径就打开」。本模块补的就是这个入口。
//
// ## 本模块的边界（不重造通路）
//
// 只做三件事：
//   ① 收一个字符串（绝对路径 或 http(s) URL）；
//   ② 把它交给服务端唯一入口 `POST /api/nf-external/open`，服务端拒因**原样**
//      显示（不吞、不猜、不改写）——凭据红线与 SSRF 闸全在服务端；
//   ③ 成功后派发既有的 `workspace-open-item` 事件 ⇒ canvas `pop.readFile` ⇒
//      viewer 注册表（无 viewer 类型仍走既有下载兜底）。零新渲染面、零新取字节腿。
//
// 事件契约与 `chat/popArtifact.js::openPopArtifact` 逐键同形（同一份打开契约的
// 第三个发射点，`pinned` 与 `optimisticOpen` 同值），区别只是**来源**：那边是
// agent 产出的文件卡，这边是用户自己给的外部路径 / URL。

import { t } from './i18n.js';
import { key } from './branding.js';
import { EVENTS } from './events.js';
import { h } from './dom.js';

/** 服务端唯一入口（`ExternalOpenRoutes`）。 */
const OPEN_URL = '/api/nf-external/open';
/** 客户端等待上限：服务端 URL 腿总时限 60s（`ExternalOpenPolicy.UrlTotalDeadlineMs`）
 *  + 落盘/清账余量。超出即视为网络层失败（可重试），不掩盖服务端语义。 */
const REQUEST_TIMEOUT_MS = 90000;

/** 服务端拒因 → i18n 键。未列出的 reason 直接显示服务端原文（**不许静默**）。 */
const REASON_KEYS = {
  'credential-path': 'externalOpen.err.credentialPath',
  'credential-hardlink': 'externalOpen.err.credentialHardlink',
  'credential-required': 'externalOpen.err.credentialRequired',
  'url-private-address': 'externalOpen.err.privateAddress',
  'url-scheme': 'externalOpen.err.scheme',
  'url-host': 'externalOpen.err.host',
  'url-malformed': 'externalOpen.err.malformed',
  'url-unresolved': 'externalOpen.err.unresolved',
  'too-many-redirects': 'externalOpen.err.redirects',
  'too-large': 'externalOpen.err.tooLarge',
  timeout: 'externalOpen.err.timeout',
  'fetch-failed': 'externalOpen.err.fetchFailed',
  'not-found': 'externalOpen.err.notFound',
  'not-absolute': 'externalOpen.err.notAbsolute',
};

/** @type {HTMLElement|null} */
let overlay = null;
/** @type {HTMLInputElement|null} */
let inputEl = null;
/** @type {HTMLElement|null} */
let errEl = null;
/** @type {HTMLButtonElement|null} */
let openBtn = null;
/** @type {HTMLButtonElement|null} */
let pasteBtn = null;
let busy = false;

function storedToken() {
  try {
    return localStorage.getItem(key('token')) || '';
  } catch (e) {
    return '';
  }
}

/** 同源 cookie 优先，401/403 再退 Bearer（与 nfTicket.js 的滞回同族）。 */
function tokenHeader() {
  const tok = storedToken();
  return tok ? { Authorization: 'Bearer ' + tok } : {};
}

function showError(message) {
  if (!errEl) return;
  errEl.textContent = message;
  errEl.hidden = false;
}

function clearError() {
  if (!errEl) return;
  errEl.textContent = '';
  errEl.hidden = true;
}

function setBusy(next) {
  busy = next;
  if (openBtn) {
    openBtn.disabled = next;
    openBtn.textContent = next ? t('externalOpen.busy') : t('externalOpen.open');
  }
  if (pasteBtn) pasteBtn.disabled = next;
  if (inputEl) inputEl.disabled = next;
}

/** 建（一次）对话框。壳与控件全部复用既有 `.wsp-*` 族 + `.modal-input`：
 *  形零新增，只多一个 `.xopen-panel` 宽度档（见 css/chat.css 的 xopen 段）。
 *  DOM 走 `dom.js::h` 构建器（零 HTML 串）：结构与字符串拼版逐节点同形（同名同序
 *  同属性），文本仍一律 textContent（下面 i18n 赋值处）。 */
function ensureDialog() {
  if (overlay) return overlay;
  const titleEl = h('div', { class: 'wsp-title' });
  const hintEl = h('div', { class: 'wsp-crumbs xopen-hint' });
  const input = h('input', {
    class: 'modal-input xopen-input',
    type: 'text',
    spellcheck: 'false',
    autocomplete: 'off',
  });
  const err = h('div', { class: 'wsp-err xopen-err', hidden: true });
  // 属性键序照抄字符串版（`type` 在 `class` 前）⇒ outerHTML 与改动前逐字节相同，
  // 等价性可直接用 diff 证明，不靠「属性顺序无所谓」的口头理由。
  const cancelBtn = h('button', { type: 'button', class: 'wsp-cancel xopen-cancel-btn' });
  const paste = h('button', { type: 'button', class: 'wsp-cancel xopen-paste-btn' });
  const open = h('button', { type: 'button', class: 'wsp-pick xopen-open-btn' });
  const foot = h('div', { class: 'wsp-foot' }, [
    h('span', { class: 'wsp-foot-slot' }),
    h('span', { class: 'wsp-cur' }),
    h('span', { class: 'wsp-foot-btns' }, [paste, cancelBtn, open]),
  ]);
  const panel = h('div', {
    class: 'wsp-panel ds-layer xopen-panel',
    role: 'dialog',
    'aria-modal': 'true',
  }, [titleEl, hintEl, input, err, foot]);
  const root = h('div', { class: 'wsp-overlay ds-overlay xopen-overlay' }, panel);
  document.body.appendChild(root);
  overlay = root;
  inputEl = input;
  errEl = err;
  openBtn = open;
  pasteBtn = paste;

  titleEl.textContent = t('externalOpen.title');
  hintEl.textContent = t('externalOpen.hint');
  input.setAttribute('placeholder', t('externalOpen.placeholder'));
  cancelBtn.textContent = t('externalOpen.cancel');
  paste.textContent = t('externalOpen.paste');
  open.textContent = t('externalOpen.open');

  cancelBtn.addEventListener('click', closeDialog);
  open.addEventListener('click', () => submit(input.value));
  paste.addEventListener('click', () => pasteInto());
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      submit(input.value);
    } else if (e.key === 'Escape') {
      e.preventDefault();
      closeDialog();
    }
  });
  // 点遮罩关闭（面板内部点击不关）。
  root.addEventListener('click', (e) => {
    if (e.target === root) closeDialog();
  });
  return overlay;
}

async function pasteInto() {
  clearError();
  try {
    const text = await navigator.clipboard.readText();
    if (inputEl && text) {
      inputEl.value = text.trim();
      inputEl.focus();
    } else if (!text) {
      showError(t('externalOpen.clipboardEmpty'));
    }
  } catch (err) {
    // 读剪贴板要权限（http 源 / 用户拒绝）——不静默，给可操作的替代。
    showError(t('externalOpen.clipboardBlocked'));
  }
}

/** 打开对话框（幂等：已在场则只聚焦输入框）。 */
export function openExternalDialog(prefill) {
  ensureDialog();
  clearError();
  setBusy(false);
  if (overlay) overlay.hidden = false;
  if (inputEl) {
    if (typeof prefill === 'string' && prefill) inputEl.value = prefill;
    inputEl.focus();
    inputEl.select();
  }
}

export function closeDialog() {
  if (!overlay) return;
  overlay.hidden = true;
  setBusy(false);
}

function explain(resp, rawText) {
  const reason = (resp.headers.get('X-Nf-Reason') || '').trim();
  const mapped = REASON_KEYS[reason];
  if (mapped) return t(mapped);
  // 未列出的拒因：**原样**显示服务端那句（`reason: message` 形态），绝不吞。
  const text = String(rawText || '').trim();
  if (text) return text;
  return t('externalOpen.failed', { msg: 'HTTP ' + resp.status });
}

/** 服务端回 ok 后的唯一打开腿：派发既有 workspace-open-item 事件。
 *  事件名走 `events.js` 常量表（R3 事件名单源）；选项与改动前逐字相同 ——
 *  **不**改成 `emit()`：`emit` 固定 `bubbles:true`，本处既有语义是默认
 *  `bubbles:false`（与 `popArtifacts.js::openPopArtifact` 同一形态），派发目标
 *  本就是 window，冒泡与否不改变投递，但换掉就不是行为等价了。 */
function openAbsPath(absPath, fileName) {
  const title = fileName || String(absPath || '').split('/').pop() || 'file';
  window.dispatchEvent(new CustomEvent(EVENTS.WORKSPACE_OPEN_ITEM, {
    detail: {
      id: 'file:' + absPath,
      title,
      itemType: '',
      content: '',
      absPath,
      path: absPath,
      pinned: true,
      optimisticOpen: true,
    },
  }));
}

async function submit(raw) {
  if (busy) return;
  const value = String(raw || '').trim();
  if (!value) {
    showError(t('externalOpen.empty'));
    return;
  }
  const isUrl = /^https?:\/\//i.test(value);
  clearError();
  setBusy(true);
  try {
    const send = (extraHeaders) =>
      fetch(OPEN_URL, {
        method: 'POST',
        credentials: 'same-origin',
        cache: 'no-store',
        headers: Object.assign({ 'Content-Type': 'application/json' }, extraHeaders),
        body: JSON.stringify(isUrl ? { url: value } : { path: value }),
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      });
    let resp = await send(tokenHeader());
    if (resp.status === 401 || resp.status === 403) {
      const tok = storedToken();
      if (tok) resp = await send({ Authorization: 'Bearer ' + tok });
    }
    const text = await resp.text();
    if (!resp.ok) {
      showError(explain(resp, text));
      return;
    }
    let data = null;
    try {
      data = JSON.parse(text);
    } catch (e) {
      showError(t('externalOpen.failed', { msg: 'malformed response' }));
      return;
    }
    if (!data || !data.absPath) {
      showError(t('externalOpen.failed', { msg: 'no path in the response' }));
      return;
    }
    closeDialog();
    openAbsPath(data.absPath, data.fileName);
  } catch (err) {
    showError(t('externalOpen.failed', { msg: err && err.message ? err.message : String(err) }));
  } finally {
    setBusy(false);
  }
}
