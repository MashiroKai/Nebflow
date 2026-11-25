#!/usr/bin/env node
// 标准：侧栏面板顶部标准__20261125_191104.md。严格范围：消息和收藏；其他面板待统一。
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
const root = fileURLToPath(new URL('..', import.meta.url));
const web = join(root, 'src/main/resources/web');
const read = p => readFileSync(join(web, p), 'utf8');
const errors = [];
for (const [file, patterns] of [
  ['index.html', [/id="panel-messages"/, /class="panel-header"/, /class="panel-title"/]],
  ['js/panelSearch.js', [/fm-msg-search panel-top-search/, /buildFieldShell\(input/]],
  ['js/canvasFavoritesPage.js', [/cfp-title panel-title/, /classList\.add\('panel-header'\)/, /classList\.add\('panel-top-search'\)/, /buildFieldShell\(input/]],
]) for (const pattern of patterns) if (!pattern.test(read(file))) errors.push(`${file}: 缺少 ${pattern}`);
const dir = join(web, 'css');
for (const file of readdirSync(dir).filter(x => x.endsWith('.css'))) {
  const text = read(`css/${file}`).replace(/\/\*[\s\S]*?\*\//g, '');
  // 匹配受控面板的专有选择器，禁止硬编码几何；共享类由下方精确契约限制。
  for (const m of text.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const selector = m[1], body = m[2];
    if (!/(?:#panel-(?:favorites|messages).*?(?:cfp-bar|cfp-title|cfp-search|fm-msg-search|panel-header|panel-title|sf-field)|\.cfp-(?:bar|title|search)(?:\s|\{|,|$))/s.test(selector)) continue;
    if (file === 'canvasFavorites.css' && !/#panel-favorites/.test(selector)) continue; // 手机页面另行管理
    for (const declaration of body.split(';')) {
      if (/^\s*(?:border(?:-[\w-]+)?-radius|padding(?:-[\w-]+)?|margin(?:-[\w-]+)?|gap|font-size)\s*:\s*[^;]*\b\d+(?:\.\d+)?(?:px|rem|em)\b/i.test(declaration)) errors.push(`css/${file}: ${selector.trim()} ${declaration.trim()}`);
    }
  }
}
const shared = read('css/searchField.css');
if (!/\.panel-top-search\s*\{[^}]*padding:\s*var\(--ds-space-2\) var\(--ds-space-2\) 0\s*;/s.test(shared)) errors.push('css/searchField.css: 共享搜索区契约变更');
if (errors.length) { console.error(`sidebar-top: RED\n${errors.join('\n')}`); process.exit(1); }
console.log('sidebar-top: OK；待统一清单：联系人、待办、文件树等其他面板');
