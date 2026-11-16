#!/usr/bin/env node
// Scoped structural guard for the migrated chat footer and composer primitives.
// D1/D2/D3 CSS remains frozen pending author selection; existing token and
// glass rules are additionally guarded by the frontend profile's other checks.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
const web = fileURLToPath(new URL('../src/main/resources/web/', import.meta.url));
const read = p => readFileSync(web + p, 'utf8');
const requirements = [
  ['js/chat/durationBadges.js', /chatFooter\(\)/, /chatFooterDivider\(\)/, /chatFooterTime\(\)/],
  ['js/messages.js', /chatFooter\('fm-msg-meta'\)/, /chatFooterDivider\('fm-msg-divider'\)/, /chatFooterTime\('fm-msg-time'/, /bubbleClass\(/],
  ['js/messages/fmComposer.js', /chatComposerClass\('messages'\)/],
  ['js/askDock.js', /chatComposerClass\('ask'\)/, /askBubbleClass\(m\.role\)/],
  ['js/main.js', /from '\.\/chatSearch\.js'/],
  ['js/chatUi.js', /'ask-bub nf-glass '/, /'ask-input nf-glass'/, /'fm-input-bar nf-glass'/],
];
const errors = [];
for (const [path, ...patterns] of requirements) {
  const text = read(path);
  for (const pattern of patterns) if (!pattern.test(text)) errors.push(`${path}: missing ${pattern}`);
}
for (const [path, pattern] of [
  ['js/chat/durationBadges.js', /className\s*=\s*['"]duration-badge(?:-time|-divider)?['"]/],
  ['js/messages/fmComposer.js', /['"]fm-input-bar nf-glass['"]/],
  ['js/askDock.js', /class:\s*['"](?:ask-input nf-glass|ask-bub nf-glass)/],
]) if (pattern.test(read(path))) errors.push(`${path}: duplicated migrated primitive`);
if (errors.length) { console.error(errors.join('\n')); process.exitCode = 1; }
else console.log('chat-ui: migrated footer/composer/bubble consumers use shared primitives; D1-D3 visual differences remain frozen');
