// swReg.js — 控制台温访问秒开注册器（console-load-perf 批 2026-10-21）。
//
// 注册 /sw.js（scope=/）。注册这个动作被刻意推到 window load 之后：SW 安装
// 线程与缓存填充不挤占首开关键路径（温访问收益在下一次导航兑现）。
//
// 降级与逃生门（任何一条不满足 = 页面行为与无 SW 时代逐字节一致）：
//  · 引擎未重启（/sw.js 路由未上，404）⇒ register 被拒 ⇒ 静默空转零影响——
//    本文件可以先行合入，激活完全等重启窗；
//  · localStorage `<lower>_sw`='0' ⇒ 反向注销现存注册并跳过；
//  · 硬刷新（Cmd/Ctrl+Shift+R）浏览器原生绕过 SW；URL 带 ?nfsw=0 同效；
//  · 本函数任何异常都被吞掉——注册面永远不能影响主功能。
//
// 陈旧防护链与缓存面 allowlist 见 sw.js 文件头。

export function initServiceWorker() {
  try {
    if (!('serviceWorker' in navigator)) return;
    if (location.protocol !== 'http:' && location.protocol !== 'https:') return;
    const lower = (/** @type {Window & { __BRAND__?: { lowerName?: string } }} */ (window).__BRAND__?.lowerName) || 'nebflow';
    const flagKey = lower + '_sw';
    if (localStorage.getItem(flagKey) === '0') {
      navigator.serviceWorker.getRegistration().then((reg) => reg && reg.unregister()).catch(() => {});
      return;
    }
    const kick = () => {
      navigator.serviceWorker.register('/sw.js', { scope: '/' }).catch(() => {
        /* /sw.js 未上线（引擎未重启）或注册被拒：静默空转 */
      });
    };
    if (document.readyState === 'complete') kick();
    else addEventListener('load', kick, { once: true });
  } catch {
    /* 注册面任何意外都不能影响主功能 */
  }
}
