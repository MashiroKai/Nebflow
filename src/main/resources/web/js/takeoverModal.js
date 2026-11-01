// takeoverModal.js — 控制台单实例（接管独占）客户端腿（2026-10-27 批；
// 四修批 2026-10-29 作者令）：①被顶出态断内容渲染（ws.js 入口闸 + 服务端
// gateFrame 双保险）②毛玻璃模糊背景（overlay .takeover-blur）③极简卡
// （一行标题+一行说明+操作 chip，errorfrozen-dialog-minimal 同口径）④两按钮
// 修真——【重新接管】= 发帧 + 回执 ok 后 reload 全量重同步重新进入（带 loading
// 态/超时/失败提示）；【退出】= 直达官网个人主页（brand.js getProfileUrl）。
//
// 接管/入场同族批（2026-11-01 作者令①②③）：
//   ① 接管必达：【重新接管】改走 HTTP 腿 POST /api/takeover/retake（同源
//      cookie 会话轨）——回执随 HTTP 响应同步必达，不再依赖本页 ws 存活（帧腿
//      的结构性缺口：sendWs 对死连接静默入队、回执可被出站队列溢出拆连吞掉、
//      4s 定时超时与服务器实际状态脱节——「重新接管不一定成功」的根因面）。
//      服务端所有权翻面 + 反向踢与帧腿同核（ConsoleTakeover.retakeBySession）。
//      失败面不再静默通用——错误码分类落卡面错误行 + toast（网络/超时/会话
//      过期/总闸关闭/拒绝各自有明确文案）。
//   ② 卡面同族重做：按钮语换代 g2 药丸族（次级白玻璃药丸 / 主动作墨玻璃药丸），
//      与入场加载卡同材质/版式/按钮语（css/modal.css + index.html DOM）。
//   ③ 入场加载卡在 index.html 门脚本（window.__nfEntry 同族面）。
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
import { onMessage } from './ws.js';
import { t } from './i18n.js';
import { showToast } from './modal.js';
import { getProfileUrl } from './brand.js';

/** HTTP 腿中止预算：响应本身即裁决（无队列面），10s 只防主机挂死形状。 */
const RETAKE_TIMEOUT_MS = 10000;
/** 接管 HTTP 腿单点（接管必达批）：ConsoleTakeoverRoutes 契约。 */
const RETAKE_ENDPOINT = '/api/takeover/retake';

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

/**
 * 卡面错误行（接管必达批①「失败要给明确报错不许静默」）：S9 同族样式
 * （modal.css #takeover-error，中性字 + 红左缘）——失败原因常驻卡面，
 * toast 只是补充扩散面。
 */
function setTakeoverError(msg) {
  const el = document.getElementById('takeover-error');
  if (!el) return;
  if (msg) {
    el.textContent = msg;
    el.hidden = false;
  } else {
    el.textContent = '';
    el.hidden = true;
  }
}

/** 被踢面（superseded）：强制二选一。幂等——重连补推/多连接同帧不叠弹。 */
function show() {
  const box = boxEl();
  if (!box) return; // 极旧入口无本盒：静默降级为现行为（无模态、连接照常）
  state.consoleSuperseded = true;
  setRetakeLoading(false);
  setTakeoverError(null);
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
  // 弹窗退场批：接管态隐藏 #modal-overlay 的注入返回钮（强制二选一，无返回
  // 语义；handler 侧 takeoverBlocking 守卫同款判据的双保险）。
  if (overlay()) overlay().classList.add('takeover-live');
  setBlur(true);
}

function hide() {
  const box = boxEl();
  if (box) box.style.display = 'none';
  if (overlay()) overlay().classList.remove('takeover-live');
  setBlur(false);
  // 收起 = ownership 已回。不调 modal.js hideModals（那会连带清其他盒的回调态——
  // 本模态受强制二选一保护，只摘自己的 overlay 面）。
  if (overlay()) overlay().classList.remove('on');
}

/** 失败收口单点：恢复可点 + 卡面错误行 + toast（明确报错，不许静默）。 */
function retakeFailed(msg) {
  setRetakeLoading(false);
  setTakeoverError(msg);
  showToast(msg, 'error');
}

/** HTTP 腿错误分类（ConsoleTakeoverRoutes 契约 + fetch 事故面）→ 明确文案。 */
function retakeErrorText(resp, json) {
  if (resp && (resp.status === 401 || resp.status === 403)) return t('takeover.errAuth');
  const code = json && json.error;
  if (code === 'disabled') return t('takeover.errDisabled');
  if (code) return t('takeover.errRefused');
  return t('takeover.retakeFailed');
}

/**
 * 【重新接管】（接管必达批①，2026-11-01 作者令）：HTTP 腿单飞 + loading 态。
 * POST /api/takeover/retake（同源 cookie 会话轨）——回执随响应同步必达（不依赖
 * 本页 ws 存活）。ok ⇒ owner 已回（服务端已把另一端顶进同款弹窗态/其重连补推
 * 同款）⇒ reload 全量重同步重新进入控制台——内容闸窗期的缺口只经 reload 愈合。
 * 失败 ⇒ 分类文案落卡面错误行 + toast（网络/超时/会话过期/总闸关闭/拒绝各自
 * 有名），恢复可点可再试——不静默。
 */
export async function takeoverRetake() {
  const btn = /** @type {HTMLButtonElement|null} */ (document.getElementById('takeover-retake'));
  if (!btn || btn.disabled) return; // 单飞：loading 期间再点忽略
  setRetakeLoading(true);
  setTakeoverError(null);
  let resp = null;
  let json = null;
  try {
    resp = await fetch(RETAKE_ENDPOINT, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      signal: AbortSignal.timeout(RETAKE_TIMEOUT_MS),
    });
    json = await resp.json().catch(() => null);
  } catch (e) {
    // 网络面两类事故各自有名：中止（主机挂死/无响应）与连接失败。
    const timedOut = e && (e.name === 'AbortError' || e.name === 'TimeoutError');
    retakeFailed(t(timedOut ? 'takeover.errTimeout' : 'takeover.errNetwork'));
    return;
  }
  if (resp.ok && json && json.ok) {
    // 所有权已回：reload 重新进入（抢位竞态下回执同样安全——owner 确已回本会话）。
    state.consoleSuperseded = false;
    window.location.reload();
    return;
  }
  retakeFailed(retakeErrorText(resp, json));
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
// takeoverResult 保留消费（兼容面）：接管必达批后本前端不再发 takeoverRetake 帧
// （HTTP 腿单点），但旧缓存页/服务端既有拦截面仍可能产出本帧——ok ⇒ reload 语义
// 不变，!ok ⇒ 与 HTTP 腿同一失败收口（不静默）。

onMessage('takeover', () => { show(); });

onMessage('takeoverResult', (msg) => {
  if (msg && msg.ok) {
    // 所有权已回：reload 重新进入（抢位竞态下迟到回执同样安全——owner 确已回本会话）。
    state.consoleSuperseded = false;
    window.location.reload();
  } else {
    retakeFailed(t('takeover.retakeFailed'));
  }
});

// ── 按钮接线（ESM deferred：import 时 DOM 已就绪，modal.js initModals 同拍语义）──

const exitBtn = document.getElementById('takeover-exit');
if (exitBtn) exitBtn.onclick = () => takeoverExit();
const retakeBtn = document.getElementById('takeover-retake');
if (retakeBtn) retakeBtn.onclick = () => takeoverRetake();
