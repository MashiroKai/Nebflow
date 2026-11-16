// refModel.js — Global Reference **模型层**（spec 20260825_global-reference-spec v1.1
// §2.1 结构 / §2.4 统一工厂）：makeReference 与它用到的纯补全助手。
//
// 抽为叶子模块（循环门禁修复批 2026-11-04）：`reference.js` 同时装模型（纯对象构造）
// 与渲染（DOM 卡片，需 `popArtifacts.js` 的媒体面），而 `msgQuote.js`（引用态/引用牌）
// 只需要**模型**——它静态 import reference.js 就等于把整条渲染链（含 popArtifacts）
// 一起拉进来，于是 popArtifacts → msgQuote → reference → popArtifacts 成环
// （scripts/check-circular.mjs 记 SCC[3]）。模型层落在叶子上后：msgQuote 只需
// `import { makeReference } from './refModel.js'`，环上那条**最薄**的边（1 个符号 /
// 1 个调用点 msgQuote.js startQuote）消失，其余两条边（reference → popArtifacts 的
// 媒体面单点、popArtifacts → msgQuote 的引用挂点）都是 codebase 里显式声明的**有意**
// 方向，逐字不动。
//
// 纪律：本文件**零 DOM / 零副作用 / 零 Chromium API** —— 只做纯对象构造。渲染入口
// （renderRefBlock / renderMessageRef / jumpRef 等）留在 reference.js。
// 兼容面：`reference.js` 保留同名 re-export（既有 import 点逐字不动，同 fileIcon.js
// 抽叶时的惯例）。
import { t } from './i18n.js';

/** Stable-ish ref id (内部用，非用户可见) — refType + monotonic counter. */
let _refSeq = 0;
function nextRefId(refType) {
  _refSeq += 1;
  return `ref:${refType}:${Date.now().toString(36)}:${_refSeq}`;
}

export function extLabel(name, mime) {
  // 20260903 filename-dup fix: an extension exists only when the BASENAME has
  // a real dot (dot > 0, so dotfiles like .gitignore stay extension-less too).
  // Dot-less names — canvas tabs pass tab.title (a display label, canvas.js
  // refInputForTab) as fileName — used to fall through split('.').pop() and
  // return the WHOLE name as the "extension", so typeLabel (chip tooltip/
  // expanded meta line + message-card badge) rendered the full filename right
  // next to the title that already showed it (引用块文件名重复, 09-03 截图).
  const base = (name || '').split('/').pop() || '';
  const dot = base.lastIndexOf('.');
  const ext = dot > 0 ? base.slice(dot + 1).toLowerCase() : '';
  if (ext === 'pdf') return 'PDF';
  if (mime === 'application/pdf') return 'PDF';
  if (['md', 'markdown'].includes(ext)) return 'MD';
  if (['xlsx', 'xls', 'csv'].includes(ext)) return 'XLS';
  if (['doc', 'docx'].includes(ext)) return 'DOC';
  if (['png', 'jpg', 'jpeg', 'gif', 'webp'].includes(ext)) return 'IMG';
  if (ext) return ext.toUpperCase();
  if (mime) return (mime.split('/').pop() || '').toUpperCase();
  return 'FILE';
}

function fileIcon(name, mime) {
  const ext = ((name || '').split('.').pop() || '').toLowerCase();
  if (['png','jpg','jpeg','gif','webp'].includes(ext)) return 'image';
  if (['pdf'].includes(ext) || mime === 'application/pdf') return 'file-text';
  if (['xlsx','xls','csv'].includes(ext)) return 'table';
  if (['md','txt','markdown'].includes(ext)) return 'file-text';
  return 'file';
}

function previewOf(name, text, max) {
  const s = (text || '').trim();
  if (!s) return '';
  const n = max || 160;
  return s.length > n ? s.slice(0, n) + '…' : s;
}

function anchorOf(anchor) {
  if (!anchor) return { kind: 'none' };
  // Fold any range-only anchor to a definite kind; keep provided fields.
  const a = { kind: 'none', ...anchor };
  if (a.pageStart != null) a.kind = 'page';
  else if (a.lineStart != null) a.kind = 'range';
  else if (a.sheet) a.kind = 'cell';
  else if (a.selector) a.kind = 'element';
  return a;
}

export function pageBadge(anchor) {
  if (!anchor) return '';
  if (anchor.kind === 'page' && anchor.pageStart != null) {
    return anchor.pageEnd != null && anchor.pageEnd !== anchor.pageStart
      ? `p.${anchor.pageStart}–${anchor.pageEnd}` : `p.${anchor.pageStart}`;
  }
  if (anchor.kind === 'range' && anchor.lineStart != null) {
    return anchor.lineEnd != null && anchor.lineEnd !== anchor.lineStart
      ? `L${anchor.lineStart}–${anchor.lineEnd}` : `L${anchor.lineStart}`;
  }
  if (anchor.kind === 'cell' && anchor.sheet) {
    return `${anchor.sheet}!${anchor.cellRange || ''}`;
  }
  if (anchor.kind === 'element') return anchor.tag ? `<${anchor.tag}>` : '';
  return '';
}

/**
 * 统一工厂（§2.4）：input 提供 refType + 该类型的源信息/锚点，补全成一个
 * 结构完整的 Reference。refType 判别产出；未知 refType 返回 null（调用方
 * 应兜底为普通文本/附件）。
 * @param {Object} input
 * @returns {Object|null}
 */
export function makeReference(input) {
  if (!input || !input.refType) return null;
  const rt = input.refType;
  const src = input.source || {};
  const anchor = anchorOf(input.anchor);
  const name = src.fileName || src.title || (src.path ? src.path.split('/').pop() : '') || '';

  if (rt === 'task') {
    const label = `@#${src.taskId != null ? src.taskId : ''} ${src.title || ''}`.trim();
    return {
      type: 'ref', refType: 'task', id: nextRefId('task'),
      source: { kind: src.kind || 'task', taskId: src.taskId, sessionId: src.sessionId, title: src.title || '' },
      anchor,
      meta: { icon: 'clipboard', typeLabel: t('ref.typeTask') },
      display: { label, preview: '', pageBadge: '' },
    };
  }

  if (rt === 'file') {
    const mime = src.mimeType || '';
    return {
      type: 'ref', refType: 'file', id: nextRefId('file'),
      source: { kind: src.kind || 'workspace', path: src.path || '', fileName: name, title: src.title || name, mimeType: mime },
      anchor,
      // 20260903 fix: type from the PATH basename — canvas tabs pass tab.title
      // (no extension) as fileName, so extLabel(name) would degrade to 'FILE'.
      meta: { mimeType: mime, sizeBytes: src.sizeBytes, icon: fileIcon(name, mime), typeLabel: extLabel(src.path || name, mime) },
      display: { label: name, preview: previewOf(name, src.preview || src.text, 160), lineBadge: pageBadge(anchor) },
    };
  }

  if (rt === 'document') {
    const mime = src.mimeType || '';
    return {
      type: 'ref', refType: 'document', id: nextRefId('document'),
      source: { kind: src.kind || 'canvas', path: src.path || '', fileName: name, title: src.title || name, mimeType: mime },
      anchor,
      meta: { mimeType: mime, sizeBytes: src.sizeBytes, icon: fileIcon(name, mime), typeLabel: extLabel(src.path || name, mime) },
      display: { label: name, preview: previewOf(name, src.preview || anchor.text, 160), pageBadge: pageBadge(anchor), lineBadge: pageBadge(anchor) },
    };
  }

  if (rt === 'html-element') {
    return {
      type: 'ref', refType: 'html-element', id: nextRefId('html-element'),
      source: { kind: src.kind || 'web', url: src.url || '', title: src.title || '', path: src.path || '' },
      anchor,
      meta: { icon: 'code', typeLabel: t('ref.typeElement') },
      display: { label: src.title || '', preview: previewOf(name, anchor.text || src.preview, 160), pageBadge: pageBadge(anchor) },
    };
  }

  // A2A 票据 290 (addendum §3.2): friend message forward - content-bearing ref
  // (the message body rides the payload as content.fullText, unlike the
  // pointer refs above). Stable id pinned to the source message.
  if (rt === 'friend-message') {
    const content = input.content || {};
    const fullText = String(content.fullText || '').slice(0, 4000);
    const preview = previewOf('', content.preview || fullText, 160);
    const friendName = src.friendName || src.friendNeblinkId || '';
    const date = src.date || '';
    return {
      type: 'ref', refType: 'friend-message',
      id: src.messageId ? `ref:fm:${src.messageId}` : nextRefId('fm'),
      source: {
        kind: src.kind || 'friend-message',
        conversationId: src.conversationId || '',
        messageId: src.messageId || '',
        friendName,
        friendNeblinkId: src.friendNeblinkId || '',
        direction: src.direction === 'out' ? 'out' : 'in',
      },
      anchor,
      content: { preview, fullText },
      meta: { icon: 'message-circle', typeLabel: t('ref.typeFriendMessage'), date },
      display: { label: t('ref.fromFriend', { name: friendName }), preview, pageBadge: date },
    };
  }

  // Chat references carry a full client snapshot; row ids are UI-only and may
  // change after remount. The gateway creates a durable handle for long bodies.
  if (rt === 'chat-message') {
    const content = input.content || {};
    const fullText = String(content.fullText || '');
    const preview = String(content.excerpt || previewOf('', fullText, 60));
    // 附件批（2026-10-31）：被引消息的附件缩略清单随 content 走（live 腿引用块
    // 渲染缩略带；后端落盘白名单化 type/name/path —— preview 只活在前端）。
    const attachments = (Array.isArray(content.attachments) ? content.attachments : []).slice(0, 8).map((a) => ({
      type: a.type === 'image' || a.type === 'video' ? a.type : 'file',
      name: String(a.name || '').slice(0, 200),
      path: String(a.path || '').slice(0, 600),
      preview: (typeof a.preview === 'string' && a.preview.startsWith('data:')) ? a.preview : undefined,
    }));
    // 叠卡单张引用批（2026-11-02 作者令「让叠卡的图片可以分开引用」）：
    // content.itemIndex/itemTotal = 「这张在第几位、组里共几张」。引用**目标**
    // 仍是 messageId（深链寻址不变）；index 只进引用牌身份与标注 ⇒ 同一叠卡里
    // 三张图的引用牌互不相同（id 带 `:i<n>`，摘要尾部带「第 N/total 张」）。
    const itemIndex = Number.isInteger(content.itemIndex) && content.itemIndex >= 0 ? content.itemIndex : null;
    const itemTotal = itemIndex !== null && Number.isInteger(content.itemTotal) && content.itemTotal > itemIndex
      ? content.itemTotal : null;
    const refContent = { excerpt: preview, fullText, fullTextComplete: content.fullTextComplete === true, attachments };
    if (itemIndex !== null) {
      refContent.itemIndex = itemIndex;
      if (itemTotal !== null) refContent.itemTotal = itemTotal;
    }
    return {
      type: 'ref', refType: 'chat-message',
      id: src.messageId
        ? `ref:chatmsg:${src.messageId}${itemIndex !== null ? `:i${itemIndex}` : ''}`
        : nextRefId('chatmsg'),
      source: {
        kind: src.kind || 'chat-message',
        sessionId: src.sessionId || '',
        messageId: src.messageId || '',
        senderRole: src.senderRole || 'user',
        senderName: src.senderName || '',
      },
      anchor,
      content: refContent,
      meta: { icon: 'message-circle', typeLabel: t('messages.quote'), ts: (input.meta && input.meta.ts) || 0 },
      display: { label: src.senderName ? `${src.senderName}：${preview}` : preview, preview },
    };
  }

  return null;
}
