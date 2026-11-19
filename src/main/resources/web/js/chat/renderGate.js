// chat/renderGate.js — 渲染遮蔽门（perf 台账 #922，2026-10-26）。
//
// 为什么必须有：上滑补页（historyPage prepend）一次性构建/挂载数十行重内容
// （大工具正文 esc+innerHTML、markdown 升级批、整页重排），主线程被占住
// 数百 ms~秒级；窗口期 WS 心跳 pong 即使已到浏览器也处理不了（或压在大帧
// 后面）⇒ 宽限定时器先到 ⇒ miss 计入 ⇒ 连续 3 miss 自逐出 ⇒ 「向上滑着
// 就重连」。这不是 socket 死了，是渲染把心跳饿死了。
//
// 门语义：**引用计数**的「渲染在途」标记。补页腿（main.js prepend 挂载）与
// 大帧派发腿（ws.js onmessage 大帧 parse+dispatch）各持一段 begin/end；
// 计数 > 0 = busy。ws.js 的心跳宽限回调读到 busy ⇒ 本窗 miss 走既有
// **传输遮蔽**同款旁路（shelteredMisses++，硬上限 HB_TRANSFER_SHELTER_MAX
// 不变，pong/新连接照常清零）——不放松 HB_MAX_MISSES=3、不放松 sessionGate
// 重座阈值（稳定性批 2026-10-26 语义原样）；真死连接在遮蔽上限内照常判死。
//
// 零依赖（不 import 任何模块）：ws.js / persistence.js / main.js 三方都能
// 安全引用，check-circular 静态环零增量。window.__nfRenderGate = 诊断/spec
// 读数缝（_keepaliveTestHooks 先例）：spec 用它注入受控占窗验证遮蔽腿。

let depth = 0;

/** 进入一段渲染遮蔽窗（幂等嵌套：计数 +1）。 */
export function renderGateBegin() {
  depth++;
}

/** 结束一段渲染遮蔽窗（计数 -1，兜底不为负——异常路径泄漏最多多遮蔽至上限）。 */
export function renderGateEnd() {
  if (depth > 0) depth--;
}

/** 渲染遮蔽窗是否在途（心跳宽限回调的判据）。 */
export function renderGateBusy() {
  return depth > 0;
}

/** 诊断读数：当前嵌套深度。 */
export function renderGateDepth() {
  return depth;
}

if (typeof window !== 'undefined') {
  window.__nfRenderGate = {
    begin: renderGateBegin,
    end: renderGateEnd,
    busy: renderGateBusy,
    depth: renderGateDepth,
  };
}
