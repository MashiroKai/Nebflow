// presentationProfile.js — 选档器（认知二期产品批①；设计稿 §6.2/§6.3）
// 五档方案档案的 UI 面：设置面板选档 + 档参数逐项覆盖；档态经 WS 与引擎
// PresentationProfileStore（~/.stand/presentation-profiles.json）对账。
// 渲染消费面（human 过滤层折叠默认）走零依赖 profileState.js，不经本模块。

import { sendWs, onMessage, onReconnect } from './ws.js';
import { t } from './i18n.js';
import { setCurrentProfile } from './profileState.js';

// ── State ──────────────────────────────────────────────────────────────
// profile = 引擎下发全量：{ current, profiles:[{key,...,overrides?}], overrides }
let snapshot = null;

function applySnapshot(snap) {
  if (!snap || !Array.isArray(snap.profiles)) return;
  snapshot = snap;
  const cur = snap.profiles.find(p => p.key === snap.current) || snap.profiles[0];
  if (cur) setCurrentProfile(cur);
  // 校准尾注/重渲钩子：设置面板与 markdown 面自行订阅。
  window.dispatchEvent(new CustomEvent('nebflow-profile-changed', { detail: { key: cur && cur.key } }));
}

export function currentProfileKey() {
  return (snapshot && snapshot.current) || 'expert-minimal';
}

export function profileSnapshot() {
  return snapshot;
}

// ── WS ─────────────────────────────────────────────────────────────────

onMessage('presentationProfileData', (msg) => applySnapshot(msg.profile));
onMessage('presentationProfileChanged', (msg) => applySnapshot(msg.profile));

export function requestProfile() {
  try { sendWs({ type: 'getPresentationProfile' }); } catch (_) { /* WS 未就绪：下次 reconnect 补 */ }
}

onReconnect(requestProfile);

function selectProfile(key) {
  try { sendWs({ type: 'setPresentationProfile', profile: key }); } catch (_) {}
}

function setOverride(profileKey, param, value) {
  try { sendWs({ type: 'setProfileParamOverride', profile: profileKey, param, value }); } catch (_) {}
}

// ── Settings section ───────────────────────────────────────────────────

// 可覆盖参数（引擎 validateOverride 同表；key/teachingAllowed 不可覆盖）。
const OVERRIDABLE = [
  { param: 'maxLines', kind: 'number' },
  { param: 'keyNumbers', kind: 'number' },
  { param: 'foldDefault', kind: 'enum', values: ['fold', 'all-fold', 'strong', 'anomaly-top', 'expand-findings'] },
  { param: 'imageDensity', kind: 'enum', values: ['high', 'medium', 'low'] },
  { param: 'reminderCadence', kind: 'enum', values: ['single', 'aggregate', 'quiet'] },
  { param: 'supervision', kind: 'enum', values: ['fine', 'weekly', 'coarse', 'exception'] },
  { param: 'pendingStyle', kind: 'enum', values: ['single', 'batch', 'none', 'blocking', 'deadline'] }
];

/** 设置面板区块 HTML（renderSettings 内联点；样式复用 settings-row/cfg-*）。 */
export function renderProfileSection() {
  const snap = snapshot;
  const profiles = (snap && snap.profiles) || [];
  const cur = (snap && snap.current) || 'expert-minimal';
  const options = profiles.map(p =>
    `<option value="${p.key}"${p.key === cur ? ' selected' : ''}>${t('profile.name.' + p.key)}</option>`
  ).join('');
  const curDef = profiles.find(p => p.key === cur);
  const ov = (snap && snap.overrides && snap.overrides[cur]) || {};

  const overrideRows = OVERRIDABLE.map(({ param, kind, values }) => {
    const base = curDef ? curDef[param] : '';
    const overridden = ov[param] !== undefined;
    const display = overridden ? ov[param] : String(base);
    // 数值上界跟引擎校验面（PresentationProfile.MaxLinesBound/KeyNumbersBound）：
    // maxLines ≤20、keyNumbers ≤9——此前两框共用 max=20，keyNumbers 10..20 会被
    // 引擎拒（人话批 2026-10-31 审计顺手修正，纯属性订正，不动生效逻辑）。
    const numMax = param === 'keyNumbers' ? 9 : 20;
    const control = kind === 'number'
      ? `<input type="number" class="cfg-input profile-ov-input" data-param="${param}" value="${display}" min="1" max="${numMax}" style="width:64px">`
      : `<select class="cfg-select profile-ov-input" data-param="${param}" style="width:auto">` +
        values.map(v =>
          `<option value="${v}"${v === display ? ' selected' : ''}>${t('profile.param.' + param + '.' + v)}</option>`
        ).join('') + '</select>';
    // 每项一行「改了会怎样」人话（profile.hint.*）：一句作用面 + 改前改后例子；
    // 未接线项的 hint 内文已如实标注（审计：七参数全部暂无行为消费面）。
    const hint = t('profile.hint.' + param);
    return `<div class="profile-ov-item">
      <div class="settings-row">
        <span class="settings-label">${t('profile.param.' + param)}${overridden ? ' <em>' + t('profile.overridden') + '</em>' : ''}</span>
        ${control}
        ${overridden ? `<button class="cfg-btn profile-ov-reset" data-param="${param}" type="button">${t('profile.reset')}</button>` : ''}
      </div>
      ${hint ? `<div class="cfg-hint profile-ov-hint">${hint}</div>` : ''}
    </div>`;
  }).join('');

  const calNote = t('profile.calibrationNote');
  // 作者令 2026-10-30 20:13：选档主入口进新手引导；设置面板保留为次级入口
  // （选档能力不撤，只明示主路径）。
  const secondaryHint = t('profile.secondaryHint');
  return `<div class="settings-section" id="profile-section">
    <div class="settings-section-title">${t('profile.sectionTitle')}</div>
    <div class="settings-row">
      <span class="settings-label">${t('profile.current')}</span>
      <select class="cfg-select" id="cfg-profile-select" style="width:auto">${options}</select>
    </div>
    <div class="cfg-hint" id="profile-desc">${t('profile.desc.' + cur)}</div>
    <div class="cfg-hint">${secondaryHint}</div>
    <div class="settings-section-title" style="margin-top:10px">${t('profile.overridesTitle')}</div>
    <div class="cfg-hint">${t('profile.overridesIntro')}</div>
    ${overrideRows}
    <div class="cfg-hint">${t('profile.wiringNote')}</div>
    <div class="cfg-hint">${calNote}</div>
  </div>`;
}

/** 区块事件绑定（bindSettingsEvents 内调用一次）。 */
export function bindProfileEvents() {
  const select = /** @type {HTMLSelectElement | null} */ (document.getElementById('cfg-profile-select'));
  if (select) {
    select.addEventListener('change', () => {
      selectProfile(select.value);
      const desc = document.getElementById('profile-desc');
      if (desc) desc.textContent = t('profile.desc.' + select.value);
    });
  }
  document.querySelectorAll('.profile-ov-input').forEach(el => {
    const input = /** @type {HTMLInputElement | HTMLSelectElement} */ (el);
    input.addEventListener('change', () => {
      const param = input.dataset.param;
      setOverride(currentProfileKey(), param, input.value);
    });
  });
  document.querySelectorAll('.profile-ov-reset').forEach(el => {
    const btn = /** @type {HTMLElement} */ (el);
    btn.addEventListener('click', () => {
      setOverride(currentProfileKey(), btn.dataset.param, null);
    });
  });
}

/** 引擎覆盖回执到达后，设置面板开着 ⇒ 原位重渲（覆盖标记/复位钮即时呈现）。 */
window.addEventListener('nebflow-profile-changed', () => {
  const section = document.getElementById('profile-section');
  if (!section) return;
  const scroll = section.closest('#settings-content')?.scrollTop || 0;
  section.outerHTML = renderProfileSection();
  bindProfileEvents();
  const host = document.getElementById('settings-content');
  if (host) host.scrollTop = scroll;
});
