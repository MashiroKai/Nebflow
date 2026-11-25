#!/usr/bin/env node
// 标准：侧栏面板顶部标准__20261125_191104.md。严格范围：消息和收藏；其他面板待统一。
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
const root = process.env.SIDEBAR_TOP_ROOT || fileURLToPath(new URL('..', import.meta.url));
const web = join(root, 'src/main/resources/web');
const read = p => readFileSync(join(web, p), 'utf8');
const errors = [];
for (const [file, patterns] of [
  ['index.html', [/id="panel-messages"/, /class="panel-header"/, /class="panel-title"/]],
  ['js/panelSearch.js', [/fm-msg-search panel-top-search/, /buildFieldShell\(input/]],
  ['js/canvasFavoritesPage.js', [/cfp-title panel-title/, /classList\.add\('panel-header'\)/, /classList\.add\('panel-top-search'\)/, /buildFieldShell\(input/]],
]) for (const pattern of patterns) if (!pattern.test(read(file))) errors.push(`${file}: 缺少 ${pattern}`);
// 旧文件树标题只允许逐条原样存在；基线随位置、值变化立即报红，不自动更新。
const baseline = [
  { file: 'split.css', line: 10, selector: '.explorer-header', prop: 'padding', value: 'var(--ds-space-block-y) 14px var(--ds-space-1)', reason: '文件树标题旧水平间距，待作者决定是否统一' },
];
const seen = new Set();
const dir = join(web, 'css');
for (const file of readdirSync(dir).filter(x => x.endsWith('.css'))) {
  const text = read(`css/${file}`).replace(/\/\*[\s\S]*?\*\//g, '');
  // 匹配受控面板的专有选择器，禁止硬编码几何；共享类由下方精确契约限制。
  for (const m of text.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const selector = m[1].trim(), body = m[2];
    // 受控面板下的通配和属性选择器可能绕开类名检查：明确禁止为其另写顶部造型。
    if (/#panel-(?:favorites|messages|contacts|sessions)\b[^,{]*(?:\*|\[)/.test(selector) && /(?:border(?:-[\w-]+)?-radius|padding(?:-[\w-]+)?|margin(?:-[\w-]+)?|(?:row-|column-)?gap|font-size|color|background(?:-color)?)\s*:/i.test(body)) errors.push(`泛型侧栏选择器不可另画顶部样式 css/${file}: ${selector}`);
    const legacy = /(?:\.explorer-(?:header|title|search-row|search-input)|#explorer-search(?:-row)?|#fm-contacts-body\s+\.fm-search(?:-block)?)(?![\w-])/.test(selector);
    if (legacy) for (const declaration of body.split(';')) {
      const d = /^\s*(border(?:-[\w-]+)?-radius|padding(?:-[\w-]+)?|margin(?:-[\w-]+)?|(?:row-|column-)?gap|font-size|color|background(?:-color)?)\s*:\s*(.+?)\s*$/i.exec(declaration);
      if (!d || !/(?:\d+(?:\.\d+)?(?:px|rem|em|vh|vw|%)|#[\da-f]{3,8}\b|\brgba?\(|\bhsla?\()/i.test(d[2])) continue;
      const line = text.slice(0, m.index + m[1].indexOf(selector)).split('\n').length;
      const index = baseline.findIndex(b => b.file === file && b.line === line && b.selector === selector && b.prop === d[1] && b.value === d[2]);
      if (index < 0) errors.push(`未登记的侧栏顶部样式 css/${file}: ${selector} ${d[1]}: ${d[2]}`);
      else seen.add(index);
    }
    if (!/(?:#panel-(?:favorites|messages).*?(?:cfp-bar|cfp-title|cfp-search|fm-msg-search|panel-header|panel-title|sf-field)|\.cfp-(?:bar|title|search)(?:\s|\{|,|$))/s.test(selector)) continue;
    if (file === 'canvasFavorites.css' && !/#panel-favorites/.test(selector)) continue; // 手机页面另行管理
    for (const declaration of body.split(';')) {
      const geometry = /^\s*(?:border(?:-[\w-]+)?-radius|padding(?:-[\w-]+)?|margin(?:-[\w-]+)?|(?:row-|column-)?gap|font-size)\s*:\s*(.+)$/i.exec(declaration);
      if (geometry && /\d+(?:\.\d+)?(?:px|rem|em|vh|vw|%)/i.test(geometry[1])) errors.push(`css/${file}: ${selector.trim()} ${declaration.trim()}`);
      if (/^\s*(?:color|background(?:-color)?|border(?:-\w+)?-color)\s*:\s*[^;]*(?:#[\da-f]{3,8}\b|rgba?\(|hsla?\()/i.test(declaration)) errors.push(`css/${file}: ${selector} ${declaration.trim()}`);
    }
  }
}
for (const [index, b] of baseline.entries()) if (!seen.has(index)) errors.push(`基线缺失或位置改变: css/${b.file} ${b.selector} ${b.prop}: ${b.value}`);
// 文件树搜索内联造型为已知旧账：逐行精确定位，改变或移动都需要重新裁定。
const explorerLines = read('js/explorer.js').split('\n');
for (const [line, value] of [
  [1063, "  input.setAttribute('style', 'flex:1 1 auto;min-width:0;padding:var(--ds-space-1) var(--ds-space-2);border-radius:var(--ds-radius-input);'"],
  [1064, "    + 'border:1px solid rgba(255,255,255,0.14);background:rgba(255,255,255,0.06);'"],
  [1065, "    + 'color:inherit;font:var(--ds-fs-hint)/1.4 inherit;');"],
]) if (explorerLines[line - 1] !== value) errors.push(`js/explorer.js:${line}: 文件树搜索旧账发生改动或移动`);
// 已知装配点不得注入顶部内联造型；不声称可静态证明任意跨模块 DOM 写入。
for (const [file, start, end] of [
  ['js/panelSearch.js', 'function buildMsgSearch()', 'const status ='],
  ['js/canvasFavoritesPage.js', 'function buildPage(', '// 分类 tab'],
  ['js/canvasFavoritesPage.js', 'export function mountFavoritesSidebar()', 'void syncFavoritesFromEngine()'],
]) {
  const source = read(file), begin = source.indexOf(start), finish = source.indexOf(end, begin);
  if (begin < 0 || finish < 0) { errors.push(`${file}: 顶部注入点定位失败`); continue; }
  const section = source.slice(begin, finish);
  if (/\.style\s*(?:\.|\[|=)|\.setAttribute\s*\(\s*['"]style['"]|\.style\.setProperty\s*\(/.test(section)) errors.push(`${file}: 已知顶部装配点禁止内联样式注入`);
}
const shared = read('css/searchField.css');
if (!/\.panel-top-search\s*\{[^}]*padding:\s*var\(--ds-space-2\) var\(--ds-space-2\) 0\s*;/s.test(shared)) errors.push('css/searchField.css: 共享搜索区契约变更');
if (errors.length) { console.error(`sidebar-top: RED\n${errors.join('\n')}`); process.exit(1); }
console.log('sidebar-top: OK；待统一清单：联系人、待办、文件树等其他面板');
