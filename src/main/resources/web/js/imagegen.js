// imagegen.js — 腿①生图占位卡片（imagegen 双层批 2026-10-24，作者裁定：
// 「生图的时候有个占位动画……非阻塞」）。调试状态（experimental）——
// release bundle 经 __NEBFLOW_RELEASE__ esbuild define 物理剥除（friendsEnabled
// 同款 DCE 口径）。
//
// 生命周期：
//  1. toolEnd(generate_image) —— 工具调用**立即**返回 job 句柄（回合零阻塞），
//     本模块在工具卡下渲染占位卡片（克制的玻璃 shimmer + spinner + 「调试中」徽标）；
//  2. WS imagegenUpdate 帧（jobId 键控）—— 完成原位替换为真实图片（nf-file
//     票据链 ticketUrl），失败原位翻错误面。
import { ticketUrl } from './nfTicket.js';
import { escapeHtml } from './utils.js';

/** @type {Map<string, HTMLElement>} jobId → 占位卡片（跨 view 唯一键） */
const slots = new Map();

const DEBUG_BADGE = '<span class="imagegen-badge">调试中</span>';

/**
 * toolEnd 特判面：renderTool 在绘制标准工具卡头后对本工具追加占位卡片行。
 * @param {HTMLElement} cardRow 工具卡所在 .row.tool
 * @param {string} content 工具返回的 job 句柄 JSON
 * @param {string} prompt 工具入参 prompt（占位卡字幕）
 * @returns {boolean} 是否已接管（true = renderTool 应短路剩余通用渲染）
 */
export function renderImageGenPlaceholder(cardRow, content, prompt) {
  let jobId = null;
  try {
    const parsed = JSON.parse(content);
    if (parsed && typeof parsed.jobId === 'string' && parsed.status === 'running') jobId = parsed.jobId;
  } catch { /* 非句柄内容按通用工具卡渲染 */ }
  if (!jobId) return false;

  const row = document.createElement('div');
  row.className = 'row imagegen-row';
  row.innerHTML =
    '<div class="imagegen-ph" data-role="ph">' +
      '<div class="imagegen-ph-shimmer"></div>' +
      '<div class="imagegen-ph-center">' +
        '<span class="imagegen-spinner"></span>' +
        '<span class="imagegen-ph-text">生图进行中</span>' +
        DEBUG_BADGE +
      '</div>' +
      '<div class="imagegen-ph-caption">' + escapeHtml((prompt || '').slice(0, 80)) + '</div>' +
    '</div>';
  cardRow.after(row);
  slots.set(jobId, row.querySelector('[data-role="ph"]'));
  return true;
}

/** WS imagegenUpdate 帧：按 jobId 原位替换/翻面。 */
export async function resolveImageGenUpdate(msg) {
  const ph = slots.get(msg.jobId);
  if (!ph) return; // 卡片已不在 DOM（切走/清屏）——帧幂等忽略
  slots.delete(msg.jobId);

  if (msg.status === 'completed' && typeof msg.path === 'string' && msg.path) {
    ph.classList.add('imagegen-done');
    ph.innerHTML = '<div class="imagegen-loading">' + DEBUG_BADGE + ' 图片加载中…</div>';
    try {
      const src = await ticketUrl(msg.path);
      ph.innerHTML =
        '<img class="imagegen-img" src="' + escapeHtml(src) + '" alt="generated image">' +
        '<div class="imagegen-done-badge">' + DEBUG_BADGE + '</div>';
    } catch {
      ph.innerHTML = '<div class="imagegen-error">图片已生成但加载失败：' + escapeHtml(msg.path) + '</div>';
    }
  } else {
    ph.classList.add('imagegen-failed');
    ph.innerHTML = '<div class="imagegen-error">生图失败' +
      (msg.error ? '：' + escapeHtml(String(msg.error).slice(0, 120)) : '') + DEBUG_BADGE + '</div>';
  }
}
