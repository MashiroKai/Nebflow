// profileSignals.js — 呈现校准信号·前端采集面（呈现档入口改造+围栏语义批，
// 作者令 2026-10-30 20:13 ②「日常使用自适应（本期只立数据面）」）。
//
// 只采一类行为信号：```human 框外折叠抽屉（.nf-human-details）被展开 ⇒
// 发 recordCalibrationSignal {kind:'drawer-expanded'}。「给我看细节」类
// 兴趣的行为证据，落引擎校准文件（~/.stand/profile-calibration.json），
// auto 调整逻辑下批消费——本模块零判定、零调整，只如实上报。
//
// 挂载形态照 termCite.js 的 body 级捕获监听先例：toggle 不冒泡但沿捕获
// 路径下发，body 上一个 capture 监听即可覆盖全部抽屉（含流式重渲新建的
// 节点）；幂等由单次 arm 保证。complaint 类信号不接受前端直写（判定权在
// 引擎词表分类器），故本模块只发 drawer-expanded 一种。

import { sendWs } from './ws.js';

let armed = false;

export function armProfileSignalCapture() {
  if (armed) return;
  armed = true;
  // capture 阶段拦截 toggle（非冒泡事件，捕获监听对后代 details 全生效）。
  document.body.addEventListener('toggle', (e) => {
    const d = e.target;
    if (!(d instanceof Element) || !d.classList || !d.classList.contains('nf-human-details')) return;
    if (!d.open) return; // 只报「展开」——收起是默认态回位，不是信号。
    try { sendWs({ type: 'recordCalibrationSignal', kind: 'drawer-expanded' }); } catch (_) { /* WS 未就绪：信号丢弃，下条再报 */ }
  }, true);
}
