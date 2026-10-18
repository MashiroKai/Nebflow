// voiceSeal.js — 全双工语音 + 同源语音气泡 封存面的 DOM 挡板（voiceseal 批）。
//
// 作者令（批 #508 逐字）：「把全双工语音的功能先封存。携带跟他同源的拖动居中
// 语音气泡的功能一起隐藏。」flag = featureFlags.js `voiceEnabled()`
// （`features.voice`，默认封存）；本模块只做两个面的 **hidden 挡板**
// （隐藏 ≠ 删除，social-section-remote 同款先例：DOM/JS/CSS/i18n 全保留，
// 摘掉 `hidden` 即原样回归）。
//
// ── 两个封存面（同一 flag 一并受控）─────────────────────────────────────
//   面 A = 全双工语音（voice-mode batch）
//     · DOM    `#voice-mode-overlay`（index.html body 级 overlay）
//     · 入口   input.js 拖动手势（voiceBtn pointer 拖右 → enterVoiceModeNow）
//     · 引擎   voiceEngine conversation 模式（voiceMode.js startDictation
//              {conversation:true}）；WS `transcribe` 帧 + /api/tts 只在会话
//              期间发生 —— 入口封死 ⇒ 无会话 ⇒ 后端腿零连接零占用。
//   面 B = 拖动居中语音气泡（micOrb 气泡）
//     · DOM    `#mic-orb-wrap`（#voice-btn + #mic-canvas + #mic-css-orb +
//              .voice-drag-hint）
//     · 挂载   micOrb.js initMicOrb 单例（WebGL 渲染器 + 1s 状态轮询）——
//              封存态不挂载 ⇒ 不建 GL 上下文、不起轮询（不占资源）。
//
// ── 同源判定（为何一个 flag 管两面，证据 file:line）──────────────────────
//   1) voiceMode.js:24 `import { registerVoiceModeOrb, notifyVoiceState }
//      from './micOrb.js'` — overlay 的居中球是输入条气泡的**镜像注册体**
//      （micOrb.js registerVoiceModeOrb → registerMirror/applyMirror，同一
//      单例、条上球为状态源）；
//   2) index.html 两处 DOM 同一解剖：#mic-orb-wrap（:313-317）与
//      #vmo-orb-wrap > .micbubble/.orb-canvas/.css-orb（:360-364）同 class 族；
//   3) 全双工唯一入口就长在气泡上：input.js 「Full-duplex voice entry …
//      dragging the voice bubble right … is the only way in」（:1272-1276，
//      pointer 手势 :1324-1356）；
//   4) css/voiceMode.css:85-100 「Centered bubble — same wrap/bubble anatomy
//      as the input-bar orb」+ .voice-drag-hint（:190-227）挂在 .mic-orb-wrap 内；
//   5) 两面共用一个 voiceEngine 会话（input.js:1290 「Push-to-talk and voice
//      mode share one mic session」 ensureDictationIdle）。
//
// ── 时序（好友批同款 latch）───────────────────────────────────────────────
//   index.html 静态携带 `hidden`（预绘制零闪现 = boot 默认封存姿态）；
//   main.js 在本 boot 首帧 configData 上 latch 一次：flag ON →
//   unsealVoiceFaces() + initMicOrb()；flag OFF → sealVoiceFaces()（幂等，
//   静态态兜底重申）。configData 会随每次配置保存重发，latch 保证不热切换，
//   flag 改动刷新页面生效。latch 前无任何语音会话可存在（入口 display:none
//   不可交互、WS 未连 ⇒ enterVoiceModeNow 的发送腿不存在），因此「已打开的
//   会话恢复默认形态」由时序天然保证。

/** 两个封存面的节点 id（index.html 静态位；测试/报告共用清单）。 */
export const VOICE_SEAL_FACE_IDS = ['mic-orb-wrap', 'voice-mode-overlay'];

function faceEl(id) {
  return document.getElementById(id);
}

/** 封存两面：补挂 `hidden`（幂等；latch OFF 时的静态态兜底重申）。 */
export function sealVoiceFaces() {
  for (const id of VOICE_SEAL_FACE_IDS) faceEl(id)?.setAttribute('hidden', '');
}

/** 解封两面：摘掉 `hidden`（幂等；不重排 DOM、不重建节点 —— 监听与子树
 *  原样在位，flag ON 即完整恢复原功能）。 */
export function unsealVoiceFaces() {
  for (const id of VOICE_SEAL_FACE_IDS) faceEl(id)?.removeAttribute('hidden');
}

/** boot latch 应用点（main.js configData 首帧调用一次）：按 flag 落姿态。
 *  @param {boolean} enabled voiceEnabled() 的 latch 读数（单一判定源）。 */
export function applyVoiceSeal(enabled) {
  if (enabled) unsealVoiceFaces();
  else sealVoiceFaces();
}
