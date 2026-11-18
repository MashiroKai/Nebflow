// downloadSave.js — 移动端「下载落点」单点（mobile-download 批 · 2026-11-18 作者令）
//
// 作者令（逐字）：「手机端的下载我不知道存哪去了 能不能直接存进相册（图片视频的话）
// 或者指定下载目录」。本模块是该诉求的**唯一**实现点：所有「把一件字节交给用户」
// 的动作都经 `saveFileBlob` 选路，禁各下载键自造第二套落点逻辑。
//
// 选路（判据 = 平台真实能力，不是猜测）：
//   · 手机形态（`body.mobile-layer`；判定单源 = `mobile/popSheet.isMobileLayer`）——
//       ① 图片/视频 ∧ `navigator.canShare({files})` ⇒ `navigator.share({files})`：
//          系统分享面板 →「存储到照片」= 入**相册**。这是纯网页在 iOS 上触达相册的
//          **唯一**正路；网页**不能**直接写相册（无 API），禁假装。
//       ② 其它文件 ∧ `navigator.canShare({files})` ⇒ 同一分享面板 →「存储到文件」
//          = 用户**可指定保存目录**（移动浏览器侧能做到的最好）。
//       ③ 能力探测失败（`canShare` 缺席 / 返回 false / 抛异常）⇒ **保留原有**
//          `<a download>` 行为，并在完成后给**落点提示**通知（可发现性兜底）。
//   · 桌面形态（非 mobile-layer）⇒ **零触碰**：仍走 `<a download>`，且不发任何通知
//     （桌面落点 = 浏览器下载栏/下载目录自明，且桌面是手机批的零触碰面）。
//
// 能力判据**只有一条** = `navigator.canShare({ files: [File] })`（Web Share Level 2）。
// 不支持 = 未知/旧环境 ⇒ 回落原行为（`downloadViaAnchor`），绝不新增假入口。
//
// File System Access API（`showSaveFilePicker`）**不进运行时路径**，理由登记在批报告：
//   手机形态（iOS Safari / Android Chrome）**均不实现** `showSaveFilePicker`
//   ⇒ 在该形态上是死分支；且接入会改动桌面/自动化形态的落盘行为（违反「桌面零触碰」
//   与「不支持则保留原行为」）。故「可指定位置」由 ② 的系统分享面板承担。
//
// 相册写入 / 系统分享面板本身是 **OS 行为**：本模块只能保证「在正确的时机、用正确的
// 文件（名/类型/字节）调用了分享接口」；「照片 App 里真出现了」需真机亲验。

import { t } from './i18n.js';
import { isMobileLayer } from './mobile/popSheet.js';
import { mediaKind } from './mediaOpenRoute.js';

/** 相册档判定（图片/视频）——扩展名/MIME 表复用 `mediaOpenRoute.mediaKind` 单点，
 *  禁在本模块再造第二套类型表（audio 不算相册档，走「存储到文件」）。 */
export function isAlbumKind(name, type) {
  const k = mediaKind({ name, mime: type });
  return k === 'image' || k === 'video';
}

/** 能力探测**单点**：Web Share Level 2 能否携带该件（`navigator.canShare({files})`）。
 *  旧引擎 `canShare` 缺席 / 拒答 / 抛异常 ⇒ 一律 `false`（fail-closed 到原行为）。 */
export function canShareFiles(file) {
  try {
    if (!file || typeof navigator === 'undefined') return false;
    if (typeof navigator.canShare !== 'function' || typeof navigator.share !== 'function') return false;
    return navigator.canShare({ files: [file] }) === true;
  } catch (e) { return false; }
}

/** **原有**下载行为（`<a download>` + blob URL → 浏览器/OS 下载管理器）。
 *  = 桌面形态逐字原行为 + 手机形态的能力降级保留腿。 */
export function downloadViaAnchor(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename || 'attachment';
  a.style.display = 'none';
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}

/** iOS/iPadOS 判定（仅用于「落点提示」措辞——不参与选路，选路只看能力）。 */
function isIosLike() {
  try {
    const ua = navigator.userAgent || '';
    return /iPhone|iPad|iPod/i.test(ua) || (/Macintosh/.test(ua) && 'ontouchend' in document);
  } catch (e) { return false; }
}

/**
 * 保存一件字节（**全站唯一落点单点**）。
 * @param {Blob} blob
 * @param {string} filename
 * @returns {Promise<{route: 'shared'|'downloaded'|'cancelled', filename: string, album: boolean}>}
 *   route —— 'shared' = 已交给系统分享面板；'downloaded' = 走原有下载；
 *            'cancelled' = 用户在分享面板取消（零提示、零回落，尊重用户意图）。
 */
export async function saveFileBlob(blob, filename) {
  const name = filename || 'attachment';
  if (!isMobileLayer()) {                       // 桌面形态：零触碰
    downloadViaAnchor(blob, name);
    return { route: 'downloaded', filename: name, album: false };
  }
  const type = (blob && blob.type) || '';
  const album = isAlbumKind(name, type);
  let file = null;
  try { file = new File([blob], name, { type }); } catch (e) { file = null; }
  if (file && canShareFiles(file)) {
    try {
      await navigator.share({ files: [file] });
      return { route: 'shared', filename: name, album };
    } catch (err) {
      if (err && err.name === 'AbortError') return { route: 'cancelled', filename: name, album };
      // NotAllowedError（用户手势窗口过期）/ 环境失败：落回保留腿（可见降级，不静默）。
      downloadViaAnchor(blob, name);
      return { route: 'downloaded', filename: name, album };
    }
  }
  downloadViaAnchor(blob, name);
  return { route: 'downloaded', filename: name, album };
}

/**
 * 落点提示（可发现性兜底 · 作者困惑的直接原因是「不知道存哪去了」）。
 * 复用既有通知栈 `window.__showToast`（通知风格统一批既有样式，**零新样式**）。
 * 只在手机形态发声；桌面形态静默（`saveFileBlob` 桌面腿 = 原行为，零通知）。
 * @param {{route: string, album?: boolean}} result `saveFileBlob` 的返回
 */
export function notifyLanding(result) {
  if (!result || !isMobileLayer()) return;
  let msg = '';
  if (result.route === 'shared') {
    msg = result.album ? t('download.sharedToAlbum') : t('download.sharedToFolder');
  } else if (result.route === 'downloaded') {
    msg = isIosLike() ? t('download.landedIosFiles') : t('download.landedBrowserDir');
  } else {
    return;   // cancelled：尊重用户取消，零提示
  }
  try { window.__showToast?.(msg, 'info'); } catch (e) { /* 通知面绝不反噬下载链 */ }
}
