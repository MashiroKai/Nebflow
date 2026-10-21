// viewers/video.js — Video viewer: HTML5 <video controls> fed via /api/nf-file.
//
// canvas-video batch (2026-10-06, author screenshot order 13:24): a video opened
// in Canvas used to fall to the `code` fallback (fileViewers.js renderFile:
// registry.get('video') used to miss) → an EMPTY Monaco editor for binary bytes.
// This viewer claims the `video` itemType the server already emits
// (FileTypeRegistry: mp4/webm/ogv/ogg/mov → Entry("video", binary=true)).
//
// Zero-copy face: the player is the BROWSER-NATIVE <video controls> strip — no
// custom chrome, no locale keys, no visible copy of our own (the only text is
// the shared viewer idiom: the `image-info` name+size line and the `canvas-error`
// failure panel, both verbatim siblings of viewers/image.js).
//
// Range: seeking (progress-bar drag) depends on the byte leg answering HTTP
// Range with 206 Partial Content — /api/nf-file rides http4s StaticFile, which
// negotiates Range when handed the request (NfFileRoutes). A server without
// Range would still play (Chromium falls back to whole-file fetch), only
// seeking degrades.
//
// Extensions mirror FileTypeRegistry's video family VERBATIM (the registry
// red-line: this table is read by itemTypeForFileName — never copy a second
// table, claim exactly what the server maps to `video`). `.m4v` is deliberately
// ABSENT: PopTool.VideoExtensions declares it, but neither nf-file whitelist
// carries it (FileTypeRegistry.scala comment, canvas-media batch), so its bytes
// can never be served — claiming it would promise an unplayable render.

import { escapeHtml, formatSize } from './shared.js';
import { reMint } from '../nfTicket.js';
import { nfViewerUrl } from '../nfFileCache.js';

/** Video viewer — <video controls> served via /api/nf-file (native controls only). */
async function viewVideo(pane, { absPath, fileName, size, objectUrl }) {
  // Same two legs as viewers/image.js:
  //   `objectUrl` = bytes already in the page (attachmentPreview blob leg) —
  //   no path needed, no ticket mint;
  //   `absPath`   = the Pop / file-tree / device-saved path — the viewer mints
  //   a per-open ticket URL itself (nfTicket.ticketUrl, the single URL builder).
  if (!absPath && !objectUrl) {
    pane.innerHTML = '<div class="canvas-error">Cannot play video: no absolute path available.</div>';
    return;
  }

  pane.classList.remove('scrollable'); // the video scales to fit; no pane scroll region
  pane.innerHTML = '';

  const wrap = document.createElement('div');
  wrap.className = 'canvas-video-viewer';

  const info = document.createElement('div');
  info.className = 'image-info';
  info.textContent = `${fileName || ''} — ${formatSize(size || 0)}`;
  wrap.appendChild(info);

  const video = document.createElement('video');
  video.className = 'canvas-video';
  // Native controls ONLY (play/seek/volume/time) — zero custom player chrome.
  video.controls = true;
  video.preload = 'metadata';
  video.playsInline = true;

  // Load failure → exactly ONE ticket re-mint + retry, then the terminal error
  // panel (viewers/image.js T3/T4 discipline: a second failure is deterministic).
  let retried = false;
  video.onerror = () => {
    if (!retried && absPath) {
      retried = true;
      reMint(absPath).then((next) => {
        if (pane.isConnected) video.src = next;
      });
      return;
    }
    if (!pane.isConnected) return; // tab closed while the error settled
    pane.innerHTML = `<div class="canvas-error">Cannot play video: the file may be corrupted or the browser lacks a codec for it.<br>Path: ${escapeHtml(absPath || fileName || '')}</div>`;
  };

  wrap.appendChild(video);
  pane.appendChild(wrap);

  // M2 账本接缝（imgdoor #542 推广批，**带尺寸闸**）：≤64MiB 的视频走账本——命中 =
  // blob URL 直开（第二次打开零取数，seek 在 blob 内本地解析）；超限视频**保持票据
  // URL 流式**（Range seek 是既有语义，大件整收等全量是 UX 回归；64MiB 阈与
  // nfFileCache 桶预算留距，超预算本也不可缓存——申报于实施报告）。
  video.src = await nfViewerUrl({ absPath, objectUrl, pane, size, maxBytes: 64 * 1024 * 1024 });
}

export default {
  name: 'video',
  label: 'Video',
  extensions: ['.mp4', '.webm', '.ogv', '.ogg', '.mov'],
  binary: true,
  priority: 0,
  render: viewVideo,
};
