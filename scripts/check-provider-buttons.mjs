// Provider-editor + settings action geometry contract. Static guard; browser layout is tested separately.
//
// 一个 md 档，两族同取（作者令 2026-11-18：「底部的这些按钮的设计 大小有些失衡，
// 包括设置页内部也是……这些按钮的样式 大小应该要有极致的控制 统一的要求」）：
//   · 服务商编辑窗动作族 = css/sidebar.css 的 `#cfg-modal .cfg-modal-actions .cfg-btn`
//   · 设置页动作族     = css/settingsRows.css 的 `.set-btn`（几何）+ css/stand-tokens.css 的 --set-*
// 本闸钉「两族取值逐项相等」，任一族偷偷换档即红。禁新开第二套：只允许两族取同一批令牌。
import { readFileSync } from 'node:fs';
const css = readFileSync(process.env.PROVIDER_BUTTON_CSS || 'src/main/resources/web/css/sidebar.css', 'utf8');
const TOKENS = readFileSync(process.env.SETTINGS_BUTTON_TOKENS || 'src/main/resources/web/css/stand-tokens.css', 'utf8');
const ROWS = readFileSync(process.env.SETTINGS_BUTTON_CSS || 'src/main/resources/web/css/settingsRows.css', 'utf8');
const form = readFileSync('src/main/resources/web/js/sidebar/providersUi.js', 'utf8');
const extra = readFileSync('src/main/resources/web/js/sidebar/imagegenTestButton.js', 'utf8');
const sidebarSrc = readFileSync('src/main/resources/web/js/sidebar.js', 'utf8');

const token = (src, name) => (src.match(new RegExp(`${name}\\s*:\\s*([^;]+);`))?.[1] || '').trim();
const block = css.match(/#cfg-modal \.cfg-modal-actions \.cfg-btn\s*\{([^}]*)\}/)?.[1] || '';
const setBtn = ROWS.replace(/\/\*[\s\S]*?\*\//g, ' ').match(/(?:^|\})\s*\.set-btn\s*\{([^}]*)\}/)?.[1] || '';

// md 档取值单源（两族共用的那一档）。
const MD = { hit: 'var(--mtap, 44px)', padX: 'var(--ds-space-3)', font: 'var(--ds-fs-btn)', radius: 'var(--ds-radius-pill)' };
const esc = (s) => s.replace(/[()]/g, '\\$&');
const setBtnH = token(TOKENS, '--set-btn-h');
const setBtnPadX = token(TOKENS, '--set-btn-pad-x');

const rules = [
  // ── 服务商编辑窗动作族 ────────────────────────────────────────────────
  ['provider action size: md', new RegExp(`min-width:\\s*${esc(MD.hit)}`).test(block) && new RegExp(`min-height:\\s*${esc(MD.hit)}`).test(block)],
  ['provider action single line', /white-space:\s*nowrap/.test(block)],
  ['provider action family padding', new RegExp(`padding:\\s*0\\s+${esc(MD.padX)}`).test(block)],
  ['provider action family font', new RegExp(`font-size:\\s*${esc(MD.font)}`).test(block)],
  ['provider action family radius', new RegExp(`border-radius:\\s*${esc(MD.radius)}`).test(block)],
  ['responsive action grid', /#cfg-modal \.cfg-modal-actions\s*\{[^}]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/.test(css)],
  ['cancel and save registered', /class="cfg-btn cfg-btn-cancel"/.test(form) && /class="cfg-btn cfg-btn-save"/.test(form)],
  ['two image actions registered', (extra.match(/class="cfg-btn" id="btn-(?:test-imagegen|imagegen-generate)"/g) || []).length === 2],

  // ── 设置页动作族：同一 md 档 ──────────────────────────────────────────
  ['settings action tier = md hit box', setBtnH === MD.hit],
  ['settings action pad token = md', setBtnPadX === MD.padX],
  ['settings action uses tier height token', /height:\s*var\(--set-btn-h\)/.test(setBtn) && /min-height:\s*var\(--set-btn-h\)/.test(setBtn)],
  ['settings action family padding', /padding:\s*0\s+var\(--set-btn-pad-x\)/.test(setBtn)],
  ['settings action family font', new RegExp(`font-size:\\s*${esc(MD.font)}`).test(setBtn)],
  ['settings action family radius', new RegExp(`border-radius:\\s*${esc(MD.radius)}`).test(setBtn)],
  ['settings action single line', /white-space:\s*nowrap/.test(setBtn)],
  ['settings action height not hand-written px', !/\.set-btn\s*\{[^}]*[^-]height:\s*\d+px/.test(setBtn)],
  ['settings behaviour buttons registered', (sidebarSrc.match(/class="cfg-btn[^"]*\bset-btn\b"/g) || []).length >= 4],
];
for (const [name, ok] of rules) console.log(`${ok ? 'PASS' : 'FAIL'} ${name}`);
const failures = rules.filter(([, ok]) => !ok).length;
console.log(`provider-buttons: ${rules.length} checks, ${failures} violations; scope: editor + settings action families (one md tier)`);
if (failures) process.exitCode = 1;
