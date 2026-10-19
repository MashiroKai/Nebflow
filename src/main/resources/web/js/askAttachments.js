// askAttachments.js — AskUser 卡片作用域的**答复附件**面（askuser-dragdrop 实施批
// 2026-10-19，批 #551；设计稿 = 20261019_askuser-dragdrop-design，作者三修正后实施）。
//
// 入口纪律（作者修正①②，推翻设计稿「附件与选项共存」推荐）：
//   · **只给开放题**：卡内全部题都带 options（选择题卡）⇒ 不渲染任何附件入口；
//     至少一道开放题（无 options）才挂附件区。
//   · **不支持附件的场景一律静默隐藏**：secret 卡（fail-closed）、dirPicker 卡
//     （答案只由选择面写入）、选择题卡、已终结卡——无按钮、无禁用态、无提示、
//     无拒绝反馈；拖拽/粘贴落在这类卡上零动作（只 preventDefault 防浏览器开门）。
//     服务端（上传端点 403 / 答案侧终验）仍保留 fail-closed 校验——纵深防御。
//   · **多文件真支持**（作者修正③）：拖入一次多件、粘贴一次多件、点选多选，
//     全部逐件入队（≤9 个/卡、单文件 ≤100MB——与后端同一口径的客户端预检）。
//
// 上传时机 = 拖入即传（设计稿 §2.6）：XHR（带上传进度）POST
// /api/askuser/attachments?requestId=…，发送帧只带已落盘引用（path/sha256/size/mime）。
// 失败 chip：红描边 + 重试钮；上传中 chip：进度条 + 百分比，发送钮置灰显示上传中。
// 移除 = abort（上传中的 XHR 一并掐断）；已发送卡无 ×。
// 样式全部 inline（本批不动任何 CSS 文件——renderAskAttachments 先例同一纪律）。

import { t } from './i18n.js';

const MAX_FILES = 9;
const MAX_BYTES = 100 * 1024 * 1024; // 100MB（与后端 MaxAttachmentBytes 同值口径）

/** 活卡注册表：document 级 paste 监听按 hover/focus 派发到唯一目标卡。 */
const liveCards = new Set();
let pasteListenerInstalled = false;

function installPasteListener() {
  if (pasteListenerInstalled) return;
  pasteListenerInstalled = true;
  document.addEventListener('paste', (e) => {
    if (!liveCards.size) return;
    // 自清：卡已被替换/移除（reclaimAskUserCards / closeAskUserCard 走 DOM 移除，
    // 不逐个调 destroy）⇒ 断连卡就地出册，注册表不随会话时长泄漏。
    [...liveCards].forEach(c => { if (!c.box.isConnected) liveCards.delete(c); });
    if (!liveCards.size) return;
    const files = filesFromClipboard(e);
    if (!files.length) return; // 文本粘贴走浏览器默认，不拦（设计稿 §2.4）
    const target =
      [...liveCards].find(c => c.box.contains(document.activeElement)) ||
      [...liveCards].find(c => c.box.matches(':hover'));
    if (!target) return;
    e.preventDefault(); // 命中活卡（焦点或悬停）⇒ 文件进附件管线
    enqueueFiles(target, files);
  });
}

function filesFromClipboard(e) {
  const out = [];
  const items = (e.clipboardData && e.clipboardData.items) || [];
  for (const item of items) {
    if (item && item.kind === 'file') {
      const f = item.getAsFile();
      if (f) out.push(f);
    }
  }
  return out;
}

/** 卡是否可以有附件入口（修正①②的单一判据；渲染与拖拽 armed 共用）。 */
export function answerAttachmentsAllowed(questions) {
  if (!Array.isArray(questions) || questions.length === 0) return false;
  if (questions.some(q => q && q.secret === true)) return false; // secret 卡 fail-closed
  if (questions.some(q => q && q.dirPicker === true)) return false; // 选择面写入的卡不带
  // 至少一道开放题（无 options）——选择题卡不显示入口（修正①）。
  return questions.some(q => q && !(q.options && q.options.length > 0));
}

function fmtSize(b) {
  if (b >= 1048576) return (b / 1048576).toFixed(1) + ' MB';
  if (b >= 1024) return Math.round(b / 1024) + ' KB';
  return b + ' B';
}

function extLabel(name) {
  const i = String(name).lastIndexOf('.');
  return i > 0 ? String(name).slice(i + 1).toUpperCase() : 'FILE';
}

function toast(msg, type) {
  window.__showToast?.(msg, type || 'error');
}

const SVG_PAPERCLIP =
  '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/></svg>';
const SVG_IMAGE =
  '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>';
const SVG_FILE =
  '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>';
const SVG_CHECK =
  '<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="20 6 9 17 4 12"/></svg>';

/**
 * 在 AskUser 卡（`.option-box`）内挂附件区 + 整卡拖拽面。只在活卡（有 requestId、
 * `answerAttachmentsAllowed(questions)`）被调用。
 *
 * @param {HTMLElement} box         卡根（.option-box，需已在 DOM 内）
 * @param {{requestId:string, onChange?:Function}} opts
 * @returns controller | null：
 *   satisfyGate()   零选项卡：≥1 个已附 chip 即满足发送闸（选项卡恒 false——附件不替代选项）
 *   uploading()     有 chip 在传（发送置灰 + 文案「上传中…」）
 *   hasFailed()     有失败 chip（点发送时拦截 toast）
 *   attachments()   已附清单（发送帧的 attachments 键；只含 done 件）
 *   lock()          发送后转只读（去 ×/进度条），整卡面降级、退出活卡注册
 *   destroy()       卡被替换/移除时摘除注册与监听
 */
export function mountAnswerAttachments(box, opts) {
  const requestId = opts && opts.requestId;
  const onChange = opts && typeof opts.onChange === 'function' ? opts.onChange : () => {};
  if (!box || !requestId) return null;

  const ctx = { box, requestId, items: [], els: null, locked: false, onChange };
  liveCards.add(ctx);
  installPasteListener();

  // ---- 附件区 DOM（插位：`.option-btn-row` 之前；与 renderAskAttachments 同位）----
  const btnRow = box.querySelector('.option-btn-row');
  const zone = document.createElement('div');
  zone.className = 'ask-answer-attach-zone';
  zone.style.cssText = 'margin-top:12px;';

  const head = document.createElement('div');
  head.style.cssText =
    'display:flex;align-items:center;gap:6px;font:600 11px -apple-system,BlinkMacSystemFont,sans-serif;' +
    'color:var(--color-text-muted,#888);letter-spacing:.3px;margin-bottom:6px;';
  const headClip = document.createElement('span');
  headClip.style.cssText = 'display:inline-flex;color:var(--color-text-muted,#888);';
  headClip.innerHTML = SVG_PAPERCLIP;
  const headLabel = document.createElement('span');
  headLabel.textContent = t('askUser.replyAttachTitle');
  const headCount = document.createElement('span');
  headCount.style.cssText = 'margin-left:auto;font-weight:500;color:var(--color-text-dim,#9ba0aa);';
  head.append(headClip, headLabel, headCount);

  const chips = document.createElement('div');
  chips.className = 'ask-answer-attach-chips';
  chips.style.cssText = 'display:flex;flex-wrap:wrap;gap:8px;';

  const emptyBtn = document.createElement('button');
  emptyBtn.type = 'button';
  emptyBtn.className = 'ask-answer-attach-empty';
  emptyBtn.style.cssText =
    'display:flex;align-items:center;gap:7px;width:100%;padding:9px 12px;border-radius:9px;' +
    'border:1px dashed var(--color-border,rgba(128,128,128,0.35));background:transparent;' +
    'color:var(--color-text-muted,#888);font:inherit;font-size:12px;cursor:pointer;text-align:left;';
  const emptyIco = document.createElement('span');
  emptyIco.style.cssText = 'display:inline-flex;flex:none;';
  emptyIco.innerHTML = SVG_IMAGE;
  const emptyText = document.createElement('span');
  emptyText.textContent = t('askUser.attachEmpty');
  emptyBtn.append(emptyIco, emptyText);

  // 点选兜底（触屏主路径——修正④：触屏无拖拽）：隐藏多选 input。
  const filePick = document.createElement('input');
  filePick.type = 'file';
  filePick.multiple = true;
  filePick.style.display = 'none';
  filePick.setAttribute('aria-hidden', 'true');
  filePick.addEventListener('change', () => {
    enqueueFiles(ctx, Array.from(filePick.files || []));
    filePick.value = '';
  });
  emptyBtn.addEventListener('click', () => {
    if (!ctx.locked) filePick.click();
  });

  zone.append(head, chips, emptyBtn, filePick);
  if (btnRow) btnRow.before(zone);
  else box.appendChild(zone);
  ctx.els = { zone, chips, emptyBtn, headCount };

  // ---- 整卡拖拽（热区 = 整卡，设计稿 §2.2；armed 态浮层纯视觉 aria-hidden）----
  const overlay = document.createElement('div');
  overlay.className = 'ask-answer-drop-overlay';
  overlay.setAttribute('aria-hidden', 'true');
  overlay.style.cssText =
    'position:absolute;inset:0;z-index:5;border-radius:11px;display:none;align-items:center;justify-content:center;gap:10px;' +
    'background:color-mix(in srgb, var(--color-surface,rgba(20,22,28,0.9)) 88%, transparent);' +
    'border:2px dashed var(--color-primary,#07c160);color:var(--color-primary,#07c160);' +
    'font-weight:600;font-size:14px;pointer-events:none;';
  const overlayIco = document.createElement('span');
  overlayIco.style.cssText = 'display:inline-flex;';
  overlayIco.innerHTML = SVG_IMAGE;
  const overlayText = document.createElement('span');
  overlayText.textContent = t('askUser.attachDropHint');
  overlay.append(overlayIco, overlayText);
  box.appendChild(overlay);

  let dragDepth = 0;
  // 卡级 preventDefault 对**所有**含 Files 的 drop 生效（防浏览器默认开门——静默）；
  // armed 活卡才进附件管线；locked 卡零动作（修正②）。
  box.addEventListener('dragenter', (e) => {
    if (!hasFiles(e)) return;
    e.preventDefault();
    if (ctx.locked) return;
    dragDepth++;
    overlay.style.display = 'flex';
  });
  box.addEventListener('dragover', (e) => {
    if (!hasFiles(e)) return;
    e.preventDefault();
    if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy';
  });
  box.addEventListener('dragleave', () => {
    dragDepth = Math.max(0, dragDepth - 1);
    if (dragDepth === 0) overlay.style.display = 'none';
  });
  box.addEventListener('drop', (e) => {
    if (!hasFiles(e)) return;
    e.preventDefault();
    dragDepth = 0;
    overlay.style.display = 'none';
    if (ctx.locked) return; // 已发送/已终结：静默零动作（修正②）
    const files = Array.from((e.dataTransfer && e.dataTransfer.files) || []);
    if (files.length) enqueueFiles(ctx, files);
  });

  function hasFiles(e) {
    if (!e || !e.dataTransfer) return false;
    const types = Array.from(e.dataTransfer.types || []);
    return types.includes('Files');
  }

  // ---- chip 管线（多文件：逐件独立入队——修正③）----
  function enqueueFiles(c, files) {
    if (c.locked || !files || !files.length) return;
    let changed = false;
    for (const file of files) {
      if (c.items.length >= MAX_FILES) {
        toast(t('askUser.attachTooMany', { n: String(MAX_FILES) }));
        break;
      }
      if (file.size > MAX_BYTES) {
        toast(t('askUser.attachTooLarge', { name: file.name }));
        continue;
      }
      if (c.items.some(i => i.name === file.name && i.size === file.size)) {
        toast(t('askUser.attachDuplicate', { name: file.name }), 'info');
        continue;
      }
      addChip(c, file);
      changed = true;
    }
    if (changed) c.onChange();
  }

  function addChip(c, file) {
    const item = {
      name: file.name,
      size: file.size,
      mime: file.type || 'application/octet-stream',
      file, // File 本体（重试复用同一引用）
      status: 'uploading',
      pct: 0,
      xhr: null,
      path: null,
      sha256: '',
      seq: 0,
      localUrl: null,
    };
    c.items.push(item);

    const chip = document.createElement('div');
    chip.className = 'ask-answer-attach-chip';
    chip.tabIndex = 0;
    chip.style.cssText =
      'position:relative;display:flex;align-items:center;gap:9px;padding:6px 10px 6px 6px;border-radius:10px;' +
      'overflow:hidden;max-width:260px;border:1px solid var(--color-border,rgba(128,128,128,0.35));' +
      'background:var(--color-surface-2,rgba(127,127,127,0.08));';
    const isImage = (item.mime || '').startsWith('image/');
    const thumb = document.createElement('span');
    thumb.style.cssText =
      'flex:none;width:38px;height:38px;border-radius:7px;display:flex;align-items:center;justify-content:center;' +
      'background:var(--color-surface,rgba(20,22,28,0.5));border:1px solid var(--color-border,rgba(128,128,128,0.35));' +
      'color:var(--color-text-dim,#9ba0aa);overflow:hidden;';
    if (isImage) {
      const img = document.createElement('img');
      img.alt = '';
      img.style.cssText = 'width:100%;height:100%;object-fit:cover;';
      img.src = URL.createObjectURL(file);
      item.localUrl = img.src;
      thumb.appendChild(img);
    } else {
      thumb.innerHTML = SVG_FILE;
    }
    const meta = document.createElement('div');
    meta.style.cssText = 'min-width:0;';
    const nameEl = document.createElement('div');
    nameEl.className = 'ask-answer-attach-name';
    nameEl.textContent = file.name;
    nameEl.title = file.name;
    nameEl.style.cssText =
      'font-size:12px;font-weight:500;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:150px;';
    const sub = document.createElement('div');
    sub.style.cssText =
      'font-size:10.5px;color:var(--color-text-muted,#888);margin-top:1px;display:flex;gap:6px;align-items:center;';
    sub.textContent = t('askUser.attachUploading', { n: '0' });
    meta.append(nameEl, sub);
    const rm = document.createElement('button');
    rm.type = 'button';
    rm.className = 'ask-answer-attach-rm';
    rm.textContent = '×';
    rm.title = t('askUser.removeAttachment', { name: file.name });
    rm.setAttribute('aria-label', t('askUser.removeAttachment', { name: file.name }));
    rm.style.cssText =
      'position:absolute;top:-1px;right:-1px;width:18px;height:18px;border-radius:0 10px 0 8px;border:none;' +
      'background:var(--color-surface-2,rgba(127,127,127,0.08));border-left:1px solid var(--color-border,rgba(128,128,128,0.35));' +
      'border-bottom:1px solid var(--color-border,rgba(128,128,128,0.35));color:var(--color-text-muted,#888);' +
      'font-size:11px;line-height:1;cursor:pointer;display:flex;align-items:center;justify-content:center;';
    rm.addEventListener('click', () => removeChip(c, item));
    const bar = document.createElement('div');
    bar.style.cssText = 'position:absolute;left:0;bottom:0;height:3px;width:100%;';
    const barFill = document.createElement('i');
    barFill.style.cssText =
      'display:block;height:100%;width:0%;background:var(--color-primary,#07c160);transition:width .2s linear;';
    bar.appendChild(barFill);
    chip.append(thumb, meta, rm, bar);
    c.els.chips.appendChild(chip);
    item.chip = chip;
    item.sub = sub;
    item.barFill = barFill;

    upload(c, item);
    refreshHead(c);
  }

  function removeChip(c, item) {
    if (c.locked) return;
    if (item.xhr) {
      try { item.xhr.abort(); } catch { /* already done */ }
    }
    if (item.localUrl) URL.revokeObjectURL(item.localUrl);
    if (item.chip) item.chip.remove();
    c.items = c.items.filter(i => i !== item);
    refreshHead(c);
    c.onChange();
  }

  function refreshHead(c) {
    const n = c.items.length;
    c.els.headCount.textContent = n ? n + ' / ' + MAX_FILES : '';
    c.els.emptyBtn.style.display = n ? 'none' : '';
  }

  function markDoneSub(c, item) {
    if (!item.sub) return;
    item.sub.textContent = '';
    const okSpan = document.createElement('span');
    okSpan.style.cssText =
      'color:var(--color-primary,#07c160);display:inline-flex;align-items:center;gap:3px;';
    okSpan.innerHTML = SVG_CHECK;
    okSpan.appendChild(document.createTextNode(t('askUser.attachAttached')));
    item.sub.append(extLabel(item.name) + ' · ' + fmtSize(item.size) + ' ', okSpan);
  }

  /** 拖入即传（XHR 带上传进度；fetch 无上传进度面）。鉴权与 /api 域同 rail
    * （authHeaders 同款：localStorage 令牌在场则 Bearer，会话 cookie 恒自动携带）。 */
  function upload(c, item) {
    const xhr = new XMLHttpRequest();
    item.xhr = xhr;
    item.status = 'uploading';
    item.pct = 0;
    if (item.chip) item.chip.style.borderColor = '';
    if (item.barFill) {
      item.barFill.style.width = '0%';
      item.barFill.style.background = 'var(--color-primary,#07c160)';
    }
    if (item.sub) item.sub.textContent = t('askUser.attachUploading', { n: '0' });
    xhr.open('POST', '/api/askuser/attachments?requestId=' + encodeURIComponent(c.requestId));
    const token = localStorage.getItem('token') || '';
    if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token);
    xhr.responseType = 'json';
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && item.status === 'uploading' && item.xhr === xhr) {
        item.pct = Math.round((e.loaded / e.total) * 100);
        if (item.barFill) item.barFill.style.width = item.pct + '%';
        if (item.sub) item.sub.textContent = t('askUser.attachUploading', { n: String(item.pct) });
      }
    };
    xhr.onerror = () => fail();
    xhr.onabort = () => { /* 用户移除触发的 abort——无动作 */ };
    xhr.onload = () => {
      if (item.xhr !== xhr) return;
      const body = xhr.response || {};
      if (xhr.status >= 200 && xhr.status < 300 && body && body.ok) {
        item.status = 'done';
        item.path = body.path;
        item.sha256 = body.sha256 || '';
        item.seq = body.seq || 0;
        item.mime = body.mime || item.mime;
        if (item.barFill) item.barFill.style.width = '0%';
        markDoneSub(c, item);
        c.onChange();
      } else {
        fail();
      }
    };
    function fail() {
      if (item.xhr !== xhr) return;
      item.status = 'failed';
      if (item.chip) item.chip.style.borderColor = 'color-mix(in srgb, var(--color-error,#f44336) 60%, transparent)';
      if (item.barFill) {
        item.barFill.style.width = '100%';
        item.barFill.style.background = 'var(--color-error,#f44336)';
      }
      if (item.sub) {
        item.sub.textContent = '';
        const errSpan = document.createElement('span');
        errSpan.textContent = t('askUser.attachUploadFailed');
        errSpan.style.cssText = 'color:var(--color-error,#f44336);';
        const retry = document.createElement('button');
        retry.type = 'button';
        retry.textContent = t('askUser.attachRetry');
        retry.style.cssText =
          'border:none;background:none;color:var(--color-warning,#ff9800);font:inherit;font-size:11px;' +
          'cursor:pointer;padding:0;text-decoration:underline;text-underline-offset:2px;';
        retry.addEventListener('click', () => { if (!c.locked) upload(c, item); });
        item.sub.append(fmtSize(item.size) + ' · ', errSpan, ' ', retry);
      }
      toast(t('askUser.attachUploadFailedToast', { name: item.name }));
      c.onChange();
    }
    const fd = new FormData();
    fd.append('file', item.file, item.name);
    xhr.send(fd);
  }

  // ---- controller ----
  return {
    satisfyGate() {
      // 零选项卡：≥1 个 done 附件即满足发送闸；选项卡恒 false（附件不替代选项）。
      if (ctx.locked) return false;
      return ctx.items.some(i => i.status === 'done');
    },
    uploading() {
      return !ctx.locked && ctx.items.some(i => i.status === 'uploading');
    },
    hasFailed() {
      return !ctx.locked && ctx.items.some(i => i.status === 'failed');
    },
    attachments() {
      return ctx.items
        .filter(i => i.status === 'done' && i.path)
        .map(i => ({
          id: 'att-' + ctx.requestId + '-' + (i.seq || 0),
          seq: i.seq || 0,
          name: i.name,
          path: i.path,
          sha256: i.sha256,
          size: i.size,
          mime: i.mime,
          kind: (i.mime || '').startsWith('image/') ? 'image' : 'file',
        }));
    },
    lock() {
      ctx.locked = true;
      ctx.items.forEach(i => {
        if (i.xhr) { try { i.xhr.abort(); } catch { /* noop */ } }
        if (i.chip) {
          const r = i.chip.querySelector('.ask-answer-attach-rm');
          if (r) r.remove();
          if (i.barFill) i.barFill.style.width = '0%';
          if (i.status === 'done') markDoneSub(ctx, i);
        }
        if (i.localUrl) { URL.revokeObjectURL(i.localUrl); i.localUrl = null; }
      });
      if (ctx.els) {
        ctx.els.emptyBtn.style.display = 'none';
        ctx.els.headCount.textContent = '';
      }
      overlay.style.display = 'none';
      liveCards.delete(ctx);
    },
    destroy() {
      liveCards.delete(ctx);
      ctx.items.forEach(i => {
        if (i.xhr) { try { i.xhr.abort(); } catch { /* noop */ } }
        if (i.localUrl) URL.revokeObjectURL(i.localUrl);
      });
    },
  };
}

/** 历史卡/已答卡的「已回答 · 附 N 个文件」徽标（设计稿 §2.8；渲染于 renderAskUserHistory）。 */
export function mountAnsweredBadge(box, n) {
  if (!box || !n) return;
  const badge = document.createElement('span');
  badge.className = 'ask-answer-attach-badge';
  badge.textContent = t('askUser.attachCountBadge', { n: String(n) });
  badge.style.cssText =
    'float:right;margin:0 0 6px 10px;font-size:10.5px;font-weight:600;padding:2px 9px;border-radius:999px;' +
    'letter-spacing:.3px;background:color-mix(in srgb, var(--color-primary,#07c160) 14%, transparent);' +
    'color:var(--color-primary,#07c160);';
  box.insertBefore(badge, box.firstChild);
}
