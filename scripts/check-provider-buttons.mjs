// Provider-editor action geometry contract. Static guard; browser layout is tested separately.
import { readFileSync } from 'node:fs';
const css = readFileSync(process.env.PROVIDER_BUTTON_CSS || 'src/main/resources/web/css/sidebar.css', 'utf8');
const form = readFileSync('src/main/resources/web/js/sidebar/providersUi.js', 'utf8');
const extra = readFileSync('src/main/resources/web/js/sidebar/imagegenTestButton.js', 'utf8');
const block = css.match(/#cfg-modal \.cfg-modal-actions \.cfg-btn\s*\{([^}]*)\}/)?.[1] || '';
const rules = [
  ['registered action size: md', /min-width:\s*var\(--mtap,\s*44px\)/.test(block) && /min-height:\s*var\(--mtap,\s*44px\)/.test(block)],
  ['single line', /white-space:\s*nowrap/.test(block)],
  ['family padding', /padding:\s*0\s+var\(--ds-space-3\)/.test(block)],
  ['family font', /font-size:\s*var\(--ds-fs-btn\)/.test(block)],
  ['family radius', /border-radius:\s*var\(--ds-radius-pill\)/.test(block)],
  ['responsive action grid', /#cfg-modal \.cfg-modal-actions\s*\{[^}]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/.test(css)],
  ['cancel and save registered', /class="cfg-btn cfg-btn-cancel"/.test(form) && /class="cfg-btn cfg-btn-save"/.test(form)],
  ['two image actions registered', (extra.match(/class="cfg-btn" id="btn-(?:test-imagegen|imagegen-generate)"/g) || []).length === 2],
];
for (const [name, ok] of rules) console.log(`${ok ? 'PASS' : 'FAIL'} ${name}`);
const failures = rules.filter(([, ok]) => !ok).length;
console.log(`provider-buttons: ${rules.length} checks, ${failures} violations; scope: editor action row only`);
if (failures) process.exitCode = 1;
