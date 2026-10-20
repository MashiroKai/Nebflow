// mobile/daemonFace.js — P4「心跳进程」手机面（mobile-secondary-screens 实施批；
// 样张 v3.5 P4 开放点③「文案沿用」裁定 + 脚注形态）。
//
// 职责（单一小面）：
//   · 面板脚注（#daemon-panel .daemon-panel-footer，桌面恒空节点）：填「每 5 秒
//     轮询 · 宿主周期检测服务端口，进程掉线约 10 秒后自动拉起。」—— 前腿为新键
//     daemons.pollEvery（轮询周期 = openPanel 既有 5000ms 实现值），后腿 = 现网
//     daemons.emptyHint2 同源原文（设计报告 §1「现网文案」口径）。
//   · 时间线（开放点①）/ 延迟毫秒（开放点②）：后端无心跳事件流/延迟字段，
//     不伪造数据 —— 记开放点不实施（见实施 spec P4 节）。
//
// 桌面零改动边界：脚注节点桌面本来就渲染为空 div；本文件只在其内追加/摘除
// 一个自建子节点。进程行本体（状态点/名/:port/命令/自启开关/动作钮）全走桌面
// 既有渲染，零重造。

import { t } from '../i18n.js';

/** @type {HTMLElement|null} 自建脚注行（拆层摘除）。 */
let foot = null;
/** @type {Array<() => void>} */
let unsubs = [];

function syncFoot() {
  if (!foot) return;
  foot.textContent = `${t('daemons.pollEvery')} · ${t('daemons.emptyHint2')}`;
}

export function activateDaemonFace() {
  const panel = document.getElementById('daemon-panel');
  const footer = panel?.querySelector('.daemon-panel-footer');
  if (footer && !footer.querySelector('.mh-daemon-foot')) {
    foot = document.createElement('div');
    foot.className = 'mh-daemon-foot';
    syncFoot();
    footer.appendChild(foot);
  }

  const onLocale = () => syncFoot();
  window.addEventListener('locale-changed', onLocale);
  unsubs.push(() => window.removeEventListener('locale-changed', onLocale));

  return function teardownDaemonFace() {
    for (const fn of unsubs.splice(0)) { try { fn(); } catch { /* ignore */ } }
    if (foot) { foot.remove(); foot = null; }
  };
}
