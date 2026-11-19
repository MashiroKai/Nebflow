#!/usr/bin/env node
// Focused source contract: static checks do not establish rendered geometry.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..');
const read = p => readFileSync(resolve(root, p), 'utf8');
const web = 'src/main/resources/web/';
const profile = read(web + 'js/profileFace.js');
const me = read(web + 'js/mobile/meFace.js');
const sidebar = read(web + 'js/sidebar.js');
const design = read(web + 'css/design-system.css');
const model = read(web + 'js/modelPanel.js');
const neblink = read(web + 'js/neblink.js');
const activity = read(web + 'js/activityBar.js');
const modal = read(web + 'css/modal.css');
const errors = [];
const check = (condition, message) => { if (!condition) errors.push(message); };
check(/mf-me-card sp-identity/.test(me) && /mf-me-avatar-frame huge/.test(me), 'personal identity must use shared centered structure and huge avatar');
// Both baseline and current implementation use 40px; OpenSpec specifies the shared
// centered structure, not a pixel size. 3fc6173f4 intentionally restored 40px.
check(/pf-hero sp-identity/.test(profile) && (profile.match(/avatarNode\([^\n]+, 40\)/g) || []).length === 2, 'profile identities must use shared structure and 40px avatars');
check(/\.sp-identity\s*\{[^}]*flex-direction:\s*column;[^}]*align-items:\s*center;/s.test(design), 'shared identity column must center');
check(/pf-edit-bar ds-chrome-band/.test(profile) && /g2-fab pf-edit-submit/.test(profile) && /g2-fab pf-edit-cancel/.test(profile), 'registered profile bottom controls must use canvas outlets');
check(/bar\.hidden = !dirty/.test(profile) && /save\.hidden = true/.test(profile), 'pending actions only; no duplicate inline save');
check(/if \(!isMobileLayer\(\)\)\s*\{[^}]*profile-face-close/s.test(profile), 'mobile back and desktop close must not duplicate');
for (const group of ['general', 'models', 'runtime', 'advanced', 'about']) check(sidebar.includes(`data-panel-group="${group}"`), `missing settings group ${group}`);
// 账户/账号栏整件退役（作者令 2026-11-18）：「设置页不得出现账户栏/账号卡」的源码级判据。
// 只判代码，不判注释（退役理由注释里保留旧选择器名以留档）⇒ 先剥注释再测。
const stripComments = (src) => src
  .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
  .replace(/^(\s*)\/\/.*$/gm, '$1');
const sidebarCode = stripComments(sidebar);
const meCode = stripComments(me);
const neblinkCode = stripComments(neblink);
const messagesCode = stripComments(read(web + 'js/messages.js'));
check(!/data-panel-group="account"/.test(sidebarCode), 'settings must not render the account section');
check(!/accountCardHTML|neblinkSettingsHTML/.test(sidebarCode), 'settings must not render the account card');
// 同一判据覆盖另外两个消费面：个人主页底部重复栏 + 账号卡模块本体 + 设置页设备槽。
check(!/accountCardHTML|neblinkSettingsHTML|mf-account-card/.test(meCode), 'personal home must not mount or render the retired account bar');
check(!/export function (accountCardHTML|neblinkSettingsHTML|bindNeblinkEvents)\b/.test(neblinkCode), 'retired account-card render/bind API must stay unexported');
check(!/neblink-account-ro|neblink-login-section|neblink-switch-btn|neblink-logged-out-hint|neblink-kicked-notice/.test(neblinkCode), 'neblink.js must not emit retired account-card DOM');
check(!/settings-devices-slot|settingsDevicesHTML|mountSettingsDevices|settingsDeviceRowHTML/.test(messagesCode), 'settings device slot/cards must stay retired');
check(/settingsModelCard\(roleLabel\(role\)/.test(model) && /settingsModelCard\(t\('model.smallLlm'\)/.test(model) && /settingsModelCard\(t\(face.titleKey\)/.test(model), 'role, small model and image generation must share settingsModelCard');
check(/renderSwitchGroups\(list, \{ settings: true \}\)/.test(model) && /mp-add-select mp-settings-role-model/.test(model), 'both additional settings must use full-width role controls');
check(/\.mp-settings-role-model\s*\{[^}]*width:\s*100%/s.test(modal), 'settings model controls must fill their card');
check(!/id="neblink-logout"|querySelector\('#neblink-logout'\)/.test(neblink), 'settings must not render or bind logout');
check(/openEndSessionHandoff\(false\)/.test(activity), 'account menu must retain logout');
if (errors.length) {
  for (const error of errors) console.error('FAIL panel-uniform:', error);
  process.exitCode = 1;
} else console.log('PASS panel-uniform: registered identity, contact controls, five settings groups (no account bar) and shared model cards');
