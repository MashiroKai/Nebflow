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
for (const group of ['account', 'general', 'models', 'runtime', 'advanced', 'about']) check(sidebar.includes(`data-panel-group="${group}"`), `missing settings group ${group}`);
if (errors.length) {
  for (const error of errors) console.error('FAIL panel-uniform:', error);
  process.exitCode = 1;
} else console.log('PASS panel-uniform: registered identity, contact controls, duplicate guard and six settings groups');
