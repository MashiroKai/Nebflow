// agentProfile.js — 会话头头像 + 智能体分区（面板合并批 2026-10-23 重构）。
//
// 作者已批形态（v2④ 提案 + v3 现场三条扩展之三 + 面板合并批两令）：
//   · 面板合并（作者令 2026-10-23 ①）：本模块不再持有 body 级独立面板
//     （#agent-profile-panel 退役）—— 头像/名称内容以 .ap-pane 一件式分区注入
//     「智能体 + 记忆」合并面板（memory.js ensureMergedChrome 调 buildProfilePane）；
//     点击会话头头像 = 打开合并面板的智能体分区（openMergedPanel('agent')）。
//   · 头像设置交互（作者令 ②）：**没有**单独的「设置头像」按钮 —— 点击面板内
//     头像本体即唤起文件选择换头像；「重置头像」按钮**删除**（恢复默认 = 换回
//     引擎缺省头像的路径不再提供 UI 入口）。
//   · 面板内便捷设置头像与名称：头像上传走 local-first（localStorage dataURL
//     按 agentId 键，镜像 avatarCache.js 的 branding 命名空间键位）；名称就地编辑
//     （回车保存，写穿既有 display-name 机制）；渲染走 avatarRender.js 复用池。
//
// 边界（identity 批任务书）：本模块只接会话头/头像两端同款 + profile 面板适肤；
// 功能逻辑零重造（存储/avatarCache 同键位、渲染/avatarRender 同池、名称/既有
// display-name 机制同写点）。
//
// 持久/同步腿（2026-10-21 诊断批症状⑧）：本地优先照旧（读先 localStorage，服务端
// 作缺键回填 + 跨端载体），写入 fire-and-forget 写穿（失败静默——内存态已成立，
// 下次 list/探针以服务端为准自纠）；上传 PUT /api/agents/:name/avatar；「重置
// 头像」按钮已随面板合并批退役（作者令②），DELETE 分支保留供跨端清除收敛语义。
//
// identity-sync 批（2026-10-23，作者令「名称和头像还是没有跨机同步」）：v1 的
// 「本地有值就永不下拉」正是仍不同步的根因——本端 localStorage 旧键永远遮住
// 服务端新值（另一端写入/清除都看不见）。本批改 **sha 对账收敛**：
//   · 本地条目升级为 {dataUrl, sha, at}（v1 裸 dataURL 旧值宽容迁移）；
//   · 收敛探针 GET /api/agents/:name/avatar/meta（引擎新面，零字节）——sha 异/
//     缺才拉一次字节回填；服务端 404 ⇒ 服务端已清（他端 reset）⇒ 本地键清除；
//   · 本机写入即知 sha（crypto.subtle）⇒ 写完即收敛态，探针零抖动；
//   · 本机写后 15s 静默窗（自己的 PUT 在途时探针不误判 404）；
//   · WS 重连 / `agentIdentityChanged`（引擎跨机收敛落地广播）⇒ 强制对账 + 重绘
//     ——另一台机器改完，这台在会话恢复/重连后即可见。

import { key } from './branding.js';
import { brand } from './brand.js';
import state from './state.js';
import { avatarImgNode } from './avatarRender.js';
import { rootDisplayName, isRootNamed, agentDisplayName, ROOT_AGENT_KEY } from './rootName.js';
import { t } from './i18n.js';
import { onReconnect } from './ws.js';

/** 头像存储键（镜像 avatarCache.js：branding 命名空间 + 按身份键一条目）。 */
function avatarKey(agentName) { return key(`agent_avatar_${agentName || ROOT_AGENT_KEY}`); }

/** Bearer 文件令牌头（agentManager.js 同款镜像；控制台会话腿同源 cookie，双保险）。 */
function authHeaders() {
  let tok = '';
  try { tok = localStorage.getItem(key('token')) || ''; } catch { /* 隐私模式：裸 cookie */ }
  return tok ? { Authorization: `Bearer ${tok}` } : {};
}

/**
 * 读本地条目。v2 形态 = JSON `{dataUrl, sha, at}`；v1 形态 = 裸 dataURL 字符串
 * （宽容迁移：sha 视为未知 ''，首次对账时补戳）。损坏 ⇒ null（回落首字母圆牌）。
 */
function readEntry(agentName) {
  try {
    const raw = localStorage.getItem(avatarKey(agentName)) || '';
    if (!raw) return null;
    if (raw.startsWith('{')) {
      const j = JSON.parse(raw);
      if (j && typeof j.dataUrl === 'string') return { dataUrl: j.dataUrl, sha: typeof j.sha === 'string' ? j.sha : '' };
      return null;
    }
    return { dataUrl: raw, sha: '' }; // v1 裸 dataURL
  } catch { return null; }
}

/** dataURL → 本地条目写盘（含 sha；null dataUrl = 清键）。 */
function writeEntry(agentName, dataUrl, sha) {
  try {
    if (dataUrl) {
      localStorage.setItem(avatarKey(agentName), JSON.stringify({ dataUrl, sha: sha || '', at: Date.now() }));
    } else {
      localStorage.removeItem(avatarKey(agentName));
    }
  } catch { /* 隐私模式/配额满：本地头像为尽力而为（回落首字母圆牌） */ }
}

/** dataURL 的 base64 载荷 → sha256 hex（crypto.subtle；引擎侧同算法同值）。 */
async function sha256OfDataUrl(dataUrl) {
  try {
    const b64 = String(dataUrl).split(',')[1] || '';
    const bin = atob(b64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    const digest = await crypto.subtle.digest('SHA-256', bytes);
    return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, '0')).join('');
  } catch { return ''; }
}

/** 本机写入时刻（agentName → ts）：静默窗判据（写后 15s 内不对账，防在途 PUT 误判）。 */
const localWriteAt = new Map();
const LOCAL_WRITE_GRACE_MS = 15000;
/** 对账节流（agentName → ts）：常规 paint 30s 至多一次；强制触发绕过。 */
const lastMetaCheckAt = new Map();
const META_CHECK_THROTTLE_MS = 30000;

/**
 * 服务端写穿（fire-and-forget）：上传/清除头像到引擎共享存储（跨设备同步载体）。
 * 引擎落盘后会外推到全部已配对设备（identity-sync 通道），另一台机器按戳收敛。
 */
function pushAvatar(agentName, dataUrl) {
  const path = `/api/agents/${encodeURIComponent(agentName || ROOT_AGENT_KEY)}/avatar`;
  const opts = dataUrl
    ? { method: 'PUT', headers: { 'Content-Type': 'application/json', ...authHeaders() }, body: JSON.stringify({ dataUrl }) }
    : { method: 'DELETE', headers: authHeaders() };
  fetch(path, opts).catch(() => { /* 离线/未落座：本地态照旧，服务端待下次写入收敛 */ });
}

/**
 * sha 对账收敛（identity-sync 批）：meta 探针（零字节）→ sha 异/缺才拉字节回填；
 * 服务端 404 = 他端已清 ⇒ 本地键清除（v1 跨端 reset 不收敛的闭合）。
 * force = 绕过节流（重连 / agentIdentityChanged 触发）。
 */
const avatarSyncInFlight = new Set();
function convergeAvatar(agentName, repaint, force) {
  const name = agentName || ROOT_AGENT_KEY;
  if (avatarSyncInFlight.has(name)) return;
  const wrote = localWriteAt.get(name) || 0;
  if (wrote && Date.now() - wrote < LOCAL_WRITE_GRACE_MS) return; // 自己的 PUT 在途
  const last = lastMetaCheckAt.get(name) || 0;
  if (!force && Date.now() - last < META_CHECK_THROTTLE_MS) return;
  avatarSyncInFlight.add(name);
  lastMetaCheckAt.set(name, Date.now());
  fetch(`/api/agents/${encodeURIComponent(name)}/avatar/meta`, { headers: authHeaders() })
    .then(async (r) => {
      if (r.status === 404) {
        // 服务端无自定义头像：本地还有值 ⇒ 他端清除已落引擎，本地收敛清掉。
        if (readEntry(name)) { writeEntry(name, null, ''); repaint(); }
        return;
      }
      if (!r.ok) return;
      const j = await r.json().catch(() => null);
      const sha = j && typeof j.sha256 === 'string' ? j.sha256 : '';
      if (!sha) return;
      const e = readEntry(name);
      if (e && e.sha === sha) return; // 已收敛（含 v1 旧值补戳后的常态）
      // sha 异/缺：拉一次字节回填（引擎回字节；mime 以 Blob 嗅探为准，回落 jpeg）。
      const resp = await fetch(`/api/agents/${encodeURIComponent(name)}/avatar`, { headers: authHeaders() });
      if (!resp.ok) return;
      const buf = await resp.arrayBuffer();
      if (!buf.byteLength) return;
      const blob = new Blob([buf]);
      const dataUrl = await new Promise((resolve) => {
        const fr = new FileReader();
        fr.onload = () => resolve(String(fr.result || ''));
        fr.onerror = () => resolve('');
        fr.readAsDataURL(blob);
      });
      if (!dataUrl) return;
      writeEntry(name, dataUrl, sha);
      repaint();
    })
    .catch(() => { /* 离线/未落座：维持本地口径 */ })
    .finally(() => avatarSyncInFlight.delete(name));
}

/** 上传图降采样上限（px）：localStorage 配额友好（avatarCache.js 同款配额纪律）。 */
const MAX_DIM = 256;

/** 当前会话头身份 = 活动会话的 agent（root 会话恒 ROOT_AGENT_KEY 机制名）。 */
export function currentAgentName() {
  const active = state.sessions ? state.sessions.find((s) => s.id === state.activeSessionId) : null;
  return (active && active.agentName) || ROOT_AGENT_KEY;
}

// ── dock 中键头像读数面（中键改版批 · 作者令 2026-10-29 23:36）────────
// dock「对话」中键图标 = 给 agent 设置的头像（作者逐字）。读链 = 会话头
// paintSlot 同一本地条目（跨端 sha 收敛落同一键），零第二数据源。

/**
 * 当前 agent 的本地头像 dataURL（无 agentName 参数 = 当前会话 agent）。
 * @param {string} [agentName]
 * @returns {string} dataURL；无自定义头像 ⇒ `''`（调用方回落既有字形图标）。
 */
export function agentAvatarDataUrl(agentName) {
  const entry = readEntry(agentName || currentAgentName());
  return (entry && entry.dataUrl) || '';
}

/** 头像重绘订阅者（dock 中键等异地面）。paintSlot 是全部头像落地点的汇合
 *  （本机上传、跨端收敛回填、会话切换、identity 广播、重连对账都经它重绘）
 *  ——订阅者在每次重绘时重读 agentAvatarDataUrl() 即与 会话头 恒同像。 */
const avatarSubs = new Set();

/**
 * @param {() => void} fn
 * @returns {() => void} 退订函数
 */
export function onAgentAvatarChange(fn) {
  avatarSubs.add(fn);
  return () => avatarSubs.delete(fn);
}

function notifyAvatarSubs() {
  for (const fn of avatarSubs) {
    try { fn(); } catch { /* 订阅者异常不阻断会话头重绘 */ }
  }
}

/** 会话头显示名（与 sidebar.js updateHeaderSessionName 同一读链的本地镜像：
 *  root → rootName 单读点（未命名 = 空串，机制名永不作为显示回落）；
 *  非 root → 同一单读点 agentDisplayName（该 agent 自己的显示名）。 */
function headerLabel(agentName) {
  if (agentName === ROOT_AGENT_KEY) return isRootNamed() ? rootDisplayName() : '';
  return agentDisplayName(agentName);
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

/**
 * stand logo 回落节点（作者令 2026-10-31 22:50「agent没设置头像之前 用我们stand
 * 的logo」）。brand 双主题 picture（亮档 logoUrl / 暗档 logoDarkUrl），与手机层
 * 三面（chatChrome 会话头注入档 / .g2-msg-logo / dock .dock-center-logo）同货源
 * 同类——零新图。桌面腿 2026-11-01 补齐：原实现只落了手机层，桌面 paintSlot 的
 * 首字母牌/人形字形两档未覆盖（主 agent Spider 有名无头像 ⇒ 显示「S」首字母牌，
 * 作者实指不生效点）；今两端统一：无自定义头像一律回退 stand logo。
 * 类名 = `agent-avatar-stand-logo`（chatChrome 空态注入档同名 ⇒ 手机层守望见
 * logo 已在场即跳过注入，两端互不叠绘；尺寸档 chat.css 桌面 / mobileLayer.css 手机）。
 */
function standLogoNode() {
  const pic = document.createElement('picture');
  pic.className = 'agent-avatar-stand-logo';
  pic.innerHTML =
    `<source media="(prefers-color-scheme: dark)" srcset="${brand.logoDarkUrl}">` +
    `<img class="agent-avatar-logo-img" src="${brand.logoUrl}" alt="">`;
  return pic;
}

/**
 * 会话头头像槽重绘（export：mobile/chatChrome.js teardown 摘除注入档后归位重绘
 * ——注入档与自绘 logo 同类名，摘除会连自绘件一并拿走，归还桌面形态时必须补一
 * 次绘；桌面事件链照旧内部调用）。
 */
export function paintSlot() {
  notifyAvatarSubs(); // 先广播再自绘：订阅面（dock 中键）与会话头同帧同像
  if (!slot) return;
  const agentName = currentAgentName();
  slot.setAttribute('aria-label', t('profile.openAria'));
  slot.title = t('profile.openAria');
  const entry = readEntry(agentName);
  const dataUrl = (entry && entry.dataUrl) || '';
  // 收敛探针：本地缺值（跨端首次可见）或旧值未对过账（v1 裸值迁移）——节流内
  // 不重复发；强制对账走 agentIdentityChanged / 重连钩子。
  if (!dataUrl || !entry.sha) convergeAvatar(agentName, paintSlot, false);
  const img = dataUrl ? avatarImgNode(dataUrl) : null; // 复用池：同 dataURL 零重解码
  if (img) {
    img.className = 'agent-avatar-img';
    img.draggable = false;
    slot.replaceChildren(img);
    slot.classList.add('has-img');
    return;
  }
  // 无自定义头像 ⇒ stand logo（作者令 2026-10-31 22:50；原首字母牌/人形字形
  // 两档随桌面腿补齐一并退役——机制名不作显示回落的口径不变，只是回落件换了）。
  slot.classList.remove('has-img');
  slot.replaceChildren(standLogoNode());
}

// ── 智能体分区（.ap-pane 一件式；注入合并面板 memory-modal，见 memory.js）──

// HTMLInputElement（非 HTMLElement）：面板存 .type/.value/.placeholder 直写点
// （症状⑧批 touched-file checkJs 清零时补注——四处 TS2339 皆此根因）。
/** @type {HTMLInputElement|null} */
let nameInput = null;
/** @type {HTMLInputElement|null} */
let fileInput = null;
/** @type {HTMLButtonElement|null} 头像预览盒（点击 = 换头像，作者令②：无独立
 *  设置按钮；面板存 .type 直写点 —— checkJs 对位，同 nameInput 根因）。 */
let previewBox = null;
/** @type {HTMLElement|null} 分区根（locale 切换重刷文案用）。 */
let paneRoot = null;

/**
 * 构建智能体分区（一件式；memory.js 合并面板初始化时调用一次）。
 * 结构：头像预览盒（点击即换头像）+ 名称就地编辑行 + 说明行 + 隐藏 file input。
 */
export function buildProfilePane() {
  if (paneRoot) return paneRoot;
  const section = document.createElement('section');
  section.className = 'ap-pane';
  section.dataset.paneFace = 'agent';

  previewBox = document.createElement('button');
  previewBox.type = 'button';
  previewBox.className = 'ap-avatar-box';
  // 作者令②（面板合并批定形）：点击头像本体 = 换头像（整盒为按钮；aria 走
  // 「上传头像」既有键）——无独立上传/重置按钮；清除收敛走 meta 404 腿。
  previewBox.addEventListener('click', () => fileInput && fileInput.click());

  fileInput = document.createElement('input');
  fileInput.type = 'file';
  fileInput.accept = 'image/*';
  fileInput.hidden = true;
  fileInput.setAttribute('aria-hidden', 'true');
  fileInput.addEventListener('change', () => {
    const f = fileInput.files && fileInput.files[0];
    fileInput.value = '';
    if (!f || !f.type.startsWith('image/')) return;
    downscaleToFile(f).then(async (dataUrl) => {
      if (!dataUrl) return;
      const name = currentAgentName();
      const sha = await sha256OfDataUrl(dataUrl); // 写入即收敛态（引擎同算法同值）
      writeEntry(name, dataUrl, sha);
      localWriteAt.set(name, Date.now()); // 静默窗：自己的 PUT 在途期间不对账
      pushAvatar(name, dataUrl); // 引擎写穿：对端按 sha 对账/按 LWW 戳收敛
      paintSlot();
      paintPreview();
    }).catch(() => { /* 读取失败：维持现状 */ });
  });

  const nameRow = document.createElement('div');
  nameRow.className = 'ap-namerow';
  const nameLab = document.createElement('span');
  nameLab.className = 'ap-name-label';
  nameInput = document.createElement('input');
  nameInput.type = 'text';
  nameInput.className = 'ap-name-input';
  // 就地编辑：回车保存（写穿既有 display-name 机制），Esc 还原。
  nameInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); saveName(); nameInput.blur(); }
    else if (e.key === 'Escape') { fillName(); nameInput.blur(); }
  });
  nameInput.addEventListener('blur', () => { /* 失焦不保存 —— 回车是唯一保存动作（作者口径） */ });
  nameRow.append(nameLab, nameInput);

  const note = document.createElement('div');
  note.className = 'ap-note';

  section.append(previewBox, nameRow, note, fileInput);
  paneRoot = section;
  syncPaneLocale();
  fillName();
  paintPreview();
  return section;
}

/** 分区文案刷新（构建时 + locale-changed）。 */
function syncPaneLocale() {
  if (!paneRoot) return;
  const lab = paneRoot.querySelector('.ap-name-label');
  if (lab) lab.textContent = t('profile.nameLabel');
  if (previewBox) {
    previewBox.setAttribute('aria-label', t('profile.upload'));
    previewBox.title = t('profile.upload');
  }
  const note = paneRoot.querySelector('.ap-note');
  if (note) note.textContent = t('profile.note');
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
  const entry = readEntry(agentName);
  const dataUrl = (entry && entry.dataUrl) || '';
  if (!dataUrl || !entry.sha) convergeAvatar(agentName, paintPreview, false); // 同 paintSlot：缺值/未对账
  const img = dataUrl ? avatarImgNode(dataUrl) : null;
  if (img) {
    img.className = 'ap-avatar-img';
    previewBox.replaceChildren(img);
    previewBox.classList.add('has-img');
    return;
  }
  // 无自定义头像 ⇒ stand logo（作者令 2026-10-31 22:50；原首字母牌档退役，
  // 与会话头 paintSlot 同源回落——点击本体换头像的交互不变）。
  previewBox.classList.remove('has-img');
  previewBox.replaceChildren(standLogoNode());
}

function fillName() {
  if (!nameInput) return;
  nameInput.value = headerLabel(currentAgentName());
  nameInput.placeholder = t('profile.namePlaceholder');
}

/** 名称就地编辑保存（回车）：写穿既有 display-name 机制 —— root 与 onboarding
 *  renameEverywhere 同三写（override + agentsData 行 + 事件）；非 root 写 agentsData
 *  行 + 事件。空值 = 还原为机制名行（root：清 override，头名按未命名口径留空）。
 *  持久/跨设备腿（症状⑧）：非空值 fire-and-forget 写引擎 display 面（PUT
 *  /api/agents/:name/display-name → agents/<name>/agent.json displayName）——
 *  agentsData 行与 rootName 探针同源此文件，另一端下次 list/探针即收敛。失败
 *  静默：内存态已成立，服务端自纠不阻塞 UI。 */
function saveName() {
  if (!nameInput) return;
  const agentName = currentAgentName();
  const next = nameInput.value.trim();
  if (agentName === ROOT_AGENT_KEY) {
    state.rootDisplayName = next;
    const self = (state.agentsData || []).find((x) => x.name === ROOT_AGENT_KEY);
    if (self) self.displayName = next;
  } else {
    const row = (state.agentsData || []).find((a) => a.name === agentName);
    if (row) row.displayName = next;
  }
  if (next) {
    fetch(`/api/agents/${encodeURIComponent(agentName)}/display-name`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', ...authHeaders() },
      body: JSON.stringify({ displayName: next }),
    }).catch(() => { /* 离线/未落座：内存态照旧 */ });
  }
  window.dispatchEvent(new CustomEvent('nebflow-root-name-change'));
  paintSlot();
  paintPreview();
}

/** 打开智能体分区（会话头头像点击）：动态 import 断依赖环
 *  （memory.js 静态 import 本模块的 buildProfilePane；本模块只在事件时回调）。
 *  手机视口由 floatController 的 PANEL_FACE 从头像圆钮播二级液态 morph。 */
export function openProfilePanel() {
  import('./memory.js').then((m) => m.openMergedPanel('agent')).catch(() => { /* 模块未就绪：下次点击重试 */ });
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
  window.addEventListener('nebflow-session-change', () => { paintSlot(); paintPreview(); });
  window.addEventListener('nebflow-root-name-change', () => { paintSlot(); });
  window.addEventListener('locale-changed', () => {
    paintSlot();
    syncPaneLocale();
    fillName();
  });
  // identity-sync 批：引擎跨机收敛落地广播（另一台机器改了名字/头像，本端引擎
  // 收敛落盘后 wsHub 推 agentIdentityChanged）⇒ 强制对账（绕过节流）+ 全量重绘
  // （fillName/paintPreview 自带空值守卫——独立面板退役后 panel 变量已不存在）。
  window.addEventListener('nebflow-agent-identity-changed', () => {
    const name = currentAgentName();
    paintSlot();
    convergeAvatar(name, paintSlot, true);
    fillName();
    paintPreview();
    convergeAvatar(name, paintPreview, true);
  });
  // WS 重连 = 会话恢复：强制对账一次（离线期间他端的改名/换头像/清除在此收敛）。
  onReconnect(() => {
    const name = currentAgentName();
    paintSlot();
    convergeAvatar(name, paintSlot, true);
    fillName();
    paintPreview();
  });
}
