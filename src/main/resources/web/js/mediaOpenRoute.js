// mediaOpenRoute.js — 媒体点击行为统一路由（P16 批 2026-11-11 作者令逐字：
// 「电脑端的媒体统一都是canvas打开，手机端的媒体统一都是直接打开，但是要注意好
// 交互和居中」）。
//
// 定位：媒体点击入口（Pop 媒体卡 / markdown 气泡图 / 帧消息图片 / 工具卡图片引用件
// / chatSearch 媒体结果 / 三窗媒体附件卡）的**分流单点**。改前各入口行为分裂——
// 有的开 Canvas（带 path 的 Pop 媒体、三窗附件卡）、有的开灯箱全屏放大（pathless
// Pop 媒体、markdown 图、帧消息）——同一类东西两种行为，即作者报的「行为会不统一」。
// 本模块之后：
//   · 手机层（body.mobile-layer）——**一切**媒体点击 = `openMediaDirect`：铸 URL
//     交给系统预览（新标签页原生显示，捏合缩放/关闭都归系统），应用内不再弹面板
//     也不再叠灯箱；
//   · Desktop media with a path uses the injected openPopArtifact /
//     workspace-open-item entry point. Pathless media keeps the lightbox:
//     Canvas requires a file path (issue 491). Inline bytes may be thumbnails;
//     once a saved path is available, the same item can use Canvas.
//
// URL 链（openMediaDirect 的取数纪律——禁第三条取字节路，全部是应用既有出口）：
//   ① resolver（uploads 路由，用户附件面）——社交通道/用户媒体的指针登记路；
//   ② ticketUrl（/api/nf-file 铸票）——工作区文件/原始本机路径；
//   ③ data:/blob: → fetch → objectURL——内联字节；http(s) 原样（远程由系统处理）。
//   全链尽空 ⇒ 返回 false，调用方落各自的灯箱兜底（本模块不知道灯箱，禁回指）。
//
// 模块纪律：叶子——只 import events/nfTicket/mobile/popSheet（三者都不回指本模块），
// Canvas 单点经参数注入（调用方传 openPopArtifact 或其懒加载包装），灯箱兜底经
// 参数注入。blob URL 生命周期：手机直开的新标签页活在 opener 存续期内，本模块
// 只保留「上一枚」待换新时回收（立即 revoke 会杀掉刚打开的系统预览页）。

import { ticketUrl } from './nfTicket.js';
import { isMobileLayer } from './mobile/popSheet.js';

/** 上一枚本模块发出的 objectURL（手机直开腿）；下次直开时回收上一枚。 */
let lastDirectObjectUrl = '';

function adoptObjectUrl(url) {
  if (lastDirectObjectUrl && lastDirectObjectUrl !== url) {
    try { URL.revokeObjectURL(lastDirectObjectUrl); } catch (e) { /* already dead */ }
  }
  lastDirectObjectUrl = url;
  return url;
}

/** 本模块发出的 objectURL 在页卸载时兜底回收（正常路径 = 下一枚换新时回收）。 */
if (typeof window !== 'undefined') {
  window.addEventListener('pagehide', () => {
    if (lastDirectObjectUrl) { try { URL.revokeObjectURL(lastDirectObjectUrl); } catch (e) { /* noop */ } }
    lastDirectObjectUrl = '';
  });
}

/**
 * 直开 URL 链：resolver > ticketUrl > 内联字节 objectURL > 远程原样。
 * @param {{path?: string, src?: string, blob?: Blob, resolver?: (path: string) => string}} item
 * @returns {Promise<string>} '' = 无可开 URL（调用方走灯箱兜底）
 */
export async function mediaDirectUrl(item) {
  if (!item) return '';
  if (typeof item.resolver === 'function' && item.path) {
    try {
      const u = item.resolver(item.path);
      if (u) return u;
    } catch (e) { /* resolver 拒答 ⇒ 落下一腿（mediaface-resolver-fallback 同语义） */ }
  }
  if (item.path) {
    try { return await ticketUrl(item.path); } catch (e) { /* 铸票败 ⇒ 落下一腿 */ }
  }
  // 发送侧本机句柄 / 好友面整件字节（调用方取好的 Blob）——objectURL 进本模块
  // 生命周期登记（上一枚换新时回收）。
  if (item.blob && typeof item.blob.size === 'number' && item.blob.size > 0) {
    try { return adoptObjectUrl(URL.createObjectURL(item.blob)); } catch (e) { /* 落下一腿 */ }
  }
  const s = item.src;
  if (typeof s === 'string' && s) {
    if (s.startsWith('data:') || s.startsWith('blob:')) {
      try { return adoptObjectUrl(URL.createObjectURL(await (await fetch(s)).blob())); } catch (e) { /* 落下一腿 */ }
    } else if (/^https?:/i.test(s)) {
      return s;
    } else if (s.startsWith('/')) {
      // 原始本机路径（工具卡图片引用件的 md src 形态）——同一铸票路。
      try { return await ticketUrl(s); } catch (e) { /* 落下一腿 */ }
    }
  }
  return '';
}

/**
 * 手机端直开（系统预览）。**预约式**（activityBar.js / neblink.js 同款先例）：
 * 先在用户手势调用栈内同步开一枚空白页占位（铸票是异步腿——await 之后才
 * window.open 会被弹窗拦截器吃掉），URL 到手后导航该页；全链尽空 ⇒ 关掉占位页
 * 返回 false（调用方落灯箱兜底）。导航目标永不为 data:（内联字节先转 objectURL，
 * 顶帧 data: 导航被现代浏览器禁止；blob:/同源 http(s) 均可导航）。
 * @param {{path?: string, src?: string, blob?: Blob, name?: string, resolver?: (path: string) => string}} item
 * @returns {Promise<boolean>}
 */
export async function openMediaDirect(item) {
  let tab = null;
  try { tab = window.open('about:blank', '_blank'); } catch (e) { /* popup blocked */ }
  if (!tab) return false;
  const url = await mediaDirectUrl(item);
  if (!url) {
    try { tab.close(); } catch (e) { /* 已被用户关掉 */ }
    return false;
  }
  try { tab.location.href = url; } catch (e) { /* 占位页已被用户关闭：放弃 */ }
  return true;
}

/**
 * 媒体点击路由单点。
 *
 * @param {{kind?: string, name?: string, path?: string, src?: string,
 *          resolver?: (path: string) => string}|null} item
 *   调用方面上已有的媒体项形状（popArtifacts item / 帧消息 payload / 裸 {src,path}）。
 * @param {(path: string, name?: string) => void} openCanvas
 *   桌面 Canvas 单点注入（popArtifacts.openPopArtifact 或其懒包装——本模块禁静态
 *   回指 popArtifacts，popArtifacts 静态依赖本模块）。
 * @param {() => void} fallback
 *   桌面 pathless / 手机直开全链尽空的兜底（各自的灯箱面）。
 */
export function routeMediaClick(item, openCanvas, fallback) {
  if (isMobileLayer()) {
    void openMediaDirect(item).then((ok) => { if (!ok) fallback(); });
    return;
  }
  if (item && item.path) {
    openCanvas(item.path, item.name || String(item.path).split('/').pop());
    return;
  }
  fallback();
}
