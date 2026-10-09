// messages.js 拆分(FE组件化试点 2026-09-27):好友信任模式 v1 可复用模块(行为保持)。
import { key } from '../branding.js';

// ── 好友信任模式 v1（作者令：信任的好友，新消息自动走既有「转发给 agent」
// 通道）── 纯客户端本地标记，localStorage 持久化（与 fm_seen_requests /
// fm_blocked 同一家族）。存 userId 数组（与黑名单缓存同键——userId 比
// neblinkId/Username 稳定，friend_event 的 senderId 匹配也用它）。零后端改动；
// 跨设备同步 = v2 候选。本模块是 store 唯一属主，contacts.js 经导出入口读写。
// ⑨-6（作者预授权令）：裸键 `fm_trusted` 迁入 `key()` 品牌命名空间
// （`nebflow_fm_trusted` today）。存量值经 branding.js 的 LEGACY_IRREGULAR
// 启动即迁移，数据不丢；裸键字面只保留在 branding.js 的兼容层里。
const LS_TRUSTED = key('fm_trusted');
function loadTrusted() {
  try { return new Set(JSON.parse(localStorage.getItem(LS_TRUSTED) || '[]')); } catch { return new Set(); }
}
export function isFriendTrusted(userId) {
  return !!userId && loadTrusted().has(userId);
}
export function setFriendTrusted(userId, trusted) {
  if (!userId) return;
  const s = loadTrusted();
  if (trusted) s.add(userId); else s.delete(userId);
  try { localStorage.setItem(LS_TRUSTED, JSON.stringify([...s])); } catch { /* non-critical */ }
  // Let an open chat modal refresh its header trust indicator.
  window.dispatchEvent(new CustomEvent('fm-trust-changed', { detail: { userId, trusted } }));
}
