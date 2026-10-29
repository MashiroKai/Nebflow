// takeoverModal.js — 控制台单实例（接管独占）客户端腿（2026-10-27 批；
// 四修批 2026-10-29 作者令）：①被顶出态断内容渲染（ws.js 入口闸 + 服务端
// gateFrame 双保险）②毛玻璃模糊背景（overlay .takeover-blur）③极简卡
// （一行标题+一行说明+操作 chip，errorfrozen-dialog-minimal 同口径）④两按钮
// 修真——【重新接管】= 发帧 + 回执 ok 后 reload 全量重同步重新进入（带 loading
// 态/超时/失败提示）；【退出】= 直达官网个人主页（brand.js getProfileUrl）。
//
// 设计稿：reports/20261027_231456_console-single-instance/DESIGN.md（§6 前端落点；
// §7.3 数据透明口径已被 2026-10-29 作者令四修修订——被顶出态断内容渲染）。
//
// 模块图纪律：本文件是叶子（只 import ws.js/state.js/i18n.js/modal.js/brand.js，
// 无回边）——ws.js 不能反向 import 本模块（成环，flowStepInterceptor 同款教训），
// 故接管帧的路由权在 ws.js GLOBAL_MSG_TYPES 注册（takeover/takeoverResult 两型），
// 消费点在本模块。modal.js 的全局关闭钩子（Escape/遮罩/hideModals）按 DOM 在场
// 判别绕行本模态（强制二选一是互斥权的裁决点，不可被全局钩子误收）。
//
// 旗标（state.js 槽，跨模块读写）：
//   state.consoleSuperseded — 本页已让出控制权；ws.js onmessage 入口闸读点
//                             （被顶出态一律不渲染新内容帧，只放行接管协议帧）。

import state from './state.js';
import { sendWs, onMessage } from './ws.js';
import { t } from './i18n.js';
import { showToast } from './modal.js';
import { getProfileUrl } from './brand.js';

/** 回执缺席窗：超时 ⇒ 恢复可点 + 失败提示（连接抖动/判死/挤占竞态面）。 */
const RETAKE_TIMEOUT_MS = 4000;
let retakeTimer = null;

function boxEl() {
  return document.getElementById('takeover-box');
}

function overlay() {
  return state.dom?.modalOverlay || null;
}

/** 毛玻璃背景：弹窗出现即整页模糊（--cv-* 玻璃族同源材质，modal.css 消费）。 */
function setBlur(on) {
  const ov = overlay();
  if (ov) ov.classList.toggle('takeover-blur', on);
}

function setRetakeLoading(loading) {
  const btn = /** @type {HTMLButtonElement|null} */ (document.getElementById('takeover-retake'));
  if (!btn) return;
  btn.disabled = loading;
  btn.textContent = loading ? t('takeover.retaking') : t('takeover.retake');
}

function clearRetakeTimer() {
  if (retakeTimer) { clearTimeout(retakeTimer); retakeTimer = null; }
}

/** 被踢面（superseded）：强制二选一。幂等——重连补推/多连接同帧不叠弹。 */
function show() {
  const box = boxEl();
  if (!box) return; // 极旧入口无本盒：静默降级为现行为（无模态、连接照常）
  state.consoleSuperseded = true;
  clearRetakeTimer();
  setRetakeLoading(false);
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
  box.style.display = 'block';
  if (overlay()) overlay().classList.add('on');
  setBlur(true);
}

function hide() {
  const box = boxEl();
  if (box) box.style.display = 'none';
  setBlur(false);
  // 收起 = ownership 已回。不调 modal.js hideModals（那会连带清其他盒的回调态——
  // 本模态受强制二选一保护，只摘自己的 overlay 面）。
  if (overlay()) overlay().classList.remove('on');
}

/**
 * 【重新接管】：发 retake 帧反向踢（单飞 + loading 态）。回执 ok ⇒ owner 已回
 * （服务端已把另一端顶进同款弹窗态）⇒ reload 全量重同步重新进入控制台——内容闸
 * 窗期的缺口只经 reload 愈合。回执 !ok/缺席 ⇒ 恢复可点 + 失败提示（挤占竞态：
 * 互斥权最后写入者胜，败者可再点）。
 */
export function takeoverRetake() {
  const btn = /** @type {HTMLButtonElement|null} */ (document.getElementById('takeover-retake'));
  if (!btn || btn.disabled) return; // 单飞：loading 期间再点忽略
  setRetakeLoading(true);
  sendWs({ type: 'takeoverRetake' });
  clearRetakeTimer();
  retakeTimer = setTimeout(() => {
    retakeTimer = null;
    setRetakeLoading(false);
    showToast(t('takeover.retakeFailed'), 'error');
  }, RETAKE_TIMEOUT_MS);
}

/**
 * 【退出】（2026-10-29 作者令四修修订，原「退出面/重入门」作废）：直达官网个人
 * 主页（brand 契约 profileUrl，activityBar.js 同源读点）。本页随导航卸载；
 * 被顶出连接的内容帧由服务端 gateFrame 断喂、ws.js 入口闸兜底不渲染。
 */
export function takeoverExit() {
  window.location.href = getProfileUrl();
}

// ── 帧消费（GLOBAL 路由权在 ws.js 注册）────────────────────────────────────

onMessage('takeover', () => { show(); });

onMessage('takeoverResult', (msg) => {
  clearRetakeTimer();
  if (msg && msg.ok) {
    // 所有权已回：reload 重新进入（抢位竞态下迟到回执同样安全——owner 确已回本会话）。
    state.consoleSuperseded = false;
    window.location.reload();
  } else {
    setRetakeLoading(false);
    showToast(t('takeover.retakeFailed'), 'error');
  }
});

// ── 按钮接线（ESM deferred：import 时 DOM 已就绪，modal.js initModals 同拍语义）──

const exitBtn = document.getElementById('takeover-exit');
if (exitBtn) exitBtn.onclick = () => takeoverExit();
const retakeBtn = document.getElementById('takeover-retake');
if (retakeBtn) retakeBtn.onclick = () => takeoverRetake();
