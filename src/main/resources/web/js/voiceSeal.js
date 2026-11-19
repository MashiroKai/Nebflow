// voiceSeal.js — 语音封存面的 DOM 挡板（voiceseal 批 #508 → 纠正批 #513）。
//
// 作者令（批 #508 逐字）：「把全双工语音的功能先封存。携带跟他同源的拖动居中
// 语音气泡的功能一起隐藏。」→ 纠正令（批 #513 逐字）：「怎么语音气泡直接没了，
// 我要去掉的是hover拖动这些功能。」——#508 把气泡整个藏了 = 理解过宽，#513
// 纠正为**分面受控**（零删除惯例不变：隐藏 ≠ 删除，social-section-remote
// 同款先例，摘掉 `hidden` 即原样回归）。
//
// ── 分面后的两个封存面（#513：各自独立 flag，正交）───────────────────────
//   面 A = 全双工语音（voice-mode batch）— flag `voiceEnabled()`（默认封存）
//     · DOM    `#voice-mode-overlay`（index.html body 级 overlay，静态 hidden）
//     · 入口   input.js 拖动手势（voiceBtn pointer 拖右 → enterVoiceModeNow）
//     · 引擎   voiceEngine conversation 模式（voiceMode.js startDictation
//              {conversation:true}）；WS `transcribe` 帧 + /api/tts 只在会话
//              期间发生 —— 入口封死 ⇒ 无会话 ⇒ 后端腿零连接零占用。
//     #508 封存形态**原样维持**（#513 ③「维持 #508 封存不变」）：拖动手势
//     入口仍由 voiceEnabled() 闸死（手势 = 全双工唯一入口，归属面 A）。
//   面 B = 语音气泡（micOrb 气泡）— flag `voiceOrbEnabled()`（默认显示）
//     · DOM    `#mic-orb-wrap`（#voice-btn + #mic-canvas + #mic-css-orb +
//              .voice-drag-hint）
//     · 挂载   micOrb.js initMicOrb 单例（WebGL 渲染器 + 1s 状态轮询）
//     #513 纠正后气泡是**默认产品形态**：可见、可点击听写（非全双工路径），
//     但为**纯静态**——hover 悬停反馈（.voice-drag-hint 箭头）与拖动/回弹
//     手势（input.js pointer 序列 + wrap transform 跟手）已摘交互面：
//       - hover   css/voiceMode.css 静态姿态覆盖规则（`.voice-static`，
//                 index.html 静态携带、JS 从不摘除）压过 `:hover` 显示规则；
//       - 拖动    input.js pointerdown 的 voiceEnabled() 闸（手势体零删除，
//                 flag ON = 随面 A 原样回归）；wrap 的 transform 跟手与
//                 `.dragging` 类因此永不发生，气泡固定默认位。
//     显式 `"features": { "voiceOrb": false }` 才藏气泡（独立于面 A）。
//
// ── 时序（#508 好友批同款 latch，#513 双参化）─────────────────────────────
//   index.html 静态携带：overlay `hidden`（面 A boot 默认封存）、气泡**无**
//   `hidden` + `voice-static`（面 B boot 默认显示 = 纠正后产品形态）。
//   main.js 在本 boot 首帧 configData 上 latch 一次：面 A flag ON →
//   unseal overlay；面 B flag 显式 OFF → seal 气泡（幂等，静态态兜底重申）。
//   configData 会随每次配置保存重发，latch 保证不热切换，flag 改动刷新页面
//   生效。latch 前无任何全双工会话可存在（入口 display:none 不可交互、WS 未连
//   ⇒ enterVoiceModeNow 的发送腿不存在），因此「已打开的会话恢复默认形态」
//   由时序天然保证。

/** 封存面节点 id 清单（index.html 静态位；测试/报告共用）。面 A 在前。 */
export const VOICE_SEAL_FACE_IDS = ['mic-orb-wrap', 'voice-mode-overlay'];

function faceEl(id) {
  return document.getElementById(id);
}

/** 封存指定面（或全部面）：补挂 `hidden`（幂等；静态态兜底重申）。
 *  @param {string|string[]} [ids] 缺省 = 全部面（#508 旧语义，零删除保留）。 */
export function sealVoiceFaces(ids = VOICE_SEAL_FACE_IDS) {
  for (const id of (Array.isArray(ids) ? ids : [ids])) faceEl(id)?.setAttribute('hidden', '');
}

/** 解封指定面（或全部面）：摘掉 `hidden`（幂等；不重排 DOM、不重建节点 ——
 *  监听与子树原样在位，flag 即完整恢复原功能）。
 *  @param {string|string[]} [ids] 缺省 = 全部面（#508 旧语义，零删除保留）。 */
export function unsealVoiceFaces(ids = VOICE_SEAL_FACE_IDS) {
  for (const id of (Array.isArray(ids) ? ids : [ids])) faceEl(id)?.removeAttribute('hidden');
}

/** boot latch 应用点（main.js configData 首帧调用一次）：按双 flag 落姿态。
 *  @param {boolean} enabled    voiceEnabled() 的 latch 读数（面 A 全双工）。
 *  @param {boolean} [orbEnabled] voiceOrbEnabled() 的 latch 读数（面 B 气泡，
 *                              默认 true = 显示；#508 单参调用兼容为「气泡在」。 */
export function applyVoiceSeal(enabled, orbEnabled = true) {
  if (enabled) unsealVoiceFaces('voice-mode-overlay');
  else sealVoiceFaces('voice-mode-overlay');
  if (orbEnabled === false) sealVoiceFaces('mic-orb-wrap');
  else unsealVoiceFaces('mic-orb-wrap');
}
