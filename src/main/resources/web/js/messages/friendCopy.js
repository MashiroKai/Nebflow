// messages.js 拆分(FE组件化试点 2026-09-27):好友腿终态码分态文案与备注显示 可复用模块(行为保持)。

// ── 好友腿终态码 → 分态文案（rcptcode 批 2026-09-20）────────────────────────
// 判定集合 = `friendsApi.js` 的 `FRIEND_TERMINAL_CODES`（**同源单表**，经
// `api.isFriendTerminalCode` 消费；🔴 禁在本文件另写一份码表）。
// 语义 = **终态**：对同一动作重试恒无效（同 `clientMsgId` + 同被引坐标重发只会再失败
// ⇒ 不给重试键，改给原因 + 正文回填），与群腿「按码分态」同族而非同表。
// 📌 r2 基线适配（uxconsist Phase B 段2 之后）：本表**零改**——码集与文案键是批契约面；
// 改的只是失败面的**装配形态**（`bindRetry` / `applyPhase` 单点，见 `sendCurrent`）。
const FRIEND_TERMINAL_TEXT = {
  not_friends: 'messages.friendNotFriends',
  not_blocker: 'messages.friendNotBlocker',
  REPLY_TARGET_INVALID: 'messages.friendReplyTargetInvalid',
};

// ── ⑦ 好友备注显示（作者裁定 2026-09-12，方案 §4.3(c)）────────────
// 显示优先级「备注 > 显示名」；`username`（neblinkId，NL 号）显示面不变。
// 备注缺失（null/undefined）⇒ 回落显示名，绝不渲染 null 字面。
export function personLabel(person) {
  if (!person) return '';
  return person.remark || person.name || person.neblinkId || '';
}

export { FRIEND_TERMINAL_TEXT };
