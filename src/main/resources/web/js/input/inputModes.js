// input.js 拆分（FE组件化批次四 2026-09-27）：输入模式族（ask/skill/compact 模式进入退出 + 指示器 + 草稿态恢复） 可复用模块（行为保持）。
import { activeView } from '../chatView.js';
import { t } from '../i18n.js';

// ---------- Ask Mode ----------
export function enterAskMode() {
  if (activeView.stream.askMode) return;
  activeView.stream.askMode = true;
  updateAskIndicator();
  activeView.dom.input.placeholder = t('input.askPlaceholder');
  activeView.dom.input.focus();
}

export function cancelAskMode() {
  if (!activeView.stream.askMode) return;
  activeView.stream.askMode = false;
  updateInputIndicator();
  activeView.dom.input.placeholder = t('input.placeholder');
}

function updateAskIndicator() {
  updateInputIndicator();
}

function updateInputIndicator() {
  const askEl = document.getElementById('ask-indicator');
  const skillEl = document.getElementById('skill-indicator');
  const skillLabel = document.getElementById('skill-indicator-label');
  const compactEl = document.getElementById('compact-indicator');
  const input = activeView.dom.input;
  // Ask/Skill/Compact mode — all mutually exclusive
  if (activeView.stream.askMode) {
    if (askEl) askEl.classList.add('show');
    if (skillEl) skillEl.classList.remove('show');
    if (compactEl) compactEl.classList.remove('show');
    input.style.paddingLeft = '';
    if (askEl) {
      const w = askEl.offsetWidth + 12;
      input.style.paddingLeft = Math.max(w, 48) + 'px';
    }
  } else if (activeView.skillMode) {
    if (askEl) askEl.classList.remove('show');
    if (skillEl) {
      if (skillLabel) skillLabel.textContent = activeView.skillModeSource === 'flow' ? 'FLOW' : (activeView.skillModeName || 'SKILL');
      skillEl.classList.add('show');
      const w = skillEl.offsetWidth + 12;
      input.style.paddingLeft = Math.max(w, 56) + 'px';
    } else {
      input.style.paddingLeft = '';
    }
    if (compactEl) compactEl.classList.remove('show');
  } else if (activeView.compactMode) {
    if (askEl) askEl.classList.remove('show');
    if (skillEl) skillEl.classList.remove('show');
    if (compactEl) compactEl.classList.add('show');
    input.style.paddingLeft = '';
    const w = compactEl.offsetWidth + 12;
    input.style.paddingLeft = Math.max(w, 48) + 'px';
  } else {
    if (askEl) askEl.classList.remove('show');
    if (skillEl) skillEl.classList.remove('show');
    if (compactEl) compactEl.classList.remove('show');
    input.style.paddingLeft = '';
  }
}

// ---------- Skill Mode ----------
export function enterSkillMode(skillName, description, argumentHint, source) {
  if (activeView.skillMode) {
    // Already in skill mode — if it's a different skill, switch; otherwise do nothing
    if (activeView.skillModeName === skillName) return;
    cancelSkillMode();
  }
  // Cancel ask mode if active
  if (activeView.stream.askMode) cancelAskMode();
  activeView.skillMode = true;
  activeView.skillModeName = skillName;
  activeView.skillModeSource = source || '';
  activeView.skillModeDesc = description || '';
  activeView.skillModeArgHint = argumentHint || '';
  updateInputIndicator();
  activeView.dom.input.placeholder = argumentHint || t('input.skillPlaceholder');
  activeView.dom.input.focus();
}

export function cancelSkillMode() {
  if (!activeView.skillMode) return;
  activeView.skillMode = false;
  activeView.skillModeName = '';
  activeView.skillModeSource = '';
  activeView.skillModeDesc = '';
  activeView.skillModeArgHint = '';
  updateInputIndicator();
  activeView.dom.input.placeholder = t('input.placeholder');
}

// ---------- Compact Mode ----------
export function enterCompactMode() {
  if (activeView.compactMode) return;
  // Cancel other modes if active
  if (activeView.stream.askMode) cancelAskMode();
  if (activeView.skillMode) cancelSkillMode();
  activeView.compactMode = true;
  updateInputIndicator();
  activeView.dom.input.placeholder = t('input.compactPlaceholder');
  activeView.dom.input.focus();
}

export function cancelCompactMode() {
  if (!activeView.compactMode) return;
  activeView.compactMode = false;
  updateInputIndicator();
  activeView.dom.input.placeholder = t('input.placeholder');
}

// Restore skill/ask mode from per-session draft data.
// Unlike enterSkillMode/enterAskMode, this does NOT focus the input.
export function applyInputModes(skillData, askActive) {
  activeView.skillMode = false;
  activeView.skillModeName = '';
  activeView.skillModeSource = '';
  activeView.skillModeDesc = '';
  activeView.skillModeArgHint = '';
  activeView.stream.askMode = false;

  if (skillData) {
    activeView.skillMode = true;
    activeView.skillModeName = skillData.name || '';
    activeView.skillModeSource = skillData.source || '';
    activeView.skillModeDesc = skillData.desc || '';
    activeView.skillModeArgHint = skillData.argHint || '';
  } else if (askActive) {
    activeView.stream.askMode = true;
  }

  updateInputIndicator();

  if (activeView.skillMode) {
    activeView.dom.input.placeholder = activeView.skillModeArgHint || t('input.skillPlaceholder');
  } else if (activeView.stream.askMode) {
    activeView.dom.input.placeholder = t('input.askPlaceholder');
  } else {
    activeView.dom.input.placeholder = t('input.placeholder');
  }
}
