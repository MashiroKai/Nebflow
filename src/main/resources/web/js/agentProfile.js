// agentProfile.js — 会话头头像 + profile 面板（mobile-mockup-375 v3 实施批 · 扩展③）。
//
// 作者已批形态（v2④ 提案 + v3 现场三条扩展之三）：
//   · 入口改道：**不要**右下角小相机角标 —— 点击会话头头像直接进 profile 面板；
//   · 面板内便捷设置**头像与名称**：头像上传走 local-first（localStorage dataURL
//     按 agentId 键，镜像 avatarCache.js 的 branding 命名空间键位）；名称就地编辑
//     （回车保存，写穿既有 display-name 机制 —— root 走 rootName 单读点同款写点，
//     非 root 写 agentsData 行，二者都以 `nebflow-root-name-change` 事件刷新 Header）；
//   · 渲染走 avatarRender.js 复用池（avatarImgNode）防闪：同 dataURL 命中池内已解码
//     节点 ⇒ 重挂载零重解码零空白帧；缺省回落首字母圆牌；
//   · 会话头两端同款：桌面 = 头像居中 + 名称在下（v2/v3 已批形态，css/chat.css）；
//     手机 375 = 悬浮顶栏中簇（css/mobileLayer.css v3 段）。同一 DOM 节点两端共用，
//     本文件不区分视口 —— 形态差异全部在 CSS 层。
//
// 边界（任务书）：本批只做会话头/头像两端同款 + profile 面板适肤；列表行/抽层等
// 其余消费点、以及名称的服务端持久化写路径（onboarding 的 agent.json 腿）不在本批。
// 功能逻辑零重造：存储/avatarCache 同键位精神、渲染/avatarRender 同池、名称/既有
// display-name 机制同写点，本模块只是把三件既有设施接到会话头这一个面上。

import { key } from './branding.js';
import state from './state.js';
import { createIconsIn } from './utils.js';
import { avatarImgNode } from './avatarRender.js';
import { rootDisplayName, isRootNamed } from './rootName.js';
import { t } from './i18n.js';

/** 头像存储键（镜像 avatarCache.js：branding 命名空间 + 按身份键一条目）。 */
function avatarKey(agentName) { return key(`agent_avatar_${agentName || 'Nebula'}`); }

/** 上传图降采样上限（px）：localStorage 配额友好（avatarCache.js 同款配额纪律）。 */
const MAX_DIM = 256;

function readStored(agentName) {
  try { return localStorage.getItem(avatarKey(agentName)) || ''; } catch { return ''; }
}

function writeStored(agentName, dataUrl) {
  try {
    if (dataUrl) localStorage.setItem(avatarKey(agentName), dataUrl);
    else localStorage.removeItem(avatarKey(agentName));
  } catch { /* 隐私模式/配额满：本地头像为尽力而为（回落首字母圆牌） */ }
}

/** 当前会话头身份 = 活动会话的 agent（root 会话恒 'Nebula' 机制名）。 */
export function currentAgentName() {
  const active = state.sessions ? state.sessions.find((s) => s.id === state.activeSessionId) : null;
  return (active && active.agentName) || 'Nebula';
}

/** 会话头显示名（与 sidebar.js updateHeaderSessionName 同一读链的本地镜像：
 *  root → rootName 单读点（未命名 = 空串，机制名永不作为显示回落）；
 *  非 root → agentsData displayName，机制名兜底。 */
function headerLabel(agentName) {
  if (agentName === 'Nebula') return isRootNamed() ? rootDisplayName() : '';
  const row = (state.agentsData || []).find((a) => a.name === agentName);
  return (row && row.displayName) || agentName;
}

// ── 会话头头像槽（两端同一节点；css/chat.css 桌面形 / mobileLayer.css 手机形）──

/** @type {HTMLElement|null} */
let slot = null;

function buildSlot() {
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.id = 'agent-avatar-btn';
  btn.className = 'agent-avatar-btn';
  btn.setAttribute('aria-haspopup', 'dialog');
  btn.addEventListener('click', () => openProfilePanel());
  return btn;
}

/** 首字母圆牌字形（缺省回落；与 agentManager/设置页命名牌同源的首字大写）。 */
function initialOf(label) {
  const ch = (label || '').trim().charAt(0);
  return ch ? ch.toUpperCase() : '';
}

function paintSlot() {
  if (!slot) return;
  const agentName = currentAgentName();
  const label = headerLabel(agentName);
  slot.setAttribute('aria-label', t('profile.openAria'));
  slot.title = t('profile.openAria');
  const dataUrl = readStored(agentName);
  const img = dataUrl ? avatarImgNode(dataUrl) : null; // 复用池：同 dataURL 零重解码
  if (img) {
    img.className = 'agent-avatar-img';
    img.draggable = false;
    slot.replaceChildren(img);
    slot.classList.add('has-img');
    return;
  }
  slot.classList.remove('has-img');
  const initial = initialOf(label);
  if (initial) {
    const plate = document.createElement('span');
    plate.className = 'agent-avatar-initial';
    plate.textContent = initial;
    plate.setAttribute('aria-hidden', 'true');
    slot.replaceChildren(plate);
  } else {
    // 未命名 root：无名字 ⇒ 无字母，中性 user 字形占位（机制名不作显示回落）。
    slot.replaceChildren();
    const glyph = document.createElement('i');
    glyph.setAttribute('data-lucide', 'user');
    slot.appendChild(glyph);
    if (typeof lucide !== 'undefined') createIconsIn(slot);
  }
}

// ── profile 面板（body 级一件；桌面居中卡 / 手机 v3 悬浮卡，几何在 CSS 层）──

/** @type {HTMLElement|null} */
let panel = null;
/** @type {HTMLElement|null} */
let nameInput = null;
/** @type {HTMLInputElement|null} */
let fileInput = null;
/** @type {HTMLElement|null} */
let previewBox = null;

function ensurePanel() {
  if (panel) return panel;
  panel = document.createElement('div');
  panel.id = 'agent-profile-panel';
  panel.className = 'agent-profile-panel';
  panel.setAttribute('role', 'dialog');
  panel.setAttribute('aria-modal', 'false');
  panel.hidden = true;

  const head = document.createElement('div');
  head.className = 'ap-head';
  const icon = document.createElement('i');
  icon.setAttribute('data-lucide', 'user');
  const title = document.createElement('span');
  title.className = 'ap-title';
  title.textContent = t('profile.title');
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'ap-close glass-control';
  close.setAttribute('aria-label', t('profile.closeAria'));
  close.innerHTML = '<i data-lucide="x"></i>';
  close.addEventListener('click', () => closeProfilePanel());
  head.append(icon, title, close);

  previewBox = document.createElement('div');
  previewBox.className = 'ap-avatar-box';

  const btnRow = document.createElement('div');
  btnRow.className = 'ap-btnrow';
  const upload = document.createElement('button');
  upload.type = 'button';
  upload.className = 'ap-btn ap-btn-primary';
  upload.innerHTML = `<i data-lucide="image"></i><span>${t('profile.upload')}</span>`;
  upload.addEventListener('click', () => fileInput && fileInput.click());
  const reset = document.createElement('button');
  reset.type = 'button';
  reset.className = 'ap-btn ap-btn-ghost';
  reset.innerHTML = `<i data-lucide="rotate-ccw"></i><span>${t('profile.reset')}</span>`;
  reset.addEventListener('click', () => {
    writeStored(currentAgentName(), '');
    paintSlot();
    paintPreview();
  });
  btnRow.append(upload, reset);

  fileInput = document.createElement('input');
  fileInput.type = 'file';
  fileInput.accept = 'image/*';
  fileInput.hidden = true;
  fileInput.setAttribute('aria-hidden', 'true');
  fileInput.addEventListener('change', () => {
    const f = fileInput.files && fileInput.files[0];
    fileInput.value = '';
    if (!f || !f.type.startsWith('image/')) return;
    downscaleToFile(f).then((dataUrl) => {
      if (!dataUrl) return;
      writeStored(currentAgentName(), dataUrl);
      paintSlot();
      paintPreview();
    }).catch(() => { /* 读取失败：维持现状 */ });
  });

  const nameRow = document.createElement('div');
  nameRow.className = 'ap-namerow';
  const nameLab = document.createElement('span');
  nameLab.className = 'ap-name-label';
  nameLab.textContent = t('profile.nameLabel');
  nameInput = document.createElement('input');
  nameInput.type = 'text';
  nameInput.className = 'ap-name-input';
  nameInput.setAttribute('aria-label', t('profile.nameLabel'));
  // 就地编辑：回车保存（写穿既有 display-name 机制），Esc 还原。
  nameInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); saveName(); nameInput.blur(); }
    else if (e.key === 'Escape') { fillName(); nameInput.blur(); }
  });
  nameInput.addEventListener('blur', () => { /* 失焦不保存 —— 回车是唯一保存动作（作者口径） */ });
  nameRow.append(nameLab, nameInput);

  const note = document.createElement('div');
  note.className = 'ap-note';
  note.textContent = t('profile.note');

  panel.append(head, previewBox, btnRow, nameRow, note, fileInput);
  document.body.appendChild(panel);
  if (typeof lucide !== 'undefined') createIconsIn(panel);
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && panel && !panel.hidden) closeProfilePanel();
  });
  return panel;
}

/** 上传图降采样到 ≤MAX_DIM 方形 dataURL（非图/读失败 resolve null）。 */
function downscaleToFile(file) {
  return new Promise((resolve) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      try {
        const side = Math.min(img.naturalWidth, img.naturalHeight);
        const dim = Math.min(MAX_DIM, side) || MAX_DIM;
        const cv = document.createElement('canvas');
        cv.width = dim; cv.height = dim;
        const ctx = cv.getContext('2d');
        // 中裁方形（头像语义）；PNG 进 JPEG 前铺底，避免透明变黑。
        ctx.fillStyle = '#f5f6f8';
        ctx.fillRect(0, 0, dim, dim);
        ctx.drawImage(img, (img.naturalWidth - side) / 2, (img.naturalHeight - side) / 2, side, side, 0, 0, dim, dim);
        URL.revokeObjectURL(url);
        resolve(cv.toDataURL('image/jpeg', 0.85));
      } catch { URL.revokeObjectURL(url); resolve(null); }
    };
    img.onerror = () => { URL.revokeObjectURL(url); resolve(null); };
    img.src = url;
  });
}

function paintPreview() {
  if (!previewBox) return;
  const agentName = currentAgentName();
  const label = headerLabel(agentName);
  const dataUrl = readStored(agentName);
  const img = dataUrl ? avatarImgNode(dataUrl) : null;
  if (img) {
    img.className = 'ap-avatar-img';
    previewBox.replaceChildren(img);
    previewBox.classList.add('has-img');
    return;
  }
  previewBox.classList.remove('has-img');
  previewBox.replaceChildren();
  const plate = document.createElement('div');
  plate.className = 'ap-avatar-initial';
  plate.textContent = initialOf(label) || '·';
  previewBox.appendChild(plate);
}

function fillName() {
  if (!nameInput) return;
  nameInput.value = headerLabel(currentAgentName());
  nameInput.placeholder = t('profile.namePlaceholder');
}

/** 名称就地编辑保存（回车）：写穿既有 display-name 机制 —— root 与 onboarding
 *  renameEverywhere 同三写（override + agentsData 行 + 事件）；非 root 写 agentsData
 *  行 + 事件。空值 = 还原为机制名行（root：清 override，头名按未命名口径留空）。 */
function saveName() {
  if (!nameInput) return;
  const agentName = currentAgentName();
  const next = nameInput.value.trim();
  if (agentName === 'Nebula') {
    state.rootDisplayName = next;
    const self = (state.agentsData || []).find((x) => x.name === 'Nebula');
    if (self) self.displayName = next;
  } else {
    const row = (state.agentsData || []).find((a) => a.name === agentName);
    if (row) row.displayName = next;
  }
  window.dispatchEvent(new CustomEvent('nebflow-root-name-change'));
  paintSlot();
  paintPreview();
}

/** 打开 profile 面板。手机视口由 mobileLayer 接管几何（morph 形态）；桌面居中卡。 */
export function openProfilePanel() {
  const p = ensurePanel();
  fillName();
  paintPreview();
  p.hidden = false;
  document.body.classList.add('ap-open');
}

export function closeProfilePanel() {
  if (!panel) return;
  document.body.classList.remove('ap-open');
  panel.hidden = true;
}

// ── 初始化（main.js 组装根调用一次；桌面/手机共此一件）────────────────────

let inited = false;
export function initAgentProfile() {
  if (inited) return;
  inited = true;
  const center = document.querySelector('#header .header-center');
  if (center) {
    slot = buildSlot();
    center.insertBefore(slot, center.firstChild);
  }
  paintSlot();
  // 身份/名字变化 → 重绘（会话切换、起名事件、agentsData 到达）。
  window.addEventListener('nebflow-session-change', paintSlot);
  window.addEventListener('nebflow-root-name-change', () => { paintSlot(); });
  window.addEventListener('locale-changed', () => { paintSlot(); if (panel && !panel.hidden) { fillName(); } });
}
