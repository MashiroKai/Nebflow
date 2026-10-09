// input.js 拆分（FE组件化批次四 2026-09-27）：附件与引用帧族（图片压缩/文件附件/纯引用帧闸/引用芯片/全局拖放） 可复用模块（行为保持）。
import state from '../state.js';
import { activeView, chatViews, setActiveView } from '../chatView.js';
import { renderAttachmentPreview } from '../chat.js';
import { t } from '../i18n.js';
import { makeReference } from '../reference.js';

// ---------- Image Compression ----------
function compressImage(file, opts = {}) {
  const maxDim = opts.maxDim || 1920;
  const quality = opts.quality || 0.8;
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(img.src);
      let w = img.width, h = img.height;
      if (w > maxDim || h > maxDim) {
        const scale = maxDim / Math.max(w, h);
        w = Math.round(w * scale);
        h = Math.round(h * scale);
      }
      const canvas = document.createElement('canvas');
      canvas.width = w; canvas.height = h;
      const ctx = canvas.getContext('2d');
      ctx.drawImage(img, 0, 0, w, h);
      const dataUrl = canvas.toDataURL('image/jpeg', quality);
      resolve({ dataUrl, w, h });
    };
    img.onerror = () => { URL.revokeObjectURL(img.src); reject(new Error('Image load failed')); };
    img.src = URL.createObjectURL(file);
  });
}

// ---------- File Attachment ----------

/** Show an inline error in the attachment preview area (no alert popup). */
export function showAttError(msg, target) {
  const attPreview = (target && target.attPreviewEl) || (activeView && activeView.dom && activeView.dom.attPreview);
  if (!attPreview) { console.warn(msg); return; }
  const err = document.createElement('div');
  err.className = 'att-error';
  err.textContent = msg;
  attPreview.appendChild(err);
  setTimeout(() => { err.classList.add('att-error-fade'); setTimeout(() => err.remove(), 300); }, 2500);
}

/** Convert ArrayBuffer to base64 in chunks (avoids reading file twice). */
function arrayBufferToBase64(buffer) {
  const bytes = new Uint8Array(buffer);
  const chunkSize = 0x8000;
  let binary = '';
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunkSize));
  }
  return btoa(binary);
}

// Track pending attachment operations to prevent send() race condition
export const pendingAttCount = { value: 0 };

export async function addFileAttachment(file, callback, target) {
  // Capture the view at entry — activeView is a live module binding that ws.js
  // changes on every incoming message. Without capturing, the await points below
  // would read a stale/changed activeView, causing renderAttachmentPreview to
  // target the wrong DOM element (or crash on null).
  if (!target && activeView) {
    target = { attPreviewEl: activeView.dom.attPreview, attachments: activeView.pendingAttachments };
  }
  const attachments = (target && target.attachments) || (activeView && activeView.pendingAttachments);
  if (!attachments) { console.error('[input] addFileAttachment: no attachments array'); return; }

  // Increment once at entry — every exit path below decrements.
  pendingAttCount.value++;

  if (file.type.startsWith('image/')) {
    if (file.size > 10 * 1024 * 1024) {
      pendingAttCount.value--;
      showAttError('Image too large (max 10MB): ' + file.name, target);
      return;
    }
    try {
      const { dataUrl, w, h } = await compressImage(file);
      attachments.push({
        type: 'image', mimeType: 'image/jpeg',
        data: dataUrl.split(',')[1],
        name: file.name, preview: dataUrl
      });
    } catch (e) {
      console.warn('[input] image compression failed, using original:', e);
      // Fallback: read once as ArrayBuffer, derive both base64 and preview
      try {
        const buffer = await file.arrayBuffer();
        const base64Data = arrayBufferToBase64(buffer);
        const mimeType = file.type || 'image/jpeg';
        const preview = 'data:' + mimeType + ';base64,' + base64Data;
        attachments.push({
          type: 'image', mimeType,
          data: base64Data, name: file.name, preview
        });
      } catch (e2) {
        console.warn('[input] image fallback read failed:', e2);
        showAttError('Failed to read image: ' + file.name, target);
        pendingAttCount.value--;
        return;
      }
    }
    renderAttachmentPreview(target);
    if (callback) callback();
    pendingAttCount.value--;
  } else {
    // Non-image: send metadata (name + size + hash) for filesystem resolution,
    // plus base64 data for small files (< 5MB) as a fallback when the file
    // can't be found on disk (e.g. inside ~/Library/Containers).
    try {
      const buffer = await file.arrayBuffer();
      let hash = '';
      try {
        const hashBuffer = await crypto.subtle.digest('SHA-256', buffer);
        const hashArray = Array.from(new Uint8Array(hashBuffer));
        hash = hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
      } catch (e) {
        console.warn('[input] SHA-256 computation failed:', e);
      }
      const isSmall = file.size < 5 * 1024 * 1024;
      const base64Data = isSmall ? arrayBufferToBase64(buffer) : '';
      attachments.push({
        type: 'text', mimeType: file.type || 'application/octet-stream',
        data: base64Data, name: file.name, hash, size: file.size
      });
      renderAttachmentPreview(target);
      if (callback) callback();
    } catch (e) {
      console.warn('[input] file read failed:', e);
      showAttError('Failed to read file: ' + file.name, target);
    }
    pendingAttCount.value--;
  }
}

// ---------- Ref-only frame gate (fwdguard-impl, 2026-09-17) ----------
// 断点（诊断真源 `.nebflow/reports/20260917_forwarddiag.md` §③）：转发腿的载荷只走
// `refs`，而本文件 672-673 的过滤又刻意把 ref 从 `attachments` 里剔除 ⇒ 网关准入
// 谓词 `WebSocketRoutes.scala:4055`（`content.nonEmpty || attachments.nonEmpty`）
// 恒假 ⇒ `:4246 else IO.unit` 全静默丢弃（零日志零 turn），而前端发帧后已
// `setBusy`（本文件 696-701）⇒ 会话永久「转圈」= 空转。
//
// 作者取向（Q1，2026-09-17）：🔴 **不放宽网关闸**（`4055` 一字不改）——改为在本
// 前端把「纯引用帧」拦在**两个发送入口**上（Enter 直发 / 点击发送键），并提示
// 「请附一句话后发送」。Q2：不落历史 ⇒ `attJson` / `UiMessage` 契约零改动。
//
// 判据必须与 672-673 的过滤口径**同源**（同一 filter 表达式）——判据口径不一致
// 正是本缺陷的成因（前端以为有载荷、网关判为空帧）。

/** 帧写出时真正会进 `attachments` 的项 —— 与 send() 内 672-673 的过滤口径同源。 */
export function wireAttachmentsOf(view) {
  return (view?.pendingAttachments || []).filter(a => a.type !== 'taskRef' && a.type !== 'ref');
}

/** 该帧是否会被网关 4055 拒绝 = 「正文为空 ∧ 线上附件为空 ∧ 有载荷被该过滤剔掉
  * （ref/taskRef）」= 纯引用帧（转发后不附言直接发送的形态）。
  *
  * 边界（🔴 禁误伤，逐条）：
  *   · 有附言 + ref        ⇒ false（4055 放行，正常发送）；
  *   · 空文本 + 真附件     ⇒ false（过滤后非空 = 合法帧，4055 放行）；
  *   · 空文本 + 无任何载荷 ⇒ false（既无附件也无 ref：由 578-581 既有空守卫处理，
  *     本闸不介入 —— 避免波及 compact / skill 等「空文本合法」的既有形态）。 */
export function isRefOnlyFrame(view) {
  if (!view || !view.dom) return false;
  const text = (view.dom.input?.value || '').trim();
  if (text) return false;
  const all = view.pendingAttachments || [];
  if (all.length === 0) return false;
  return wireAttachmentsOf(view).length === 0;
}

/** 同步「纯引用帧」闸的视觉态（发送键禁用 + 提示可见）。返回闸是否生效。
  * 调用点（覆盖全部会改变该判据的路径）：initInput 首帧、输入事件、attPreview 的
  * DOM 变更观察器（附件/引用的增删都经 renderAttachmentPreview 重建整条 strip，
  * 含 chat.js 侧 ref 芯片的移除按钮——本文件不介入 chat.js）。 */
export function syncRefOnlyGate(view) {
  const v = view || activeView;
  if (!v || !v.dom) return false;
  const blocked = isRefOnlyFrame(v);
  const bar = v.dom.inputBar;
  // 冻结态（main.js `setFrozenBarState`）用**同一个** disabled 属性：两者必须合成，
  // 否则本闸在 blocked=false 时会把冻结态的禁用一并抹掉（fc-1 同族缺陷）。
  const frozen = !!(bar && (bar.classList.contains('frozen') || bar.dataset.frozen === 'true'));
  const btn = v.dom.sendBtn;
  if (btn) {
    btn.disabled = frozen || blocked;
    if (blocked) btn.setAttribute('aria-disabled', 'true');
    else if (!frozen) btn.removeAttribute('aria-disabled');
  }
  const hint = v.dom.refGateHint;
  if (hint) {
    if (blocked) hint.textContent = t('input.refOnlyHint');
    hint.hidden = !blocked;
  }
  return blocked;
}

// ---------- Global drag & drop onto input bars (#303) ----------
// Event delegation on document: popup views recreate their input bar DOM on
// every open (per-element binding would go stale — see _inputBound), so all
// drag listeners live here and resolve the owning ChatView at event time.
// A file dropped onto ANY input bar (main or popup) attaches to THAT bar's
// session; drops elsewhere are ignored (no page navigation).
const INPUT_BAR_SELECTOR = '#input-bar, .fa-input-bar';
// #303 internal drag: explorer file rows carry this custom MIME in addition to
// (or instead of) native 'Files' — both count as attach payloads.
const INTERNAL_DRAG_MIME = 'application/x-nebflow-file';
// #303 global-reference: canvas tab rows carry this MIME to produce a Reference
// (document/file/html-element) on drop into an input bar.
const CANVAS_DRAG_MIME = 'application/x-nebflow-ref';

/**
 * #303/global-reference: append a unified Reference to a view's
 * pendingAttachments and re-render the attachment preview strip. Used by the
 * explorer/canvas entry points (right-click & drag). Returns true on success.
 * @param {Object} ref  Reference produced by makeReference()
 * @param {{attPreviewEl?:HTMLElement, attachments?:Array, focus?:boolean}} [target]
 */
export function appendRefToActiveView(ref, target) {
  const view = activeView || state.getActiveView?.();
  if (!view || !ref) return false;
  if (!Array.isArray(view.pendingAttachments)) view.pendingAttachments = [];
  view.pendingAttachments.push(ref);
  if (target && target.attPreviewEl) {
    renderAttachmentPreview({ attPreviewEl: target.attPreviewEl, attachments: view.pendingAttachments });
  } else if (view.dom && view.dom.attPreview) {
    renderAttachmentPreview({ attPreviewEl: view.dom.attPreview, attachments: view.pendingAttachments });
  }
  if (target?.focus !== false) view.dom?.input?.focus?.();
  return true;
}

export function initGlobalFileDrop() {
  let dragDepth = 0;    // child-element nesting depth inside the bar
  let activeBar = null; // currently highlighted input bar

  const hasAttach = (e) => {
    if (!e.dataTransfer) return false;
    const types = Array.from(e.dataTransfer.types || []);
    return types.includes('Files') || types.includes(INTERNAL_DRAG_MIME) || types.includes(CANVAS_DRAG_MIME);
  };
  const barOf = (e) =>
    e.target instanceof Element ? e.target.closest(INPUT_BAR_SELECTOR) : null;
  const clearHighlight = () => {
    if (activeBar) activeBar.classList.remove('drag-over');
    activeBar = null; dragDepth = 0;
  };

  document.addEventListener('dragenter', (e) => {
    if (!hasAttach(e)) return;
    const bar = barOf(e);
    if (!bar) return;
    if (bar !== activeBar) clearHighlight();
    activeBar = bar;
    dragDepth++;
    bar.classList.add('drag-over');
  });

  document.addEventListener('dragover', (e) => {
    if (!hasAttach(e)) return;     // native text drags pass through untouched
    e.preventDefault();            // block browser default "open dropped file"
    e.dataTransfer.dropEffect = 'copy';
  });

  // No hasFiles() check here: dataTransfer.types is unreadable during
  // dragleave in some engines (this was the old stuck-highlight bug).
  document.addEventListener('dragleave', () => {
    if (!activeBar) return;
    dragDepth--;
    if (dragDepth <= 0) clearHighlight();
  });

  document.addEventListener('drop', (e) => {
    if (!hasAttach(e)) return;
    e.preventDefault();            // never navigate to the dropped file
    const bar = barOf(e);
    clearHighlight();
    if (!bar) return;              // dropped outside any input bar — ignore
    const view = Object.values(chatViews)
      .find(v => v.mounted && v.dom && v.dom.inputBar === bar);
    if (!view || view.dom.input?.readOnly) return; // disabled popup guard
    setActiveView(view);
    const target = { attPreviewEl: view.dom.attPreview,
                     attachments: view.pendingAttachments };
    // #303 global-reference drag routing (v1.1):
    //   internal explorer file row (application/x-nebflow-file with path+rootPath)
    //     → Reference @path (not an attachment upload)
    //   canvas tab row (application/x-nebflow-ref) → Reference w/ current anchor
    //   anything else (OS/Finder files) → attachment upload (#303 regression)
    const internal = e.dataTransfer.getData(INTERNAL_DRAG_MIME);
    const canvasRef = e.dataTransfer.getData(CANVAS_DRAG_MIME);
    if (internal) {
      try {
        const { path, rootPath } = JSON.parse(internal);
        if (path) {
          const absPath = joinAbsPath(rootPath, path);
          const name = path.split('/').pop() || path;
          appendRefToActiveView(makeReference({
            refType: 'file',
            source: { kind: 'workspace', path: absPath, fileName: name, title: name },
          }), { focus: false });
        } else console.warn('[drop] internal drag payload missing path');
      } catch (err) {
        console.warn('[drop] bad internal drag payload:', err);
      }
    } else if (canvasRef) {
      try {
        const input = JSON.parse(canvasRef);
        if (input && input.refType) appendRefToActiveView(makeReference(input), { focus: false });
        else console.warn('[drop] canvas ref payload missing refType');
      } catch (err) {
        console.warn('[drop] bad canvas ref payload:', err);
      }
    } else {
      // External drag (desktop files) — unchanged.
      Array.from(e.dataTransfer.files || []).forEach(f => addFileAttachment(f, null, target));
    }
    view.dom.input?.focus();
  });
}

export function joinAbsPath(rootPath, path) {
  if (!rootPath) return path;
  return rootPath.endsWith('/') ? rootPath + path : rootPath + '/' + path;
}
