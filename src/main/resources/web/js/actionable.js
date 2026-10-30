// actionable.js — 角标可行动判据聚合面（认知二期产品批②；设计稿 §5 P6）
// 判据 = 闭合相关性：徽章只报「需要作者行动」的量（待批准/待销账/待拍板），
// 不报「有更新」（新鲜度不上徽章）。各面注册自己的可行动计数提供者；
// messages 徽标消费 actionableTotal()。零依赖模块，谁 import 都安全。

const providers = new Set();
const listeners = new Set();

/** 注册一个可行动计数提供者（返回非负整数；注销 = 返回的函数再调一次）。 */
export function registerActionableProvider(fn) {
  if (typeof fn !== 'function') return () => {};
  providers.add(fn);
  return () => { providers.delete(fn); };
}

export function actionableTotal() {
  let n = 0;
  for (const fn of providers) {
    const v = Number(fn());
    if (Number.isFinite(v) && v > 0) n += Math.floor(v);
  }
  return n;
}

/** 任一提供者计数变了 ⇒ 广播（messages 徽标等订阅者重渲）。 */
export function notifyActionableChanged() {
  for (const l of listeners) {
    try { l(actionableTotal()); } catch (_) { /* 单个监听炸不拖垮徽标面 */ }
  }
}

export function onActionableChanged(fn) {
  listeners.add(fn);
  return () => { listeners.delete(fn); }
}
