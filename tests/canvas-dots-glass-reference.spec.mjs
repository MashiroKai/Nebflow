// The mobile pagination capsule is the measured reference. These checks pin
// both the literal source values and the reference's token-consumption chain.
// This is deliberately separate from the legacy global glass contract: it does
// not assert that other surfaces have already migrated.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const root = fileURLToPath(new URL('..', import.meta.url));
const css = join(root, 'src/main/resources/web/css');
const read = name => readFileSync(join(css, name), 'utf8');
const sources = {
  tokens: read('stand-tokens.css'),
  design: read('design-system.css'),
  mobile: read('mobileLayer.css'),
  glass: read('glass.css'),
};
function validate(s) {
  const rule = (text, selector) => {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const match = text.match(new RegExp(escaped + '\\s*\\{([^}]*)\\}'));
    assert.ok(match, `missing rule ${selector}`);
    return match[1];
  };
  const decl = (text, name, value) => {
    const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const values = [...text.matchAll(new RegExp('(?:^|[;{\\n])\\s*' + escaped + '\\s*:\\s*([^;]+);', 'g'))].map(m => m[1].trim());
    assert.ok(values.length, `missing ${name}`);
    assert.ok(values.every(v => v === value), `${name}: ${values.join(', ')} != ${value}`);
  };
  decl(s.design, '--ds-wash-16', 'rgb(128 128 128 / 0.16)');
  decl(s.design, '--ds-shadow-dots', '0 2px 10px rgb(16 20 30 / 0.10)');
  decl(s.tokens, '--nf-glass-input-filter', 'blur(24px) saturate(1.15)');
  for (const [name, value] of Object.entries({
    '--nf-glass-dots-bg': 'var(--ds-wash-16)',
    '--nf-glass-dots-border': 'var(--glass-border)',
    '--nf-glass-dots-shadow': 'var(--ds-shadow-dots)',
    '--nf-glass-dots-radius': 'var(--ds-radius-pill)',
    '--nf-glass-dots-filter': 'var(--nf-glass-input-filter)',
  })) decl(s.tokens, name, value);
  const dots = rule(s.mobile, 'body.mobile-layer .mf-dots');
  for (const [name, value] of Object.entries({
    'background': 'var(--nf-glass-dots-bg)',
    'border': '1px solid var(--nf-glass-dots-border)',
    'box-shadow': 'var(--nf-glass-dots-shadow)',
    'border-radius': 'var(--nf-glass-dots-radius)',
  })) decl(dots, name, value);
  assert.match(s.glass, /body\.mobile-layer \.mf-dots\s*,/);
  assert.match(s.glass, /backdrop-filter:\s*var\(--nf-glass-input-filter\)\s*;/);
}
validate(sources);
console.log('PASS reference token chain and pinned source values');
for (const [label, source, from, to] of [
  ['alpha drift', 'design', 'rgb(128 128 128 / 0.16)', 'rgb(128 128 128 / 0.17)'],
  ['shadow drift', 'design', '0 2px 10px rgb(16 20 30 / 0.10)', '0 2px 12px rgb(16 20 30 / 0.10)'],
  ['blur drift', 'tokens', '--nf-glass-input-filter: blur(24px) saturate(1.15);', '--nf-glass-input-filter: blur(25px) saturate(1.15);'],
  ['bare background', 'mobile', 'background: var(--nf-glass-dots-bg);', 'background: rgba(128, 128, 128, .16);'],
]) {
  assert.ok(sources[source].includes(from), `negative fixture missing: ${label}`);
  const mutant = { ...sources, [source]: sources[source].replace(from, to) };
  assert.throws(() => validate(mutant), assert.AssertionError, `negative control escaped: ${label}`);
  console.log(`PASS negative control rejected: ${label}`);
}
