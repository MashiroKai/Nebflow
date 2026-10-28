// takeoverModal.js — 控制台单实例（接管独占）客户端腿（2026-10-27 批）。
//
// 设计稿：reports/20261027_231456_console-single-instance/DESIGN.md（§6 前端落点）。
// 服务端语义（ConsoleTakeover）：新实例认证成功 ⇒ 旧实例收 `takeover` 帧（被踢面：
// 强制二选一【退出】【重新接管】，重新接管即反向踢，互斥权最后写入者胜）；【退出】
// 翻退出面（重入门）。数据可见性不受影响（#929 多设备透明照旧）——本模态只独占
// 「主动控制权」。
//
// 模块图纪律：本文件是叶子（只 import ws.js/state.js/i18n.js，无回边）——ws.js 不能
// 反向 import 本模块（成环，flowStepInterceptor 同款教训），故接管帧的路由权在
// ws.js GLOBAL_MSG_TYPES 注册（takeover/takeoverResult 两型），消费点在本模块。
// modal.js 的全局关闭钩子（Escape/遮罩/hideModals）按 DOM 在场判别绕行本模态
// （强制二选一是互斥权的裁决点，不可被全局钩子误收）。
//
// 旗标（state.js 槽，跨模块读写）：
//   state.consoleSuperseded — 本页已让出控制权；canvas 标签页账本联动读点
//                             （设计稿 §8：唯一活跃实例 = 账本唯一写入方）。
//   state.takeoverExited    — 用户已退出：ws.js onclose 见旗标跳过自动重连与
//                             remint/粘贴票面；updateConnBanner 不再谎报「正在重连」。

import state from './state.js';
import { sendWs, onMessage } from './ws.js';
import { t } from './i18n.js';

let retakeInFlight = false;

function boxEl() {
  return document.getElementById('takeover-box');
}

/** 被踢面（superseded）：强制二选一。幂等——重连补推/多连接同帧不叠弹。 */
function show() {
  const box = boxEl();
  if (!box) return; // 极旧入口无本盒：静默降级为现行为（无模态、连接照常）
  state.consoleSuperseded = true;
  retakeInFlight = false;
  // 既有模态族同拍：一次只亮一个盒（showConfirm/showConflict 同款互斥）。
  for (const id of ['modal-box', 'delete-box', 'conflict-box']) {
    const el = document.getElementById(id);
    if (el) el.style.display = 'none';
  }
  document.getElementById('takeover-title').textContent = t('takeover.title');
  document.getElementById('takeover-msg').textContent = t('takeover.msg');
  const exitBtn = document.getElementById('takeover-exit');
  const retakeBtn = document.getElementById('takeover-retake');
  exitBtn.textContent = t('takeover.exit');
  retakeBtn.textContent = t('takeover.retake');
  exitBtn.hidden = false;
  retakeBtn.hidden = false;
  document.getElementById('takeover-reenter').hidden = true;
  box.style.display = 'block';
  if (state.dom?.modalOverlay) state.dom.modalOverlay.classList.add('on');
}

/** 退出面（重入门）：单按钮，reload 后同会话重连 → 服务端落 superseded 面 → 一键 retake。 */
function showExitFace() {
  const box = boxEl();
  if (!box) return;
  state.consoleSuperseded = false;
  document.getElementById('takeover-title').textContent = t('takeover.exitTitle');
  document.getElementById('takeover-msg').textContent = t('takeover.exitMsg');
  document.getElementById('takeover-exit').hidden = true;
  document.getElementById('takeover-retake').hidden = true;
  const reenterBtn = document.getElementById('takeover-reenter');
  reenterBtn.textContent = t('takeover.reenter');
  reenterBtn.hidden = false;
  box.style.display = 'block';
  if (state.dom?.modalOverlay) state.dom.modalOverlay.classList.add('on');
}

function hide() {
  const box = boxEl();
  if (box) box.style.display = 'none';
  // 收起 = ownership 已回。不调 modal.js hideModals（那会连带清其他盒的回调态——
  // 本模态受强制二选一保护，只摘自己的 overlay 面）。
  if (state.dom?.modalOverlay) state.dom.modalOverlay.classList.remove('on');
}

/** 【重新接管】：发 retake 帧反向踢（单飞；回执缺席 3s 后允许再点——连接抖动兜底）。 */
export function takeoverRetake() {
  if (retakeInFlight) return;
  retakeInFlight = true;
  sendWs({ type: 'takeoverRetake' });
  setTimeout(() => { retakeInFlight = false; }, 3000);
}

/**
 * 【退出】：先置 exited 旗标再主动 close——ws.js onclose 见旗标不重连、不落
 * remint/粘贴票面（被踢面退出到可一键重入门）。
 */
export function takeoverExit() {
  state.takeoverExited = true;
  showExitFace();
  try {
    if (state.ws) state.ws.close();
  } catch (_) { /* 已断开 */ }
}

// ── 帧消费（GLOBAL 路由权在 ws.js 注册）────────────────────────────────────

onMessage('takeover', () => { show(); });

onMessage('takeoverResult', (msg) => {
  retakeInFlight = false;
  if (msg && msg.ok) {
    state.consoleSuperseded = false;
    hide();
  }
  // !ok：所有权未回（竞态/总闸关闭）——被踢面原样保留，可再点或退出。
});

// ── 按钮接线（ESM deferred：import 时 DOM 已就绪，modal.js initModals 同拍语义）──

const exitBtn = document.getElementById('takeover-exit');
if (exitBtn) exitBtn.onclick = () => takeoverExit();
const retakeBtn = document.getElementById('takeover-retake');
if (retakeBtn) retakeBtn.onclick = () => takeoverRetake();
const reenterBtn = document.getElementById('takeover-reenter');
if (reenterBtn) reenterBtn.onclick = () => { window.location.reload(); };
