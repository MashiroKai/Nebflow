const fs = require('node:fs');
const assert = require('node:assert/strict');
const path = require('node:path');
const root = process.env.TERM_TEST_ROOT || '.';
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const term = read('src/main/resources/web/js/termTip.js');
const card = read('src/main/resources/web/js/documentTermCard.js');
const css = read('src/main/resources/web/css/split.css');
const mode = process.argv[2];
if (!mode || mode === 'structure') {
  assert.match(term, /showDocumentTermCard\(/, '词条必须使用文档卡');
  assert.doesNotMatch(term, /term-tip-close/, '不得保留方形关闭按钮');
  assert.doesNotMatch(css, /\.nf-term-tip\s*\{/, '不得保留旧词条卡样式');
  console.log('结构通过');
}
if (!mode || mode === 'button') {
  assert.match(card, /↗ 打开原词条/, '词条按钮文案');
  assert.match(card, /className = 'document-term-open'/, '复用文档卡按钮');
  assert.match(css, /\.document-term-open\s*\{[^}]*width:\s*100%;[^}]*background:\s*var\(--color-frame-text-bright\)/s, '按钮须通栏黑色');
  console.log('按钮通过');
}
